package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/// 주간 리포트 화면용 구조화 지표 — Insight(문장)와 같은 산식·가드를 쓰되,
/// 카드/차트가 그릴 수 있도록 수치를 그대로 노출한다. iOS `ReportMetrics.swift` 이식.
///
/// 산식과 미노출 가드는 ReportEngine 주석 참조. 여기서도 동일하게,
/// 표본이 부족하면 해당 카드를 아예 만들지 않는다(null).
data class WeeklyReport(
    val weekLabel: String,          // "2026년 8월 2째주" — 그 주 목요일 기준 (Format.weekLabel)
    val dateRange: String,          // "8.3 – 8.9"
    val distance: DistanceCard?,
    val acwr: AcwrCard?,
    val efficiency: EfficiencyCard?,
    val streakWeeks: Int,           // 주 1회 이상 달린 ISO 주 연속 개수
    val weekRunCount: Int,          // 이번 달력 주 러닝 횟수 (streak 카드 캡션용)
) {
    data class WeekBar(
        val label: String,     // "8월 2째주", "1주" 등
        val km: Double,
        val isCurrent: Boolean,
        val index: Int,
    )

    data class DistanceCard(
        val tone: RRTone,
        val weeks: List<WeekBar>,   // 기록 전체 달력 주, 최소 6주 (차트용 — 가로 스크롤)
        val recent7Km: Double,      // 롤링 최근 7일 (판정 기준)
        val previous7Km: Double,    // 롤링 이전 7일
        val capKm: Double,          // 이전 7일 × 1.1 (10% 룰 상한)
        val changePct: Double,
    ) {
        val overKm: Double get() = recent7Km - capKm
    }

    data class AcwrCard(
        val tone: RRTone,
        val acute: Double,          // 최근 7일 km
        val chronic: Double,        // 4주 주평균 km
        val ratio: Double,
    )

    data class EfficiencyCard(
        val tone: RRTone,
        val points: List<Double>,       // 주별 평균 EF (오래된 → 최신, 기록 전체 — 표본 없는 주 제외)
        val pointLabels: List<String>,  // points와 병행 — "3주 전"·"이번 주" (탭 콜아웃용)
        val recentEF: Double,           // 최근 2주 평균
        val previousEF: Double,         // 직전 2주 평균
        val changePct: Double,
        val referenceHR: Double,        // 표본 러닝의 평균 심박 (페이스 환산 기준)
    ) {
        val recentPaceSec: Double get() = 60_000 / (recentEF * referenceHR)
        val previousPaceSec: Double get() = 60_000 / (previousEF * referenceHR)
        val paceDeltaSec: Double get() = previousPaceSec - recentPaceSec  // 양수면 빨라짐
    }

    val isEmpty: Boolean get() = distance == null && acwr == null && efficiency == null

    /// 상세 화면 첫 문장 — 가장 나쁜 톤 기준으로 한 주를 요약한다
    val headline: String
        get() {
            val tones = listOfNotNull(distance?.tone, acwr?.tone, efficiency?.tone)
            return when {
                RRTone.OVERLOAD in tones -> "몸보다 훈련량이 앞서 나간 한 주였습니다."
                RRTone.CAUTION in tones -> "조금 무리했거나 리듬이 흔들린 한 주였습니다."
                RRTone.IMPROVING in tones -> "몸이 좋아지고 있는 한 주였습니다."
                else -> "안정적으로 리듬을 지킨 한 주였습니다."
            }
        }

    /// 다음 주 제안 — 과부하면 안전 상한을 계산해 감량 폭을 제시한다
    val suggestion: String?
        get() {
            val d = distance ?: return null
            val overloaded = d.tone == RRTone.OVERLOAD || (acwr?.let { it.ratio > 1.3 } ?: false)
            if (overloaded) {
                var upper = d.capKm
                acwr?.let { upper = min(upper, it.chronic * 1.3) }
                val lower = upper * 0.93
                return "주간 %.0f–%.0f km로 줄이면 안전 구간으로 돌아옵니다. 롱런 하나를 회복 주행으로 바꾸면 충분해요."
                    .format(Locale.ROOT, lower, upper)
            }
            return "지금 리듬 그대로 이어가면 됩니다. 다음 주에도 증가 폭 10% 이내를 지켜보세요."
        }
}

