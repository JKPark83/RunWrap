import SwiftUI

/// 온보딩 카드 설문 플로우 (기획서 v0.7 §2, 시안 1a~1e).
///
/// 카드 1장 = 질문 1개. 타이핑 없이 전부 탭으로 답하고, 마지막에 레벨을 발표하며 알을 준다.
/// **권한 요청은 설문 뒤로 옮겼다** — 맥락(내 레벨·내 알)을 만든 뒤 요청해야 수락률이 오르고,
/// 설문 자체는 자기 신고라 HealthKit이 필요 없기 때문이다 (기획서 §2 설계 원칙).
///
/// 답은 전부 로컬에만 남는다: 판정 결과는 `ProfileKey.*`(@AppStorage),
/// 원답은 `OnboardingAnswersStore`(재진단 프리필용). 네트워크 전송은 없다.
struct OnboardingFlowScreen: View {
    /// 재진단("다시 진단받기", §7) 진입 시 이전 답을 프리필한다. 첫 실행이면 nil
    var prefill: OnboardingAnswers?
    /// 플로우 완료(권한 요청까지) 후 호출 — 재진단 시트를 닫는 데 쓴다
    var onFinish: () -> Void = {}

    @EnvironmentObject private var health: HealthStore
    @EnvironmentObject private var backup: ProgressBackupStore
    @StateObject private var model = OnboardingFlowModel()

