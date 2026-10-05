package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 대회 목표 상태 엔진 검증 (이슈 #21, 표본 규칙 개편은 이슈 #24). now = 2026-08-10T09:00:00Z 고정.
/// 예측 재료: 7일 전 5km 22:00(1,320초) 세션 → Riegel 10K 예측 1320×(10/5)^1.06 ≈ 2752.1초.
class RaceOutlookEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")

    private fun at(offsetSec: Double): Instant = instantSince1970(now.timeIntervalSince1970 + offsetSec)

    /// 5km 22:00(1,320초) 세션 — daysAgo로 1주·4주·8주 창 안팎을 오간다
    private fun run5K(daysAgo: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(), start = at(-daysAgo * 86_400),
                   durationSec = 1_320.0, distanceMeters = 5_000.0, avgHeartRate = 165.0)

    /// 거리별 외삽 상한의 통합 경로를 검증하기 위한 임의 거리 세션
    private fun run(km: Double, timeSec: Double, daysAgo: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(), start = at(-daysAgo * 86_400),
                   durationSec = timeSec, distanceMeters = km * 1_000, avgHeartRate = 165.0)

    /// ready 케이스 언래핑 헬퍼 — 다른 상태면 nil이라 assertNotNull이 실패를 찍는다
    private fun ready(status: RaceOutlookEngine.Status): RaceOutlookEngine.Outlook? =
        (status as? RaceOutlookEngine.Status.ready)?.outlook

    @Test
    @DisplayName("미설정 가드 — 레이스·기록·날짜 중 하나라도 없으면 notConfigured")
    fun notConfigured() {
        val date = at(20.0 * 86_400)
        assertEquals(RaceOutlookEngine.Status.notConfigured,
                     RaceOutlookEngine.status(race = null, goalSec = 2_700.0, raceDate = date,
                                              runs = emptyList(), now = now, zone = testZone))
        assertEquals(RaceOutlookEngine.Status.notConfigured,
                     RaceOutlookEngine.status(race = RaceDistance.tenK, goalSec = 0.0, raceDate = date,
                                              runs = emptyList(), now = now, zone = testZone))
        assertEquals(RaceOutlookEngine.Status.notConfigured,
                     RaceOutlookEngine.status(race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = null,
                                              runs = emptyList(), now = now, zone = testZone))
    }

    @Test
    @DisplayName("대회 종료 — 어제 대회는 raceFinished, 대회 당일은 아직 유효(D-day 0)")
    fun raceFinished() {
        assertEquals(RaceOutlookEngine.Status.raceFinished(race = RaceDistance.tenK),
                     RaceOutlookEngine.status(race = RaceDistance.tenK, goalSec = 2_700.0,
                                              raceDate = at(-86_400.0),
                                              runs = emptyList(), now = now, zone = testZone))
        // 자정 경계 기준이라 같은 날은 시각과 무관하게 0일 — 종료가 아니다
        assertEquals(RaceOutlookEngine.Status.awaitingRecords(race = RaceDistance.tenK, daysToRace = 0),
                     RaceOutlookEngine.status(race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = now,
                                              runs = emptyList(), now = now, zone = testZone))
    }

    @Test
    @DisplayName("기록 대기 — 최근 12주 세션이 없으면 예측 없이 D-day만 낸다")
    fun awaitingRecords() {
        // 90일 전 세션은 가장 넓은 대회 예측 창(84일) 밖 → Riegel 재료가 아니다 (이슈 #34)
        val status = RaceOutlookEngine.status(race = RaceDistance.tenK, goalSec = 2_700.0,
                                              raceDate = at(20.0 * 86_400),
                                              runs = listOf(run5K(daysAgo = 90.0)), now = now, zone = testZone)
        assertEquals(RaceOutlookEngine.Status.awaitingRecords(race = RaceDistance.tenK, daysToRace = 20), status)
    }

    @Test
    @DisplayName("외삽 상한 통합 경로 — 풀코스는 5km로 대기, 14.1km로 예측 가능")
    fun fullMarathonExtrapolationBoundary() {
        val raceDate = at(20.0 * 86_400)
        val short = RaceOutlookEngine.status(
            race = RaceDistance.full, goalSec = 14_400.0, raceDate = raceDate,
            runs = listOf(run(km = 5.0, timeSec = 1_500.0, daysAgo = 3.0)), now = now, zone = testZone)
        assertEquals(RaceOutlookEngine.Status.awaitingRecords(race = RaceDistance.full, daysToRace = 20), short)

        // 42.195/14.1 = 2.99배 — 3배 상한 안쪽이라 ready가 된다
        val long = RaceOutlookEngine.status(
            race = RaceDistance.full, goalSec = 14_400.0, raceDate = raceDate,
            runs = listOf(run(km = 14.1, timeSec = 5_076.0, daysAgo = 3.0)), now = now, zone = testZone)
        val outlook = assertNotNull(ready(long))
        // D-20 풀코스는 테이퍼(2주+대회 주간) — 4주 창을 건너뛰어 84가 된다 (이슈 #34)
        assertEquals(84, outlook.sampleWindowDays)
    }

    @Test
    @DisplayName("예측 — 선선한 달(1월)은 열 보정 없이 Riegel 그대로")
    fun readyCoolMonth() {
        // 1월 평년(−1.9°C·56%)은 열 점수가 38 미만 → 보정 0.
        // Riegel 2752.1초는 목표 45:00(2,700초) 초과·+5%(2,835초) 이내 → steady
        val raceDate = iso("2027-01-15T09:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = raceDate,
            runs = listOf(run5K(daysAgo = 7.0)), now = now, zone = testZone)))
        assertEquals(0.0, outlook.heatDeltaSecPerKm)
        assertTrue(abs(outlook.predictedSec - 2_752.1) < 1)
        assertEquals(1, outlook.raceMonth)
        assertEquals(RRTone.steady, outlook.tone)
    }

    @Test
    @DisplayName("예측 — 더운 달(8월)은 평년 열 보정을 페이스에 더한다")
    fun readyHotMonth() {
        // 8월 평년 26.1°C·74% → 이슬점 21.1, 열 점수 47.2 → 보정 8×1.5 + 1.2×3.0 ≈ 15.6초/km.
        // 페이스 275.2+15.6 = 290.8초/km, 완주 2,908.2초. D-day: 8/10 → 8/30 = 20일
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 3_000.0, raceDate = at(20.0 * 86_400),
            runs = listOf(run5K(daysAgo = 7.0)), now = now, zone = testZone)))
        assertEquals(20, outlook.daysToRace)
        assertEquals(8, outlook.raceMonth)
        assertTrue(abs(outlook.heatDeltaSecPerKm - 15.6) < 0.1)
        assertTrue(abs(outlook.predictedPaceSecPerKm - 290.8) < 0.1)
        assertTrue(abs(outlook.predictedSec - 2_908.2) < 1)
    }

    @Test
    @DisplayName("톤 — 목표 대비 달성권 improving / 5% 이내 steady / 그 밖 caution")
    fun tones() {
        // 8월 10K 예상 2,908.2초 고정 — 목표만 바꿔 경계를 확인한다
        fun tone(goal: Double): RRTone =
            assertNotNull(ready(RaceOutlookEngine.status(
                race = RaceDistance.tenK, goalSec = goal, raceDate = at(20.0 * 86_400),
                runs = listOf(run5K(daysAgo = 7.0)), now = now, zone = testZone))).tone
        assertEquals(RRTone.improving, tone(goal = 3_000.0))  // 2908 ≤ 3000
        assertEquals(RRTone.steady, tone(goal = 2_800.0))     // 2800 < 2908 ≤ 2940 (+5%)
        assertEquals(RRTone.caution, tone(goal = 2_700.0))    // 2908 > 2835 (+5%)
    }

    // MARK: - 표본 창 노출 (이슈 #24, 대회 예측 전용 창은 이슈 #34)

    @Test
    @DisplayName("표본 창 — 4주 안에 세션이 있으면 sampleWindowDays는 28")
    fun sampleWindowFourWeeks() {
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = at(20.0 * 86_400),
            runs = listOf(run5K(daysAgo = 3.0)), now = now, zone = testZone)))
        assertEquals(28, outlook.sampleWindowDays)
    }

    @Test
    @DisplayName("표본 창 — 4주에 없고 12주 안에만 있으면 84로 폴백")
    fun sampleWindowFallback() {
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = at(20.0 * 86_400),
            runs = listOf(run5K(daysAgo = 40.0)), now = now, zone = testZone)))
        assertEquals(84, outlook.sampleWindowDays)
    }

    // MARK: - 열 중립 환산 (이슈 #33)

    /// 세션 당시 날씨를 지정한 세션 — 열 중립 환산 검증용
    private fun run(km: Double, timeSec: Double, daysAgo: Double,
                    tempC: Double, humidityPct: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(), start = at(-daysAgo * 86_400),
                   durationSec = timeSec, distanceMeters = km * 1_000, avgHeartRate = 165.0,
                   weatherTempC = tempC, weatherHumidityPct = humidityPct)

    @Test
    @DisplayName("한여름 세션의 더위를 제거한 뒤 가을 대회로 환산한다")
    fun neutralizesSampleHeatForCoolRace() {
        // 7월 평년(25.3°C·76%) 날씨의 10km 50:00(300초/km) 세션.
        // 이슬점 20.8 → 열 점수 46.1 → 세션 보정량 8×1.5 + 0.06×3.0 ≈ 12.2초/km.
        // 중립 페이스 287.8초/km → 10월 대회(15.0°C·63%, 열 점수 23)는 보정 0 → 예상 2,878.1초.
        // 중립 환산이 없으면 3,000초가 그대로 나온다 — 그 회귀를 막는 테스트다.
        val raceDate = iso("2026-10-15T09:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 3_000.0, raceDate = raceDate,
            runs = listOf(run(km = 10.0, timeSec = 3_000.0, daysAgo = 3.0, tempC = 25.3, humidityPct = 76.0)),
            now = now, zone = testZone)))
        assertTrue(abs(outlook.sampleHeatDeltaSecPerKm - 12.2) < 0.1)
        assertEquals(0.0, outlook.heatDeltaSecPerKm)
        assertTrue(abs(outlook.predictedSec - 2_878.1) < 1)
    }

    @Test
    @DisplayName("선선한 날 세션은 보정량이 노이즈 바닥 미만이라 중립 환산 없이 그대로 쓴다")
    fun skipsNeutralizationBelowNoiseFloor() {
        // 22°C·70% → 이슬점 16.3, 열 점수 38.3 → 보정량 0.4초/km는 노이즈 바닥(3초/km) 미만
        // → HeatEngine이 nil을 반환해 원본 기록을 그대로 쓴다. 1월 대회 Riegel 2,752.1초
        val raceDate = iso("2027-01-15T09:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = raceDate,
            runs = listOf(run(km = 5.0, timeSec = 1_320.0, daysAgo = 7.0, tempC = 22.0, humidityPct = 70.0)),
            now = now, zone = testZone)))
        assertEquals(0.0, outlook.sampleHeatDeltaSecPerKm)
        assertTrue(abs(outlook.predictedSec - 2_752.1) < 1)
    }

    @Test
    @DisplayName("실내 세션은 날씨가 없어 중립 환산을 건너뛴다")
    fun indoorSampleHasNoWeather() {
        // run5K는 날씨 필드가 nil(실내·미기록과 동일) — HeatEngine 가드에 걸려
        // timeSec가 그대로 쓰인다. readyCoolMonth와 같은 2,752.1초가 나와야 한다
        val raceDate = iso("2027-01-15T09:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = raceDate,
            runs = listOf(run5K(daysAgo = 7.0)), now = now, zone = testZone)))
        assertEquals(0.0, outlook.sampleHeatDeltaSecPerKm)
        assertTrue(abs(outlook.predictedSec - 2_752.1) < 1)
    }

    @Test
    @DisplayName("한여름 세션 → 한여름 대회 — 중립 환산 뒤 대회일 더위를 다시 더한다")
    fun neutralizesThenReappliesRaceHeat() {
        // 세션 보정 −12.2초/km(7월 날씨) 뒤 8월 평년 보정 +15.6초/km(열 점수 47.2).
        // 예상 페이스 300 − 12.19 + 15.60 = 303.4초/km → 완주 3,034.1초.
        // 예전 동작(중립 환산 없음)은 315.6초/km·3,156초 — 더위가 이중으로 실렸다
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 3_000.0, raceDate = at(20.0 * 86_400),
            runs = listOf(run(km = 10.0, timeSec = 3_000.0, daysAgo = 3.0, tempC = 25.3, humidityPct = 76.0)),
            now = now, zone = testZone)))
        assertEquals(8, outlook.raceMonth)
        assertTrue(abs(outlook.sampleHeatDeltaSecPerKm - 12.2) < 0.1)
        assertTrue(abs(outlook.heatDeltaSecPerKm - 15.6) < 0.1)
        assertTrue(abs(outlook.predictedSec - 3_034.1) < 1)
    }

    // MARK: - 대회 노력도(EF) 환산·표본 창·VO₂max 추세 (이슈 #34)

    /// 평균 심박을 지정한 세션 — 노력도 환산 검증용 (hr nil = 심박 미기록)
    private fun run(km: Double, timeSec: Double, daysAgo: Double, hr: Double?): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(), start = at(-daysAgo * 86_400),
                   durationSec = timeSec, distanceMeters = km * 1_000, avgHeartRate = hr)

    @Test
    @DisplayName("이지런 표본 — EF 환산이 구간의 빠른 끝을 만든다")
    fun effortConversionFastEnd() {
        // 20km 2:00:00(360초/km)·평균 심박 145, HRmax 185, 풀코스.
        // 느린 끝(Riegel): 7,200×(42.195/20)^1.06 ≈ 15,886.1초 (4:24:46)
        // 빠른 끝(EF): EF = (60,000/360)/145 = 1.1494 → 목표 심박 185×0.84 = 155.4
        //   → 페이스 60,000/(1.1494×155.4) ≈ 335.9초/km → 14,173.6초 (3:56:14).
        // 훈련은 이지런만 해도 "대회 노력이면 이 정도"가 구간으로 드러난다
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_200.0, daysAgo = 5.0, hr = 145.0)),
            now = now, zone = testZone, hrMaxBpm = 185.0))
        assertEquals(28, p.windowDays)
        assertTrue(abs(p.riegelSec - 15_886.1) < 2)
        assertTrue(abs(assertNotNull(p.effortSec) - 14_173.6) < 2)
    }

    @Test
    @DisplayName("전력에 가까운 표본 — 구간이 좁아진다")
    fun nearMaxEffortNarrowsRange() {
        // 10km 40:00(240초/km)·평균 심박 166.5(HRmax 185의 90%), 10K 목표.
        // EF = 250/166.5 = 1.5015 → 목표 심박 185×0.93 = 172.05
        //   → 페이스 60,000/(1.5015×172.05) ≈ 232.3초/km → 2,322.6초.
        // Riegel 2,400초와 3.2% 차 — 이미 강했던 표본은 환산이 조금만 앞당긴다
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.tenK,
            runs = listOf(run(km = 10.0, timeSec = 2_400.0, daysAgo = 5.0, hr = 166.5)),
            now = now, zone = testZone, hrMaxBpm = 185.0))
        val effort = assertNotNull(p.effortSec)
        assertTrue(abs(effort - 2_322.6) < 2)
        assertTrue((p.riegelSec - effort) / p.riegelSec < 0.05)
    }

    @Test
    @DisplayName("심박이 없는 표본은 노력도 환산 없이 Riegel 단일 값으로 폴백한다")
    fun noHeartRateFallsBackToRiegel() {
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_200.0, daysAgo = 5.0, hr = null)),
            now = now, zone = testZone, hrMaxBpm = 185.0))
        assertNull(p.effortSec)
        assertTrue(abs(p.riegelSec - 15_886.1) < 2)
    }

    @Test
    @DisplayName("HRmax를 구할 수 없으면 노력도 환산 없이 Riegel 단일 값으로 폴백한다")
    fun noHrMaxFallsBackToRiegel() {
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_200.0, daysAgo = 5.0, hr = 145.0)),
            now = now, zone = testZone, hrMaxBpm = null))
        assertNull(p.effortSec)
    }

    @Test
    @DisplayName("테이퍼 기간에는 4주 창을 건너뛴다 — 감량 주간 이지런이 표본을 독식하지 않는다")
    fun taperSkipsFourWeekWindow() {
        // D-7 풀코스는 테이퍼(풀은 2주) — 10일 전의 느린 20km(2:10:00)가 아니라
        // 50일 전의 강한 20km(2:00:00)가 표본이 된다 (12주 창에서 최솟값)
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_800.0, daysAgo = 10.0, hr = 145.0),
                          run(km = 20.0, timeSec = 7_200.0, daysAgo = 50.0, hr = 145.0)),
            now = now, zone = testZone, daysToRace = 7))
        assertEquals(84, p.windowDays)
        assertTrue(abs(p.riegelSec - 15_886.1) < 2)   // 7,200 기반 — 테이퍼 스킵의 증거
    }

    @Test
    @DisplayName("VO₂max가 오른 만큼 오래된 표본을 현재 체력으로 앞당긴다")
    fun vo2TrendAdjustsOldSample() {
        // 40일 전 표본(4주 창 밖) + VO₂max 표본 시점 44.0 → 현재 46.0 (차이 2.0 ≥ 1.0 가드).
        // 비율 44/46 = 0.9565 → Riegel 15,886.1×0.9565 ≈ 15,195.4초
        val vo2 = listOf(
            TrainingGuideEngine.Vo2MaxSample(at(-45.0 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(-35.0 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(-7.0 * 86_400), 46.0),
            TrainingGuideEngine.Vo2MaxSample(at(-2.0 * 86_400), 46.0),
        )
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_200.0, daysAgo = 40.0, hr = null)),
            now = now, zone = testZone, vo2MaxSamples = vo2))
        assertTrue(abs(p.fitnessRatio - 0.9565) < 0.001)
        assertTrue(abs(p.riegelSec - 15_195.4) < 2)
    }

    @Test
    @DisplayName("VO₂max 변화가 ±1.0 미만이면 보정하지 않는다 — 워치 추정 노이즈 가드")
    fun vo2NoiseGuardSkipsAdjustment() {
        // 표본 시점 44.0 → 현재 44.5 — 차이 0.5는 노이즈 수준이라 배율 1.0 그대로
        val vo2 = listOf(
            TrainingGuideEngine.Vo2MaxSample(at(-45.0 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(-35.0 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(-7.0 * 86_400), 44.5),
            TrainingGuideEngine.Vo2MaxSample(at(-2.0 * 86_400), 44.5),
        )
        val p = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full,
            runs = listOf(run(km = 20.0, timeSec = 7_200.0, daysAgo = 40.0, hr = null)),
            now = now, zone = testZone, vo2MaxSamples = vo2))
        assertEquals(1.0, p.fitnessRatio)
        assertTrue(abs(p.riegelSec - 15_886.1) < 2)
    }

    @Test
    @DisplayName("HRmax 추정 — 관찰 최대(2번째 값)와 Tanaka 중 큰 쪽과 그 출처, 둘 다 없으면 190 폴백")
    fun hrMaxSources() {
        // 관찰: 세션 최고 심박 [190, 186, 178] → 이상치 방어로 2번째 값 186.
        // Tanaka(1990년생, now 2026년): 208 − 0.7×36 = 182.8 → 관찰 186이 이긴다
        fun runs(peaks: List<Double>): List<RunSummary> =
            peaks.mapIndexed { index, maxHR ->
                RunSummary(id = UUID.randomUUID().toString(), start = at(-(index + 1) * 86_400.0),
                           durationSec = 3_600.0, distanceMeters = 10_000.0, avgHeartRate = 150.0,
                           maxHeartRate = maxHR)
            }
        val observed = TrainingGuideEngine.hrMaxEstimate(runs = runs(listOf(190.0, 186.0, 178.0)),
                                                         now = now, zone = testZone, birthYear = 1990)
        assertEquals(186.0, observed.bpm)
        assertEquals(HeartRateProfile.Source.observed, observed.source)
        // 관찰 [180, 178, 170] → 178 < Tanaka 182.8 → 큰 쪽인 Tanaka (이슈 #56 출처 표기)
        val tanakaWins = TrainingGuideEngine.hrMaxEstimate(runs = runs(listOf(180.0, 178.0, 170.0)),
                                                           now = now, zone = testZone, birthYear = 1990)
        assertTrue(abs(tanakaWins.bpm - 182.8) < 0.01)
        assertEquals(HeartRateProfile.Source.tanaka, tanakaWins.source)
        // 관찰 표본 3개 미만 → Tanaka 폴백
        val tanaka = TrainingGuideEngine.hrMaxEstimate(runs = runs(listOf(190.0, 186.0)),
                                                       now = now, zone = testZone, birthYear = 1990)
        assertTrue(abs(tanaka.bpm - 182.8) < 0.01)
        assertEquals(HeartRateProfile.Source.tanaka, tanaka.source)
        // 둘 다 없으면 190 폴백 — 노력도는 reliableHrMax(nil)로 꺼진다
        val fallback = TrainingGuideEngine.hrMaxEstimate(runs = emptyList(), now = now, zone = testZone,
                                                         birthYear = null)
        assertEquals(190.0, fallback.bpm)
        assertEquals(HeartRateProfile.Source.fallback, fallback.source)
    }

    @Test
    @DisplayName("아웃룩 구간 — 빠른 끝만 목표 안이면 steady")
    fun outlookRangeTone() {
        // 5km 22:00·심박 165, HRmax 190, 8월 10K 대회 (열 보정 +15.6초/km).
        // 느린 끝 2,752.1 + 156 = 2,908.2초 / 빠른 끝: EF = (60,000/264)/165 = 1.3774,
        //   목표 심박 190×0.93 = 176.7 → 페이스 246.5 + 15.6 → 2,621.2초.
        // 목표 2,700: 느린 끝은 +5% 밖(예전 규칙이면 caution), 빠른 끝이 안 → steady
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = at(20.0 * 86_400),
            runs = listOf(run5K(daysAgo = 3.0)), now = now, zone = testZone, hrMaxBpm = 190.0)))
        val fast = assertNotNull(outlook.predictedFastSec)
        assertTrue(abs(outlook.predictedSec - 2_908.2) < 1)
        assertTrue(abs(fast - 2_621.2) < 2)
        assertEquals(RRTone.steady, outlook.tone)
    }
}
