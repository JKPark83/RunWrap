package com.jkpark.runwrap.engine

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 크로스 트레이닝 주간 요약 엔진 검증 — 미노출 가드·종목별 집계·러닝 감소 감지.
/// iOS `CrossTrainingEngineTests` 이식 (8개 전부).
class CrossTrainingEngineTest {
    private val now = OffsetDateTime.parse("2026-08-12T10:00:00+09:00").toInstant()

    private fun cross(daysAgo: Int, minutes: Int, kind: CrossTraining.Kind) = CrossTraining(
        start = now.minusSeconds(daysAgo * 86_400L),
        durationSec = minutes * 60.0,
        kind = kind,
    )

    private fun run(daysAgo: Int, km: Double) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusSeconds(daysAgo * 86_400L),
        durationSec = km * 300,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    @Test
    fun `미노출 가드 - 세션이 하나도 없으면 요약을 내지 않는다`() {
        assertNull(CrossTrainingEngine.weekly(emptyList(), emptyList(), now))
    }

    @Test
    fun `미노출 가드 - 주간 합산 20분 미만이면 요약할 거리가 안 된다`() {
        val cross = listOf(cross(daysAgo = 1, minutes = 15, kind = CrossTraining.Kind.YOGA))
        assertNull(CrossTrainingEngine.weekly(cross, emptyList(), now))
    }

    @Test
    fun `걷기 30분 미만은 일상 보행 - 집계에서 뺀다`() {
        val cross = listOf(cross(daysAgo = 1, minutes = 25, kind = CrossTraining.Kind.WALKING))
        assertNull(CrossTrainingEngine.weekly(cross, emptyList(), now))
    }

    @Test
    fun `종목별 집계 - 시간 많은 순으로 정렬한다`() {
        val cross = listOf(
            cross(daysAgo = 2, minutes = 40, kind = CrossTraining.Kind.STRENGTH),
            cross(daysAgo = 3, minutes = 90, kind = CrossTraining.Kind.CYCLING),
        )
        val summary = CrossTrainingEngine.weekly(cross, emptyList(), now)!!
        assertEquals(2, summary.sessionCount)
        assertEquals(130, summary.totalMinutes)
        assertEquals(
            listOf(
                CrossTrainingEngine.Item(label = "자전거", minutes = 90, count = 1),
                CrossTrainingEngine.Item(label = "근력", minutes = 40, count = 1),
            ),
            summary.breakdown,
        )
    }

    @Test
    fun `러닝이 지난주보다 20퍼센트 이상 줄었으면 줄었다는 문장이 나온다`() {
        val cross = listOf(cross(daysAgo = 2, minutes = 90, kind = CrossTraining.Kind.CYCLING))
        val runs = listOf(run(daysAgo = 10, km = 20.0), run(daysAgo = 2, km = 8.0))
        val summary = CrossTrainingEngine.weekly(cross, runs, now)!!
        assertTrue(summary.headline.contains("줄었지만"))
        assertTrue(summary.headline.contains("자전거"))
    }

    @Test
    fun `러닝이 유지되면 부지런히 움직였다는 문장이 나온다`() {
        val cross = listOf(cross(daysAgo = 2, minutes = 90, kind = CrossTraining.Kind.CYCLING))
        val runs = listOf(run(daysAgo = 10, km = 20.0), run(daysAgo = 2, km = 19.0))
        val summary = CrossTrainingEngine.weekly(cross, runs, now)!!
        assertTrue(summary.headline.contains("부지런히"))
    }

    @Test
    fun `주간 창 - 7일보다 오래된 세션은 세지 않는다`() {
        val cross = listOf(cross(daysAgo = 8, minutes = 60, kind = CrossTraining.Kind.CYCLING))
        assertNull(CrossTrainingEngine.weekly(cross, emptyList(), now))
    }

    @Test
    fun `디테일 - ACWR에 넣지 않는다는 안내가 항상 붙는다`() {
        val cross = listOf(cross(daysAgo = 2, minutes = 60, kind = CrossTraining.Kind.SWIMMING))
        val summary = CrossTrainingEngine.weekly(cross, emptyList(), now)!!
        assertTrue(summary.detail.contains("ACWR"))
    }
}