    var body: some View {
        Group {
            if model.isFinished {
                resultCard
            } else {
                questionCard
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RR.bg.ignoresSafeArea())
        .onAppear { model.prefillIfNeeded(prefill) }
    }

    // MARK: - 질문 카드 (시안 1a~1d)

    private var questionCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            OnboardingProgressBar(total: model.stepCount,
                                  index: model.stepIndex,
                                  canGoBack: model.canGoBack,
                                  onBack: model.goBack)

            switch model.step {
            case .goalTime: goalTimePage
            case .purposes: purposesPage
            default: choicePage
            }

            Spacer(minLength: 0)
        }
        // 카드는 위에서부터 쌓는다 — 이걸 빼면 VStack이 콘텐츠 높이로 줄어들어
        // 바깥 frame 기준 세로 가운데로 내려온다
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.top, 70)
        .padding(.horizontal, 26)
        .padding(.bottom, 34)
    }

    /// 단일 선택 문항 — 탭 즉시 다음 카드로 넘어간다 (CTA 없음)
    private var choicePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            QuestionIcon(systemName: model.step.iconName)
                .padding(.top, 34)

            Text(model.title)
                .font(RR.display(model.step.titleSize))
                .lineSpacing(model.step.titleSize * 0.35)
                .foregroundStyle(RR.text)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)

            if let subtitle = model.step.subtitle {
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(RR.text3)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }

            VStack(spacing: 10) {
                ForEach(model.choices, id: \.id) { choice in
                    OptionButton(label: choice.label,
                                 isSelected: model.isSelected(choice)) {
                        model.answer(choice)
                    }
                }
            }
            .padding(.top, 28)
        }
    }

    /// Q8 — 목표 기록 피커 (시안 1c).
    /// `.id`로 종목을 물려 두면 뒤로 가서 Q7을 바꿨을 때 휠 초기값이 새 종목 프리셋으로 다시 잡힌다
    /// (onAppear는 한 번만 도는데 종목이 바뀌면 프리셋·페이스 환산 기준 자체가 달라진다)
    private var goalTimePage: some View {
        let distance = model.answers.q7Target ?? .full
        return GoalTimePage(distance: distance,
                            initialSec: model.answers.q8GoalSec,
                            onConfirm: { model.answerGoalTime($0) },
                            onSkip: { model.answerGoalTime(nil) })
            .id(distance)
    }

    /// Q9 — 최대 2개 다중 선택이라 CTA가 필요하다 (시안 1d)
    private var purposesPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            QuestionIcon(systemName: "heart.fill")
                .padding(.top, 34)

            Text("달리는 이유, 뭐예요?")
                .font(RR.display(26))
                .foregroundStyle(RR.text)
                .padding(.top, 22)

            Text("최대 2개까지 골라도 돼요.")
                .font(.system(size: 13))
                .foregroundStyle(RR.text3)
                .padding(.top, 10)

            VStack(spacing: 10) {
                ForEach(RunPurpose.allCases, id: \.self) { purpose in
                    OptionButton(label: purpose.label,
                                 isSelected: model.answers.q9Purposes.contains(purpose)) {
                        model.togglePurpose(purpose)
                    }
                }
            }
            .padding(.top, 24)

            PrimaryButton(title: "다음") { model.finishSurvey() }
                .padding(.top, 22)
                .disabled(model.answers.q9Purposes.isEmpty)
                .opacity(model.answers.q9Purposes.isEmpty ? 0.4 : 1)
        }
    }

    // MARK: - 결과 카드 (시안 1e)

    private var resultCard: some View {
        VStack(spacing: 0) {
            Eyebrow(text: "진단 결과")

            Text(model.level.label)
                .font(RR.display(44))
                .lineSpacing(44 * 0.1)
                .foregroundStyle(RR.brand)
                .padding(.top, 14)

            Text(Self.levelCaption(model.level))
                .font(.system(size: 15))
                .foregroundStyle(RR.text2)
                .multilineTextAlignment(.center)
                .padding(.top, 10)

            BirdView(stage: .egg)
                .frame(width: 130, height: 130)
                .padding(.top, 26)

            Text("알이 도착했어요")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(RR.text)
                .padding(.top, 16)

            Text("달릴수록 부화가 가까워집니다. 정상은 아니지만 멋있을 예정입니다.")
                .font(.system(size: 14))
                .lineSpacing(14 * 0.6)
                .foregroundStyle(RR.text2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
                .padding(.top, 12)

            Spacer(minLength: 24)

            // CTA 문구는 "다음"으로 고정한다 — 권한 요청 직전 버튼이 허용을 권유하면
            // App Review 5.1.1(iv)에 걸린다("Continue"/"Next" 같은 중립 표현을 쓰라는 지적).
            // 요청 이유는 아래 캡션으로만 설명하고, 버튼은 다음 단계로 넘어간다는 뜻만 갖는다.
            PrimaryButton(title: "다음") {
                // 저장이 먼저, 권한 요청이 나중 — 권한 시트에서 이탈해도 진단 결과는 남는다
                model.persist(isRediagnosis: prefill != nil)
                Task {
                    await health.connect()
                    onFinish()
                }
                // 온보딩(첫 사이클) 확정 즉시 스냅샷을 올린다 — 다음 재설치부터 복원 가능 (이슈 #29)
                Task { await backup.backupIfChanged() }
            }

            Text("리포트를 만들려면 러닝 기록이 필요해서, 다음 화면에서 Apple 건강 읽기 권한을 물어봐요. 허용 여부는 직접 정하시면 됩니다.")
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
                .multilineTextAlignment(.center)
                .padding(.top, 12)
        }
        // maxHeight까지 채워야 위 Spacer가 벌어져 CTA가 화면 아래에 붙는다
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, 96)
        .padding(.horizontal, 30)
        .padding(.bottom, 34)
    }

    /// 레벨별 한 줄 설명 — 런린이 문구는 시안 1e verbatim.
    /// 런잘알·런친놈은 시안에 없어 §3의 리포트 초점(기록 향상 / 부하 관리)을 같은 목소리로 옮겼다
    private static func levelCaption(_ level: RunnerLevel) -> String {
        switch level {
        case .beginner: "완주와 습관부터, 같이 갑니다"
        case .intermediate: "기록을 당길 때가 됐네요, 같이 갑니다"
        case .advanced: "이제 관리가 실력입니다, 같이 갑니다"
        }
    }
}