fun ReportEngine.weeklyReport(runs: List<RunSummary>): WeeklyReport {
    val currentWeek = weekStart(now)  // ISO 주(월요일 시작), KST
    val weekStartInstant = currentWeek.atStartOfDay(SEOUL).toInstant()
    val weekEndInstant = currentWeek.plusWeeks(1).atStartOfDay(SEOUL).toInstant()
    val range = "${shortDate(currentWeek)} – ${shortDate(currentWeek.plusDays(6))}"

    return WeeklyReport(
        weekLabel = Format.weekLabel(currentWeek, withYear = true),
        dateRange = range,
        distance = distanceCard(runs, now, currentWeek),
        acwr = acwrCard(runs, now),
        efficiency = efficiencyCard(runs, now),
        streakWeeks = streakWeeks(runs, now),
        weekRunCount = runs.count { it.start >= weekStartInstant && it.start < weekEndInstant },
    )
}

// MARK: - 카드 계산

private fun distanceCard(runs: List<RunSummary>, now: Instant,
                         currentWeek: LocalDate): WeeklyReport.DistanceCard? {
    val recent = windowKm(runs, now, fromDaysAgo = 7, toDaysAgo = 0)
    val previous = windowKm(runs, now, fromDaysAgo = 14, toDaysAgo = 7)
    if (previous < 3) return null  // ReportEngine과 동일 가드
    val change = (recent - previous) / previous * 100

    // 차트: 기록 전체 달력 주 합계 (판정은 롤링 7일, 차트는 달력 주 — 라벨이 명확하다).
    // 지난 주들은 차트의 가로 스크롤로 본다 — 최소 6주는 채워 그린다.
    val span = chartWeekSpan(runs, currentWeek)
    val weeks = (0 until span).reversed().mapIndexed { index, back ->
        val start = currentWeek.minusWeeks(back.toLong())
        val startInstant = start.atStartOfDay(SEOUL).toInstant()
        val endInstant = start.plusWeeks(1).atStartOfDay(SEOUL).toInstant()
        val km = runs.filter { it.start >= startInstant && it.start < endInstant }
            .mapNotNull { it.distanceKm }.sum()
        WeeklyReport.WeekBar(label = Format.weekLabel(start),
                             km = km, isCurrent = back == 0, index = index)
    }

    val tone = if (change >= 10) RRTone.OVERLOAD else if (change < -30) RRTone.CAUTION else RRTone.STEADY
    return WeeklyReport.DistanceCard(tone = tone, weeks = weeks,
                                     recent7Km = recent, previous7Km = previous,
                                     capKm = previous * 1.1, changePct = change)
}

private fun acwrCard(runs: List<RunSummary>, now: Instant): WeeklyReport.AcwrCard? {
    val oldest = runs.minOfOrNull { it.start } ?: return null
    if (oldest > now.minusSeconds(21 * 86_400L)) return null
    val acute = windowKm(runs, now, fromDaysAgo = 7, toDaysAgo = 0)
    val chronic = windowKm(runs, now, fromDaysAgo = 28, toDaysAgo = 0) / 4
    if (chronic < 3) return null
    val ratio = acute / chronic
    val tone = when {
        ratio >= 1.5 -> RRTone.OVERLOAD
        ratio >= 1.3 -> RRTone.CAUTION
        ratio >= 0.8 -> RRTone.STEADY
        else -> RRTone.CAUTION  // 급감도 리듬 관점에서는 주의
    }
    return WeeklyReport.AcwrCard(tone = tone, acute = acute, chronic = chronic, ratio = ratio)
}

private fun efficiencyCard(runs: List<RunSummary>, now: Instant): WeeklyReport.EfficiencyCard? {
    val recentRuns = runs.filter { it.start >= day(now, -14) }
    val previousRuns = runs.filter { it.start >= day(now, -28) && it.start < day(now, -14) }
    val recent = recentRuns.mapNotNull { ReportEngine.efficiency(it) }
    val previous = previousRuns.mapNotNull { ReportEngine.efficiency(it) }
    if (recent.size < 3 || previous.size < 3) return null

    val hrSamples = (recentRuns + previousRuns).mapNotNull { it.avgHeartRate }
    val referenceHR = hrSamples.average()

    // 라인 차트: 기록 전체를 롤링 7일 단위로 평균 (표본 없는 주는 건너뛴다, 최소 8주 창)
    val weekSpan = runs.minOfOrNull { it.start }
        ?.let { maxOf(8, ceil(secondsBetween(it, now) / (7 * 86_400)).toInt()) } ?: 8
    val points = mutableListOf<Double>()
    val pointLabels = mutableListOf<String>()
    for (back in (0 until weekSpan).reversed()) {
        val samples = runs.filter { it.start >= day(now, -7 * (back + 1)) && it.start < day(now, -7 * back) }
            .mapNotNull { ReportEngine.efficiency(it) }
        if (samples.isEmpty()) continue
        points.add(samples.average())
        pointLabels.add(if (back == 0) "이번 주" else "${back}주 전")
    }

    val recentAvg = recent.average()
    val previousAvg = previous.average()
    val change = (recentAvg - previousAvg) / previousAvg * 100
    val tone = if (change >= 3) RRTone.IMPROVING else if (change < -3) RRTone.CAUTION else RRTone.STEADY
    return WeeklyReport.EfficiencyCard(tone = tone, points = points, pointLabels = pointLabels,
                                       recentEF = recentAvg, previousEF = previousAvg,
                                       changePct = change, referenceHR = referenceHR)
}

