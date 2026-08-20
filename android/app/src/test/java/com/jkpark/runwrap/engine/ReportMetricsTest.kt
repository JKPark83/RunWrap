package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.time.YearMonth
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 주간 리포트 지표 레이어 검증 — iOS `ReportMetricsTests` 이식.
/// iOS 16개 중 15개 — weekLabelFormat은 M1 `FormatTest`에 이미 있다.
class ReportMetricsTest {
    // 2026-08-10(월) 18:00 KST
    private val now = Instant.parse("2026-08-10T09:00:00Z")
    private val engine get() = ReportEngine(now = now)

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double? = 150.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1000,
        avgHeartRate = hr,
    )

    @Test
    fun `거리 카드 - 수치와 과부하 톤 +23퍼센트, 상한 22km`() {
        val runs = listOf(run(daysAgo = 1.0, km = 12.3), run(daysAgo = 3.0, km = 12.3),
                          run(daysAgo = 8.0, km = 10.0), run(daysAgo = 10.0, km = 10.0))
        val card = engine.weeklyReport(runs).distance!!
        assertEquals(RRTone.OVERLOAD, card.tone)
        assertEquals(24.6, card.recent7Km, 0.01)
        assertEquals(20.0, card.previous7Km, 0.01)
        assertEquals(22.0, card.capKm, 0.01)
        assertEquals(23.0, card.changePct, 0.01)
        assertEquals(2.6, card.overKm, 0.01)
        assertEquals(6, card.weeks.size)
        assertTrue(card.weeks.last().isCurrent)
    }

    @Test
    fun `ACWR 카드 - 급성 20 나누기 만성 12_5 = 1_6, 과부하 톤`() {
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0),
                          run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 24.0, km = 10.0))
        val card = engine.weeklyReport(runs).acwr!!
        assertEquals(20.0, card.acute, 0.01)
        assertEquals(12.5, card.chronic, 0.01)
        assertEquals(1.6, card.ratio, 0.01)
        assertEquals(RRTone.OVERLOAD, card.tone)
    }

    @Test
    fun `EF 카드 - 페이스 환산 델타가 양수(빨라짐)이고 개선 톤`() {
        // 최근 2주는 같은 심박에 더 빠른 페이스 → EF 상승
        val runs = listOf(run(daysAgo = 1.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 4.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 8.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 15.0, km = 8.0, minPerKm = 6.0, hr = 150.0),
                          run(daysAgo = 18.0, km = 8.0, minPerKm = 6.0, hr = 150.0),
                          run(daysAgo = 22.0, km = 8.0, minPerKm = 6.0, hr = 150.0))
        val card = engine.weeklyReport(runs).efficiency!!
        assertEquals(RRTone.IMPROVING, card.tone)
        assertTrue(card.changePct > 3)
        // 150bpm 기준 6′00″ → 5′30″: 델타 약 +30초
        assertEquals(30.0, card.paceDeltaSec, 1.5)
        assertTrue(card.points.size >= 2)
        assertEquals(card.points.size, card.pointLabels.size)  // 콜아웃 라벨 병행 배열
        assertEquals("이번 주", card.pointLabels.last())
    }

    @Test
    fun `거리 카드 차트 - 가장 오래된 기록의 주까지 전체 주를 그린다 (스크롤용)`() {
        // now = 8.10(월). 56일 전 = 6.15(월) 주 → 6.15…8.10 주가 9개
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 10.0),
                          run(daysAgo = 56.0, km = 5.0))
        val card = engine.weeklyReport(runs).distance!!
        assertEquals(9, card.weeks.size)
        assertEquals("6월 3째주", card.weeks.first().label)   // 6.15 주 — 목요일 6.18
        assertEquals("8월 2째주", card.weeks.last().label)    // 이번 주 — 목요일 8.13
        assertTrue(card.weeks.last().isCurrent)
        assertEquals(5.0, card.weeks.first().km, 0.01)        // 가장 오래된 주의 합계
    }

    @Test
    fun `표본 부족 가드 - 기준 주 3km 미만·3주 미만 기록이면 카드가 없다`() {
        val report = engine.weeklyReport(listOf(run(daysAgo = 1.0, km = 10.0),
                                                run(daysAgo = 8.0, km = 2.0)))
        assertNull(report.distance)   // 기준 주 3km 미만
        assertNull(report.acwr)       // 기록 3주 미만
        assertNull(report.efficiency) // 표본 3개 미만
        assertTrue(report.isEmpty)
    }

    @Test
    fun `월간 통계 - 8월 집계와 지난달 같은 날짜까지 비교`() {
        val august = listOf(run(daysAgo = 1.0, km = 10.0, minPerKm = 6.0, hr = 150.0),   // 8.9
                            run(daysAgo = 5.0, km = 10.0, minPerKm = 6.0, hr = 148.0))   // 8.5
        val july = listOf(run(daysAgo = 33.0, km = 8.0, minPerKm = 6.5, hr = 152.0),     // 7.8 — 비교 구간 안
                          run(daysAgo = 38.0, km = 8.0, minPerKm = 6.5, hr = 152.0))     // 7.3 — 비교 구간 안
        val stats = MonthlyStats.compute(august + july, YearMonth.of(2026, 8), now)
        assertEquals(2, stats.count)
        assertEquals(20.0, stats.totalKm, 0.01)
        assertEquals(10, stats.comparisonDays)                        // 8.10 기준 → 7.1–7.10
        assertNotNull(stats.deltaPct); assertEquals(25.0, stats.deltaPct!!, 0.01)  // 16→20km
        assertNotNull(stats.avgPaceSec); assertEquals(360.0, stats.avgPaceSec!!, 0.01)
        assertNotNull(stats.paceDeltaSec); assertTrue(stats.paceDeltaSec!! < 0)    // 빨라짐
        assertTrue(stats.runs.first().start > stats.runs.last().start)  // 최신순 정렬
        assertEquals("지난달 1–10일 대비", stats.deltaCaption)
    }

    @Test
    fun `월간 통계 - 진행 중인 달은 지난달 후반 기록을 비교에서 뺀다`() {
        // now = 8.10. 7.26 롱런은 '같은 날짜까지' 밖이라 비교 대상이 아니다.
        val runs = listOf(run(daysAgo = 1.0, km = 10.0),               // 8.9
                          run(daysAgo = 33.0, km = 8.0),               // 7.8  — 비교 구간 안
                          run(daysAgo = 15.0, km = 40.0))              // 7.26 — 비교 구간 밖
        val stats = MonthlyStats.compute(runs, YearMonth.of(2026, 8), now)
        // 8km와만 비교 → +25%. 48km 전체와 비교하면 −79%로 나왔다.
        assertNotNull(stats.deltaPct); assertEquals(25.0, stats.deltaPct!!, 0.01)
    }

    @Test
    fun `월간 통계 - 이미 끝난 달은 지난달 전체와 비교한다`() {
        // 7월을 볼 때(now = 8.10)는 7월도 6월도 완결된 달 — 잘라 볼 이유가 없다
        val runs = listOf(run(daysAgo = 15.0, km = 20.0),              // 7.26
                          run(daysAgo = 45.0, km = 8.0),               // 6.26
                          run(daysAgo = 55.0, km = 8.0))               // 6.16
        val stats = MonthlyStats.compute(runs, YearMonth.of(2026, 7), now)
        assertNull(stats.comparisonDays)
        assertEquals("지난달 대비", stats.deltaCaption)
        assertNotNull(stats.deltaPct); assertEquals(25.0, stats.deltaPct!!, 0.01)  // 16→20km
    }

    // MARK: - streak · 추이 지표
    // now = 8.10(월) 18:00 KST — 이번 ISO 주는 [8.10, 8.17).
    // daysAgo 0.1~0.3 = 이번 주, 1~6 = 지난주(8.3–8.9), 9 = 2주 전(8.1), 25 = 4주 전 주(7.16).

    @Test
    fun `streak - 이번 주 포함 3주 연속이면 3`() {
        val runs = listOf(run(daysAgo = 0.2, km = 5.0),   // 8.10 — 이번 주
                          run(daysAgo = 3.0, km = 5.0),   // 8.7  — 지난주
                          run(daysAgo = 9.0, km = 5.0))   // 8.1  — 2주 전
        assertEquals(3, streakWeeks(runs, now))
    }

    @Test
    fun `streak - 지난주가 비면 이번 주만 세어 1`() {
        val runs = listOf(run(daysAgo = 0.2, km = 5.0),   // 8.10 — 이번 주
                          run(daysAgo = 9.0, km = 5.0))   // 8.1  — 2주 전 (지난주 단절)
        assertEquals(1, streakWeeks(runs, now))
    }

    @Test
    fun `streak - 진행 중인 이번 주에 무기록이어도 지난 연속은 유지된다`() {
        // 이번 주(월요일)에 아직 안 달렸다 — 지난주·2주 전 연속 2가 0으로 초기화되면 안 된다
        val runs = listOf(run(daysAgo = 3.0, km = 5.0),   // 8.7 — 지난주
                          run(daysAgo = 9.0, km = 5.0))   // 8.1 — 2주 전
        assertEquals(2, streakWeeks(runs, now))
    }

    @Test
    fun `streak - 기록이 없으면 0`() {
        assertEquals(0, streakWeeks(emptyList(), now))
    }

    @Test
    fun `VO2max 추이 - 주 평균 45_2, 4주 전 44_0 대비 +1_2 개선 톤`() {
        // 이번 주 (45.0 + 45.4) / 2 = 45.2, 4주 전 주(7.16) 44.0 → 델타 +1.2 ≥ +1.0 → improving
        val samples = listOf(
            DatedSample(now.minusMillis((0.1 * 86_400_000).roundToLong()), 45.0),
            DatedSample(now.minusMillis((0.3 * 86_400_000).roundToLong()), 45.4),
            DatedSample(now.minusMillis((25.0 * 86_400_000).roundToLong()), 44.0),
        )
        val trend = vo2MaxTrend(samples, now)!!
        assertEquals(45.2, trend.current, 0.01)
        assertNotNull(trend.delta); assertEquals(1.2, trend.delta!!, 0.01)
        assertEquals(4, trend.spanWeeks)
        assertEquals(RRTone.IMPROVING, trend.tone)
        assertEquals(2, trend.points.size)
        assertEquals(44.0, trend.points[0], 0.01)  // 오래된 → 최신 순서
        assertEquals(listOf("7월 3째주", "8월 2째주"), trend.pointLabels)
    }

    @Test
    fun `VO2max 유지 판정 - 1_0 미만 변화 +0_5는 추정 오차 범위로 보고 steady`() {
        // 이번 주 (44.4 + 44.6) / 2 = 44.5, 4주 전 44.0 → 델타 +0.5 < +1.0 → steady
        val samples = listOf(
            DatedSample(now.minusMillis((0.1 * 86_400_000).roundToLong()), 44.4),
            DatedSample(now.minusMillis((0.3 * 86_400_000).roundToLong()), 44.6),
            DatedSample(now.minusMillis((25.0 * 86_400_000).roundToLong()), 44.0),
        )
        val trend = vo2MaxTrend(samples, now)!!
        assertNotNull(trend.delta); assertEquals(0.5, trend.delta!!, 0.01)
        assertEquals(RRTone.STEADY, trend.tone)
    }

    @Test
    fun `VO2max 가드 - 최근 12주 추정 기록 3회 미만이면 null`() {
        val two = listOf(
            DatedSample(now.minusMillis((0.1 * 86_400_000).roundToLong()), 45.0),
            DatedSample(now.minusMillis((25.0 * 86_400_000).roundToLong()), 44.0),
        )
        assertNull(vo2MaxTrend(two, now))

        // 3개째가 12주(84일) 밖이면 표본 수에 들지 않는다 → 여전히 null
        val outOfWindow = two + DatedSample(now.minusMillis((90.0 * 86_400_000).roundToLong()), 42.0)
        assertNull(vo2MaxTrend(outOfWindow, now))
    }
}