// MARK: - 상태 관리

/// 설문 진행 상태 — 현재 문항·답변·분기 경로를 들고 있다.
/// 분기 때문에 문항 시퀀스가 답에 따라 달라지므로, "현재 답변 상태로부터 문항 목록을 계산"하는
/// 순수 함수(`steps(for:)`)를 두고 인덱스만 움직인다 — 시퀀스를 미리 고정하지 않는다.
@MainActor
final class OnboardingFlowModel: ObservableObject {
    @Published private(set) var answers = OnboardingAnswers(q1Experience: .experienced,
                                                            q9Purposes: [])
    /// 아직 Q1에 답하지 않은 상태 — Q1 답에 따라 전체 문항 수가 정해진다
    @Published private(set) var stepIndex = 0
    @Published private(set) var isFinished = false

    private var didPrefill = false

    /// 재진단 진입 시 이전 답을 채워 둔다. 진행 위치는 처음부터 (답만 프리필)
    func prefillIfNeeded(_ prefill: OnboardingAnswers?) {
        guard !didPrefill, let prefill else { return }
        didPrefill = true
        answers = prefill
    }

    /// 현재 답변 상태에서의 문항 시퀀스 — 답이 바뀌면 길이도 바뀐다 (기획서 §2 분기 트리)
    var steps: [OnboardingStep] { Self.steps(for: answers) }
    var stepCount: Int { steps.count }
    var step: OnboardingStep { steps.indices.contains(stepIndex) ? steps[stepIndex] : .purposes }
    var canGoBack: Bool { stepIndex > 0 }

    var level: RunnerLevel { LevelEngine.decide(answers) }

    /// 분기 규칙 (기획서 §2):
    /// - 무경험(Q1 = 이제 시작해요)은 Q2a → 공통(Q7~Q9)의 5문항
    /// - 유경험은 Q2 → (Q3) → Q4 → Q5 → Q6 → 공통의 최대 9문항
    /// - Q3는 Q2가 "5km 미만"이거나 "하프~풀"이면 건너뛴다.
    ///   "5km 미만"은 10km 기록을 물을 근거가 없고, "하프~풀" 완주자는 그 자체로 최소 런잘알이라
    ///   기록을 더 물을 필요가 없다(§3 "하프 이상 완주자는 최소 L2, 대신 Q3를 건너뛴다").
    ///   반대로 "풀코스 완주"는 런친놈 조건①(풀 4:30 이내) 판정에 기록이 필요해 계속 묻는다.
    /// - Q8은 Q7이 "아직 없어요"(nil)면 건너뛴다
    nonisolated static func steps(for answers: OnboardingAnswers) -> [OnboardingStep] {
        var result: [OnboardingStep] = [.experience]

        if answers.q1Experience == .novice {
            result.append(.activity)
        } else {
            result.append(.longest)
            if needsRecordQuestion(answers.q2Longest) { result.append(.record) }
            result.append(contentsOf: [.monthly, .frequency, .raceExperience])
        }

        result.append(.target)
        if answers.q7Target != nil { result.append(.goalTime) }
        result.append(.purposes)
        return result
    }

    /// Q3 노출 조건 — Q2가 아직 없으면 일단 노출(자리를 잡아 두고 답에 따라 사라진다)
    nonisolated private static func needsRecordQuestion(_ longest: OnboardingAnswers.Q2Longest?) -> Bool {
        switch longest {
        case .under5, .halfToFull: false
        case .fiveToTen, .tenToHalf, .fullFinisher: true
        case nil: true
        }
    }

    // MARK: 답변

    /// 단일 선택 답 — 답을 반영하고 즉시 다음 문항으로 넘어간다 (CTA 없음)
    func answer(_ choice: OnboardingChoice) {
        let answered = step
        apply(choice)
        advance(from: answered)
    }

