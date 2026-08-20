package com.jkpark.runwrap.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.jkpark.runwrap.engine.GrowthStage
import com.jkpark.runwrap.engine.LevelEngine
import com.jkpark.runwrap.engine.OnboardingAnswers
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RunPurpose
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.health.HealthPermissions
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.ui.BirdView
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.NumberWheel
import com.jkpark.runwrap.ui.theme.Eyebrow
import com.jkpark.runwrap.ui.theme.RR
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

/// 온보딩 카드 설문 플로우 (기획서 §2, 시안 1a~1e) — iOS `OnboardingFlowScreen` 이식.
///
/// 카드 1장 = 질문 1개. 타이핑 없이 전부 탭으로 답하고, 마지막에 레벨을 발표하며 알을 준다.
/// 권한 요청은 설문 뒤다 — 맥락(내 레벨·내 알)을 만든 뒤 요청해야 수락률이 오르고,
/// 설문 자체는 자기 신고라 헬스 커넥트가 필요 없기 때문이다 (기획서 §2 설계 원칙).
///
/// iOS와의 차이 (계획서 M5):
/// - **생년 입력 단계 추가** — HC에는 생년월일 프로필 타입이 없어 HRmax(Tanaka) 재료를
///   직접 묻는다 (계획서 §2.2). 스킵 가능 — 스킵하면 기본 HRmax 190 추정을 쓴다.
/// - 결과 카드 캡션이 Apple 건강 대신 헬스 커넥트·삼성헬스 연동 안내다.
/// - 원답 영속화(iOS OnboardingAnswersStore)는 이식하지 않았다 — iOS도 재진단 프리필을
///   쓰지 않아(설정 화면의 의도) Android에는 소비자가 없다.
///
/// 답은 전부 로컬(DataStore)에만 남는다. 네트워크 전송은 없다.
@Composable
fun OnboardingFlowScreen(
    settings: SettingsStore,
    /// 재진단("다시 진단", §7) 진입이면 true — 성장 사이클은 되돌리지 않는다 (§5).
    /// iOS는 prefill 유무로 판별하지만 프리필을 쓰지 않아 사실상 항상 리셋된다 —
    /// 여기서는 persist 문서의 의도대로 명시 플래그를 쓴다.
    isRediagnosis: Boolean = false,
    /// 플로우 완료(권한 요청까지) 후 호출 — 재진단 시트를 닫거나 루트 분기를 푸는 데 쓴다
    onFinish: () -> Unit = {},
) {
    val model = remember { OnboardingModel() }
    val scope = rememberCoroutineScope()

    // HC 권한 시트 — 허용/거부와 무관하게 플로우를 마친다 (거부해도 빈 결과 안내로 이어진다)
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        scope.launch {
            settings.setDidConnectHealth(true)
            onFinish()
        }
    }

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        if (model.isFinished) {
            ResultCard(level = model.level) {
                // 저장이 먼저, 권한 요청이 나중 — 권한 시트에서 이탈해도 진단 결과는 남는다
                scope.launch {
                    model.persist(settings, isRediagnosis)
                    // HC가 없는 기기에서 시트를 못 띄워도 플로우는 마친다 (루트가 unavailable 안내)
                    runCatching { permissionLauncher.launch(HealthPermissions.standard) }
                        .onFailure { onFinish() }
                }
            }
        } else {
            QuestionCard(model)
        }
    }
}

// MARK: - 질문 카드 (시안 1a~1d)

