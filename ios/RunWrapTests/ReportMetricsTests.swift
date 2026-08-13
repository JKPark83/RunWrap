import Foundation
import Testing
@testable import RunWrap

/// 주간 리포트 지표 레이어 검증 — ReportEngine과 같은 가드/산식을 수치로 노출하는지
struct ReportMetricsTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!
    var engine: ReportEngine { ReportEngine(now: now) }

    private func run(daysAgo: Double, km: Double,
                     minPerKm: Double = 6, hr: Double? = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }

    @Test("거리 카드 — 수치와 과부하 톤 (+23%, 상한 22km)")
    func distanceCardValues() throws {
        let runs = [run(daysAgo: 1, km: 12.3), run(daysAgo: 3, km: 12.3),
                    run(daysAgo: 8, km: 10), run(daysAgo: 10, km: 10)]
        let card = try #require(engine.weeklyReport(from: runs).distance)
        #expect(card.tone == .overload)
        #expect(abs(card.recent7Km - 24.6) < 0.01)
        #expect(abs(card.previous7Km - 20) < 0.01)
        #expect(abs(card.capKm - 22) < 0.01)
        #expect(abs(card.changePct - 23) < 0.01)
        #expect(abs(card.overKm - 2.6) < 0.01)
        #expect(card.weeks.count == 6)
        #expect(card.weeks.last?.isCurrent == true)
    }

    @Test("ACWR 카드 — 급성 20 ÷ 만성 12.5 = 1.6, 과부하 톤")
    func acwrCardRatio() throws {
        let runs = [run(daysAgo: 2, km: 10), run(daysAgo: 4, km: 10),
                    run(daysAgo: 10, km: 10),
                    run(daysAgo: 17, km: 10),
                    run(daysAgo: 24, km: 10)]
        let card = try #require(engine.weeklyReport(from: runs).acwr)
        #expect(abs(card.acute - 20) < 0.01)
        #expect(abs(card.chronic - 12.5) < 0.01)
        #expect(abs(card.ratio - 1.6) < 0.01)
        #expect(card.tone == .overload)
    }

    @Test("EF 카드 — 페이스 환산 델타가 양수(빨라짐)이고 개선 톤")
    func efficiencyCardPaceDelta() throws {
        // 최근 2주는 같은 심박에 더 빠른 페이스 → EF 상승
        let runs = [run(daysAgo: 1, km: 8, minPerKm: 5.5, hr: 150),
                    run(daysAgo: 4, km: 8, minPerKm: 5.5, hr: 150),
                    run(daysAgo: 8, km: 8, minPerKm: 5.5, hr: 150),
                    run(daysAgo: 15, km: 8, minPerKm: 6.0, hr: 150),
                    run(daysAgo: 18, km: 8, minPerKm: 6.0, hr: 150),
                    run(daysAgo: 22, km: 8, minPerKm: 6.0, hr: 150)]
        let card = try #require(engine.weeklyReport(from: runs).efficiency)
        #expect(card.tone == .improving)
        #expect(card.changePct > 3)
        // 150bpm 기준 6′00″ → 5′30″: 델타 약 +30초
        #expect(abs(card.paceDeltaSec - 30) < 1.5)
        #expect(card.points.count >= 2)
        #expect(card.pointLabels.count == card.points.count)  // 콜아웃 라벨 병행 배열
        #expect(card.pointLabels.last == "이번 주")
    }

    @Test("주 라벨 — 목요일 기준 '8월 2째주' 표기, 달 경계 주는 목요일의 달을 따른다")
    func weekLabelFormat() {
        let calendar = Calendar.current
        // 2026-08-10(월) 주 → 목요일 8.13 → (13+6)/7 = 2째주
        let aug10 = calendar.date(from: DateComponents(year: 2026, month: 8, day: 10))!
        #expect(Format.weekLabel(weekStart: aug10) == "8월 2째주")
        // 2026-06-29(월) 주는 6·7월에 걸친다 → 목요일 7.2 → 7월 1째주
        let jun29 = calendar.date(from: DateComponents(year: 2026, month: 6, day: 29))!
        #expect(Format.weekLabel(weekStart: jun29) == "7월 1째주")
        // 2026-12-28(월) 주 → 목요일 12.31 → 12월 5째주
        let dec28 = calendar.date(from: DateComponents(year: 2026, month: 12, day: 28))!
        #expect(Format.weekLabel(weekStart: dec28) == "12월 5째주")
    }

    @Test("거리 카드 차트 — 가장 오래된 기록의 주까지 전체 주를 그린다 (스크롤용)")
    func distanceCardFullSpanWeeks() throws {
        // now = 8.10(월). 56일 전 = 6.15(월) 주 → 6.15…8.10 주가 9개
        let runs = [run(daysAgo: 1, km: 10), run(daysAgo: 8, km: 10),
                    run(daysAgo: 56, km: 5)]
        let card = try #require(engine.weeklyReport(from: runs).distance)
        #expect(card.weeks.count == 9)
        #expect(card.weeks.first?.label == "6월 3째주")   // 6.15 주 — 목요일 6.18
        #expect(card.weeks.last?.label == "8월 2째주")    // 이번 주 — 목요일 8.13
        #expect(card.weeks.last?.isCurrent == true)
        #expect(abs((card.weeks.first?.km ?? 0) - 5) < 0.01)  // 가장 오래된 주의 합계
    }

    @Test("표본 부족 가드 — 기준 주 3km 미만·3주 미만 기록이면 카드가 없다")
    func guardsProduceNilCards() {
        let report = engine.weeklyReport(from: [run(daysAgo: 1, km: 10), run(daysAgo: 8, km: 2)])
        #expect(report.distance == nil)   // 기준 주 3km 미만
        #expect(report.acwr == nil)       // 기록 3주 미만
        #expect(report.efficiency == nil) // 표본 3개 미만
        #expect(report.isEmpty)
    }

    @Test("월간 통계 — 8월 집계와 지난달 같은 날짜까지 비교")
    func monthlyStatsAggregates() {
        let august = [run(daysAgo: 1, km: 10, minPerKm: 6, hr: 150),    // 8.9
                      run(daysAgo: 5, km: 10, minPerKm: 6, hr: 148)]    // 8.5
        let july = [run(daysAgo: 33, km: 8, minPerKm: 6.5, hr: 152),    // 7.8  — 비교 구간 안
                    run(daysAgo: 38, km: 8, minPerKm: 6.5, hr: 152)]    // 7.3  — 비교 구간 안
        let month = Calendar.current.date(from: DateComponents(year: 2026, month: 8, day: 1))!
        let stats = MonthlyStats.compute(runs: august + july, month: month, now: now)
        #expect(stats.count == 2)
        #expect(abs(stats.totalKm - 20) < 0.01)
        #expect(stats.comparisonDays == 10)                                // 8.10 기준 → 7.1–7.10
        #expect(stats.deltaPct != nil && abs(stats.deltaPct! - 25) < 0.01)  // 16→20km
        #expect(stats.avgPaceSec != nil && abs(stats.avgPaceSec! - 360) < 0.01)
        #expect(stats.paceDeltaSec != nil && stats.paceDeltaSec! < 0)       // 빨라짐
        #expect(stats.runs.first!.start > stats.runs.last!.start)  // 최신순 정렬
        #expect(stats.deltaCaption == "지난달 1–10일 대비")
    }

    @Test("월간 통계 — 진행 중인 달은 지난달 후반 기록을 비교에서 뺀다")
    func currentMonthIgnoresLaterDaysOfPreviousMonth() {
        // now = 8.10. 7.26 롱런은 '같은 날짜까지' 밖이라 비교 대상이 아니다.
        let runs = [run(daysAgo: 1, km: 10),                 // 8.9
                    run(daysAgo: 33, km: 8),                 // 7.8  — 비교 구간 안
                    run(daysAgo: 15, km: 40)]                // 7.26 — 비교 구간 밖
        let month = Calendar.current.date(from: DateComponents(year: 2026, month: 8, day: 1))!
        let stats = MonthlyStats.compute(runs: runs, month: month, now: now)
        // 8km와만 비교 → +25%. 48km 전체와 비교하면 −79%로 나왔다.
        #expect(stats.deltaPct != nil && abs(stats.deltaPct! - 25) < 0.01)
    }

    @Test("월간 통계 — 이미 끝난 달은 지난달 전체와 비교한다")
    func pastMonthComparesWholeMonth() {
        // 7월을 볼 때(now = 8.10)는 7월도 6월도 완결된 달 — 잘라 볼 이유가 없다
        let runs = [run(daysAgo: 15, km: 20),                // 7.26
                    run(daysAgo: 45, km: 8),                 // 6.26
                    run(daysAgo: 55, km: 8)]                 // 6.16
        let july = Calendar.current.date(from: DateComponents(year: 2026, month: 7, day: 1))!
        let stats = MonthlyStats.compute(runs: runs, month: july, now: now)
        #expect(stats.comparisonDays == nil)
        #expect(stats.deltaCaption == "지난달 대비")
        #expect(stats.deltaPct != nil && abs(stats.deltaPct! - 25) < 0.01)  // 16→20km
    }

    // MARK: - streak · 추이 지표
    // now = 8.10(월) 18:00 KST — 이번 ISO 주는 [8.10, 8.17).
    // daysAgo 0.1~0.3 = 이번 주, 1~6 = 지난주(8.3–8.9), 9 = 2주 전(8.1), 25 = 4주 전 주(7.16).

    @Test("streak — 이번 주 포함 3주 연속이면 3")
    func streakConsecutiveWeeks() {
        let runs = [run(daysAgo: 0.2, km: 5),   // 8.10 — 이번 주
                    run(daysAgo: 3, km: 5),     // 8.7  — 지난주
                    run(daysAgo: 9, km: 5)]     // 8.1  — 2주 전
        #expect(ReportEngine.streakWeeks(runs: runs, now: now) == 3)
    }

    @Test("streak — 지난주가 비면 이번 주만 세어 1")
    func streakBrokenWeek() {
        let runs = [run(daysAgo: 0.2, km: 5),   // 8.10 — 이번 주
                    run(daysAgo: 9, km: 5)]     // 8.1  — 2주 전 (지난주 단절)
        #expect(ReportEngine.streakWeeks(runs: runs, now: now) == 1)
    }

    @Test("streak — 진행 중인 이번 주에 무기록이어도 지난 연속은 유지된다")
    func streakGraceForCurrentWeek() {
        // 이번 주(월요일)에 아직 안 달렸다 — 지난주·2주 전 연속 2가 0으로 초기화되면 안 된다
        let runs = [run(daysAgo: 3, km: 5),     // 8.7 — 지난주
                    run(daysAgo: 9, km: 5)]     // 8.1 — 2주 전
        #expect(ReportEngine.streakWeeks(runs: runs, now: now) == 2)
    }

    @Test("streak — 기록이 없으면 0")
    func streakEmpty() {
        #expect(ReportEngine.streakWeeks(runs: [], now: now) == 0)
    }

    @Test("VO₂max 추이 — 주 평균 45.2, 4주 전 44.0 대비 +1.2 개선 톤")
    func vo2MaxTrendWeeklyAverage() throws {
        // 이번 주 (45.0 + 45.4) / 2 = 45.2, 4주 전 주(7.16) 44.0 → 델타 +1.2 ≥ +1.0 → improving
        let samples: [(date: Date, value: Double)] = [
            (now.addingTimeInterval(-0.1 * 86_400), 45.0),
            (now.addingTimeInterval(-0.3 * 86_400), 45.4),
            (now.addingTimeInterval(-25 * 86_400), 44.0),
        ]
        let trend = try #require(ReportEngine.vo2MaxTrend(samples: samples, now: now))
        #expect(abs(trend.current - 45.2) < 0.01)
        #expect(trend.delta != nil && abs(trend.delta! - 1.2) < 0.01)
        #expect(trend.spanWeeks == 4)
        #expect(trend.tone == .improving)
        #expect(trend.points.count == 2)
        #expect(abs(trend.points[0] - 44.0) < 0.01)  // 오래된 → 최신 순서
        #expect(trend.pointLabels == ["7월 3째주", "8월 2째주"])
    }

    @Test("VO₂max 유지 판정 — ±1.0 미만 변화(+0.5)는 추정 오차 범위로 보고 steady")
    func vo2MaxTrendSteadyBand() throws {
        // 이번 주 (44.4 + 44.6) / 2 = 44.5, 4주 전 44.0 → 델타 +0.5 < +1.0 → steady
        let samples: [(date: Date, value: Double)] = [
            (now.addingTimeInterval(-0.1 * 86_400), 44.4),
            (now.addingTimeInterval(-0.3 * 86_400), 44.6),
            (now.addingTimeInterval(-25 * 86_400), 44.0),
        ]
        let trend = try #require(ReportEngine.vo2MaxTrend(samples: samples, now: now))
        #expect(trend.delta != nil && abs(trend.delta! - 0.5) < 0.01)
        #expect(trend.tone == .steady)
    }

    @Test("VO₂max 가드 — 최근 12주 추정 기록 3회 미만이면 nil")
    func vo2MaxTrendGuard() {
        let two: [(date: Date, value: Double)] = [
            (now.addingTimeInterval(-0.1 * 86_400), 45.0),
            (now.addingTimeInterval(-25 * 86_400), 44.0),
        ]
        #expect(ReportEngine.vo2MaxTrend(samples: two, now: now) == nil)

        // 3개째가 12주(84일) 밖이면 표본 수에 들지 않는다 → 여전히 nil
        let outOfWindow = two + [(now.addingTimeInterval(-90 * 86_400), 42.0)]
        #expect(ReportEngine.vo2MaxTrend(samples: outOfWindow, now: now) == nil)
    }
}
