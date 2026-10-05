package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/// 훈련 계획 캘린더 엔진 (이슈 #189) — 목표 대회 날짜에서 역산한 주차별 처방.
/// 순수 로직: Foundation만 쓰고 now·레벨·달력을 값으로 주입받아 결정론적이다.
///
/// 새 산식이 아니라 `TrainingGuideEngine.guide()`의 **이번 주** 처방 가정을 미래 주로
/// 그대로 늘린 것이다 — 그래서 현재 주의 단계·주간 거리는 guide()와 항상 같다 (테스트로 고정).
/// - 단계: 주 k(현재 주 = 0)의 남은 일수를 `max(0, daysToRace − 7k)`로 보고
///   `TrainingGuideEngine.phase`에 넣는다 (피크 3주·강화 4주·테이퍼 종목별 — guide()와 같은 가정).
///   대회 주간은 대회 날짜가 속한 마지막 달력 주 하나만이다 — 그 앞 주는 남은 일수를 7일 이상으로 받친다.
/// - 볼륨: 만성 부하(최근 28일 km ÷ 4)에서 10% 룰로 주마다 ×1.1씩 올리고
///   종목·레벨별 피크 주간 거리(`peakWeeklyKm`)에서 멈춘다 (10% 룰 — Gabbett 2016, guide()와 같은 상한).
///   주 k 범위 = (k == 0 ? 만성 부하 : 전 주 상한) … min(만성 부하 × 1.1^(k+1), 피크).
///   현재 주는 guide()의 `만성 부하 … min(만성 부하 × 1.1, 피크)`와 같다.
/// - 테이퍼: 마지막 기초·강화·피크 주의 상한(없으면 min(만성 부하, 피크))의 60~70%,
///   대회 주간은 40~50% — guide()와 같은 배수 (일반 플랜 관례, 가정).
/// - LSD: 주간 하한 × 25% … 상한 × 35%, 대회 주간은 0 — guide()의 배터리 비제한 경로와 같다.
///   계획은 배터리를 보지 않는다 — 배터리는 '오늘' 신호이고 계획은 몇 주짜리 구조라서다.
///   오늘 컨디션에 따른 하향은 홈 판정·이번 주 처방이 맡는다.
/// - 퀄리티: `TrainingGuideEngine.qualityMix(phase:level:batteryLimited: false)`.
///
/// 가드 (guide()와 같음 + 지평): 가장 오래된 러닝이 21일 전보다 최근이거나 만성 부하가
/// 주 3km 미만이면 nil. 대회가 지났거나 24주보다 멀어도 nil — 24주 뒤 볼륨을 오늘의
/// 만성 부하로 외삽하는 건 근거가 없다. "틀린 인사이트는 없느니만 못하다."

data class TrainingPlan(
    val race: RaceDistance,
    val raceDate: Instant,
    /// D-day (대회 당일 = 0)
    val daysToRace: Int,
    /// 이 종목·레벨의 권장 피크 주간 거리 — 점증이 여기서 멈춘다
    val peakWeeklyKm: Double,
    /// 오래된 → 대회 주간. 지난 주(최대 4개) + 이번 주 + 미래 주
    val weeks: List<Week>,
) {
    data class Week(
        /// ISO 8601 달력 주(월요일 시작) — ZoneDistributionEngine.compute와 같은 경계
        val weekStart: Instant,
        /// "10월 2째주" — Format.weekLabel(weekStart:)
        val label: String,
        /// 지난 주(계획 없음)는 nil
        val phase: TrainingGuide.Phase?,
        /// 계획 주간 거리(km) — 지난 주는 nil
        val weeklyKmLow: Double?,
        val weeklyKmHigh: Double?,
        /// 롱런(LSD) 거리(km) — 지난 주는 nil, 대회 주간은 0
        val lsdKmLow: Double?,
        val lsdKmHigh: Double?,
        /// 퀄리티 세션 권장 횟수 — 지난 주는 0
        val tempoCount: Int,
        val intervalCount: Int,
        /// 실제 달린 거리(km) — 지난 주는 그 주 합계, 이번 주는 지금까지 합계, 미래 주는 nil
        val actualKm: Double?,
        val isCurrent: Boolean,
    ) {
        val id: Instant get() = weekStart
    }
}

object TrainingPlanEngine {
    /// 대회가 이보다 멀면 nil — 24주 뒤 볼륨을 오늘 만성 부하로 외삽하면 근거가 없다
    const val maxHorizonWeeks = 24
    /// 나란히 보여줄 지난 주 수
    const val pastWeeks = 4

