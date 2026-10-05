package com.jkpark.runwrap.screen

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.BirdSpecies
import com.jkpark.runwrap.engine.CollectionEngine
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.SpeciesBirdView
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/// 수집 세러모니 — 성조 도달 → 축하 → 도감 수록 → 새 목표 → 새 알 (기획서 §5).
///
/// 외부 의존성 없음 원칙이라 Lottie를 쓰지 않는다. 파티클은 `TimelineView`+`Canvas`로
/// 직접 그린다 — 뷰를 수백 개 만들지 않고 한 캔버스에 찍는 편이 가볍다.
///
/// 이 화면은 **되돌아갈 수 없는 전환**을 담는다. 마지막 버튼을 누르는 순간 도감에
/// 수록되고 사이클이 초기화되므로, 중간에 닫아도 아무 일도 일어나지 않게 설계했다 —
/// 실제 커밋은 `finish()` 한 곳에서만 일어난다.
///
/// - cycleGoal: 이번 사이클 목표 — 다음 목표 추천을 여기서 한 칸 올린다 (이슈 #127)
/// - currentGoal: 설정의 현재 목표 — 사이클 도중 더 높게 바꿔 뒀다면 그 값을 초기 선택으로 우선한다
/// - onLater: "조금 더 키우기" — 수집을 미룬다. 수집 전까지는 더 긴 거리·빠른 기록이 나오면 종이 오른다
///   (풀코스 대회 전에 XP가 먼저 차도 대회를 기다릴 수 있게)
/// - onFinish: 수집 확정 — 도감 수록과 사이클 초기화를 호출부(홈)가 실행한다.
///   전환 부작용을 화면이 직접 저지르지 않게 하려고 클로저로 올린다.
///   false(도감 저장 실패)면 닫지 않고 남아 다시 누를 수 있게 한다 (이슈 #67)
/// (Android: 홈이 전체 화면 Dialog로 띄우고 닫기도 홈이 한다 — iOS의 dismiss()는 호출부 몫이다.
/// 시스템 뒤로가기는 onLater)
@Composable
fun CeremonyScreen(
    species: BirdSpecies,
    goalLabel: String,
    cycleStartedAt: Instant,
    cycleGoal: RaceDistance?,
    cycleGoalSeconds: Int,
    currentGoal: RaceDistance?,
    currentGoalSeconds: Int,
    onLater: () -> Unit,
    onFinish: (newGoal: RaceDistance?, newGoalSeconds: Int) -> Boolean,
) {
    val reduceMotion = rememberReduceMotion()
    /// 세러모니 단계 — 축하를 먼저 보여주고, 이어서 다음 목표를 고르게 한다
    var step by rememberSaveable { mutableStateOf(CeremonyStep.celebrate) }

    /// 사용자가 고른 다음 목표. 처음에는 추천값으로 채워 두고 바꿀 수 있게 한다
    // 추천 목표를 초기 선택으로 깔아 둔다 (§5 "직전 목표·최근 기록 기반", 이슈 #127)
    val initial = remember {
        CollectionEngine.initialNextGoal(cycleGoal, cycleGoalSeconds, currentGoal, currentGoalSeconds)
    }
    var pickedDistance by remember { mutableStateOf(initial.distance) }
    var pickedSeconds by remember { mutableStateOf(initial.seconds) }

    BackHandler { onLater() }

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        Confetti(reduceMotion)

        Crossfade(step, Modifier.systemBarsPadding(), animationSpec = tween(250, easing = EaseInOut), label = "step") { current ->
            when (current) {
                CeremonyStep.celebrate -> CelebrateBody(species, goalLabel, cycleStartedAt, reduceMotion,
                    onCollect = { step = CeremonyStep.chooseGoal }, onLater = onLater)
                CeremonyStep.chooseGoal -> GoalBody(
                    pickedDistance = pickedDistance,
                    pickedSeconds = pickedSeconds,
                    onPick = { distance, seconds -> pickedDistance = distance; pickedSeconds = seconds },
                    // 저장 실패(false)면 남아 다시 누를 수 있게 한다 — 닫기는 홈이 한다
                    onFinish = { onFinish(pickedDistance, pickedSeconds) },
                )
            }
        }
    }
}

private enum class CeremonyStep { celebrate, chooseGoal }

// MARK: - 1) 축하