@Composable
private fun QuestionCard(model: OnboardingModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 70.dp, bottom = 34.dp)
            .padding(horizontal = 26.dp),
    ) {
        ProgressBar(
            total = model.stepCount, index = model.stepIndex,
            canGoBack = model.canGoBack, onBack = model::goBack,
        )
        when (model.step) {
            // `key`로 종목을 물려 두면 뒤로 가서 Q7을 바꿨을 때 휠 초기값이
            // 새 종목 프리셋으로 다시 잡힌다 (iOS `.id(distance)` 대응)
            Step.GOAL_TIME -> key(model.answers.q7Target) {
                GoalTimePage(
                    distance = model.answers.q7Target ?: RaceDistance.FULL,
                    initialSec = model.answers.q8GoalSec,
                    onConfirm = { model.answerGoalTime(it) },
                    onSkip = { model.answerGoalTime(null) },
                )
            }
            Step.PURPOSES -> PurposesPage(model)
            Step.BIRTH_YEAR -> BirthYearPage(
                initialYear = model.birthYear,
                onConfirm = { model.finishSurvey(it) },
                onSkip = { model.finishSurvey(0) },
            )
            else -> ChoicePage(model)
        }
    }
}

/// 단일 선택 문항 — 탭 즉시 다음 카드로 넘어간다 (CTA 없음)
@Composable
private fun ChoicePage(model: OnboardingModel) {
    QuestionIcon(model.step.emoji, Modifier.padding(top = 34.dp))
    Text(
        model.title,
        style = RR.display(model.step.titleSize.sp),
        lineHeight = (model.step.titleSize * 1.35).sp,
        color = RR.text,
        modifier = Modifier.padding(top = 22.dp),
    )
    model.step.subtitle?.let {
        Text(it, fontSize = 13.sp, color = RR.text3, modifier = Modifier.padding(top = 10.dp))
    }
    Column(
        Modifier.padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        model.choices.forEach { choice ->
            OptionButton(choice.label, model.isSelected(choice)) { model.answer(choice) }
        }
    }
}

/// Q9 — 최대 2개 다중 선택이라 CTA가 필요하다 (시안 1d)
@Composable
private fun PurposesPage(model: OnboardingModel) {
    QuestionIcon("❤️", Modifier.padding(top = 34.dp))
    Text(
        "달리는 이유, 뭐예요?",
        style = RR.display(26.sp), color = RR.text,
        modifier = Modifier.padding(top = 22.dp),
    )
    Text(
        "최대 2개까지 골라도 돼요.",
        fontSize = 13.sp, color = RR.text3,
        modifier = Modifier.padding(top = 10.dp),
    )
    Column(
        Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RunPurpose.entries.forEach { purpose ->
            OptionButton(purpose.label, purpose in model.answers.q9Purposes) {
                model.togglePurpose(purpose)
            }
        }
    }
    PrimaryButton(
        "다음",
        enabled = model.answers.q9Purposes.isNotEmpty(),
        modifier = Modifier.padding(top = 22.dp),
    ) { model.finishPurposes() }
}

/// 생년 입력 — Android 추가 단계 (계획서 §2.2).
/// HC에는 생년월일이 없어 최대 심박(Tanaka 208 − 0.7×나이) 재료를 직접 묻는다.
@Composable
private fun BirthYearPage(initialYear: Int, onConfirm: (Int) -> Unit, onSkip: () -> Unit) {
    val maxYear = remember { LocalDate.now(SEOUL).year - 10 }
    var year by remember { mutableIntStateOf(if (initialYear > 0) initialYear else 1990) }

    QuestionIcon("🎂", Modifier.padding(top = 34.dp))
    Text(
        "태어난 해가 언제세요?",
        style = RR.display(26.sp), color = RR.text,
        modifier = Modifier.padding(top = 22.dp),
    )
    Text(
        "최대 심박(HRmax) 추정에만 써요. 기기 밖으로 나가지 않습니다.",
        fontSize = 13.sp, color = RR.text3,
        modifier = Modifier.padding(top = 10.dp),
    )
    WheelBand(Modifier.padding(top = 24.dp)) {
        NumberWheel("년", 1930..maxYear, year, { year = it }, Modifier.fillMaxWidth())
    }
    PrimaryButton("다음", modifier = Modifier.padding(top = 18.dp)) { onConfirm(year) }
    SkipButton("나중에 할게요", onSkip)
}

// MARK: - 결과 카드 (시안 1e)

