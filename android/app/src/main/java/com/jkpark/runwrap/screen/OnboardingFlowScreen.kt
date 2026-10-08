package com.jkpark.runwrap.screen

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GoalPreset
import com.jkpark.runwrap.engine.GrowthStage
import com.jkpark.runwrap.engine.OnboardingAnswers
import com.jkpark.runwrap.engine.OnboardingFlowModel
import com.jkpark.runwrap.engine.OnboardingStep
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RunPurpose
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.health.HealthPermissions
import com.jkpark.runwrap.store.DemoMode
import com.jkpark.runwrap.ui.BirdView
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.NumberWheel
import com.jkpark.runwrap.ui.PrimaryButton
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

/// 온보딩 카드 설문 플로우 (기획서 v0.7 §2, 시안 1a~1e).
///
/// 카드 1장 = 질문 1개. 타이핑 없이 전부 탭으로 답하고, 마지막에 레벨을 발표하며 알을 준다.
/// **권한 요청은 설문 뒤로 옮겼다** — 맥락(내 레벨·내 알)을 만든 뒤 요청해야 수락률이 오르고,
/// 설문 자체는 자기 신고라 HealthKit이 필요 없기 때문이다 (기획서 §2 설계 원칙).
///
/// 답은 전부 로컬에만 남는다: 판정 결과는 `ProfileKey.*`(@AppStorage),
/// 원답은 저장하지 않는다 — 판정 결과(레벨·목표)만 남긴다. 네트워크 전송은 없다.
///
/// - prefill: 이전 답 프리필 — 원답을 저장하지 않으므로 앱 호출부는 넘기지 않는다(nil). 테스트가 답을 심는 용도 (이슈 #156)
/// - isRediagnosis: 설정의 "다시 진단받기"(§7)에서 열었는지 — 참이면 성장 사이클을 보존한다 (이슈 #44).
///   프리필 유무로 판정하면 안 된다: 재진단은 의도적으로 프리필 없이 열린다
/// - onFinish: 플로우 완료(권한 요청까지) 후 호출 — 재진단 시트를 닫는 데 쓴다
@Composable
fun OnboardingFlowScreen(
    prefill: OnboardingAnswers? = null,
    isRediagnosis: Boolean = false,
    onFinish: () -> Unit = {},
) {
    val vm = containerViewModel { OnboardingFlowViewModel() }
    val version by vm.version.collectAsStateWithLifecycle()
    // version을 읽어 두면 모델이 바뀔 때마다 이 화면이 다시 그려진다.
    // 모델은 늘 같은 인스턴스라 그대로 넘기면 하위 컴포저블이 건너뛰어진다(strong skipping은 인스턴스가 같으면 생략한다) —
    // 모델을 받는 컴포저블에는 @NonSkippableComposable을 붙인다
    val model = remember(version) { vm.model }

    LaunchedEffect(Unit) { vm.update { prefillIfNeeded(prefill) } }
    // 시스템 뒤로가기 — 설문 중이면 이전 질문, 첫 질문·결과 카드면 기본 동작(첫 온보딩은 앱 종료, 재진단은 닫기)
    BackHandler(enabled = !model.isFinished && model.canGoBack) { vm.update { goBack() } }

    Box(Modifier.fillMaxSize().background(RR.bg).systemBarsPadding()) {
        if (model.isFinished) {
            ResultCard(model.level, isRediagnosis, onNext = rememberFinishAction(vm, isRediagnosis, onFinish))
        } else {
            QuestionCard(model, vm)
        }
    }
}

/// 엔진 `OnboardingFlowModel`(가변 클래스)을 감싼다 — 바꿀 때마다 version을 올려 화면을 다시 그린다
class OnboardingFlowViewModel : ViewModel() {
    val model = OnboardingFlowModel()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun update(change: OnboardingFlowModel.() -> Unit) {
        model.change()
        _version.update { it + 1 }
    }
}

// MARK: - 질문 카드 (시안 1a~1d)

@NonSkippableComposable
@Composable
private fun QuestionCard(model: OnboardingFlowModel, vm: OnboardingFlowViewModel) {
    // 카드는 위에서부터 쌓는다
    Column(Modifier.fillMaxSize().padding(top = 70.dp, start = 26.dp, end = 26.dp, bottom = 34.dp)) {
        OnboardingProgressBar(total = model.stepCount, index = model.stepIndex,
            canGoBack = model.canGoBack, onBack = { vm.update { goBack() } })

        when (model.step) {
            OnboardingStep.goalTime -> {
                // Q8 — 목표 기록 피커 (시안 1c).
                // `.id`로 종목을 물려 두면 뒤로 가서 Q7을 바꿨을 때 휠 초기값이 새 종목 프리셋으로 다시 잡힌다
                // (onAppear는 한 번만 도는데 종목이 바뀌면 프리셋·페이스 환산 기준 자체가 달라진다)
                val distance = model.answers.q7Target ?: RaceDistance.full
                key(distance) {
                    GoalTimePage(distance = distance, initialSec = model.answers.q8GoalSec,
                        onConfirm = { sec -> vm.update { answerGoalTime(sec) } },
                        onSkip = { vm.update { answerGoalTime(null) } })
                }
            }
            OnboardingStep.purposes -> PurposesPage(model, vm)
            else -> ChoicePage(model, vm)
        }
    }
}

