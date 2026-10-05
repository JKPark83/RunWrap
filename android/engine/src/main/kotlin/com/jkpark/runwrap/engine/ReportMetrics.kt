package com.jkpark.runwrap.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/// 런미새 리포트('내 상태') 화면용 구조화 지표 — Insight(문장)와 같은 산식·가드를 쓰되,
/// 카드/차트가 그릴 수 있도록 수치를 그대로 노출한다.
/// 판정·헤더 표기는 달력 주가 아니라 최근 7일 기준이다 (이슈 #21) — 헤더가 보여 주는
/// "6일 전 자정 ~ 지금" 달력 창에 거리·횟수를 맞춘다 (이슈 #75). ACWR만 롤링 창이다.
///
/// 산식과 미노출 가드는 ReportEngine 주석 참조. 여기서도 동일하게,
/// 표본이 부족하면 해당 카드를 아예 만들지 않는다(nil).
data class WeeklyReport(
    val dateRange: String,          // "8.15 – 8.21" — 최근 7일 달력 날짜 (오늘 포함)
    /// 기록 전체 달력 주 거리, 최소 6주 (주간 거리 차트용 — 가로 스크롤). 기록이 없으면 빈 배열.
    /// 증가율 가드(`distance` nil)와 무관하게 채운다 — 비율이 없어도 이력 차트는 보여 준다 (이슈 #91)
    val weeks: List<WeekBar>,
    val distance: DistanceCard?,
    val acwr: AcwrCard?,
    val efficiency: EfficiencyCard?,
    val streakWeeks: Int,           // 주 1회 이상 달린 ISO 주 연속 개수
    val ranThisWeek: Boolean,       // now가 속한 ISO 주에 러닝이 있는지 (스트릭 캡션용, 이슈 #195)
    val weekRunCount: Int,          // 최근 7일(헤더와 같은 달력 창) 러닝 횟수 (streak 카드 캡션·알림 본문용)
) {
    data class WeekBar(
        val label: String,     // "8월 2째주", "1주" 등
        val km: Double,
        val isCurrent: Boolean,
        val index: Int,
    ) {
        val id: Int get() = index
    }

    data class DistanceCard(
        val tone: RRTone,
        val recent7Km: Double,      // 최근 7일 — 헤더와 같은 달력 창 (판정 기준, 이슈 #75)
        val previous7Km: Double,    // 그 직전 달력 7일
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
        val recentEF: Double,       // 최근 2주 평균
        val previousEF: Double,     // 직전 2주 평균
        val changePct: Double,
        val referenceHR: Double,    // 표본 러닝의 평균 심박 (페이스 환산 기준)
    ) {
        val recentPaceSec: Double get() = 60_000 / (recentEF * referenceHR)
        val previousPaceSec: Double get() = 60_000 / (previousEF * referenceHR)
        val paceDeltaSec: Double get() = previousPaceSec - recentPaceSec  // 양수면 빨라짐
    }

    /// 이 레벨에서 실제로 그려지는 판정 카드 — 미노출 가드(엔진 nil) AND 레벨 게이트 (이슈 #119).
    /// 비어 있으면 홈은 표본 부족 안내를 띄운다.
    /// 예전 `isEmpty`(셋 다 nil)는 게이트를 몰라, 런린이는 숨겨진 ACWR·EF 때문에 안내도 판정 카드도 없었다.
    /// 거리는 증감 판정(`distance`)이 있을 때만 센다 — 주간 이력 차트(`weeks`)는 기록만 있으면
    /// 늘 그려지므로(이슈 #91) 그것까지 세면 표본 부족 안내가 영영 뜨지 않는다.
    fun visibleCards(level: RunnerLevel): List<ReportCard> =
        listOf(ReportCard.distance to (distance != null),
               ReportCard.acwr to (acwr != null),
               ReportCard.efficiency to (efficiency != null))
            .filter { it.second && ReportGate.shows(it.first, level = level) }
            .map { it.first }

    /// 상세 화면 첫 문장 — 이 레벨에서 보이는 카드 중 가장 나쁜 톤 기준으로 한 주를 요약한다.
    /// 근거 카드는 `visibleCards(level:)`와 같은 규칙 — 런린이에게 숨긴 ACWR·EF의 판정이
    /// 문장으로 새 나가지 않게 한다 (이슈 #124)
    fun headline(level: RunnerLevel): String {
        val tones = visibleTones(level = level)
        if (RRTone.overload in tones) return "몸보다 훈련량이 앞서 나간 한 주였습니다."
        if (RRTone.caution in tones) return "조금 무리했거나 리듬이 흔들린 한 주였습니다."
        if (RRTone.improving in tones) return "몸이 좋아지고 있는 한 주였습니다."
        return "안정적으로 리듬을 지킨 한 주였습니다."
    }

    /// 다음 주 제안 — 과부하면 안전 상한을 계산해 감량 폭을 제시한다.
    /// ACWR 근거는 ACWR 카드가 보이는 레벨에서만 쓰고, 거리 수치를 못 보는 런린이에게는
    /// km 없이 문장만 낸다 (기획서 §4 "문장만", 이슈 #124)
    fun suggestion(level: RunnerLevel): String? {
        val d = distance ?: return null
        val a = if (ReportGate.shows(ReportCard.acwr, level = level)) acwr else null
        val overloaded = d.tone == RRTone.overload || (a?.let { it.ratio > 1.3 } ?: false)
        if (overloaded) {
            if (!ReportGate.showsNumbers(ReportCard.distance, level = level)) {
                return "이번 주보다 조금 덜 달려도 괜찮아요. 롱런 하나를 가볍게 바꿔 보세요."
            }
            var upper = d.capKm
            if (a != null) upper = min(upper, a.chronic * 1.3)
            val lower = upper * 0.93
            return "주간 ${fmt(lower, 0)}–${fmt(upper, 0)} km로 줄이면 안전 구간으로 돌아옵니다. 롱런 하나를 회복 주행으로 바꾸면 충분해요."
        }
        return "지금 리듬 그대로 이어가면 됩니다. 다음 주에도 증가 폭 10% 이내를 지켜보세요."
    }

    /// 이 레벨에서 그려지는 판정 카드의 톤만 모은다 (headline 근거)
    private fun visibleTones(level: RunnerLevel): List<RRTone> =
        visibleCards(level = level).mapNotNull { card ->
            when (card) {
                ReportCard.distance -> distance?.tone
                ReportCard.acwr -> acwr?.tone
                ReportCard.efficiency -> efficiency?.tone
                else -> null
            }
        }
}