@Composable
private fun ResultCard(level: RunnerLevel, onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 96.dp, bottom = 34.dp)
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("진단 결과")
        Text(
            level.label,
            style = RR.display(44.sp), color = RR.brand,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            levelCaption(level),
            fontSize = 15.sp, color = RR.text2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        BirdView(GrowthStage.EGG, modifier = Modifier.padding(top = 26.dp).size(130.dp))
        Text(
            "알이 도착했어요",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RR.text,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "달릴수록 부화가 가까워집니다. 정상은 아니지만 멋있을 예정입니다.",
            fontSize = 14.sp, lineHeight = 22.sp, color = RR.text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 300.dp),
        )
        Spacer(Modifier.weight(1f))
        // CTA 문구는 "다음" 고정 — 권한 요청 직전 버튼이 허용을 권유하면 심사 지적 대상이다
        // (iOS App Review 5.1.1(iv) 대응과 같은 원칙). 요청 이유는 아래 캡션으로만 설명한다.
        PrimaryButton("다음", onClick = onNext)
        Text(
            "리포트를 만들려면 러닝 기록이 필요해서, 다음 화면에서 헬스 커넥트 읽기 권한을 물어봐요. " +
                "허용 여부는 직접 정하시면 됩니다. 삼성헬스 기록은 삼성헬스 앱에서 " +
                "'헬스 커넥트와 연동'을 켜야 넘어와요.",
            fontSize = 11.5.sp, lineHeight = 16.sp, color = RR.text3,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/// 레벨별 한 줄 설명 — 런린이 문구는 시안 1e verbatim (iOS 동일)
private fun levelCaption(level: RunnerLevel): String = when (level) {
    RunnerLevel.BEGINNER -> "완주와 습관부터, 같이 갑니다"
    RunnerLevel.INTERMEDIATE -> "기록을 당길 때가 됐네요, 같이 갑니다"
    RunnerLevel.ADVANCED -> "이제 관리가 실력입니다, 같이 갑니다"
}

// MARK: - 상태 관리

/// 설문 진행 상태 — 현재 문항·답변·분기 경로를 들고 있다 (iOS `OnboardingFlowModel` 이식).
/// 분기 때문에 문항 시퀀스가 답에 따라 달라지므로, "현재 답변 상태로부터 문항 목록을 계산"하는
/// 순수 함수(`stepsFor`)를 두고 인덱스만 움직인다 — 시퀀스를 미리 고정하지 않는다.
private class OnboardingModel {
    /// 아직 Q1에 답하지 않은 상태 — Q1 답에 따라 전체 문항 수가 정해진다
    var answers by mutableStateOf(
        OnboardingAnswers(q1Experience = OnboardingAnswers.Q1.EXPERIENCED)
    )
        private set
    var stepIndex by mutableIntStateOf(0)
        private set
    var isFinished by mutableStateOf(false)
        private set

    /// 생년 (Android 추가) — 0이면 입력 안 함
    var birthYear by mutableIntStateOf(0)
        private set

    val steps: List<Step> get() = stepsFor(answers)
    val stepCount: Int get() = steps.size
    val step: Step get() = steps.getOrElse(stepIndex) { Step.PURPOSES }
    val canGoBack: Boolean get() = stepIndex > 0
    val level: RunnerLevel get() = LevelEngine.decide(answers)

    /// 현재 문항 제목 — Q3만 Q2 답(풀 완주 여부)에 따라 질문 자체가 갈린다 (기획서 §2)
    val title: String
        get() = when {
            step != Step.RECORD -> step.title
            answers.q2Longest == OnboardingAnswers.Q2Longest.FULL_FINISHER ->
                "풀코스 기록이 어떻게 되세요?"
            else -> "10km는 얼마 만에 들어오세요?"
        }

    /// 현재 문항 선택지 — Q3만 분기라 여기서 만든다
    val choices: List<Choice>
        get() {
            if (step != Step.RECORD) return step.choices
            val cases = if (answers.q2Longest == OnboardingAnswers.Q2Longest.FULL_FINISHER) {
                listOf(
                    OnboardingAnswers.Q3Record.FULL_UNDER_430,
                    OnboardingAnswers.Q3Record.FULL_430_TO_5,
                    OnboardingAnswers.Q3Record.FULL_OVER_5,
                )
            } else {
                listOf(
                    OnboardingAnswers.Q3Record.TEN_UNDER_60,
                    OnboardingAnswers.Q3Record.TEN_OVER_60,
                    OnboardingAnswers.Q3Record.TEN_UNKNOWN,
                )
            }
            return cases.map { Choice.Record(it) }
        }

    /// 단일 선택 답 — 답을 반영하고 즉시 다음 문항으로 넘어간다 (CTA 없음)
    fun answer(choice: Choice) {
        val answered = step
        apply(choice)
        advance(answered)
    }

    fun isSelected(choice: Choice): Boolean = when (choice) {
        is Choice.Experience -> answers.q1Experience == choice.value
        is Choice.Activity -> answers.q2aActivity == choice.value
        is Choice.Longest -> answers.q2Longest == choice.value
        is Choice.Record -> answers.q3Record == choice.value
        is Choice.Monthly -> answers.q4Monthly == choice.value
        is Choice.Frequency -> answers.q5Frequency == choice.value
        is Choice.RaceExperience -> answers.q6Race == choice.value
        is Choice.Target -> answers.q7Target == choice.value
    }

    private fun apply(choice: Choice) {
        answers = when (choice) {
            is Choice.Experience ->
                // 분기가 바뀌면 반대편 경로의 답은 의미가 없어진다 — 지워서 판정 오염을 막는다
                if (choice.value == OnboardingAnswers.Q1.NOVICE) {
                    answers.copy(
                        q1Experience = choice.value,
                        q2Longest = null, q3Record = null,
                        q4Monthly = null, q5Frequency = null, q6Race = null,
                    )
                } else {
                    answers.copy(q1Experience = choice.value, q2aActivity = null)
                }
            is Choice.Activity -> answers.copy(q2aActivity = choice.value)
            // Q2가 바뀌면 Q3 분기(풀 기록 / 10km 기록)도 바뀐다 — 이전 답을 지운다
            is Choice.Longest -> answers.copy(q2Longest = choice.value, q3Record = null)
            is Choice.Record -> answers.copy(q3Record = choice.value)
            is Choice.Monthly -> answers.copy(q4Monthly = choice.value)
            is Choice.Frequency -> answers.copy(q5Frequency = choice.value)
            is Choice.RaceExperience -> answers.copy(q6Race = choice.value)
            is Choice.Target -> answers.copy(
                q7Target = choice.value,
                // "아직 없어요"면 Q8도 건너뛴다
                q8GoalSec = if (choice.value == null) null else answers.q8GoalSec,
            )
        }
    }

    /// Q8 — 피커 확정("이 기록으로 할게요") 또는 스킵("일단 완주부터요")
    fun answerGoalTime(seconds: Int?) {
        answers = answers.copy(q8GoalSec = seconds)
        advance(Step.GOAL_TIME)
    }

    /// Q9 — 최대 2개. 이미 2개를 골랐으면 더 담지 않는다 (기획서 §2)
    fun togglePurpose(purpose: RunPurpose) {
        val current = answers.q9Purposes
        answers = answers.copy(
            q9Purposes = when {
                purpose in current -> current - purpose
                current.size < 2 -> current + purpose
                else -> current
            }
        )
    }

    /// Q9 CTA — iOS는 여기서 설문이 끝나지만 Android는 생년 단계가 하나 더 있다
    fun finishPurposes() {
        if (answers.q9Purposes.isNotEmpty()) advance(Step.PURPOSES)
    }

    /// 생년 확정(스킵이면 0) — 설문 종료, 결과 카드로
    fun finishSurvey(year: Int) {
        birthYear = year
        isFinished = true
    }

    /// 방금 답한 문항 **다음**으로 이동한다.
    ///
    /// 인덱스를 그냥 +1 하지 않는 이유: 답 하나로 시퀀스 자체가 바뀐다(Q2를 "하프~풀"로 고치면
    /// Q3가 사라지고, Q7을 "아직 없어요"로 고치면 Q8이 사라진다). 그래서 갱신된 시퀀스에서
    /// 방금 답한 문항의 위치를 다시 찾아 그 뒤로 넘어간다 — 인덱스가 아니라 문항이 기준이다.
    private fun advance(answered: Step) {
        val updated = steps
        val position = updated.indexOf(answered)
        if (position == -1) {
            isFinished = true
            return
        }
        if (position + 1 < updated.size) stepIndex = position + 1 else isFinished = true
    }

    fun goBack() {
        if (stepIndex > 0) stepIndex--
    }

    /// 판정 결과를 로컬에 저장한다 (iOS `persist` 대응 — DataStore 버전).
    /// 재진단이면 성장 사이클은 건드리지 않는다 — "성장은 되돌리지 않는다" (§5)
    suspend fun persist(settings: SettingsStore, isRediagnosis: Boolean, now: Instant = Instant.now()) {
        settings.setLevelV2(LevelEngine.decide(answers).storageValue)
        settings.setPurposes(RunPurpose.encode(answers.q9Purposes))
        // 주간 목표 초기값 — Q5의 1·3·4회. 무경험자는 Q5를 묻지 않으므로 기본 주 2회 (§2)
        settings.setWeeklyGoal(answers.q5Frequency?.weeklyGoal ?: 2)
        settings.setRaceGoal(answers.q7Target?.storageValue ?: "")
        settings.setRaceGoalSec(answers.q8GoalSec ?: 0)
        settings.setOnboardedAt(now.epochSecond)
        // 생년은 스킵하면 덮어쓰지 않는다 — 재진단에서 스킵해도 기존 값이 남는다
        if (birthYear > 0) settings.setBirthYear(birthYear)
        if (!isRediagnosis) {
            settings.setCycleStartedAt(now.epochSecond)
            settings.setMaxStage(GrowthStage.EGG.raw)
        }
    }

    companion object {
        /// 분기 규칙 (기획서 §2):
        /// - 무경험(Q1 = 이제 시작해요)은 Q2a → 공통(Q7~Q9)
        /// - 유경험은 Q2 → (Q3) → Q4 → Q5 → Q6 → 공통
        /// - Q3는 Q2가 "5km 미만"이거나 "하프~풀"이면 건너뛴다 (§3)
        /// - Q8은 Q7이 "아직 없어요"(null)면 건너뛴다
        /// - 마지막 생년 단계는 Android 추가 (계획서 §2.2)
        fun stepsFor(answers: OnboardingAnswers): List<Step> = buildList {
            add(Step.EXPERIENCE)
            if (answers.q1Experience == OnboardingAnswers.Q1.NOVICE) {
                add(Step.ACTIVITY)
            } else {
                add(Step.LONGEST)
                if (needsRecordQuestion(answers.q2Longest)) add(Step.RECORD)
                add(Step.MONTHLY)
                add(Step.FREQUENCY)
                add(Step.RACE_EXPERIENCE)
            }
            add(Step.TARGET)
            if (answers.q7Target != null) add(Step.GOAL_TIME)
            add(Step.PURPOSES)
            add(Step.BIRTH_YEAR)
        }

        /// Q3 노출 조건 — Q2가 아직 없으면 일단 노출 (자리를 잡아 두고 답에 따라 사라진다)
        private fun needsRecordQuestion(longest: OnboardingAnswers.Q2Longest?): Boolean =
            when (longest) {
                OnboardingAnswers.Q2Longest.UNDER_5,
                OnboardingAnswers.Q2Longest.HALF_TO_FULL -> false
                else -> true
            }
    }
}

// MARK: - 문항 정의

/// 설문 카드 한 장 — 화면이 그릴 문구·아이콘·선택지를 들고 있다 (iOS `OnboardingStep` 이식).
/// 아이콘은 SF Symbols 대신 이모지다 — M5 화면들의 아이콘 관례를 따른다.
private enum class Step {
    EXPERIENCE, ACTIVITY, LONGEST, RECORD, MONTHLY, FREQUENCY,
    RACE_EXPERIENCE, TARGET, GOAL_TIME, PURPOSES, BIRTH_YEAR;

    val title: String
        get() = when (this) {
            EXPERIENCE -> "러닝, 해보신 적 있나요?"
            ACTIVITY -> "요즘 몸은 좀 움직이고 계세요?"
            LONGEST -> "지금까지 가장 멀리 달려본 거리는요?"
            // Q3는 Q2 답에 따라 모델의 `title`이 덮어쓴다 — 여기 값은 폴백
            RECORD -> "10km는 얼마 만에 들어오세요?"
            MONTHLY -> "한 달에 보통 얼마나 달리세요?"
            FREQUENCY -> "일주일에 몇 번 나가세요?"
            RACE_EXPERIENCE -> "대회는 나가보셨어요?"
            TARGET -> "다음 목표는 어떤 거리인가요?"
            GOAL_TIME -> "목표 기록도 정해볼까요?"
            PURPOSES -> "달리는 이유, 뭐예요?"
            BIRTH_YEAR -> "태어난 해가 언제세요?"
        }

    /// 시안 1a는 27px, 나머지 질문 카드는 26px
    val titleSize: Int get() = if (this == EXPERIENCE) 27 else 26

    val subtitle: String?
        get() = when (this) {
            EXPERIENCE -> "답에 따라 질문 수가 달라져요 — 5~9문항, 전부 탭이면 끝나요."
            PURPOSES -> "최대 2개까지 골라도 돼요."
            else -> null
        }

    val emoji: String
        get() = when (this) {
            EXPERIENCE -> "🏃"
            ACTIVITY -> "🚶"
            LONGEST, TARGET -> "🗺️"
            RECORD, GOAL_TIME -> "⏱️"
            MONTHLY -> "📅"
            FREQUENCY -> "🔁"
            RACE_EXPERIENCE -> "🏁"
            PURPOSES -> "❤️"
            BIRTH_YEAR -> "🎂"
        }

    val choices: List<Choice>
        get() = when (this) {
            EXPERIENCE -> OnboardingAnswers.Q1.entries.map { Choice.Experience(it) }
            ACTIVITY -> OnboardingAnswers.Q2A.entries.map { Choice.Activity(it) }
            LONGEST -> OnboardingAnswers.Q2Longest.entries.map { Choice.Longest(it) }
            // Q2 분기에 따라 모델의 `choices`가 덮어쓴다 — 여기 값은 10km 분기 폴백
            RECORD -> listOf(
                OnboardingAnswers.Q3Record.TEN_UNDER_60,
                OnboardingAnswers.Q3Record.TEN_OVER_60,
                OnboardingAnswers.Q3Record.TEN_UNKNOWN,
            ).map { Choice.Record(it) }
            MONTHLY -> OnboardingAnswers.Q4Monthly.entries.map { Choice.Monthly(it) }
            FREQUENCY -> OnboardingAnswers.Q5Frequency.entries.map { Choice.Frequency(it) }
            RACE_EXPERIENCE -> OnboardingAnswers.Q6Race.entries.map { Choice.RaceExperience(it) }
            TARGET -> RaceDistance.entries.map { Choice.Target(it) } + Choice.Target(null)
            GOAL_TIME, PURPOSES, BIRTH_YEAR -> emptyList()
        }
}

/// 선택지 하나 — 어떤 문항의 어떤 답인지를 함께 들고 다녀 화면이 분기 없이 넘길 수 있게 한다
private sealed interface Choice {
    val label: String

    data class Experience(val value: OnboardingAnswers.Q1) : Choice {
        override val label get() = value.label
    }
    data class Activity(val value: OnboardingAnswers.Q2A) : Choice {
        override val label get() = value.label
    }
    data class Longest(val value: OnboardingAnswers.Q2Longest) : Choice {
        override val label get() = value.label
    }
    data class Record(val value: OnboardingAnswers.Q3Record) : Choice {
        override val label get() = value.label
    }
    data class Monthly(val value: OnboardingAnswers.Q4Monthly) : Choice {
        override val label get() = value.label
    }
    data class Frequency(val value: OnboardingAnswers.Q5Frequency) : Choice {
        override val label get() = value.label
    }
    data class RaceExperience(val value: OnboardingAnswers.Q6Race) : Choice {
        override val label get() = value.label
    }
    /// Q7 — null은 "아직 없어요"
    data class Target(val value: RaceDistance?) : Choice {
        override val label get() = value?.label ?: "아직 없어요"
    }
}

// MARK: - 공통 하위 뷰

/// 상단 진행 표시 — 좌 뒤로가기 / 가운데 진행 점 / 우 26dp 스페이서 (시안 온보딩 셸)
@Composable
private fun ProgressBar(total: Int, index: Int, canGoBack: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(24.dp), contentAlignment = Alignment.CenterStart) {
            if (canGoBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "이전 질문",
                    tint = RR.text3,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack),
                )
            }
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(total) { position ->
                when {
                    position == index -> Box(
                        Modifier.size(width = 18.dp, height = 6.dp)
                            .background(RR.brand, RoundedCornerShape(3.dp))
                    )
                    position < index -> Box(Modifier.size(6.dp).background(RR.brand, CircleShape))
                    else -> Box(
                        Modifier.size(6.dp)
                            .background(RR.surface2, CircleShape)
                            .border(1.dp, RR.line, CircleShape)
                    )
                }
            }
        }
        // 좌측 뒤로가기 자리와 대칭 — 점 그룹이 좌우로 흔들리지 않게 한다
        Spacer(Modifier.width(26.dp))
    }
}

