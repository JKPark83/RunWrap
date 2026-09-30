import Foundation
import Testing
@testable import RunWrap

/// 훈련 부하 추세 검증 (이슈 #177) — TRIMP 산식·미노출 가드·CTL/ATL 지수 가중 평균·
/// TSB 전날 잔고 규칙·추세 점·폼 구간 경계.
@Suite("훈련 부하 추세")
struct TrainingLoadEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    /// 달력 일 경계를 고정하기 위해 UTC 그레고리력을 주입한다
    let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// HRmax 190(직접 입력) · 안정 심박 50 — 평균 150bpm → HRr = 100 / 140
    let profile = HeartRateProfile(hrMax: 190, hrMaxSource: .manual,
                                   restingHR: 50, zoneMethod: .percentMax)

    /// daysAgo일 전 08:00Z(now보다 1시간 이른 시각) 러닝 — 같은 UTC 달력 일에 떨어진다
    private func run(daysAgo: Double, minutes: Double = 60, hr: Double? = 150) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400 - 3_600),
                   durationSec: minutes * 60, distanceMeters: 10_000, avgHeartRate: hr)
    }

    /// 평균 150bpm으로 TRIMP가 정확히 `trimp`가 되는 러닝 시간(분) —
    /// 분당 TRIMP = HRr × 0.64 × e^(1.92 × HRr), HRr = 100/140 → 약 1.8016 (100이면 약 55.5분)
    private func minutes(forTrimp trimp: Double) -> Double {
        let hrr = 100.0 / 140
        return trimp / (hrr * 0.64 * exp(1.92 * hrr))
    }

    private func compute(_ runs: [RunSummary]) -> TrainingLoad? {
        TrainingLoadEngine.compute(runs: runs, profile: profile, now: now, calendar: utc)
    }

    // MARK: TRIMP 산식

    @Test("TRIMP — 60분·평균 150bpm(HRmax 190·안정 50)이면 약 108.1")
    func trimpFormula() throws {
        // HRr = 100/140 = 0.7143 → 60 × 0.7143 × 0.64 × e^(1.3714 = 3.9407) ≈ 108.10
        let trimp = try #require(TrainingLoadEngine.trimp(run: run(daysAgo: 1), profile: profile))
        #expect(abs(trimp - 108.1) < 0.5)
    }

    @Test("TRIMP — 평균 심박이 없거나 HRmax가 190 폴백이면 nil")
    func trimpNilWithoutEvidence() {
        #expect(TrainingLoadEngine.trimp(run: run(daysAgo: 1, hr: nil), profile: profile) == nil)
        let fallback = HeartRateProfile(hrMax: 190, hrMaxSource: .fallback,
                                        restingHR: 50, zoneMethod: .percentMax)
        #expect(TrainingLoadEngine.trimp(run: run(daysAgo: 1), profile: fallback) == nil)
        #expect(TrainingLoadEngine.trimp(run: run(daysAgo: 1, minutes: 0), profile: profile) == nil)
    }

    @Test("TRIMP — 평균 심박이 안정 심박 이하면 HRr 0으로 잘려 0")
    func trimpClampsAtRest() {
        #expect(TrainingLoadEngine.trimp(run: run(daysAgo: 1, hr: 45), profile: profile) == 0)
    }

    @Test("TRIMP — 안정 심박이 없으면 60bpm으로 폴백")
    func trimpRestingFallback() throws {
        let noRest = HeartRateProfile(hrMax: 190, hrMaxSource: .manual,
                                      restingHR: nil, zoneMethod: .percentMax)
        // HRr = 90/130 = 0.6923 → 60 × 0.6923 × 0.64 × e^(1.3292 = 3.7778) ≈ 100.44
        let trimp = try #require(TrainingLoadEngine.trimp(run: run(daysAgo: 1), profile: noRest))
        #expect(abs(trimp - 100.44) < 0.5)
    }

    // MARK: 미노출 가드

    @Test("이력 가드 — 가장 오래된 심박 세션이 41일 전이면 nil, 43일 전이면 값")
    func historyGuard() throws {
        // 41·36·…·1일 전 9회 — 42일 창 표본은 충분하지만 이력이 42일에 못 미친다
        let recent = stride(from: 41.0, through: 1, by: -5).map { run(daysAgo: $0) }
        #expect(compute(recent) == nil)

        let result = try #require(compute(recent + [run(daysAgo: 43)]))
        // 43일 전 세션은 42일 창 밖이라 표본에는 안 센다
        #expect(result.sessionCount == 9)
    }

    @Test("표본 가드 — 최근 42일 세션 7회면 nil, 8회면 값")
    func sessionCountGuard() throws {
        let old = run(daysAgo: 50)  // 이력 가드용 — 창 밖
        let seven = (1...7).map { run(daysAgo: Double($0)) }
        #expect(compute([old] + seven) == nil)

        let result = try #require(compute([old] + seven + [run(daysAgo: 8)]))
        #expect(result.sessionCount == 8)
    }

    @Test("표본 가드 — 평균 심박 없는 세션은 세지 않는다")
    func sessionsWithoutHeartRateDoNotCount() {
        let old = run(daysAgo: 50)
        let seven = (1...7).map { run(daysAgo: Double($0)) }
        #expect(compute([old] + seven + [run(daysAgo: 8, hr: nil)]) == nil)
    }

    // MARK: 지수 가중 평균

    @Test("EWMA — 매일 TRIMP 100을 60일 연속이면 CTL ≈ 76.4, ATL ≈ 100.0")
    func ewmaSteadyLoad() throws {
        let perDay = minutes(forTrimp: 100)
        let runs = (0..<60).map { run(daysAgo: Double($0), minutes: perDay) }
        let result = try #require(compute(runs))
        // 초기값 0에서 매일 t=100 → n일째 CTL = 100 × (1 − (41/42)^n)
        // 60일째: 100 × (1 − 0.2355) ≈ 76.45 · ATL: 100 × (1 − (6/7)^60) ≈ 99.99
        #expect(abs(result.ctl - 100 * (1 - pow(41.0 / 42.0, 60.0))) < 0.5)
        #expect(abs(result.ctl - 76.4) < 0.5)
        #expect(abs(result.atl - 99.99) < 0.5)
    }

    @Test("EWMA — 오늘 세션이 없으면 오늘은 t=0으로 감쇠한다")
    func ewmaDecaysOnRestDay() throws {
        let perDay = minutes(forTrimp: 100)
        let runs = (1...60).map { run(daysAgo: Double($0), minutes: perDay) }
        let result = try #require(compute(runs))
        // 어제까지 60일 누적 뒤 오늘 t=0: CTL × 41/42, ATL × 6/7
        let ctlYesterday = 100 * (1 - pow(41.0 / 42.0, 60.0))
        let atlYesterday = 100 * (1 - pow(6.0 / 7.0, 60.0))
        #expect(abs(result.ctl - ctlYesterday * (41.0 / 42)) < 0.01)
        #expect(abs(result.atl - atlYesterday * (6.0 / 7)) < 0.01)
    }

    // MARK: TSB · 추세 점

    @Test("TSB — 전날 잔고를 쓴다: 오늘 큰 세션은 오늘 CTL·ATL만 올리고 TSB는 그대로")
    func tsbUsesPreviousDay() throws {
        let perDay = minutes(forTrimp: 100)
        let base = (1...60).map { run(daysAgo: Double($0), minutes: perDay) }
        let rest = try #require(compute(base))
        let hard = try #require(compute(base + [run(daysAgo: 0, minutes: 180)]))

        #expect(hard.ctl > rest.ctl)
        #expect(hard.atl > rest.atl)
        #expect(abs(hard.tsb - rest.tsb) < 1e-9)
        // 오늘 TSB = 어제 점의 CTL − ATL
        let yesterday = hard.points[hard.points.count - 2]
        #expect(abs(hard.tsb - (yesterday.ctl - yesterday.atl)) < 1e-9)
    }

    @Test("추세 점 — 29개, 오래된 → 최신, 하루 간격 자정, 각 점 TSB는 전날 잔고")
    func trendPoints() throws {
        let perDay = minutes(forTrimp: 100)
        let result = try #require(compute((0..<60).map { run(daysAgo: Double($0), minutes: perDay) }))

        #expect(result.points.count == 29)
        #expect(result.points.last?.day == utc.startOfDay(for: now))
        #expect(result.points.first?.day == utc.date(byAdding: .day, value: -28,
                                                    to: utc.startOfDay(for: now)))
        for (prev, point) in zip(result.points, result.points.dropFirst()) {
            #expect(point.day == utc.startOfDay(for: point.day))
            #expect(point.day.timeIntervalSince(prev.day) == 86_400)
            #expect(abs(point.tsb - (prev.ctl - prev.atl)) < 1e-9)
        }
        #expect(result.ctl == result.points.last?.ctl)
        #expect(result.tsb == result.points.last?.tsb)
    }

    // MARK: 폼 구간 경계

    @Test("폼 구간 경계 — −30 미만 과부하 · −10 미만 체력 쌓는 중 · +5 이하 유지 · +25 이하 가벼움 · 그 위 훈련 부족")
    func bandBoundaries() {
        let cases: [(Double, TrainingLoad.Band, RRTone)] = [
            (-31, .overload, .overload),
            (-30, .productive, .improving),   // −30 정확히는 과부하가 아니다
            (-11, .productive, .improving),
            (-10, .maintain, .steady),        // −10 정확히는 유지
            (5, .maintain, .steady),          // +5 정확히는 유지
            (6, .fresh, .steady),
            (25, .fresh, .steady),            // +25 정확히는 가벼움
            (26, .detraining, .caution),
        ]
        for (tsb, band, tone) in cases {
            let actual = TrainingLoadEngine.band(tsb: tsb)
            #expect(actual == band, "TSB \(tsb)")
            #expect(TrainingLoadEngine.tone(actual) == tone, "TSB \(tsb)")
        }
    }
}
