import Foundation
import Testing
@testable import RunWrap

/// 리포트 레벨 게이트 검증 (기획서 v0.7 §4) — 노출 매트릭스와 가드 우선순위.
///
/// 핵심은 두 가지다: ① 매트릭스대로 레벨이 카드를 여닫는가 ②
/// **미노출 가드(엔진 nil)가 레벨 게이트보다 항상 위인가**. ②는 게이트가 통과해도
/// 엔진이 nil이면 그릴 게 없다는 뜻이라, 엔진 결과가 nil임을 함께 확인한다.
@Suite("리포트 레벨 게이트")
struct ReportGateTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func run(daysAgo: Double, km: Double,
                     minPerKm: Double = 6, hr: Double? = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }

    /// 4주 이상 꾸준히 달린 이력 — ACWR·EF 가드를 모두 통과하는 표본
    private var richRuns: [RunSummary] {
        (0..<28).map { day in
            run(daysAgo: Double(day), km: 5, hr: 150 - Double(day) * 0.3)
        }
    }

    // MARK: 매트릭스 — 런린이

    @Test("런린이는 부하 비율·심박 효율·심폐 체력·주법을 보지 않는다")
    func beginnerHidesAdvancedMetrics() {
        for card in [ReportCard.acwr, .efficiency, .vo2Max, .form] {
            #expect(ReportGate.shows(card, level: .beginner) == false)
        }
    }

    @Test("런린이도 거리·체력 배터리·크로스 트레이닝·훈련 가이드는 본다")
    func beginnerKeepsBasicCards() {
        for card in [ReportCard.distance, .battery, .crossTraining, .trainingGuide] {
            #expect(ReportGate.shows(card, level: .beginner))
        }
    }

    @Test("런린이의 주간 거리는 카드는 나오되 수치는 감춘다 — 문장만")
    func beginnerDistanceIsSentenceOnly() {
        #expect(ReportGate.shows(.distance, level: .beginner))
        #expect(ReportGate.showsNumbers(.distance, level: .beginner) == false)
    }

    // MARK: 매트릭스 — 런잘알 · 런친놈

    @Test("런잘알은 부하 비율·심박 효율·심폐 체력·주법을 전부 본다")
    func intermediateSeesAdvancedMetrics() {
        for card in [ReportCard.acwr, .efficiency, .vo2Max, .form] {
            #expect(ReportGate.shows(card, level: .intermediate))
        }
    }

    @Test("런친놈도 런잘알이 보는 카드를 전부 본다 — 승급으로 잃는 카드는 없다")
    func advancedIsSupersetOfIntermediate() {
        for card in ReportCard.allCases where ReportGate.shows(card, level: .intermediate) {
            #expect(ReportGate.shows(card, level: .advanced))
        }
    }

    @Test("런잘알·런친놈은 주간 거리 수치를 본다")
    func upperLevelsSeeDistanceNumbers() {
        #expect(ReportGate.showsNumbers(.distance, level: .intermediate))
        #expect(ReportGate.showsNumbers(.distance, level: .advanced))
    }

    // MARK: 걷뛰 카드 — 런린이 전용

    @Test("걷뛰 카드는 런린이만 본다")
    func walkRunIsBeginnerOnly() {
        #expect(ReportGate.shows(.walkRun, level: .beginner))
        #expect(ReportGate.shows(.walkRun, level: .intermediate) == false)
        #expect(ReportGate.shows(.walkRun, level: .advanced) == false)
    }

    // MARK: 가드 우선순위 — 미노출 가드가 레벨 게이트보다 위

    @Test("미노출 가드 우선 — 런잘알이어도 기록이 3주 미만이면 ACWR이 나오지 않는다")
    func sampleGuardBeatsLevelGateForAcwr() {
        // 게이트는 열려 있다
        #expect(ReportGate.shows(.acwr, level: .intermediate))
        // 하지만 엔진이 nil을 내면 그릴 게 없다 — 최근 10일치뿐이라 3주 가드에 걸린다
        let runs = [run(daysAgo: 1, km: 10), run(daysAgo: 5, km: 10), run(daysAgo: 9, km: 10)]
        #expect(ReportEngine(now: now, level: .intermediate).weeklyReport(from: runs).acwr == nil)
    }

    @Test("미노출 가드 우선 — 런친놈이어도 심박 표본이 부족하면 심박 효율이 나오지 않는다")
    func sampleGuardBeatsLevelGateForEfficiency() {
        #expect(ReportGate.shows(.efficiency, level: .advanced))
        // 각 2주 창에 심박 표본이 3개 미만 (창당 2회)
        let runs = [run(daysAgo: 1, km: 5), run(daysAgo: 3, km: 5),
                    run(daysAgo: 16, km: 5), run(daysAgo: 20, km: 5)]
        #expect(ReportEngine(now: now, level: .advanced).weeklyReport(from: runs).efficiency == nil)
    }

    @Test("미노출 가드 우선 — 주법 표본이 부족하면 레벨과 무관하게 nil")
    func sampleGuardBeatsLevelGateForForm() {
        #expect(ReportGate.shows(.form, level: .advanced))
        // 케이던스가 하나도 없는 표본 (RunSummary 기본값 nil)
        #expect(FormTrend.compute(runs: richRuns, now: now) == nil)
    }

    @Test("표본이 충분하면 런잘알은 실제로 ACWR·EF를 받아본다 — 게이트만 막고 있지 않다")
    func intermediateReceivesMetricsWhenSampleIsEnough() {
        let report = ReportEngine(now: now, level: .intermediate).weeklyReport(from: richRuns)
        #expect(report.acwr != nil)
        #expect(report.efficiency != nil)
    }
}

