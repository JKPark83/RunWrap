import Foundation
import Testing
@testable import RunWrap

/// 온보딩 카드 설문 분기 검증 — 기획서 §2 분기 트리(문항 수 5~9)와 스킵 규칙.
/// 문항 시퀀스는 답변 상태로부터 계산하는 순수 함수라 화면 없이 검증할 수 있다.
@Suite("온보딩 설문 분기")
struct OnboardingFlowTests {
    /// 미지정 필드는 nil로 두는 기본 답변 빌더 — 각 테스트가 필요한 필드만 채운다
    private func answers(q1: OnboardingAnswers.Q1,
                         q2a: OnboardingAnswers.Q2A? = nil,
                         q2: OnboardingAnswers.Q2Longest? = nil,
                         q3: OnboardingAnswers.Q3Record? = nil,
                         q4: OnboardingAnswers.Q4Monthly? = nil,
                         q5: OnboardingAnswers.Q5Frequency? = nil,
                         q6: OnboardingAnswers.Q6Race? = nil,
                         q7: RaceDistance? = nil,
                         q8: Int? = nil,
                         q9: [RunPurpose] = []) -> OnboardingAnswers {
        OnboardingAnswers(q1Experience: q1, q2aActivity: q2a, q2Longest: q2, q3Record: q3,
                          q4Monthly: q4, q5Frequency: q5, q6Race: q6,
                          q7Target: q7, q8GoalSec: q8, q9Purposes: q9)
    }

    @Test("무경험 경로는 5문항 — Q1·Q2a·Q7·Q8·Q9")
    func noviceHasFiveSteps() {
        // 목표 거리를 골랐으므로 Q8이 붙는다 → Q1, Q2a, Q7, Q8, Q9 = 5문항 (기획서 §2)
        let steps = OnboardingFlowModel.steps(for: answers(q1: .novice, q2a: .walking, q7: .fiveK))
        #expect(steps == [.experience, .activity, .target, .goalTime, .purposes])
    }

    @Test("무경험자가 목표 거리를 안 정하면 Q8을 건너뛰어 4문항")
    func noviceWithoutTargetSkipsGoalTime() {
        let steps = OnboardingFlowModel.steps(for: answers(q1: .novice, q2a: .sedentary))
        #expect(steps == [.experience, .activity, .target, .purposes])
    }