/// 단일 선택 문항 — 탭 즉시 다음 카드로 넘어간다 (CTA 없음)
@NonSkippableComposable
@Composable
private fun ChoicePage(model: OnboardingFlowModel, vm: OnboardingFlowViewModel) {
    val step = model.step
    Column {
        QuestionIcon(step.iconName, Modifier.padding(top = 34.dp))

        Text(
            model.title,
            style = RR.display(step.titleSize.sp).copy(lineHeight = (step.titleSize * 1.35f).sp),
            color = RR.text,
            modifier = Modifier.padding(top = 22.dp),
        )

        step.subtitle?.let {
            Text(it, fontSize = 13.sp, color = RR.text3, modifier = Modifier.padding(top = 10.dp))
        }

        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            model.choices.forEach { choice ->
                key(choice.id) {
                    OptionButton(choice.label, model.isSelected(choice)) { vm.update { answer(choice) } }
                }
            }
        }
    }
}

/// Q9 — 최대 2개 다중 선택이라 CTA가 필요하다 (시안 1d)
@NonSkippableComposable
@Composable
private fun PurposesPage(model: OnboardingFlowModel, vm: OnboardingFlowViewModel) {
    val isEmpty = model.answers.q9Purposes.isEmpty()
    Column {
        QuestionIcon("heart.fill", Modifier.padding(top = 34.dp))

        Text("달리는 이유, 뭐예요?", style = RR.display(26.sp), color = RR.text,
            modifier = Modifier.padding(top = 22.dp))

        Text("최대 2개까지 골라도 돼요.", fontSize = 13.sp, color = RR.text3,
            modifier = Modifier.padding(top = 10.dp))

        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RunPurpose.entries.forEach { purpose ->
                OptionButton(purpose.label, purpose in model.answers.q9Purposes) {
                    vm.update { togglePurpose(purpose) }
                }
            }
        }

        Box(Modifier.padding(top = 22.dp).alpha(if (isEmpty) 0.4f else 1f)) {
            PrimaryButton("다음") { if (!isEmpty) vm.update { finishSurvey() } }
        }
    }
}

// MARK: - 결과 카드 (시안 1e)

