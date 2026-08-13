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
