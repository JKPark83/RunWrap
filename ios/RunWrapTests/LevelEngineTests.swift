import Foundation
import Testing
@testable import RunWrap

/// 레벨 판정 엔진 검증 — 기획서 §3 결정표 5행 + 경계 케이스 + 실데이터 승급 후보 판정
@Suite("레벨 판정 엔진")
struct LevelEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    /// 미지정 필드는 nil로 두는 기본 답변 빌더 — 각 테스트가 필요한 필드만 채운다
    private func answers(q1: OnboardingAnswers.Q1,
                         q2: OnboardingAnswers.Q2Longest? = nil,
                         q3: OnboardingAnswers.Q3Record? = nil,
                         q4: OnboardingAnswers.Q4Monthly? = nil) -> OnboardingAnswers {
        OnboardingAnswers(q1Experience: q1, q2aActivity: nil, q2Longest: q2, q3Record: q3,
                          q4Monthly: q4, q5Frequency: nil, q6Race: nil, q7Target: nil,
                          q8GoalSec: nil, q9Purposes: [])
    }

    private func run(daysAgo: Double, km: Double, minPerKm: Double) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: nil)
    }

    // MARK: 판정 결정표 — 순서 1

    @Test("결정표 1행 — Q1 무경험이면 나머지 답과 무관하게 런린이")
    func rule1NoviceIsBeginner() {
        // Q2·Q3·Q4가 최상위 조건이어도 Q1이 무경험이면 무조건 런린이
        let a = answers(q1: .novice, q2: .fullFinisher, q3: .fullUnder430, q4: .over200)
        #expect(LevelEngine.decide(a) == .beginner)
    }

    // MARK: 판정 결정표 — 순서 2

    @Test("결정표 2행 — 풀코스 완주 AND 풀 4:30 이내 AND 월 200km 이상은 런친놈")
    func rule2AllThreeConditionsIsAdvanced() {
        let a = answers(q1: .experienced, q2: .fullFinisher, q3: .fullUnder430, q4: .over200)
        #expect(LevelEngine.decide(a) == .advanced)
    }

    @Test("2행 미충족(월 마일리지 부족)이면 3행으로 내려가 런잘알")
    func rule2FailsFallsThroughToRule3() {
        // 풀 완주 + 4:30 이내지만 월 200km 미만 — 2행 조건 3개 중 하나가 빠짐
        let a = answers(q1: .experienced, q2: .fullFinisher, q3: .fullUnder430, q4: .hundredTo200)
        #expect(LevelEngine.decide(a) == .intermediate)
    }

    // MARK: 판정 결정표 — 순서 3

    @Test("결정표 3행 — 하프~풀 완주(2행 미충족)는 런잘알")
    func rule3HalfToFullIsIntermediate() {
        let a = answers(q1: .experienced, q2: .halfToFull)
        #expect(LevelEngine.decide(a) == .intermediate)
    }

    @Test("결정표 3행 — 풀코스 완주만으로도(기록 정보 없어도) 런잘알")
    func rule3FullFinisherWithoutRecordIsIntermediate() {
        // Q3가 nil(하프~풀 완주자는 Q3 건너뜀)이어도 2행 조건 미충족이면 3행이 적용된다
        let a = answers(q1: .experienced, q2: .fullFinisher, q3: nil, q4: nil)
        #expect(LevelEngine.decide(a) == .intermediate)
    }

    // MARK: 판정 결정표 — 순서 4

    @Test("결정표 4행 — 10km 1시간 이내 기록은 런잘알")
    func rule4TenKUnderHourIsIntermediate() {
        let a = answers(q1: .experienced, q2: .fiveToTen, q3: .tenUnder60)
        #expect(LevelEngine.decide(a) == .intermediate)
    }

    // MARK: 판정 결정표 — 순서 5 (기본값)

    @Test("결정표 5행 — 나머지 전부는 런린이")
    func rule5EverythingElseIsBeginner() {
        let a = answers(q1: .experienced, q2: .under5, q3: nil)
        #expect(LevelEngine.decide(a) == .beginner)
    }

    @Test("경계 — 10km를 1시간 조금 넘게(60분 미충족)면 런린이로 하향")
    func boundaryTenKJustOverHourIsBeginner() {
        // "1시간 이내"가 아니므로 4행 미충족 — 애매하면 아래 레벨(미노출 가드와 같은 철학, §3)
        let a = answers(q1: .experienced, q2: .fiveToTen, q3: .tenOver60)
        #expect(LevelEngine.decide(a) == .beginner)
    }

    // MARK: 실데이터 승급 후보

    @Test("최근 4주 내 10km를 60분 이내로 달렸으면 런린이 → 런잘알 후보")
    func promotionCandidateWhenRecentFastTenK() {
        // 8일 전 10km를 55분(분당 5.5분 페이스)에 완주 — 4주 이내, 60분 이내 조건 충족
        let runs = [run(daysAgo: 8, km: 10, minPerKm: 5.5)]
        #expect(LevelEngine.promotionCandidate(current: .beginner, runs: runs, now: now) == .intermediate)
    }

    @Test("10km를 60분 넘게 달렸으면 승급 후보 아님")
    func noPromotionWhenTenKOverHour() {
        // 10km를 65분(분당 6.5분 페이스)에 완주 — 60분 조건 미충족
        let runs = [run(daysAgo: 8, km: 10, minPerKm: 6.5)]
        #expect(LevelEngine.promotionCandidate(current: .beginner, runs: runs, now: now) == nil)
    }

    @Test("빠른 10km 기록이 4주보다 오래됐으면 승급 후보 아님")
    func noPromotionWhenFastRunTooOld() {
        // 30일 전 — 4주(28일) 관측 창을 벗어난다
        let runs = [run(daysAgo: 30, km: 10, minPerKm: 5.5)]
        #expect(LevelEngine.promotionCandidate(current: .beginner, runs: runs, now: now) == nil)
    }

    @Test("이미 런친놈(최상위)이면 승급 후보를 내지 않는다")
    func noPromotionWhenAlreadyAdvanced() {
        let runs = [run(daysAgo: 8, km: 10, minPerKm: 5.5)]
        #expect(LevelEngine.promotionCandidate(current: .advanced, runs: runs, now: now) == nil)
    }
}