    /// (Android: iOS `calendar: Calendar = .current` 대신 `zone`을 받는다)
    fun plan(runs: List<RunSummary>, race: RaceDistance, level: RunnerLevel,
             raceDate: Instant, now: Instant, zone: ZoneId): TrainingPlan? {
        // 가드: guide()와 같은 3주(21일)·만성 부하 주 3km (이슈 #49)
        val oldest = runs.minOfOrNull { it.start } ?: return null
        if (!(oldest <= now.minusSeconds(21L * 86_400))) return null
        val chronic = km(runs, from = now.minusSeconds(28L * 86_400), to = now, inclusive = false) / 4
        if (!(chronic >= 3)) return null

        // 주 경계는 Format.weekLabel과 같은 ISO 8601 달력 주 (ZoneDistributionEngine과 같은 방식)
        // 날짜 차이(일) — 자정 경계 기준 (TrainingGuideEngine.days와 같은 방식, 그쪽은 private)
        val daysToRace = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(),
                                                 raceDate.atZone(zone).toLocalDate()).toInt()
        if (!(daysToRace >= 0 && daysToRace / 7 <= maxHorizonWeeks)) return null
        val currentWeekStart = isoWeekStart(now, zone)
        val raceWeekStart = isoWeekStart(raceDate, zone)

        fun weekStart(offset: Int): Instant =
            currentWeekStart.plusWeeks(offset.toLong()).atStartOfDay(zone).toInstant()

        // 지난 주 — 기록 시작 이후에 시작한 주만. 기록 없는 주가 "실제 0km"로 오해되지 않게
        val past = (1..pastWeeks).reversed().mapNotNull { back ->
            val start = weekStart(-back)
            if (!(start >= oldest)) return@mapNotNull null
            TrainingPlan.Week(weekStart = start, label = Format.weekLabel(weekStart = start, zone = zone),
                              phase = null,
                              weeklyKmLow = null, weeklyKmHigh = null,
                              lsdKmLow = null, lsdKmHigh = null,
                              tempoCount = 0, intervalCount = 0,
                              actualKm = km(runs, from = start, to = weekStart(-back + 1),
                                            inclusive = false),
                              isCurrent = false)
        }

        // 이번 주부터 대회 날짜가 속한 주까지
        val futureCount = ChronoUnit.WEEKS.between(currentWeekStart, raceWeekStart).toInt() + 1
        val peak = TrainingGuideEngine.peakWeeklyKm(race = race, level = level)
        var lastBuiltHigh: Double? = null   // 마지막 기초·강화·피크 주의 상한 — 테이퍼 감량의 기준
        val future = (0 until futureCount).map { k ->
            val start = weekStart(k)
            // 대회 주간은 마지막(대회 날짜가 속한) 달력 주 하나뿐이다 — 대회가 오늘보다 이른 요일이면
            // 굴러가는 식 `daysToRace − 7k`가 그 앞 주에서도 6일 이하로 떨어져 대회 주간이 둘이 되므로
            // 마지막 주가 아니면 7일 이상으로 받쳐 단계가 테이퍼 밑으로 내려가지 않게 한다
            val isLast = k == futureCount - 1
            val remaining = if (isLast) max(0, daysToRace - 7 * k) else max(7, daysToRace - 7 * k)
            val phase = TrainingGuideEngine.phase(daysToRace = remaining, race = race)
            val low: Double
            val high: Double
            when (phase) {
                TrainingGuide.Phase.taper -> {
                    val built = lastBuiltHigh ?: min(chronic, peak)
                    low = built * 0.6
                    high = built * 0.7
                }
                TrainingGuide.Phase.raceWeek -> {
                    val built = lastBuiltHigh ?: min(chronic, peak)
                    low = built * 0.4
                    high = built * 0.5
                }
                else -> {
                    // 10% 룰 점증 — 주 k 상한 = min(만성 × 1.1^(k+1), 피크), 하한 = 전 주 상한
                    val built = min(chronic * 1.1.pow((k + 1).toDouble()), peak)
                    val floor = if (k == 0) chronic else min(chronic * 1.1.pow(k.toDouble()), peak)
                    low = floor
                    high = max(floor, built)
                    lastBuiltHigh = high
                }
            }
            val quality = TrainingGuideEngine.qualityMix(phase = phase, level = level,
                                                         batteryLimited = false)
            TrainingPlan.Week(weekStart = start, label = Format.weekLabel(weekStart = start, zone = zone),
                              phase = phase,
                              weeklyKmLow = low, weeklyKmHigh = high,
                              lsdKmLow = if (phase == TrainingGuide.Phase.raceWeek) 0.0 else low * 0.25,
                              lsdKmHigh = if (phase == TrainingGuide.Phase.raceWeek) 0.0 else high * 0.35,
                              tempoCount = quality.tempo, intervalCount = quality.interval,
                              actualKm = if (k == 0) km(runs, from = start, to = now, inclusive = true) else null,
                              isCurrent = k == 0)
        }

        return TrainingPlan(race = race, raceDate = raceDate, daysToRace = daysToRace,
                            peakWeeklyKm = peak, weeks = past + future)
    }

    /// [from, to) 또는 [from, to] 창에 시작한 러닝의 거리 합(km) — 거리 없는 세션은 건너뛴다
    private fun km(runs: List<RunSummary>, from: Instant, to: Instant, inclusive: Boolean): Double =
        runs.filter { it.start >= from && (if (inclusive) it.start <= to else it.start < to) }
            .mapNotNull { it.distanceKm }
            .fold(0.0) { acc, v -> acc + v }
}
