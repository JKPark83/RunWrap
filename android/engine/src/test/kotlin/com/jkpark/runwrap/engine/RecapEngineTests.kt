package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 월간·연간 결산 리캡 검증 (이슈 #167) — 표본 가드·총량·하이라이트·PR 필터·런린이 거리 숨김·
/// 강도 배분 가드·마무리 문장 분기·홈 카드 노출 날짜 경계. 달력은 Asia/Seoul 고정.
@DisplayName("월간·연간 결산 리캡")
class RecapEngineTests {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    /// 9월 3일 — 8월 결산을 보는 시점 (8월은 끝난 달)
    private val now = iso("2026-09-03T09:00:00+09:00")

    /// HRmax 200 %HRmax — 130→0.65(Z2) · 150→0.75(Z3)
    private val profile = HeartRateProfile(hrMax = 200.0, hrMaxSource = HeartRateProfile.Source.manual,
                                           restingHR = null, zoneMethod = HeartRateZoneMethod.percentMax)

    private fun date(text: String): Instant = iso(text)

    private val august: RecapPeriod get() = RecapPeriod.month(date("2026-08-01T00:00:00+09:00"))

    /// "2026-08-03" 07:00 KST 러닝 — 시간 = km × 분/km × 60
    private fun run(day: String, km: Double, minPerKm: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(), start = date("${day}T07:00:00+09:00"),
                   durationSec = km * minPerKm * 60, distanceMeters = km * 1_000, avgHeartRate = 145.0)

    /// 8월 4회 — 총 23km · 8,160초
    /// 8/3 5km 6:00(1,800초) · 8/10 12km 6:30(4,680초) · 8/20 2km 4:00(480초) · 8/25 4km 5:00(1,200초)
    private val augustRuns: List<RunSummary>
        get() = listOf(run("2026-08-03", km = 5.0, minPerKm = 6.0),
                       run("2026-08-10", km = 12.0, minPerKm = 6.5),
                       run("2026-08-20", km = 2.0, minPerKm = 4.0),
                       run("2026-08-25", km = 4.0, minPerKm = 5.0))

    private fun compute(period: RecapPeriod, runs: List<RunSummary>,
                        efforts: BestEffortTable = emptyMap(),
                        histograms: Map<String, ZoneHistogram> = emptyMap(),
                        level: RunnerLevel = RunnerLevel.intermediate,
                        now: Instant? = null): Recap? =
        RecapEngine.compute(period = period, runs = runs, efforts = efforts, histograms = histograms,
                            profile = profile, level = level, now = now ?: this.now, zone = zone)

    // MARK: (a) 표본 가드

    @Test
    @DisplayName("표본 가드 — 그 달 러닝 2회면 nil, 3회면 값 (다음 달 1일 0시 세션은 세지 않는다)")
    fun minimumRuns() {
        val two = listOf(run("2026-08-03", km = 5.0, minPerKm = 6.0), run("2026-08-10", km = 5.0, minPerKm = 6.0),
                         // 9월 1일 0시 정각 — 8월 구간 [8/1, 9/1)의 끝이라 빠진다
                         RunSummary(id = UUID.randomUUID().toString().uppercase(),
                                    start = date("2026-09-01T00:00:00+09:00"),
                                    durationSec = 1_800.0, distanceMeters = 5_000.0, avgHeartRate = 145.0),
                         run("2026-07-20", km = 5.0, minPerKm = 6.0))
        assertNull(compute(august, runs = two))
        assertFalse(RecapEngine.hasEnoughRuns(august, runs = two, zone = zone))

        val three = two + run("2026-08-31", km = 5.0, minPerKm = 6.0)
        val recap = assertNotNull(compute(august, runs = three))
        assertEquals(3, recap.totals.count)
        assertTrue(RecapEngine.hasEnoughRuns(august, runs = three, zone = zone))
        assertEquals("2026년 8월 결산", recap.title)
        assertEquals("2026년 8월", recap.periodLabel)
    }

    // MARK: (b) 총량 · 하이라이트

    @Test
    @DisplayName("총량·최장 런·가장 빠른 세션 — 3km 미만은 가장 빠른 세션 후보에서 뺀다")
    fun totalsAndHighlights() {
        val runs = augustRuns
        val recap = assertNotNull(compute(august, runs = runs))
        assertTrue(abs(recap.totals.distanceKm - 23) < 1e-9)          // 5 + 12 + 2 + 4
        assertEquals(4, recap.totals.count)
        assertTrue(abs(recap.totals.durationSec - 8_160) < 1e-9)      // 1,800 + 4,680 + 480 + 1,200
        val highlights = assertNotNull(recap.highlights)
        assertEquals(runs[1].id, highlights.longest?.id)              // 12km
        // 2km 4:00이 가장 빠르지만 3km 미만 → 4km 5:00(8/25)이 가장 빠른 세션
        assertEquals(runs[3].id, highlights.fastest?.id)
    }

    @Test
    @DisplayName("연간 결산 — 달력 연 전체를 모으고 강도 배분은 내지 않는다")
    fun yearly() {
        val runs = augustRuns + listOf(run("2026-01-05", km = 10.0, minPerKm = 6.0),
                                       run("2025-12-31", km = 30.0, minPerKm = 6.0))   // 지난해 — 빠진다
        val recap = assertNotNull(compute(RecapPeriod.year(date("2026-01-01T00:00:00+09:00")), runs = runs))
        assertEquals(5, recap.totals.count)
        assertTrue(abs(recap.totals.distanceKm - 33) < 1e-9)          // 23 + 10
        assertEquals("2026년 결산", recap.title)
        assertNull(recap.intensity)
    }

    // MARK: (c) 새 기록

    @Test
    @DisplayName("새 기록 — 기간 끝까지의 PR 중 그 기간 세션이 세운 것만, 다음 달 기록은 비교에서 뺀다")
    fun recordsFilter() {
        val july = run("2026-07-15", km = 6.0, minPerKm = 5.0)
        val september = run("2026-09-02", km = 6.0, minPerKm = 4.5)
        val runs = augustRuns + listOf(july, september)
        val efforts: BestEffortTable = mapOf(
            july.id to mapOf(1_000.0 to 300.0, 5_000.0 to 1_500.0),     // 5K PR은 7월 — 8월 결산에서 빠진다
            runs[0].id to mapOf(1_000.0 to 330.0, 5_000.0 to 1_600.0),  // 8/3 5K 1,600 > 7월 1,500 → PR 아님
            runs[1].id to mapOf(10_000.0 to 3_500.0),                   // 8/10 10K — 유일한 후보라 PR
            runs[3].id to mapOf(1_000.0 to 280.0),                      // 8/25 1K — PB 종목이 아니라 기록에 안 잡힌다
            september.id to mapOf(1_000.0 to 250.0),                    // 9월 — 8월 끝 이후라 비교 대상이 아니다
        )
        val recap = assertNotNull(compute(august, runs = runs, efforts = efforts))
        assertEquals(listOf("10K"), recap.records.map { it.label })
        assertEquals(runs[1].id, recap.records.firstOrNull()?.run?.id)
        assertEquals(3_500.0, recap.records.firstOrNull()?.timeSec)
    }

    // MARK: (d) 런린이

    @Test
    @DisplayName("런린이 — 거리 수치 숨김 플래그, 강도 배분 미노출 (ReportGate §4)")
    fun beginnerHidesDistance() {
        val (runs, histograms) = histogramRuns(count = 8, histogram = mapOf(130 to 600.0))
        val beginner = assertNotNull(compute(august, runs = runs, histograms = histograms, level = RunnerLevel.beginner))
        assertFalse(beginner.totals.showsDistance)
        assertNull(beginner.intensity)
        val intermediate = assertNotNull(compute(august, runs = runs, histograms = histograms))
        assertTrue(intermediate.totals.showsDistance)
        assertNotNull(intermediate.intensity)
    }

    // MARK: 강도 배분

    /// 8월 1일부터 하루 간격 count회 + 같은 히스토그램
    private fun histogramRuns(count: Int, histogram: Map<Int, Double>)
        : Pair<List<RunSummary>, Map<String, ZoneHistogram>> {
        val runs = (1..count).map { run(String.format(Locale.ROOT, "2026-08-%02d", it), km = 5.0, minPerKm = 6.0) }
        return runs to runs.associate { it.id to ZoneHistogram(secondsByBpm = histogram) }
    }

    @Test
    @DisplayName("강도 배분 — 그 달 히스토그램 세션 7회면 생략, 8회면 이지 비율·톤 (ZoneDistributionEngine 경계)")
    fun intensityGuardAndTone() {
        val (seven, sevenHist) = histogramRuns(count = 7, histogram = mapOf(130 to 600.0))
        assertNull(assertNotNull(compute(august, runs = seven, histograms = sevenHist)).intensity)

        // 130bpm(Z2) 70초 + 150bpm(Z3) 30초 × 8회 → 이지 560 / 800 = 0.70 → 주의
        val (eight, eightHist) = histogramRuns(count = 8, histogram = mapOf(130 to 70.0, 150 to 30.0))
        val intensity = assertNotNull(compute(august, runs = eight, histograms = eightHist)?.intensity)
        assertTrue(abs(intensity.easyShare - 0.70) < 1e-9)
        assertEquals(RRTone.caution, intensity.tone)
        assertEquals(8, intensity.sessions)
    }

    // MARK: (e) 마무리 문장

    @Test
    @DisplayName("마무리 문장 — 증가(+10% 이상)·감소(−10% 이하)·첫 결산·꾸준함 분기")
    fun closingLineBranches() {
        // 7월 10km → 8월 23km: (23 − 10) / 10 = +130%
        val up = assertNotNull(compute(august, runs = augustRuns + run("2026-07-10", km = 10.0, minPerKm = 6.0)))
        assertEquals(true, up.deltaPct?.let { abs(it - 130) < 1e-9 })
        assertEquals("지난달보다 130% 더 달리셨습니다. 새가 살찌는 소리가 들립니다.", up.closingLine)

        // 7월 40km → 8월 23km: −42.5%
        val down = assertNotNull(compute(august, runs = augustRuns + run("2026-07-10", km = 40.0, minPerKm = 6.0)))
        assertEquals("쉬어 가는 달도 훈련입니다. 몸이 고마워하고 있을 겁니다.", down.closingLine)

        // 8월 이전 기록 없음 → 첫 결산 (증감 비교 불가)
        val first = assertNotNull(compute(august, runs = augustRuns))
        assertNull(first.deltaPct)
        assertEquals("첫 결산입니다. 시작이 반이라는 말, 오늘은 믿어 보겠습니다.", first.closingLine)

        // 7월 22km → 8월 23km: +4.5% — ±10% 안이면 횟수로 꾸준함
        val steady = assertNotNull(compute(august, runs = augustRuns + run("2026-07-10", km = 22.0, minPerKm = 6.0)))
        assertEquals("4번을 달리셨습니다. 꾸준함은 배신하지 않습니다 — 새도 마찬가지고요.", steady.closingLine)

        // 연간 문구는 "지난해"·"해"
        val year = RecapPeriod.year(date("2026-01-01T00:00:00+09:00"))
        assertEquals("지난해보다 25% 더 달리셨습니다. 새가 살찌는 소리가 들립니다.",
                     RecapEngine.closingLine(period = year, isFirst = false, deltaPct = 25.0, count = 90))
        assertEquals("쉬어 가는 해도 훈련입니다. 몸이 고마워하고 있을 겁니다.",
                     RecapEngine.closingLine(period = year, isFirst = false, deltaPct = -30.0, count = 40))
    }

    @Test
    @DisplayName("진행 중인 달 — 지난달의 같은 경과 일수까지만 견준다")
    fun inProgressComparison() {
        // 9월 10일 09시 = 경과 10일 → 비교 구간 8/1 ~ 8/11 0시
        val midSeptember = date("2026-09-10T09:00:00+09:00")
        val runs = listOf(run("2026-08-05", km = 10.0, minPerKm = 6.0),
                          run("2026-08-25", km = 30.0, minPerKm = 6.0),    // 비교 구간 밖
                          run("2026-09-02", km = 4.0, minPerKm = 6.0),
                          run("2026-09-04", km = 4.0, minPerKm = 6.0),
                          run("2026-09-06", km = 4.0, minPerKm = 6.0))
        val recap = assertNotNull(compute(RecapPeriod.month(date("2026-09-01T00:00:00+09:00")), runs = runs,
                                          now = midSeptember))
        // (12 − 10) / 10 = +20% (지난달 전체 40km와 견줬다면 −70%)
        assertEquals(true, recap.deltaPct?.let { abs(it - 20) < 1e-9 })
    }

    // MARK: (f) 홈 카드 노출

    private fun prompts(text: String, month: String = "", year: String = ""): List<RecapPeriod> =
        RecapEngine.promptKinds(now = date(text), zone = zone, dismissedMonth = month, dismissedYear = year)

    @Test
    @DisplayName("홈 카드 — 월간은 1~7일만, 연간은 12/25~1/7 (1월이면 지난해)")
    fun promptDateBoundaries() {
        val lastMonth = RecapPeriod.month(date("2026-08-01T00:00:00+09:00"))
        assertEquals(listOf(lastMonth), prompts("2026-09-01T00:00:00+09:00"))
        assertEquals(listOf(lastMonth), prompts("2026-09-07T23:59:00+09:00"))
        assertTrue(prompts("2026-09-08T00:00:00+09:00").isEmpty())

        val thisYear = RecapPeriod.year(date("2026-01-01T00:00:00+09:00"))
        assertTrue(prompts("2026-12-24T12:00:00+09:00").isEmpty())
        assertEquals(listOf(thisYear), prompts("2026-12-25T08:00:00+09:00"))
        // 1월 7일 — 지난달(12월)과 지난해(2026) 둘 다, 월간 먼저
        assertEquals(listOf(RecapPeriod.month(date("2026-12-01T00:00:00+09:00")), thisYear),
                     prompts("2027-01-07T20:00:00+09:00"))
        assertTrue(prompts("2027-01-08T00:00:00+09:00").isEmpty())
    }

    @Test
    @DisplayName("홈 카드 — 닫은(열어 본) 기간 키와 같으면 빼고, 예전 기간 키는 무시한다")
    fun promptDismissed() {
        assertTrue(prompts("2026-09-03T09:00:00+09:00", month = "2026-08").isEmpty())
        assertEquals(listOf(RecapPeriod.month(date("2026-08-01T00:00:00+09:00"))),
                     prompts("2026-09-03T09:00:00+09:00", month = "2026-07"))
        // 1월 3일 — 지난해만 닫았으면 12월 월간은 남는다
        assertEquals(listOf(RecapPeriod.month(date("2026-12-01T00:00:00+09:00"))),
                     prompts("2027-01-03T09:00:00+09:00", year = "2026"))
        assertTrue(prompts("2027-01-03T09:00:00+09:00", month = "2026-12", year = "2026").isEmpty())
    }

    @Test
    @DisplayName("닫힘 키 형식 — 월간 yyyy-MM, 연간 yyyy")
    fun dismissKeyFormat() {
        assertEquals("2026-08",
                     RecapEngine.dismissKey(RecapPeriod.month(date("2026-08-15T12:00:00+09:00")), zone = zone))
        assertEquals("2026",
                     RecapEngine.dismissKey(RecapPeriod.year(date("2026-08-15T12:00:00+09:00")), zone = zone))
    }
}
