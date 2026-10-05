package com.jkpark.runwrap.engine

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/// 월간·연간 결산 리캡 (이슈 #167) — 달력 월·달력 연 단위로 총량·하이라이트·새 기록·강도 배분을
/// 한 번에 묶고, 존댓말 위트 톤(기획서 v0.4 §4.11)의 마무리 한 줄을 결정론적으로 고른다.
///
/// 왜: 주간 리포트는 "지금 상태"를 말하지만, 한 달·한 해를 돌아보는 계기가 앱에 없었다.
/// 새 산식은 만들지 않는다 — PR은 `PersonalRecords.compute`(#166 베스트 에포트),
/// 강도 배분은 `ZoneDistributionEngine`(#165)과 같은 존 매핑·가드·톤 경계를 그대로 쓴다.
/// 순수 로직: Foundation만 쓰고 `now`·`calendar`를 주입받아 결정론적이다.

/// 결산 기간 — 연관값은 그 기간 안의 아무 시각(보통 기간 시작일)
sealed class RecapPeriod {
    data class month(val date: Instant) : RecapPeriod()
    data class year(val date: Instant) : RecapPeriod()

    /// `.sheet(item:)`용 — 같은 기준 시각이면 같은 시트
    val id: String
        get() = when (this) {
            is month -> "month-${swiftDescription(date.timeIntervalSince1970)}"
            is year -> "year-${swiftDescription(date.timeIntervalSince1970)}"
        }

    /// 달력 월·달력 연 구간 [start, end)
    /// (Android: DateInterval → ClosedRange — `in`은 Swift `DateInterval.contains`처럼 끝을 포함한다.
    /// 끝을 빼야 하는 곳은 Swift처럼 `<`로 직접 비교한다)
    fun interval(zone: ZoneId): ClosedRange<Instant> {
        val first = firstDay(zone)
        val next = when (this) {
            is month -> first.plusMonths(1)
            is year -> first.plusYears(1)
        }
        return first.atStartOfDay(zone).toInstant()..next.atStartOfDay(zone).toInstant()
    }

    /// 같은 길이의 직전 기간 — 지난달·지난해
    fun previous(zone: ZoneId): RecapPeriod {
        val first = firstDay(zone)
        return when (this) {
            is month -> month(first.minusMonths(1).atStartOfDay(zone).toInstant())
            is year -> year(first.minusYears(1).atStartOfDay(zone).toInstant())
        }
    }

    /// 기간 시작일(달력 날짜)
    private fun firstDay(zone: ZoneId): LocalDate = when (this) {
        is month -> date.atZone(zone).toLocalDate().withDayOfMonth(1)
        is year -> date.atZone(zone).toLocalDate().withDayOfYear(1)
    }

    private companion object {
        /// Swift `"\(Double)"` 표기 — 정수 초도 "1785510000.0"처럼 소수점을 붙인다.
        /// (Android: 1e16 이상은 Swift가 지수 표기 "1e+16"을 쓰지만 날짜 범위 밖이라 옮기지 않는다)
        fun swiftDescription(value: Double): String {
            val plain = BigDecimal.valueOf(value).toPlainString()
            return if ('.' in plain) plain else "$plain.0"
        }
    }
}

data class Recap(
    /// "2026년 8월 결산" / "2026년 결산"
    val title: String,
    /// "2026년 8월" / "2026년" — 공유 카드 아이브로
    val periodLabel: String,
    val period: RecapPeriod,
    val totals: Totals,
    val highlights: Highlights?,
    /// 카드 3 — 이 기간에 세운 거리별 최고 기록. 비어 있으면 카드 생략
    val records: List<PersonalRecords.Entry>,
    val intensity: Intensity?,
    /// 카드 5 — 마무리 한 줄
    val closingLine: String,
    /// 직전 기간 대비 거리 증감(%) — 직전 기간 거리가 3km 미만이면 nil (MonthlyStats.deltaPct와 같은 가드)
    val deltaPct: Double?,
) {
    /// 카드 1 — 총량. 런린이는 거리 수치를 숨기고 횟수·시간을 크게 (ReportGate §4 "문장만")
    data class Totals(
        val distanceKm: Double,
        val count: Int,
        val durationSec: Double,
        val showsDistance: Boolean,
    )

    /// 카드 2 — 하이라이트. 둘 다 없으면 카드째 nil
    data class Highlights(
        /// 거리 최대 세션
        val longest: RunSummary?,
        /// 평균 페이스 최소 세션 — 3km 이상만 후보
        val fastest: RunSummary?,
    )

    /// 카드 4 — 강도 배분 (월간만). ZoneDistributionEngine.easyShare 결과
    data class Intensity(
        val easyShare: Double,
        val tone: RRTone,
        val sessions: Int,
    )
}

