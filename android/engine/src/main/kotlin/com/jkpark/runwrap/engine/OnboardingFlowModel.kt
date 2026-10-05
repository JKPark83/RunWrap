package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/// iOS OnboardingFlowScreen.swift의 순수부 — 설문 진행 상태(`OnboardingFlowModel`)·문항 정의·목표 기록 프리셋.
/// 화면(카드·진행 표시·피커)은 :app이 그리고, 화면 상태 알림(@Published)도 :app ViewModel이 이 모델을 감싸 맡는다.

// MARK: - 상태 관리

/// 설문 진행 상태 — 현재 문항·답변·분기 경로를 들고 있다.
/// 분기 때문에 문항 시퀀스가 답에 따라 달라지므로, "현재 답변 상태로부터 문항 목록을 계산"하는
/// 순수 함수(`steps(for:)`)를 두고 인덱스만 움직인다 — 시퀀스를 미리 고정하지 않는다.
class OnboardingFlowModel {
    var answers = OnboardingAnswers(q1Experience = OnboardingAnswers.Q1.experienced,
                                    q9Purposes = emptyList())
        private set
    /// 아직 Q1에 답하지 않은 상태 — Q1 답에 따라 전체 문항 수가 정해진다
    var stepIndex = 0
        private set
    var isFinished = false
        private set

    private var didPrefill = false

    /// 재진단 진입 시 이전 답을 채워 둔다. 진행 위치는 처음부터 (답만 프리필)
    /// (Android: Swift 구조체 대입은 복사라 같은 의미가 되도록 copy해 둔다)
    fun prefillIfNeeded(prefill: OnboardingAnswers?) {
        if (didPrefill || prefill == null) return
        didPrefill = true
        answers = prefill.copy()
    }

    /// 현재 답변 상태에서의 문항 시퀀스 — 답이 바뀌면 길이도 바뀐다 (기획서 §2 분기 트리)
    val steps: List<OnboardingStep> get() = steps(answers)
    val stepCount: Int get() = steps.size
    val step: OnboardingStep get() = steps.getOrNull(stepIndex) ?: OnboardingStep.purposes
    val canGoBack: Boolean get() = stepIndex > 0

    val level: RunnerLevel get() = LevelEngine.decide(answers)

    // MARK: 답변

    /// 단일 선택 답 — 답을 반영하고 즉시 다음 문항으로 넘어간다 (CTA 없음)
    fun answer(choice: OnboardingChoice) {
        val answered = step
        apply(choice)
        advance(answered)
    }

    fun isSelected(choice: OnboardingChoice): Boolean = when (choice) {
        is OnboardingChoice.experience -> answers.q1Experience == choice.value
        is OnboardingChoice.activity -> answers.q2aActivity == choice.value
        is OnboardingChoice.longest -> answers.q2Longest == choice.value
        is OnboardingChoice.record -> answers.q3Record == choice.value
        is OnboardingChoice.monthly -> answers.q4Monthly == choice.value
        is OnboardingChoice.frequency -> answers.q5Frequency == choice.value
        is OnboardingChoice.raceExperience -> answers.q6Race == choice.value
        is OnboardingChoice.target -> answers.q7Target == choice.value
    }