// MARK: - 헬퍼 (ReportEngine의 private 헬퍼와 동일 정의)

/// 막대 차트에 그릴 달력 주 수 — 가장 오래된 기록의 주부터 이번 주까지, 최소 6주
private fun chartWeekSpan(runs: List<RunSummary>, currentWeek: LocalDate): Int {
    val oldest = runs.minOfOrNull { it.start } ?: return 6
    val back = ChronoUnit.WEEKS.between(weekStart(oldest), currentWeek).toInt()
    return maxOf(6, back + 1)
}

private fun day(now: Instant, offset: Int): Instant = now.plusSeconds(offset * 86_400L)

private fun windowKm(runs: List<RunSummary>, now: Instant, fromDaysAgo: Int, toDaysAgo: Int): Double =
    runs.filter { it.start >= day(now, -fromDaysAgo) && it.start < day(now, -toDaysAgo) }
        .mapNotNull { it.distanceKm }
        .sum()

private fun shortDate(date: LocalDate): String = "${date.monthValue}.${date.dayOfMonth}"

// MARK: - streak · 주간 추이 골격

/// 주 1회 이상 달린 ISO 주가 이어지는 개수 — now가 속한 주부터 거꾸로 센다.
/// 진행 중인 이번 주는 아직 안 달렸어도 단절로 치지 않는다 — 매주 월요일 아침
/// streak이 0으로 초기화되는 오판을 막는다 (가정: 지난주까지의 연속은 유지).
fun streakWeeks(runs: List<RunSummary>, now: Instant): Int {
    val ranWeeks = runs.map { weekStart(it.start) }.toSet()
    var cursor = weekStart(now)
    if (cursor !in ranWeeks) cursor = cursor.minusWeeks(1)
    var count = 0
    while (cursor in ranWeeks) {
        count += 1
        cursor = cursor.minusWeeks(1)
    }
    return count
}

/// 날짜 붙은 표본 하나 — iOS의 `(date: Date, value: Double)` 튜플 대응
data class DatedSample(val date: Instant, val value: Double)

/// VO₂max 추이가 쓰는 골격 — ISO 주 평균 시리즈 + 4주 전 대비 변화량.
/// iOS에서는 HRR 추이와 공유하지만 HC에는 HRR 레코드 타입이 없어(계획서 §2.2)
/// Android에서는 HrrTrend를 이식하지 않는다.
private data class WeeklyTrendSeries(
    val points: List<Double>,        // 주 평균 (오래된 → 최신, 표본 있는 주만)
    val weekStarts: List<LocalDate>, // points와 병행하는 주 시작일
    val current: Double,             // 최신 주 평균
    val delta: Double?,              // spanWeeks주 전 대비 (비교할 주가 있을 때만)
    val spanWeeks: Int,
)

/// 창 안 표본 3개 미만이면 null. 4주 전 주에 표본이 없으면 그보다 오래된
/// 가장 가까운 주와 비교한다 (VO₂max 추정은 매주 기록되지 않는다).
private fun weeklyTrendSeries(samples: List<DatedSample>, now: Instant,
                              windowDays: Int): WeeklyTrendSeries? {
    val from = now.minusSeconds(windowDays * 86_400L)
    val recent = samples.filter { it.date >= from && it.date <= now }
    if (recent.size < 3) return null

    val byWeek = recent.groupBy({ weekStart(it.date) }, { it.value })
    val weeks = byWeek.keys.sorted()
    val averages = weeks.map { byWeek.getValue(it).average() }
    val latestWeek = weeks.lastOrNull() ?: return null
    val current = averages.last()

    val target = latestWeek.minusWeeks(4)
    var delta: Double? = null
    var spanWeeks = 0
    val baseline = weeks.indexOfLast { it <= target }
    if (baseline >= 0) {
        delta = current - averages[baseline]
        spanWeeks = ChronoUnit.WEEKS.between(weeks[baseline], latestWeek).toInt()
    }
    return WeeklyTrendSeries(points = averages, weekStarts = weeks,
                             current = current, delta = delta, spanWeeks = spanWeeks)
}

