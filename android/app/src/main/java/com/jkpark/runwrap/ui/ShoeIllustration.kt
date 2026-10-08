package com.jkpark.runwrap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/// 러닝화 기본 일러스트 — 사진이 없는 신발의 썸네일·카드에 쓴다 (이슈 #206).
/// 마스코트 새(BirdIllustrations.swift)와 한 가족으로 보이도록 같은 240×240 좌표계·굵은 윤곽선·
/// 고정 팔레트를 그대로 따른다. 자체 윤곽선으로 라이트/다크 양쪽 대비를 확보하는 설계라
/// BirdIllustrations와 같은 예외로 RR 토큰 대신 BirdPalette 고정색을 쓴다.
/// 옆면 장식은 실제 브랜드 마크(스우시·삼선 등)를 연상시키지 않도록 둥근 힐 카운터 패치 하나만 둔다.
/// (Android: 크기는 modifier로 준다. 캔버스를 s배로 스케일한 뒤 240 좌표·선 굵기를 그대로 쓴다)
@Composable
fun ShoeView(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width / 240f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            // 힐 풀탭 — 갑피 뒤에 깔려 윗부분만 보인다
            fillStroke(pullTab(), BirdPalette.brandDeep, 5.5f)

            // 갑피(크림) — 아랫변은 미드솔이 덮는다
            fillStroke(upper(), BirdPalette.cream, 7f, cap = StrokeCap.Round)

            // 힐 카운터 패치
            fillStroke(heelPatch(), BirdPalette.brand, 5.5f)

            // 끈 3줄
            drawPath(laces(), BirdPalette.outline, style = Stroke(5.5f, cap = StrokeCap.Round))

            // 미드솔(브랜드) — 바닥이 살짝 볼록한 로커형
            fillStroke(midsole(), BirdPalette.brand, 7f, cap = StrokeCap.Round)

            // 바닥선
            drawPath(Path().apply {
                moveTo(64f, 192f)
                cubicTo(100f, 198f, 140f, 198f, 176f, 192f)
            }, BirdPalette.hint, style = Stroke(5f, cap = StrokeCap.Round))
        }
    }
}

/// `M40 84C36 72 40 64 50 64c6 0 8 6 6 14z`
private fun pullTab() = Path().apply {
    moveTo(40f, 84f)
    cubicTo(36f, 72f, 40f, 64f, 50f, 64f)
    cubicTo(56f, 64f, 58f, 70f, 56f, 78f)
    close()
}

/// 뒤꿈치 → 발목 칼라 → 텅 → 발등 → 둥근 앞코 → 아랫변
private fun upper() = Path().apply {
    moveTo(36f, 140f)
    cubicTo(32f, 118f, 34f, 94f, 44f, 80f)
    cubicTo(60f, 88f, 76f, 92f, 92f, 88f)
    lineTo(104f, 76f)
    cubicTo(108f, 74f, 112f, 76f, 114f, 80f)
    cubicTo(130f, 100f, 160f, 110f, 186f, 116f)
    cubicTo(202f, 120f, 210f, 128f, 208f, 140f)
    close()
}

/// `M42 138C40 120 44 106 56 102c14-4 26 8 28 22 1 9-4 14-12 14z`
private fun heelPatch() = Path().apply {
    moveTo(42f, 138f)
    cubicTo(40f, 120f, 44f, 106f, 56f, 102f)
    cubicTo(70f, 98f, 82f, 110f, 84f, 124f)
    cubicTo(85f, 133f, 80f, 138f, 72f, 138f)
    close()
}

/// `M116 116l10-10m8 15l10-9m8 14l10-7`
private fun laces() = Path().apply {
    val strokes = listOf(
        floatArrayOf(116f, 116f, 126f, 106f), floatArrayOf(134f, 121f, 144f, 112f), floatArrayOf(152f, 126f, 162f, 119f),
    )
    for ((x0, y0, x1, y1) in strokes) {
        moveTo(x0, y0)
        lineTo(x1, y1)
    }
}

/// `M34 136H200c12 0 16 6 14 14-4 12-18 16-34 16-40 5-80 5-120 0-16 0-26-8-28-18-1-6-1-12 2-12z`
private fun midsole() = Path().apply {
    moveTo(34f, 136f)
    lineTo(200f, 136f)
    cubicTo(212f, 136f, 216f, 142f, 214f, 150f)
    cubicTo(210f, 162f, 196f, 166f, 180f, 166f)
    cubicTo(140f, 171f, 100f, 171f, 60f, 166f)
    cubicTo(44f, 166f, 34f, 158f, 32f, 148f)
    cubicTo(31f, 142f, 31f, 136f, 34f, 136f)
    close()
}