    func isSelected(_ choice: OnboardingChoice) -> Bool {
        switch choice {
        case .experience(let value): answers.q1Experience == value
        case .activity(let value): answers.q2aActivity == value
        case .longest(let value): answers.q2Longest == value
        case .record(let value): answers.q3Record == value
        case .monthly(let value): answers.q4Monthly == value
        case .frequency(let value): answers.q5Frequency == value
        case .raceExperience(let value): answers.q6Race == value
        case .target(let value): answers.q7Target == value
        }
    }

    private func apply(_ choice: OnboardingChoice) {
        switch choice {
        case .experience(let value):
            answers.q1Experience = value
            // 분기가 바뀌면 반대편 경로의 답은 의미가 없어진다 — 지워서 판정 오염을 막는다
            if value == .novice {
                answers.q2Longest = nil
                answers.q3Record = nil
                answers.q4Monthly = nil
                answers.q5Frequency = nil
                answers.q6Race = nil
            } else {
                answers.q2aActivity = nil
            }
        case .activity(let value):
            answers.q2aActivity = value
        case .longest(let value):
            answers.q2Longest = value
            // Q2가 바뀌면 Q3 분기(풀 기록 / 10km 기록)도 바뀐다 — 이전 답을 지운다
            answers.q3Record = nil
        case .record(let value):
            answers.q3Record = value
        case .monthly(let value):
            answers.q4Monthly = value
        case .frequency(let value):
            answers.q5Frequency = value
        case .raceExperience(let value):
            answers.q6Race = value
        case .target(let value):
            answers.q7Target = value
            if value == nil { answers.q8GoalSec = nil }  // "아직 없어요"면 Q8도 건너뛴다
        }
    }

    /// Q8 — 피커 확정("이 기록으로 할게요") 또는 스킵("일단 완주부터요")
    func answerGoalTime(_ seconds: Int?) {
        answers.q8GoalSec = seconds
        advance(from: .goalTime)
    }

    /// Q9 — 최대 2개. 이미 2개를 골랐으면 더 담지 않는다 (기획서 §2)
    func togglePurpose(_ purpose: RunPurpose) {
        if let index = answers.q9Purposes.firstIndex(of: purpose) {
            answers.q9Purposes.remove(at: index)
        } else if answers.q9Purposes.count < 2 {
            answers.q9Purposes.append(purpose)
        }
    }

    func finishSurvey() {
        guard !answers.q9Purposes.isEmpty else { return }
        isFinished = true
    }

    /// 방금 답한 문항 **다음**으로 이동한다.
    ///
    /// 인덱스를 그냥 +1 하지 않는 이유: 답 하나로 시퀀스 자체가 바뀐다(Q2를 "하프~풀"로 고치면
    /// Q3가 사라지고, Q7을 "아직 없어요"로 고치면 Q8이 사라진다). 그래서 갱신된 시퀀스에서
    /// 방금 답한 문항의 위치를 다시 찾아 그 뒤로 넘어간다 — 인덱스가 아니라 문항이 기준이다.
    private func advance(from answered: OnboardingStep) {
        let updated = steps
        guard let position = updated.firstIndex(of: answered) else {
            isFinished = true
            return
        }
        if position + 1 < updated.count {
            stepIndex = position + 1
        } else {
            isFinished = true
        }
    }

    func goBack() {
        guard stepIndex > 0 else { return }
        stepIndex -= 1
    }

    // MARK: 저장

