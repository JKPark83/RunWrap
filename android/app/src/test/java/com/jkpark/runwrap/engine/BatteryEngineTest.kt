package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS `BatteryEngineTests` 이식 — 15개 중 11개.
/// HRR 관련 4개(hrrBelowMinCountStaysSilent·hrrDropPenalizes·hrrRiseCharges·
/// restingHRAndHRRAloneExposeBattery)는 HC에 HRR 레코드 타입이 없어 제외 (계획서 §2.2).
class BatteryEngineTest {
    /// 2026-08-10(월) 18:00 KST — ReportMetricsTest와 같은 고정 시각
    private val now = Instant.parse("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double = 150.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1000,
        avgHeartRate = hr,
    )

    private fun reading(today: Double, baseline: Double, days: Int = 28) =
        VitalsSnapshot.Reading(today = today, baseline = baseline, baselineDays = days)

    private fun night(daysAgo: Double, fraction: Double? = null, bedtime: Double? = null) =
        VitalsSnapshot.SleepNight(
            date = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
            asleepHours = 7.0, deepRemFraction = fraction, bedtimeMinutes = bedtime)

    @Test
    fun `중립 활력징후면 배터리 50`() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(50, report.level)
        assertEquals(RRTone.STEADY, report.tone)
        assertEquals(3, report.factors.size)
        assertTrue(report.factors.all { it.points == 0 })
    }

    @Test
    fun `회복된 활력징후는 충전한다`() {
        // HRV +20% → +16, 안정 심박 −8% → +12, 수면 8.5시간 → +11
        val vitals = VitalsSnapshot(hrvMs = reading(72.0, 60.0),
                                    restingHR = reading(46.0, 50.0),
                                    sleepHours = 8.5)
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(89, report.level)
        assertEquals(RRTone.IMPROVING, report.tone)
        assertEquals("충전 충분", report.statusLabel)
    }

    @Test
    fun `나쁜 활력징후 + 훈련이면 0까지 방전된다`() {
        // HRV −30%(클램프 −20), 안정 심박 +12%(클램프 −15), 수면 5시간(−15)
        // + 오늘 10 km(−20) + ACWR 1.6(−8) → 50−78 → 0으로 클램프
        val vitals = VitalsSnapshot(hrvMs = reading(42.0, 60.0),
                                    restingHR = reading(56.0, 50.0),
                                    sleepHours = 5.0)
        val runs = listOf(run(daysAgo = 0.1, km = 10.0),
                          run(daysAgo = 8.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0),
                          run(daysAgo = 22.0, km = 5.0))
        val report = BatteryEngine.compute(vitals, runs, now)!!
        assertEquals(0, report.level)
        assertEquals(RRTone.OVERLOAD, report.tone)
        assertTrue(report.factors.any { it.name == "오늘 훈련" && it.points == -20 })
        assertTrue(report.factors.any { it.name == "훈련 부하" && it.points == -8 })
    }

    @Test
    fun `핵심 신호가 2개 미만이면 계산하지 않는다`() {
        // HRV 하나만 유효 (안정 심박은 기준선 3일뿐, 수면 없음) → 계산하지 않는다
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0, days = 3))
        assertNull(BatteryEngine.compute(vitals, emptyList(), now))
    }

    @Test
    fun `호흡수·피부 온도 이탈은 감점된다`() {
        // 핵심 신호는 중립, 호흡수 +18%·피부 온도 +0.5°C → 각각 −6
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    respiratoryRate = reading(16.5, 14.0),
                                    skinTempC = reading(36.9, 36.4, days = 21),
                                    sleepHours = 7.0)
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(38, report.level)
        assertEquals(RRTone.CAUTION, report.tone)
        assertTrue(report.factors.any { it.name == "호흡수" && it.points == -6 })
        assertTrue(report.factors.any { it.name == "피부 온도" && it.points == -6 })
    }

    @Test
    fun `호흡수·피부 온도가 평소 범위면 침묵한다`() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    respiratoryRate = reading(14.5, 14.2),
                                    skinTempC = reading(36.5, 36.4, days = 21),
                                    sleepHours = 7.0)
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "호흡수" })
        assertFalse(report.factors.any { it.name == "피부 온도" })
    }

    @Test
    fun `수면 질 - 기저 대비 20퍼센트 이상 하락하면 -8점`() {
        // 최근 밤 22% vs 나머지 6개 밤 평균 30% → (30−22)/30 ≈ 26.7% ≥ 20% → −8, 50 − 8 = 42
        val vitals = VitalsSnapshot(
            hrvMs = reading(60.0, 60.0),
            restingHR = reading(52.0, 52.0),
            sleepHours = 7.0,
            sleepNights = listOf(night(daysAgo = 0.0, fraction = 0.22)) +
                (1..6).map { night(daysAgo = it.toDouble(), fraction = 0.30) })
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(42, report.level)
        assertEquals(RRTone.CAUTION, report.tone)
        assertTrue(report.factors.any { it.name == "수면 질" && it.points == -8 })
    }

    @Test
    fun `수면 질 - 하락이 10퍼센트뿐이면 팩터 없음`() {
        // 최근 밤 27% vs 평균 30% → (30−27)/30 = 10% < 20%
        val vitals = VitalsSnapshot(
            hrvMs = reading(60.0, 60.0),
            restingHR = reading(52.0, 52.0),
            sleepHours = 7.0,
            sleepNights = listOf(night(daysAgo = 0.0, fraction = 0.27)) +
                (1..6).map { night(daysAgo = it.toDouble(), fraction = 0.30) })
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 질" })
    }

    @Test
    fun `수면 질·리듬 - 데이터가 있는 밤이 6개뿐이면 둘 다 팩터 없음`() {
        // 값 자체는 기준 초과지만(하락 33%, SD > 90분) 6개뿐이라 최소 7개 가드에 걸려 침묵한다
        val fractions = listOf(0.20, 0.30, 0.30, 0.30, 0.30, 0.30)
        val bedtimes = listOf(550.0, 550.0, 550.0, 700.0, 850.0, 850.0)
        val vitals = VitalsSnapshot(
            hrvMs = reading(60.0, 60.0),
            restingHR = reading(52.0, 52.0),
            sleepHours = 7.0,
            sleepNights = (0 until 6).map {
                night(daysAgo = it.toDouble(), fraction = fractions[it], bedtime = bedtimes[it])
            })
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 질" })
        assertFalse(report.factors.any { it.name == "수면 리듬" })
    }

    @Test
    fun `수면 리듬 - 취침 시각 표준편차가 90분을 넘으면 -6점`() {
        // 취침 시각(분) [550,550,550,700,850,850,850], 평균 700
        // 모집단분산 = (3·150² + 0 + 3·150²)/7 = 135000/7 ≈ 19285.7 → SD ≈ 138.9분(>90) → −6, 50 − 6 = 44
        val bedtimes = listOf(550.0, 550.0, 550.0, 700.0, 850.0, 850.0, 850.0)
        val vitals = VitalsSnapshot(
            hrvMs = reading(60.0, 60.0),
            restingHR = reading(52.0, 52.0),
            sleepHours = 7.0,
            sleepNights = bedtimes.mapIndexed { i, b -> night(daysAgo = i.toDouble(), bedtime = b) })
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(44, report.level)
        assertTrue(report.factors.any { it.name == "수면 리듬" && it.points == -6 })
    }

    @Test
    fun `수면 리듬 - 표준편차가 90분 미만이면 팩터 없음`() {
        // 취침 시각(분) [660×6, 775] → 평균 ≈676.3, SD ≈ 40.2분(<90) → 팩터 없음
        val bedtimes = listOf(660.0, 660.0, 660.0, 660.0, 660.0, 660.0, 775.0)
        val vitals = VitalsSnapshot(
            hrvMs = reading(60.0, 60.0),
            restingHR = reading(52.0, 52.0),
            sleepHours = 7.0,
            sleepNights = bedtimes.mapIndexed { i, b -> night(daysAgo = i.toDouble(), bedtime = b) })
        val report = BatteryEngine.compute(vitals, emptyList(), now)!!
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 리듬" })
    }
}