object RecapEngine {
    /// 미노출 가드 — 기간 안 러닝이 이보다 적으면 결산할 게 없다
    const val minRuns = 3
    /// 가장 빠른 세션 후보의 최소 거리(km) — 1~2km 짧은 질주가 "가장 빠른 세션"을 차지하지 않게
    const val fastestMinKm = 3.0
    /// 마무리 문장의 증감 분기 폭(%) — 주간 거리 10% 규칙과 같은 폭. 이 안이면 "꾸준함" 문장
    const val closingDeltaPct = 10.0

    /// 결산 가능 여부 — 기간 안 러닝 3회 이상. 화면의 버튼 활성·홈 카드 노출이 compute와 같은 기준을 쓴다
    fun hasEnoughRuns(period: RecapPeriod, runs: List<RunSummary>, zone: ZoneId): Boolean =
        sessions(period.interval(zone), runs).size >= minRuns

    fun compute(period: RecapPeriod,
                runs: List<RunSummary>,
                efforts: BestEffortTable,
                histograms: Map<String, ZoneHistogram>,
                profile: HeartRateProfile,
                level: RunnerLevel,
                now: Instant,
                zone: ZoneId): Recap? {
        val interval = period.interval(zone)
        val inPeriod = sessions(interval, runs)
        if (inPeriod.size < minRuns) return null

        val totalKm = inPeriod.mapNotNull { it.distanceKm }.sum()
        val totals = Recap.Totals(distanceKm = totalKm,
                                  count = inPeriod.size,
                                  durationSec = inPeriod.map { it.durationSec }.sum(),
                                  showsDistance = ReportGate.showsNumbers(ReportCard.distance, level))

        // 하이라이트 — 동률이면 먼저 달린 세션
        val ordered = inPeriod.sortedBy { it.start }
        val longest = ordered.filter { (it.distanceKm ?: 0.0) > 0 }
            .fold(null as RunSummary?) { best, run ->
                if ((best?.distanceKm ?: 0.0) >= (run.distanceKm ?: 0.0)) best else run
            }
        val fastest = ordered.filter { (it.distanceKm ?: 0.0) >= fastestMinKm && it.paceSecPerKm != null }
            .fold(null as RunSummary?) { best, run ->
                if (best == null) return@fold run
                if (best.paceSecPerKm!! <= run.paceSecPerKm!!) best else run
            }
        val highlights = if (longest == null && fastest == null) null
            else Recap.Highlights(longest = longest, fastest = fastest)

        // 새 기록 — 기간 끝까지의 전체 기록으로 PR을 뽑고, 그 PR을 세운 세션이 기간 안인 것만
        val records = PersonalRecords.compute(runs.filter { it.start < interval.endInclusive }, efforts)
            .filter { it.run.start >= interval.start && it.run.start < interval.endInclusive }

        // 강도 배분 — 월간만, 레벨 게이트(런린이 미노출)는 리포트 탭 카드와 같다
        var intensity: Recap.Intensity? = null
        if (period is RecapPeriod.month && ReportGate.shows(ReportCard.zoneBalance, level)) {
            val share = ZoneDistributionEngine.easyShare(histograms, runs, profile,
                                                         interval.start, interval.endInclusive)
            if (share != null) {
                intensity = Recap.Intensity(easyShare = share.share, tone = share.tone, sessions = share.sessions)
            }
        }

        // 직전 기간 — 진행 중인 기간은 직전 기간의 같은 경과 일수까지만 견준다 (MonthlyStats와 같은 방식).
        // 전체와 견주면 기간 초반에는 무조건 크게 줄어든 것처럼 보인다
        val previousInterval = period.previous(zone).interval(zone)
        var previousEnd = previousInterval.endInclusive
        if (now in interval) {
            val elapsedDays = ChronoUnit.DAYS.between(interval.start.atZone(zone), now.atZone(zone)) + 1
            previousEnd = minOf(previousInterval.start.atZone(zone).plusDays(elapsedDays).toInstant(),
                                previousInterval.endInclusive)
        }
        val previousKm = runs.filter { it.start >= previousInterval.start && it.start < previousEnd }
            .mapNotNull { it.distanceKm }.sum()
        val deltaPct = if (previousKm >= 3) (totalKm - previousKm) / previousKm * 100 else null

        val isFirst = !runs.any { it.start < interval.start }
        val label = periodLabel(period, zone)
        return Recap(title = "$label 결산",
                     periodLabel = label,
                     period = period,
                     totals = totals,
                     highlights = highlights,
                     records = records,
                     intensity = intensity,
                     closingLine = closingLine(period = period, isFirst = isFirst,
                                               deltaPct = deltaPct, count = inPeriod.size),
                     deltaPct = deltaPct)
    }