/// (Android: iOS `Calendar.current`·`Calendar(identifier: .iso8601)` + `.current` 타임존 자리에 `zone`을 받는다)
fun ReportEngine.weeklyReport(from: List<RunSummary>, zone: ZoneId): WeeklyReport {
    val runs = from
    // 월요일 시작 — 주별 차트용
    val week = isoWeekStart(now, zone)
    // 헤더 표기·횟수는 달력 주가 아니라 최근 7일 (이슈 #21) — 오늘 포함.
    // 헤더가 날짜로 표기하므로 횟수도 같은 달력 창으로 센다 (이슈 #75)
    val range = "${shortDate(day(-6), zone)} – ${shortDate(now, zone)}"

    return WeeklyReport(dateRange = range,
                        weeks = weekBars(runs, zone = zone, currentWeek = week),
                        distance = distanceCard(runs, zone),
                        acwr = acwrCard(runs),
                        efficiency = efficiencyCard(runs),
                        streakWeeks = ReportEngine.streakWeeks(runs = runs, now = now, zone = zone),
                        ranThisWeek = ReportEngine.ranThisWeek(runs = runs, now = now, zone = zone),
                        weekRunCount = runs.count { it.start >= recentWindowStart(zone) && it.start < now })
}

// MARK: - 카드 계산

private fun ReportEngine.distanceCard(runs: List<RunSummary>, zone: ZoneId): WeeklyReport.DistanceCard? {
    // 헤더("최근 7일 · 날짜")와 같은 달력 창, 이전 7일은 그 바로 앞 달력 7일 — 두 창이
    // 맞붙어야 사이에 빠지는 기록이 없다 (이슈 #75)
    val previousStart = startOfDay(day(-13), zone)
    val recentWindowStart = recentWindowStart(zone)
    val recent = windowKm(runs, from = recentWindowStart, to = now)
    val previous = windowKm(runs, from = previousStart, to = recentWindowStart)
    if (!(previous >= 3)) return null  // ReportEngine과 동일 가드
    val change = (recent - previous) / previous * 100

    val tone = if (change >= 10) RRTone.overload else (if (change < -30) RRTone.caution else RRTone.steady)
    return WeeklyReport.DistanceCard(tone = tone,
                                     recent7Km = recent, previous7Km = previous,
                                     capKm = previous * 1.1, changePct = change)
}

