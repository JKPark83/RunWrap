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
}