    /// 마무리 한 줄 — 첫 결산 > 증가 > 감소 > 꾸준함 순으로 하나만 (기획서 v0.4 §4.11 존댓말 위트 톤).
    /// 증감은 ±10% 밖일 때만 말한다 — 그 안이거나 비교할 수 없으면 횟수로 꾸준함을 칭찬한다
    fun closingLine(period: RecapPeriod, isFirst: Boolean, deltaPct: Double?, count: Int): String {
        val isMonth = period is RecapPeriod.month
        if (isFirst) {
            return "첫 결산입니다. 시작이 반이라는 말, 오늘은 믿어 보겠습니다."
        }
        if (deltaPct != null && deltaPct >= closingDeltaPct) {
            return "${if (isMonth) "지난달" else "지난해"}보다 ${deltaPct.swiftRoundedInt()}% 더 달리셨습니다. " +
                "새가 살찌는 소리가 들립니다."
        }
        if (deltaPct != null && deltaPct <= -closingDeltaPct) {
            return "쉬어 가는 ${if (isMonth) "달" else "해"}도 훈련입니다. 몸이 고마워하고 있을 겁니다."
        }
        return "${count}번을 달리셨습니다. 꾸준함은 배신하지 않습니다 — 새도 마찬가지고요."
    }

    // MARK: 홈 카드 노출

    /// 홈 결산 카드 — 매월 1~7일엔 지난달, 12월 25일~1월 7일엔 올해(1월이면 지난해).
    /// 열어 보거나 닫은 기간(저장 키가 같음)은 뺀다. 순서는 월간 → 연간.
    /// 표본 가드(`hasEnoughRuns`)는 러닝 목록을 가진 화면이 따로 건다
    fun promptKinds(now: Instant, zone: ZoneId, dismissedMonth: String, dismissedYear: String): List<RecapPeriod> {
        val today = now.atZone(zone).toLocalDate()
        val day = today.dayOfMonth
        val month = today.monthValue
        val thisMonth = today.withDayOfMonth(1)
        val thisYear = today.withDayOfYear(1)

        val kinds = mutableListOf<RecapPeriod>()
        if (day <= 7) {
            val last = RecapPeriod.month(thisMonth.minusMonths(1).atStartOfDay(zone).toInstant())
            if (dismissKey(last, zone) != dismissedMonth) kinds.add(last)
        }
        val yearly: RecapPeriod? =
            if (month == 12 && day >= 25) RecapPeriod.year(thisYear.atStartOfDay(zone).toInstant())
            else if (month == 1 && day <= 7) RecapPeriod.year(thisYear.minusYears(1).atStartOfDay(zone).toInstant())
            else null
        if (yearly != null && dismissKey(yearly, zone) != dismissedYear) {
            kinds.add(yearly)
        }
        return kinds
    }

    /// 닫힘 저장 키 — 월간 "yyyy-MM", 연간 "yyyy" (RecapKey)
    fun dismissKey(period: RecapPeriod, zone: ZoneId): String {
        val start = period.interval(zone).start.atZone(zone)
        val year = start.year
        return when (period) {
            is RecapPeriod.month -> String.format(Locale.ROOT, "%04d-%02d", year, start.monthValue)
            is RecapPeriod.year -> String.format(Locale.ROOT, "%04d", year)
        }
    }

    // MARK: 내부

    /// "2026년 8월" / "2026년"
    fun periodLabel(period: RecapPeriod, zone: ZoneId): String {
        val start = period.interval(zone).start.atZone(zone)
        val year = start.year
        return when (period) {
            is RecapPeriod.month -> "${year}년 ${start.monthValue}월"
            is RecapPeriod.year -> "${year}년"
        }
    }

    /// 기간 [start, end) 안의 러닝 — 다음 기간 자정 시작 세션이 두 기간에 겹치지 않게 끝은 뺀다
    private fun sessions(interval: ClosedRange<Instant>, runs: List<RunSummary>): List<RunSummary> =
        runs.filter { it.start >= interval.start && it.start < interval.endInclusive }
}
