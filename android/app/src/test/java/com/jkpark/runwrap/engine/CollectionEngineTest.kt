package com.jkpark.runwrap.engine

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 도감 엔진 검증 — 새 배정 경계값, 목표 표기, 완성 판정, 다음 목표 추천.
/// iOS `CollectionEngineTests` 이식 (12개 전부 — CollectionCacheTests 3개는 스토어 계층 M5).
class CollectionEngineTest {
    private val sub3 = 3 * 3_600
    private val sub4 = 4 * 3_600

    // MARK: - 새 배정

    @Test
    fun `목표 없음은 참새 - 기록 입력이 있어도 종목이 없으면 참새`() {
        assertEquals(BirdSpecies.SPARROW, CollectionEngine.species(null, 0))
        assertEquals(BirdSpecies.SPARROW, CollectionEngine.species(null, 3_600))
    }

    @Test
    fun `5K와 10K는 제비, 하프는 매`() {
        assertEquals(BirdSpecies.SWALLOW, CollectionEngine.species(RaceDistance.FIVE_K, 0))
        assertEquals(BirdSpecies.SWALLOW, CollectionEngine.species(RaceDistance.TEN_K, 0))
        assertEquals(BirdSpecies.FALCON, CollectionEngine.species(RaceDistance.HALF, 0))
    }

    @Test
    fun `풀코스 기록 없음은 기러기 - 0 이하도 기록 없음으로 본다`() {
        assertEquals(BirdSpecies.GOOSE, CollectionEngine.species(RaceDistance.FULL, 0))
        assertEquals(BirdSpecies.GOOSE, CollectionEngine.species(RaceDistance.FULL, -1))
    }

    @Test
    fun `sub-3 경계 - 3시간 미만은 백조, 정각부터 두루미`() {
        assertEquals(BirdSpecies.SWAN, CollectionEngine.species(RaceDistance.FULL, sub3 - 1))
        assertEquals(BirdSpecies.CRANE, CollectionEngine.species(RaceDistance.FULL, sub3))
    }

    @Test
    fun `sub-4 경계 - 4시간 미만은 두루미, 정각부터 기러기`() {
        assertEquals(BirdSpecies.CRANE, CollectionEngine.species(RaceDistance.FULL, sub4 - 1))
        assertEquals(BirdSpecies.GOOSE, CollectionEngine.species(RaceDistance.FULL, sub4))
        assertEquals(BirdSpecies.GOOSE, CollectionEngine.species(RaceDistance.FULL, 5 * 3_600))
    }

    // MARK: - 목표 표기

    @Test
    fun `목표 표기 - 종목+기록, 기록 없으면 종목만, 목표 없으면 참새 힌트`() {
        assertEquals("풀코스 3:30:00",
            CollectionEngine.goalLabel(RaceDistance.FULL, 3 * 3_600 + 30 * 60))
        assertEquals("하프", CollectionEngine.goalLabel(RaceDistance.HALF, 0))
        assertEquals("목표 없이 완주 습관", CollectionEngine.goalLabel(null, 0))
    }

    // MARK: - 완성 판정

    @Test
    fun `성체 판정 - 최종 단계(나는 새)만 성체다`() {
        assertTrue(CollectionEngine.hasReachedAdult(GrowthStage.FLYING))
        for (stage in GrowthStage.entries.filter { it != GrowthStage.FLYING }) {
            assertFalse(CollectionEngine.hasReachedAdult(stage))
        }
    }

    @Test
    fun `완성 카드 - 종·목표·완성일·사이클 일수를 그대로 얼린다`() {
        val cycleStart = Instant.parse("2026-05-01T09:00:00Z")
        val now = Instant.parse("2026-08-13T09:00:00Z")
        val bird = CollectionEngine.collect(
            distance = RaceDistance.FULL,
            goalSeconds = 3 * 3_600 + 30 * 60,
            cycleStartedAt = cycleStart,
            now = now,
        )
        assertEquals(BirdSpecies.CRANE, bird.species)
        assertEquals("풀코스 3:30:00", bird.goalLabel)
        assertEquals(now, bird.collectedAt)
        assertEquals(104, bird.cycleDays)   // 5/1 → 8/13 (KST 자정 경계)
    }

    @Test
    fun `완성 카드 - 시계 역행으로 시작이 미래면 일수는 0으로 접는다`() {
        val now = Instant.parse("2026-08-13T09:00:00Z")
        val bird = CollectionEngine.collect(
            distance = null,
            goalSeconds = 0,
            cycleStartedAt = now.plusSeconds(10 * 86_400L),
            now = now,
        )
        assertEquals(0, bird.cycleDays)
    }

    // MARK: - 다음 목표 추천

    @Test
    fun `추천 사다리 - 없음에서 5K, 5K에서 10K, 10K에서 하프, 하프에서 풀`() {
        assertEquals(RaceDistance.FIVE_K, CollectionEngine.recommendedGoal(null, 0)?.distance)
        assertEquals(RaceDistance.TEN_K,
            CollectionEngine.recommendedGoal(RaceDistance.FIVE_K, 0)?.distance)
        assertEquals(RaceDistance.HALF,
            CollectionEngine.recommendedGoal(RaceDistance.TEN_K, 0)?.distance)
        assertEquals(RaceDistance.FULL,
            CollectionEngine.recommendedGoal(RaceDistance.HALF, 0)?.distance)
    }

    @Test
    fun `풀 완주 다음은 sub-4, sub-4 다음은 30분 조이기`() {
        val afterFinish = CollectionEngine.recommendedGoal(RaceDistance.FULL, 0)
        assertEquals(RaceDistance.FULL, afterFinish?.distance)
        assertEquals(sub4, afterFinish?.seconds)

        val afterSub4 = CollectionEngine.recommendedGoal(RaceDistance.FULL, sub4)
        assertEquals(3 * 3_600 + 30 * 60, afterSub4?.seconds)
    }

    @Test
    fun `sub-3 아래로는 더 조일 목표를 추천하지 않는다`() {
        assertNull(CollectionEngine.recommendedGoal(RaceDistance.FULL, sub3))
        assertNull(CollectionEngine.recommendedGoal(RaceDistance.FULL, 2 * 3_600 + 50 * 60))
        assertEquals(sub3,
            CollectionEngine.recommendedGoal(RaceDistance.FULL, 3 * 3_600 + 30 * 60)?.seconds)
    }
}
