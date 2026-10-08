package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/// 성장 시스템 단계 — 알에서 나는 새까지 6단계 (기획서 §5)
///
/// 임계값은 누적 XP 기준. `next`가 nil이면 성조(사이클 완성)다.
enum class GrowthStage(val rawValue: Int) {
    egg(1),
    crackedEgg(2),
    hatchling(3),
    fledgling(4),
    flapping(5),
    flying(6);

    val label: String
        get() = when (this) {
            egg -> "알"
            crackedEgg -> "금 간 알"
            hatchling -> "부화"
            fledgling -> "어린 새"
            flapping -> "날갯짓"
            flying -> "나는 새"
        }

    /// 이 단계 진입에 필요한 누적 XP (기획서 §5 표)
    val threshold: Int
        get() = when (this) {
            egg -> 0
            crackedEgg -> 50
            hatchling -> 200
            fledgling -> 500
            flapping -> 1_000
            flying -> 1_800
        }

    val next: GrowthStage? get() = fromRawValue(rawValue + 1)

    companion object {
        fun fromRawValue(rawValue: Int): GrowthStage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// 홈 화면이 그리는 성장 상태 스냅샷 — GrowthEngine.state()의 반환값
data class GrowthState(
    val xp: Int,
    val stage: GrowthStage,
    /// 현재 단계 임계값 이후로 쌓인 XP (게이지 분자)
    val xpIntoStage: Int,
    /// 다음 단계까지 남은 XP. 성조(flying)면 nil
    val xpToNextStage: Int?,
    /// 게이지 채움 비율 0...1. 성조는 1.0
    val progress: Double,
    /// 마지막 러닝으로부터 7일 이상 지났는지 — 새 표정 분기용 (기획서 §5)
    val isSulky: Boolean,
    /// 마지막 러닝으로부터 경과일. 러닝이 아예 없으면 nil
    val daysSinceLastRun: Int?,
)

/// 주간 목표 변경 이력 1건 — (변경 시각, 변경 직전 목표) (이슈 #108, #116).
///
/// 변경을 전부 보관한다(사용자 결정 2026-09-30) — 1건만 두면 두 번째 변경이 첫 기록을 덮어
/// 첫 변경 이전 주까지 낮춘 목표로 소급 판정되기 때문이다. 이력은 시각 오름차순이다
@Serializable
data class WeeklyGoalChange(
    @Serializable(with = ReferenceDateInstantSerializer::class) val at: Instant,
    val before: Int,
)

/// 성장 시스템 — 알에서 나는 새까지 XP·단계를 계산하는 순수 로직 (기획서 §5)
///
/// **XP 원장을 저장하지 않는다.** `cycleStartedAt` 이후의 HealthKit 러닝 이력에서
/// 매번 결정론적으로 재계산한다 — 저장·동기화 문제가 사라진다.
/// 저장할 값은 주간 목표·사이클 시작 시각·이번 사이클 최고 단계·수집 도감뿐이며,
/// 이 엔진은 그 저장값(cycleStartedAt, maxStage)을 입력으로만 받는다.
/// (Android: iOS `Calendar(identifier: .iso8601)` + `.current` 자리에 `zone`을 주입받는다. 주는 ISO 주의 월요일 날짜로 다룬다)
object GrowthEngine {
    /// 러닝 1회 완료(1km 이상) 기본 보상
    private const val baseXp = 10
    /// 거리 1km당 보너스
    private const val perKmXp = 1
    /// 세션당 거리 보너스 상한 (21km ≈ 하프 지점, 그 이상은 과부하 비보상)
    private const val perSessionDistanceCap = 21
    /// 주간 목표 달성 보너스
    private const val weeklyGoalXp = 30
    /// 주간 목표 4주 연속 달성 보너스 (연속 4주 단위마다 1회)
    private const val streakBonusXp = 50
    private const val streakBonusIntervalWeeks = 4
    /// 몰아 뛰기 방지 — 하루(사용자 캘린더 기준) XP 상한
    private const val dailyXpCap = 40

    /// 러닝 이력·사이클 시작 시각·주간 목표로 현재 성장 상태를 산출한다.
    ///
    /// - Parameters:
    ///   - runs: 전체 러닝 이력 (온보딩 이전 이력 포함해도 무방 — cycleStartedAt으로 걸러낸다)
    ///   - cycleStartedAt: 이번 사이클(첫 사이클 = 온보딩) 시작 시각. 이전 러닝은 XP 미산입
    ///   - maxStage: 저장된 이번 사이클 최고 도달 단계 (rawValue). "성장은 되돌리지 않는다" 하한
    ///   - weeklyGoal: 주간 목표 러닝 횟수 (Q5 초기값 · 설정에서 변경)
    ///   - weeklyGoalChanges: 주간 목표 변경 이력(시각 오름차순). 비어 있으면 변경 없음 —
    ///     모든 주를 현재 목표로 판정한다 (이슈 #108, #116)
    ///   - now: 판정 기준 시각 (결정론을 위한 주입)
    fun state(runs: List<RunSummary>, cycleStartedAt: Instant, maxStage: Int,
              weeklyGoal: Int, weeklyGoalChanges: List<WeeklyGoalChange> = emptyList(),
              now: Instant, zone: ZoneId): GrowthState {
        // 월요일 시작 — streakWeeks와 동일 방식 (zone = iOS Calendar(.iso8601) + .current)

        val cycleRuns = runs.filter { it.start >= cycleStartedAt && it.start <= now }

        val xp = totalXp(runs = cycleRuns, cycleStartedAt = cycleStartedAt,
                         weeklyGoal = weeklyGoal, weeklyGoalChanges = weeklyGoalChanges,
                         zone = zone, now = now)
        val computedStage = stage(forXp = xp)
        val savedStage = GrowthStage.fromRawValue(maxStage) ?: GrowthStage.egg
        // 표시 단계 = max(계산 단계, 저장된 최고 단계) — 되돌리지 않는다
        val displayStage = GrowthStage.fromRawValue(max(computedStage.rawValue, savedStage.rawValue)) ?: computedStage

        val (xpIntoStage, xpToNextStage, progress) = gauge(xp = xp, stage = displayStage)

        val lastRun = runs.maxOfOrNull { it.start }
        val daysSinceLastRun = lastRun?.let {
            ChronoUnit.DAYS.between(it.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()).toInt()
        }
        // 러닝이 아예 없으면 시무룩이 아니다 — 첫 실행 상태는 시무룩과 다르다
        val isSulky = (daysSinceLastRun ?: 0) >= 7 && lastRun != null

        return GrowthState(xp = xp, stage = displayStage, xpIntoStage = xpIntoStage,
                           xpToNextStage = xpToNextStage, progress = progress,
                           isSulky = isSulky, daysSinceLastRun = daysSinceLastRun)
    }

    /// 누적 XP → 단계. 임계값을 순서대로 넘는 마지막 단계를 고른다.
    private fun stage(forXp: Int): GrowthStage {
        var result = GrowthStage.egg
        for (candidate in GrowthStage.entries) {
            if (forXp >= candidate.threshold) result = candidate
        }
        return result
    }

    /// 주간 목표를 바꿀 때 저장할 변경 이력 — 기존 이력 뒤에 (변경 시각, 변경 직전 목표)를 붙인다 (이슈 #108, #116).
    ///
    /// 같은 ISO 주에 이미 항목이 있으면 이력을 그대로 둔다 — 변경한 주는 그 주 첫 변경 전
    /// 목표로 판정해야 하므로(3→2→1로 나눠 바꿔도 그 주는 3으로 판정) `before`를 덮지 않는다.
    /// 다른 주의 항목은 지우지 않는다 — 각 변경이 "변경한 주까지는 변경 전 목표"를 따로 지킨다.
    fun recordWeeklyGoalChange(history: List<WeeklyGoalChange>, oldGoal: Int,
                               now: Instant, zone: ZoneId): List<WeeklyGoalChange> {
        // weeklyBonusXp와 같은 주 경계
        val nowWeekStart = isoWeekStart(now, zone)
        if (history.any { isoWeekStart(it.at, zone) == nowWeekStart }) {
            return history
        }
        return history + WeeklyGoalChange(at = now, before = oldGoal)
    }

    /// 현재 단계 안에서의 진행률 — (단계 내 XP, 다음 단계까지 남은 XP, 게이지 비율)
    private fun gauge(xp: Int, stage: GrowthStage): Triple<Int, Int?, Double> {
        val intoStage = max(0, xp - stage.threshold)
        val next = stage.next ?: return Triple(intoStage, null, 1.0)
        val span = next.threshold - stage.threshold
        val remaining = max(0, next.threshold - xp)
        val progress = if (span > 0) min(1.0, intoStage.toDouble() / span.toDouble()) else 1.0
        return Triple(intoStage, remaining, progress)
    }

    /// 이번 사이클 총 XP = 세션 XP 합(하루 상한 적용) + 주간 목표 달성 보너스 + 4주 연속 보너스
    private fun totalXp(runs: List<RunSummary>, cycleStartedAt: Instant, weeklyGoal: Int,
                        weeklyGoalChanges: List<WeeklyGoalChange>,
                        zone: ZoneId, now: Instant): Int =
        sessionXp(runs = runs, zone = zone) +
            weeklyBonusXp(runs = runs, cycleStartedAt = cycleStartedAt, weeklyGoal = weeklyGoal,
                          weeklyGoalChanges = weeklyGoalChanges, zone = zone, now = now)

    /// 러닝 세션 XP — 날짜별로 묶어 하루 상한 40을 적용한 합
    private fun sessionXp(runs: List<RunSummary>, zone: ZoneId): Int {
        val byDay = runs.groupBy { it.start.atZone(zone).toLocalDate() }
        return byDay.values.fold(0) { total, dayRuns ->
            val dayXp = dayRuns.fold(0) { acc, run -> acc + xp(run) }
            total + min(dailyXpCap, dayXp)
        }
    }

    /// 러닝 1회 완료 인정 기준 — 1km 이상 (기획서 §5 '러닝 1회 완료(1km 이상)').
    /// 세션 XP·주간 목표 판정·홈 주간 목표 칩·브리핑 횟수가 모두 이 한 곳을 공유한다
    fun countsAsCompletedRun(run: RunSummary): Boolean {
        val km = run.distanceKm ?: return false
        return km >= 1.0
    }

    /// 러닝 1회 XP — 1km 미만은 0(완료 인정 안 함). 그 외 기본 10 + km당 1(세션당 21 상한)
    private fun xp(run: RunSummary): Int {
        if (!countsAsCompletedRun(run)) return 0
        val km = run.distanceKm ?: return 0
        val distanceBonus = min(perSessionDistanceCap, floor(km).toInt() * perKmXp)
        return baseXp + distanceBonus
    }

    /// 주간 목표 달성(+30) 및 4주 연속 달성(+50, 4주 단위마다) 보너스.
    /// 완결된 주(사이클 시작 주 ~ 이전 주. 진행 중인 이번 주는 제외 — streakWeeks와 동일한 원칙)만 판정한다.
    ///
    /// 주간 목표 변경은 다음 주부터 적용한다 (이슈 #108, 사용자 결정 2026-09-30) — 변경한 주까지는
    /// 변경 전 목표(`before`), 그다음 주부터 현재 목표로 판정한다. 변경이 여러 번이면 각 변경마다
    /// 같은 규칙을 적용한다 (이슈 #116): 주 W의 목표 = W 이후(같은 주 포함)에 한 변경 중 가장 이른
    /// 변경의 `before`, 그런 변경이 없으면(모든 변경보다 뒤인 주) 현재 목표. 과거 주를 전부 현재 목표로
    /// 다시 판정하면 목표를 1로 낮추는 즉시 지난 주들에 +30·연속 +50이 붙어 단계가 한 번에 오르고,
    /// maxStage는 되돌리지 않으므로 그 상승이 영구화된다.
    private fun weeklyBonusXp(runs: List<RunSummary>, cycleStartedAt: Instant, weeklyGoal: Int,
                              weeklyGoalChanges: List<WeeklyGoalChange>,
                              zone: ZoneId, now: Instant): Int {
        if (weeklyGoal <= 0) return 0
        val cycleStart = isoWeekStart(cycleStartedAt, zone)
        val currentWeekStart = isoWeekStart(now, zone)

        // 세션 XP와 같은 기준 — 1km 미만 러닝은 주간 횟수에도 세지 않는다
        val countsByWeek: Map<LocalDate, Int> = runs.filter(::countsAsCompletedRun)
            .groupingBy { isoWeekStart(it.start, zone) }
            .eachCount()

        // cycleStart 주부터 currentWeekStart 이전 주(완결된 주)까지 오래된 순으로 순회
        val weeks = mutableListOf<LocalDate>()
        var cursor = cycleStart
        while (cursor < currentWeekStart) {
            weeks.add(cursor)
            cursor = cursor.plusWeeks(1)
        }

        // 변경마다 (변경 시각이 속한 주의 시작, 변경 전 목표) — 시각 오름차순이라 같은 주가 겹쳐도 첫 변경이 앞선다
        val changeWeeks: List<Pair<LocalDate, Int>> = weeklyGoalChanges
            .sortedBy { it.at }
            .map { change -> isoWeekStart(change.at, zone) to change.before }

        var bonus = 0
        var consecutive = 0
        for (week in weeks) {
            // 이 주 이후(같은 주 포함)에 한 가장 이른 변경의 변경 전 목표 — 없으면 현재 목표
            val goal = changeWeeks.firstOrNull { (weekStart, _) -> week <= weekStart }?.second ?: weeklyGoal
            // 변경 전 목표가 0 이하(미설정)면 그 주는 달성으로 치지 않는다
            val achieved = goal > 0 && (countsByWeek[week] ?: 0) >= goal
            if (achieved) {
                bonus += weeklyGoalXp
                consecutive += 1
                if (consecutive % streakBonusIntervalWeeks == 0) {
                    bonus += streakBonusXp
                }
            } else {
                consecutive = 0
            }
        }
        return bonus
    }
}