/// 질문 카드 상단 아이콘 박스 — 58×58 radius 12 brandSoft (시안 1a·1b·1d)
@Composable
private fun QuestionIcon(emoji: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(58.dp).background(RR.brandSoft, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 27.sp)
    }
}

/// 온보딩 선택지 버튼 — 선택 시 1.5dp 브랜드 테두리 + brandSoft 배경 + 우측 체크 원 (시안)
@Composable
private fun OptionButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) RR.brandSoft else RR.surface, shape)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) RR.brand else RR.line, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Box(
                Modifier.size(22.dp).background(RR.brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/// 브랜드 채움 CTA — 시안 온보딩 primary 버튼 (800 16px, radius 10, padding 16)
@Composable
private fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .background(RR.brand, shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

/// 보조 스킵 버튼 — CTA 아래 회색 문구 (시안 1c "일단 완주부터요" 스타일)
@Composable
private fun SkipButton(title: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RR.text3)
    }
}

/// 휠 뒤 가운데 선택 띠 — 행 전체를 가로지르는 surface2 박스 (시안 1c)
@Composable
private fun WheelBand(modifier: Modifier = Modifier, wheels: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .background(RR.surface2, RoundedCornerShape(8.dp))
        )
        wheels()
    }
}

// MARK: - Q8 목표 기록 피커 (시안 1c)

