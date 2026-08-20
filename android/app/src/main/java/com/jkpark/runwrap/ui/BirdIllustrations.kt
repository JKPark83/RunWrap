package com.jkpark.runwrap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jkpark.runwrap.engine.GrowthStage

/// 일러스트 고정 팔레트 — 시안 §10. 크림 배경과 다크 배경 양쪽에서 자체 윤곽선으로
/// 대비를 확보하는 게 설계 의도라, 여기서만 RR 토큰 대신 고정색을 쓴다 (iOS와 동일).
private object BirdPalette {
    val outline = Color(0xFF201F1B)
    val cream = Color(0xFFFFF3E4)
    val brand = Color(0xFFFF4D2E)
    val brandDeep = Color(0xFFD93B1F)
    val hint = Color(0xFFB9B3A6)  // 속도선·바닥선
}

/// 시안의 인라인 SVG(240×240 viewBox)를 Compose Canvas로 옮긴 자리표시 일러스트 —
/// iOS `BirdIllustrations.swift` 이식. 좌표는 전부 240 기준 원본 수치 × s(=변/240)다.
@Composable
fun BirdView(stage: GrowthStage, isSulky: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width / 240f
        when (stage) {
            GrowthStage.EGG -> {
                drawEgg(s)
                drawEggDots(s)
            }
            GrowthStage.CRACKED_EGG -> {
                drawEgg(s)
                drawCrack(s)
            }
            GrowthStage.HATCHLING -> drawHatchling(s, isSulky)
            GrowthStage.FLEDGLING -> drawChick(s, isSulky)
            GrowthStage.FLAPPING -> drawFledgling(s, isSulky)
            GrowthStage.FLYING -> drawFlying(s, isSulky)
        }
    }
}

// MARK: - 1~2단계: 알

/// SVG 1·2: `M120 46c34 0 58 40 58 82a58 58 0 01-116 0c0-42 24-82 58-82z`
private fun eggPath(s: Float): Path = Path().apply {
    moveTo(120 * s, 46 * s)
    cubicTo(154 * s, 46 * s, 178 * s, 86 * s, 178 * s, 128 * s)
    // a58 58 0 01-116 0 — 하단 반원 (Compose 각도: 0°=오른쪽, +sweep=아래 경유)
    arcTo(Rect(62 * s, 70 * s, 178 * s, 186 * s), 0f, 180f, false)
    cubicTo(62 * s, 86 * s, 86 * s, 46 * s, 120 * s, 46 * s)
    close()
}

private fun DrawScope.drawEgg(s: Float) {
    drawPath(eggPath(s), BirdPalette.cream)
    drawPath(eggPath(s), BirdPalette.outline, style = Stroke(7 * s))
}

/// 알 표면 브랜드 점 3개
private fun DrawScope.drawEggDots(s: Float) {
    drawCircle(BirdPalette.brand, 5 * s, Offset(100 * s, 110 * s))
    drawCircle(BirdPalette.brand, 6 * s, Offset(132 * s, 132 * s))
    drawCircle(BirdPalette.brand, 4 * s, Offset(118 * s, 164 * s))
}