    /// 판정 결과·원답을 로컬에 저장한다.
    /// 재진단(`isRediagnosis`)이면 성장 사이클은 건드리지 않는다 — "성장은 되돌리지 않는다"(§5)
    func persist(isRediagnosis: Bool, now: Date = Date(), defaults: UserDefaults = .standard) {
        let decided = LevelEngine.decide(answers)
        defaults.set(decided.rawValue, forKey: ProfileKey.levelV2)
        defaults.set(RunPurpose.encode(answers.q9Purposes), forKey: ProfileKey.purposes)
        defaults.set(weeklyGoal, forKey: ProfileKey.weeklyGoal)
        defaults.set(answers.q7Target?.rawValue ?? "", forKey: ProfileKey.raceGoal)
        defaults.set(answers.q8GoalSec ?? 0, forKey: ProfileKey.raceGoalSec)
        defaults.set(now.timeIntervalSince1970, forKey: ProfileKey.onboardedAt)

        if !isRediagnosis {
            defaults.set(now.timeIntervalSince1970, forKey: GrowthKey.cycleStartedAt)
            defaults.set(GrowthStage.egg.rawValue, forKey: GrowthKey.maxStage)
            // 새 사이클 = 새 식별자 — CloudKit 스냅샷 병합의 사이클 경계 (이슈 #29)
            defaults.set(UUID().uuidString, forKey: GrowthKey.cycleID)
        }

        OnboardingAnswersStore.save(answers)
    }

    /// 주간 러닝 목표 초기값 — Q5의 1·3·4회. 무경험자는 Q5를 묻지 않으므로 기본 주 2회 (§2)
    private var weeklyGoal: Int {
        answers.q5Frequency?.weeklyGoal ?? 2
    }
}

// MARK: - 문항 정의

/// 설문 카드 한 장 — 화면이 그릴 문구·아이콘·선택지를 들고 있다
enum OnboardingStep: Hashable {
    case experience      // Q1
    case activity        // Q2a
    case longest         // Q2
    case record          // Q3
    case monthly         // Q4
    case frequency       // Q5
    case raceExperience  // Q6
    case target          // Q7
    case goalTime        // Q8
    case purposes        // Q9

    var title: String {
        switch self {
        case .experience: "러닝, 해보신 적 있나요?"
        case .activity: "요즘 몸은 좀 움직이고 계세요?"
        case .longest: "지금까지 가장 멀리 달려본 거리는요?"
        // Q3는 Q2 답(풀 완주 여부)에 따라 질문이 갈려 모델의 `title`이 덮어쓴다 — 여기 값은 폴백
        case .record: "10km는 얼마 만에 들어오세요?"
        case .monthly: "한 달에 보통 얼마나 달리세요?"
        case .frequency: "일주일에 몇 번 나가세요?"
        case .raceExperience: "대회는 나가보셨어요?"
        case .target: "다음 목표는 어떤 거리인가요?"
        case .goalTime: "목표 기록도 정해볼까요?"
        case .purposes: "달리는 이유, 뭐예요?"
        }
    }

    /// 시안 1a는 27px, 나머지 질문 카드는 26px
    var titleSize: CGFloat {
        self == .experience ? 27 : 26
    }

    var subtitle: String? {
        switch self {
        case .experience: "답에 따라 질문 수가 달라져요 — 5~9문항, 전부 탭이면 끝나요."
        case .purposes: "최대 2개까지 골라도 돼요."
        default: nil
        }
    }

    /// 시안의 온보딩 카드 글리프 4종(러너·경로·스톱워치·하트)을 SF Symbols로 대체 (시안 §10)
    var iconName: String {
        switch self {
        case .experience: "figure.run"
        case .activity: "figure.walk"
        case .longest, .target: "point.topleft.down.curvedto.point.bottomright.up"
        case .record, .goalTime: "stopwatch"
        case .monthly: "calendar"
        case .frequency: "repeat"
        case .raceExperience: "flag.checkered"
        case .purposes: "heart.fill"
        }
    }

