package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/// 활력징후 스냅샷 — 오늘 값과 최근 28일 개인 기준선. iOS `BatteryEngine.swift` 이식.
///
/// 워치가 주로 수면 중에 기록하는 값들이다. 기준선은 오늘을 제외한
/// 일평균의 평균으로, "내 평소 범위" 개념이다.
///
/// iOS와의 차이 (계획서 §2.2):
/// - hrr 없음 — Health Connect에 심박 회복(HRR) 레코드 타입이 없다.
/// - wristTempC → skinTempC — HC `SkinTemperatureRecord`(갤럭시 워치 피부 온도) 대응.
///   산식은 기준선 대비 상대 변화라 그대로 쓴다.
data class VitalsSnapshot(
    val hrvMs: Reading? = null,            // 심박 변이도 RMSSD (ms) — 높을수록 회복 (iOS는 SDNN — 기준선 대비 상대 변화 산식이라 교체 허용, 계획서 오픈 이슈 #4)
    val restingHR: Reading? = null,        // 안정 심박 (bpm) — 낮을수록 회복
    val respiratoryRate: Reading? = null,  // 수면 중 호흡수 (회/분) — 이탈 시 감점
    val skinTempC: Reading? = null,        // 수면 중 피부 온도 (°C) — 상승 시 감점
    val sleepHours: Double? = null,        // 지난밤 수면 시간
    /// 최근 밤별 수면 상세 (스토어가 최근 2주치를 넘긴다). 단계 데이터가 없는 밤은 deepRemFraction이 null.
    val sleepNights: List<SleepNight> = emptyList(),
) {
    data class Reading(
        val today: Double,
        val baseline: Double,      // 오늘을 제외한 최근 28일 일평균의 평균
        val baselineDays: Int,     // 기준선 계산에 쓰인 날짜 수
    )

    /// 밤별 수면 상세 — 단계(깊은+렘)와 취침 시각 규칙성을 다룰 때만 쓰인다
    data class SleepNight(
        val date: Instant,                 // 기상일 자정 (KST)
        val asleepHours: Double,
        val deepRemFraction: Double?,      // (깊은 수면 + 렘) ÷ 총 수면, 0...1
        val bedtimeMinutes: Double?,       // 취침 시각 — 정오(12:00) 기준 경과 분. 자정 넘김(23시=660, 새벽 1시=780)을 연속값으로 다루기 위한 좌표계
    )
}

/// 체력 배터리 결과 — 남은 체력 추정치(0–100)와 요인별 기여
data class BatteryReport(
    val level: Int,                // 0–100
    val tone: RRTone,              // 색상 매핑용
    val statusLabel: String,       // "충전 충분" / "양호" / "주의" / "방전 임박"
    val headline: String,
    val factors: List<Factor>,
) {
    /// iOS의 systemImage(SF Symbols)는 이식하지 않는다 — 아이콘 매핑은 화면 계층(M5) 몫
    data class Factor(
        val name: String,
        val detail: String,        // "55 ms · 평소 62 ms"
        val points: Int,           // 기여 포인트 (충전 +, 소모 −)
    )
}

/// 체력 배터리 엔진 — 활력징후(회복)와 훈련 부하(소모)를 합산한다
///
/// 모델: 중립 50에서 시작해 요인별 포인트를 더한다.
/// - 심박 변이(HRV): 기준선 대비 ±25% 편차가 ±20pt
/// - 안정 심박: 기준선 대비 ∓10% 편차가 ±15pt (낮을수록 +)
/// - 수면: 7시간 기준, ±2시간이 ±15pt
/// - 호흡수·피부 온도: 평소 범위를 벗어나면 각각 −6pt (감점 전용)
/// - 수면 질(깊은+렘 비율 하락)·수면 리듬(취침 시각 표준편차): 이탈 시 각각 −8pt·−6pt (감점 전용)
/// - 오늘 훈련: 오늘 뛴 거리 km × 2pt 소모 (최대 −25)
/// - 훈련 부하: ACWR > 1.3이면 초과분만큼 소모 (최대 −15)
///
/// 가드: 핵심 신호(HRV·안정 심박·수면) 중 2개 이상이 있어야 계산한다.
/// (iOS는 HRR 포함 4종 중 2개 — HC에 HRR이 없어 3종 중 2개가 된다.)
/// 기준선은 최소 7일 — 부족하면 그 요인은 없는 것으로 친다.
/// "틀린 인사이트는 없느니만 못하다."
object BatteryEngine {
    const val MIN_BASELINE_DAYS = 7   // Apple 활력징후 앱과 같은 최소 기준선

