package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 발전상 월별 시리즈 검증 — iOS `ProgressStatsTests`의 MonthlySeries 케이스 이식.
class MonthlySeriesTest {
    // KST 기준 2026-08-10 18:00 — 이번 달이 8월이 되도록 고정
    private val now = Instant.parse("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Double, km: Double, minPerKm: Double = 6.0,
                    hr: Double? = 150.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1_000,
        avgHeartRate = hr,
    )

    @Test
    fun `미노출 가드 - 러닝이 있는 월이 2개 미만이면 null`() {
        // 8월에만 기록 → 추이라 부를 수 없다
        assertNull(MonthlySeries.compute(listOf(run(1.0, 5.0), run(3.0, 6.0)), now))
    }

    @Test
    fun `월 라벨과 거리 합계 - 오래된 달부터 최신 순으로 쌓인다`() {
        val runs = listOf(
            run(daysAgo = 45.0, km = 8.0),   // 6월 26일 (KST)
            run(daysAgo = 20.0, km = 5.0),   // 7월 21일
            run(daysAgo = 15.0, km = 7.0),   // 7월 26일
            run(daysAgo = 2.0, km = 10.0),   // 8월 8일
        )
        val series = MonthlySeries.compute(runs, now)
        assertNotNull(series)
        val points = series!!.points
        assertEquals(3, points.size)                       // 6월·7월·8월
        assertEquals(listOf("6월", "7월", "8월"), points.map { it.label })
        assertTrue(abs(points[1].totalKm - 12.0) < 0.001)  // 7월 = 5 + 7
        assertTrue(abs(points[2].totalKm - 10.0) < 0.001)
    }

    @Test
    fun `월평균 EF - 심박 있는 세션 3회 미만인 달은 점을 내지 않는다`() {
        val runs = listOf(
            // 7월: 심박 있는 세션 3회 → EF 산출. 페이스 360초/km, 심박 150
            // EF = (60000/360)/150 = 1.1111
            run(daysAgo = 20.0, km = 5.0), run(daysAgo = 18.0, km = 5.0),
            run(daysAgo = 16.0, km = 5.0),
            // 8월: 심박 있는 세션 2회 → 가드
            run(daysAgo = 2.0, km = 5.0), run(daysAgo = 1.0, km = 5.0, hr = null),
            run(daysAgo = 3.0, km = 5.0),
        )
        val series = MonthlySeries.compute(runs, now)!!
        val july = series.points.first { it.label == "7월" }
        val august = series.points.first { it.label == "8월" }
        assertNotNull(july.avgEF)
        assertTrue(abs(july.avgEF!! - 60_000.0 / 360.0 / 150.0) < 0.0001)
        assertNull(august.avgEF)
    }
}