@Composable
private fun ResultCard(level: RunnerLevel, isRediagnosis: Boolean, onNext: () -> Unit) {
    // maxHeight까지 채워야 위 Spacer가 벌어져 CTA가 화면 아래에 붙는다
    Column(
        Modifier.fillMaxSize().padding(top = 96.dp, start = 30.dp, end = 30.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("진단 결과")

        Text(level.label, style = RR.display(44.sp), color = RR.brand,
            modifier = Modifier.padding(top = 14.dp))

        Text(levelCaption(level), fontSize = 15.sp, color = RR.text2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp))

        if (isRediagnosis) {
            // 재진단은 사이클을 보존하므로 알을 새로 주지 않는다 (이슈 #44).
            // "알이 도착했어요"를 그대로 두면 지금 키우는 새가 사라진 것처럼 읽힌다
            Text("새는 지금 단계 그대로 자라요", fontSize = 14.sp, color = RR.text2, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 26.dp))
        } else {
            BirdView(GrowthStage.egg, Modifier.padding(top = 26.dp).size(130.dp))

            Text("알이 도착했어요", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RR.text,
                modifier = Modifier.padding(top = 16.dp))

            Text(
                "달릴수록 부화가 가까워집니다. 정상은 아니지만 멋있을 예정입니다.",
                style = TextStyle(fontSize = 14.sp, lineHeight = 25.sp, textAlign = TextAlign.Center),
                color = RR.text2,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 300.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))

        // CTA 문구는 "다음"으로 고정한다 — 권한 요청 직전 버튼이 허용을 권유하면
        // App Review 5.1.1(iv)에 걸린다("Continue"/"Next" 같은 중립 표현을 쓰라는 지적).
        // 요청 이유는 아래 캡션으로만 설명하고, 버튼은 다음 단계로 넘어간다는 뜻만 갖는다.
        PrimaryButton("다음", onNext)

        // 재진단은 권한 시트를 이미 지난 사용자라 권한 안내를 되풀이하지 않는다
        if (!isRediagnosis) {
            Text(
                "리포트를 만들려면 러닝 기록이 필요해서, 다음 화면에서 헬스 커넥트 읽기 권한을 물어봐요. 허용 여부는 직접 정하시면 됩니다.",
                fontSize = 11.5.sp, color = RR.text3, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/// 결과 카드 "다음" — 판정 저장 → 헬스 커넥트 권한 시트 → 조회 → onFinish.
/// (Android: iOS는 저장이 먼저, 권한 요청이 나중이다("권한 시트에서 이탈해도 진단 결과는 남는다").
/// Android는 저장하는 순간 RootView가 이 화면을 치워 권한 계약의 결과 콜백이 끊기므로,
/// 시트를 먼저 띄우고 결과(허용·거부 무관)를 받은 뒤 저장한다. 데모 모드거나 이미 허용한 사용자는 시트를 건너뛴다)
@Composable
private fun rememberFinishAction(vm: OnboardingFlowViewModel, isRediagnosis: Boolean, onFinish: () -> Unit): () -> Unit {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    fun complete() {
        vm.model.persist(isRediagnosis, Instant.now(), ZoneId.systemDefault(), container.settings)
        // 조회는 앱 수명 스코프에서 — 닫히는 화면과 함께 끊기지 않게. onFinish는 화면이 살아 있을 때만 부른다
        val load = container.scope.launch { container.health.connect() }
        scope.launch {
            load.join()
            onFinish()
        }
        // 온보딩(첫 사이클) 확정 즉시 스냅샷을 올린다 — 다음 재설치부터 복원 가능 (이슈 #29)
        container.backup.backupIfChanged()
    }

    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        complete()
    }

    return {
        scope.launch {
            if (DemoMode.isActive(container.settings) || container.health.hasPermissions()) {
                complete()
            } else {
                try {
                    permissions.launch(HealthPermissions.standard)
                } catch (e: ActivityNotFoundException) {
                    complete()   // 헬스 커넥트가 없는 기기 — RootView가 미지원 안내로 보낸다
                }
            }
        }
    }
}

/// 레벨별 한 줄 설명 — 런린이 문구는 시안 1e verbatim.
/// 런잘알·런친놈은 시안에 없어 §3의 리포트 초점(기록 향상 / 부하 관리)을 같은 목소리로 옮겼다
private fun levelCaption(level: RunnerLevel): String = when (level) {
    RunnerLevel.beginner -> "완주와 습관부터, 같이 갑니다"
    RunnerLevel.intermediate -> "기록을 당길 때가 됐네요, 같이 갑니다"
    RunnerLevel.advanced -> "이제 관리가 실력입니다, 같이 갑니다"
}

/// 시안의 온보딩 카드 글리프 4종(러너·경로·스톱워치·하트)을 SF Symbols로 대체 (시안 §10)
/// (Android: iOS는 OnboardingStep의 속성 — SF 이름은 앱 관심사라 엔진 대신 화면에 둔다)
private val OnboardingStep.iconName: String
    get() = when (this) {
        OnboardingStep.experience -> "figure.run"
        OnboardingStep.activity -> "figure.walk"
        OnboardingStep.longest, OnboardingStep.target -> "point.topleft.down.curvedto.point.bottomright.up"
        OnboardingStep.record, OnboardingStep.goalTime -> "stopwatch"
        OnboardingStep.monthly -> "calendar"
        OnboardingStep.frequency -> "repeat"
        OnboardingStep.raceExperience -> "flag.checkered"
        OnboardingStep.purposes -> "heart.fill"
    }

// MARK: - 공통 하위 뷰

/// 상단 진행 표시 — 좌 뒤로가기 / 가운데 진행 점 / 우 26pt 스페이서 (시안 온보딩 셸)
@Composable
private fun OnboardingProgressBar(total: Int, index: Int, canGoBack: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 첫 화면은 빈 자리표시 — 점 그룹이 좌우로 흔들리지 않게 한다.
        Box(Modifier.width(26.dp).height(24.dp), contentAlignment = Alignment.Center) {
            if (canGoBack) {
                // (Android: 48dp 터치 영역은 26pt 자리 밖으로 넘치게 둔다 — 행 높이는 24로 묶어 둔다)
                IconButton(onClick = onBack, modifier = Modifier.requiredSize(48.dp)) {
                    Icon(RRIcons.named("chevron.left"), contentDescription = "이전 질문", tint = RR.text3,
                        modifier = Modifier.size(24.dp))
                }
            }
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { position ->
                val width by animateDpAsState(if (position == index) 18.dp else 6.dp, tween(200, easing = EaseOut), label = "dot")
                val fill by animateColorAsState(if (position <= index) RR.brand else RR.surface2, tween(200, easing = EaseOut), label = "dotFill")
                val shape = if (position == index) RoundedCornerShape(3.dp) else CircleShape
                Box(
                    Modifier.width(width).height(6.dp).clip(shape).background(fill)
                        .then(if (position > index) Modifier.border(1.dp, RR.line, shape) else Modifier)
                )
            }
        }

        // 좌측 뒤로가기 자리와 대칭 — 여기도 높이를 묶어야 행이 부풀지 않는다
        Spacer(Modifier.width(26.dp).height(24.dp))
    }
}

/// 질문 카드 상단 아이콘 박스 — 58×58 radius 12 brandSoft (시안 1a·1b·1d)
@Composable
private fun QuestionIcon(systemName: String, modifier: Modifier = Modifier) {
    Box(modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(RR.brandSoft), contentAlignment = Alignment.Center) {
        Icon(RRIcons.named(systemName), contentDescription = null, tint = RR.brand, modifier = Modifier.size(30.dp))
    }
}

/// 온보딩 선택지 버튼 — 선택 시 1.5px 브랜드 테두리 + brandSoft 배경 + 우측 체크 원 (시안)
@Composable
private fun OptionButton(label: String, isSelected: Boolean, action: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) RR.brandSoft else RR.surface)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) RR.brand else RR.line, shape)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (isSelected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(RR.brand), contentAlignment = Alignment.Center) {
                Icon(RRIcons.named("checkmark"), contentDescription = null, tint = RR.onBrand, modifier = Modifier.size(13.dp))
            }
        }
    }
}