    fun compute(vitals: VitalsSnapshot,
                runs: List<RunSummary>,
                now: Instant = Instant.now()): BatteryReport? {
        val factors = mutableListOf<BatteryReport.Factor>()
        var coreSignals = 0

        valid(vitals.hrvMs)?.let { r ->
            val pts = points((r.today / r.baseline - 1) / 0.25, scale = 20.0)
            factors.add(BatteryReport.Factor(
                name = "심박 변이",
                detail = "%.0f ms · 평소 %.0f ms".format(Locale.ROOT, r.today, r.baseline),
                points = pts))
            coreSignals += 1
        }

        valid(vitals.restingHR)?.let { r ->
            val pts = points((1 - r.today / r.baseline) / 0.10, scale = 15.0)
            factors.add(BatteryReport.Factor(
                name = "안정 심박",
                detail = "%.0f bpm · 평소 %.0f bpm".format(Locale.ROOT, r.today, r.baseline),
                points = pts))
            coreSignals += 1
        }

        vitals.sleepHours?.let { hours ->
            val pts = points((hours - 7) / 2, scale = 15.0)
            factors.add(BatteryReport.Factor(
                name = "수면",
                detail = sleepText(hours),
                points = pts))
            coreSignals += 1
        }

        if (coreSignals < 2) return null

        // 감점 전용 보조 신호 — 평소 범위 안이면 표시하지 않는다
        valid(vitals.respiratoryRate)?.let { r ->
            if (abs(r.today / r.baseline - 1) > 0.12) {
                factors.add(BatteryReport.Factor(
                    name = "호흡수",
                    detail = "분당 %.1f회 · 평소 %.1f회".format(Locale.ROOT, r.today, r.baseline),
                    points = -6))
            }
        }
        valid(vitals.skinTempC)?.let { r ->
            if (r.today - r.baseline >= 0.4) {
                factors.add(BatteryReport.Factor(
                    name = "피부 온도",
                    detail = "평소보다 +%.1f°C".format(Locale.ROOT, r.today - r.baseline),
                    points = -6))
            }
        }

        // 수면 질 — 깊은+렘 비율이 있는 밤이 7개 이상일 때만, 가장 최근 밤 vs 나머지 밤 평균(기저)
        val qualityNights = vitals.sleepNights.filter { it.deepRemFraction != null }
            .sortedBy { it.date }
        if (qualityNights.size >= 7) {
            val todayFraction = qualityNights.last().deepRemFraction!!
            val baselineNights = qualityNights.dropLast(1)
            val baselineFraction = baselineNights.mapNotNull { it.deepRemFraction }.sum() /
                baselineNights.size
            if (baselineFraction > 0 && (baselineFraction - todayFraction) / baselineFraction >= 0.20) {
                factors.add(BatteryReport.Factor(
                    name = "수면 질",
                    detail = "깊은+렘 %.0f%% · 평소 %.0f%%".format(
                        Locale.ROOT, todayFraction * 100, baselineFraction * 100),
                    points = -8))
            }
        }

        // 수면 리듬 — 취침 시각이 있는 밤이 7개 이상일 때만, 모집단 표준편차 > 90분이면 감점
        val bedtimes = vitals.sleepNights.mapNotNull { it.bedtimeMinutes }
        if (bedtimes.size >= 7) {
            val mean = bedtimes.average()
            val variance = bedtimes.sumOf { (it - mean) * (it - mean) } / bedtimes.size
            val sd = sqrt(variance)
            if (sd > 90) {
                factors.add(BatteryReport.Factor(
                    name = "수면 리듬",
                    detail = "취침 시각 편차 " + bedtimeSDText(sd),
                    points = -6))
            }
        }

        val todayKm = kmToday(runs, now)
        if (todayKm > 0.1) {
            factors.add(BatteryReport.Factor(
                name = "오늘 훈련",
                detail = "%.1f km".format(Locale.ROOT, todayKm),
                points = -minOf(25, roundAwayFromZero(todayKm * 2).toInt())))
        }

        acwr(runs, now)?.let { ratio ->
            if (ratio > 1.3) {
                factors.add(BatteryReport.Factor(
                    name = "훈련 부하",
                    detail = "부하 비율 %.2f".format(Locale.ROOT, ratio),
                    points = -minOf(15, roundAwayFromZero((ratio - 1.3) * 25).toInt())))
            }
        }

        val level = (50 + factors.sumOf { it.points }).coerceIn(0, 100)
        val (tone, label, headline) = status(level)
        return BatteryReport(level = level, tone = tone, statusLabel = label,
                             headline = headline, factors = factors)
    }

