package com.jkpark.runwrap.ui

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.R
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RaceEngine

/// 디자인 토큰 — 디자인 시안(claude.design "Runner Report" v0.4)의 rr-theme 팔레트.
/// 라이트/다크 값을 UIColor 다이내믹 프로바이더로 묶어 시스템 모드를 따른다.
/// v0.4는 "스포티 볼드" 리디자인 — 웜 크림 배경 + 레드오렌지 브랜드가 기준이다.
/// (Android: 다이내믹 프로바이더 대신 각 토큰이 여기서만 `isSystemInDarkTheme()`로 (light, dark)를 고른다 —
/// 화면 코드는 분기하지 않는다. `RRTone`·`Format`은 엔진 모듈(engine/Theme.kt)에 있다)
object RR {
    val bg: Color @Composable @ReadOnlyComposable get() = adaptive(0xF2F0EA, 0x0A0A09)
    val surface: Color @Composable @ReadOnlyComposable get() = adaptive(0xFFFFFF, 0x151513)
    val surface2: Color @Composable @ReadOnlyComposable get() = adaptive(0xEAE7E0, 0x1E1E1B)
    val text: Color @Composable @ReadOnlyComposable get() = adaptive(0x12120F, 0xF5F4EF)
    val text2: Color @Composable @ReadOnlyComposable get() = adaptive(0x54544E, 0xA8A79E)
    val text3: Color @Composable @ReadOnlyComposable get() = adaptive(0x8F8F86, 0x73736C)
    val brand: Color @Composable @ReadOnlyComposable get() = adaptive(0xFF4D2E, 0xFF5A3C)
    /// 브랜드(주황)·톤 채움 위 글자·아이콘 — 지금은 양쪽 흰색이지만,
    /// 다크에서 브랜드가 밝아져 대비를 바꿔야 할 때 한 곳에서 일괄 조정하려고 토큰으로 둔다 (이슈 #85)
    val onBrand: Color @Composable @ReadOnlyComposable get() = adaptive(0xFFFFFF, 0xFFFFFF)
    val pos: Color @Composable @ReadOnlyComposable get() = adaptive(0x0E9146, 0x35E077)
    /// 웜 팔레트의 유일한 한랭 색 — 날씨 아이콘의 강수(비·눈) 심볼과, 코스 탭 화장실(위험 신호가 아닌 중립 표시)에만 쓴다
    val sky: Color @Composable @ReadOnlyComposable get() = adaptive(0x2E8BD9, 0x5AAEFF)
    val warn: Color @Composable @ReadOnlyComposable get() = adaptive(0xC77700, 0xFFAE00)
    val dang: Color @Composable @ReadOnlyComposable get() = adaptive(0xD91F00, 0xFF3B30)

    /// 기동 스플래시 전용 — 앱 아이콘의 파랑 그라데이션 중간 톤 (light #16A5FB / dark #101426).
    /// 런치 스크린(스플래시 테마)이 같은 색 리소스를 참조하므로 hex가 아니라 리소스에서 온다 —
    /// 색을 바꾸려면 values/colors.xml·values-night/colors.xml의 launch_bg 한 곳만 고친다
    val launchBg: Color @Composable @ReadOnlyComposable get() = colorResource(R.color.launch_bg)

    /// 시안: light rgba(20,20,16,.13) / dark rgba(255,255,255,.11)
    val line: Color @Composable @ReadOnlyComposable get() = alpha(black = 0.13f, white = 0.11f)
    /// 차트 막대 바탕 — light rgba(20,20,16,.09) / dark rgba(255,255,255,.10)
    val barFill: Color @Composable @ReadOnlyComposable get() = alpha(black = 0.09f, white = 0.10f)
    /// 카드 그림자 — 라이트는 옅은 검정, 다크는 배경이 거의 검정이라 그림자가 보이지 않아 0 (이슈 #85)
    val shadow: Color @Composable @ReadOnlyComposable get() = alpha(black = 0.04f, white = 0.0f)
    /// 떠 있는 미리보기(공유 카드 등)용 진한 그림자 — 다크는 같은 이유로 0 (이슈 #85)
    val shadowStrong: Color @Composable @ReadOnlyComposable get() = alpha(black = 0.10f, white = 0.0f)

    val brandSoft: Color @Composable @ReadOnlyComposable get() = soft(brand, 0.12f, 0.18f)
    val posSoft: Color @Composable @ReadOnlyComposable get() = soft(pos, 0.12f, 0.16f)
    val warnSoft: Color @Composable @ReadOnlyComposable get() = soft(warn, 0.13f, 0.16f)
    val dangSoft: Color @Composable @ReadOnlyComposable get() = soft(dang, 0.11f, 0.16f)
    val skySoft: Color @Composable @ReadOnlyComposable get() = soft(sky, 0.12f, 0.16f)

