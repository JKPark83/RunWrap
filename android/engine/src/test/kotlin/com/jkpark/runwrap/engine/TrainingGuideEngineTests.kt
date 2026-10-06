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

/// 훈련 가이드 엔진 검증 — Riegel 예측, VDOT 페이스 존, 주기화, 오늘의 훈련,
/// 세션 분류, 배터리 하향 보정, 표본 가드.
/// now = 2026-08-10T09:00:00Z = KST 2026-08-10(월) 18:00 고정 —
/// 이번 주(ISO 8601) 창은 08-10 00:00 ~ 08-17 00:00 KST, 남은 날은 7일이다.
class TrainingGuideEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")
    private val engine: TrainingGuideEngine get() = TrainingGuideEngine(now = now, zone = testZone)

    private fun at(base: Instant, offsetSec: Double): Instant =
        instantSince1970(base.timeIntervalSince1970 + offsetSec)

    private fun run(daysAgo: Double, km: Double, paceSecPerKm: Double = 360.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(), start = at(now, -daysAgo * 86_400),
                   durationSec = paceSecPerKm * km, distanceMeters = km * 1_000,
                   avgHeartRate = 150.0)

    /// 28일 동안 10km × 12회 = 총 120km → chronic(4주 주평균) = 30km.
    /// daysAgo 1~26.3 — 창(28일) 안이고 최고령이 21일을 넘어 가드를 통과한다.
    private val baseRuns: List<RunSummary>
        get() = (0 until 12).map { run(daysAgo = it * 2.3 + 1, km = 10.0) }

    /// 공인 거리 사이의 사각지대 회귀 표본. 7km × 8회가 8주에 걸쳐 있어
    /// 훈련 가이드 전체 가드(3주 이상·주 3km 이상)도 통과한다.
    private val nonStandardRuns: List<RunSummary>
        get() = (0 until 8).map { record(km = 7.0, timeSec = 2_520.0, daysAgo = it * 7.0 + 2) }

    /// 예측 표본이 될 세션 하나 — 거리·기록을 직접 지정한다 (이슈 #24로 입력이 세션이 됐다)
    private fun record(km: Double, timeSec: Double, daysAgo: Double): RunSummary =
        run(daysAgo = daysAgo, km = km, paceSecPerKm = timeSec / km)

    @Test
    @DisplayName("Riegel 예측 — 5K 25:00 세션이면 10K는 52:07로 예측한다")
    fun riegelPrediction() {
        // T2 = 1500 × (10/5)^1.06 = 1500 × 2.0849 ≈ 3127초 = 52:07
        val runs = listOf(record(km = 5.0, timeSec = 1_500.0, daysAgo = 3.0))
        val predicted = assertNotNull(TrainingGuideEngine.predictedTime(
            goal = RaceDistance.tenK, runs = runs, now = now))
        assertTrue(abs(predicted - 3_127) < 1)
    }

    @Test
    @DisplayName("Riegel 표본 창 — 8주(56일)가 지난 세션으로는 예측하지 않는다")
    fun riegelWindow() {
        val runs = listOf(record(km = 5.0, timeSec = 1_500.0, daysAgo = 57.0))
        assertNull(TrainingGuideEngine.predictedTime(goal = RaceDistance.tenK, runs = runs, now = now))
    }

    // MARK: - 표본 적격성·창 (이슈 #24)

    @Test
    @DisplayName("공인 거리 사각지대 — 6~9km 세션만 있어도 예측이 나온다 (이슈 #24 회귀)")
    fun predictsFromNonStandardDistances() {
        // 6~9km는 5K 창(≤5.5)과 10K 창(≥10) 어디에도 안 걸려 PR이 0개였던 구간이다.
        // 7km를 42분(360초/km)에 → 10K = 2520 × (10/7)^1.06 ≈ 3678초
        val predicted = assertNotNull(TrainingGuideEngine.predictedTime(
            goal = RaceDistance.tenK, runs = nonStandardRuns, now = now))
        assertTrue(abs(predicted - 3_678) < 1)
    }

    @Test
    @DisplayName("공인 거리 사각지대 — 같은 표본에서 훈련 페이스 존도 함께 나온다 (이슈 #24 회귀)")
    fun zonesFromNonStandardDistances() {
        // 예측과 존은 같은 7km-only bestPrediction에서 나오므로 한쪽만 나오는 일이 없어야 한다
        val guide = assertNotNull(engine.guide(runs = nonStandardRuns, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = null))
        val prediction = assertNotNull(guide.prediction)
        assertNotNull(guide.zones)
        assertEquals("7.0km", prediction.baseLabel)
        assertEquals(2_520.0, prediction.baseTimeSec)
    }

    @Test
    @DisplayName("표본 최소 거리 — 5km 미만 세션은 예측에 쓰지 않는다")
    fun rejectsShortSamples() {
        // 4.9km는 하한(5.0) 미달 — 페이스 변동성이 커 외삽 기반이 못 된다
        val short = listOf(record(km = 4.9, timeSec = 1_470.0, daysAgo = 2.0))
        assertNull(TrainingGuideEngine.predictedTime(goal = RaceDistance.tenK, runs = short, now = now))
        // 5.0km는 경계 안쪽이라 통과한다
        val exact = listOf(record(km = 5.0, timeSec = 1_500.0, daysAgo = 2.0))
        assertNotNull(TrainingGuideEngine.predictedTime(goal = RaceDistance.tenK, runs = exact, now = now))
    }

    @Test
    @DisplayName("외삽 배율 상한 — 목표가 세션 거리의 3배를 넘으면 예측하지 않는다")
    fun rejectsExcessiveExtrapolation() {
        // 5km → 풀(42.195km)은 8.4배 — Riegel 신뢰 구간(약 1/3~3배) 밖이라 제외한다
        val runs = listOf(record(km = 5.0, timeSec = 1_500.0, daysAgo = 2.0))
        assertNull(TrainingGuideEngine.predictedTime(goal = RaceDistance.full, runs = runs, now = now))
        // 14.1km면 42.195/14.1 = 2.99배로 상한 안쪽 — 예측이 나온다
        val long = listOf(record(km = 14.1, timeSec = 5_076.0, daysAgo = 2.0))
        assertNotNull(TrainingGuideEngine.predictedTime(goal = RaceDistance.full, runs = long, now = now))
    }

    @Test
    @DisplayName("표본 창 우선순위 — 1주 안에 세션이 있으면 더 빠른 8주 전 기록을 쓰지 않는다")
    fun recentWindowWinsOverFasterOldSample() {
        // 3일 전 느린 세션(10km 60분)과 40일 전 빠른 세션(10km 45분)이 함께 있는 상황.
        // 창 간 비교를 하지 않으므로 1주 창의 느린 쪽이 이긴다 — 의도된 동작(이슈 #24)
        val runs = listOf(record(km = 10.0, timeSec = 3_600.0, daysAgo = 3.0),
                          record(km = 10.0, timeSec = 2_700.0, daysAgo = 40.0))
        val best = assertNotNull(TrainingGuideEngine.bestPrediction(goal = RaceDistance.tenK, runs = runs,
                                                                    now = now))
        assertEquals(7, best.windowDays)
        assertTrue(abs(best.sec - 3_600) < 1)
    }

    @Test
    @DisplayName("표본 창 폴백 — 1주에 세션이 없으면 4주 → 8주로 넓힌다")
    fun fallsBackToWiderWindows() {
        // 20일 전 세션뿐 — 1주 창은 비었고 4주 창에서 잡힌다
        val fourWeek = assertNotNull(TrainingGuideEngine.bestPrediction(
            goal = RaceDistance.tenK, runs = listOf(record(km = 10.0, timeSec = 3_600.0, daysAgo = 20.0)),
            now = now))
        assertEquals(28, fourWeek.windowDays)

        // 40일 전 세션뿐 — 4주도 비어 8주 창까지 넓어진다
        val eightWeek = assertNotNull(TrainingGuideEngine.bestPrediction(
            goal = RaceDistance.tenK, runs = listOf(record(km = 10.0, timeSec = 3_600.0, daysAgo = 40.0)),
            now = now))
        assertEquals(56, eightWeek.windowDays)
    }

    @Test
    @DisplayName("근거 표기 — 공인 종목명이 아니라 실제 세션 거리로 적는다")
    fun baseLabelUsesActualDistance() {
        val runs = (0 until 8).map { record(km = 7.4, timeSec = 2_664.0, daysAgo = it * 3.0 + 1) }
        val guide = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = null))
        val prediction = assertNotNull(guide.prediction)
        assertEquals("7.4km", prediction.baseLabel)
        assertEquals(7, prediction.baseWindowDays)
    }

    @Test
    @DisplayName("세션 분류 — 주간 최장은 LSD, 4주 평균보다 10% 빠르면 스피드, 나머지 easy")
    fun sessionClassification() {
        // 주간 총 28km: 최장 14km ≥ 28×0.35 = 9.8 → LSD.
        // 4주 평균 페이스 360 기준: 320 ≤ 324(= 360×0.9) → 스피드, 365는 easy.
        val week = listOf(run(daysAgo = 1.0, km = 14.0, paceSecPerKm = 360.0),
                          run(daysAgo = 3.0, km = 6.0, paceSecPerKm = 320.0),
                          run(daysAgo = 5.0, km = 8.0, paceSecPerKm = 365.0))
        assertEquals(listOf(TrainingGuide.SessionKind.lsd, TrainingGuide.SessionKind.speed,
                            TrainingGuide.SessionKind.easy),
                     TrainingGuideEngine.classify(week = week, avg4wPaceSec = 360.0))
    }

    @Test
    @DisplayName("배터리 하향 보정 — overload/caution이면 LSD 상한을 25% 하한으로 내린다")
    fun batteryLowersLSD() {
        // chronic 30 → 주간 30~33km, LSD 정상 7.5(30×0.25)~11.55(33×0.35)
        val normal = assertNotNull(engine.guide(runs = baseRuns, race = RaceDistance.tenK,
                                                goalSec = null, batteryTone = RRTone.steady))
        assertTrue(abs(normal.prescription.weeklyKmLow - 30) < 0.01)
        assertTrue(abs(normal.prescription.weeklyKmHigh - 33) < 0.01)
        assertTrue(abs(normal.prescription.lsdKmLow - 7.5) < 0.01)
        assertTrue(abs(normal.prescription.lsdKmHigh - 11.55) < 0.01)
        assertEquals(false, normal.prescription.batteryLimited)

        // caution이면 상한도 30×0.25 = 7.5로 고정된다
        val limited = assertNotNull(engine.guide(runs = baseRuns, race = RaceDistance.tenK,
                                                 goalSec = null, batteryTone = RRTone.caution))
        assertTrue(abs(limited.prescription.lsdKmHigh - 7.5) < 0.01)
        assertTrue(limited.prescription.batteryLimited)
    }

    @Test
    @DisplayName("표본 부족 가드 — 기록 3주 미만이거나 만성 부하가 주 3km 미만이면 침묵(nil)")
    fun insufficientGuard() {
        // 기록이 11일치뿐 — 최고령이 21일보다 최근이라 가드에 걸린다
        val young = (0 until 6).map { run(daysAgo = it * 2.0 + 1, km = 10.0) }
        assertNull(engine.guide(runs = young, race = RaceDistance.tenK,
                                goalSec = null, batteryTone = null))

        // 3주는 넘지만 4주 총 8km → chronic 2km/주 < 3
        val tiny = listOf(run(daysAgo = 25.0, km = 4.0), run(daysAgo = 10.0, km = 4.0))
        assertNull(engine.guide(runs = tiny, race = RaceDistance.tenK,
                                goalSec = null, batteryTone = null))
    }

    @Test
    @DisplayName("목표 대비 판정 — 예측이 목표보다 빠르면 improving, 5% 넘게 느리면 caution")
    fun predictionTone() {
        // 이번 주 5K 25:00 세션이 최속 → 예측 3127초 (baseRuns 10km 60분보다 빠르다)
        val runs = baseRuns + listOf(record(km = 5.0, timeSec = 1_500.0, daysAgo = 3.0))
        // 목표 3200(53:20) → 달성권 improving
        val ok = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                            goalSec = 3_200.0, batteryTone = null))
        assertEquals(RRTone.improving, ok.prediction?.tone)
        // 목표 2900(48:20) → 3127 > 2900×1.05 = 3045 → caution
        val gap = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                             goalSec = 2_900.0, batteryTone = null))
        assertEquals(RRTone.caution, gap.prediction?.tone)
    }

    // MARK: - VDOT 페이스 존

    @Test
    @DisplayName("VDOT 역산 — 5K 19:57이면 VDOT ≈ 50 (Daniels 표 대조)")
    fun vdotFromRecord() {
        val vdot = assertNotNull(TrainingGuideEngine.vdot(distanceKm = 5.0, timeSec = 1_197.0))
        assertTrue(abs(vdot - 50) < 0.5)
    }

    @Test
    @DisplayName("페이스 존 — VDOT 50이면 이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″ (Daniels 표)")
    fun paceZones() {
        // 5K 19:57 → VDOT ≈ 50. 존 상수(62~74/88/97.5%)를 Daniels 표 페이스와 대조한다.
        // baseRuns(10km 60분)보다 빨라 이 세션이 표본으로 뽑힌다
        val runs = baseRuns + listOf(record(km = 5.0, timeSec = 1_197.0, daysAgo = 3.0))
        val guide = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = null))
        val zones = assertNotNull(guide.zones)
        assertTrue(abs(zones.easySecPerKm.start - 294) < 3)          // 4′54″ (74%)
        assertTrue(abs(zones.easySecPerKm.endInclusive - 338) < 3)   // 5′38″ (62%)
        assertTrue(abs(zones.tempoSecPerKm - 255) < 3)               // 4′15″ (88%)
        assertTrue(abs(zones.intervalSecPerKm - 235) < 3)            // 3′55″ (97.5%)
    }

    @Test
    @DisplayName("페이스 존 가드 — 유효 표본(5km 이상)이 없으면 예측·존을 내지 않는다 (처방만 노출)")
    fun zonesRequireRecentRecord() {
        // 4km × 24회 = 4주 96km → chronic 24km/주로 처방 가드는 통과하지만,
        // 모든 세션이 최소 거리(5km) 미만이라 예측 표본이 하나도 없다
        val shortOnly = (0 until 24).map { run(daysAgo = it + 1.0, km = 4.0) }
        val guide = assertNotNull(engine.guide(runs = shortOnly, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = null))
        assertNull(guide.zones)
        assertNull(guide.prediction)
        assertTrue(guide.prescription.weeklyKmLow > 0)   // 처방은 그대로 나온다
    }

    @Test
    @DisplayName("목표 페이스 가드 — 이지 존보다 느리거나 세계기록보다 빠르면 내지 않는다")
    fun goalPaceGuard() {
        // VDOT 50 → 이지 느린 끝 ≈ 338초/km. 종목·목표 기록이 어긋난 입력 실수 시나리오.
        // 풀 목표까지 재는 테스트라 외삽 상한(3배)에 걸리지 않게 15km 세션을 쓴다
        // (42.195/15 = 2.81배). 15km 63:34는 VDOT 50 — 5K 19:57과 같은 기력이다
        val runs = baseRuns + listOf(record(km = 15.0, timeSec = 3_814.0, daysAgo = 3.0))
        fun goalPace(race: RaceDistance, goalSec: Double): Double? =
            assertNotNull(engine.guide(runs = runs, race = race,
                                       goalSec = goalSec, batteryTone = null)).zones?.goalSecPerKm
        // 10K 45:00 → 270초/km — 이지(338)보다 빠르고 세계기록(150)보다 느려 정상 노출
        assertEquals(270.0, goalPace(race = RaceDistance.tenK, goalSec = 2_700.0))
        // 10K 3:00:00 → 1,080초/km — 이지 존보다 느리다 (하프·풀 목표가 10K에 남은 실수)
        assertNull(goalPace(race = RaceDistance.tenK, goalSec = 10_800.0))
        // 풀 30:00 → 42.7초/km — 5000m 세계기록 페이스(≈151초/km)보다 빠르다
        assertNull(goalPace(race = RaceDistance.full, goalSec = 1_800.0))
    }

    // MARK: - 주기화 (대회 날짜)

    @Test
    @DisplayName("주기화 단계 — 남은 주 수로 기초→강화→피크→테이퍼→대회 주간을 가른다")
    fun phaseBoundaries() {
        // 풀코스(테이퍼 2주): 10주 기초 / 9주 강화 / 5주 피크 / 2주 테이퍼 / 0주 대회 주간
        assertEquals(TrainingGuide.Phase.base, TrainingGuideEngine.phase(daysToRace = 70, race = RaceDistance.full))
        assertEquals(TrainingGuide.Phase.build, TrainingGuideEngine.phase(daysToRace = 63, race = RaceDistance.full))
        assertEquals(TrainingGuide.Phase.peak, TrainingGuideEngine.phase(daysToRace = 35, race = RaceDistance.full))
        assertEquals(TrainingGuide.Phase.taper, TrainingGuideEngine.phase(daysToRace = 14, race = RaceDistance.full))
        assertEquals(TrainingGuide.Phase.raceWeek, TrainingGuideEngine.phase(daysToRace = 3, race = RaceDistance.full))
        // 지난 날짜는 단계 없음, 10K는 테이퍼 없이 1주 전이 이미 피크
        assertNull(TrainingGuideEngine.phase(daysToRace = -1, race = RaceDistance.full))
        assertEquals(TrainingGuide.Phase.peak, TrainingGuideEngine.phase(daysToRace = 7, race = RaceDistance.tenK))
    }

    @Test
    @DisplayName("주기화 볼륨 — 테이퍼는 60~70%, 대회 주간은 40~50%로 감량하고 LSD·퀄리티를 끈다")
    fun periodizedVolume() {
        // chronic 30 → 테이퍼(풀코스 D-10): 30×0.6~0.7 = 18~21km
        val taper = assertNotNull(engine.guide(
            runs = baseRuns, race = RaceDistance.full, goalSec = null,
            raceDate = at(now, 10.0 * 86_400), batteryTone = null))
        assertEquals(TrainingGuide.Phase.taper, taper.prescription.phase)
        assertEquals(10, taper.prescription.daysToRace)
        assertTrue(abs(taper.prescription.weeklyKmLow - 18) < 0.01)
        assertTrue(abs(taper.prescription.weeklyKmHigh - 21) < 0.01)

        // 대회 주간(D-3): 30×0.4~0.5 = 12~15km, LSD 없음, 퀄리티 0회
        val raceWeek = assertNotNull(engine.guide(
            runs = baseRuns, race = RaceDistance.full, goalSec = null,
            raceDate = at(now, 3.0 * 86_400), batteryTone = null))
        assertEquals(TrainingGuide.Phase.raceWeek, raceWeek.prescription.phase)
        assertTrue(abs(raceWeek.prescription.weeklyKmLow - 12) < 0.01)
        assertTrue(abs(raceWeek.prescription.weeklyKmHigh - 15) < 0.01)
        assertEquals(0.0, raceWeek.prescription.lsdKmHigh)
        assertEquals(0, raceWeek.prescription.qualityCount)
    }

    @Test
    @DisplayName("피크 상한 — 10% 룰 점증이 종목·레벨 피크 거리에 닿으면 더 올리지 않는다")
    fun peakWeeklyKmCap() {
        // chronic 30, 5K 중급 피크 30 → 상한이 33(×1.1)이 아니라 30에서 멈춘다
        val guide = assertNotNull(engine.guide(runs = baseRuns, race = RaceDistance.fiveK,
                                               goalSec = null, batteryTone = null))
        assertTrue(abs(guide.prescription.weeklyKmHigh - 30) < 0.01)
    }

    @Test
    @DisplayName("퀄리티 구성 — 레벨·단계별 템포/인터벌 횟수, 배터리 하향이면 인터벌 제외")
    fun qualityMix() {
        // 날짜 미설정(nil)은 피크 수준: 초보 (1,0), 중급 (1,1)
        assertEquals(TrainingGuideEngine.QualityMix(1, 0),
                     TrainingGuideEngine.qualityMix(phase = null, level = RunnerLevel.beginner,
                                                    batteryLimited = false))
        assertEquals(TrainingGuideEngine.QualityMix(1, 1),
                     TrainingGuideEngine.qualityMix(phase = null, level = RunnerLevel.intermediate,
                                                    batteryLimited = false))
        // 기초기는 템포만, 대회 주간은 전부 끈다
        assertEquals(TrainingGuideEngine.QualityMix(1, 0),
                     TrainingGuideEngine.qualityMix(phase = TrainingGuide.Phase.base, level = RunnerLevel.intermediate,
                                                    batteryLimited = false))
        assertEquals(TrainingGuideEngine.QualityMix(0, 0),
                     TrainingGuideEngine.qualityMix(phase = TrainingGuide.Phase.raceWeek, level = RunnerLevel.advanced,
                                                    batteryLimited = false))
        // 배터리 하향 주간은 인터벌을 빼고 템포만 남긴다
        assertEquals(TrainingGuideEngine.QualityMix(1, 0),
                     TrainingGuideEngine.qualityMix(phase = null, level = RunnerLevel.advanced,
                                                    batteryLimited = true))
    }

    // MARK: - 오늘의 훈련

    @Test
    @DisplayName("오늘의 훈련 — 어제 롱런(LSD 하한 이상)을 뛰었으면 오늘은 회복 이지런")
    fun todayAfterHardDay() {
        // baseRuns의 최신 러닝 = daysAgo 1(어제 저녁) 10km ≥ LSD 하한 7.5 → 하드-이지 원칙
        val guide = assertNotNull(engine.guide(runs = baseRuns, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = RRTone.steady))
        val today = engine.todayWorkout(runs = baseRuns, guide = guide,
                                        batteryTone = RRTone.steady, weeklyGoal = 4)
        assertEquals(TodayWorkout.Kind.easy, today.kind)
        assertEquals(TodayWorkout.Reason.hardRecently, today.reason)
    }

    @Test
    @DisplayName("오늘의 훈련 — 남은 횟수가 1회면 미완의 롱런(LSD)부터 처방한다")
    fun todayLsdDue() {
        // 이번 주 0회, 최근 러닝은 그제(토) — 주 1회 목표라 오늘이 롱런의 마지막 기회
        val runs = (0 until 12).map { run(daysAgo = it * 2.2 + 2, km = 10.0) }
        val guide = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = RRTone.steady))
        val today = engine.todayWorkout(runs = runs, guide = guide,
                                        batteryTone = RRTone.steady, weeklyGoal = 1)
        assertEquals(TodayWorkout.Kind.lsd, today.kind)
        assertEquals(TodayWorkout.Reason.lsdDue, today.reason)
        // LSD 구간 7.5~11.55의 중앙값 9.525km — 상한 11km(10×1.1)에 걸리지 않는다
        val km = assertNotNull(today.distanceKm)
        assertTrue(abs(km - 9.525) < 0.01)
    }

    @Test
    @DisplayName("오늘의 훈련 — 퀄리티 잔여면 템포런 20분 분량을 T 페이스로 처방한다")
    fun todayTempo() {
        // 이번 주 스피드 0회 < 템포 처방 1회 → 템포 차례. T ≈ 255초 × 20분 → 4.7km.
        // 표본은 6일 전 5km 19:57(VDOT 50) — 1주 창(이슈 #24 우선 창) 안이라 같은 창의
        // 10km 60:00 세션들을 제치고 예측·존 표본이 된다. now가 월요일이라 6일 전은
        // 지난주(화)라서 이번 주 세션 분류에는 들어가지 않는다
        val runs = (0 until 12).map { run(daysAgo = it * 2.2 + 2, km = 10.0) } +
            listOf(record(km = 5.0, timeSec = 1_197.0, daysAgo = 6.0))
        val guide = assertNotNull(engine.guide(runs = runs, race = RaceDistance.tenK,
                                               goalSec = null, batteryTone = RRTone.steady))
        val today = engine.todayWorkout(runs = runs, guide = guide,
                                        batteryTone = RRTone.steady, weeklyGoal = 4)
        assertEquals(TodayWorkout.Kind.tempo, today.kind)
        assertEquals(TodayWorkout.Reason.qualityDue, today.reason)
        val km = assertNotNull(today.distanceKm)
        assertTrue(abs(km - 4.7) < 0.1)
        assertEquals(today.paceSecPerKm?.start, today.paceSecPerKm?.endInclusive)
    }

    @Test
    @DisplayName("오늘의 훈련 — 이번 주 스피드 1회를 이미 했으면 인터벌 차례다 (중급 5×800m)")
    fun todayInterval() {
        // 목요일 저녁 기준: 화요일에 빠른 6km(310 ≤ 4주 평균 355.8×0.9)를 이미 뛰었다.
        // 하드-이지 창(어제 0시~)은 지났고, 남은 날 4일 > 2라 LSD 규칙도 건너뛴다
        val now2 = iso("2026-08-13T09:00:00Z")
        val engine2 = TrainingGuideEngine(now = now2, zone = testZone)
        fun run2(daysAgo: Double, km: Double, paceSecPerKm: Double = 360.0): RunSummary =
            RunSummary(id = UUID.randomUUID().toString(), start = at(now2, -daysAgo * 86_400),
                       durationSec = paceSecPerKm * km, distanceMeters = km * 1_000,
                       avgHeartRate = 150.0)
        val runs = (0 until 11).map { run2(daysAgo = it * 2.1 + 4, km = 10.0) }.toMutableList()
        runs.add(run2(daysAgo = 2.0, km = 6.0, paceSecPerKm = 310.0))
        // 예측·존 표본 — 7일 전 5km 19:57(VDOT 50). 지지난 주라 이번 주 분류엔 안 들어간다
        runs.add(run2(daysAgo = 7.0, km = 5.0, paceSecPerKm = 239.4))
        val guide = assertNotNull(engine2.guide(runs = runs, race = RaceDistance.tenK,
                                                goalSec = null, batteryTone = RRTone.steady))
        val today = engine2.todayWorkout(runs = runs, guide = guide,
                                         batteryTone = RRTone.steady, weeklyGoal = 4)
        assertEquals(TodayWorkout.Kind.interval(reps = 5, meters = 800), today.kind)
        assertEquals(TodayWorkout.Reason.qualityDue, today.reason)
        assertEquals(4.0, today.distanceKm)   // 5 × 800m 본훈련 합계
    }

    // MARK: - 심박 존 (세션 상세, 이슈 #48)

    /// 존 비율 비교 — 부동소수 오차 허용
    private fun expectZones(zones: List<Double>, expected: List<Double>) {
        assertEquals(5, zones.size)
        for ((a, b) in zones.zip(expected)) assertTrue(abs(a - b) < 1e-9)
    }

    @Test
    @DisplayName("심박 존 — 관찰 최대·Tanaka·190 폴백 세 경로가 같은 샘플을 서로 다른 존으로 나눈다")
    fun heartRateZonesByHrMaxSource() {
        // 샘플 bpm [112, 129, 150, 166]을 0/10/20/30초에 둔다 → 가중 10·10·10·5(마지막은 5초), 합 35
        val samples = listOf(112.0, 129.0, 150.0, 166.0).mapIndexed { offset, bpm ->
            TrainingGuideEngine.HeartRateSample(time = at(now, offset * 10.0), bpm = bpm)
        }
        // 수동값 없음·%HRmax — 추정이 그대로 존 기준이 된다
        fun profile(estimate: TrainingGuideEngine.HrMaxEstimate): HeartRateProfile =
            TrainingGuideEngine.heartRateProfile(estimate = estimate, manualHrMax = 0, manualRestingHR = 0,
                                                 measuredRestingHR = null, zoneMethodRaw = "")

        // ① 관찰 최대: 세션 최고 심박 [190, 186, 178] → 2번째 값 186 (생년월일 없음)
        //    경계 111.6/130.2/148.8/167.4 → 112·129는 Z2, 150·166은 Z4
        val runs = listOf(190.0, 186.0, 178.0).mapIndexed { index, maxHR ->
            RunSummary(id = UUID.randomUUID().toString(), start = at(now, -(index + 1) * 86_400.0),
                       durationSec = 3_600.0, distanceMeters = 10_000.0, avgHeartRate = 150.0,
                       maxHeartRate = maxHR)
        }
        val observed = profile(TrainingGuideEngine.hrMaxEstimate(runs = runs, now = now, zone = testZone,
                                                                 birthYear = null))
        assertEquals(186.0, observed.hrMax)
        assertEquals(HeartRateProfile.Source.observed, observed.hrMaxSource)
        expectZones(TrainingGuideEngine.heartRateZones(samples = samples, profile = observed),
                    listOf(0.0, 20.0 / 35, 0.0, 15.0 / 35, 0.0))

        // ② Tanaka: 1990년생, now 2026년 → 208 − 0.7×36 = 182.8
        //    경계 109.68/127.96/146.24/164.52 → 112 Z2, 129 Z3(0.706), 150 Z4, 166 Z5(0.908)
        val tanaka = profile(TrainingGuideEngine.hrMaxEstimate(runs = emptyList(), now = now, zone = testZone,
                                                               birthYear = 1990))
        assertTrue(abs(tanaka.hrMax - 182.8) < 0.01)
        assertEquals(HeartRateProfile.Source.tanaka, tanaka.hrMaxSource)
        expectZones(TrainingGuideEngine.heartRateZones(samples = samples, profile = tanaka),
                    listOf(0.0, 10.0 / 35, 10.0 / 35, 10.0 / 35, 5.0 / 35))

        // ③ 폴백: 관찰 표본도 생년월일도 없음 → 190(출처 기본값)
        //    경계 114/133/152/171 → 112 Z1(0.589), 129 Z2, 150 Z3, 166 Z4
        val fallback = profile(TrainingGuideEngine.hrMaxEstimate(runs = emptyList(), now = now, zone = testZone,
                                                                 birthYear = null))
        assertEquals(190.0, fallback.hrMax)
        assertEquals(HeartRateProfile.Source.fallback, fallback.hrMaxSource)
        expectZones(TrainingGuideEngine.heartRateZones(samples = samples, profile = fallback),
                    listOf(10.0 / 35, 10.0 / 35, 10.0 / 35, 5.0 / 35, 0.0))
    }

    @Test
    @DisplayName("세션 최고 심박 — 230 초과 스파이크만 빼고, 쉬운 조깅 값은 남기며, 비면 nil")
    fun sessionPeakFiltersSpikes() {
        // 245는 착용 불량 스파이크 → 빠지고 188이 최고
        assertEquals(188.0, TrainingGuideEngine.sessionPeakBpm(listOf(150.0, 188.0, 245.0)))
        // 120 아래로만 달린 쉬운 조깅도 최고 심박 줄은 유지한다
        assertEquals(110.0, TrainingGuideEngine.sessionPeakBpm(listOf(80.0, 110.0)))
        // 남는 값이 없으면 nil — 화면에 내지 않는다
        assertNull(TrainingGuideEngine.sessionPeakBpm(listOf(240.0)))
        assertNull(TrainingGuideEngine.sessionPeakBpm(emptyList()))
    }

    // MARK: - 심박 기준 (이슈 #56)

    @Test
    @DisplayName("심박 기준 폴백 — 추정 재료가 없으면 190·출처 기본값, reliableHrMax는 nil이라 노력도는 Riegel로 떨어진다")
    fun heartRateProfileFallback() {
        val estimate = TrainingGuideEngine.hrMaxEstimate(runs = emptyList(), now = now, zone = testZone,
                                                         birthYear = null)
        assertEquals(190.0, estimate.bpm)
        assertEquals(HeartRateProfile.Source.fallback, estimate.source)
        val fallback = TrainingGuideEngine.heartRateProfile(
            estimate = estimate, manualHrMax = 0, manualRestingHR = 0,
            measuredRestingHR = null, zoneMethodRaw = "")
        assertEquals(190.0, fallback.hrMax)
        assertNull(fallback.reliableHrMax)
        // 직접 입력한 182는 근거가 있는 값이라 노력도에 쓴다
        val manual = TrainingGuideEngine.heartRateProfile(
            estimate = estimate, manualHrMax = 182, manualRestingHR = 0,
            measuredRestingHR = null, zoneMethodRaw = "")
        assertEquals(182.0, manual.reliableHrMax)
        assertEquals(HeartRateProfile.Source.manual, manual.hrMaxSource)
    }

    @Test
    @DisplayName("심박 기준 — 범위 안 수동 최대 심박은 추정보다 우선하고 출처는 직접 입력")
    fun manualHrMaxOverridesEstimate() {
        // 추정 186(관찰) 위에 수동 175 → 175·직접 입력. 경계 120·230도 범위 안이라 채택
        for (manual in listOf(175, 120, 230)) {
            val hr = TrainingGuideEngine.heartRateProfile(
                estimate = TrainingGuideEngine.HrMaxEstimate(186.0, HeartRateProfile.Source.observed),
                manualHrMax = manual, manualRestingHR = 0,
                measuredRestingHR = null, zoneMethodRaw = "")
            assertEquals(manual.toDouble(), hr.hrMax)
            assertEquals(HeartRateProfile.Source.manual, hr.hrMaxSource)
        }
    }

    @Test
    @DisplayName("심박 기준 — 범위 밖 수동값은 무시한다 (최대 119·231, 안정 29·101)")
    fun outOfRangeManualIgnored() {
        fun hr(maxManual: Int = 0, restManual: Int = 0, measured: Double?): HeartRateProfile =
            TrainingGuideEngine.heartRateProfile(
                estimate = TrainingGuideEngine.HrMaxEstimate(186.0, HeartRateProfile.Source.observed),
                manualHrMax = maxManual, manualRestingHR = restManual,
                measuredRestingHR = measured, zoneMethodRaw = "")
        // 최대 119·231 → 추정 186(관찰 최대)로 돌아간다
        for (manual in listOf(119, 231)) {
            assertEquals(186.0, hr(maxManual = manual, measured = null).hrMax)
            assertEquals(HeartRateProfile.Source.observed, hr(maxManual = manual, measured = null).hrMaxSource)
        }
        // 안정 29·101 → 건강 앱 최근값 52
        for (manual in listOf(29, 101)) {
            assertEquals(52.0, hr(restManual = manual, measured = 52.0).restingHR)
        }
        // 건강 앱 값도 범위 밖(25)이고 수동 없음 → 안정 심박 없음
        assertNull(hr(measured = 25.0).restingHR)
        // 범위 안 수동 48은 건강 앱 52보다 우선
        assertEquals(48.0, hr(restManual = 48, measured = 52.0).restingHR)
    }

    @Test
    @DisplayName("Karvonen 존 — 경계는 (HRmax−안정)×0.6/0.7/0.8/0.9 + 안정 심박이고, 50% HRR 아래는 Z1에 넣는다")
    fun karvonenZoneBoundaries() {
        // 수동 190·안정 50 → HRR 140, 경계 134/148/162/176
        val karvonen = TrainingGuideEngine.heartRateProfile(
            estimate = TrainingGuideEngine.HrMaxEstimate(186.0, HeartRateProfile.Source.observed),
            manualHrMax = 190, manualRestingHR = 50,
            measuredRestingHR = null, zoneMethodRaw = "karvonen")
        assertEquals(HeartRateZoneMethod.karvonen, karvonen.zoneMethod)
        assertEquals(50.0, karvonen.restingHR)
        // 샘플 [120, 140, 155, 170, 180]을 0/10/20/30/40초 → 가중 10·10·10·10·5(마지막 5초), 합 45
        val samples = listOf(120.0, 140.0, 155.0, 170.0, 180.0).mapIndexed { offset, bpm ->
            TrainingGuideEngine.HeartRateSample(time = at(now, offset * 10.0), bpm = bpm)
        }
        // %HRR 0.5/0.643/0.75/0.857/0.929 → Z1·Z2·Z3·Z4·Z5 한 개씩
        expectZones(TrainingGuideEngine.heartRateZones(samples = samples, profile = karvonen),
                    listOf(10.0 / 45, 10.0 / 45, 10.0 / 45, 10.0 / 45, 5.0 / 45))
        // 같은 샘플을 %HRmax(190)로 → 0.632/0.737/0.816/0.895/0.947 → Z2·Z3·Z4·Z4·Z5
        val percentMax = TrainingGuideEngine.heartRateProfile(
            estimate = TrainingGuideEngine.HrMaxEstimate(186.0, HeartRateProfile.Source.observed),
            manualHrMax = 190, manualRestingHR = 50,
            measuredRestingHR = null, zoneMethodRaw = "percentMax")
        expectZones(TrainingGuideEngine.heartRateZones(samples = samples, profile = percentMax),
                    listOf(0.0, 10.0 / 45, 10.0 / 45, 20.0 / 45, 5.0 / 45))
        // 110 bpm = (110−50)/140 = 0.43 HRR — 명목 하한 0.5 아래지만 버리지 않고 Z1
        expectZones(TrainingGuideEngine.heartRateZones(
                        samples = listOf(TrainingGuideEngine.HeartRateSample(time = now, bpm = 110.0)),
                        profile = karvonen),
                    listOf(1.0, 0.0, 0.0, 0.0, 0.0))
    }

    @Test
    @DisplayName("HRR 폴백 — 안정 심박이 없으면 Karvonen을 골라도 %HRmax로 계산한다")
    fun karvonenFallsBackWithoutResting() {
        val samples = listOf(120.0, 140.0, 155.0, 170.0, 180.0).mapIndexed { offset, bpm ->
            TrainingGuideEngine.HeartRateSample(time = at(now, offset * 10.0), bpm = bpm)
        }
        fun hr(raw: String): HeartRateProfile =
            TrainingGuideEngine.heartRateProfile(
                estimate = TrainingGuideEngine.HrMaxEstimate(190.0, HeartRateProfile.Source.observed),
                manualHrMax = 0, manualRestingHR = 0,
                measuredRestingHR = null, zoneMethodRaw = raw)
        val chosenKarvonen = hr("karvonen")
        assertEquals(HeartRateZoneMethod.percentMax, chosenKarvonen.zoneMethod)
        assertNull(chosenKarvonen.restingHR)
        assertEquals(TrainingGuideEngine.heartRateZones(samples = samples, profile = hr("percentMax")),
                     TrainingGuideEngine.heartRateZones(samples = samples, profile = chosenKarvonen))
        // 빈 값·모르는 값은 기본 %HRmax
        assertEquals(HeartRateZoneMethod.percentMax, hr("").zoneMethod)
        assertEquals(HeartRateZoneMethod.percentMax, hr("unknown").zoneMethod)
    }
}
