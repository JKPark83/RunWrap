package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 주간 리포트 지표 레이어 검증 — ReportEngine과 같은 가드/산식을 수치로 노출하는지
class ReportMetricsTests {
    private val now = iso("2026-08-10T09:00:00Z")
    private val engine: ReportEngine get() = ReportEngine(now = now)

    private fun run(daysAgo: Double, km: Double,
                    minPerKm: Double = 6.0, hr: Double? = 150.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = hr)

    /// iOS `Calendar.current.date(from: DateComponents(...))` — testZone 기준 로컬 시각
    private fun local(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(testZone).toInstant()

    /// iOS `Calendar.current.dateInterval(of: .month, for:)!.start`
    private fun monthStart(date: Instant): Instant =
        date.atZone(testZone).toLocalDate().withDayOfMonth(1).atStartOfDay(testZone).toInstant()

    @Test
    @DisplayName("거리 카드 — 수치와 과부하 톤 (+23%, 상한 22km)")
    fun distanceCardValues() {
        val runs = listOf(run(daysAgo = 1.0, km = 12.3), run(daysAgo = 3.0, km = 12.3),
                          run(daysAgo = 8.0, km = 10.0), run(daysAgo = 10.0, km = 10.0))
        val report = engine.weeklyReport(from = runs, zone = testZone)
        val card = assertNotNull(report.distance)
        assertEquals(RRTone.overload, card.tone)
        assertTrue(abs(card.recent7Km - 24.6) < 0.01)
        assertTrue(abs(card.previous7Km - 20) < 0.01)
        assertTrue(abs(card.capKm - 22) < 0.01)
        assertTrue(abs(card.changePct - 23) < 0.01)
        assertTrue(abs(card.overKm - 2.6) < 0.01)
        assertEquals(6, report.weeks.size)
        assertEquals(true, report.weeks.lastOrNull()?.isCurrent)
    }

    @Test
    @DisplayName("최근 7일 창 — 헤더 날짜(6일 전 자정~지금)와 거리·횟수 창이 일치한다 (이슈 #75)")
    fun recentWindowMatchesHeaderDates() {
        // now = 8.10 18:00 KST → 헤더 "8.4 – 8.10", 창은 8.4 00:00부터.
        // 7일 전+2시간 = 8.3 20:00 → 롤링 7×86_400 안이지만 헤더 밖(8일째 날) → 이전 7일로
        val windowStart = now.minusSeconds(6L * 86_400).atZone(testZone).toLocalDate()
            .atStartOfDay(testZone).toInstant()
        val eighthDay = run(daysAgo = 7 - 2.0 / 24, km = 5.0)
        assertTrue(eighthDay.start < windowStart)  // 전제: 8일째 날 기록은 창 시작 전
        val firstDay = RunSummary(id = UUID.randomUUID().toString().uppercase(), start = windowStart.plusSeconds(60),
                                  durationSec = 4.0 * 360, distanceMeters = 4_000.0, avgHeartRate = 150.0)
        val report = engine.weeklyReport(from = listOf(eighthDay, firstDay), zone = testZone)
        assertEquals("8.4 – 8.10", report.dateRange)
        assertEquals(1, report.weekRunCount)                             // 8.4 00:01만
        val card = assertNotNull(report.distance)
        assertTrue(abs(card.recent7Km - 4) < 0.01)                       // 8.4 00:01 기록만
        assertTrue(abs(card.previous7Km - 5) < 0.01)                     // 8.3 20:00 기록은 직전 7일
    }

    @Test
    @DisplayName("ACWR 카드 — 급성 20 ÷ 만성 12.5 = 1.6, 과부하 톤")
    fun acwrCardRatio() {
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0),
                          run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 24.0, km = 10.0),
                          // 이슈 #49: 가드 28일 — 최고령 기록을 창 밖(30일)에 둬 chronic 50/4=12.5 유지
                          run(daysAgo = 30.0, km = 10.0))
        val card = assertNotNull(engine.weeklyReport(from = runs, zone = testZone).acwr)
        assertTrue(abs(card.acute - 20) < 0.01)
        assertTrue(abs(card.chronic - 12.5) < 0.01)
        assertTrue(abs(card.ratio - 1.6) < 0.01)
        assertEquals(RRTone.overload, card.tone)
    }

    @Test
    @DisplayName("ACWR 카드 — 기록이 21~27일치면 카드를 내지 않는다 (이슈 #49)")
    fun acwrCardNeedsFourWeeks() {
        for (oldestDaysAgo in listOf(21.0, 24.0, 27.9)) {
            // 옛 21일 가드였다면 분모 50/4=12.5로 1.6(과부하)이 나왔을 이력 — 실제 주평균은 더 크다
            val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                              run(daysAgo = 10.0, km = 10.0), run(daysAgo = 17.0, km = 10.0),
                              run(daysAgo = oldestDaysAgo, km = 10.0))
            assertNull(engine.weeklyReport(from = runs, zone = testZone).acwr, "oldestDaysAgo=$oldestDaysAgo")
        }
    }

    @Test
    @DisplayName("ACWR 카드 — 기록이 정확히 28일이면 카드를 낸다 (가드 경계, 이슈 #49)")
    fun acwrCardAtExactlyFourWeeks() {
        // 최고령 now-28일 정각: 가드(<=)·창 시작(>=) 모두 포함 → acute 20, chronic 50/4=12.5
        val runs = listOf(run(daysAgo = 2.0, km = 10.0), run(daysAgo = 4.0, km = 10.0),
                          run(daysAgo = 10.0, km = 10.0), run(daysAgo = 17.0, km = 10.0),
                          run(daysAgo = 28.0, km = 10.0))
        val card = assertNotNull(engine.weeklyReport(from = runs, zone = testZone).acwr)
        assertTrue(abs(card.acute - 20) < 0.01)
        assertTrue(abs(card.chronic - 12.5) < 0.01)
        assertTrue(abs(card.ratio - 1.6) < 0.01)
        assertEquals(RRTone.overload, card.tone)
    }

    @Test
    @DisplayName("EF 카드 — 페이스 환산 델타가 양수(빨라짐)이고 개선 톤")
    fun efficiencyCardPaceDelta() {
        // 최근 2주는 같은 심박에 더 빠른 페이스 → EF 상승
        val runs = listOf(run(daysAgo = 1.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 4.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 8.0, km = 8.0, minPerKm = 5.5, hr = 150.0),
                          run(daysAgo = 15.0, km = 8.0, minPerKm = 6.0, hr = 150.0),
                          run(daysAgo = 18.0, km = 8.0, minPerKm = 6.0, hr = 150.0),
                          run(daysAgo = 22.0, km = 8.0, minPerKm = 6.0, hr = 150.0))
        val card = assertNotNull(engine.weeklyReport(from = runs, zone = testZone).efficiency)
        assertEquals(RRTone.improving, card.tone)
        assertTrue(card.changePct > 3)
        // 150bpm 기준 6′00″ → 5′30″: 델타 약 +30초
        assertTrue(abs(card.paceDeltaSec - 30) < 1.5)
        assertTrue(card.points.size >= 2)
        assertEquals(card.points.size, card.pointLabels.size)  // 콜아웃 라벨 병행 배열
        assertEquals("이번 주", card.pointLabels.lastOrNull())
    }

    @Test
    @DisplayName("주 라벨 — 목요일 기준 '8월 2째주' 표기, 달 경계 주는 목요일의 달을 따른다")
    fun weekLabelFormat() {
        // 2026-08-10(월) 주 → 목요일 8.13 → (13+6)/7 = 2째주
        val aug10 = local(2026, 8, 10)
        assertEquals("8월 2째주", Format.weekLabel(weekStart = aug10, zone = testZone))
        // 2026-06-29(월) 주는 6·7월에 걸친다 → 목요일 7.2 → 7월 1째주
        val jun29 = local(2026, 6, 29)
        assertEquals("7월 1째주", Format.weekLabel(weekStart = jun29, zone = testZone))
        // 2026-12-28(월) 주 → 목요일 12.31 → 12월 5째주
        val dec28 = local(2026, 12, 28)
        assertEquals("12월 5째주", Format.weekLabel(weekStart = dec28, zone = testZone))
    }

    @Test
    @DisplayName("상대 주 표기 — 7일 묶음이 아니라 달력 주(월요일 시작) 차이로 '이번 주/지난주/N주 전'")
    fun relativeWeekLabel() {
        // now = 2026-08-17(월) 09:00
        val now = local(2026, 8, 17, hour = 9)
        // 어제(8.16 일)는 지난 달력 주 → 1주 차이
        val sunday = local(2026, 8, 16, hour = 7)
        assertEquals("지난주", Format.relativeWeek(of = sunday, now = now, zone = testZone))
        // 지난주 화요일(8.11)은 6일 전이라 7일 묶음으로는 0이지만 달력 주로는 지난주
        val tuesday = local(2026, 8, 11, hour = 7)
        assertEquals("지난주", Format.relativeWeek(of = tuesday, now = now, zone = testZone))
        // 같은 날 이른 시각은 이번 주
        val earlier = local(2026, 8, 17, hour = 6)
        assertEquals("이번 주", Format.relativeWeek(of = earlier, now = now, zone = testZone))
        // 7.27(월) 주 → 3주 차이
        val threeAgo = local(2026, 8, 2, hour = 7)
        assertEquals("3주 전", Format.relativeWeek(of = threeAgo, now = now, zone = testZone))
    }

    @Test
    @DisplayName("날짜·시각 표기 — 기기 로케일과 무관하게 '9월 30일 오후 3:12', 오전·자정도 12시간제")
    fun monthDayTimeFormat() {
        // 2026-09-30 15:12 → 오후 3:12 (시 앞자리 0 없음)
        val afternoon = local(2026, 9, 30, hour = 15, minute = 12)
        assertEquals("9월 30일 오후 3:12", Format.monthDayTime(afternoon, zone = testZone))
        // 2026-01-05 00:07 → 자정은 오전 12:07
        val midnight = local(2026, 1, 5, hour = 0, minute = 7)
        assertEquals("1월 5일 오전 12:07", Format.monthDayTime(midnight, zone = testZone))
    }

    @Test
    @DisplayName("거리 카드 차트 — 가장 오래된 기록의 주까지 전체 주를 그린다 (스크롤용)")
    fun distanceCardFullSpanWeeks() {
        // now = 8.10(월). 56일 전 = 6.15(월) 주 → 6.15…8.10 주가 9개
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 10.0),
                          run(daysAgo = 56.0, km = 5.0))
        val weeks = engine.weeklyReport(from = runs, zone = testZone).weeks
        assertEquals(9, weeks.size)
        assertEquals("6월 3째주", weeks.firstOrNull()?.label)   // 6.15 주 — 목요일 6.18
        assertEquals("8월 2째주", weeks.lastOrNull()?.label)    // 이번 주 — 목요일 8.13
        assertEquals(true, weeks.lastOrNull()?.isCurrent)
        assertTrue(abs((weeks.firstOrNull()?.km ?: 0.0) - 5) < 0.01)  // 가장 오래된 주의 합계
    }

    @Test
    @DisplayName("주간 차트 독립 — 기준 7일 3km 미만이라 거리 카드가 nil이어도 주 막대는 채운다 (이슈 #91)")
    fun weeksSurviveDistanceGuard() {
        // now = 8.10(월) 18:00 KST. 이전 7일(7.28~8.3)은 8일 전 2km뿐 → 증가율 가드로 distance nil.
        // 차트는 달력 주 합계 — 8일 전 = 8.2(일) → 7.27 주 막대 2km,
        // 1일 전 = 8.9(일) → 8.3 주 막대 10km. 이번 주(8.10 주)는 기록 없음 0km. 3주뿐이라 최소 6주로 채운다.
        val report = engine.weeklyReport(from = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 2.0)),
                                         zone = testZone)
        assertNull(report.distance)
        assertEquals(6, report.weeks.size)
        assertEquals(true, report.weeks.lastOrNull()?.isCurrent)
        assertTrue(abs(report.weeks.map { it.km }.sum() - 12) < 0.01)  // 10 + 2 — 가드와 무관하게 전부 합산
    }

    @Test
    @DisplayName("주간 차트 — 기록이 하나도 없으면 빈 배열")
    fun weeksEmptyWithoutRuns() {
        assertTrue(engine.weeklyReport(from = emptyList(), zone = testZone).weeks.isEmpty())
    }

    @Test
    @DisplayName("표본 부족 가드 — 기준 주 3km 미만·3주 미만 기록이면 카드가 없다")
    fun guardsProduceNilCards() {
        val report = engine.weeklyReport(from = listOf(run(daysAgo = 1.0, km = 10.0), run(daysAgo = 8.0, km = 2.0)),
                                         zone = testZone)
        assertNull(report.distance)   // 기준 주 3km 미만
        assertNull(report.acwr)       // 기록 3주 미만
        assertNull(report.efficiency) // 표본 3개 미만
        assertTrue(report.visibleCards(level = RunnerLevel.advanced).isEmpty())
    }

    /// 문장 게이트 픽스처 — 거리·ACWR만 있는 리포트 (EF 없음)
    private fun sentenceReport(distanceTone: RRTone, recent: Double,
                               acwr: WeeklyReport.AcwrCard?): WeeklyReport {
        val distance = WeeklyReport.DistanceCard(tone = distanceTone, recent7Km = recent, previous7Km = 20.0,
                                                 capKm = 22.0, changePct = (recent - 20) / 20 * 100)
        return WeeklyReport(dateRange = "8.4 – 8.10", weeks = emptyList(), distance = distance, acwr = acwr,
                            efficiency = null, streakWeeks = 3, ranThisWeek = true, weekRunCount = 3)
    }

    @Test
    @DisplayName("첫 문장 게이트 — 런린이는 숨긴 ACWR 과부하 톤으로 문장을 고르지 않는다 (이슈 #124)")
    fun headlineIgnoresHiddenCards() {
        // 거리 +0% steady, ACWR 20 ÷ 12.5 = 1.6 overload
        val acwr = WeeklyReport.AcwrCard(tone = RRTone.overload, acute = 20.0, chronic = 12.5, ratio = 1.6)
        val report = sentenceReport(distanceTone = RRTone.steady, recent = 20.0, acwr = acwr)
        assertEquals("안정적으로 리듬을 지킨 한 주였습니다.", report.headline(level = RunnerLevel.beginner))
        assertEquals("몸보다 훈련량이 앞서 나간 한 주였습니다.", report.headline(level = RunnerLevel.intermediate))
    }

    @Test
    @DisplayName("다음 주 제안 게이트 — 런린이는 km 수치 없이 문장만, ACWR 근거도 쓰지 않는다 (이슈 #124)")
    fun beginnerSuggestionHasNoNumbers() {
        // 거리 +23% overload → 런린이는 감량 문장만
        val overload = sentenceReport(distanceTone = RRTone.overload, recent = 24.6, acwr = null)
        val text = assertNotNull(overload.suggestion(level = RunnerLevel.beginner))
        assertFalse(text.contains("km"))
        assertEquals("이번 주보다 조금 덜 달려도 괜찮아요. 롱런 하나를 가볍게 바꿔 보세요.", text)

        // 거리 steady + ACWR 1.6 — ACWR 카드가 숨겨진 런린이는 과부하 근거로 쓰지 않는다
        val acwr = WeeklyReport.AcwrCard(tone = RRTone.overload, acute = 20.0, chronic = 12.5, ratio = 1.6)
        val hiddenAcwr = sentenceReport(distanceTone = RRTone.steady, recent = 20.0, acwr = acwr)
        assertEquals("지금 리듬 그대로 이어가면 됩니다. 다음 주에도 증가 폭 10% 이내를 지켜보세요.",
                     hiddenAcwr.suggestion(level = RunnerLevel.beginner))
        // 런잘알은 ACWR 근거로 감량 — 상한 min(22, 12.5 × 1.3 = 16.25) → 16, 하한 16.25 × 0.93 ≈ 15.1 → 15
        assertEquals("주간 15–16 km로 줄이면 안전 구간으로 돌아옵니다. 롱런 하나를 회복 주행으로 바꾸면 충분해요.",
                     hiddenAcwr.suggestion(level = RunnerLevel.intermediate))
    }

    @Test
    @DisplayName("다음 주 제안 — 런잘알은 기존 수치 문장을 유지한다 (상한 22km, 하한 22 × 0.93 ≈ 20)")
    fun intermediateSuggestionKeepsNumbers() {
        val report = sentenceReport(distanceTone = RRTone.overload, recent = 24.6, acwr = null)
        assertEquals("주간 20–22 km로 줄이면 안전 구간으로 돌아옵니다. 롱런 하나를 회복 주행으로 바꾸면 충분해요.",
                     report.suggestion(level = RunnerLevel.intermediate))
    }

    @Test
    @DisplayName("월간 통계 — 8월 집계와 지난달 같은 날짜까지 비교")
    fun monthlyStatsAggregates() {
        val august = listOf(run(daysAgo = 1.0, km = 10.0, minPerKm = 6.0, hr = 150.0),    // 8.9
                            run(daysAgo = 5.0, km = 10.0, minPerKm = 6.0, hr = 148.0))    // 8.5
        val july = listOf(run(daysAgo = 33.0, km = 8.0, minPerKm = 6.5, hr = 152.0),    // 7.8  — 비교 구간 안
                          run(daysAgo = 38.0, km = 8.0, minPerKm = 6.5, hr = 152.0))    // 7.3  — 비교 구간 안
        val month = local(2026, 8, 1)
        val stats = MonthlyStats.compute(runs = august + july, month = month, now = now, zone = testZone)
        assertEquals(2, stats.count)
        assertTrue(abs(stats.totalKm - 20) < 0.01)
        assertEquals(10, stats.comparisonDays)                                // 8.10 기준 → 7.1–7.10
        assertTrue(stats.deltaPct != null && abs(stats.deltaPct!! - 25) < 0.01)  // 16→20km
        assertTrue(stats.avgPaceSec != null && abs(stats.avgPaceSec!! - 360) < 0.01)
        assertTrue(stats.paceDeltaSec != null && stats.paceDeltaSec!! < 0)       // 빨라짐
        assertTrue(stats.runs.first().start > stats.runs.last().start)  // 최신순 정렬
        assertEquals("지난달 1–10일 대비", stats.deltaCaption)
    }

    @Test
    @DisplayName("월간 통계 — 0초·비현실 페이스 기록은 평균 페이스의 분자·분모 모두에서 뺀다 (이슈 #78)")
    fun monthlyAvgPaceSkipsInvalidPace() {
        // 8.9 10km 60분(360초/km) + 8.5 0초 5.2km 임포트.
        // 가드가 없으면 3_600 ÷ 15.2km ≈ 237초/km로 과하게 빨라졌다 → 가드 후 3_600 ÷ 10km = 360
        val zeroDuration = RunSummary(id = UUID.randomUUID().toString().uppercase(), start = now.minusSeconds(5L * 86_400),
                                      durationSec = 0.0, distanceMeters = 5_200.0, avgHeartRate = null)
        val runs = listOf(run(daysAgo = 1.0, km = 10.0), zeroDuration)
        val month = local(2026, 8, 1)
        val stats = MonthlyStats.compute(runs = runs, month = month, now = now, zone = testZone)
        val pace = assertNotNull(stats.avgPaceSec)
        assertTrue(abs(pace - 360) < 0.01)
        assertEquals(2, stats.count)                       // 횟수·거리 집계는 그대로
        assertTrue(abs(stats.totalKm - 15.2) < 0.01)
    }

    @Test
    @DisplayName("월간 통계 — 진행 중인 달은 지난달 후반 기록을 비교에서 뺀다")
    fun currentMonthIgnoresLaterDaysOfPreviousMonth() {
        // now = 8.10. 7.26 롱런은 '같은 날짜까지' 밖이라 비교 대상이 아니다.
        val runs = listOf(run(daysAgo = 1.0, km = 10.0),                 // 8.9
                          run(daysAgo = 33.0, km = 8.0),                 // 7.8  — 비교 구간 안
                          run(daysAgo = 15.0, km = 40.0))                // 7.26 — 비교 구간 밖
        val month = local(2026, 8, 1)
        val stats = MonthlyStats.compute(runs = runs, month = month, now = now, zone = testZone)
        // 8km와만 비교 → +25%. 48km 전체와 비교하면 −79%로 나왔다.
        assertTrue(stats.deltaPct != null && abs(stats.deltaPct!! - 25) < 0.01)
    }

    @Test
    @DisplayName("월간 통계 — 이미 끝난 달은 지난달 전체와 비교한다")
    fun pastMonthComparesWholeMonth() {
        // 7월을 볼 때(now = 8.10)는 7월도 6월도 완결된 달 — 잘라 볼 이유가 없다
        val runs = listOf(run(daysAgo = 15.0, km = 20.0),                // 7.26
                          run(daysAgo = 45.0, km = 8.0),                 // 6.26
                          run(daysAgo = 55.0, km = 8.0))                 // 6.16
        val july = local(2026, 7, 1)
        val stats = MonthlyStats.compute(runs = runs, month = july, now = now, zone = testZone)
        assertNull(stats.comparisonDays)
        assertEquals("지난달 대비", stats.deltaCaption)
        assertTrue(stats.deltaPct != null && abs(stats.deltaPct!! - 25) < 0.01)  // 16→20km
    }

    @Test
    @DisplayName("월간 통계 — 진행 중인 달이 7일 미만 경과면 '주 N회'를 내지 않는다 (이슈 #75·#86)")
    fun currentMonthPerWeekUsesElapsedDays() {
        // now = 9.3 09:00(로컬), 9.1·9.3 기록 2회 → 경과 3일(오늘 포함) < 7 → nil.
        // #75에서는 2 ÷ (3/7) ≈ 4.67로 외삽했지만 표본 부족이라 미노출로 결정했다 (#86).
        // 로컬 달력으로 만들어 시간대와 무관하게 9월 안에 둔다
        val september3 = local(2026, 9, 3, hour = 9)
        val runs = listOf(local(2026, 9, 1, hour = 9), local(2026, 9, 3, hour = 7))
            .map { RunSummary(id = UUID.randomUUID().toString().uppercase(), start = it, durationSec = 1_800.0,
                              distanceMeters = 5_000.0, avgHeartRate = 150.0) }
        val month = monthStart(september3)
        val stats = MonthlyStats.compute(runs = runs, month = month, now = september3, zone = testZone)
        assertEquals(2, stats.count)
        assertNull(stats.perWeek)
    }

    @Test
    @DisplayName("월간 통계 — 진행 중인 달이 7일째면 '주 N회'를 경과 일수로 계산한다 (이슈 #86)")
    fun currentMonthPerWeekFromSeventhDay() {
        // now = 9.7 09:00(로컬), 9.1·9.7 기록 2회 → 경과 7일(오늘 포함) → 2 ÷ (7/7) = 2.0
        val september7 = local(2026, 9, 7, hour = 9)
        val runs = listOf(local(2026, 9, 1, hour = 9), local(2026, 9, 7, hour = 7))
            .map { RunSummary(id = UUID.randomUUID().toString().uppercase(), start = it, durationSec = 1_800.0,
                              distanceMeters = 5_000.0, avgHeartRate = 150.0) }
        val month = monthStart(september7)
        val stats = MonthlyStats.compute(runs = runs, month = month, now = september7, zone = testZone)
        val perWeek = assertNotNull(stats.perWeek)
        assertTrue(abs(perWeek - 2.0) < 0.05)
    }

    @Test
    @DisplayName("월간 통계 — 끝난 달의 '주 N회'는 월 전체 일수로 나눈다 (기존 동작 유지)")
    fun pastMonthPerWeekUsesWholeMonth() {
        // now = 8.10에 6월(30일)을 본다 — 8회 ÷ (30/7) ≈ 1.87
        val june = local(2026, 6, 1)
        val runs = (0 until 8).map { index ->
            RunSummary(id = UUID.randomUUID().toString().uppercase(),
                       start = instantSince1970(june.timeIntervalSince1970 + index.toDouble() * 3 * 86_400 + 7 * 3_600),
                       durationSec = 1_800.0, distanceMeters = 5_000.0, avgHeartRate = 150.0)
        }
        val stats = MonthlyStats.compute(runs = runs, month = june, now = now, zone = testZone)
        assertEquals(8, stats.count)
        val perWeek = assertNotNull(stats.perWeek)
        assertTrue(abs(perWeek - 8 / (30.0 / 7)) < 0.05)
    }

    @Test
    @DisplayName("월 목록 — 모든 기록이 다음 달이어도 이번 달 하나는 돌려준다 (이슈 #68)")
    fun availableMonthsAllFutureRuns() {
        // now = 8.10. 9.9·9.14 기록뿐 → 가장 오래된 달(9월)이 이번 달보다 뒤라 예전엔 빈 배열
        val runs = listOf(run(daysAgo = -30.0, km = 5.0), run(daysAgo = -35.0, km = 5.0))
        val august = monthStart(now)
        assertEquals(listOf(august), MonthlyStats.availableMonths(runs = runs, now = now, zone = testZone))
    }

    @Test
    @DisplayName("월 목록 — 3개월에 걸친 기록이면 이번 달부터 3개, 최신 먼저")
    fun availableMonthsSpansThreeMonths() {
        // now = 8.10. 8.9·7.8·6.16 → [8월, 7월, 6월]
        val runs = listOf(run(daysAgo = 1.0, km = 5.0), run(daysAgo = 33.0, km = 5.0), run(daysAgo = 55.0, km = 5.0))
        val augustDay = now.atZone(testZone).toLocalDate().withDayOfMonth(1)
        val august = augustDay.atStartOfDay(testZone).toInstant()
        val july = augustDay.minusMonths(1).atStartOfDay(testZone).toInstant()
        val june = augustDay.minusMonths(2).atStartOfDay(testZone).toInstant()
        assertEquals(listOf(august, july, june), MonthlyStats.availableMonths(runs = runs, now = now, zone = testZone))
    }

    @Test
    @DisplayName("월 목록 — 기록이 없으면 이번 달 하나만 돌려준다 (기존 동작 유지)")
    fun availableMonthsNoRuns() {
        val august = monthStart(now)
        assertEquals(listOf(august), MonthlyStats.availableMonths(runs = emptyList(), now = now, zone = testZone))
    }

    // MARK: - streak · 추이 지표
    // now = 8.10(월) 18:00 KST — 이번 ISO 주는 [8.10, 8.17).
    // daysAgo 0.1~0.3 = 이번 주, 1~6 = 지난주(8.3–8.9), 9 = 2주 전(8.1), 25 = 4주 전 주(7.16).

    @Test
    @DisplayName("streak — 이번 주 포함 3주 연속이면 3")
    fun streakConsecutiveWeeks() {
        val runs = listOf(run(daysAgo = 0.2, km = 5.0),   // 8.10 — 이번 주
                          run(daysAgo = 3.0, km = 5.0),     // 8.7  — 지난주
                          run(daysAgo = 9.0, km = 5.0))     // 8.1  — 2주 전
        assertEquals(3, ReportEngine.streakWeeks(runs = runs, now = now, zone = testZone))
    }

    @Test
    @DisplayName("streak — 지난주가 비면 이번 주만 세어 1")
    fun streakBrokenWeek() {
        val runs = listOf(run(daysAgo = 0.2, km = 5.0),   // 8.10 — 이번 주
                          run(daysAgo = 9.0, km = 5.0))     // 8.1  — 2주 전 (지난주 단절)
        assertEquals(1, ReportEngine.streakWeeks(runs = runs, now = now, zone = testZone))
    }

    @Test
    @DisplayName("streak — 진행 중인 이번 주에 무기록이어도 지난 연속은 유지된다")
    fun streakGraceForCurrentWeek() {
        // 이번 주(월요일)에 아직 안 달렸다 — 지난주·2주 전 연속 2가 0으로 초기화되면 안 된다
        val runs = listOf(run(daysAgo = 3.0, km = 5.0),     // 8.7 — 지난주
                          run(daysAgo = 9.0, km = 5.0))     // 8.1 — 2주 전
        assertEquals(2, ReportEngine.streakWeeks(runs = runs, now = now, zone = testZone))
    }

    @Test
    @DisplayName("streak — 기록이 없으면 0")
    fun streakEmpty() {
        assertEquals(0, ReportEngine.streakWeeks(runs = emptyList(), now = now, zone = testZone))
    }

    @Test
    @DisplayName("이번 주 러닝 여부 — 이번 주 월요일 러닝이면 true, 지난주만이면 false (이슈 #195)")
    fun ranThisWeek() {
        // now = 8.10(월) 18:00 KST — daysAgo 0.2는 같은 날 새벽, 3은 지난주 8.7
        assertTrue(ReportEngine.ranThisWeek(runs = listOf(run(daysAgo = 0.2, km = 5.0), run(daysAgo = 3.0, km = 5.0)),
                                            now = now, zone = testZone))
        assertFalse(ReportEngine.ranThisWeek(runs = listOf(run(daysAgo = 3.0, km = 5.0), run(daysAgo = 9.0, km = 5.0)),
                                             now = now, zone = testZone))
        assertFalse(ReportEngine.ranThisWeek(runs = emptyList(), now = now, zone = testZone))
    }

    private fun daysBefore(days: Double): Instant = instantSince1970(now.timeIntervalSince1970 - days * 86_400)

    @Test
    @DisplayName("VO₂max 추이 — 주 평균 45.2, 4주 전 44.0 대비 +1.2 개선 톤")
    fun vo2MaxTrendWeeklyAverage() {
        // 이번 주 (45.0 + 45.4) / 2 = 45.2, 4주 전 주(7.16) 44.0 → 델타 +1.2 ≥ +1.0 → improving
        val samples = listOf(
            daysBefore(0.1) to 45.0,
            daysBefore(0.3) to 45.4,
            daysBefore(25.0) to 44.0,
        )
        val trend = assertNotNull(ReportEngine.vo2MaxTrend(samples = samples, now = now, zone = testZone))
        assertTrue(abs(trend.current - 45.2) < 0.01)
        assertTrue(trend.delta != null && abs(trend.delta!! - 1.2) < 0.01)
        assertEquals(4, trend.spanWeeks)
        assertEquals(RRTone.improving, trend.tone)
        assertEquals(2, trend.points.size)
        assertTrue(abs(trend.points[0] - 44.0) < 0.01)  // 오래된 → 최신 순서
        assertEquals(listOf("7월 3째주", "8월 2째주"), trend.pointLabels)
    }

    @Test
    @DisplayName("VO₂max 유지 판정 — ±1.0 미만 변화(+0.5)는 추정 오차 범위로 보고 steady")
    fun vo2MaxTrendSteadyBand() {
        // 이번 주 (44.4 + 44.6) / 2 = 44.5, 4주 전 44.0 → 델타 +0.5 < +1.0 → steady
        val samples = listOf(
            daysBefore(0.1) to 44.4,
            daysBefore(0.3) to 44.6,
            daysBefore(25.0) to 44.0,
        )
        val trend = assertNotNull(ReportEngine.vo2MaxTrend(samples = samples, now = now, zone = testZone))
        assertTrue(trend.delta != null && abs(trend.delta!! - 0.5) < 0.01)
        assertEquals(RRTone.steady, trend.tone)
    }

    @Test
    @DisplayName("VO₂max 가드 — 최근 12주 추정 기록 3회 미만이면 nil")
    fun vo2MaxTrendGuard() {
        val two = listOf(
            daysBefore(0.1) to 45.0,
            daysBefore(25.0) to 44.0,
        )
        assertNull(ReportEngine.vo2MaxTrend(samples = two, now = now, zone = testZone))

        // 3개째가 12주(84일) 밖이면 표본 수에 들지 않는다 → 여전히 nil
        val outOfWindow = two + listOf(daysBefore(90.0) to 42.0)
        assertNull(ReportEngine.vo2MaxTrend(samples = outOfWindow, now = now, zone = testZone))
    }
}