/// SVG 2: 지그재그 균열 `M78 112l20 12 14-16 18 14 16-12 14 10` + 브랜드 점 2개
private fun DrawScope.drawCrack(s: Float) {
    val crack = Path().apply {
        moveTo(78 * s, 112 * s)
        lineTo(98 * s, 124 * s)
        lineTo(112 * s, 108 * s)
        lineTo(130 * s, 122 * s)
        lineTo(146 * s, 110 * s)
        lineTo(160 * s, 120 * s)
    }
    drawPath(
        crack, BirdPalette.outline,
        style = Stroke(5.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawCircle(BirdPalette.brand, 5 * s, Offset(100 * s, 150 * s))
    drawCircle(BirdPalette.brand, 4 * s, Offset(134 * s, 160 * s))
}

// MARK: - 3단계: 부화

/// SVG 3·4: 브랜드 원 몸통 + 눈 2개 + 부리 + 크림 껍질(지그재그 상단)
private fun DrawScope.drawHatchling(s: Float, isSulky: Boolean) {
    // 몸통 원 d84 — strokeBorder 6.5는 테두리 절반만큼 안쪽으로 (iOS strokeBorder 대응)
    drawCircle(BirdPalette.brand, 42 * s, Offset(120 * s, 112 * s))
    drawCircle(
        BirdPalette.outline, (42 - 3.25f) * s, Offset(120 * s, 112 * s),
        style = Stroke(6.5f * s),
    )

    if (isSulky) {
        drawSulkyEye(104f, 105f, s)
        drawSulkyEye(136f, 105f, s)
    } else {
        drawNormalEye(104f, 104f, 106f, 101.5f, s)
        drawNormalEye(136f, 104f, 138f, 101.5f, s)
    }

    // 부리 `M112 120l8 10 8-10z`
    drawBeak(listOf(112f to 120f, 120f to 130f, 128f to 120f), s)

    drawEggshellBottom(s)
}

/// 정상 눈: 검은 원 + 크림 하이라이트 작은 원
private fun DrawScope.drawNormalEye(cx: Float, cy: Float, hcx: Float, hcy: Float, s: Float) {
    drawCircle(BirdPalette.outline, 6 * s, Offset(cx * s, cy * s))
    drawCircle(BirdPalette.cream, 2 * s, Offset(hcx * s, hcy * s))
}

/// 시무룩 눈: 아래로 볼록한 반원 채움 + 그 위 수평 막대
private fun DrawScope.drawSulkyEye(cx: Float, cy: Float, s: Float) {
    drawArc(
        BirdPalette.outline, startAngle = 0f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset((cx - 8) * s, (cy - 8) * s), size = Size(16 * s, 16 * s),
    )
    drawRect(
        BirdPalette.outline,
        topLeft = Offset((cx - 9) * s, (cy - 2) * s), size = Size(18 * s, 4 * s),
    )
}

/// 크림 삼각 부리 — outline 스트로크 포함
private fun DrawScope.drawBeak(points: List<Pair<Float, Float>>, s: Float) {
    val path = Path().apply {
        moveTo(points.first().first * s, points.first().second * s)
        points.drop(1).forEach { (x, y) -> lineTo(x * s, y * s) }
        close()
    }
    drawPath(path, BirdPalette.cream)
    drawPath(path, BirdPalette.outline, style = Stroke(4 * s, join = StrokeJoin.Round))
}

/// `M62 140l16-14 14 13 … c… z` — 지그재그 상단 + 타원 하단의 깨진 껍질
private fun DrawScope.drawEggshellBottom(s: Float) {
    val path = Path().apply {
        moveTo(62 * s, 140 * s)
        lineTo(78 * s, 126 * s)
        lineTo(92 * s, 139 * s)
        lineTo(106 * s, 126 * s)
        lineTo(120 * s, 139 * s)
        lineTo(134 * s, 126 * s)
        lineTo(148 * s, 139 * s)
        lineTo(164 * s, 126 * s)
        cubicTo(166 * s, 134 * s, 167 * s, 140 * s, 167 * s, 146 * s)
        cubicTo(167 * s, 176 * s, 140 * s, 196 * s, 106 * s, 196 * s)
        cubicTo(72 * s, 196 * s, 45 * s, 176 * s, 45 * s, 146 * s)
        cubicTo(45 * s, 140 * s, 46 * s, 134 * s, 62 * s, 140 * s)
        close()
    }
    drawPath(path, BirdPalette.cream)
    drawPath(path, BirdPalette.outline, style = Stroke(6.5f * s, join = StrokeJoin.Round))
}

// MARK: - 4단계: 어린 새

/// SVG 5·6: 머리깃 + 브랜드 원 + 크림 배 + 날개 2개 + 눈 + 부리 + 다리
private fun DrawScope.drawChick(s: Float, isSulky: Boolean) {
    drawTuft(66f, s)

    drawCircle(BirdPalette.brand, 58 * s, Offset(120 * s, 130 * s))
    drawCircle(
        BirdPalette.outline, (58 - 3.5f) * s, Offset(120 * s, 130 * s),
        style = Stroke(7 * s),
    )

    // 배(크림 타원 60×46)
    drawOval(BirdPalette.cream, Offset(90 * s, 129 * s), Size(60 * s, 46 * s))

    // 좌우 날개 `M66 124c-12 8-16 24-8 36 12-2 22-14 25-27z` / 대칭
    drawWing(s) {
        moveTo(66 * s, 124 * s)
        cubicTo(54 * s, 132 * s, 50 * s, 148 * s, 58 * s, 160 * s)
        cubicTo(70 * s, 158 * s, 80 * s, 146 * s, 83 * s, 133 * s)
        close()
    }
    drawWing(s) {
        moveTo(174 * s, 124 * s)
        cubicTo(186 * s, 132 * s, 190 * s, 148 * s, 182 * s, 160 * s)
        cubicTo(170 * s, 158 * s, 160 * s, 146 * s, 157 * s, 133 * s)
        close()
    }

    if (isSulky) {
        drawSulkyEye(100f, 122f, s)
        drawSulkyEye(136f, 122f, s)
    } else {
        drawNormalEye(102f, 118f, 104f, 115.5f, s)
        drawNormalEye(138f, 118f, 140f, 115.5f, s)
    }

    // 부리 `M111 132l9 11 9-11z`
    drawBeak(listOf(111f to 132f, 120f to 143f, 129f to 132f), s)

    // 다리 `M106 186v16m28-16v16` + 발 `M96 202h18m12 0h18`
    val legs = Path().apply {
        moveTo(106 * s, 186 * s); lineTo(106 * s, 202 * s)
        moveTo(134 * s, 186 * s); lineTo(134 * s, 202 * s)
        moveTo(96 * s, 202 * s); lineTo(114 * s, 202 * s)
        moveTo(126 * s, 202 * s); lineTo(144 * s, 202 * s)
    }
    drawPath(legs, BirdPalette.outline, style = Stroke(6 * s, cap = StrokeCap.Round))
}

/// 머리깃 `M120 (startY)c-4-12 4-20 10-22` — 시작 y만 다르게 재사용
private fun DrawScope.drawTuft(startY: Float, s: Float) {
    val path = Path().apply {
        moveTo(120 * s, startY * s)
        cubicTo(116 * s, (startY - 12) * s, 126 * s, (startY - 20) * s, 130 * s, (startY - 22) * s)
    }
    drawPath(path, BirdPalette.outline, style = Stroke(5.5f * s, cap = StrokeCap.Round))
}

/// 날개 공용 — brandDeep 채움 + outline 스트로크
private fun DrawScope.drawWing(s: Float, build: Path.() -> Unit) {
    val path = Path().apply(build)
    drawPath(path, BirdPalette.brandDeep)
    drawPath(path, BirdPalette.outline, style = Stroke(5.5f * s, join = StrokeJoin.Round))
}

// MARK: - 5단계: 날갯짓

/// SVG 7·8: 활짝 편 날개 + 벌린 다리 + 바닥선
private fun DrawScope.drawFledgling(s: Float, isSulky: Boolean) {
    drawTuft(78f, s)

    drawCircle(BirdPalette.brand, 52 * s, Offset(120 * s, 138 * s))
    drawCircle(
        BirdPalette.outline, (52 - 3.5f) * s, Offset(120 * s, 138 * s),
        style = Stroke(7 * s),
    )

    drawOval(BirdPalette.cream, Offset(93 * s, 138 * s), Size(54 * s, 40 * s))

    // 펼친 날개 `M76 120C58 96 42 88 26 92c8 18 24 34 48 40z` / 대칭
    drawWing(s) {
        moveTo(76 * s, 120 * s)
        cubicTo(58 * s, 96 * s, 42 * s, 88 * s, 26 * s, 92 * s)
        cubicTo(34 * s, 110 * s, 50 * s, 126 * s, 74 * s, 132 * s)
        close()
    }
    drawWing(s) {
        moveTo(164 * s, 120 * s)
        cubicTo(182 * s, 96 * s, 198 * s, 88 * s, 214 * s, 92 * s)
        cubicTo(206 * s, 110 * s, 190 * s, 126 * s, 166 * s, 132 * s)
        close()
    }

    if (isSulky) {
        drawSulkyEye(104f, 132f, s)
        drawSulkyEye(136f, 132f, s)
    } else {
        drawNormalEye(104f, 128f, 106f, 125.5f, s)
        drawNormalEye(136f, 128f, 138f, 125.5f, s)
    }

    // 부리 `M112 142l8 10 8-10z`
    drawBeak(listOf(112f to 142f, 120f to 152f, 128f to 142f), s)

    // 다리 `M104 190l4 12m28-12l-4 12`
    val legs = Path().apply {
        moveTo(104 * s, 190 * s); lineTo(108 * s, 202 * s)
        moveTo(132 * s, 190 * s); lineTo(128 * s, 202 * s)
    }
    drawPath(legs, BirdPalette.outline, style = Stroke(6 * s, cap = StrokeCap.Round))

    // 바닥선 `M84 218c22 10 50 10 72 0`
    val ground = Path().apply {
        moveTo(84 * s, 218 * s)
        cubicTo(106 * s, 228 * s, 134 * s, 228 * s, 156 * s, 218 * s)
    }
    drawPath(ground, BirdPalette.hint, style = Stroke(5 * s, cap = StrokeCap.Round))
}

// MARK: - 6단계: 나는 새

/// SVG 9·10: 기울어진 타원 몸통 + 큰 날개/꼬리 + 속도선
private fun DrawScope.drawFlying(s: Float, isSulky: Boolean) {
    // 속도선 `M40 92h26m-38 22h22`
    val speedLines = Path().apply {
        moveTo(40 * s, 92 * s); lineTo(66 * s, 92 * s)
        moveTo(2 * s, 114 * s); lineTo(24 * s, 114 * s)
    }
    drawPath(speedLines, BirdPalette.hint, style = Stroke(5 * s, cap = StrokeCap.Round))

    // 기울어진 몸통 타원 rx54 ry40, rotate(-10° @128,132) — 테두리는 strokeBorder처럼 안쪽으로
    rotate(-10f, Offset(128 * s, 132 * s)) {
        drawOval(BirdPalette.brand, Offset(74 * s, 92 * s), Size(108 * s, 80 * s))
        drawOval(
            BirdPalette.outline,
            Offset((74 + 3.5f) * s, (92 + 3.5f) * s), Size(101 * s, 73 * s),
            style = Stroke(7 * s),
        )
    }

    // 위쪽 날개 `M118 104C108 68 120 44 148 36c6 26-2 56-24 74z`
    drawWing(s) {
        moveTo(118 * s, 104 * s)
        cubicTo(108 * s, 68 * s, 120 * s, 44 * s, 148 * s, 36 * s)
        cubicTo(154 * s, 62 * s, 146 * s, 92 * s, 124 * s, 110 * s)
        close()
    }
    // 아래쪽 날개 `M124 162c-6 20 0 36 16 44 8-16 4-36-6-48z`
    drawWing(s) {
        moveTo(124 * s, 162 * s)
        cubicTo(118 * s, 182 * s, 124 * s, 198 * s, 140 * s, 206 * s)
        cubicTo(148 * s, 190 * s, 144 * s, 170 * s, 134 * s, 158 * s)
        close()
    }
    // 꼬리 깃털 `M82 142L44 130l12 16-16 10 38 6z`
    drawWing(s) {
        moveTo(82 * s, 142 * s)
        lineTo(44 * s, 130 * s)
        lineTo(56 * s, 146 * s)
        lineTo(40 * s, 156 * s)
        lineTo(78 * s, 162 * s)
        close()
    }

    // 배(크림 타원 rx24 ry15, 같은 축으로 회전)
    rotate(-10f, Offset(136 * s, 146 * s)) {
        drawOval(BirdPalette.cream, Offset(112 * s, 131 * s), Size(48 * s, 30 * s))
    }

    if (isSulky) {
        drawSulkyEye(158f, 116f, s)
    } else {
        drawNormalEye(158f, 112f, 160f, 109.5f, s)
    }

    // 부리 `M182 116l24 8-22 9z`
    drawBeak(listOf(182f to 116f, 206f to 124f, 184f to 133f), s)
}