    @Test("유경험 최대 경로는 9문항 — Q3·Q8이 모두 붙는 경우")
    func experiencedFullPathHasNineSteps() {
        // 5~10km는 Q3(10km 기록) 대상이고 목표 거리도 골랐다 → 9문항 (기획서 §2 "최대 9문항")
        let steps = OnboardingFlowModel.steps(for: answers(q1: .experienced, q2: .fiveToTen, q7: .half))
        #expect(steps.count == 9)
        #expect(steps == [.experience, .longest, .record, .monthly,
                          .frequency, .raceExperience, .target, .goalTime, .purposes])
    }

    @Test("Q2가 5km 미만이면 Q3를 건너뛴다 — 10km 기록을 물을 근거가 없다")
    func under5SkipsRecord() {
        let steps = OnboardingFlowModel.steps(for: answers(q1: .experienced, q2: .under5, q7: .fiveK))
        #expect(!steps.contains(.record))
        #expect(steps.count == 8)
    }

    @Test("Q2가 하프~풀이면 Q3를 건너뛴다 — 하프 완주는 그 자체로 최소 런잘알")
    func halfToFullSkipsRecord() {
        let steps = OnboardingFlowModel.steps(for: answers(q1: .experienced, q2: .halfToFull, q7: .full))
        #expect(!steps.contains(.record))
    }

    @Test("풀코스 완주자는 Q3를 묻는다 — 런친놈 조건① 판정에 풀 기록이 필요하다")
    func fullFinisherKeepsRecord() {
        let steps = OnboardingFlowModel.steps(for: answers(q1: .experienced, q2: .fullFinisher, q7: .full))
        #expect(steps.contains(.record))
    }

    @Test("Q7이 아직 없어요면 Q8을 건너뛴다")
    func noTargetSkipsGoalTime() {
        let steps = OnboardingFlowModel.steps(for: answers(q1: .experienced, q2: .tenToHalf))
        #expect(!steps.contains(.goalTime))
        #expect(steps.count == 8)
    }

    @Test("모든 분기에서 문항 수는 5~9 사이 — 기획서 §2 한도")
    func stepCountStaysInRange() {
        var combinations: [OnboardingAnswers] = []
        for q2 in OnboardingAnswers.Q2Longest.allCases {
            for q7 in RaceDistance.allCases.map(Optional.init) + [nil] {
                combinations.append(answers(q1: .experienced, q2: q2, q7: q7))
            }
        }
        for q7 in RaceDistance.allCases.map(Optional.init) + [nil] {
            combinations.append(answers(q1: .novice, q2a: .walking, q7: q7))
        }
        for combination in combinations {
            let count = OnboardingFlowModel.steps(for: combination).count
            #expect(count >= 4 && count <= 9)
        }
    }

    @Test("목표 기록 프리셋 — 종목별 개수와 값 (기획서 §2)")
    func presetsMatchSpec() {
        #expect(GoalPreset.presets(for: .fiveK).map(\.seconds) == [30 * 60, 25 * 60])
        #expect(GoalPreset.presets(for: .tenK).map(\.seconds) == [60 * 60, 50 * 60])
        #expect(GoalPreset.presets(for: .half).map(\.seconds) == [7_200, 6_600])
        // 풀은 시안 1c의 칩 4개 — "완주"(5:00)를 포함해 라벨이 겹치지 않는다
        let full = GoalPreset.presets(for: .full)
        #expect(full.map(\.label) == ["완주", "sub-5", "sub-4:30", "sub-4"])
        #expect(full[0].seconds == 5 * 3_600)
        #expect(Set(full.map(\.seconds)).count == full.count)
    }
}

/// 온보딩 저장 — 재진단이 성장 사이클을 보존하는지 (이슈 #44).
/// 설정의 "다시 진단받기"가 사이클을 새로 열어 XP가 0이 되던 회귀를 막는다.
/// now = 2026-09-29T09:00:00Z 고정, UserDefaults는 스위트별로 비우고 시작한다.
@MainActor
@Suite("온보딩 저장 — 재진단 사이클 보존")
struct OnboardingPersistTests {
    let now = ISO8601DateFormatter().date(from: "2026-09-29T09:00:00Z")!
    /// 재진단 전부터 키우던 사이클 — 8/1 시작, 4단계(fledgling), 고정 식별자
    let cycleStartedAt = ISO8601DateFormatter().date(from: "2026-08-01T00:00:00Z")!
    let onboardedAt = ISO8601DateFormatter().date(from: "2026-07-01T00:00:00Z")!
    let cycleID = "AAAAAAAA-0000-0000-0000-000000000001"

    /// 기존 사이클 값을 미리 넣어 둔 격리 UserDefaults
    private func seededDefaults(_ name: String) -> UserDefaults {
        let suite = "OnboardingPersistTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(cycleStartedAt.timeIntervalSince1970, forKey: GrowthKey.cycleStartedAt)
        defaults.set(GrowthStage.fledgling.rawValue, forKey: GrowthKey.maxStage)
        defaults.set(cycleID, forKey: GrowthKey.cycleID)
        defaults.set(onboardedAt.timeIntervalSince1970, forKey: ProfileKey.onboardedAt)
        return defaults
    }

    /// 재진단 답 — 주 4회 이상(Q5 fourPlus → 주간 목표 4회), 하프 목표 1:45:00(6_300초)
    private func model() -> OnboardingFlowModel {
        let model = OnboardingFlowModel()
        model.prefillIfNeeded(OnboardingAnswers(
            q1Experience: .experienced, q2aActivity: nil, q2Longest: .halfToFull, q3Record: nil,
            q4Monthly: .hundredTo200, q5Frequency: .fourPlus, q6Race: .finished,
            q7Target: .half, q8GoalSec: 6_300, q9Purposes: [.record]))
        return model
    }