    /// PB 메달 색 (이슈 #21) — 종목 격에 맞춰 풀=금·하프=은·10K=동, 5K는 브랜드색
    val medalGold: Color @Composable @ReadOnlyComposable get() = adaptive(0xC9A227, 0xE3C34E)
    val medalSilver: Color @Composable @ReadOnlyComposable get() = adaptive(0x8E9196, 0xB6BAC1)
    val medalBronze: Color @Composable @ReadOnlyComposable get() = adaptive(0xB0703C, 0xCE8B52)

    /// PB 메달 색 매핑 — PersonalRecords.Entry.label 기준
    @Composable @ReadOnlyComposable
    fun medalColor(forPB: String): Color = when (forPB) {
        "풀" -> medalGold
        "하프" -> medalSilver
        "10K" -> medalBronze
        else -> brand
    }

    @Composable @ReadOnlyComposable
    private fun adaptive(light: Long, dark: Long): Color =
        Color(0xFF000000 or if (isSystemInDarkTheme()) dark else light)

    @Composable @ReadOnlyComposable
    private fun alpha(black: Float, white: Float): Color =
        if (isSystemInDarkTheme()) Color.White.copy(alpha = white) else Color.Black.copy(alpha = black)

    @Composable @ReadOnlyComposable
    private fun soft(base: Color, light: Float, dark: Float): Color =
        base.copy(alpha = if (isSystemInDarkTheme()) dark else light)

    private val blackHanSans = FontFamily(Font(R.font.black_han_sans))
    private val anton = FontFamily(Font(R.font.anton))

    /// 화면 대제목용 디스플레이 서체 — 시안 v0.4의 'Black Han Sans'(번들 .ttf, SIL OFL 1.1).
    /// 한글 전용 굵은 서체라 획이 두꺼워 시안처럼 weight를 따로 주지 않는다.
    fun display(size: TextUnit): TextStyle = TextStyle(fontFamily = blackHanSans, fontSize = size)

    /// 큰 숫자용 디스플레이 서체 — 시안 v0.4의 'Anton'(번들 .ttf, SIL OFL 1.1).
    /// 라틴 숫자 전용이라 한글이 섞이는 자리에는 쓰지 않는다 (한글 글리프가 없어 폴백된다).
    fun numeral(size: TextUnit): TextStyle = TextStyle(fontFamily = anton, fontSize = size)
}

/// 카드 상태 톤 4가지 — 시안의 배지/강조색 매핑 (과부하·주의·유지·개선). 라벨은 엔진 `RRTone.label`
val RRTone.color: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        RRTone.overload -> RR.dang
        RRTone.caution -> RR.warn
        RRTone.steady -> RR.brand
        RRTone.improving -> RR.pos
    }

val RRTone.softColor: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        RRTone.overload -> RR.dangSoft
        RRTone.caution -> RR.warnSoft
        RRTone.steady -> RR.brandSoft
        RRTone.improving -> RR.posSoft
    }

/// iOS 루트의 `.tint(RR.brand)` 대응 — Material 컴포넌트(다이얼로그·날짜 피커·토글 등 Android 관례 그대로 쓰는 것)가
/// RR 색을 따르게 한다. 화면은 MaterialTheme 색이 아니라 RR 토큰을 직접 쓴다.
@Composable
fun RunWrapTheme(content: @Composable () -> Unit) {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = RR.brand, onPrimary = RR.onBrand,
        background = RR.bg, onBackground = RR.text,
        surface = RR.surface, onSurface = RR.text, onSurfaceVariant = RR.text2,
        error = RR.dang,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/// 시안 v0.4의 상태 배지: 굵은 16×4 막대 + 한글 라벨 (+ 선택적 보조 코드).
/// 한글 라벨과 뜻이 겹치던 영문 톤 코드는 뺐다 (이슈 #213).
/// v0.3까지의 soft 배경 알약에서 배경 없는 플랫 형태로 바뀌었다 — 카드 상단에서
/// 색 면적을 줄이고 헤드라인이 주인공이 되게 하려는 의도.
/// - label: 톤 라벨을 문맥에 맞게 덮어쓴다
/// - code: 라벨 옆 보조 표기 (예: 대기질 출처 "에어코리아"). null이면 그리지 않는다
@Composable
fun ToneBadge(tone: RRTone, label: String? = null, code: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 16.dp, height = 4.dp).background(tone.color))
        Text(
            label ?: tone.label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.55.sp),
            color = tone.color,
        )
        if (code != null) {
            Text(
                code,
                style = TextStyle(
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                ),
                color = tone.color.copy(alpha = 0.7f),
            )
        }
    }
}

