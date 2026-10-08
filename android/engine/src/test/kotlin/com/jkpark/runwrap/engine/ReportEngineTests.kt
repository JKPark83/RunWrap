package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 리포트 엔진 검증 — 고정 시각 + 합성 데이터 (유용성 자가 검증은 실기기에서 별도 진행)
class ReportEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")
    private val engine: ReportEngine get() = ReportEngine(now = now)

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double? = 150.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = hr)

    private fun insight(kind: Insight.Kind, runs: List<RunSummary>): Insight? =
        engine.insights(runs).firstOrNull { it.kind == kind }

    // MARK: 주간 거리 증가율

    @Test
    @DisplayName("주간 증가율 10% 초과는 경고 — 기획서 예시 +23%")
    fun weeklyIncreaseOverTenPercentWarns() {
        val runs = listOf(run(daysAgo = 1.0, km = 12.3), run(daysAgo = 3.0, km = 12.3),
                          run(daysAgo = 8.0, km = 10.0), run(daysAgo = 10.0, km = 10.0))
        val result = insight(Insight.Kind.weeklyDistanceChange, runs)
        assertEquals(Insight.Tone.warning, result?.tone)
        assertEquals(true, result?.headline?.contains("+23%"))
    }

    @Test
    @DisplayName("증가 폭이 10% 이내면 안정 판정")
    fun weeklyIncreaseWithinTenPercentIsPositive() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.5), run(daysAgo = 8.0, km = 10.0))
        assertEquals(Insight.Tone.positive, insight(Insight.Kind.weeklyDistanceChange, runs)?.tone)
    }

    @Test
    @DisplayName("기준 주 거리가 3km 미만이면 증가율을 내지 않는다")
    fun weeklyChangeNeedsBaselineWeek() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 2.0))
        assertNull(insight(Insight.Kind.weeklyDistanceChange, runs))
    }

    // MARK: ACWR

    @Test
    @DisplayName("급성 부하가 만성의 1.5배를 넘으면 부상 위험 경고")
    fun acwrOverOnePointFiveWarns() {
        // 3주간 주 10km 유지 후 이번 주 20km — acute 20 ÷ chronic 12.5 = 1.6
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0),
                          run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 24.0, km = 10.0),
                          // 이슈 #49: 가드 28일 — 최고령 기록을 창 밖(30일)에 둬 chronic 50/4=12.5 유지
                          run(daysAgo = 30.0, km = 10.0))
        val result = insight(Insight.Kind.acwr, runs)
        assertEquals(Insight.Tone.warning, result?.tone)
        assertEquals(true, result?.headline?.contains("1.6배"))
    }

    @Test
    @DisplayName("부하가 4주 평균과 비슷하면 적정 판정")
    fun acwrSteadyLoadIsPositive() {
        // 이슈 #49: 가드 28일 — through 26→29로 최고령 기록(29일)을 창 밖에 둔다.
        // 창 안은 그대로 2…26일 9회 → chronic 45/4=11.25, acute(2·5일) 10 → 0.89 적정
        // (iOS stride(from: 2.0, through: 29, by: 3) = 2, 5, …, 29의 10개)
        val runs = (0..9).map { run(daysAgo = 2.0 + it * 3, km = 5.0) }
        val result = insight(Insight.Kind.acwr, runs)
        assertEquals(Insight.Tone.positive, result?.tone)
        assertEquals(true, result?.headline?.contains("적정"))
    }

    @Test
    @DisplayName("기록이 3주 미만이면 ACWR을 내지 않는다")
    fun acwrNeedsThreeWeeksOfHistory() {
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 10.0), run(daysAgo = 14.0, km = 10.0))
        assertNull(insight(Insight.Kind.acwr, runs))
    }

    @Test
    @DisplayName("기록이 21~27일치면 ACWR을 내지 않는다 — 만성 분모 과소 방지 (이슈 #49)")
    fun acwrNeedsFourWeeksOfHistory() {
        // iOS arguments: [21.0, 24.0, 27.9] — 파라미터 테스트를 루프로 옮긴다
        for (oldestDaysAgo in listOf(21.0, 24.0, 27.9)) {
            // 옛 산식(21일 가드)이면 21일치 이력에서 50/4=12.5 → 20/12.5=1.6(부상 위험)이지만,
            // 실제 3주 주평균은 50/3≈16.7 → 1.2(적정)다. 분모가 부풀려지므로 내지 않는다.
            val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                              run(daysAgo = 10.0, km = 10.0), run(daysAgo = 17.0, km = 10.0),
                              run(daysAgo = oldestDaysAgo, km = 10.0))
            assertNull(insight(Insight.Kind.acwr, runs), "oldestDaysAgo=$oldestDaysAgo")
        }
    }

    @Test
    @DisplayName("기록이 정확히 28일이면 ACWR을 낸다 — 가드 경계 (이슈 #49)")
    fun acwrAtExactlyFourWeeks() {
        // 최고령 기록이 now-28일 정각 → 가드(<=) 통과, 창 시작(>=)에도 포함.
        // acute 2·4일 = 20, chronic 50/4 = 12.5 → 1.6
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0), run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 28.0, km = 10.0))
        val result = insight(Insight.Kind.acwr, runs)
        assertEquals(Insight.Tone.warning, result?.tone)
        assertEquals(true, result?.headline?.contains("1.6배"))
    }

    // MARK: 심박 효율

    @Test
    @DisplayName("같은 페이스에서 심박이 내려가면 체력 상승 판정")
    fun efficiencyRiseIsPositive() {
        // 직전 2주 150bpm → 최근 2주 140bpm, 페이스 동일 = EF +7%
        val runs = listOf(run(daysAgo = 1.0, km = 5.0, hr = 140.0), run(daysAgo = 3.0, km = 5.0, hr = 140.0),
                          run(daysAgo = 5.0, km = 5.0, hr = 140.0),
                          run(daysAgo = 15.0, km = 5.0, hr = 150.0), run(daysAgo = 17.0, km = 5.0, hr = 150.0),
                          run(daysAgo = 19.0, km = 5.0, hr = 150.0))
        assertEquals(Insight.Tone.positive, insight(Insight.Kind.heartRateEfficiency, runs)?.tone)
    }

    @Test
    @DisplayName("각 2주 창에 표본 3개 미만이면 심박 효율을 내지 않는다")
    fun efficiencyNeedsThreeRunsPerWindow() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0), run(daysAgo = 3.0, km = 5.0), run(daysAgo = 5.0, km = 5.0),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 17.0, km = 5.0))
        assertNull(insight(Insight.Kind.heartRateEfficiency, runs))
    }

    @Test
    @DisplayName("심박 없는 러닝은 효율 표본에서 제외된다")
    fun efficiencySkipsRunsWithoutHeartRate() {
        val runs = listOf(run(daysAgo = 1.0, km = 5.0, hr = null), run(daysAgo = 3.0, km = 5.0, hr = null),
                          run(daysAgo = 5.0, km = 5.0, hr = null),
                          run(daysAgo = 15.0, km = 5.0), run(daysAgo = 17.0, km = 5.0), run(daysAgo = 19.0, km = 5.0))
        assertNull(insight(Insight.Kind.heartRateEfficiency, runs))
    }

    // MARK: 공통

    @Test
    @DisplayName("데이터가 부족하면 어떤 지표도 내지 않는다")
    fun insufficientDataYieldsNothing() {
        assertTrue(engine.insights(listOf(run(daysAgo = 1.0, km = 5.0))).isEmpty())
        assertTrue(engine.insights(emptyList()).isEmpty())
    }
}