/// 차트: 기록 전체 달력 주 합계 (판정은 헤더와 같은 최근 7일 달력 창, 차트는 달력 주 — 라벨이 명확하다).
/// 지난 주들은 차트의 가로 스크롤로 본다 — 최소 6주는 채워 그린다.
/// 증가율 가드와 따로 계산한다 — 기준 7일이 3km 미만이어도 이력은 그린다 (이슈 #91)
private fun weekBars(runs: List<RunSummary>, zone: ZoneId,
                     currentWeek: LocalDate): List<WeeklyReport.WeekBar> {
    if (runs.isEmpty()) return emptyList()
    val span = chartWeekSpan(runs, zone = zone, currentWeek = currentWeek)
    return (0 until span).reversed().mapIndexed { index, back ->
        val startDay = currentWeek.minusWeeks(back.toLong())
        val start = startDay.atStartOfDay(zone).toInstant()
        val end = startDay.plusWeeks(1).atStartOfDay(zone).toInstant()
        val km = runs.filter { it.start >= start && it.start < end }
            .mapNotNull { it.distanceKm }.sum()
        WeeklyReport.WeekBar(label = Format.weekLabel(weekStart = start, zone = zone),
                             km = km, isCurrent = back == 0, index = index)
    }
}

private fun ReportEngine.acwrCard(runs: List<RunSummary>): WeeklyReport.AcwrCard? {
    // 가드·산식은 리포트 문장과 공유한다 (이슈 #49 — 기록 4주 미만이면 nil)
    // 급성·만성 부하는 7×86_400·28×86_400 롤링 창이 지표 정의라(Gabbett, 2016) 헤더의
    // 달력 창(recentWindowStart)과 일부러 다르다 — 헤더에 맞추지 않는다 (이슈 #75)
    val load = ReportEngine.acwrLoad(runs = runs, now = now) ?: return null
    val (acute, chronic) = load
    val ratio = acute / chronic
    val tone = if (ratio >= 1.5) RRTone.overload
        else if (ratio >= 1.3) RRTone.caution
        else if (ratio >= 0.8) RRTone.steady
        else RRTone.caution  // 급감도 리듬 관점에서는 주의
    return WeeklyReport.AcwrCard(tone = tone, acute = acute, chronic = chronic, ratio = ratio)
}

private fun ReportEngine.efficiencyCard(runs: List<RunSummary>): WeeklyReport.EfficiencyCard? {
    fun ef(run: RunSummary): Double? {
        val pace = run.paceSecPerKm ?: return null
        val hr = run.avgHeartRate ?: return null
        if (!(hr > 0)) return null
        return (60_000 / pace) / hr
    }
    val recentRuns = runs.filter { it.start >= day(-14) }
    val previousRuns = runs.filter { it.start >= day(-28) && it.start < day(-14) }
    val recent = recentRuns.mapNotNull(::ef)
    val previous = previousRuns.mapNotNull(::ef)
    if (!(recent.size >= 3 && previous.size >= 3)) return null

    val hrSamples = (recentRuns + previousRuns).mapNotNull { it.avgHeartRate }
    val referenceHR = hrSamples.sum() / hrSamples.size

    // 라인 차트: 기록 전체를 롤링 7일 단위로 평균 (표본 없는 주는 건너뛴다, 최소 8주 창)
    val weekSpan = runs.minOfOrNull { it.start }?.let {
        max(8, ceil(timeIntervalSince(now, it) / (7 * 86_400)).toInt())
    } ?: 8
    val points = mutableListOf<Double>()
    val pointLabels = mutableListOf<String>()
    for (back in (0 until weekSpan).reversed()) {
        val samples = runs.filter { it.start >= day(-7 * (back + 1)) && it.start < day(-7 * back) }
            .mapNotNull(::ef)
        if (samples.isEmpty()) continue
        points.add(samples.sum() / samples.size)
        pointLabels.add(if (back == 0) "이번 주" else "${back}주 전")
    }

    val recentAvg = recent.sum() / recent.size
    val previousAvg = previous.sum() / previous.size
    val change = (recentAvg - previousAvg) / previousAvg * 100
    val tone = if (change >= 3) RRTone.improving else (if (change < -3) RRTone.caution else RRTone.steady)
    return WeeklyReport.EfficiencyCard(tone = tone, points = points, pointLabels = pointLabels,
                                       recentEF = recentAvg, previousEF = previousAvg,
                                       changePct = change, referenceHR = referenceHR)
}