    private fun apply(choice: OnboardingChoice) {
        when (choice) {
            is OnboardingChoice.experience -> {
                answers.q1Experience = choice.value
                // 분기가 바뀌면 반대편 경로의 답은 의미가 없어진다 — 지워서 판정 오염을 막는다
                if (choice.value == OnboardingAnswers.Q1.novice) {
                    answers.q2Longest = null
                    answers.q3Record = null
                    answers.q4Monthly = null
                    answers.q5Frequency = null
                    answers.q6Race = null
                } else {
                    answers.q2aActivity = null
                }
            }
            is OnboardingChoice.activity ->
                answers.q2aActivity = choice.value
            is OnboardingChoice.longest -> {
                answers.q2Longest = choice.value
                // Q2가 바뀌면 Q3 분기(풀 기록 / 10km 기록)도 바뀐다 — 이전 답을 지운다
                answers.q3Record = null
            }
            is OnboardingChoice.record ->
                answers.q3Record = choice.value
            is OnboardingChoice.monthly ->
                answers.q4Monthly = choice.value
            is OnboardingChoice.frequency ->
                answers.q5Frequency = choice.value
            is OnboardingChoice.raceExperience ->
                answers.q6Race = choice.value
            is OnboardingChoice.target -> {
                // 종목이 바뀌면 이전 종목 기준 목표 기록은 무의미하다 — 지워서 Q8이 새 종목 프리셋으로 다시 잡히게 한다
                if (choice.value != answers.q7Target) answers.q8GoalSec = null
                answers.q7Target = choice.value
                if (choice.value == null) answers.q8GoalSec = null  // "아직 없어요"면 Q8도 건너뛴다
            }
        }
    }

    /// Q8 — 피커 확정("이 기록으로 할게요") 또는 스킵("일단 완주부터요")
    fun answerGoalTime(seconds: Int?) {
        answers.q8GoalSec = seconds
        advance(OnboardingStep.goalTime)
    }

    /// Q9 — 최대 2개. 이미 2개를 골랐으면 더 담지 않는다 (기획서 §2)
    fun togglePurpose(purpose: RunPurpose) {
        if (purpose in answers.q9Purposes) {
            answers.q9Purposes = answers.q9Purposes - purpose  // 첫 번째 하나만 뺀다 (Swift remove(at: firstIndex))
        } else if (answers.q9Purposes.size < 2) {
            answers.q9Purposes = answers.q9Purposes + purpose
        }
    }

    fun finishSurvey() {
        if (answers.q9Purposes.isEmpty()) return
        isFinished = true
    }

    /// 방금 답한 문항 **다음**으로 이동한다.
    ///
    /// 인덱스를 그냥 +1 하지 않는 이유: 답 하나로 시퀀스 자체가 바뀐다(Q2를 "하프~풀"로 고치면
    /// Q3가 사라지고, Q7을 "아직 없어요"로 고치면 Q8이 사라진다). 그래서 갱신된 시퀀스에서
    /// 방금 답한 문항의 위치를 다시 찾아 그 뒤로 넘어간다 — 인덱스가 아니라 문항이 기준이다.
    private fun advance(answered: OnboardingStep) {
        val updated = steps
        val position = updated.indexOf(answered)
        if (position < 0) {
            isFinished = true
            return
        }
        if (position + 1 < updated.size) {
            stepIndex = position + 1
        } else {
            isFinished = true
        }
    }

    fun goBack() {
        if (stepIndex <= 0) return
        stepIndex -= 1
    }

    // MARK: 저장

