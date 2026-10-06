package com.jkpark.runwrap.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 훈련 계획 캘린더 엔진 검증 (이슈 #189) — 표본·지평 가드, 주 구성, guide()와의 일치,
/// 10% 룰 점증·피크 정지, 테이퍼, 실제 거리, 주 라벨.
/// now = 2026-08-10T09:00:00Z = KST 2026-08-10(월) 18:00 고정 —
/// 월요일이라 대회까지의 달력 주 수가 daysToRace / 7 + 1과 같다.
class TrainingPlanEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")

    /// 주 경계가 시각에 민감한 테스트용 — KST 고정 (이번 주 = 08-10 00:00 ~ 08-17 00:00 KST)
    private val kst: ZoneId = KST

    private fun run(daysAgo: Double, km: Double, minPerKm: Double = 6.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = 150.0)

    /// 28일 동안 10km × 12회 = 총 120km → chronic(4주 주평균) = 30km.
    /// daysAgo 1~26.3 — 최고령이 21일을 넘어 가드를 통과한다 (TrainingGuideEngineTests와 같은 표본)
    private val baseRuns: List<RunSummary>
        get() = (0 until 12).map { run(daysAgo = it.toDouble() * 2.3 + 1, km = 10.0) }

    private fun raceDate(inDays: Int): Instant =
        now.plusSeconds(inDays.toLong() * 86_400)

    private fun plan(runs: List<RunSummary>? = null, race: RaceDistance = RaceDistance.full,
                     level: RunnerLevel = RunnerLevel.intermediate, days: Int,
                     zone: ZoneId = testZone): TrainingPlan? =
        TrainingPlanEngine.plan(runs = runs ?: baseRuns, race = race, level = level,
                                raceDate = raceDate(inDays = days), now = now, zone = zone)

    private fun approx(a: Double?, b: Double): Boolean {
        if (a == null) return false
        return abs(a - b) < 1e-6
    }

    // MARK: - ① 가드

    @Test
    @DisplayName("표본 부족 가드 — 기록이 21일 미만이면 계획을 내지 않는다")
    fun guardShortHistory() {
        // 최고령 20일 전 — guide()와 같은 3주 가드에 걸린다
        val runs = (0 until 10).map { run(daysAgo = it.toDouble() * 2 + 1, km = 10.0) }
        assertNull(plan(runs = runs, days = 70))
    }

    @Test
    @DisplayName("표본 부족 가드 — 만성 부하가 주 3km 미만이면 계획을 내지 않는다")
    fun guardLowChronic() {
        // 28일 합 2 + 5 = 7km → 7 / 4 = 1.75km < 3
        val runs = listOf(run(daysAgo = 25.0, km = 2.0), run(daysAgo = 22.0, km = 5.0))
        assertNull(plan(runs = runs, days = 70))
    }

    @Test
    @DisplayName("지평 가드 — 대회가 지났으면 nil, 대회 당일은 계획을 낸다")
    fun guardRacePassed() {
        assertNull(plan(days = -1))
        assertNotNull(plan(days = 0))
    }

    @Test
    @DisplayName("지평 가드 — 대회가 25주 뒤면 nil, 24주 6일 뒤까지는 낸다")
    fun guardHorizon() {
        // 175 / 7 = 25 > 24 → nil. 174 / 7 = 24 → 통과
        assertNull(plan(days = 175))
        assertNotNull(plan(days = 174))
    }

    // MARK: - ② 주 구성

    @Test
    @DisplayName("주 구성 — 지난 주(기록 시작 이후) + 이번 주부터 대회 주간까지, 마지막 주가 대회 주간")
    fun weekLayout() {
        val p = assertNotNull(plan(days = 70, zone = kst))
        // 지난 주: 최고령 26.3일 전(07-14 KST) → 07-13 주는 기록 시작 전이라 빠지고
        // 07-20·07-27·08-03 3개. 미래 주: 70 / 7 + 1 = 11개 → 총 14개
        assertEquals(70, p.daysToRace)
        assertEquals(3 + 11, p.weeks.size)
        assertTrue(p.weeks.take(3).all { it.phase == null && !it.isCurrent })
        assertEquals(1, p.weeks.count { it.isCurrent })
        assertTrue(p.weeks[3].isCurrent)

        val last = assertNotNull(p.weeks.lastOrNull())
        assertEquals(TrainingGuide.Phase.raceWeek, last.phase)
        val raceDay = raceDate(inDays = 70)
        assertTrue(last.weekStart <= raceDay)
        assertTrue(raceDay < last.weekStart.plusSeconds(7L * 86_400))
        // 주는 정확히 7일 간격으로 이어진다
        for ((a, b) in p.weeks.zip(p.weeks.drop(1))) {
            assertEquals(7L * 86_400, Duration.between(a.weekStart, b.weekStart).seconds)
        }
    }

    // MARK: - ③ guide()와 일치

    @Test
    @DisplayName("이번 주 처방 — 단계·주간 거리·LSD·퀄리티가 guide()의 이번 주 처방과 같다")
    fun currentWeekMatchesGuide() {
        for (days in listOf(140, 70, 35, 10, 3, 0)) {
            val prescription = assertNotNull(TrainingGuideEngine(now = now, zone = testZone,
                                                                 level = RunnerLevel.intermediate)
                .guide(runs = baseRuns, race = RaceDistance.full, goalSec = null,
                       raceDate = raceDate(inDays = days), batteryTone = null)?.prescription, "days $days")
            val current = assertNotNull(plan(days = days)?.weeks?.firstOrNull { it.isCurrent }, "days $days")
            assertEquals(prescription.phase, current.phase, "days $days")
            assertTrue(approx(current.weeklyKmLow, prescription.weeklyKmLow), "days $days")
            assertTrue(approx(current.weeklyKmHigh, prescription.weeklyKmHigh), "days $days")
            assertTrue(approx(current.lsdKmLow, prescription.lsdKmLow), "days $days")
            assertTrue(approx(current.lsdKmHigh, prescription.lsdKmHigh), "days $days")
            assertEquals(prescription.tempoCount, current.tempoCount, "days $days")
            assertEquals(prescription.intervalCount, current.intervalCount, "days $days")
        }
    }

    // MARK: - ④ 점증

    @Test
    @DisplayName("10% 룰 점증 — 만성 30km에서 주마다 ×1.1, 피크 65km(풀·런잘알)에서 멈춘다")
    fun progressiveBuild() {
        // 140일 = 20주 → 미래 주 k = 0…20. 단계: 남은 주 20−k —
        // 기초 k 0…10, 강화 k 11…14, 피크 k 15…17, 테이퍼 k 18·19, 대회 주간 k 20
        val p = assertNotNull(plan(days = 140))
        val future = p.weeks.dropWhile { !it.isCurrent }
        assertEquals(21, future.size)
        assertEquals(65.0, p.peakWeeklyKm)

        // 주 0: 30 … 33, 주 1: 33 … 36.3, 주 2: 36.3 … 39.93
        assertTrue(approx(future[0].weeklyKmLow, 30.0) && approx(future[0].weeklyKmHigh, 33.0))
        assertTrue(approx(future[1].weeklyKmLow, 33.0) && approx(future[1].weeklyKmHigh, 36.3))
        assertTrue(approx(future[2].weeklyKmLow, 36.3) && approx(future[2].weeklyKmHigh, 39.93))
        // 30 × 1.1^9 = 70.74 ≥ 65 → 주 8: 30 × 1.1^8 = 64.3077 … 65 (상한에 처음 닿음)
        assertTrue(approx(future[8].weeklyKmLow, 30 * 1.1.pow(8.0)))
        assertTrue(approx(future[8].weeklyKmHigh, 65.0))
        // 주 9부터 피크 단계 끝(주 17)까지 65 … 65에서 정지
        for (week in future.subList(9, 18)) {
            assertTrue(approx(week.weeklyKmLow, 65.0) && approx(week.weeklyKmHigh, 65.0))
        }
        // 전 구간 low ≤ high
        assertTrue(future.all { (it.weeklyKmLow ?: 0.0) <= (it.weeklyKmHigh ?: 0.0) })
        // LSD는 하한 × 25% … 상한 × 35% — 주 1: 8.25 … 12.705
        assertTrue(approx(future[1].lsdKmLow, 33 * 0.25) && approx(future[1].lsdKmHigh, 36.3 * 0.35))
    }

    // MARK: - ⑤ 테이퍼

    @Test
    @DisplayName("풀코스 테이퍼 — 2주는 쌓은 볼륨의 60~70%, 대회 주간은 40~50%·LSD 0")
    fun fullTaper() {
        // 위 140일 계획: 쌓은 볼륨(마지막 피크 주 상한) = 65
        // → 테이퍼 39 … 45.5, 대회 주간 26 … 32.5
        val p = assertNotNull(plan(days = 140))
        val future = p.weeks.dropWhile { !it.isCurrent }
        for (week in future.subList(18, 20)) {
            assertEquals(TrainingGuide.Phase.taper, week.phase)
            assertTrue(approx(week.weeklyKmLow, 39.0) && approx(week.weeklyKmHigh, 45.5))
        }
        val raceWeek = future[20]
        assertEquals(TrainingGuide.Phase.raceWeek, raceWeek.phase)
        assertTrue(approx(raceWeek.weeklyKmLow, 26.0) && approx(raceWeek.weeklyKmHigh, 32.5))
        assertTrue(raceWeek.lsdKmLow == 0.0 && raceWeek.lsdKmHigh == 0.0)
        assertTrue(raceWeek.tempoCount == 0 && raceWeek.intervalCount == 0)

        // 70일 계획은 피크에 못 닿는다 — 마지막 피크 주(k 7) 상한 30 × 1.1^8 = 64.3077이 기준
        val short = assertNotNull(plan(days = 70))
        val taper = assertNotNull(short.weeks.firstOrNull { it.phase == TrainingGuide.Phase.taper })
        assertTrue(approx(taper.weeklyKmLow, 30 * 1.1.pow(8.0) * 0.6))
        assertTrue(approx(taper.weeklyKmHigh, 30 * 1.1.pow(8.0) * 0.7))
    }


    @Test
    @DisplayName("대회 주간 단일 — 대회가 오늘보다 이른 요일(일요일에 본 토요일 대회)이어도 대회 주간은 마지막 주 하나뿐")
    fun singleRaceWeek() {
        // now = KST 2026-08-16(일) 18:00, 대회 = 2026-08-29(토) → daysToRace 13.
        // 달력 주: 이번 주 8/10~16(k0), 8/17~23(k1), 8/24~30(k2, 대회 주간).
        // k1은 굴러가는 식으로 13−7 = 6일이라 대회 주간이 되지만, 마지막 주가 아니므로 7일로 받쳐 테이퍼여야 한다
        val sunday = iso("2026-08-16T09:00:00Z")
        val runs = (0 until 12).map { i ->
            RunSummary(id = UUID.randomUUID().toString().uppercase(),
                       start = instantSince1970(sunday.timeIntervalSince1970 - (i.toDouble() * 2.3 + 1) * 86_400),
                       durationSec = 3_600.0, distanceMeters = 10_000.0, avgHeartRate = 150.0)
        }
        val p = assertNotNull(TrainingPlanEngine.plan(runs = runs, race = RaceDistance.full,
                                                      level = RunnerLevel.intermediate,
                                                      raceDate = sunday.plusSeconds(13L * 86_400),
                                                      now = sunday, zone = testZone))
        val future = p.weeks.dropWhile { !it.isCurrent }
        assertEquals(3, future.size)
        assertEquals(listOf(TrainingGuide.Phase.taper, TrainingGuide.Phase.taper, TrainingGuide.Phase.raceWeek),
                     future.map { it.phase })
        assertEquals(1, future.count { it.phase == TrainingGuide.Phase.raceWeek })
    }

    // MARK: - ⑥ 실제 거리

    @Test
    @DisplayName("실제 거리 — 지난 주는 합계, 이번 주는 지금까지 합계, 미래 주는 nil, 기록 전 주는 빠진다")
    fun actualKm() {
        // KST 주 시작: 이번 주 08-10(0.75일 전), −1주 08-03(7.75일 전),
        // −2주 07-27(14.75일 전), −3주 07-20(21.75일 전), −4주 07-13(28.75일 전)
        // 최고령 22일 전 → −4주는 기록 시작 전이라 목록에서 빠진다 (그 주 10km는 표시 안 함)
        val runs = listOf(run(daysAgo = 22.0, km = 10.0),                                    // −4주
                          run(daysAgo = 20.0, km = 8.0), run(daysAgo = 16.0, km = 6.0),      // −3주 = 14
                          run(daysAgo = 10.0, km = 12.0),                                    // −2주 = 12
                          run(daysAgo = 3.0, km = 9.0), run(daysAgo = 1.0, km = 5.0),        // −1주 = 14
                          run(daysAgo = 0.5, km = 4.0))                                      // 이번 주 = 4
        // 28일 합 54km → chronic 13.5 ≥ 3 → 가드 통과
        val p = assertNotNull(plan(runs = runs, days = 35, zone = kst))
        val past = p.weeks.filter { it.phase == null }
        assertEquals(listOf<Double?>(14.0, 12.0, 14.0), past.map { it.actualKm })
        assertTrue(past.all { it.weeklyKmLow == null && it.lsdKmHigh == null && it.tempoCount == 0 })
        val current = assertNotNull(p.weeks.firstOrNull { it.isCurrent })
        assertEquals(4.0, current.actualKm)
        assertTrue(p.weeks.dropWhile { !it.isCurrent }.drop(1).all { it.actualKm == null })
    }

    // MARK: - ⑦ 라벨

    @Test
    @DisplayName("주 라벨 — Format.weekLabel(weekStart:)과 같다")
    fun labels() {
        val p = assertNotNull(plan(days = 70))
        assertTrue(p.weeks.all { it.label == Format.weekLabel(weekStart = it.weekStart, zone = testZone) })
    }
}
