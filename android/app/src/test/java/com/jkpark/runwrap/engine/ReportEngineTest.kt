package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 리포트 엔진 검증 — 고정 시각 + 합성 데이터. iOS `ReportEngineTests` 이식 (10개 전부).
class ReportEngineTest {
    // 2026-08-10(월) 18:00 KST
    private val now = Instant.parse("2026-08-10T09:00:00Z")
    private val engine get() = ReportEngine(now = now)

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double? = 150.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1000,
        avgHeartRate = hr,
    )

    private fun insight(kind: Insight.Kind, runs: List<RunSummary>): Insight? =
        engine.insights(runs).firstOrNull { it.kind == kind }

    // MARK: 주간 거리 증가율

    @Test
    fun `주간 증가율 10퍼센트 초과는 경고 - 기획서 예시 +23퍼센트`() {
        val runs = listOf(run(daysAgo = 1.0, km = 12.3), run(daysAgo = 3.0, km = 12.3),
                          run(daysAgo = 8.0, km = 10.0), run(daysAgo = 10.0, km = 10.0))
        val result = insight(Insight.Kind.WEEKLY_DISTANCE_CHANGE, runs)
        assertEquals(Insight.Tone.WARNING, result?.tone)
        assertTrue(result?.headline?.contains("+23%") == true)
    }

    @Test
    fun `증가 폭이 10퍼센트 이내면 안정 판정`() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.5), run(daysAgo = 8.0, km = 10.0))
        assertEquals(Insight.Tone.POSITIVE, insight(Insight.Kind.WEEKLY_DISTANCE_CHANGE, runs)?.tone)
    }

    @Test
    fun `기준 주 거리가 3km 미만이면 증가율을 내지 않는다`() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 2.0))
        assertNull(insight(Insight.Kind.WEEKLY_DISTANCE_CHANGE, runs))
    }

    // MARK: ACWR

    @Test
    fun `급성 부하가 만성의 1_5배를 넘으면 부상 위험 경고`() {
        // 3주간 주 10km 유지 후 이번 주 20km — acute 20 ÷ chronic 12.5 = 1.6
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0),
                          run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 24.0, km = 10.0))
        val result = insight(Insight.Kind.ACWR, runs)
        assertEquals(Insight.Tone.WARNING, result?.tone)
        assertTrue(result?.headline?.contains("1.6배") == true)
    }

    @Test
    fun `부하가 4주 평균과 비슷하면 적정 판정`() {
        val runs = generateSequence(2.0) { it + 3 }.takeWhile { it <= 26 }
            .map { run(daysAgo = it, km = 5.0) }.toList()
        val result = insight(Insight.Kind.ACWR, runs)
        assertEquals(Insight.Tone.POSITIVE, result?.tone)
        assertTrue(result?.headline?.contains("적정") == true)
    }

    @Test
    fun `기록이 3주 미만이면 ACWR을 내지 않는다`() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 10.0),
                          run(daysAgo = 14.0, km = 10.0))
        assertNull(insight(Insight.Kind.ACWR, runs))
    }

    // MARK: 심박 효율

    @Test
    fun `같은 페이스에서 심박이 내려가면 체력 상승 판정`() {
        // 직전 2주 150bpm → 최근 2주 140bpm, 페이스 동일 = EF +7%
        val runs = listOf(run(daysAgo = 1.0, km = 5.0, hr = 140.0), run(daysAgo = 3.0, km = 5.0, hr = 140.0),
                          run(daysAgo = 5.0, km = 5.0, hr = 140.0),
                          run(daysAgo = 15.0, km = 5.0, hr = 150.0), run(daysAgo = 17.0, km = 5.0, hr = 150.0),
                          run(daysAgo = 19.0, km = 5.0, hr = 150.0))
        assertEquals(Insight.Tone.POSITIVE, insight(Insight.Kind.HEART_RATE_EFFICIENCY, runs)?.tone)
    }

    @Test
    fun `각 2주 창에 표본 3개 미만이면 심박 효율을 내지 않는다`() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0), run(daysAgo = 3.0, km = 5.0),
                          run(daysAgo = 5.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 17.0, km = 5.0))
        assertNull(insight(Insight.Kind.HEART_RATE_EFFICIENCY, runs))
    }

    @Test
    fun `심박 없는 러닝은 효율 표본에서 제외된다`() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0, hr = null), run(daysAgo = 3.0, km = 5.0, hr = null),
                          run(daysAgo = 5.0, km = 5.0, hr = null),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 17.0, km = 5.0),
                          run(daysAgo = 19.0, km = 5.0))
        assertNull(insight(Insight.Kind.HEART_RATE_EFFICIENCY, runs))
    }

    // MARK: 공통

    @Test
    fun `데이터가 부족하면 어떤 지표도 내지 않는다`() {
        assertTrue(engine.insights(listOf(run(daysAgo = 1.0, km = 5.0))).isEmpty())
        assertTrue(engine.insights(emptyList()).isEmpty())
    }
}