    // MARK: - 내부

    /// 기준선이 최소 표본 이상 쌓인 정상 측정값만 통과시킨다 (7일)
    private fun valid(reading: VitalsSnapshot.Reading?): VitalsSnapshot.Reading? {
        if (reading == null || reading.baselineDays < MIN_BASELINE_DAYS || reading.baseline <= 0) {
            return null
        }
        return reading
    }

    private fun points(normalized: Double, scale: Double): Int =
        roundAwayFromZero(normalized.coerceIn(-1.0, 1.0) * scale).toInt()

    /// Swift `.rounded()` 대응 — 0.5는 0에서 먼 쪽으로 (Kotlin roundToInt는 -0.5를 0으로 올려 다름)
    private fun roundAwayFromZero(x: Double): Double =
        if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

    private fun status(level: Int): Triple<RRTone, String, String> = when {
        level >= 75 -> Triple(RRTone.IMPROVING, "충전 충분", "몸이 충분히 충전됐어요")
        level >= 50 -> Triple(RRTone.STEADY, "양호", "무리하지 않으면 충분한 상태예요")
        level >= 25 -> Triple(RRTone.CAUTION, "주의", "회복이 덜 됐어요, 오늘은 가볍게 가세요")
        else -> Triple(RRTone.OVERLOAD, "방전 임박", "오늘은 훈련보다 충전이 먼저예요")
    }

    private fun sleepText(hours: Double): String {
        val totalMin = roundAwayFromZero(hours * 60).toInt()
        return "${totalMin / 60}시간 ${totalMin % 60}분"
    }

    private fun bedtimeSDText(minutes: Double): String {
        val totalMin = roundAwayFromZero(minutes).toInt()
        return "±${totalMin / 60}시간 ${totalMin % 60}분"
    }

    /// 오늘 0시(KST) 이후 뛴 거리 — 어제까지의 훈련은 밤사이 활력징후에 이미 반영돼 있다
    private fun kmToday(runs: List<RunSummary>, now: Instant): Double {
        val dayStart = now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant()
        return runs.filter { it.start >= dayStart && it.start <= now }
            .mapNotNull { it.distanceKm }
            .sum()
    }

    /// ReportEngine과 같은 가드의 ACWR — 3주 미만 기록이거나 주평균 3km 미만이면 null.
    /// 창이 now를 포함(≤)하는 점이 ReportEngine의 [from, to) 창과 다르다 — iOS와 동일.
    private fun acwr(runs: List<RunSummary>, now: Instant): Double? {
        val oldest = runs.minOfOrNull { it.start } ?: return null
        if (oldest > now.minusSeconds(21 * 86_400L)) return null
        fun windowKm(days: Long): Double {
            val from = now.minusSeconds(days * 86_400L)
            return runs.filter { it.start >= from && it.start <= now }
                .mapNotNull { it.distanceKm }
                .sum()
        }
        val acute = windowKm(7)
        val chronic = windowKm(28) / 4
        if (chronic < 3) return null
        return acute / chronic
    }
}