@Composable
private fun CelebrateBody(
    species: BirdSpecies, goalLabel: String, cycleStartedAt: Instant, reduceMotion: Boolean,
    onCollect: () -> Unit, onLater: () -> Unit,
) {
    // 모션 줄이기면 튀어나오는 spring 없이 바로 보인다 (이슈 #212)
    // SwiftUI spring(response 0.7, dampingFraction 0.6) → stiffness (2π/0.7)² ≈ 80.6
    val appeared = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        appeared.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = ((2 * PI / 0.7) * (2 * PI / 0.7)).toFloat()))
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))

        SpeciesBirdView(species, Modifier.size(210.dp).graphicsLayer {
            val p = appeared.value
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
            alpha = p.coerceIn(0f, 1f)
        })

        Text("${species.label}가 되었어요", style = RR.display(28.sp), color = RR.text,
            modifier = Modifier.padding(top = 10.dp))

        Text(
            goalLabel,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.brand,
            modifier = Modifier.padding(top = 10.dp)
                .clip(RoundedCornerShape(999.dp)).background(RR.brandSoft)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Text(
            "${cycleDays(cycleStartedAt)}일을 함께 달렸습니다.\n이 새는 도감에 남아요.",
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center),
            color = RR.text2,
            modifier = Modifier.padding(top = 14.dp),
        )

        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.weight(1f))

        BrandButton("도감에 넣기", Modifier.padding(horizontal = 20.dp), onCollect)

        species.next?.let { next ->
            Column(
                Modifier.padding(horizontal = 20.dp).padding(top = 6.dp).fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onLater)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("조금 더 키우기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RR.text)
                Text("${next.goalHint} → ${next.label}", fontSize = 11.5.sp, color = RR.text2)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/// 사이클 소요 일수 — 세러모니 문장에만 쓴다
private fun cycleDays(cycleStartedAt: Instant): Int {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(cycleStartedAt.atZone(zone).toLocalDate(), Instant.now().atZone(zone).toLocalDate())
    return max(0, days.toInt())
}

// MARK: - 2) 새 목표

@Composable
private fun GoalBody(
    pickedDistance: RaceDistance?, pickedSeconds: Int,
    onPick: (RaceDistance?, Int) -> Unit, onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.weight(1f))

        Box(Modifier.padding(horizontal = 20.dp)) { Eyebrow("다음 목표") }
        Text("다음은 어디까지 가 볼까요?", style = RR.display(24.sp), color = RR.text,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 6.dp))
        Text("목표 거리를 실제로 달리면 그 새가 돼요.", fontSize = 13.sp, color = RR.text2,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp))

        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(null) + RaceDistance.entries).forEach { distance ->
                GoalOption(distance, pickedDistance, pickedSeconds, onPick)
            }
        }

        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.weight(1f))

        BrandButton("새 알 받기", Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), onFinish)
    }
}

/// 목표 후보 한 줄 — 그 목표를 달성하면 될 새 종류를 미리 보여준다
@Composable
private fun GoalOption(
    distance: RaceDistance?, pickedDistance: RaceDistance?, pickedSeconds: Int,
    onPick: (RaceDistance?, Int) -> Unit,
) {
    // 기록 목표는 이 화면에서 받지 않는다(설정에서 정한다). 다만 풀코스를
    // 이어 가는 경우엔 추천 기록을 유지해야 종이 달라지므로 그대로 넘긴다
    val seconds = if (distance == RaceDistance.full && pickedDistance == RaceDistance.full) pickedSeconds else 0
    val resulting = CollectionEngine.species(distance, seconds)
    val isPicked = distance == pickedDistance
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(RR.surface)
            .border(if (isPicked) 2.dp else 1.dp, if (isPicked) RR.brand else RR.line, shape)
            .clickable(role = Role.Button) { onPick(distance, seconds) }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(distance?.label ?: "목표 없이 꾸준히", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text)
            Text(CollectionEngine.goalLabel(distance, seconds), fontSize = 11.5.sp, color = RR.text2)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            resulting.label,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (isPicked) RR.onBrand else RR.text2,
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(if (isPicked) RR.brand else RR.surface2)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/// 세러모니 하단 브랜드 채움 버튼 — "도감에 넣기"·"새 알 받기" 공용 (16 bold, r12, 세로 15)
@Composable
private fun BrandButton(title: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        title,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        color = RR.onBrand,
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RR.brand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp),
    )
}

// MARK: - 파티클

/// 축하 색종이 — TimelineView로 시간을 받아 Canvas 한 장에 찍는다.
/// 난수는 인덱스 기반 결정론 함수로 만든다(뷰가 다시 그려져도 같은 자리)
/// (Android: withFrameNanos 루프로 시각을 받는다. 시각은 그리기 단계에서만 읽어 재구성 없이 다시 그린다)
@Composable
private fun Confetti(reduceMotion: Boolean) {
    val colors = listOf(RR.brand, RR.pos, RR.warn)
    var time by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1_000.0) }
    // 모션 줄이기면 색종이를 멈춘 한 장면으로 둔다 (이슈 #212)
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val origin = time
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time = origin + (it - start) / 1e9 }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val t = time
        for (i in 0 until CONFETTI_COUNT) {
            val seedX = pseudoRandom(i, salt = 1)
            val seedSpeed = 0.5 + pseudoRandom(i, salt = 2)
            val seedSize = 4 + pseudoRandom(i, salt = 3) * 5

            // 아래로 흐르다 화면 밖에서 되감긴다 (좌표는 iOS pt 수치 → dp)
            val cycle = (t * seedSpeed) % 1
            val y = cycle * (size.height + 40.dp.toPx()) - 20.dp.toPx()
            val sway = sin((t + i) * 1.6) * 12.dp.toPx()
            val x = seedX * size.width + sway

            drawRoundRect(
                color = colors[i % colors.size],
                topLeft = Offset(x.toFloat(), y.toFloat()),
                size = Size(seedSize.dp.toPx(), (seedSize * 1.6).dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

private const val CONFETTI_COUNT = 34

/// 인덱스 기반 유사 난수 0..<1 — `Math.random` 없이 결정론적으로 흩뿌린다
private fun pseudoRandom(index: Int, salt: Int): Double {
    val x = sin(index * 12.9898 + salt * 78.233) * 43_758.5453
    return x - floor(x)
}

/// iOS `accessibilityReduceMotion` 대응 — 개발자 옵션·접근성의 "애니메이션 삭제"(애니메이터 배율 0)
@Composable
private fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