    /// 판정 결과·원답을 로컬에 저장한다.
    /// 재진단(`isRediagnosis`)이면 성장 사이클은 건드리지 않는다 — "성장은 되돌리지 않는다"(§5).
    /// 설문 답(레벨·목적·주간 목표·대회 목표)은 갱신하고, 사이클 키(시작 시각·최고 단계·식별자·사이클 목표)와 온보딩 시각은 보존한다.
    /// 온보딩 시각까지 지키는 이유: 사이클 시작 시각이 없는 구버전 사용자는 홈·리포트가
    /// 온보딩 시각을 사이클 시작으로 대신 쓰므로, 이걸 덮으면 XP가 0이 된다 (이슈 #44)
    fun persist(isRediagnosis: Boolean, now: Instant, zone: ZoneId, defaults: KeyValueStore) {
        val decided = LevelEngine.decide(answers)
        defaults.set(ProfileKey.levelV2, decided.rawValue)
        defaults.set(ProfileKey.purposes, RunPurpose.encode(answers.q9Purposes))
        // 재진단으로 주간 목표가 바뀌면 변경 이력에 남긴다 — 설정 화면의 onChange에 기대지 않고
        // 여기서 직접 기록해 과거 주가 새 목표로 소급 판정되는 일을 막는다 (이슈 #108, #116)
        val oldGoal = defaults.int(ProfileKey.weeklyGoal)
        if (isRediagnosis && oldGoal > 0 && oldGoal != weeklyGoal) {
            val history = WeeklyGoalChangeLog.load(defaults)
            WeeklyGoalChangeLog.save(GrowthEngine.recordWeeklyGoalChange(history, oldGoal, now, zone), defaults)
        }
        defaults.set(ProfileKey.weeklyGoal, weeklyGoal)
        defaults.set(ProfileKey.raceGoal, answers.q7Target?.rawValue ?: "")
        defaults.set(ProfileKey.raceGoalSec, answers.q8GoalSec ?: 0)

        if (!isRediagnosis) {
            defaults.set(ProfileKey.onboardedAt, now.timeIntervalSince1970)
            defaults.set(GrowthKey.cycleStartedAt, now.timeIntervalSince1970)
            defaults.set(GrowthKey.maxStage, GrowthStage.egg.rawValue)
            // 첫 사이클의 목표를 고정한다 — 새 종류는 이 값으로 판정한다 (이슈 #110)
            defaults.set(GrowthKey.cycleGoal, answers.q7Target?.rawValue ?: "")
            defaults.set(GrowthKey.cycleGoalSec, answers.q8GoalSec ?: 0)
            // 새 사이클 = 새 식별자 — CloudKit 스냅샷 병합의 사이클 경계 (이슈 #29)
            defaults.set(GrowthKey.cycleID, UUID.randomUUID().toString().uppercase())
        }
        // 레벨·목표·사이클 모두 스냅샷 내용이다 — 병합 기준 시각을 지금으로 (이슈 #130)
        ProgressSnapshot.markLocalChanged(defaults, now)
    }

    /// 주간 러닝 목표 초기값 — Q5의 1·3·4회. 무경험자는 Q5를 묻지 않으므로 기본 주 2회 (§2)
    private val weeklyGoal: Int
        get() = answers.q5Frequency?.weeklyGoal ?: 2

    /// 현재 문항 제목 — Q3만 Q2 답(풀 완주 여부)에 따라 질문 자체가 갈린다 (기획서 §2)
    val title: String
        get() {
            if (step != OnboardingStep.record) return step.title
            return if (answers.q2Longest == OnboardingAnswers.Q2Longest.fullFinisher)
                "풀코스 기록이 어떻게 되세요?"
            else "10km는 얼마 만에 들어오세요?"
        }

    /// 현재 문항 선택지 — Q3만 분기라 여기서 만든다
    val choices: List<OnboardingChoice>
        get() {
            if (step != OnboardingStep.record) return step.choices
            val cases = if (answers.q2Longest == OnboardingAnswers.Q2Longest.fullFinisher)
                listOf(OnboardingAnswers.Q3Record.fullUnder430, OnboardingAnswers.Q3Record.full430to5,
                       OnboardingAnswers.Q3Record.fullOver5)
            else listOf(OnboardingAnswers.Q3Record.tenUnder60, OnboardingAnswers.Q3Record.tenOver60,
                        OnboardingAnswers.Q3Record.tenUnknown)
            return cases.map { OnboardingChoice.record(it) }
        }

