package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/// 성장 단계 6단계 (기획서 §5) — 알에서 나는 새까지. raw 값은 저장·래칫 비교용 (iOS rawValue 대응)
enum class GrowthStage(val raw: Int) {
    EGG(1), CRACKED_EGG(2), HATCHLING(3), FLEDGLING(4), FLAPPING(5), FLYING(6);

    val label: String
        get() = when (this) {
            EGG -> "알"
            CRACKED_EGG -> "금 간 알"
            HATCHLING -> "부화"
            FLEDGLING -> "어린 새"
            FLAPPING -> "날갯짓"
            FLYING -> "나는 새"
        }

    /// 이 단계에 들어서는 누적 XP 문턱
    val xpThreshold: Int
        get() = when (this) {
            EGG -> 0
            CRACKED_EGG -> 50
            HATCHLING -> 200
            FLEDGLING -> 500
            FLAPPING -> 1_000
            FLYING -> 1_800
        }

    /// 다음 단계 — 최종 단계(나는 새)면 null
    val next: GrowthStage? get() = fromRaw(raw + 1)

    companion object {
        fun fromRaw(raw: Int): GrowthStage? = entries.find { it.raw == raw }
    }
}

/// 현재 사이클의 성장 상태 스냅샷 — 홈 탭 새 카드가 그대로 그린다
data class GrowthState(
    val xp: Int,
    val stage: GrowthStage,
    val xpIntoStage: Int,        // 현재 단계에 들어선 뒤 쌓은 XP
    val xpToNextStage: Int?,     // 다음 단계까지 남은 XP (최종 단계면 null)
    val progress: Double,        // 현재 단계 게이지 0.0~1.0
    val isSulky: Boolean,        // 7일 이상 안 뛰면 시무룩
    val daysSinceLastRun: Int?,  // 마지막 러닝 후 경과일 (기록 없으면 null)
)

/// 성장 시스템 엔진 (기획서 §5) — XP 적립·단계 판정·시무룩 상태를 결정론적으로 계산한다.
/// iOS `GrowthEngine.swift` 이식. 순수 로직 — 사이클 시작 시각·최고 단계(래칫)·주간 목표를
/// 입력으로 받고, 저장(GrowthKey)은 스토어 계층 몫이다.
///
/// XP 규칙 (기획서 §5):
/// - 세션: 1km 미만은 0 XP(산책 구분). 이상이면 기본 10 XP + 1km당 1 XP (거리분은 21km 캡)
/// - 하루 합산 40 XP 캡 — 하루 여러 번 뛰어도 과속 적립을 막는다
/// - 주간 목표 달성 보너스 30 XP — 지난 주까지만 정산 (진행 중인 주는 아직 미확정)
/// - 4주 연속 달성마다 스트릭 보너스 50 XP
object GrowthEngine {
    private const val BASE_XP = 10
    private const val PER_KM_XP = 1
    private const val PER_SESSION_DISTANCE_CAP = 21  // 풀코스를 뛰어도 거리 XP는 하프 수준까지
    private const val WEEKLY_GOAL_XP = 30
    private const val STREAK_BONUS_XP = 50
    private const val STREAK_BONUS_INTERVAL_WEEKS = 4
    private const val DAILY_XP_CAP = 40
    private const val SULKY_AFTER_DAYS = 7

    fun state(
        runs: List<RunSummary>,
        cycleStartedAt: Instant,
        maxStage: Int,
        weeklyGoal: Int,
        now: Instant,
    ): GrowthState {
        val cycleRuns = runs.filter { it.start >= cycleStartedAt && it.start <= now }
        val xp = sessionXp(cycleRuns) + weeklyBonusXp(cycleRuns, cycleStartedAt, weeklyGoal, now)

        // 단계는 XP로 계산하되 저장된 최고 단계 아래로는 안 내려간다 (래칫 — "성장은 되돌리지 않는다")
        val computed = GrowthStage.entries.last { it.xpThreshold <= xp }
        val floorStage = GrowthStage.fromRaw(maxStage) ?: GrowthStage.EGG
        val stage = if (computed.raw >= floorStage.raw) computed else floorStage

        val intoStage = max(0, xp - stage.xpThreshold)
        val next = stage.next
        val xpToNext: Int?
        val progress: Double
        if (next == null) {
            xpToNext = null
            progress = 1.0
        } else {
            xpToNext = max(0, next.xpThreshold - xp)
            val span = next.xpThreshold - stage.xpThreshold
            progress = if (span > 0) min(1.0, intoStage.toDouble() / span) else 1.0
        }

        // 시무룩 판정은 사이클과 무관하게 "마지막으로 달린 날" 기준 (자정 경계)
        val lastRun = runs.maxByOrNull { it.start }
        val daysSinceLastRun = lastRun?.let {
            ChronoUnit.DAYS.between(dayOf(it.start), dayOf(now)).toInt()
        }
        val isSulky = (daysSinceLastRun ?: 0) >= SULKY_AFTER_DAYS && lastRun != null

        return GrowthState(
            xp = xp,
            stage = stage,
            xpIntoStage = intoStage,
            xpToNextStage = xpToNext,
            progress = progress,
            isSulky = isSulky,
            daysSinceLastRun = daysSinceLastRun,
        )
    }

    // MARK: - 내부

    /// 세션 XP — 날짜별로 묶어 하루 캡을 적용한 뒤 합산
    private fun sessionXp(cycleRuns: List<RunSummary>): Int =
        cycleRuns.groupBy { dayOf(it.start) }.values.sumOf { dayRuns ->
            min(DAILY_XP_CAP, dayRuns.sumOf { xpForRun(it) })
        }

    private fun xpForRun(run: RunSummary): Int {
        val km = run.distanceKm ?: 0.0
        if (km < 1.0) return 0
        return BASE_XP + min(PER_SESSION_DISTANCE_CAP, floor(km).toInt()) * PER_KM_XP
    }

    /// 주간 보너스 — 사이클 시작 주부터 지난 주까지 주 단위로 목표 달성을 정산한다.
    /// 진행 중인 주는 아직 결과가 안 정해졌으므로 제외 (미확정 보너스를 미리 주지 않는다)
    private fun weeklyBonusXp(
        cycleRuns: List<RunSummary>,
        cycleStartedAt: Instant,
        weeklyGoal: Int,
        now: Instant,
    ): Int {
        if (weeklyGoal <= 0) return 0
        val countsByWeek = cycleRuns.groupingBy { weekStart(it.start) }.eachCount()
        val currentWeek = weekStart(now)
        var cursor = weekStart(cycleStartedAt)
        var bonus = 0
        var consecutive = 0
        while (cursor < currentWeek) {
            if ((countsByWeek[cursor] ?: 0) >= weeklyGoal) {
                bonus += WEEKLY_GOAL_XP
                consecutive += 1
                if (consecutive % STREAK_BONUS_INTERVAL_WEEKS == 0) bonus += STREAK_BONUS_XP
            } else {
                consecutive = 0
            }
            cursor = cursor.plusWeeks(1)
        }
        return bonus
    }
}