/// 시:분 2휠 목표 기록 피커 — 설정의 시:분:초 3휠에서 초를 뺐다.
/// 목표 설정에 초 단위는 과하고, 실시간 페이스 환산이 주인공이라 휠을 하나 줄였다 (기획서 §2).
@Composable
private fun GoalTimePage(
    distance: RaceDistance,
    initialSec: Int?,
    onConfirm: (Int) -> Unit,
    onSkip: () -> Unit,
) {
    val presets = remember(distance) { goalPresets(distance) }
    val startSec = initialSec ?: presets.first().seconds
    var hour by remember { mutableIntStateOf(startSec / 3_600) }
    var minute by remember { mutableIntStateOf(startSec % 3_600 / 60) }
    val totalSec = hour * 3_600 + minute * 60

    Text(
        "목표 기록도 정해볼까요?",
        style = RR.display(26.sp), color = RR.text,
        modifier = Modifier.padding(top = 34.dp),
    )
    Box(Modifier.padding(top = 14.dp)) { Eyebrow(goalEyebrowText(distance)) }
    Row(
        Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            PresetChip(preset.label, isSelected = totalSec == preset.seconds) {
                hour = preset.seconds / 3_600
                minute = preset.seconds % 3_600 / 60
            }
        }
    }
    WheelBand(Modifier.padding(top = 12.dp)) {
        Row {
            NumberWheel("시간", 0..7, hour, { hour = it }, Modifier.weight(1f))
            NumberWheel("분", 0..59, minute, { minute = it }, Modifier.weight(1f))
        }
    }
    // 실시간 페이스 환산 — 휠을 돌리면 즉시 갱신된다 (기획서 §2)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("=", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.text3)
        Text("km당", fontSize = 13.sp, color = RR.text2)
        Text(
            if (totalSec > 0) Format.pace(totalSec / distance.km) else "—",
            style = RR.numeral(17.sp), color = RR.text,
        )
    }
    PrimaryButton(
        "이 기록으로 할게요",
        enabled = totalSec > 0,
        modifier = Modifier.padding(top = 18.dp),
    ) { onConfirm(totalSec) }
    SkipButton("일단 완주부터요", onSkip)
}

