import Foundation
import Testing
@testable import RunWrap

/// 리포트 엔진 검증 — 고정 시각 + 합성 데이터 (유용성 자가 검증은 실기기에서 별도 진행)
struct ReportEngineTests {
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

    private func insight(_ kind: Insight.Kind, in runs: [RunSummary]) -> Insight? {
        engine.insights(from: runs).first { $0.kind == kind }
    }

    // MARK: 주간 거리 증가율

    @Test("주간 증가율 10% 초과는 경고 — 기획서 예시 +23%")
    func weeklyIncreaseOverTenPercentWarns() {
        let runs = [run(daysAgo: 1, km: 12.3), run(daysAgo: 3, km: 12.3),
                    run(daysAgo: 8, km: 10), run(daysAgo: 10, km: 10)]
        let result = insight(.weeklyDistanceChange, in: runs)
        #expect(result?.tone == .warning)
        #expect(result?.headline.contains("+23%") == true)
    }

    @Test("증가 폭이 10% 이내면 안정 판정")
    func weeklyIncreaseWithinTenPercentIsPositive() {
        let runs = [run(daysAgo: 1, km: 10.5), run(daysAgo: 8, km: 10)]
        #expect(insight(.weeklyDistanceChange, in: runs)?.tone == .positive)
    }

    @Test("기준 주 거리가 3km 미만이면 증가율을 내지 않는다")
    func weeklyChangeNeedsBaselineWeek() {
        let runs = [run(daysAgo: 1, km: 10), run(daysAgo: 8, km: 2)]
        #expect(insight(.weeklyDistanceChange, in: runs) == nil)
    }

    // MARK: ACWR

    @Test("급성 부하가 만성의 1.5배를 넘으면 부상 위험 경고")
    func acwrOverOnePointFiveWarns() {
        // 3주간 주 10km 유지 후 이번 주 20km — acute 20 ÷ chronic 12.5 = 1.6
        let runs = [run(daysAgo: 2, km: 10), run(daysAgo: 4, km: 10),
                    run(daysAgo: 10, km: 10),
                    run(daysAgo: 17, km: 10),
                    run(daysAgo: 24, km: 10)]
        let result = insight(.acwr, in: runs)
        #expect(result?.tone == .warning)
        #expect(result?.headline.contains("1.6배") == true)
    }

    @Test("부하가 4주 평균과 비슷하면 적정 판정")
    func acwrSteadyLoadIsPositive() {
        let runs = stride(from: 2.0, through: 26, by: 3).map { run(daysAgo: $0, km: 5) }
        let result = insight(.acwr, in: runs)
        #expect(result?.tone == .positive)
        #expect(result?.headline.contains("적정") == true)
    }

    @Test("기록이 3주 미만이면 ACWR을 내지 않는다")
    func acwrNeedsThreeWeeksOfHistory() {
        let runs = [run(daysAgo: 1, km: 10), run(daysAgo: 8, km: 10), run(daysAgo: 14, km: 10)]
        #expect(insight(.acwr, in: runs) == nil)
    }

    // MARK: 심박 효율

    @Test("같은 페이스에서 심박이 내려가면 체력 상승 판정")
    func efficiencyRiseIsPositive() {
        // 직전 2주 150bpm → 최근 2주 140bpm, 페이스 동일 = EF +7%
        let runs = [run(daysAgo: 1, km: 5, hr: 140), run(daysAgo: 3, km: 5, hr: 140),
                    run(daysAgo: 5, km: 5, hr: 140),
                    run(daysAgo: 15, km: 5, hr: 150), run(daysAgo: 17, km: 5, hr: 150),
                    run(daysAgo: 19, km: 5, hr: 150)]
        #expect(insight(.heartRateEfficiency, in: runs)?.tone == .positive)
    }

    @Test("각 2주 창에 표본 3개 미만이면 심박 효율을 내지 않는다")
    func efficiencyNeedsThreeRunsPerWindow() {
        let runs = [run(daysAgo: 1, km: 5), run(daysAgo: 3, km: 5), run(daysAgo: 5, km: 5),
                    run(daysAgo: 15, km: 5), run(daysAgo: 17, km: 5)]
        #expect(insight(.heartRateEfficiency, in: runs) == nil)
    }

    @Test("심박 없는 러닝은 효율 표본에서 제외된다")
    func efficiencySkipsRunsWithoutHeartRate() {
        let runs = [run(daysAgo: 1, km: 5, hr: nil), run(daysAgo: 3, km: 5, hr: nil),
                    run(daysAgo: 5, km: 5, hr: nil),
                    run(daysAgo: 15, km: 5), run(daysAgo: 17, km: 5), run(daysAgo: 19, km: 5)]
        #expect(insight(.heartRateEfficiency, in: runs) == nil)
    }

    // MARK: 공통

    @Test("데이터가 부족하면 어떤 지표도 내지 않는다")
    func insufficientDataYieldsNothing() {
        #expect(engine.insights(from: [run(daysAgo: 1, km: 5)]).isEmpty)
        #expect(engine.insights(from: []).isEmpty)
    }
}
