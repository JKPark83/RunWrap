package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BatteryEngineTests {
    /// 2026-08-10(월) 18:00 KST — ReportMetricsTests와 같은 고정 시각
    private val now = iso("2026-08-10T09:00:00Z")

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double = 150.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = hr)

    private fun reading(today: Double, baseline: Double,
                        days: Int = 28): VitalsSnapshot.Reading =
        VitalsSnapshot.Reading(today = today, baseline = baseline, baselineDays = days)

    private fun night(daysAgo: Double, fraction: Double? = null,
                      bedtime: Double? = null): VitalsSnapshot.SleepNight =
        VitalsSnapshot.SleepNight(date = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                                  asleepHours = 7.0, deepRemFraction = fraction, bedtimeMinutes = bedtime)

    private fun compute(vitals: VitalsSnapshot, runs: List<RunSummary> = emptyList()): BatteryReport? =
        BatteryEngine.compute(vitals = vitals, runs = runs, now = now, zone = testZone)

    @Test
    @DisplayName("neutralVitalsGiveMidBattery")
    fun neutralVitalsGiveMidBattery() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertEquals(RRTone.steady, report.tone)
        assertEquals(3, report.factors.size)
        assertTrue(report.factors.all { it.points == 0 })
    }

    @Test
    @DisplayName("recoveredVitalsCharge")
    fun recoveredVitalsCharge() {
        // HRV +20% → +16, 안정 심박 −8% → +12, 수면 8.5시간 → +11
        val vitals = VitalsSnapshot(hrvMs = reading(72.0, 60.0),
                                    restingHR = reading(46.0, 50.0),
                                    sleepHours = 8.5)
        val report = assertNotNull(compute(vitals))
        assertEquals(89, report.level)
        assertEquals(RRTone.improving, report.tone)
        assertEquals("충전 충분", report.statusLabel)
    }

    @Test
    @DisplayName("poorVitalsAndTrainingDrainToZero")
    fun poorVitalsAndTrainingDrainToZero() {
        // HRV −30%(클램프 −20), 안정 심박 +12%(클램프 −15), 수면 5시간(−15)
        // + 오늘 10 km(−20) + ACWR 1.6(−8) → 50−78 → 0으로 클램프
        val vitals = VitalsSnapshot(hrvMs = reading(42.0, 60.0),
                                    restingHR = reading(56.0, 50.0),
                                    sleepHours = 5.0)
        val runs = listOf(run(daysAgo = 0.1, km = 10.0),
                          run(daysAgo = 8.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0),
                          run(daysAgo = 22.0, km = 5.0),
                          // 이슈 #49: 가드 28일 — 창 밖 앵커라 chronic 25/4=6.25, ACWR 1.6(−8) 유지
                          run(daysAgo = 30.0, km = 5.0))
        val report = assertNotNull(compute(vitals, runs))
        assertEquals(0, report.level)
        assertEquals(RRTone.overload, report.tone)
        assertTrue(report.factors.any { it.name == "오늘 훈련" && it.points == -20 })
        assertTrue(report.factors.any { it.name == "훈련 부하" && it.points == -8 })
    }

    @Test
    @DisplayName("ACWR 표본 가드 — 기록이 4주 미만이면 훈련 부하 감점이 없다 (이슈 #49)")
    fun acwrNeedsFourWeeksOfHistory() {
        // 중립 활력징후(각 0점). 1일 전 기록은 어제라 '오늘 훈련' 감점도 없다.
        // 옛 21일 가드였다면 10 ÷ (25/4=6.25) = 1.6 → −8이었지만, 이력이 22일뿐이라 nil
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0), restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 22.0, km = 5.0))
        val report = assertNotNull(compute(vitals, runs))
        assertEquals(false, report.factors.any { it.name == "훈련 부하" })
        assertEquals(50, report.level)
    }

    @Test
    @DisplayName("ACWR 표본 가드 — 4주 이상이면 부하 비율 1.6에 −8 감점")
    fun acwrPenaltyWithFourWeeksOfHistory() {
        // 위와 같은 기록 + 창 밖(30일) 앵커 → acute 10, chronic 25/4=6.25, 1.6
        // 감점 −min(15, round((1.6−1.3)×25=7.5)) = −8 → 50−8 = 42
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0), restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 22.0, km = 5.0),
                          run(daysAgo = 30.0, km = 5.0))
        val report = assertNotNull(compute(vitals, runs))
        assertTrue(report.factors.any { it.name == "훈련 부하" && it.points == -8 })
        assertEquals(42, report.level)
    }

    @Test
    @DisplayName("카드 게이트 — 훈련 부하 팩터만 ACWR 카드에 묶이고 나머지는 nil (이슈 #125)")
    fun onlyTrainingLoadFactorIsGatedByACWR() {
        // 위 −8 케이스와 같은 기록에 오늘 3km를 더해 '오늘 훈련'(−6)도 함께 나오게 한다
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0), restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val runs = listOf(run(daysAgo = 0.1, km = 3.0), run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 22.0, km = 5.0),
                          run(daysAgo = 30.0, km = 5.0))
        val report = assertNotNull(compute(vitals, runs))
        val load = assertNotNull(report.factors.firstOrNull { it.name == "훈련 부하" })
        assertEquals(ReportCard.acwr, load.gate)
        assertTrue(report.factors.any { it.name == "오늘 훈련" })
        assertTrue(report.factors.filter { it.name != "훈련 부하" }.all { it.gate == null })
    }

    @Test
    @DisplayName("ACWR 표본 가드 — 만성 주평균 3km 미만이면 감점 없음")
    fun acwrNeedsThreeKmChronic() {
        // 4주 이력은 있지만 창 안 거리 10km → chronic 10/4=2.5 < 3 → nil
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0), restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 30.0, km = 1.0))
        val report = assertNotNull(compute(vitals, runs))
        assertEquals(false, report.factors.any { it.name == "훈련 부하" })
        assertEquals(50, report.level)
    }

    @Test
    @DisplayName("requiresTwoCoreSignals")
    fun requiresTwoCoreSignals() {
        // HRV 하나만 유효 (안정 심박은 기준선 3일뿐, 수면 없음) → 계산하지 않는다
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0, days = 3))
        assertNull(compute(vitals))
    }

    @Test
    @DisplayName("outlierRespirationAndTemperaturePenalize")
    fun outlierRespirationAndTemperaturePenalize() {
        // 핵심 신호는 중립, 호흡수 +18%·손목 온도 +0.5°C → 각각 −6
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    respiratoryRate = reading(16.5, 14.0),
                                    wristTempC = reading(36.9, 36.4, days = 21),
                                    sleepHours = 7.0)
        val report = assertNotNull(compute(vitals))
        assertEquals(38, report.level)
        assertEquals(RRTone.caution, report.tone)
        assertTrue(report.factors.any { it.name == "호흡수" && it.points == -6 })
        assertTrue(report.factors.any { it.name == "손목 온도" && it.points == -6 })
    }

    @Test
    @DisplayName("typicalRespirationAndTemperatureStaySilent")
    fun typicalRespirationAndTemperatureStaySilent() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    respiratoryRate = reading(14.5, 14.2),
                                    wristTempC = reading(36.5, 36.4, days = 21),
                                    sleepHours = 7.0)
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "호흡수" })
        assertFalse(report.factors.any { it.name == "손목 온도" })
    }

    @Test
    @DisplayName("HRR — 기저 표본 4개는 최소 5개 미달이라 팩터 없음")
    fun hrrBelowMinCountStaysSilent() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    hrr = reading(22.0, 30.0, days = 4))
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertEquals(2, report.factors.size)
        assertFalse(report.factors.any { it.name == "심박 회복" })
    }

    @Test
    @DisplayName("HRR — 표본 5개·−25%면 −10점 클램프")
    fun hrrDropPenalizes() {
        // 21 / 28 = 0.75 → −25% → 정규화 −1(클램프) × 10 = −10
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    hrr = reading(21.0, 28.0, days = 5))
        val report = assertNotNull(compute(vitals))
        assertEquals(40, report.level)
        assertTrue(report.factors.any { it.name == "심박 회복" && it.points == -10 })
    }

    @Test
    @DisplayName("HRR — +25%면 +10점")
    fun hrrRiseCharges() {
        // 35 / 28 = 1.25 → +25% → 정규화 1(클램프) × 10 = +10
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    hrr = reading(35.0, 28.0, days = 5))
        val report = assertNotNull(compute(vitals))
        assertEquals(60, report.level)
        assertTrue(report.factors.any { it.name == "심박 회복" && it.points == 10 })
    }

    @Test
    @DisplayName("HRV 없이 안정 심박 + HRR 두 코어 신호만으로도 배터리가 노출된다")
    fun restingHRAndHRRAloneExposeBattery() {
        // 24 / 30 = 0.8 → −20% → 정규화 −0.8 × 10 = −8, 안정 심박은 중립(0) → 50 − 8 = 42
        val vitals = VitalsSnapshot(restingHR = reading(52.0, 52.0),
                                    hrr = reading(24.0, 30.0, days = 5))
        val report = assertNotNull(compute(vitals))
        assertEquals(42, report.level)
        assertEquals(2, report.factors.size)
        assertTrue(report.factors.any { it.name == "심박 회복" && it.points == -8 })
    }

    @Test
    @DisplayName("수면 질 — 기저 대비 20% 이상 하락하면 −8점")
    fun sleepQualityDropPenalizes() {
        // 최근 밤 22% vs 나머지 6개 밤 평균 30% → (30−22)/30 ≈ 26.7% ≥ 20% → −8, 50 − 8 = 42
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        vitals.sleepNights = listOf(night(daysAgo = 0.0, fraction = 0.22)) +
            (1..6).map { night(daysAgo = it.toDouble(), fraction = 0.30) }
        val report = assertNotNull(compute(vitals))
        assertEquals(42, report.level)
        assertEquals(RRTone.caution, report.tone)
        assertTrue(report.factors.any { it.name == "수면 질" && it.points == -8 })
    }

    @Test
    @DisplayName("수면 질 — 하락이 10%뿐이면 팩터 없음")
    fun sleepQualityMinorDropStaysSilent() {
        // 최근 밤 27% vs 평균 30% → (30−27)/30 = 10% < 20%
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        vitals.sleepNights = listOf(night(daysAgo = 0.0, fraction = 0.27)) +
            (1..6).map { night(daysAgo = it.toDouble(), fraction = 0.30) }
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 질" })
    }

    @Test
    @DisplayName("수면 질 신선도 — 단계 데이터가 있는 최근 밤이 3일 전이면 팩터 없음 (이슈 #99)")
    fun staleSleepQualityNightStaysSilent() {
        // 하락 폭은 위와 같이 26.7%지만, 최근 밤(3일 전 = 8/7)이 어제(8/9) 이전이라 신선도 가드에 걸린다
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        vitals.sleepNights = listOf(night(daysAgo = 3.0, fraction = 0.22)) +
            (4..9).map { night(daysAgo = it.toDouble(), fraction = 0.30) }
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 질" })
    }

    @Test
    @DisplayName("수면 질 신선도 — 최근 밤이 어제면 감점 유지")
    fun yesterdaySleepQualityNightPenalizes() {
        // 최근 밤(1일 전 = 8/9)은 어제라 가드 통과, 22% vs 30% → 26.7% 하락 → −8, 50 − 8 = 42
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        vitals.sleepNights = listOf(night(daysAgo = 1.0, fraction = 0.22)) +
            (2..7).map { night(daysAgo = it.toDouble(), fraction = 0.30) }
        val report = assertNotNull(compute(vitals))
        assertEquals(42, report.level)
        assertTrue(report.factors.any { it.name == "수면 질" && it.points == -8 })
    }

    @Test
    @DisplayName("수면 질·리듬 — 단계/취침 데이터가 있는 밤이 6개뿐이면 둘 다 팩터 없음")
    fun fewerThanSevenNightsStayBothSilent() {
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        // 값 자체는 기준 초과지만(하락 33%, SD > 90분) 6개뿐이라 최소 7개 가드에 걸려 침묵한다
        val fractions = listOf(0.20, 0.30, 0.30, 0.30, 0.30, 0.30)
        val bedtimes = listOf(550.0, 550.0, 550.0, 700.0, 850.0, 850.0)
        vitals.sleepNights = (0 until 6).map { night(daysAgo = it.toDouble(), fraction = fractions[it], bedtime = bedtimes[it]) }
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 질" })
        assertFalse(report.factors.any { it.name == "수면 리듬" })
    }

    @Test
    @DisplayName("수면 리듬 — 취침 시각 표준편차가 90분을 넘으면 −6점")
    fun bedtimeSDOverThresholdPenalizes() {
        // 취침 시각(분) [550,550,550,700,850,850,850], 평균 700
        // 모집단분산 = (3·150² + 0 + 3·150²)/7 = 135000/7 ≈ 19285.7 → SD ≈ 138.9분(>90) → −6, 50 − 6 = 44
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val bedtimes = listOf(550.0, 550.0, 550.0, 700.0, 850.0, 850.0, 850.0)
        vitals.sleepNights = bedtimes.mapIndexed { offset, element -> night(daysAgo = offset.toDouble(), bedtime = element) }
        val report = assertNotNull(compute(vitals))
        assertEquals(44, report.level)
        assertTrue(report.factors.any { it.name == "수면 리듬" && it.points == -6 })
    }

    @Test
    @DisplayName("수면 리듬 — 표준편차가 90분 미만이면 팩터 없음")
    fun bedtimeSDUnderThresholdStaysSilent() {
        // 취침 시각(분) [660×6, 775] → 평균 ≈676.3, SD ≈ 40.2분(<90) → 팩터 없음
        val vitals = VitalsSnapshot(hrvMs = reading(60.0, 60.0),
                                    restingHR = reading(52.0, 52.0),
                                    sleepHours = 7.0)
        val bedtimes = listOf(660.0, 660.0, 660.0, 660.0, 660.0, 660.0, 775.0)
        vitals.sleepNights = bedtimes.mapIndexed { offset, element -> night(daysAgo = offset.toDouble(), bedtime = element) }
        val report = assertNotNull(compute(vitals))
        assertEquals(50, report.level)
        assertFalse(report.factors.any { it.name == "수면 리듬" })
    }
}
