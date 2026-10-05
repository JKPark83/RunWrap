package com.jkpark.runwrap.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 훈련 부하 추세 검증 (이슈 #177) — TRIMP 산식·미노출 가드·CTL/ATL 지수 가중 평균·
/// TSB 전날 잔고 규칙·추세 점·폼 구간 경계.
@DisplayName("훈련 부하 추세")
class TrainingLoadEngineTests {
    private val now = iso("2026-08-13T09:00:00Z")

    /// 달력 일 경계를 고정하기 위해 UTC 그레고리력을 주입한다
    private val utc: ZoneId = ZoneId.of("UTC")

    /// HRmax 190(직접 입력) · 안정 심박 50 — 평균 150bpm → HRr = 100 / 140
    private val profile = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.manual,
                                           restingHR = 50.0, zoneMethod = HeartRateZoneMethod.percentMax)

    /// daysAgo일 전 08:00Z(now보다 1시간 이른 시각) 러닝 — 같은 UTC 달력 일에 떨어진다
    private fun run(daysAgo: Double, minutes: Double = 60.0, hr: Double? = 150.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400 - 3_600),
                   durationSec = minutes * 60, distanceMeters = 10_000.0, avgHeartRate = hr)

    /// 평균 150bpm으로 TRIMP가 정확히 `trimp`가 되는 러닝 시간(분) —
    /// 분당 TRIMP = HRr × 0.64 × e^(1.92 × HRr), HRr = 100/140 → 약 1.8016 (100이면 약 55.5분)
    private fun minutes(forTrimp: Double): Double {
        val hrr = 100.0 / 140
        return forTrimp / (hrr * 0.64 * exp(1.92 * hrr))
    }

    private fun compute(runs: List<RunSummary>): TrainingLoad? =
        TrainingLoadEngine.compute(runs = runs, profile = profile, now = now, zone = utc)

    /// `utc.startOfDay(for:)`
    private fun startOfDay(date: Instant): Instant = date.atZone(utc).toLocalDate().atStartOfDay(utc).toInstant()

    // MARK: TRIMP 산식

    @Test
    @DisplayName("TRIMP — 60분·평균 150bpm(HRmax 190·안정 50)이면 약 108.1")
    fun trimpFormula() {
        // HRr = 100/140 = 0.7143 → 60 × 0.7143 × 0.64 × e^(1.3714 = 3.9407) ≈ 108.10
        val trimp = assertNotNull(TrainingLoadEngine.trimp(run = run(daysAgo = 1.0), profile = profile))
        assertTrue(abs(trimp - 108.1) < 0.5)
    }

    @Test
    @DisplayName("TRIMP — 평균 심박이 없거나 HRmax가 190 폴백이면 nil")
    fun trimpNilWithoutEvidence() {
        assertNull(TrainingLoadEngine.trimp(run = run(daysAgo = 1.0, hr = null), profile = profile))
        val fallback = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.fallback,
                                        restingHR = 50.0, zoneMethod = HeartRateZoneMethod.percentMax)
        assertNull(TrainingLoadEngine.trimp(run = run(daysAgo = 1.0), profile = fallback))
        assertNull(TrainingLoadEngine.trimp(run = run(daysAgo = 1.0, minutes = 0.0), profile = profile))
    }

    @Test
    @DisplayName("TRIMP — 평균 심박이 안정 심박 이하면 HRr 0으로 잘려 0")
    fun trimpClampsAtRest() {
        assertEquals(0.0, TrainingLoadEngine.trimp(run = run(daysAgo = 1.0, hr = 45.0), profile = profile))
    }

    @Test
    @DisplayName("TRIMP — 안정 심박이 없으면 60bpm으로 폴백")
    fun trimpRestingFallback() {
        val noRest = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.manual,
                                      restingHR = null, zoneMethod = HeartRateZoneMethod.percentMax)
        // HRr = 90/130 = 0.6923 → 60 × 0.6923 × 0.64 × e^(1.3292 = 3.7778) ≈ 100.44
        val trimp = assertNotNull(TrainingLoadEngine.trimp(run = run(daysAgo = 1.0), profile = noRest))
        assertTrue(abs(trimp - 100.44) < 0.5)
    }

    // MARK: 미노출 가드

    @Test
    @DisplayName("이력 가드 — 가장 오래된 심박 세션이 41일 전이면 nil, 43일 전이면 값")
    fun historyGuard() {
        // 41·36·…·1일 전 9회 — 42일 창 표본은 충분하지만 이력이 42일에 못 미친다
        val recent = generateSequence(41.0) { it - 5 }.takeWhile { it >= 1 }.map { run(daysAgo = it) }.toList()
        assertNull(compute(recent))

        val result = assertNotNull(compute(recent + listOf(run(daysAgo = 43.0))))
        // 43일 전 세션은 42일 창 밖이라 표본에는 안 센다
        assertEquals(9, result.sessionCount)
    }

    @Test
    @DisplayName("표본 가드 — 최근 42일 세션 7회면 nil, 8회면 값")
    fun sessionCountGuard() {
        val old = run(daysAgo = 50.0)  // 이력 가드용 — 창 밖
        val seven = (1..7).map { run(daysAgo = it.toDouble()) }
        assertNull(compute(listOf(old) + seven))

        val result = assertNotNull(compute(listOf(old) + seven + listOf(run(daysAgo = 8.0))))
        assertEquals(8, result.sessionCount)
    }

    @Test
    @DisplayName("표본 가드 — 평균 심박 없는 세션은 세지 않는다")
    fun sessionsWithoutHeartRateDoNotCount() {
        val old = run(daysAgo = 50.0)
        val seven = (1..7).map { run(daysAgo = it.toDouble()) }
        assertNull(compute(listOf(old) + seven + listOf(run(daysAgo = 8.0, hr = null))))
    }

    // MARK: 지수 가중 평균

    @Test
    @DisplayName("EWMA — 매일 TRIMP 100을 60일 연속이면 CTL ≈ 76.4, ATL ≈ 100.0")
    fun ewmaSteadyLoad() {
        val perDay = minutes(forTrimp = 100.0)
        val runs = (0 until 60).map { run(daysAgo = it.toDouble(), minutes = perDay) }
        val result = assertNotNull(compute(runs))
        // 초기값 0에서 매일 t=100 → n일째 CTL = 100 × (1 − (41/42)^n)
        // 60일째: 100 × (1 − 0.2355) ≈ 76.45 · ATL: 100 × (1 − (6/7)^60) ≈ 99.99
        assertTrue(abs(result.ctl - 100 * (1 - (41.0 / 42.0).pow(60.0))) < 0.5)
        assertTrue(abs(result.ctl - 76.4) < 0.5)
        assertTrue(abs(result.atl - 99.99) < 0.5)
    }

    @Test
    @DisplayName("EWMA — 오늘 세션이 없으면 오늘은 t=0으로 감쇠한다")
    fun ewmaDecaysOnRestDay() {
        val perDay = minutes(forTrimp = 100.0)
        val runs = (1..60).map { run(daysAgo = it.toDouble(), minutes = perDay) }
        val result = assertNotNull(compute(runs))
        // 어제까지 60일 누적 뒤 오늘 t=0: CTL × 41/42, ATL × 6/7
        val ctlYesterday = 100 * (1 - (41.0 / 42.0).pow(60.0))
        val atlYesterday = 100 * (1 - (6.0 / 7.0).pow(60.0))
        assertTrue(abs(result.ctl - ctlYesterday * (41.0 / 42)) < 0.01)
        assertTrue(abs(result.atl - atlYesterday * (6.0 / 7)) < 0.01)
    }

    // MARK: TSB · 추세 점

    @Test
    @DisplayName("TSB — 전날 잔고를 쓴다: 오늘 큰 세션은 오늘 CTL·ATL만 올리고 TSB는 그대로")
    fun tsbUsesPreviousDay() {
        val perDay = minutes(forTrimp = 100.0)
        val base = (1..60).map { run(daysAgo = it.toDouble(), minutes = perDay) }
        val rest = assertNotNull(compute(base))
        val hard = assertNotNull(compute(base + listOf(run(daysAgo = 0.0, minutes = 180.0))))

        assertTrue(hard.ctl > rest.ctl)
        assertTrue(hard.atl > rest.atl)
        assertTrue(abs(hard.tsb - rest.tsb) < 1e-9)
        // 오늘 TSB = 어제 점의 CTL − ATL
        val yesterday = hard.points[hard.points.size - 2]
        assertTrue(abs(hard.tsb - (yesterday.ctl - yesterday.atl)) < 1e-9)
    }

    @Test
    @DisplayName("추세 점 — 29개, 오래된 → 최신, 하루 간격 자정, 각 점 TSB는 전날 잔고")
    fun trendPoints() {
        val perDay = minutes(forTrimp = 100.0)
        val result = assertNotNull(compute((0 until 60).map { run(daysAgo = it.toDouble(), minutes = perDay) }))

        assertEquals(29, result.points.size)
        assertEquals(startOfDay(now), result.points.lastOrNull()?.day)
        assertEquals(startOfDay(now).atZone(utc).minusDays(28).toInstant(), result.points.firstOrNull()?.day)
        for ((prev, point) in result.points.zip(result.points.drop(1))) {
            assertEquals(startOfDay(point.day), point.day)
            assertEquals(86_400L, Duration.between(prev.day, point.day).seconds)
            assertTrue(abs(point.tsb - (prev.ctl - prev.atl)) < 1e-9)
        }
        assertEquals(result.points.lastOrNull()?.ctl, result.ctl)
        assertEquals(result.points.lastOrNull()?.tsb, result.tsb)
    }

    // MARK: 폼 구간 경계

    @Test
    @DisplayName("폼 구간 경계 — −30 미만 과부하 · −10 미만 체력 쌓는 중 · +5 이하 유지 · +25 이하 가벼움 · 그 위 훈련 부족")
    fun bandBoundaries() {
        val cases: List<Triple<Double, TrainingLoad.Band, RRTone>> = listOf(
            Triple(-31.0, TrainingLoad.Band.overload, RRTone.overload),
            Triple(-30.0, TrainingLoad.Band.productive, RRTone.improving),   // −30 정확히는 과부하가 아니다
            Triple(-11.0, TrainingLoad.Band.productive, RRTone.improving),
            Triple(-10.0, TrainingLoad.Band.maintain, RRTone.steady),        // −10 정확히는 유지
            Triple(5.0, TrainingLoad.Band.maintain, RRTone.steady),          // +5 정확히는 유지
            Triple(6.0, TrainingLoad.Band.fresh, RRTone.steady),
            Triple(25.0, TrainingLoad.Band.fresh, RRTone.steady),            // +25 정확히는 가벼움
            Triple(26.0, TrainingLoad.Band.detraining, RRTone.caution),
        )
        for ((tsb, band, tone) in cases) {
            val actual = TrainingLoadEngine.band(tsb = tsb)
            assertEquals(band, actual, "TSB $tsb")
            assertEquals(tone, TrainingLoadEngine.tone(actual), "TSB $tsb")
        }
    }
}