// MARK: - 헬퍼 (ReportEngine의 private 헬퍼와 동일 정의)

/// 막대 차트에 그릴 달력 주 수 — 가장 오래된 기록의 주부터 이번 주까지, 최소 6주
private fun chartWeekSpan(runs: List<RunSummary>, zone: ZoneId, currentWeek: LocalDate): Int {
    val oldest = runs.minOfOrNull { it.start } ?: return 6
    val oldestWeek = isoWeekStart(oldest, zone)
    val back = ChronoUnit.WEEKS.between(oldestWeek, currentWeek).toInt()
    return max(6, back + 1)
}

private fun ReportEngine.day(offset: Int): Instant =
    now.plusSeconds(offset.toLong() * 86_400)

/// '최근 7일' 헤더(6일 전 ~ 오늘)와 같은 달력 창의 시작 — 6일 전 자정 (이슈 #75)
private fun ReportEngine.recentWindowStart(zone: ZoneId): Instant =
    startOfDay(day(-6), zone)

/// [from, to) 창에 시작된 러닝의 거리 합 (km)
private fun windowKm(runs: List<RunSummary>, from: Instant, to: Instant): Double =
    runs.filter { it.start >= from && it.start < to }
        .mapNotNull { it.distanceKm }
        .sum()

private fun shortDate(date: Instant, zone: ZoneId): String {
    val c = date.atZone(zone)
    return "${c.monthValue}.${c.dayOfMonth}"
}

/// `Calendar.startOfDay(for:)`
private fun startOfDay(date: Instant, zone: ZoneId): Instant =
    date.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

/// `to.timeIntervalSince(from)` — Swift `Date`처럼 Double 초로 뺀다
private fun timeIntervalSince(to: Instant, from: Instant): Double =
    to.timeIntervalSince1970 - from.timeIntervalSince1970

// MARK: - streak · 주간 추이 골격

/// 주 1회 이상 달린 ISO 주가 이어지는 개수 — now가 속한 주부터 거꾸로 센다.
/// 진행 중인 이번 주는 아직 안 달렸어도 단절로 치지 않는다 — 매주 월요일 아침
/// streak이 0으로 초기화되는 오판을 막는다 (가정: 지난주까지의 연속은 유지).
fun ReportEngine.Companion.streakWeeks(runs: List<RunSummary>, now: Instant, zone: ZoneId): Int {
    // 월요일 시작
    val ranWeeks = runs.map { isoWeekStart(it.start, zone) }.toSet()
    var cursor = isoWeekStart(now, zone)
    if (cursor !in ranWeeks) {
        cursor = cursor.minusWeeks(1)
    }
    var count = 0
    while (cursor in ranWeeks) {
        count += 1
        cursor = cursor.minusWeeks(1)
    }
    return count
}

/// now가 속한 ISO 주에 러닝이 하나라도 있는지 (이슈 #195) — streakWeeks와 같은 달력(ISO 8601,
/// 현재 타임존)이다. streakWeeks는 이번 주 무기록을 끊김으로 안 세므로, 캡션이 "이번 주 몫을
/// 채웠는지"를 가르려면 따로 알아야 한다
fun ReportEngine.Companion.ranThisWeek(runs: List<RunSummary>, now: Instant, zone: ZoneId): Boolean {
    // 월요일 시작
    val monday = isoWeekStart(now, zone)
    val start = monday.atStartOfDay(zone).toInstant()
    val end = monday.plusWeeks(1).atStartOfDay(zone).toInstant()
    // DateInterval.contains는 끝을 포함한다
    return runs.any { it.start >= start && it.start <= end }
}

/// VO₂max·HRR 추이가 공유하는 골격 — ISO 주 평균 시리즈 + 4주 전 대비 변화량
private data class WeeklyTrendSeries(
    val points: List<Double>,       // 주 평균 (오래된 → 최신, 표본 있는 주만)
    val weekStarts: List<Instant>,  // points와 병행하는 주 시작일
    val current: Double,            // 최신 주 평균
    val delta: Double?,             // spanWeeks주 전 대비 (비교할 주가 있을 때만)
    val spanWeeks: Int,
)