// MARK: - Q8 목표 기록 피커 (시안 1c)

/// 시:분 2휠 목표 기록 피커 — 설정의 시:분:초 3휠에서 초를 뺐다.
/// 목표 설정에 초 단위는 과하고, 실시간 페이스 환산이 주인공이라 휠을 하나 줄였다 (기획서 §2).
@Composable
private fun GoalTimePage(distance: RaceDistance, initialSec: Int?, onConfirm: (Int) -> Unit, onSkip: () -> Unit) {
    val presets = remember(distance) { GoalPreset.presets(distance) }
    val seconds = initialSec ?: presets.firstOrNull()?.seconds ?: 0
    var hour by remember { mutableIntStateOf(seconds / 3_600) }
    var minute by remember { mutableIntStateOf(seconds % 3_600 / 60) }
    val totalSec = hour * 3_600 + minute * 60

    Column {
        Text("목표 기록도 정해볼까요?", style = RR.display(26.sp), color = RR.text,
            modifier = Modifier.padding(top = 34.dp))

        Box(Modifier.padding(top = 14.dp)) { Eyebrow(eyebrowText(distance)) }

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                PresetChip(preset, isSelected = totalSec == preset.seconds) {
                    hour = preset.seconds / 3_600
                    minute = preset.seconds % 3_600 / 60
                }
            }
        }

        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)).background(RR.surface2))
            Row {
                NumberWheel("시간", 0..7, hour, { hour = it }, Modifier.weight(1f))
                NumberWheel("분", 0..59, minute, { minute = it }, Modifier.weight(1f))
            }
        }

        // 실시간 페이스 환산 — 휠을 돌리면 즉시 갱신된다 (기획서 §2)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Text("=", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.text3)
            Text("km당", fontSize = 13.sp, color = RR.text2)
            Text(if (totalSec > 0) Format.pace(totalSec.toDouble() / distance.km) else "—",
                style = RR.numeral(17.sp), color = RR.text)
        }

        Box(Modifier.padding(top = 18.dp).alpha(if (totalSec == 0) 0.4f else 1f)) {
            PrimaryButton("이 기록으로 할게요") { if (totalSec != 0) onConfirm(totalSec) }
        }

        Text(
            "일단 완주부터요",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            color = RR.text3,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSkip).padding(vertical = 12.dp),
        )
    }
}

/// "풀코스 42.195km" — 시안 1c의 eyebrow. km는 소수점 이하 0이면 정수로 줄인다
/// 시안은 풀을 "풀코스"로 적는다 — RaceDistance.label("풀")보다 이 자리에선 또렷하다
private fun eyebrowText(distance: RaceDistance): String {
    val km = distance.km
    val kmText = if (km == floor(km)) fmt(km, 0) else fmt(km, 3)
    val displayName = if (distance == RaceDistance.full) "풀코스" else distance.label
    return "$displayName ${kmText}km"
}

/// 목표 기록 프리셋 칩 하나 — 탭하면 휠이 그 값으로 이동한다 (기획서 §2)
@Composable
private fun PresetChip(preset: GoalPreset, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        preset.label,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (isSelected) RR.brand else RR.text2,
        modifier = Modifier
            .clip(shape)
            .background(if (isSelected) RR.brandSoft else RR.surface)
            .border(if (isSelected) 1.5.dp else 1.dp, if (isSelected) RR.brand else RR.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
