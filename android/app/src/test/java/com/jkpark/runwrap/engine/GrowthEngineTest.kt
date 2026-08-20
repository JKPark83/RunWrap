package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 성장 시스템 엔진 검증 — XP 규칙·하루 캡·주간 보너스·스트릭·래칫·시무룩.
/// now = 2026-08-13T09:00:00Z = KST 목요일 18:00 — ISO 주 시작은 08-10(월).
/// iOS `GrowthEngineTests` 이식 (12개 전부).
class GrowthEngineTest {
    private val now = Instant.parse("2026-08-13T09:00:00Z")
    private val farPastCycleStart = Instant.parse("2020-01-01T00:00:00Z")

    private fun run(daysAgo: Double, km: Double) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * 6 * 60,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    private fun state(
        runs: List<RunSummary>,
        cycleStartedAt: Instant = farPastCycleStart,
        maxStage: Int = 1,
        weeklyGoal: Int = 0,
    ) = GrowthEngine.state(runs, cycleStartedAt, maxStage, weeklyGoal, now)

    // MARK: - 세션 XP

    @Test
    fun `세션 XP - 기본 10 + 1km당 1점 (5km는 15 XP)`() {
        assertEquals(15, state(listOf(run(daysAgo = 1.0, km = 5.0))).xp)
    }

    @Test
    fun `거리 XP 캡 - 30km를 뛰어도 거리분은 21km까지만 (31 XP)`() {
        assertEquals(31, state(listOf(run(daysAgo = 1.0, km = 30.0))).xp)
    }

    @Test
    fun `하루 합산 캡 - 같은 날 두 번 뛰어도 40 XP까지만`() {
        // 20km 두 번 = 30 + 30 = 60이지만 하루 캡 40에 걸린다
        val runs = listOf(run(daysAgo = 1.0, km = 20.0), run(daysAgo = 1.1, km = 20.0))
        assertEquals(40, state(runs).xp)
    }

    @Test
    fun `1km 미만은 0 XP - 산책은 세지 않는다`() {
        val result = state(listOf(run(daysAgo = 1.0, km = 0.5)))
        assertEquals(0, result.xp)
        assertEquals(GrowthStage.EGG, result.stage)
    }

    // MARK: - 주간 보너스·스트릭

    @Test
    fun `주간 목표 보너스 - 지난주 목표 달성이면 +30 XP`() {
        // 지난주(08-03 주)에 2회 — 목표 2회 달성 → 세션 13+13 + 보너스 30 = 56
        val runs = listOf(run(daysAgo = 8.0, km = 3.0), run(daysAgo = 6.0, km = 3.0))
        assertEquals(56, state(runs, weeklyGoal = 2).xp)
    }

    @Test
    fun `4주 연속 달성이면 스트릭 보너스 +50 XP`() {
        // 사이클 시작 = 4주 전 월요일 — 지난 4주 매주 1회(1km)씩 목표 1회 달성.
        // 세션 4×11 + 주간 보너스 4×30 + 스트릭 50 = 214
        val cycleStart = weekStart(now).minusWeeks(4).atStartOfDay(SEOUL).toInstant()
        val runs = listOf(run(daysAgo = 8.0, km = 1.0), run(daysAgo = 15.0, km = 1.0),
                          run(daysAgo = 22.0, km = 1.0), run(daysAgo = 29.0, km = 1.0))
        assertEquals(214, state(runs, cycleStartedAt = cycleStart, weeklyGoal = 1).xp)
    }

    // MARK: - 단계·게이지

    @Test
    fun `단계 경계 - 정확히 50 XP면 금 간 알, 다음까지 150 남는다`() {
        // 10km(20) + 20km(30) = 50 XP → CRACKED_EGG 문턱 정확히
        val result = state(listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 3.0, km = 20.0)))
        assertEquals(50, result.xp)
        assertEquals(GrowthStage.CRACKED_EGG, result.stage)
        assertEquals(0, result.xpIntoStage)
        assertEquals(150, result.xpToNextStage)
    }

    @Test
    fun `래칫 - 저장된 최고 단계 아래로는 내려가지 않는다`() {
        // XP 15면 알 단계지만 maxStage가 어린 새면 어린 새 유지
        val result = state(listOf(run(daysAgo = 1.0, km = 5.0)),
                           maxStage = GrowthStage.FLEDGLING.raw)
        assertEquals(GrowthStage.FLEDGLING, result.stage)
    }

    // MARK: - 시무룩

    @Test
    fun `시무룩 - 7일 공백이면 시무룩하다`() {
        val result = state(listOf(run(daysAgo = 7.0, km = 5.0)))
        assertTrue(result.isSulky)
        assertEquals(7, result.daysSinceLastRun)
    }

    @Test
    fun `시무룩 아님 - 6일 공백까지는 괜찮다`() {
        val result = state(listOf(run(daysAgo = 6.0, km = 5.0)))
        assertFalse(result.isSulky)
        assertEquals(6, result.daysSinceLastRun)
    }

    @Test
    fun `기록이 없으면 시무룩하지 않다 - 경과일도 null`() {
        val result = state(emptyList())
        assertFalse(result.isSulky)
        assertNull(result.daysSinceLastRun)
    }

    // MARK: - 사이클 창

    @Test
    fun `사이클 시작 전 러닝은 XP에 넣지 않는다`() {
        // 사이클 시작 3일 전 — 5일 전 10km는 제외, 1일 전 5km만 15 XP
        val cycleStart = now.minusSeconds(3 * 86_400L)
        val runs = listOf(run(daysAgo = 5.0, km = 10.0), run(daysAgo = 1.0, km = 5.0))
        assertEquals(15, state(runs, cycleStartedAt = cycleStart).xp)
    }
}