    var choices: [OnboardingChoice] {
        switch self {
        case .experience:
            OnboardingAnswers.Q1.allCases.map { .experience($0) }
        case .activity:
            OnboardingAnswers.Q2A.allCases.map { .activity($0) }
        case .longest:
            OnboardingAnswers.Q2Longest.allCases.map { .longest($0) }
        case .record:
            // Q2 분기에 따라 모델의 `choices`가 덮어쓴다 — 여기 값은 10km 분기 폴백
            [.record(.tenUnder60), .record(.tenOver60), .record(.tenUnknown)]
        case .monthly:
            OnboardingAnswers.Q4Monthly.allCases.map { .monthly($0) }
        case .frequency:
            OnboardingAnswers.Q5Frequency.allCases.map { .frequency($0) }
        case .raceExperience:
            OnboardingAnswers.Q6Race.allCases.map { .raceExperience($0) }
        case .target:
            RaceDistance.allCases.map { OnboardingChoice.target($0) } + [.target(nil)]
        case .goalTime, .purposes:
            []
        }
    }
}

/// 선택지 하나 — 어떤 문항의 어떤 답인지를 함께 들고 다녀 화면이 분기 없이 넘길 수 있게 한다
enum OnboardingChoice: Hashable {
    case experience(OnboardingAnswers.Q1)
    case activity(OnboardingAnswers.Q2A)
    case longest(OnboardingAnswers.Q2Longest)
    case record(OnboardingAnswers.Q3Record)
    case monthly(OnboardingAnswers.Q4Monthly)
    case frequency(OnboardingAnswers.Q5Frequency)
    case raceExperience(OnboardingAnswers.Q6Race)
    /// Q7 — nil은 "아직 없어요"
    case target(RaceDistance?)

    var label: String {
        switch self {
        case .experience(let value): value.label
        case .activity(let value): value.label
        case .longest(let value): value.label
        case .record(let value): value.label
        case .monthly(let value): value.label
        case .frequency(let value): value.label
        case .raceExperience(let value): value.label
        case .target(let value): value?.label ?? "아직 없어요"
        }
    }

    var id: String {
        switch self {
        case .experience(let value): "q1.\(value.rawValue)"
        case .activity(let value): "q2a.\(value.rawValue)"
        case .longest(let value): "q2.\(value.rawValue)"
        case .record(let value): "q3.\(value.rawValue)"
        case .monthly(let value): "q4.\(value.rawValue)"
        case .frequency(let value): "q5.\(value.rawValue)"
        case .raceExperience(let value): "q6.\(value.rawValue)"
        case .target(let value): "q7.\(value?.rawValue ?? "none")"
        }
    }
}

extension OnboardingFlowModel {
    /// 현재 문항 제목 — Q3만 Q2 답(풀 완주 여부)에 따라 질문 자체가 갈린다 (기획서 §2)
    var title: String {
        guard step == .record else { return step.title }
        return answers.q2Longest == .fullFinisher
            ? "풀코스 기록이 어떻게 되세요?"
            : "10km는 얼마 만에 들어오세요?"
    }

    /// 현재 문항 선택지 — Q3만 분기라 여기서 만든다
    var choices: [OnboardingChoice] {
        guard step == .record else { return step.choices }
        let cases: [OnboardingAnswers.Q3Record] = answers.q2Longest == .fullFinisher
            ? [.fullUnder430, .full430to5, .fullOver5]
            : [.tenUnder60, .tenOver60, .tenUnknown]
        return cases.map { .record($0) }
    }
}

// MARK: - 공통 하위 뷰