/// 걷뛰 처방 엔진 검증 — 주차 점증과 미노출 가드
@Suite("걷뛰 처방 엔진")
struct WalkRunEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func start(weeksAgo: Double) -> Date {
        now.addingTimeInterval(-weeksAgo * 7 * 86_400)
    }

    @Test("1주차는 걷기 4분 · 뛰기 1분 × 5세트 — 총 25분 고정")
    func firstWeekIsGentle() throws {
        let plan = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 0),
                                                   weeklyGoal: 3, runs: [], now: now))
        #expect(plan.week == 1)
        #expect(plan.walkMinutes == 4)
        #expect(plan.runMinutes == 1)
        #expect(plan.sets == 5)   // 25분 ÷ (4+1)분
    }

    @Test("3주차는 시안과 같은 걷기 2분 · 뛰기 3분 × 5세트")
    func thirdWeekMatchesDesign() throws {
        let plan = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 2),
                                                   weeklyGoal: 3, runs: [], now: now))
        #expect(plan.week == 3)
        #expect(plan.headline == "걷기 2분 · 뛰기 3분 × 5세트")
        #expect(plan.weekBadge == "걷뛰 3주차")
    }

    @Test("주차가 오를수록 뛰기 시간이 늘고 걷기 시간은 줄어든다")
    func runTimeIncreasesMonotonically() throws {
        var previousRun = 0.0
        var previousWalk = Double.infinity
        // 1 → 8주차 순으로 훑는다 (weeksAgo가 클수록 주차가 크다)
        for weeksAgo in 0..<8 {
            let plan = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: Double(weeksAgo)),
                                                       weeklyGoal: 3, runs: [], now: now))
            #expect(plan.week == weeksAgo + 1)
            if weeksAgo > 0 {
                #expect(plan.runMinutes >= previousRun)
                #expect(plan.walkMinutes <= previousWalk)
            }
            previousRun = plan.runMinutes
            previousWalk = plan.walkMinutes
        }
    }

    @Test("8주차를 넘어가도 마지막 단계를 유지한다 — 더 밀어붙이지 않는다")
    func ladderPlateausAfterLastStep() throws {
        let eighth = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 7),
                                                     weeklyGoal: 3, runs: [], now: now))
        let twentieth = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 19),
                                                        weeklyGoal: 3, runs: [], now: now))
        #expect(twentieth.week == 20)
        #expect(twentieth.walkMinutes == eighth.walkMinutes)
        #expect(twentieth.runMinutes == eighth.runMinutes)
    }

    @Test("미노출 가드 — 사이클 시작 시각을 모르면 처방을 내지 않는다")
    func missingCycleStartYieldsNil() {
        #expect(WalkRunEngine.plan(cycleStartedAt: nil, weeklyGoal: 3, runs: [], now: now) == nil)
    }

    @Test("미노출 가드 — 주간 목표가 0이면 진행률 분모가 없어 처방을 내지 않는다")
    func zeroWeeklyGoalYieldsNil() {
        #expect(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 2), weeklyGoal: 0,
                                   runs: [], now: now) == nil)
    }

    @Test("이번 달력 주의 러닝만 진행 횟수로 센다")
    func countsOnlyThisCalendarWeek() throws {
        // now = 2026-08-13(목) → ISO 주는 8/10(월)~8/16(일)
        let thisWeek = RunSummary(id: UUID(), start: now.addingTimeInterval(-86_400),
                                  durationSec: 1_800, distanceMeters: 5_000, avgHeartRate: nil)
        let lastWeek = RunSummary(id: UUID(), start: now.addingTimeInterval(-8 * 86_400),
                                  durationSec: 1_800, distanceMeters: 5_000, avgHeartRate: nil)
        let plan = try #require(WalkRunEngine.plan(cycleStartedAt: start(weeksAgo: 2),
                                                   weeklyGoal: 3,
                                                   runs: [thisWeek, lastWeek], now: now))
        #expect(plan.doneThisWeek == 1)
        #expect(plan.progressLine == "이번 주 1 / 3회 했어요")
    }
}