    @Test("재진단 저장 — 사이클 시작·최고 단계·사이클 식별자·온보딩 시각을 보존하고 설문 답만 갱신한다")
    func rediagnosisKeepsCycle() {
        let defaults = seededDefaults("rediagnosis")
        let model = model()

        model.persist(isRediagnosis: true, now: now, defaults: defaults)

        // 사이클 키 3종 + 온보딩 시각: 미리 넣은 값 그대로
        #expect(defaults.double(forKey: GrowthKey.cycleStartedAt) == cycleStartedAt.timeIntervalSince1970)
        #expect(defaults.integer(forKey: GrowthKey.maxStage) == GrowthStage.fledgling.rawValue)
        #expect(defaults.string(forKey: GrowthKey.cycleID) == cycleID)
        #expect(defaults.double(forKey: ProfileKey.onboardedAt) == onboardedAt.timeIntervalSince1970)
        // 설문 답: 레벨은 LevelEngine 판정, 주간 목표는 Q5 fourPlus → 4회, 대회 목표는 Q7·Q8
        #expect(defaults.string(forKey: ProfileKey.levelV2) == LevelEngine.decide(model.answers).rawValue)
        #expect(defaults.integer(forKey: ProfileKey.weeklyGoal) == 4)
        #expect(defaults.string(forKey: ProfileKey.raceGoal) == RaceDistance.half.rawValue)
        #expect(defaults.integer(forKey: ProfileKey.raceGoalSec) == 6_300)
    }

    @Test("재진단 저장 — 주간 목표가 바뀌면 변경 시각과 이전 목표를 남긴다 (이슈 #108)")
    func rediagnosisRecordsWeeklyGoalChange() {
        let defaults = seededDefaults("rediagnosisGoalChange")
        defaults.set(2, forKey: ProfileKey.weeklyGoal)  // 재진단 전 주 2회

        model().persist(isRediagnosis: true, now: now, defaults: defaults)  // Q5 fourPlus → 4회

        #expect(defaults.integer(forKey: ProfileKey.weeklyGoal) == 4)
        #expect(defaults.double(forKey: ProfileKey.weeklyGoalChangedAt) == now.timeIntervalSince1970)
        #expect(defaults.integer(forKey: ProfileKey.weeklyGoalBefore) == 2)
    }

    @Test("재진단 저장 — 주간 목표가 그대로면 변경 기록을 남기지 않는다")
    func rediagnosisSameGoalLeavesNoRecord() {
        let defaults = seededDefaults("rediagnosisSameGoal")
        defaults.set(4, forKey: ProfileKey.weeklyGoal)

        model().persist(isRediagnosis: true, now: now, defaults: defaults)

        #expect(defaults.double(forKey: ProfileKey.weeklyGoalChangedAt) == 0)
    }

    @Test("첫 온보딩 저장 — 새 사이클을 연다: 시작=지금, 단계=알, 새 식별자, 온보딩 시각=지금")
    func firstOnboardingOpensNewCycle() throws {
        let defaults = seededDefaults("firstOnboarding")

        model().persist(isRediagnosis: false, now: now, defaults: defaults)

        #expect(defaults.double(forKey: GrowthKey.cycleStartedAt) == now.timeIntervalSince1970)
        #expect(defaults.integer(forKey: GrowthKey.maxStage) == GrowthStage.egg.rawValue)
        let newID = try #require(defaults.string(forKey: GrowthKey.cycleID))
        #expect(newID != cycleID)
        #expect(UUID(uuidString: newID) != nil)
        #expect(defaults.double(forKey: ProfileKey.onboardedAt) == now.timeIntervalSince1970)
    }
}