    companion object {
        /// 분기 규칙 (기획서 §2):
        /// - 무경험(Q1 = 이제 시작해요)은 Q2a → 공통(Q7~Q9)의 5문항
        /// - 유경험은 Q2 → (Q3) → Q4 → Q5 → Q6 → 공통의 최대 9문항
        /// - Q3는 Q2가 "5km 미만"이거나 "하프~풀"이면 건너뛴다.
        ///   "5km 미만"은 10km 기록을 물을 근거가 없고, "하프~풀" 완주자는 그 자체로 최소 런잘알이라
        ///   기록을 더 물을 필요가 없다(§3 "하프 이상 완주자는 최소 L2, 대신 Q3를 건너뛴다").
        ///   반대로 "풀코스 완주"는 런친놈 조건①(풀 4:30 이내) 판정에 기록이 필요해 계속 묻는다.
        /// - Q8은 Q7이 "아직 없어요"(nil)면 건너뛴다
        fun steps(answers: OnboardingAnswers): List<OnboardingStep> {
            val result = mutableListOf(OnboardingStep.experience)

            if (answers.q1Experience == OnboardingAnswers.Q1.novice) {
                result.add(OnboardingStep.activity)
            } else {
                result.add(OnboardingStep.longest)
                if (needsRecordQuestion(answers.q2Longest)) result.add(OnboardingStep.record)
                result.addAll(listOf(OnboardingStep.monthly, OnboardingStep.frequency, OnboardingStep.raceExperience))
            }

            result.add(OnboardingStep.target)
            if (answers.q7Target != null) result.add(OnboardingStep.goalTime)
            result.add(OnboardingStep.purposes)
            return result
        }

        /// Q3 노출 조건 — Q2가 아직 없으면 일단 노출(자리를 잡아 두고 답에 따라 사라진다)
        private fun needsRecordQuestion(longest: OnboardingAnswers.Q2Longest?): Boolean = when (longest) {
            OnboardingAnswers.Q2Longest.under5, OnboardingAnswers.Q2Longest.halfToFull -> false
            OnboardingAnswers.Q2Longest.fiveToTen, OnboardingAnswers.Q2Longest.tenToHalf,
            OnboardingAnswers.Q2Longest.fullFinisher -> true
            null -> true
        }
    }
}

// MARK: - 문항 정의

/// 설문 카드 한 장 — 화면이 그릴 문구·아이콘·선택지를 들고 있다
/// (Android: SF Symbols 이름(`iconName`)은 화면 자산이라 :app이 정한다)
enum class OnboardingStep {
    experience,      // Q1
    activity,        // Q2a
    longest,         // Q2
    record,          // Q3
    monthly,         // Q4
    frequency,       // Q5
    raceExperience,  // Q6
    target,          // Q7
    goalTime,        // Q8
    purposes;        // Q9

    val title: String
        get() = when (this) {
            experience -> "러닝, 해보신 적 있나요?"
            activity -> "요즘 몸은 좀 움직이고 계세요?"
            longest -> "지금까지 가장 멀리 달려본 거리는요?"
            // Q3는 Q2 답(풀 완주 여부)에 따라 질문이 갈려 모델의 `title`이 덮어쓴다 — 여기 값은 폴백
            record -> "10km는 얼마 만에 들어오세요?"
            monthly -> "한 달에 보통 얼마나 달리세요?"
            frequency -> "일주일에 몇 번 나가세요?"
            raceExperience -> "대회는 나가보셨어요?"
            target -> "다음 목표는 어떤 거리인가요?"
            goalTime -> "목표 기록도 정해볼까요?"
            purposes -> "달리는 이유, 뭐예요?"
        }

    /// 시안 1a는 27px, 나머지 질문 카드는 26px
    val titleSize: Int
        get() = if (this == experience) 27 else 26

    val subtitle: String?
        get() = when (this) {
            experience -> "답에 따라 질문 수가 달라져요 — 5~9문항, 전부 탭이면 끝나요."
            purposes -> "최대 2개까지 골라도 돼요."
            else -> null
        }

    val choices: List<OnboardingChoice>
        get() = when (this) {
            experience ->
                OnboardingAnswers.Q1.entries.map { OnboardingChoice.experience(it) }
            activity ->
                OnboardingAnswers.Q2A.entries.map { OnboardingChoice.activity(it) }
            longest ->
                OnboardingAnswers.Q2Longest.entries.map { OnboardingChoice.longest(it) }
            record ->
                // Q2 분기에 따라 모델의 `choices`가 덮어쓴다 — 여기 값은 10km 분기 폴백
                listOf(OnboardingChoice.record(OnboardingAnswers.Q3Record.tenUnder60),
                       OnboardingChoice.record(OnboardingAnswers.Q3Record.tenOver60),
                       OnboardingChoice.record(OnboardingAnswers.Q3Record.tenUnknown))
            monthly ->
                OnboardingAnswers.Q4Monthly.entries.map { OnboardingChoice.monthly(it) }
            frequency ->
                OnboardingAnswers.Q5Frequency.entries.map { OnboardingChoice.frequency(it) }
            raceExperience ->
                OnboardingAnswers.Q6Race.entries.map { OnboardingChoice.raceExperience(it) }
            target ->
                RaceDistance.entries.map { OnboardingChoice.target(it) } + OnboardingChoice.target(null)
            goalTime, purposes ->
                emptyList()
        }
}