/// "풀코스 42.195km" — 시안 1c의 eyebrow. km는 소수점 이하 0이면 정수로 줄인다.
/// 시안은 풀을 "풀코스"로 적는다 — RaceDistance.label("풀코스")과 이미 같다.
private fun goalEyebrowText(distance: RaceDistance): String {
    val km = distance.km
    val kmText = if (km == Math.rint(km)) "%.0f".format(Locale.ROOT, km)
                 else "%.3f".format(Locale.ROOT, km)
    return "${distance.label} ${kmText}km"
}

@Composable
private fun PresetChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .background(if (isSelected) RR.brandSoft else RR.surface, shape)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) RR.brand else RR.line, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (isSelected) RR.brand else RR.text2,
        )
    }
}

/// 목표 기록 프리셋 칩 하나 — 탭하면 휠이 그 값으로 이동한다 (기획서 §2)
private data class GoalPreset(val label: String, val seconds: Int)

/// 종목별 프리셋 (기획서 §2) — 풀의 칩 문구는 시안 1c의 "완주 / sub-5 / sub-4:30 / sub-4"
private fun goalPresets(distance: RaceDistance): List<GoalPreset> = when (distance) {
    RaceDistance.FIVE_K -> listOf(
        GoalPreset("30분", 30 * 60),
        GoalPreset("25분", 25 * 60),
    )
    RaceDistance.TEN_K -> listOf(
        GoalPreset("60분", 60 * 60),
        GoalPreset("50분", 50 * 60),
    )
    RaceDistance.HALF -> listOf(
        GoalPreset("2:00", 2 * 3_600),
        GoalPreset("1:50", 3_600 + 50 * 60),
    )
    // "완주"는 기획서 §2가 정한 5:00을 뜻하고, "sub-5"는 그보다 1분 아래(4:59)로
    // 두어 같은 값이 겹치지 않게 한다
    RaceDistance.FULL -> listOf(
        GoalPreset("완주", 5 * 3_600),
        GoalPreset("sub-5", 4 * 3_600 + 59 * 60),
        GoalPreset("sub-4:30", 4 * 3_600 + 29 * 60),
        GoalPreset("sub-4", 3 * 3_600 + 59 * 60),
    )
}