// MARK: - 심폐 체력 (VO₂max 추이)

/// 심폐 체력 추이 카드 — 워치가 야외 러닝·걷기에서 추정한 VO₂max(ml/kg/min)의
/// 주 단위 평균. 러닝 목적과 무관한 기초 체력 지표라 모든 프로필에 노출한다.
data class Vo2MaxTrend(
    val tone: RRTone,
    val points: List<Double>,       // 주 평균 ml/kg/min (오래된 → 최신, 표본 있는 주만)
    val pointLabels: List<String>,  // points와 병행 — "8월 2째주" (탭 콜아웃·축 라벨용)
    val current: Double,            // 최신 주 평균
    val delta: Double?,             // spanWeeks주 전 대비 변화량 (비교할 주가 있을 때만)
    val spanWeeks: Int,             // 비교 구간 주 수 — "N주 전보다 …" 문장용
)

/// VO₂max 표본 → ISO 주 단위 평균 + 4주 전 대비 변화량.
/// 미노출 가드(가정): 최근 12주 추정 기록 3회 미만이면 null.
/// 워치 추정치는 회당 편차가 있어 주 평균으로 누르고, ±1.0 ml/kg/min 미만
/// 변화는 유지로 판정한다 (가정 — 오차 범위 안 변동에 톤을 매기지 않는다).
fun vo2MaxTrend(samples: List<DatedSample>, now: Instant): Vo2MaxTrend? {
    val series = weeklyTrendSeries(samples, now, windowDays = 84) ?: return null
    val d = series.delta
    val tone = when {
        d != null && d >= 1.0 -> RRTone.IMPROVING
        d != null && d <= -1.0 -> RRTone.CAUTION
        else -> RRTone.STEADY
    }
    return Vo2MaxTrend(tone = tone, points = series.points,
                       pointLabels = series.weekStarts.map { Format.weekLabel(it) },
                       current = series.current, delta = series.delta,
                       spanWeeks = series.spanWeeks)
}

// (iOS의 HrrTrend는 이식하지 않음 — Health Connect에 심박 회복(HRR) 레코드 타입이 없다. 계획서 §2.2)

// MARK: - 통계 탭 (월간)