/// 문장 톤 3단계 검증 (기획서 §4 "문장 톤" 행) — 레벨 게이트의 문구 쪽 짝
@Suite("리포트 문장 톤 3단계")
struct ReportVoiceTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func run(daysAgo: Double, km: Double, hr: Double? = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * 6 * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }

    private func headline(_ kind: Insight.Kind, level: RunnerLevel,
                          runs: [RunSummary]) -> String? {
        ReportEngine(now: now, level: level).insights(from: runs)
            .first { $0.kind == kind }?.headline
    }

    @Test("레벨은 문장 톤 3단계로 매핑된다")
    func levelMapsToVoice() {
        #expect(ReportVoice(level: .beginner) == .plain)
        #expect(ReportVoice(level: .intermediate) == .standard)
        #expect(ReportVoice(level: .advanced) == .compact)
    }

    @Test("주간 거리 문장이 레벨 3단계로 전부 갈린다")
    func weeklyDistanceHeadlineDiffersByLevel() throws {
        // 최근 7일 10.5km · 이전 7일 10km → +5%, 안정 구간
        let runs = [run(daysAgo: 1, km: 10.5), run(daysAgo: 8, km: 10)]
        let plain = try #require(headline(.weeklyDistanceChange, level: .beginner, runs: runs))
        let standard = try #require(headline(.weeklyDistanceChange, level: .intermediate, runs: runs))
        let compact = try #require(headline(.weeklyDistanceChange, level: .advanced, runs: runs))
        #expect(plain != standard)
        #expect(standard != compact)
        #expect(plain != compact)
        // 런친놈은 수치 중심 — 지표명과 값이 함께 나온다
        #expect(compact.contains("10.5"))
    }

    @Test("런친놈의 ACWR 문장에는 구간명이 함께 붙는다 — §4 '수치+구간'")
    func advancedAcwrShowsBand() throws {
        // 3주 이상 이력 + 주 10km 유지 → ACWR 1.0 언저리(적정 구간)
        let runs = (0..<28).map { run(daysAgo: Double($0), km: 2.5) }
        let compact = try #require(headline(.acwr, level: .advanced, runs: runs))
        #expect(compact.hasPrefix("ACWR "))
        #expect(compact.contains("적정 구간"))
    }

    @Test("런린이의 ACWR 문장에는 지표 약어(ACWR)가 나오지 않는다 — 용어 풀어쓰기")
    func beginnerAcwrAvoidsJargon() throws {
        let runs = (0..<28).map { run(daysAgo: Double($0), km: 2.5) }
        let plain = try #require(headline(.acwr, level: .beginner, runs: runs))
        #expect(plain.contains("ACWR") == false)
    }

    @Test("런친놈의 심박 효율 문장은 EF 값을 앞세운다")
    func advancedEfficiencyLeadsWithEF() throws {
        // 최근 2주는 심박이 낮아 EF가 오른다 — 각 창에 표본 3개 이상
        let recent = (0..<5).map { run(daysAgo: Double($0) * 2 + 1, km: 5, hr: 140) }
        let previous = (0..<5).map { run(daysAgo: Double($0) * 2 + 15, km: 5, hr: 155) }
        let compact = try #require(headline(.heartRateEfficiency, level: .advanced,
                                            runs: recent + previous))
        #expect(compact.hasPrefix("EF "))
    }
}
