package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 발전상 레이어 검증 — PR(베스트 에포트 최소값, 이슈 #166) 선택과 월별 시리즈의 표본 가드
/// now = 2026-08-10(월) 18:00 KST — daysAgo 1~9 = 8월, 33~40 = 7월.
class ProgressStatsTests {
    private val now = iso("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double? = 150.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = hr)

    // MARK: PR

    @Test
    @DisplayName("PR 판정 — 세션별 베스트 에포트 중 최소 기록과 달성일을 고른다 (이슈 #166)")
    fun personalRecordsPicksBest() {
        val bestFiveK = run(daysAgo = 40.0, km = 5.4, minPerKm = 5.0)
        val slower = run(daysAgo = 10.0, km = 5.2, minPerKm = 5.5)
        val long = run(daysAgo = 20.0, km = 10.5, minPerKm = 5.8)
        val uncomputed = run(daysAgo = 5.0, km = 5.8, minPerKm = 4.0)   // 백필 전 — 표에 없다
        val efforts: BestEffortTable = mapOf(
            slower.id to mapOf(1_000.0 to 320.0, 5_000.0 to 1_650.0),
            bestFiveK.id to mapOf(1_000.0 to 290.0, 5_000.0 to 1_500.0),
            long.id to mapOf(1_000.0 to 330.0, 5_000.0 to 1_700.0, 10_000.0 to 3_480.0),
        )
        val entries = PersonalRecords.compute(runs = listOf(slower, bestFiveK, long, uncomputed),
                                              efforts = efforts)
        // 하프·풀 기록 없음 → 항목 자체 미포함. 1K는 에포트가 있어도 PB 종목이 아니다
        assertEquals(listOf("5K", "10K"), entries.map { it.label })

        val fiveK = assertNotNull(entries.firstOrNull { it.label == "5K" })
        assertEquals(1_500.0, fiveK.timeSec)             // min(1650, 1500, 1700)
        assertEquals(bestFiveK.start, fiveK.date)
        assertNotEquals(uncomputed.id, fiveK.run.id)     // 평균 페이스가 가장 빨라도 계산 전이면 후보 아님

        val tenK = assertNotNull(entries.firstOrNull { it.label == "10K" })
        assertEquals(3_480.0, tenK.timeSec)
        assertEquals(long.id, tenK.run.id)
    }

    @Test
    @DisplayName("PR — 긴 세션 안 구간 기록도 후보, 못 채운 거리는 후보 아님 (이슈 #91·#166)")
    fun personalRecordsFromLongRunSegment() {
        // 예전 산식은 완주 거리 ∈ [D×0.995, D×1.10]만 봤다 — 12km 세션은 5K·10K 모두 범위 밖이었다.
        // 베스트 에포트는 세션 안 가장 빠른 5km 구간(1,400초)을 그대로 5K 기록으로 쓴다
        val long = run(daysAgo = 3.0, km = 12.0, minPerKm = 5.5)
        // 4.97km 세션은 엔진이 5K를 못 채워 1K만 낸다 → 5K 후보가 아니다
        val short = run(daysAgo = 4.0, km = 4.97, minPerKm = 5.0)
        val efforts: BestEffortTable = mapOf(
            long.id to mapOf(1_000.0 to 270.0, 5_000.0 to 1_400.0, 10_000.0 to 3_200.0),
            short.id to mapOf(1_000.0 to 285.0),
        )
        val entries = PersonalRecords.compute(runs = listOf(long, short), efforts = efforts)
        val fiveK = assertNotNull(entries.firstOrNull { it.label == "5K" })
        assertEquals(1_400.0, fiveK.timeSec)
        assertEquals(long.id, fiveK.run.id)
        // 4.97km 세션만 있으면 5K 항목이 없다
        assertFalse(PersonalRecords.compute(runs = listOf(short), efforts = efforts).any { it.label == "5K" })
    }

    @Test
    @DisplayName("PR — 베스트 에포트가 없거나(미계산) 빈 dict(1K 미만)면 빈 배열")
    fun personalRecordsEmpty() {
        val short = run(daysAgo = 3.0, km = 0.8)
        assertTrue(PersonalRecords.compute(runs = listOf(short), efforts = emptyMap()).isEmpty())
        assertTrue(PersonalRecords.compute(runs = listOf(short), efforts = mapOf(short.id to emptyMap())).isEmpty())
    }

    // MARK: 페이스 타당 범위 가드 (이슈 #76)

    @Test
    @DisplayName("페이스 가드 — 시간 0초면 nil")
    fun paceNilWhenZeroDuration() {
        val zero = RunSummary(id = UUID.randomUUID().toString(), start = now, durationSec = 0.0,
                              distanceMeters = 5_200.0, avgHeartRate = 150.0)
        assertNull(zero.paceSecPerKm)
    }

    @Test
    @DisplayName("페이스 가드 — 2:00/km·25:00/km처럼 비현실 페이스는 nil")
    fun paceNilWhenOutOfRange() {
        assertNull(run(daysAgo = 1.0, km = 5.0, minPerKm = 2.0).paceSecPerKm)    // 120초/km < 150
        assertNull(run(daysAgo = 1.0, km = 5.0, minPerKm = 25.0).paceSecPerKm)   // 1500초/km > 1200
    }

    @Test
    @DisplayName("페이스 가드 — 정상 페이스와 경계값 150·1200초/km는 값을 낸다")
    fun paceValueInRange() {
        val normal = assertNotNull(run(daysAgo = 1.0, km = 5.0, minPerKm = 5.5).paceSecPerKm)
        assertTrue(abs(normal - 330) < 0.01)   // 5:30/km
        val fastest = assertNotNull(run(daysAgo = 1.0, km = 5.0, minPerKm = 2.5).paceSecPerKm)
        assertTrue(abs(fastest - 150) < 0.01)  // 2:30/km — 하한 포함
        val slowest = assertNotNull(run(daysAgo = 1.0, km = 5.0, minPerKm = 20.0).paceSecPerKm)
        assertTrue(abs(slowest - 1_200) < 0.01) // 20:00/km — 상한 포함
    }

    @Test
    @DisplayName("회귀 — 시간 0초인 5.2km 기록이 섞여도 5K PB는 그대로, 월 EF는 유한하다")
    fun zeroDurationRunDoesNotBecomePBOrInfiniteEF() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.2, minPerKm = 5.0),   // 5K 베스트 에포트 1500초
                          run(daysAgo = 5.0, km = 8.0, minPerKm = 6.0),   // EF (60000/360)/150 ≈ 1.1111
                          run(daysAgo = 9.0, km = 8.0, minPerKm = 6.0),
                          run(daysAgo = 33.0, km = 8.0, minPerKm = 6.0))
        val broken = RunSummary(id = UUID.randomUUID().toString(),
                                start = instantSince1970(now.timeIntervalSince1970 - 3 * 86_400),
                                durationSec = 0.0, distanceMeters = 5_200.0, avgHeartRate = 150.0)

        // 가드 전에는 페이스 0 → 5K 0초가 영구 PB가 됐다. 이제 PR은 세션 시간이 아니라
        // 거리 샘플의 베스트 에포트를 쓴다 — 시간 0초 세션은 샘플이 없어 빈 dict (이슈 #166)
        val efforts: BestEffortTable = mapOf(runs[0].id to mapOf(1_000.0 to 290.0, 5_000.0 to 1_500.0),
                                             broken.id to emptyMap())
        val fiveK = assertNotNull(PersonalRecords.compute(runs = runs + broken, efforts = efforts)
            .firstOrNull { it.label == "5K" })
        assertTrue(abs(fiveK.timeSec - 1500) < 0.01)
        assertNotEquals(broken.id, fiveK.run.id)

        // 가드 전에는 60000/0 = inf가 월평균 EF를 inf로 만들었다.
        // 8월 유효 표본 3회: (1.3333 + 1.1111 + 1.1111) / 3 ≈ 1.1852
        val series = assertNotNull(MonthlySeries.compute(runs = runs + broken, now = now, zone = testZone))
        val august = assertNotNull(series.points.lastOrNull()?.avgEF)
        assertTrue(august.isFinite())
        assertTrue(abs(august - 1.1852) < 0.001)
    }

    // MARK: 월별 시리즈

    @Test
    @DisplayName("월 시리즈 — 7·8월 집계가 오래된 → 최신 순서로 나온다")
    fun monthlySeriesOrderAndValues() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.0, minPerKm = 6.0),     // 8.9
                          run(daysAgo = 5.0, km = 10.0, minPerKm = 6.0),     // 8.5 — 8월 합계 20km, 페이스 360
                          run(daysAgo = 33.0, km = 8.0, minPerKm = 6.5),     // 7.8
                          run(daysAgo = 38.0, km = 8.0, minPerKm = 6.5))     // 7.3 — 7월 합계 16km, 페이스 390
        val series = assertNotNull(MonthlySeries.compute(runs = runs, now = now, zone = testZone))
        assertEquals(2, series.points.size)

        val july = series.points[0]
        assertEquals("7월", july.label)
        assertTrue(abs(july.totalKm - 16) < 0.01)
        assertTrue(july.avgPaceSec != null && abs(july.avgPaceSec!! - 390) < 0.01)
        assertNull(july.avgEF)  // 심박 세션 2회 — 3회 미만 가드

        val august = series.points[1]
        assertEquals("8월", august.label)
        assertTrue(abs(august.totalKm - 20) < 0.01)
        assertTrue(august.avgPaceSec != null && abs(august.avgPaceSec!! - 360) < 0.01)
    }

    @Test
    @DisplayName("월 EF — 심박 세션 3회 이상인 달만 점이 있다")
    fun monthlyEFGuard() {
        val runs = listOf(run(daysAgo = 1.0, km = 8.0, minPerKm = 6.0, hr = 150.0),   // 8.9
                          run(daysAgo = 5.0, km = 8.0, minPerKm = 6.0, hr = 150.0),   // 8.5
                          run(daysAgo = 9.0, km = 8.0, minPerKm = 6.0, hr = 150.0),   // 8.1 — 8월 3회
                          run(daysAgo = 33.0, km = 8.0, minPerKm = 6.0, hr = 150.0),  // 7.8
                          run(daysAgo = 38.0, km = 8.0, minPerKm = 6.0, hr = 150.0))  // 7.3 — 7월 2회
        val series = assertNotNull(MonthlySeries.compute(runs = runs, now = now, zone = testZone))
        // EF = (60000 / 360) ÷ 150 = 166.67 m/min ÷ 150 bpm ≈ 1.1111
        val august = assertNotNull(series.points.lastOrNull())
        assertTrue(august.avgEF != null && abs(august.avgEF!! - 1.1111) < 0.001)
        assertNull(series.points.firstOrNull()?.avgEF)
    }

    @Test
    @DisplayName("월 시리즈 가드 — 기록 있는 월이 2개 미만이면 nil")
    fun monthlySeriesGuard() {
        val augustOnly = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 5.0, km = 10.0))
        assertNull(MonthlySeries.compute(runs = augustOnly, now = now, zone = testZone))
        assertNull(MonthlySeries.compute(runs = emptyList(), now = now, zone = testZone))
    }
}