/// 통계 화면의 월 단위 집계
data class MonthlyStats(
    val monthLabel: String,             // "2026년 8월"
    val totalKm: Double,
    val deltaPct: Double?,              // 지난달 같은 구간 대비 누적 거리 증감 (지난달 기록 있을 때만)
    /// 비교에 쓴 지난달 구간의 일수 — 진행 중인 달일 때만 값이 있다(null = 지난달 전체)
    val comparisonDays: Int?,
    val weeks: List<WeeklyReport.WeekBar>,  // 월 내 주차 합계 ("1주"…)
    val avgPaceSec: Double?,
    val paceDeltaSec: Double?,          // 전월 대비 (음수 = 빨라짐)
    val avgHeartRate: Double?,
    val heartRateDelta: Double?,
    val count: Int,
    val perWeek: Double,
    val totalDurationSec: Double,
    val pacePoints: List<Double>,       // 러닝별 페이스 (오래된 → 최신, 스파크라인)
    val heartRatePoints: List<Double>,
    val runs: List<RunSummary>,         // 해당 월, 최신순
) {
    /// 증감 배지 밑에 붙는 비교 기준 — 무엇과 견준 수치인지 밝힌다
    val deltaCaption: String
        get() = comparisonDays?.let { "지난달 1–${it}일 대비" } ?: "지난달 대비"

    companion object {
        /// runs 전체에서 기록이 있는 월 목록 (최신 먼저)
        fun availableMonths(runs: List<RunSummary>, now: Instant = Instant.now()): List<YearMonth> {
            val currentMonth = YearMonth.from(now.atZone(SEOUL))
            val oldest = runs.minOfOrNull { it.start } ?: return listOf(currentMonth)
            val first = YearMonth.from(oldest.atZone(SEOUL))
            val months = mutableListOf<YearMonth>()
            var cursor = currentMonth
            while (cursor >= first) {
                months.add(cursor)
                cursor = cursor.minusMonths(1)
            }
            return months
        }

        fun compute(runs: List<RunSummary>, month: YearMonth,
                    now: Instant = Instant.now()): MonthlyStats {
            val start = month.atDay(1).atStartOfDay(SEOUL).toInstant()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant()
            val inMonth = runs.filter { it.start >= start && it.start < end }
                .sortedByDescending { it.start }

            // 비교 구간 — 진행 중인 달은 지난달의 "오늘과 같은 날짜"까지만 본다.
            // 지난달 전체와 견주면 월초에는 무조건 크게 줄어든 것처럼 보인다.
            val previousMonth = month.minusMonths(1)
            val previousStartDate = previousMonth.atDay(1)
            val previousMonthEndDate = month.atDay(1)
            val previousEndDate: LocalDate
            val comparisonDays: Int?
            if (now >= start && now < end) {
                val elapsed = ChronoUnit.DAYS.between(month.atDay(1),
                                                      now.atZone(SEOUL).toLocalDate()) + 1
                // 지난달이 더 짧으면(예: 3월 30일 → 2월) 그 달 끝에서 멈춘다
                previousEndDate = minOf(previousStartDate.plusDays(elapsed), previousMonthEndDate)
                comparisonDays = ChronoUnit.DAYS.between(previousStartDate, previousEndDate).toInt()
            } else {
                previousEndDate = previousMonthEndDate
                comparisonDays = null
            }
            val previousStart = previousStartDate.atStartOfDay(SEOUL).toInstant()
            val previousEnd = previousEndDate.atStartOfDay(SEOUL).toInstant()
            val inPrevious = runs.filter { it.start >= previousStart && it.start < previousEnd }

            fun totalKm(list: List<RunSummary>): Double = list.mapNotNull { it.distanceKm }.sum()
            /// 시간 가중 평균 페이스 = 총 시간 ÷ 총 거리
            fun avgPace(list: List<RunSummary>): Double? {
                val km = totalKm(list)
                if (km <= 0.1) return null
                val sec = list.filter { it.distanceKm != null }.sumOf { it.durationSec }
                return sec / km
            }
            fun avgHR(list: List<RunSummary>): Double? =
                list.mapNotNull { it.avgHeartRate }.takeIf { it.isNotEmpty() }?.average()

            val total = totalKm(inMonth)
            val previousTotal = totalKm(inPrevious)

            // 월 내 주차 (1일부터 7일 단위 — 달력 주 대신 단순 분할이 라벨과 맞다)
            val dayCount = month.lengthOfMonth()
            val weekCount = ceil(dayCount / 7.0).toInt()
            val weeks = (0 until weekCount).map { index ->
                val sliceStart = start.plusSeconds(index * 7 * 86_400L)
                val sliceEnd = minOf(sliceStart.plusSeconds(7 * 86_400L), end)
                val km = inMonth.filter { it.start >= sliceStart && it.start < sliceEnd }
                    .mapNotNull { it.distanceKm }.sum()
                WeeklyReport.WeekBar(label = "${index + 1}주", km = km,
                                     isCurrent = false, index = index)
            }

            val pace = avgPace(inMonth)
            val previousPace = avgPace(inPrevious)
            val hr = avgHR(inMonth)
            val previousHR = avgHR(inPrevious)
            val ordered = inMonth.sortedBy { it.start }

            return MonthlyStats(
                monthLabel = "${month.year}년 ${month.monthValue}월",
                totalKm = total,
                deltaPct = if (previousTotal >= 3) (total - previousTotal) / previousTotal * 100 else null,
                comparisonDays = comparisonDays,
                weeks = weeks,
                avgPaceSec = pace,
                paceDeltaSec = if (pace != null && previousPace != null) pace - previousPace else null,
                avgHeartRate = hr,
                heartRateDelta = if (hr != null && previousHR != null) hr - previousHR else null,
                count = inMonth.size,
                perWeek = inMonth.size / (dayCount / 7.0),
                totalDurationSec = inMonth.sumOf { it.durationSec },
                pacePoints = ordered.mapNotNull { it.paceSecPerKm },
                heartRatePoints = ordered.mapNotNull { it.avgHeartRate },
                runs = inMonth,
            )
        }
    }
}

// MARK: - 러닝 세션 표시 이름

/// "일요일 롱런" / "화요일 러닝" — 세션 목록·상세 제목
val RunSummary.displayTitle: String
    get() {
        val weekday = start.atZone(SEOUL).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)
        val kind = if ((distanceKm ?: 0.0) >= 15) "롱런" else "러닝"
        return "$weekday $kind"
    }

/// "1:52:34 · 5′20″/km · 152 bpm"
val RunSummary.metaLine: String
    get() {
        val parts = mutableListOf(Format.duration(durationSec))
        paceSecPerKm?.let { parts.add(Format.paceKm(it)) }
        avgHeartRate?.let { parts.add("${it.roundToInt()} bpm") }
        return parts.joinToString(" · ")
    }
