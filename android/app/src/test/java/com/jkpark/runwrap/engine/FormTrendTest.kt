package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 주간 케이던스 추이 검증 — iOS `FormEngineTests`의 FormTrend 케이스 이식.
class FormTrendTest {
    private val now = Instant.parse("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Long, spm: Double?) = RunSummary(
        id = "run-$daysAgo",
        start = now.minusSeconds(daysAgo * 86_400),
        durationSec = 1_800.0,
        distanceMeters = 5_000.0,
        avgHeartRate = 150.0,
        cadenceSpm = spm,
    )

    @Test
    fun `표본 부족 가드 - 한 창이라도 케이던스 3회 미만이면 null`() {
        val runs = listOf(
            run(1, 170.0), run(3, 172.0), run(5, 171.0),   // 최근 2주: 3회
            run(16, 168.0), run(18, null), run(20, 169.0), // 이전 2주: 유효 2회
        )
        assertNull(FormTrend.compute(runs, now))
    }

    @Test
    fun `개선 판정 - 최근 2주 평균이 +2 spm 이상이면 improving`() {
        val runs = listOf(
            run(1, 174.0), run(3, 175.0), run(5, 176.0),   // 평균 175
            run(16, 170.0), run(18, 171.0), run(20, 172.0), // 평균 171 → Δ +4
        )
        val trend = FormTrend.compute(runs, now)!!
        assertEquals(RRTone.IMPROVING, trend.tone)
        assertTrue(abs(trend.deltaSpm - 4.0) < 0.001)
    }

    @Test
    fun `유지 판정 - ±2 spm 미만 변화는 측정 요동으로 보고 steady`() {
        val runs = listOf(
            run(1, 171.0), run(3, 172.0), run(5, 173.0),   // 평균 172
            run(16, 170.5), run(18, 171.5), run(20, 172.0), // 평균 171.33 → Δ +0.67
        )
        assertEquals(RRTone.STEADY, FormTrend.compute(runs, now)!!.tone)
    }
}