/// 상단 진행 표시 — 좌 뒤로가기 / 가운데 진행 점 / 우 26pt 스페이서 (시안 온보딩 셸)
private struct OnboardingProgressBar: View {
    let total: Int
    let index: Int
    let canGoBack: Bool
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Group {
                if canGoBack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(RR.text3)
                    }
                } else {
                    // 첫 화면은 빈 자리표시 — 점 그룹이 좌우로 흔들리지 않게 한다.
                    // Color는 세로로도 무한 확장이라 높이를 반드시 묶어야 한다 —
                    // 안 묶으면 이 행이 화면 높이만큼 부풀어 아래 질문이 밀려 내려간다.
                    Color.clear.frame(height: 24)
                }
            }
            .frame(width: 26, alignment: .leading)

            HStack(spacing: 6) {
                ForEach(0..<total, id: \.self) { position in
                    if position == index {
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(RR.brand)
                            .frame(width: 18, height: 6)
                    } else if position < index {
                        Circle().fill(RR.brand).frame(width: 6, height: 6)
                    } else {
                        Circle().fill(RR.surface2)
                            .overlay(Circle().strokeBorder(RR.line))
                            .frame(width: 6, height: 6)
                    }
                }
            }
            .frame(maxWidth: .infinity)

            // 좌측 뒤로가기 자리와 대칭 — 여기도 높이를 묶어야 행이 부풀지 않는다
            Color.clear.frame(width: 26, height: 24)
        }
        .animation(.easeOut(duration: 0.2), value: index)
    }
}

/// 질문 카드 상단 아이콘 박스 — 58×58 radius 12 brandSoft (시안 1a·1b·1d)
private struct QuestionIcon: View {
    let systemName: String

    var body: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(RR.brandSoft)
            .frame(width: 58, height: 58)
            .overlay {
                Image(systemName: systemName)
                    .font(.system(size: 27, weight: .semibold))
                    .foregroundStyle(RR.brand)
            }
    }
}

/// 온보딩 선택지 버튼 — 선택 시 1.5px 브랜드 테두리 + brandSoft 배경 + 우측 체크 원 (시안)
private struct OptionButton: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(label)
                    .font(.system(size: 15.5, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                if isSelected {
                    Circle()
                        .fill(RR.brand)
                        .frame(width: 22, height: 22)
                        .overlay {
                            Image(systemName: "checkmark")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.white)
                        }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 15)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(isSelected ? RR.brandSoft : RR.surface,
                    in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
            .strokeBorder(isSelected ? RR.brand : RR.line, lineWidth: isSelected ? 1.5 : 1))
    }
}

