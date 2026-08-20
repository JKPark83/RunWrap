package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// 발전상 레이어 검증 — PR 범위 판정.
/// iOS `ProgressStatsTests`의 PR 테스트 2개 이식 — 월별 시리즈(MonthlySeries)는 통계 화면과 함께 M5.
class ProgressStatsTest {
    private val now = Instant.parse("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Double, km: Double, minPerKm: Double = 6.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    @Test
    fun `PR 판정 - D부터 D x 1_10 범위 안 최소 기록과 달성일을 고른다`() {
        val bestFiveK = run(daysAgo = 40.0, km = 5.4, minPerKm = 5.0)   // 페이스 300 → 5K 환산 1500초
        val runs = listOf(
            run(daysAgo = 10.0, km = 5.2, minPerKm = 5.5),              // 페이스 330 → 1650초 (밀림)
            bestFiveK,
            run(daysAgo = 20.0, km = 10.5, minPerKm = 5.8),             // 페이스 348 → 10K 환산 3480초
            run(daysAgo = 5.0, km = 5.8, minPerKm = 4.0),               // 5.8 > 5.5 — 5K 범위 밖, 10K 미달
        )
        val entries = PersonalRecords.compute(runs)
        assertEquals(2, entries.size)  // 하프·풀 기록 없음 → 항목 자체 미포함

        val fiveK = entries.first { it.label == "5K" }
        assertTrue(abs(fiveK.timeSec - 1_500) < 0.01)   // 300초/km × 5.0km
        assertEquals(bestFiveK.start, fiveK.date)

        val tenK = entries.first { it.label == "10K" }
        assertTrue(abs(tenK.timeSec - 3_480) < 0.01)    // 348초/km × 10.0km
    }

    @Test
    fun `PR - 해당 거리 기록이 하나도 없으면 빈 배열`() {
        assertTrue(PersonalRecords.compute(listOf(run(daysAgo = 3.0, km = 3.0))).isEmpty())
    }
}