/// 시안 v0.4의 기본 카드: surface + 1px line + r12 + 옅은 그림자.
/// v0.3의 큰 곡률(r24)에서 스포티한 각진 인상으로 조정됐다.
/// (Android: iOS의 연속 곡률 모서리 대신 원호 모서리. 그림자는 elevation 1dp + RR.shadow 색 — 다크는 0이라 사라진다)
@Composable
fun Modifier.rrCard(radius: Dp = 12.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return shadow(1.dp, shape, clip = false, ambientColor = RR.shadow, spotColor = RR.shadow)
        .background(RR.surface, shape)
        .border(1.dp, RR.line, shape)
}

// iOS `rrTapTarget()`(탭 영역만 최소 44pt로 넓힘, 이슈 #212)은 옮기지 않는다.
// 주의: Compose가 48dp 최소 터치 영역을 자동으로 주는 것은 Material 컴포넌트(IconButton 등)뿐이다 —
// 맨 `Modifier.clickable`에는 없다. 작은 탭 대상은 `Modifier.minimumInteractiveComponentSize()`를 단다
// (iOS와 달리 레이아웃도 48dp로 커진다).

/// 내비게이션 바를 숨긴 화면의 상태바(시계·다이내믹 아일랜드) 영역을 시스템 바 머티리얼로 덮는다 (이슈 #211).
/// 설정처럼 바가 있는 화면은 시스템이 해 주지만, 바를 숨기면 스크롤한 본문이 시계와 그대로 겹친다.
/// `belowTop`은 안전 영역 아래로 더 덮을 높이 (떠 있는 뒤로가기 버튼 줄 등).
/// 시스템 바처럼 쉴 때는 투명하고 본문이 밀려 올라갔을 때만 보인다 — `visible`은 `rrTracksScroll`이 채운다
/// (Android: edge-to-edge 화면 최상위(상태바 밑까지 깔린) 컨테이너에 단다. `.bar` 블러 머티리얼 대신
/// 반투명 RR.bg를 상태바 인셋 + belowTop 높이로 그린다 — 그리기만 하므로 터치를 막지 않는다)
@Composable
fun Modifier.rrStatusBarScrim(belowTop: Dp = 0.dp, visible: Boolean): Modifier {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(150, easing = EaseOut), label = "scrim")
    val height = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + belowTop
    val color = RR.bg.copy(alpha = 0.94f)
    return drawWithContent {
        drawContent()
        if (alpha > 0f) drawRect(color, size = Size(size.width, height.toPx()), alpha = alpha)
    }
}

/// 스크롤 본문이 처음 자리보다 위로 밀려 올라갔는지 (이슈 #211) — `rrStatusBarScrim(visible:)`에 넘긴다.
/// (Android: 맨 위 뷰의 위치 추적 대신 스크롤 상태의 오프셋을 본다)
@Composable
fun ScrollState.rrTracksScroll(): Boolean {
    val scrolled by remember(this) { derivedStateOf { value > 0 } }
    return scrolled
}

@Composable
fun LazyListState.rrTracksScroll(): Boolean {
    val scrolled by remember(this) {
        derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 }
    }
    return scrolled
}

/// 실내(트레드밀) 세션 표시용 소형 텍스트 배지 — 상태(톤)가 아니라 종류 표시라
/// RRTone 매핑을 쓰지 않는다 (계획서 M1). 시안: 10px/650, brandSoft 배경
@Composable
fun IndoorBadge() {
    Text(
        "실내",
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = RR.brand,
        modifier = Modifier
            .background(RR.brandSoft, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.5.dp),
    )
}

/// 화면 상단 모노스페이스 아이브로 라벨 (예: "2026년 8월 2째주")
@Composable
fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        style = TextStyle(
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp,
        ),
        color = RR.text3,
    )
}

/// 브랜드 채움 CTA — 시안 온보딩 primary 버튼(800 16px, radius 10, padding 16).
/// 복원 선택 시트(RootView)도 같은 버튼을 쓴다
/// (iOS는 OnboardingFlowScreen.swift에 있다 — 웨이브 간 의존을 끊으려고 여기로 옮겼다)
@Composable
fun PrimaryButton(title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        title,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center),
        color = RR.onBrand,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RR.brand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
    )
}

