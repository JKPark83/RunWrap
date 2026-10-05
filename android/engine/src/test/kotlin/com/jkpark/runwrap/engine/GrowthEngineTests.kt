package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 성장 시스템(GrowthEngine) 검증 — XP 산식·단계 경계·되돌리지 않음·시무룩 판정.
/// now는 2026-08-13(목) — ISO 주 기준 이번 주 시작은 2026-08-10(월).
class GrowthEngineTests {
    private val now = iso("2026-08-13T09:00:00Z")
    /// 별다른 지정이 없으면 사이클은 아주 오래 전에 시작한 것으로 두어
    /// "cycleStartedAt 필터"가 결과에 끼어들지 않게 한다.
    private val farPastCycleStart = iso("2020-01-01T00:00:00Z")

    private fun run(daysAgo: Double, km: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * 6 * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = 150.0)

    private fun ago(seconds: Long): Instant = now.minusSeconds(seconds)

    @Test
    @DisplayName("XP 산식 — 5km 러닝 1회 = 기본 10 + 거리 5 = 15")
    fun basicSessionXp() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(15, state.xp)
    }

    @Test
    @DisplayName("세션 상한 — 30km 러닝 = 기본 10 + 거리 보너스 21(상한) = 31")
    fun sessionDistanceBonusCap() {
        // 30km → floor(30) * 1 = 30, 세션당 21 상한 적용 → 10 + 21 = 31
        val runs = listOf(run(daysAgo = 1.0, km = 30.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(31, state.xp)
    }

    @Test
    @DisplayName("하루 상한 40 — 같은 날 20km + 20km는 (10+20)+(10+20)=60이 아니라 40으로 잘린다")
    fun dailyXpCap() {
        // 각 세션 20km → 10 + min(21, 20) = 30. 두 세션 합 60 → 하루 상한 40
        val runs = listOf(run(daysAgo = 1.0, km = 20.0), run(daysAgo = 1.1, km = 20.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(40, state.xp)
    }

    @Test
    @DisplayName("1km 미만은 완료로 인정하지 않아 XP 0")
    fun belowOneKmYieldsZeroXp() {
        val runs = listOf(run(daysAgo = 1.0, km = 0.5))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(0, state.xp)
        assertEquals(GrowthStage.egg, state.stage)
    }

    @Test
    @DisplayName("주간 목표 달성 — 완결된 지난주에 목표 2회를 채우면 세션 XP 외에 +30")
    fun weeklyGoalBonus() {
        // 지난주(2026-08-03 월 ~ 08-09 일)에 3km 러닝 2회 = 목표 2회 달성.
        // 세션 XP: (10+3) * 2 = 26. 주간 보너스 +30. 이번 주(진행 중)는 판정 제외.
        val runs = listOf(run(daysAgo = 8.0, km = 3.0), run(daysAgo = 6.0, km = 3.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 2, now = now, zone = testZone)
        assertEquals(26 + 30, state.xp)
    }

    @Test
    @DisplayName("주간 목표 — 1km 미만 러닝은 횟수에 세지 않아 0.5km 3회로는 목표 3회를 달성하지 못한다")
    fun subKmRunsDoNotCountTowardWeeklyGoal() {
        // 지난주(완결)에 0.5km 3회 — 세션 XP 0, 1km 기준으로 세면 0회 → 보너스 없음
        val runs = listOf(run(daysAgo = 8.0, km = 0.5), run(daysAgo = 7.0, km = 0.5), run(daysAgo = 6.0, km = 0.5))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals(0, state.xp)
        assertFalse(GrowthEngine.countsAsCompletedRun(run(daysAgo = 1.0, km = 0.5)))
        assertTrue(GrowthEngine.countsAsCompletedRun(run(daysAgo = 1.0, km = 1.0)))
    }

    @Test
    @DisplayName("4주 연속 달성 — 완결된 4주 각각 목표 채우면 주당 +30 네 번 + 연속 보너스 +50")
    fun fourWeekStreakBonus() {
        // cycleStartedAt을 4주 전 월요일로 맞추고, 그 이후 완결된 4개 ISO 주 각각에
        // 목표 1회(1km 러닝, XP 11)를 채운다. 이번 주(진행 중)는 판정에서 제외되므로
        // daysAgo는 8, 15, 22, 29일 전(각기 다른 완결된 주)로 배치한다.
        // (iOS Calendar(identifier: .iso8601) + .current → testZone의 ISO 주 월요일 자정)
        val cycleStart = isoWeekStart(now, testZone).minusWeeks(4).atStartOfDay(testZone).toInstant()
        val runs = listOf(run(daysAgo = 8.0, km = 1.0), run(daysAgo = 15.0, km = 1.0),
                          run(daysAgo = 22.0, km = 1.0), run(daysAgo = 29.0, km = 1.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = cycleStart,
                                       maxStage = 1, weeklyGoal = 1, now = now, zone = testZone)
        // 세션 XP: 11 * 4 = 44. 주간 보너스: 30 * 4 = 120. 4주 연속 보너스: +50.
        assertEquals(44 + 120 + 50, state.xp)
    }

    @Test
    @DisplayName("주간 목표 변경 — 이번 주에 3→1로 낮춰도 완결된 과거 주(러닝 1~2회)에는 +30이 붙지 않는다")
    fun weeklyGoalLoweredDoesNotRewardPastWeeks() {
        // 지난주(08-03 주) 3km 2회, 그 전 주(07-27 주) 3km 1회. 세션 XP: 13 * 3 = 39.
        // 변경 시각이 이번 주(08-10 주)라 완결된 두 주 모두 변경 전 목표 3으로 판정 → 둘 다 미달, 보너스 0
        val runs = listOf(run(daysAgo = 6.0, km = 3.0), run(daysAgo = 8.0, km = 3.0), run(daysAgo = 15.0, km = 3.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 1,
                                       weeklyGoalChanges = listOf(WeeklyGoalChange(at = now, before = 3)),
                                       now = now, zone = testZone)
        assertEquals(39, state.xp)
    }

    @Test
    @DisplayName("주간 목표 변경 — 변경한 주까지는 옛 목표, 다음 주부터는 새 목표 1회로 달성한다")
    fun weeklyGoalChangeAppliesFromNextWeek() {
        // 변경 시각 = 14일 전(07-30 목, 07-27 주)에 3→1.
        // 07-27 주(변경한 주) 3km 1회 → 옛 목표 3으로 판정 → 미달.
        // 08-03 주(변경 다음 주) 3km 1회 → 새 목표 1로 판정 → +30 (연속 1주라 연속 보너스 없음).
        // 세션 XP: 13 * 2 = 26. 합계 26 + 30 = 56
        val runs = listOf(run(daysAgo = 15.0, km = 3.0), run(daysAgo = 8.0, km = 3.0))
        val changedAt = ago(14L * 86_400)
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 1,
                                       weeklyGoalChanges = listOf(WeeklyGoalChange(at = changedAt, before = 3)),
                                       now = now, zone = testZone)
        assertEquals(26 + 30, state.xp)
    }

    @Test
    @DisplayName("주간 목표 변경 이력이 비어 있으면 기존처럼 모든 완결된 주를 현재 목표로 판정한다")
    fun weeklyGoalChangeNilKeepsLegacyBehavior() {
        // 첫 테스트와 같은 러닝. 현재 목표 1로 두 주(07-27 주 1회, 08-03 주 2회) 모두 달성 → +30 * 2.
        // 연속 2주라 4주 연속 보너스 없음. 세션 XP 39 + 60 = 99
        val runs = listOf(run(daysAgo = 6.0, km = 3.0), run(daysAgo = 8.0, km = 3.0), run(daysAgo = 15.0, km = 3.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 1,
                                       weeklyGoalChanges = emptyList(), now = now, zone = testZone)
        assertEquals(39 + 60, state.xp)
    }

    @Test
    @DisplayName("주간 목표 변경 이력 — 첫 변경·다른 주 변경은 뒤에 붙이고, 같은 주 재변경은 첫 변경 전 값을 유지한다")
    fun recordWeeklyGoalChange() {
        // 이력 없음 → [(now, 직전 값 3)]
        val first = GrowthEngine.recordWeeklyGoalChange(history = emptyList(), oldGoal = 3, now = now, zone = testZone)
        assertEquals(listOf(WeeklyGoalChange(at = now, before = 3)), first)

        // 같은 주(08-10 주) 안에서 2→1로 다시 바꿔도 이력을 그대로 둔다
        val sameWeek = GrowthEngine.recordWeeklyGoalChange(history = first, oldGoal = 2,
                                                           now = now.plusSeconds(86_400), zone = testZone)
        assertEquals(first, sameWeek)

        // 다음 주(7일 뒤)에 바꾸면 기존 항목을 지우지 않고 (그 시각, 직전 값 1)을 뒤에 붙인다
        val nextWeekTime = now.plusSeconds(7L * 86_400)
        val nextWeek = GrowthEngine.recordWeeklyGoalChange(history = first, oldGoal = 1, now = nextWeekTime, zone = testZone)
        assertEquals(listOf(WeeklyGoalChange(at = now, before = 3),
                            WeeklyGoalChange(at = nextWeekTime, before = 1)), nextWeek)
    }

    @Test
    @DisplayName("주간 목표 두 단계 변경 — 주 A에 3→1, 주 C에 1→2면 A 이전 주는 3, A 다음~C 주는 1, C 이후는 2로 판정한다 (이슈 #116)")
    fun weeklyGoalTwoStepChangesKeepEachBoundary() {
        // A = 07-13 주(28일 전 07-16 목에 3→1), C = 07-27 주(14일 전 07-30 목에 1→2). 현재 목표 2.
        // 완결된 주별 3km 러닝(수·목, 주 경계와 먼 요일)과 판정:
        //   07-06 주: 2회 — A 이전(A 포함 이하) → 목표 3 → 미달
        //   07-13 주(A): 1회 — 목표 3 → 미달
        //   07-20 주: 1회 — A 다음~C → 목표 1 → +30
        //   07-27 주(C): 1회 — 목표 1 → +30 (연속 2주, 4주 연속 보너스 없음)
        //   08-03 주: 1회 — C 이후 → 현재 목표 2 → 미달
        // 세션 XP: 13 * 6 = 78. 보너스 30 * 2 = 60. 합계 138.
        // (#108처럼 C 기록 1건만 남기면 08-03 주 이전이 전부 목표 1 → 07-06~07-27 네 주 달성 +120·연속 +50으로 부풀었다)
        val changeA = ago(28L * 86_400)
        val changeC = ago(14L * 86_400)
        var history = GrowthEngine.recordWeeklyGoalChange(history = emptyList(), oldGoal = 3, now = changeA, zone = testZone)
        history = GrowthEngine.recordWeeklyGoalChange(history = history, oldGoal = 1, now = changeC, zone = testZone)
        assertEquals(listOf(WeeklyGoalChange(at = changeA, before = 3),
                            WeeklyGoalChange(at = changeC, before = 1)), history)

        val runs = listOf(run(daysAgo = 36.0, km = 3.0), run(daysAgo = 35.0, km = 3.0),  // 07-06 주
                          run(daysAgo = 29.0, km = 3.0),                                  // 07-13 주(A)
                          run(daysAgo = 22.0, km = 3.0),                                  // 07-20 주
                          run(daysAgo = 15.0, km = 3.0),                                  // 07-27 주(C)
                          run(daysAgo = 8.0, km = 3.0))                                   // 08-03 주
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 2,
                                       weeklyGoalChanges = history, now = now, zone = testZone)
        assertEquals(78 + 60, state.xp)
    }

    @Test
    @DisplayName("단계 경계 — XP 정확히 50이면 금 간 알")
    fun stageBoundaryAtFifty() {
        // 기본 10 + 거리 보너스 40(40km, 21 상한 미적용 구간 아님 주의: 40km는 21 상한 걸림)
        // 대신 정확히 50을 만들기 위해 두 세션으로 구성: 10km(10+10=20) + 20km(10+20=30) = 50
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 3.0, km = 20.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(50, state.xp)
        assertEquals(GrowthStage.crackedEgg, state.stage)
        assertEquals(0, state.xpIntoStage)
        assertEquals(150, state.xpToNextStage)  // 부화(200) - 50
    }

    @Test
    @DisplayName("성장은 되돌리지 않는다 — maxStage가 계산값보다 높으면 그 값을 쓴다")
    fun maxStageWins() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0))  // XP 15 → 계산 단계는 알(egg)
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = GrowthStage.fledgling.rawValue, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(GrowthStage.fledgling, state.stage)
    }

    @Test
    @DisplayName("시무룩 — 마지막 러닝 7일 전이면 true")
    fun sulkyAtSevenDays() {
        val runs = listOf(run(daysAgo = 7.0, km = 5.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(true, state.isSulky)
        assertEquals(7, state.daysSinceLastRun)
    }

    @Test
    @DisplayName("시무룩 아님 — 마지막 러닝 6일 전이면 false")
    fun notSulkyAtSixDays() {
        val runs = listOf(run(daysAgo = 6.0, km = 5.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(false, state.isSulky)
        assertEquals(6, state.daysSinceLastRun)
    }

    @Test
    @DisplayName("시무룩 아님 — 러닝이 아예 없으면 false (첫 실행 상태는 시무룩이 아니다)")
    fun notSulkyWhenNoRuns() {
        val state = GrowthEngine.state(runs = emptyList(), cycleStartedAt = farPastCycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        assertEquals(false, state.isSulky)
        assertNull(state.daysSinceLastRun)
    }

    @Test
    @DisplayName("cycleStartedAt 이전 러닝은 XP에 산입되지 않는다")
    fun runsBeforeCycleStartExcluded() {
        // cycleStartedAt을 3일 전으로 설정 — 그 이전(5일 전) 러닝은 무시되어야 한다
        val cycleStart = ago(3L * 86_400)
        val runs = listOf(run(daysAgo = 5.0, km = 10.0), run(daysAgo = 1.0, km = 5.0))
        val state = GrowthEngine.state(runs = runs, cycleStartedAt = cycleStart,
                                       maxStage = 1, weeklyGoal = 0, now = now, zone = testZone)
        // 5일 전 10km(XP 20)는 제외, 1일 전 5km(XP 15)만 산입
        assertEquals(15, state.xp)
    }
}
