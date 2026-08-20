package com.jkpark.runwrap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/// 디자인 토큰 — iOS `Theme.swift`의 rr-theme 팔레트(claude.design "Runner Report" v0.4)를
/// 수치 그대로 이식. 라이트/다크 팔레트를 CompositionLocal로 내려 화면은 `RR.bg`처럼
/// iOS와 같은 표기로만 색을 읽는다 (화면 코드에서 색 리터럴 금지 — iOS 규칙 유지).
@Immutable
data class RRPalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val brand: Color,
    val pos: Color,
    /// 강수(비·눈) 심볼 전용 청색 — 웜 팔레트의 유일한 한랭 색
    val sky: Color,
    val warn: Color,
    val dang: Color,
    /// 시안: light rgba(20,20,16,.13) / dark rgba(255,255,255,.11)
    val line: Color,
    /// 차트 막대 바탕 — light rgba(20,20,16,.09) / dark rgba(255,255,255,.10)
    val barFill: Color,
    val brandSoft: Color,
    val posSoft: Color,
    val warnSoft: Color,
    val dangSoft: Color,
)

private fun rgb(hex: Long) = Color(0xFF00_0000L or hex)

val LightRRPalette = RRPalette(
    bg = rgb(0xF2F0EA), surface = rgb(0xFFFFFF), surface2 = rgb(0xEAE7E0),
    text = rgb(0x12120F), text2 = rgb(0x54544E), text3 = rgb(0x8F8F86),
    brand = rgb(0xFF4D2E), pos = rgb(0x0E9146), sky = rgb(0x2E8BD9),
    warn = rgb(0xC77700), dang = rgb(0xD91F00),
    line = Color.Black.copy(alpha = 0.13f), barFill = Color.Black.copy(alpha = 0.09f),
    brandSoft = rgb(0xFF4D2E).copy(alpha = 0.12f), posSoft = rgb(0x0E9146).copy(alpha = 0.12f),
    warnSoft = rgb(0xC77700).copy(alpha = 0.13f), dangSoft = rgb(0xD91F00).copy(alpha = 0.11f),
)

val DarkRRPalette = RRPalette(
    bg = rgb(0x0A0A09), surface = rgb(0x151513), surface2 = rgb(0x1E1E1B),
    text = rgb(0xF5F4EF), text2 = rgb(0xA8A79E), text3 = rgb(0x73736C),
    brand = rgb(0xFF5A3C), pos = rgb(0x35E077), sky = rgb(0x5AAEFF),
    warn = rgb(0xFFAE00), dang = rgb(0xFF3B30),
    line = Color.White.copy(alpha = 0.11f), barFill = Color.White.copy(alpha = 0.10f),
    brandSoft = rgb(0xFF5A3C).copy(alpha = 0.18f), posSoft = rgb(0x35E077).copy(alpha = 0.16f),
    warnSoft = rgb(0xFFAE00).copy(alpha = 0.16f), dangSoft = rgb(0xFF3B30).copy(alpha = 0.16f),
)

val LocalRRPalette = staticCompositionLocalOf { LightRRPalette }

/// 화면에서 `RR.bg`처럼 iOS와 같은 표기로 읽는 접근자
object RR {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.bg
    val surface: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.surface
    val surface2: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.surface2
    val text: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.text
    val text2: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.text2
    val text3: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.text3
    val brand: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.brand
    val pos: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.pos
    val sky: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.sky
    val warn: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.warn
    val dang: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.dang
    val line: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.line
    val barFill: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.barFill
    val brandSoft: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.brandSoft
    val posSoft: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.posSoft
    val warnSoft: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.warnSoft
    val dangSoft: Color @Composable @ReadOnlyComposable get() = LocalRRPalette.current.dangSoft
}

/// 앱 루트 테마 — 시스템 모드에 따라 RR 팔레트를 내려보낸다.
@Composable
fun RunWrapTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkRRPalette else LightRRPalette
    CompositionLocalProvider(LocalRRPalette provides palette) {
        MaterialTheme(content = content)
    }
}

/// 카드 상태 톤 4가지 — 과부하·주의·유지·개선 (iOS `RRTone` 대응).
/// 엔진은 색이 아니라 이 톤까지만 결정한다. label/code는 순수 값이라 JVM 테스트 가능,
/// 색 매핑은 팔레트가 필요해 @Composable 접근자로만 연다.
enum class RRTone {
    OVERLOAD, CAUTION, STEADY, IMPROVING;

    val label: String
        get() = when (this) {
            OVERLOAD -> "과부하 구간"
            CAUTION -> "주의 구간"
            STEADY -> "유지 중"
            IMPROVING -> "좋아지는 중"
        }

    val code: String
        get() = when (this) {
            OVERLOAD -> "OVERLOAD"
            CAUTION -> "CAUTION"
            STEADY -> "STEADY"
            IMPROVING -> "IMPROVING"
        }

    val color: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            OVERLOAD -> RR.dang
            CAUTION -> RR.warn
            STEADY -> RR.brand
            IMPROVING -> RR.pos
        }

    val softColor: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            OVERLOAD -> RR.dangSoft
            CAUTION -> RR.warnSoft
            STEADY -> RR.brandSoft
            IMPROVING -> RR.posSoft
        }
}