/// 선택지 하나 — 어떤 문항의 어떤 답인지를 함께 들고 다녀 화면이 분기 없이 넘길 수 있게 한다
sealed interface OnboardingChoice {
    data class experience(val value: OnboardingAnswers.Q1) : OnboardingChoice
    data class activity(val value: OnboardingAnswers.Q2A) : OnboardingChoice
    data class longest(val value: OnboardingAnswers.Q2Longest) : OnboardingChoice
    data class record(val value: OnboardingAnswers.Q3Record) : OnboardingChoice
    data class monthly(val value: OnboardingAnswers.Q4Monthly) : OnboardingChoice
    data class frequency(val value: OnboardingAnswers.Q5Frequency) : OnboardingChoice
    data class raceExperience(val value: OnboardingAnswers.Q6Race) : OnboardingChoice
    /// Q7 — nil은 "아직 없어요"
    data class target(val value: RaceDistance?) : OnboardingChoice

    val label: String
        get() = when (this) {
            is experience -> value.label
            is activity -> value.label
            is longest -> value.label
            is record -> value.label
            is monthly -> value.label
            is frequency -> value.label
            is raceExperience -> value.label
            is target -> value?.label ?: "아직 없어요"
        }

    val id: String
        get() = when (this) {
            is experience -> "q1.${value.rawValue}"
            is activity -> "q2a.${value.rawValue}"
            is longest -> "q2.${value.rawValue}"
            is record -> "q3.${value.rawValue}"
            is monthly -> "q4.${value.rawValue}"
            is frequency -> "q5.${value.rawValue}"
            is raceExperience -> "q6.${value.rawValue}"
            is target -> "q7.${value?.rawValue ?: "none"}"
        }
}

/// 목표 기록 프리셋 칩 하나 — 탭하면 휠이 그 값으로 이동한다 (기획서 §2)
data class GoalPreset(val label: String, val seconds: Int) {
    companion object {
        /// 종목별 프리셋 (기획서 §2) — 풀의 칩 문구는 시안 1c의 "완주 / sub-5 / sub-4:30 / sub-4"
        fun presets(distance: RaceDistance): List<GoalPreset> = when (distance) {
            RaceDistance.fiveK ->
                listOf(GoalPreset(label = "30분", seconds = 30 * 60),
                       GoalPreset(label = "25분", seconds = 25 * 60))
            RaceDistance.tenK ->
                listOf(GoalPreset(label = "60분", seconds = 60 * 60),
                       GoalPreset(label = "50분", seconds = 50 * 60))
            RaceDistance.half ->
                listOf(GoalPreset(label = "2:00", seconds = 2 * 3_600),
                       GoalPreset(label = "1:50", seconds = 3_600 + 50 * 60))
            RaceDistance.full ->
                // 시안 1c의 칩 4개. "완주"는 기획서 §2가 정한 5:00을 뜻하고,
                // "sub-5"는 그보다 1분 아래(4:59)로 두어 같은 값이 겹치지 않게 한다
                listOf(GoalPreset(label = "완주", seconds = 5 * 3_600),
                       GoalPreset(label = "sub-5", seconds = 4 * 3_600 + 59 * 60),
                       GoalPreset(label = "sub-4:30", seconds = 4 * 3_600 + 29 * 60),
                       GoalPreset(label = "sub-4", seconds = 3 * 3_600 + 59 * 60))
        }
    }
}