/// 창 안 표본 3개 미만이면 nil. 4주 전 주에 표본이 없으면 그보다 오래된
/// 가장 가까운 주와 비교한다 (VO₂max 추정도, HRR도 매주 기록되지 않는다).
/// (Android: iOS `[(date: Date, value: Double)]` 튜플 배열은 `Pair(date, value)` 목록으로 받는다)
private fun weeklyTrendSeries(samples: List<Pair<Instant, Double>>,
                              now: Instant, zone: ZoneId, windowDays: Double): WeeklyTrendSeries? {
    val windowStart = instantSince1970(now.timeIntervalSince1970 - windowDays * 86_400)
    val recent = samples.filter { (date, _) -> date >= windowStart && date <= now }
    if (recent.size < 3) return null

    val byWeek = HashMap<LocalDate, MutableList<Double>>()
    for ((date, value) in recent) {
        byWeek.getOrPut(isoWeekStart(date, zone)) { mutableListOf() }.add(value)
    }
    val weeks = byWeek.keys.sorted()
    val averages = weeks.map { byWeek.getValue(it).sum() / byWeek.getValue(it).size }
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
    return WeeklyTrendSeries(points = averages, weekStarts = weeks.map { it.atStartOfDay(zone).toInstant() },
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
/// 미노출 가드(가정): 최근 12주 추정 기록 3회 미만이면 nil.
/// 워치 추정치는 회당 편차가 있어 주 평균으로 누르고, ±1.0 ml/kg/min 미만
/// 변화는 유지로 판정한다 (가정 — 오차 범위 안 변동에 톤을 매기지 않는다).
fun ReportEngine.Companion.vo2MaxTrend(samples: List<Pair<Instant, Double>>, now: Instant, zone: ZoneId): Vo2MaxTrend? {
    val series = weeklyTrendSeries(samples = samples, now = now, zone = zone, windowDays = 84.0)
        ?: return null
    val d = series.delta
    val tone = when {
        d != null && d >= 1.0 -> RRTone.improving
        d != null && d <= -1.0 -> RRTone.caution
        else -> RRTone.steady
    }
    return Vo2MaxTrend(tone = tone, points = series.points,
                       pointLabels = series.weekStarts.map { Format.weekLabel(weekStart = it, zone = zone) },
                       current = series.current, delta = series.delta,
                       spanWeeks = series.spanWeeks)
}

// MARK: - 심박 회복 (HRR 추이)

/// 심박 회복(HRR) 추이 — 야외 러닝 종료 후 1분간 심박 하락 폭(bpm)의 주 단위 평균.
/// 심폐 체력 카드의 보조 라인 재료 (제안 문서 B1). 클수록 회복이 빠르다 (Cole 1999).
/// 차트 없이 한 줄로만 보여줘 points는 두지 않는다.
data class HrrTrend(
    val tone: RRTone,
    val current: Double,        // 최신 주 평균 bpm
    val delta: Double?,         // spanWeeks주 전 대비 변화량 (비교할 주가 있을 때만)
    val spanWeeks: Int,
)

/// HRR 표본 → ISO 주 단위 평균 + 4주 전 대비 변화량.
/// 미노출 가드(가정): 최근 12주 기록 3회 미만이면 nil — 야외 러닝을 해야만 쌓이는 지표라
/// 표본이 적을 때가 많다. 회당 편차가 커 주 평균으로 누르고, ±2 bpm 미만 변화는 유지 판정.
fun ReportEngine.Companion.hrrTrend(samples: List<Pair<Instant, Double>>, now: Instant, zone: ZoneId): HrrTrend? {
    val series = weeklyTrendSeries(samples = samples, now = now, zone = zone, windowDays = 84.0)
        ?: return null
    val d = series.delta
    val tone = when {
        d != null && d >= 2 -> RRTone.improving
        d != null && d <= -2 -> RRTone.caution
        else -> RRTone.steady
    }
    return HrrTrend(tone = tone, current = series.current,
                    delta = series.delta, spanWeeks = series.spanWeeks)
}

// MARK: - 통계 탭 (월간)

/// 통계 화면의 월 단위 집계
data class MonthlyStats(
    val monthLabel: String,            // "2026년 8월"
    val totalKm: Double,
    val deltaPct: Double?,             // 지난달 같은 구간 대비 누적 거리 증감 (지난달 기록 있을 때만)
    /// 비교에 쓴 지난달 구간의 일수 — 진행 중인 달일 때만 값이 있다(nil = 지난달 전체)
    val comparisonDays: Int?,
    val weeks: List<WeeklyReport.WeekBar>, // 월 내 주차 합계 ("1주"…)
    val avgPaceSec: Double?,
    val paceDeltaSec: Double?,         // 전월 대비 (음수 = 빨라짐)
    val avgHeartRate: Double?,
    val heartRateDelta: Double?,
    val count: Int,
    val perWeek: Double?,              // 진행 중인 달이 7일 미만 경과면 nil (표본 부족)
    val totalDurationSec: Double,
    val pacePoints: List<Double>,      // 러닝별 페이스 (오래된 → 최신, 스파크라인)
    val heartRatePoints: List<Double>,
    val runs: List<RunSummary>,        // 해당 월, 최신순
) {
    /// 증감 배지 밑에 붙는 비교 기준 — 무엇과 견준 수치인지 밝힌다
    val deltaCaption: String
        get() {
            val comparisonDays = comparisonDays ?: return "지난달 대비"
            return "지난달 1–${comparisonDays}일 대비"
        }

    companion object {
        /// runs 전체에서 기록이 있는 월 목록 (최신 먼저).
        /// 이번 달은 항상 포함한다 — 기록이 없거나 모든 기록이 미래 달이어도(기기 시계 오차 등)
        /// 빈 배열을 돌려주면 화면이 months[-1]로 크래시한다 (이슈 #68).
        /// (Android: iOS 인자 레이블 `in`은 Kotlin 예약어라 `runs`로 쓴다)
        fun availableMonths(runs: List<RunSummary>, now: Instant, zone: ZoneId): List<Instant> {
            val oldest = runs.minOfOrNull { it.start }
                ?: return listOf(monthStart(now, zone).toInstant())
            val months = mutableListOf<Instant>()
            var cursor = monthStart(now, zone)
            val first = minOf(monthStart(oldest, zone).toInstant(), cursor.toInstant())
            while (cursor.toInstant() >= first) {
                months.add(cursor.toInstant())
                cursor = cursor.minusMonths(1)
            }
            return months
        }

        fun compute(runs: List<RunSummary>, month: Instant, now: Instant, zone: ZoneId): MonthlyStats {
            val intervalStart = monthStart(month, zone)
            val intervalEnd = intervalStart.plusMonths(1)
            val start = intervalStart.toInstant()
            val end = intervalEnd.toInstant()
            // DateInterval.contains는 끝을 포함한다
            val inMonth = runs.filter { it.start >= start && it.start <= end }.sortedByDescending { it.start }

            // 비교 구간 — 진행 중인 달은 지난달의 "오늘과 같은 날짜"까지만 본다.
            // 지난달 전체와 견주면 월초에는 무조건 크게 줄어든 것처럼 보인다.
            val previousStart = intervalStart.minusMonths(1)
            val previousIntervalEnd = intervalStart.toInstant()
            val previousEnd: Instant
            val comparisonDays: Int?
            // 진행 중인 달의 경과 일수(오늘 포함) — 비교 구간과 '주 N회' 분모가 함께 쓴다. 끝난 달은 nil
            val elapsedDays: Int? = if (now >= start && now <= end)
                ChronoUnit.DAYS.between(intervalStart, now.atZone(zone)).toInt() + 1
            else null
            if (elapsedDays != null) {
                // 지난달이 더 짧으면(예: 3월 30일 → 2월) 그 달 끝에서 멈춘다
                previousEnd = minOf(previousStart.plusDays(elapsedDays.toLong()).toInstant(),
                                    previousIntervalEnd)
                comparisonDays = ChronoUnit.DAYS.between(previousStart, previousEnd.atZone(zone)).toInt()
            } else {
                previousEnd = previousIntervalEnd
                comparisonDays = null
            }
            val previousStartInstant = previousStart.toInstant()
            val inPrevious = runs.filter { it.start >= previousStartInstant && it.start < previousEnd }

            fun totalKm(list: List<RunSummary>): Double =
                list.mapNotNull { it.distanceKm }.sum()

            /// 시간 가중 평균 페이스 = 총 시간 ÷ 총 거리.
            /// 페이스 가드(paceSecPerKm)를 통과한 기록만 분자·분모 모두에 넣는다 — 0초 5.2km 임포트가
            /// 거리만 더해 월 평균을 과하게 빠르게 만들지 않게 (이슈 #78)
            fun avgPace(list: List<RunSummary>): Double? {
                val valid = list.filter { it.paceSecPerKm != null }
                val km = totalKm(valid)
                if (!(km > 0.1)) return null
                return valid.map { it.durationSec }.sum() / km
            }
            fun avgHR(list: List<RunSummary>): Double? {
                val samples = list.mapNotNull { it.avgHeartRate }
                if (samples.isEmpty()) return null
                return samples.sum() / samples.size
            }

            val total = totalKm(inMonth)
            val previousTotal = totalKm(inPrevious)

            // 월 내 주차 (1일부터 7일 단위 — 달력 주 대신 단순 분할이 라벨과 맞다)
            val dayCount = YearMonth.from(month.atZone(zone)).lengthOfMonth()
            val weekCount = ceil(dayCount.toDouble() / 7).toInt()
            // '주 N회' 분모 — 진행 중인 달은 지난 날수만 센다. 월 전체 일수로 나누면 월초일수록
            // 과소 표시된다 (이슈 #75: 9.10에 4회 → 0.9회가 아니라 2.8회)
            val perWeekDays = elapsedDays ?: dayCount
            // 7일 미만 경과면 내지 않는다 — 월 1일에 1회면 "주 7.0회"로 외삽된다 (이슈 #86, 표본 부족 가드)
            val perWeek: Double? = if (perWeekDays >= 7) inMonth.size.toDouble() / (perWeekDays.toDouble() / 7) else null
            val weeks = (0 until weekCount).map { index ->
                val weekStart = start.plusSeconds(index.toLong() * 7 * 86_400)
                val weekEnd = minOf(weekStart.plusSeconds(7L * 86_400), end)
                val km = inMonth.filter { it.start >= weekStart && it.start < weekEnd }
                    .mapNotNull { it.distanceKm }.sum()
                WeeklyReport.WeekBar(label = "${index + 1}주", km = km,
                                     isCurrent = false, index = index)
            }

            val pace = avgPace(inMonth)
            val previousPace = avgPace(inPrevious)
            val hr = avgHR(inMonth)
            val previousHR = avgHR(inPrevious)
            val ordered = inMonth.sortedBy { it.start }

            // iOS DateFormatter ko_KR "yyyy년 M월" — 직접 조립한다
            val label = month.atZone(zone)

            return MonthlyStats(monthLabel = "${label.year}년 ${label.monthValue}월",
                                totalKm = total,
                                deltaPct = if (previousTotal >= 3) (total - previousTotal) / previousTotal * 100 else null,
                                comparisonDays = comparisonDays,
                                weeks = weeks,
                                avgPaceSec = pace,
                                paceDeltaSec = if (pace != null && previousPace != null) pace - previousPace else null,
                                avgHeartRate = hr,
                                heartRateDelta = if (hr != null && previousHR != null) hr - previousHR else null,
                                count = inMonth.size,
                                perWeek = perWeek,
                                totalDurationSec = inMonth.map { it.durationSec }.sum(),
                                pacePoints = ordered.mapNotNull { it.paceSecPerKm },
                                heartRatePoints = ordered.mapNotNull { it.avgHeartRate },
                                runs = inMonth)
        }

        /// `calendar.dateInterval(of: .month, for:)?.start` — 그 시각이 속한 달의 1일 자정
        private fun monthStart(date: Instant, zone: ZoneId): ZonedDateTime =
            date.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
    }
}

// MARK: - 러닝 세션 표시 이름

/// "일요일 롱런" / "화요일 러닝" — 세션 목록·상세 제목
/// (iOS DateFormatter ko_KR "EEEE" — 요일 이름을 직접 조립한다)
fun RunSummary.displayTitle(zone: ZoneId): String {
    val weekday = when (start.atZone(zone).dayOfWeek) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        DayOfWeek.SUNDAY -> "일요일"
    }
    val kind = if ((distanceKm ?: 0.0) >= 15) "롱런" else "러닝"
    return "$weekday $kind"
}

/// "1:52:34 · 5′20″/km · 152 bpm"
val RunSummary.metaLine: String
    get() {
        val parts = mutableListOf(Format.duration(durationSec))
        paceSecPerKm?.let { parts.add(Format.paceKm(it)) }
        avgHeartRate?.let { parts.add("${it.swiftRoundedInt()} bpm") }
        return parts.joinToString(" · ")
    }