/// 브랜드 채움 CTA — 시안 온보딩 primary 버튼(800 16px, radius 10, padding 16)
private struct PrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .heavy))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(RR.brand, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Q8 목표 기록 피커 (시안 1c)

/// 시:분 2휠 목표 기록 피커 — 설정의 시:분:초 3휠에서 초를 뺐다.
/// 목표 설정에 초 단위는 과하고, 실시간 페이스 환산이 주인공이라 휠을 하나 줄였다 (기획서 §2).
private struct GoalTimePage: View {
    let distance: RaceDistance
    let initialSec: Int?
    let onConfirm: (Int) -> Void
    let onSkip: () -> Void

    @State private var hour = 0
    @State private var minute = 0

    private var totalSec: Int { hour * 3_600 + minute * 60 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("목표 기록도 정해볼까요?")
                .font(RR.display(26))
                .foregroundStyle(RR.text)
                .padding(.top, 34)

            Eyebrow(text: eyebrowText)
                .padding(.top, 14)

            HStack(spacing: 8) {
                ForEach(Self.presets(for: distance), id: \.label) { preset in
                    presetChip(preset)
                }
            }
            .padding(.top, 16)

            wheels
                .padding(.top, 12)

            paceReadout
                .padding(.top, 8)

            PrimaryButton(title: "이 기록으로 할게요") { onConfirm(totalSec) }
                .padding(.top, 18)
                .disabled(totalSec == 0)
                .opacity(totalSec == 0 ? 0.4 : 1)

            Button(action: onSkip) {
                Text("일단 완주부터요")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.text3)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .onAppear {
            let seconds = initialSec ?? Self.presets(for: distance).first?.seconds ?? 0
            hour = seconds / 3_600
            minute = seconds % 3_600 / 60
        }
    }

    /// "풀코스 42.195km" — 시안 1c의 eyebrow. km는 소수점 이하 0이면 정수로 줄인다
    private var eyebrowText: String {
        let km = distance.km
        let kmText = km == km.rounded() ? String(format: "%.0f", km) : String(format: "%.3f", km)
        return "\(displayName) \(kmText)km"
    }

    /// 시안은 풀을 "풀코스"로 적는다 — RaceDistance.label("풀")보다 이 자리에선 또렷하다
    private var displayName: String {
        distance == .full ? "풀코스" : distance.label
    }

    private var wheels: some View {
        HStack(spacing: 0) {
            wheel(unit: "시간", range: 0..<8, value: $hour)
            wheel(unit: "분", range: 0..<60, value: $minute)
        }
        .frame(height: 132)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(RR.surface2)
                .frame(height: 54)
        )
    }

    private func wheel(unit: String, range: Range<Int>, value: Binding<Int>) -> some View {
        Picker(unit, selection: value) {
            ForEach(range, id: \.self) { Text("\($0)\(unit)").tag($0) }
        }
        .pickerStyle(.wheel)
        .frame(maxWidth: .infinity)
        .clipped()
    }

    /// 실시간 페이스 환산 — 휠을 돌리면 즉시 갱신된다 (기획서 §2)
    private var paceReadout: some View {
        HStack(spacing: 5) {
            Text("=")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.text3)
            Text("km당")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
            Text(totalSec > 0 ? Format.pace(Double(totalSec) / distance.km) : "—")
                .font(RR.numeral(17))
                .foregroundStyle(RR.text)
        }
        .frame(maxWidth: .infinity)
    }

    private func presetChip(_ preset: GoalPreset) -> some View {
        let isSelected = totalSec == preset.seconds
        return Button {
            hour = preset.seconds / 3_600
            minute = preset.seconds % 3_600 / 60
        } label: {
            Text(preset.label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(isSelected ? RR.brand : RR.text2)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(isSelected ? RR.brandSoft : RR.surface,
                    in: RoundedRectangle(cornerRadius: 999, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 999, style: .continuous)
            .strokeBorder(isSelected ? RR.brand : RR.line, lineWidth: isSelected ? 1.5 : 1))
    }

    private static func presets(for distance: RaceDistance) -> [GoalPreset] {
        GoalPreset.presets(for: distance)
    }
}

/// 목표 기록 프리셋 칩 하나 — 탭하면 휠이 그 값으로 이동한다 (기획서 §2)
struct GoalPreset: Equatable {
    let label: String
    let seconds: Int

    /// 종목별 프리셋 (기획서 §2) — 풀의 칩 문구는 시안 1c의 "완주 / sub-5 / sub-4:30 / sub-4"
    static func presets(for distance: RaceDistance) -> [GoalPreset] {
        switch distance {
        case .fiveK:
            [GoalPreset(label: "30분", seconds: 30 * 60),
             GoalPreset(label: "25분", seconds: 25 * 60)]
        case .tenK:
            [GoalPreset(label: "60분", seconds: 60 * 60),
             GoalPreset(label: "50분", seconds: 50 * 60)]
        case .half:
            [GoalPreset(label: "2:00", seconds: 2 * 3_600),
             GoalPreset(label: "1:50", seconds: 3_600 + 50 * 60)]
        case .full:
            // 시안 1c의 칩 4개. "완주"는 기획서 §2가 정한 5:00을 뜻하고,
            // "sub-5"는 그보다 1분 아래(4:59)로 두어 같은 값이 겹치지 않게 한다
            [GoalPreset(label: "완주", seconds: 5 * 3_600),
             GoalPreset(label: "sub-5", seconds: 4 * 3_600 + 59 * 60),
             GoalPreset(label: "sub-4:30", seconds: 4 * 3_600 + 29 * 60),
             GoalPreset(label: "sub-4", seconds: 3 * 3_600 + 59 * 60)]
        }
    }
}