/// 접수 상태 소형 배지 — 훈련 상태(RRTone 4단계)가 아니라 모집 상태 표시라
/// IndoorBadge처럼 별도 색 매핑을 쓴다 (Theme.swift 참조). 상태 미상(nil)이면 그리지 않는다.
/// 목록·상세 화면이 같이 쓴다.
/// (iOS는 RaceListScreen.swift에 있다 — Home·RaceDetail·RaceList가 같이 써서 여기로 옮겼다)
@Composable
fun RegisterBadge(status: RaceEngine.RegisterStatus?) {
    if (status == null) return
    val (label, color, softColor) = when (status) {
        is RaceEngine.RegisterStatus.notYet -> Triple("접수예정", RR.brand, RR.brandSoft)
        is RaceEngine.RegisterStatus.open -> Triple("접수중", RR.pos, RR.posSoft)
        RaceEngine.RegisterStatus.closed -> Triple("접수완료", RR.text3, RR.barFill)
    }
    Text(
        label,
        style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier
            .background(softColor, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
    )
}

/// WMO 날씨 코드 → SF 심볼·한국어 상태·팔레트 색 (Open-Meteo weather_code, WMO 4677 기준).
/// 홈 판단 카드의 날씨 타일과 '오늘' 시트가 같은 아이콘을 쓰도록 파일 밖으로 꺼내 뒀다.
/// 멀티컬러 렌더링은 구름 본체를 흰색으로 그려 밝은 배경에서 사라진다(피드백 2026-08-20) —
/// palette 렌더링에 RR 토큰을 입힌다: tint가 본체(구름·해), tint2가 보조 레이어(해·강수·번개)
/// (Android: symbol은 `RRIcons.named`에 넘기는 SF 이름. Material Symbols는 단색이라 tint만 칠해진다)
object WeatherCondition {
    data class Condition(val symbol: String, val label: String, val tint: Color, val tint2: Color)

    @Composable @ReadOnlyComposable
    fun of(code: Int?): Condition? {
        if (code == null) return null
        return when (code) {
            0 -> Condition("sun.max.fill", "맑음", RR.warn, RR.warn)
            1 -> Condition("sun.max.fill", "대체로 맑음", RR.warn, RR.warn)
            2 -> Condition("cloud.sun.fill", "구름 조금", RR.text3, RR.warn)
            3 -> Condition("cloud.fill", "흐림", RR.text3, RR.text3)
            45, 48 -> Condition("cloud.fog.fill", "안개", RR.text3, RR.text3)
            in 51..57 -> Condition("cloud.drizzle.fill", "이슬비", RR.text3, RR.sky)
            in 61..67 -> Condition("cloud.rain.fill", "비", RR.text3, RR.sky)
            in 71..77 -> Condition("cloud.snow.fill", "눈", RR.text3, RR.sky)
            in 80..82 -> Condition("cloud.heavyrain.fill", "소나기", RR.text3, RR.sky)
            85, 86 -> Condition("cloud.snow.fill", "소낙눈", RR.text3, RR.sky)
            in 95..99 -> Condition("cloud.bolt.rain.fill", "뇌우", RR.text3, RR.warn)
            else -> Condition("cloud.fill", "흐림", RR.text3, RR.text3)
        }
    }
}

/// iOS `Picker(.segmented)` 대응 — 회색 트랙 위 선택 칸만 흰 surface로 띄운다 (iOS 세그먼트 모양).
/// ReportHome·SessionDetail·Settings·대회 기록 입력이 같이 쓴다.
@Composable
fun RRSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(RR.barFill, RoundedCornerShape(9.dp))
            .padding(2.dp),
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = i == selected
            val shape = RoundedCornerShape(7.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isSelected) Modifier.shadow(1.dp, shape).background(RR.surface, shape) else Modifier)
                    .clip(shape)
                    .selectable(isSelected, role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = RR.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/// iOS `Stepper` 대응 — [− | +] 두 칸 버튼. 범위 끝에서는 그쪽 버튼이 흐려지고 눌리지 않는다.
/// 설정(주간 목표·심박 기준)과 러닝화 편집이 같이 쓴다.
@Composable
fun RRStepper(value: Double, range: ClosedFloatingPointRange<Double>, step: Double = 1.0, onValue: (Double) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(32.dp).clip(shape).background(RR.barFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperHalf("−", "감소", enabled = value > range.start) {
            onValue((value - step).coerceIn(range))
        }
        Box(Modifier.width(1.dp).height(18.dp).background(RR.line))
        StepperHalf("+", "증가", enabled = value < range.endInclusive) {
            onValue((value + step).coerceIn(range))
        }
    }
}

@Composable
fun RRStepper(value: Int, range: IntRange, onValue: (Int) -> Unit) =
    RRStepper(value.toDouble(), range.first.toDouble()..range.last.toDouble()) { onValue(it.toInt()) }

@Composable
private fun StepperHalf(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(47.dp)
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            color = if (enabled) RR.text else RR.text3,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
