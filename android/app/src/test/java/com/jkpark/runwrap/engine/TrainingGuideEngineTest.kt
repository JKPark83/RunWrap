package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 훈련 가이드 엔진 v2 검증 — Riegel 예측·VDOT 존·주기화·주간 처방·오늘의 훈련.
/// now = 2026-08-10T09:00:00Z = KST 월 18:00. iOS `TrainingGuideEngineTests` 이식 (18개 전부).
class TrainingGuideEngineTest {
    private val now = Instant.parse("2026-08-10T09:00:00Z")
    private val engine = TrainingGuideEngine(now = now)

    private fun run(daysAgo: Double, km: Double, paceSecPerKm: Double = 360.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * paceSecPerKm,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    /// 4주에 걸쳐 10km × 12회 — 만성 부하 30km/주. 가드(3주·주 3km)를 넉넉히 넘는다
    private val baseRuns = (0 until 12).map { run(it * 2.3 + 1, 10.0) }

    private fun record(label: String, km: Double, timeSec: Double, daysAgo: Double) =
        PersonalRecords.Entry(label = label, distanceKm = km, timeSec = timeSec,
                              run = run(daysAgo, km, timeSec / km))

    // MARK: - 진단 (Riegel)

    @Test
    fun `Riegel 예측 - 5K 25분에서 10K 약 52분 07초, 8주 지난 PR은 버린다`() {
        // 1500 × 2^1.06 ≈ 3127초
        val fresh = TrainingGuideEngine.predictedTime(
            RaceDistance.TEN_K, listOf(record("5K", 5.0, 1_500.0, daysAgo = 7.0)), now)
        assertNotNull(fresh)
        assertEquals(3_127.0, fresh!!, 1.0)

        assertNull(TrainingGuideEngine.predictedTime(
            RaceDistance.TEN_K, listOf(record("5K", 5.0, 1_500.0, daysAgo = 57.0)), now))
    }

    @Test
    fun `목표 대비 판정 - 달성권이면 improving, 5퍼센트 넘게 밀리면 caution`() {
        val records = listOf(record("5K", 5.0, 1_500.0, daysAgo = 7.0))
        // 예측 ≈ 3127초: 목표 3200이면 달성권, 2900이면 5% 밖
        val reachable = engine.guide(baseRuns, records, RaceDistance.TEN_K,
                                     goalSec = 3_200.0, batteryTone = RRTone.STEADY)
        assertEquals(RRTone.IMPROVING, reachable?.prediction?.tone)

        val missed = engine.guide(baseRuns, records, RaceDistance.TEN_K,
                                  goalSec = 2_900.0, batteryTone = RRTone.STEADY)
        assertEquals(RRTone.CAUTION, missed?.prediction?.tone)
    }

    // MARK: - VDOT·페이스 존

    @Test
    fun `VDOT - 5K 19분 57초는 약 50이다 - Daniels 표 대조`() {
        val vdot = TrainingGuideEngine.vdot(5.0, 1_197.0)
        assertNotNull(vdot)
        assertEquals(50.0, vdot!!, 0.5)
    }

    @Test
    fun `페이스 존 - VDOT 50이면 이지 294~338, 템포 255, 인터벌 235초 부근`() {
        val records = listOf(record("5K", 5.0, 1_197.0, daysAgo = 7.0))
        val zones = engine.guide(baseRuns, records, RaceDistance.TEN_K,
                                 goalSec = null, batteryTone = RRTone.STEADY)?.zones
        assertNotNull(zones)
        assertEquals(294.0, zones!!.easySecPerKm.start, 3.0)
        assertEquals(338.0, zones.easySecPerKm.endInclusive, 3.0)
        assertEquals(255.0, zones.tempoSecPerKm, 3.0)
        assertEquals(235.0, zones.intervalSecPerKm, 3.0)
    }

    @Test
    fun `PR이 8주보다 오래되면 예측도 존도 내지 않는다`() {
        val records = listOf(record("5K", 5.0, 1_197.0, daysAgo = 57.0))
        val guide = engine.guide(baseRuns, records, RaceDistance.TEN_K,
                                 goalSec = null, batteryTone = RRTone.STEADY)
        assertNotNull(guide)
        assertNull(guide!!.zones)
        assertNull(guide.prediction)
    }

    @Test
    fun `목표 페이스 가드 - 조깅권이거나 세계기록보다 빠르면 내지 않는다`() {
        val records = listOf(record("5K", 5.0, 1_197.0, daysAgo = 7.0))
        fun goalPace(race: RaceDistance, goalSec: Double) =
            engine.guide(baseRuns, records, race, goalSec = goalSec,
                         batteryTone = RRTone.STEADY)?.zones?.goalSecPerKm

        assertEquals(270.0, goalPace(RaceDistance.TEN_K, 2_700.0)!!, 1e-9)
        assertNull(goalPace(RaceDistance.TEN_K, 10_800.0))   // 108′/km — 이지 존보다 느리다
        assertNull(goalPace(RaceDistance.FULL, 1_800.0))     // 43″/km — 사람 기록이 아니다
    }

    // MARK: - 주간 처방·배터리

    @Test
    fun `주간 처방 - 만성 30이면 30~33km, 배터리 주의면 LSD 상한을 하한으로 접는다`() {
        val steady = engine.guide(baseRuns, emptyList(), RaceDistance.FULL,
                                  goalSec = null, batteryTone = RRTone.STEADY)!!
        assertEquals(30.0, steady.prescription.weeklyKmLow, 0.01)
        assertEquals(33.0, steady.prescription.weeklyKmHigh, 0.01)
        assertEquals(7.5, steady.prescription.lsdKmLow, 0.01)
        assertEquals(11.55, steady.prescription.lsdKmHigh, 0.01)
        assertTrue(!steady.prescription.batteryLimited)

        val caution = engine.guide(baseRuns, emptyList(), RaceDistance.FULL,
                                   goalSec = null, batteryTone = RRTone.CAUTION)!!
        assertEquals(7.5, caution.prescription.lsdKmHigh, 0.01)
        assertTrue(caution.prescription.batteryLimited)
    }

    @Test
    fun `가드 - 기록 3주 미만이거나 만성 부하 주 3km 미만이면 null`() {
        val young = (0 until 6).map { run(it * 2.0 + 1, 10.0) }   // 최고령 11일
        assertNull(engine.guide(young, emptyList(), RaceDistance.FULL,
                                goalSec = null, batteryTone = RRTone.STEADY))

        val tiny = listOf(run(25.0, 4.0), run(10.0, 4.0))          // 만성 2km/주
        assertNull(engine.guide(tiny, emptyList(), RaceDistance.FULL,
                                goalSec = null, batteryTone = RRTone.STEADY))
    }

    @Test
    fun `피크 상한 - 5K 목표는 주간 상한이 30km에서 멈춘다`() {
        val guide = engine.guide(baseRuns, emptyList(), RaceDistance.FIVE_K,
                                 goalSec = null, batteryTone = RRTone.STEADY)!!
        assertEquals(30.0, guide.prescription.weeklyKmHigh, 0.01)
    }

    // MARK: - 주기화

    @Test
    fun `단계 판정 - 풀코스 D-70 기초, D-63 강화, D-35 피크, D-14 테이퍼, D-3 대회 주간`() {
        assertEquals(TrainingGuide.Phase.BASE, TrainingGuideEngine.phase(70, RaceDistance.FULL))
        assertEquals(TrainingGuide.Phase.BUILD, TrainingGuideEngine.phase(63, RaceDistance.FULL))
        assertEquals(TrainingGuide.Phase.PEAK, TrainingGuideEngine.phase(35, RaceDistance.FULL))
        assertEquals(TrainingGuide.Phase.TAPER, TrainingGuideEngine.phase(14, RaceDistance.FULL))
        assertEquals(TrainingGuide.Phase.RACE_WEEK, TrainingGuideEngine.phase(3, RaceDistance.FULL))
        assertNull(TrainingGuideEngine.phase(-1, RaceDistance.FULL))
        // 5K/10K는 테이퍼 없이 대회 주간으로 직행 — D-7이면 아직 피크
        assertEquals(TrainingGuide.Phase.PEAK, TrainingGuideEngine.phase(7, RaceDistance.TEN_K))
    }

    @Test
    fun `테이퍼 - D-10이면 볼륨을 60~70퍼센트로 감량한다`() {
        val guide = engine.guide(baseRuns, emptyList(), RaceDistance.FULL, goalSec = null,
                                 raceDate = now.plusSeconds(10 * 86_400L),
                                 batteryTone = RRTone.STEADY)!!
        assertEquals(TrainingGuide.Phase.TAPER, guide.prescription.phase)
        assertEquals(10, guide.prescription.daysToRace)
        assertEquals(18.0, guide.prescription.weeklyKmLow, 0.01)   // 30 × 0.6
        assertEquals(21.0, guide.prescription.weeklyKmHigh, 0.01)  // 30 × 0.7
    }

    @Test
    fun `대회 주간 - 볼륨 40~50퍼센트, 롱런과 퀄리티는 끈다`() {
        val guide = engine.guide(baseRuns, emptyList(), RaceDistance.FULL, goalSec = null,
                                 raceDate = now.plusSeconds(3 * 86_400L),
                                 batteryTone = RRTone.STEADY)!!
        assertEquals(TrainingGuide.Phase.RACE_WEEK, guide.prescription.phase)
        assertEquals(12.0, guide.prescription.weeklyKmLow, 0.01)   // 30 × 0.4
        assertEquals(15.0, guide.prescription.weeklyKmHigh, 0.01)  // 30 × 0.5
        assertEquals(0.0, guide.prescription.lsdKmHigh, 1e-9)
        assertEquals(0, guide.prescription.qualityCount)
    }

    @Test
    fun `퀄리티 구성 - 단계와 레벨과 배터리로 템포·인터벌 횟수를 가른다`() {
        assertEquals(1 to 0, TrainingGuideEngine.qualityMix(null, RunnerLevel.BEGINNER, false))
        assertEquals(1 to 1, TrainingGuideEngine.qualityMix(null, RunnerLevel.INTERMEDIATE, false))
        assertEquals(1 to 0, TrainingGuideEngine.qualityMix(
            TrainingGuide.Phase.BASE, RunnerLevel.INTERMEDIATE, false))
        assertEquals(0 to 0, TrainingGuideEngine.qualityMix(
            TrainingGuide.Phase.RACE_WEEK, RunnerLevel.ADVANCED, false))
        assertEquals(1 to 0, TrainingGuideEngine.qualityMix(null, RunnerLevel.ADVANCED, true))
    }

    // MARK: - 세션 분류

    @Test
    fun `세션 분류 - 주간 최장이 LSD, 평균보다 10퍼센트 빠르면 스피드, 나머지 이지`() {
        val week = listOf(run(1.0, 14.0, 360.0), run(3.0, 6.0, 320.0), run(5.0, 8.0, 365.0))
        assertEquals(
            listOf(TrainingGuide.SessionKind.LSD, TrainingGuide.SessionKind.SPEED,
                   TrainingGuide.SessionKind.EASY),
            TrainingGuideEngine.classify(week, 360.0),
        )
    }

    // MARK: - 오늘의 훈련

    @Test
    fun `오늘의 훈련 - 어제 롱런을 뛰었으면 오늘은 회복 이지런이다`() {
        // baseRuns의 어제 10km는 LSD 하한(7.5km) 이상 — 하드-이지 원칙
        val guide = engine.guide(baseRuns, emptyList(), RaceDistance.FULL,
                                 goalSec = null, batteryTone = RRTone.STEADY)!!
        val today = engine.todayWorkout(baseRuns, guide, RRTone.STEADY, 4)
        assertEquals(TodayWorkout.Kind.Easy, today.kind)
        assertEquals(TodayWorkout.Reason.HARD_RECENTLY, today.reason)
    }

    @Test
    fun `오늘의 훈련 - 남은 횟수가 1이고 롱런 미완이면 LSD가 우선이다`() {
        // 최근 러닝이 이틀 전 — 하드-이지에 안 걸리고, 주간 목표 1회의 마지막 기회
        val runs = (0 until 12).map { run(it * 2.2 + 2, 10.0) }
        val guide = engine.guide(runs, emptyList(), RaceDistance.FULL,
                                 goalSec = null, batteryTone = RRTone.STEADY)!!
        val today = engine.todayWorkout(runs, guide, RRTone.STEADY, 1)
        assertEquals(TodayWorkout.Kind.Lsd, today.kind)
        assertEquals(TodayWorkout.Reason.LSD_DUE, today.reason)
        // LSD 목표 중앙값 (7.5 + 11.55) / 2 = 9.525km
        assertEquals(9.525, today.distanceKm!!, 0.01)
    }

    @Test
    fun `오늘의 훈련 - 퀄리티 잔여면 템포 20분 분량을 처방한다`() {
        val runs = (0 until 12).map { run(it * 2.2 + 2, 10.0) }
        val records = listOf(record("5K", 5.0, 1_197.0, daysAgo = 7.0))
        val guide = engine.guide(runs, records, RaceDistance.FULL,
                                 goalSec = null, batteryTone = RRTone.STEADY)!!
        val today = engine.todayWorkout(runs, guide, RRTone.STEADY, 4)
        assertEquals(TodayWorkout.Kind.Tempo, today.kind)
        assertEquals(TodayWorkout.Reason.QUALITY_DUE, today.reason)
        assertEquals(4.7, today.distanceKm!!, 0.1)   // 1200초 ÷ 템포 페이스 ≈ 4.7km
        assertEquals(today.paceSecPerKm!!.start, today.paceSecPerKm!!.endInclusive, 1e-9)
    }

    @Test
    fun `오늘의 훈련 - 템포를 이미 뛰었으면 다음 퀄리티는 인터벌이다`() {
        // 목 18:00 KST — 이번 주에 스피드 세션(310초/km, 평균의 90% 이하) 1회 완료
        val now2 = Instant.parse("2026-08-13T09:00:00Z")
        val engine2 = TrainingGuideEngine(now = now2)
        fun run2(daysAgo: Double, km: Double, paceSecPerKm: Double = 360.0) = RunSummary(
            id = "run2-$daysAgo-$km",
            start = now2.minusMillis((daysAgo * 86_400_000).roundToLong()),
            durationSec = km * paceSecPerKm,
            distanceMeters = km * 1_000,
            avgHeartRate = 150.0,
        )
        val runs = (0 until 11).map { run2(it * 2.1 + 4, 10.0) } + run2(2.0, 6.0, 310.0)
        val records = listOf(PersonalRecords.Entry(
            label = "5K", distanceKm = 5.0, timeSec = 1_197.0, run = run2(7.0, 5.0, 239.4)))
        val guide = engine2.guide(runs, records, RaceDistance.TEN_K,
                                  goalSec = null, batteryTone = RRTone.STEADY)!!
        val today = engine2.todayWorkout(runs, guide, RRTone.STEADY, 4)
        assertEquals(TodayWorkout.Kind.Interval(5, 800), today.kind)   // 런잘알 스펙 5×800m
        assertEquals(TodayWorkout.Reason.QUALITY_DUE, today.reason)
        assertEquals(4.0, today.distanceKm!!, 1e-9)                    // 본훈련 합계 4km
    }
}
