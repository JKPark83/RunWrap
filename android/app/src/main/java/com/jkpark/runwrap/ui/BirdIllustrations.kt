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
import androidx.compose.ui.graphics.drawscope.withTransform
import com.jkpark.runwrap.engine.GrowthStage

// 일러스트 고정 팔레트 — 시안 §10. 크림 배경(#F2F0EA)과 다크 배경(#0A0A09) 양쪽에서
// 자체 윤곽선으로 대비를 확보하는 게 설계 의도라, 여기서만 RR 토큰 대신 고정색을 쓴다.
internal object BirdPalette {
    val outline = Color(0xFF201F1B)     // #201F1B
    val cream = Color(0xFFFFF3E4)       // #FFF3E4
    val brand = Color(0xFFFF4D2E)       // #FF4D2E
    val brandDeep = Color(0xFFD93B1F)   // #D93B1F
    val hint = Color(0xFFB9B3A6)        // #B9B3A6 (속도선·바닥선)
}

/// 시안의 인라인 SVG(240×240 viewBox)를 SwiftUI Path로 옮긴 자리표시 일러스트.
/// 실제 1024×1024 에셋으로 교체할 때 레이아웃을 건드리지 않도록 정사각 바운딩 박스를 유지한다.
/// (Android: 크기는 modifier로 준다. 캔버스를 s배로 스케일한 뒤 240 좌표·선 굵기를 그대로 쓴다)
@Composable
fun BirdView(stage: GrowthStage, modifier: Modifier = Modifier, isSulky: Boolean = false) {
    Canvas(modifier) {
        val s = size.width / 240f
        // iOS `.position(…).rotationEffect`는 위치 지정된 뷰(=컨테이너 전체)의 중심을 축으로 돈다 — 240 좌표로 환산
        val center = Offset(120f, size.height / 2f / s)
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            when (stage) {
                GrowthStage.egg -> {
                    fillStroke(eggShape(), BirdPalette.cream, 7f, join = StrokeJoin.Miter)
                    eggDots()
                }
                GrowthStage.crackedEgg -> {
                    fillStroke(eggShape(), BirdPalette.cream, 7f, join = StrokeJoin.Miter)
                    drawPath(crackZigzag(), BirdPalette.outline,
                             style = Stroke(5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawCircle(BirdPalette.brand, 5f, Offset(100f, 150f))
                    drawCircle(BirdPalette.brand, 4f, Offset(134f, 160f))
                }
                GrowthStage.hatchling -> hatchingView(isSulky)
                GrowthStage.fledgling -> chickView(isSulky)
                GrowthStage.flapping -> fledglingView(isSulky)
                GrowthStage.flying -> flyingView(isSulky, center)
            }
        }
    }
}

private fun path(build: Path.() -> Unit) = Path().apply(build)

/// 채움 + outline 스트로크 — iOS의 `Shape().fill` 다음 `Shape().stroke`. 굵기는 240 좌표 단위
internal fun DrawScope.fillStroke(
    path: Path, fill: Color, width: Float,
    cap: StrokeCap = StrokeCap.Butt, join: StrokeJoin = StrokeJoin.Round,
) {
    drawPath(path, fill)
    drawPath(path, BirdPalette.outline, style = Stroke(width, cap = cap, join = join))
}

/// `Circle().strokeBorder` — 테두리가 프레임 안쪽에 그려지므로 반지름을 선 굵기 절반만큼 줄인다
private fun DrawScope.circleBorder(diameter: Float, center: Offset, width: Float) =
    drawCircle(BirdPalette.outline, diameter / 2f - width / 2f, center, style = Stroke(width))

// MARK: - 1~2단계: 알

/// SVG 1·2: `M120 46c34 0 58 40 58 82a58 58 0 01-116 0c0-42 24-82 58-82z`
private fun eggShape() = path {
    moveTo(120f, 46f)
    cubicTo(154f, 46f, 178f, 86f, 178f, 128f)
    // a58 58 0 01-116 0 — 반지름 58 원호 절반을 3차 베지어로 근사
    // (Android: SwiftUI `clockwise: false`(y-아래 좌표에서 각도 증가 방향) = 양수 sweep — 아래쪽을 지난다)
    arcTo(Rect(Offset(120f, 128f), 58f), 0f, 180f, false)
    cubicTo(62f, 86f, 86f, 46f, 120f, 46f)
    close()
}

/// SVG 1: 알 표면 브랜드 점 3개 (130px 결과 카드용)
private fun DrawScope.eggDots() {
    drawCircle(BirdPalette.brand, 5f, Offset(100f, 110f))
    drawCircle(BirdPalette.brand, 6f, Offset(132f, 132f))
    drawCircle(BirdPalette.brand, 4f, Offset(118f, 164f))
}

/// SVG 2: 지그재그 균열 `M78 112l20 12 14-16 18 14 16-12 14 10`
private fun crackZigzag() = path {
    moveTo(78f, 112f)
    lineTo(98f, 124f)
    lineTo(112f, 108f)
    lineTo(130f, 122f)
    lineTo(146f, 110f)
    lineTo(160f, 120f)
}

// MARK: - 3~4단계: 부화

/// SVG 3·4: 브랜드 원 몸통 + 눈 2개 + 부리 + 크림 껍질(지그재그 상단)
private fun DrawScope.hatchingView(isSulky: Boolean) {
    drawCircle(BirdPalette.brand, 42f, Offset(120f, 112f))
    circleBorder(84f, Offset(120f, 112f), 6.5f)

    if (isSulky) {
        sulkyEye(104f, 105f)
        sulkyEye(136f, 105f)
    } else {
        normalEye(104f, 104f, 106f, 101.5f)
        normalEye(136f, 104f, 138f, 101.5f)
    }

    // 부리 `M112 120l8 10 8-10z`
    beakTriangle(112f, 120f, 120f, 130f, 128f, 120f)

    // 크림 껍질(하단) `M62 140l16-14 14 13 14-13 14 13 14-13 14 13 16-14c2 8 3 14 3 20 0 30-27 50-61 50s-61-20-61-50c0-6 1-12 3-20z`
    fillStroke(eggshellBottomShape(), BirdPalette.cream, 6.5f)
}

/// 정상 눈: 검은 원 + 흰 하이라이트 작은 원
private fun DrawScope.normalEye(cx: Float, cy: Float, hcx: Float, hcy: Float) {
    drawCircle(BirdPalette.outline, 6f, Offset(cx, cy))
    drawCircle(BirdPalette.cream, 2f, Offset(hcx, hcy))
}

/// 시무룩 눈: 아래로 감긴 반달(`a 8 8 0 0 0 16 0z` 채움) + 그 위 수평선
private fun DrawScope.sulkyEye(cx: Float, cy: Float) {
    drawPath(sulkyEyeShape(cx, cy), BirdPalette.outline)
    drawRect(BirdPalette.outline, Offset(cx - 9f, cy - 2f), Size(18f, 4f))
}

/// `M(cx-8) cy a8 8 0 0 0 16 0z` — 중심 아래로 볼록한 반원(하현 눈썹형 눈)
/// (Android: iOS 코드 그대로 180°→360° `clockwise: false` = 양수 sweep으로 옮긴다)
private fun sulkyEyeShape(cx: Float, cy: Float) = path {
    moveTo(cx - 8f, cy)
    arcTo(Rect(Offset(cx, cy), 8f), 180f, 180f, false)
    close()
}

/// 크림 삼각 부리 — outline 스트로크 포함
private fun DrawScope.beakTriangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
    val beak = path {
        moveTo(x0, y0)
        lineTo(x1, y1)
        lineTo(x2, y2)
        close()
    }
    fillStroke(beak, BirdPalette.cream, 4f)
}

/// `M62 140l16-14 14 13 14-13 14 13 14-13 14 13 16-14c2 8 3 14 3 20 0 30-27 50-61 50s-61-20-61-50c0-6 1-12 3-20z`
private fun eggshellBottomShape() = path {
    moveTo(62f, 140f)
    lineTo(78f, 126f)
    lineTo(92f, 139f)
    lineTo(106f, 126f)
    lineTo(120f, 139f)
    lineTo(134f, 126f)
    lineTo(148f, 139f)
    lineTo(164f, 126f)
    // c2 8 3 14 3 20 → 오른쪽으로 완만히 내려가는 곡선
    cubicTo(166f, 134f, 167f, 140f, 167f, 146f)
    // 0 30-27 50-61 50 → 오른쪽 아래로 향하는 큰 원호(타원 하단부) 근사
    cubicTo(167f, 176f, 140f, 196f, 106f, 196f)
    // s-61-20-61-50 → 대칭으로 왼쪽 아래에서 위로
    cubicTo(72f, 196f, 45f, 176f, 45f, 146f)
    cubicTo(45f, 140f, 46f, 134f, 62f, 140f)
    close()
}

// MARK: - 5~6단계: 어린 새

/// SVG 5·6: 머리깃 + 브랜드 원 + 크림 배 + 날개 2개 + 눈 + 부리 + 다리
private fun DrawScope.chickView(isSulky: Boolean) {
    // 머리깃 `M120 66c-4-12 4-20 10-22`
    drawPath(tuftShape(66f), BirdPalette.outline, style = Stroke(5.5f, cap = StrokeCap.Round))

    // 몸통 원
    drawCircle(BirdPalette.brand, 58f, Offset(120f, 130f))
    circleBorder(116f, Offset(120f, 130f), 7f)

    // 배(크림 타원)
    drawOval(BirdPalette.cream, Offset(90f, 129f), Size(60f, 46f))

    // 좌우 날개
    // `M66 124c-12 8-16 24-8 36 12-2 22-14 25-27z`
    fillStroke(path {
        moveTo(66f, 124f)
        cubicTo(54f, 132f, 50f, 148f, 58f, 160f)
        cubicTo(70f, 158f, 80f, 146f, 83f, 133f)
        close()
    }, BirdPalette.brandDeep, 5.5f)
    // `M174 124c12 8 16 24 8 36-12-2-22-14-25-27z`
    fillStroke(path {
        moveTo(174f, 124f)
        cubicTo(186f, 132f, 190f, 148f, 182f, 160f)
        cubicTo(170f, 158f, 160f, 146f, 157f, 133f)
        close()
    }, BirdPalette.brandDeep, 5.5f)

    if (isSulky) {
        sulkyEye(100f, 122f)
        sulkyEye(136f, 122f)
    } else {
        normalEye(102f, 118f, 104f, 115.5f)
        normalEye(138f, 118f, 140f, 115.5f)
    }

    // 부리 `M111 132l9 11 9-11z`
    beakTriangle(111f, 132f, 120f, 143f, 129f, 132f)

    // 다리 `M106 186v16m28-16v16` + `M96 202h18m12 0h18`
    val legs = Stroke(6f, cap = StrokeCap.Round)
    drawPath(path {
        moveTo(106f, 186f); lineTo(106f, 202f)
        moveTo(134f, 186f); lineTo(134f, 202f)
    }, BirdPalette.outline, style = legs)
    drawPath(path {
        moveTo(96f, 202f); lineTo(114f, 202f)
        moveTo(126f, 202f); lineTo(144f, 202f)
    }, BirdPalette.outline, style = legs)
}

/// `M120 (startY)c-4-12 4-20 10-22` — 시작 y좌표만 다른 머리깃 곡선 재사용
private fun tuftShape(startY: Float) = path {
    moveTo(120f, startY)
    cubicTo(116f, startY - 12f, 126f, startY - 20f, 130f, startY - 22f)
}

// MARK: - 7~8단계: 날갯짓

/// SVG 7·8: 펼친 날개 + 다리 + 바닥선
private fun DrawScope.fledglingView(isSulky: Boolean) {
    // 머리깃 `M120 78c-4-12 4-20 10-22`
    drawPath(tuftShape(78f), BirdPalette.outline, style = Stroke(5.5f, cap = StrokeCap.Round))

    drawCircle(BirdPalette.brand, 52f, Offset(120f, 138f))
    circleBorder(104f, Offset(120f, 138f), 7f)

    drawOval(BirdPalette.cream, Offset(93f, 138f), Size(54f, 40f))

    // 활짝 펼친 날개 좌우
    // `M76 120C58 96 42 88 26 92c8 18 24 34 48 40z`
    fillStroke(path {
        moveTo(76f, 120f)
        cubicTo(58f, 96f, 42f, 88f, 26f, 92f)
        cubicTo(34f, 110f, 50f, 126f, 74f, 132f)
        close()
    }, BirdPalette.brandDeep, 5.5f)
    // `M164 120c18-24 34-32 50-28-8 18-24 34-48 40z`
    fillStroke(path {
        moveTo(164f, 120f)
        cubicTo(182f, 96f, 198f, 88f, 214f, 92f)
        cubicTo(206f, 110f, 190f, 126f, 166f, 132f)
        close()
    }, BirdPalette.brandDeep, 5.5f)

    if (isSulky) {
        sulkyEye(104f, 132f)
        sulkyEye(136f, 132f)
    } else {
        normalEye(104f, 128f, 106f, 125.5f)
        normalEye(136f, 128f, 138f, 125.5f)
    }

    // 부리 `M112 142l8 10 8-10z`
    beakTriangle(112f, 142f, 120f, 152f, 128f, 142f)

    // 다리 `M104 190l4 12m28-12l-4 12`
    drawPath(path {
        moveTo(104f, 190f); lineTo(108f, 202f)
        moveTo(132f, 190f); lineTo(128f, 202f)
    }, BirdPalette.outline, style = Stroke(6f, cap = StrokeCap.Round))

    // 바닥선 `M84 218c22 10 50 10 72 0`
    drawPath(path {
        moveTo(84f, 218f)
        cubicTo(106f, 228f, 134f, 228f, 156f, 218f)
    }, BirdPalette.hint, style = Stroke(5f, cap = StrokeCap.Round))
}

// MARK: - 9~10단계: 나는 새

/// SVG 9·10: 기울어진 타원 몸통 + 큰 날개/꼬리 + 속도선
/// (Android: iOS는 회전축이 SVG의 (128,132)가 아니라 컨테이너 중심이다 — 화면 결과를 맞추려고 그대로 따른다)
private fun DrawScope.flyingView(isSulky: Boolean, center: Offset) {
    // 속도선 `M40 92h26m-38 22h22`
    drawPath(path {
        moveTo(40f, 92f); lineTo(66f, 92f)
        moveTo(2f, 114f); lineTo(24f, 114f)
    }, BirdPalette.hint, style = Stroke(5f, cap = StrokeCap.Round))

    // 기울어진 몸통 타원 `rotate(-10 128 132)` rx54 ry40
    rotate(-10f, center) {
        drawOval(BirdPalette.brand, Offset(74f, 92f), Size(108f, 80f))
        // strokeBorder — 선 굵기 절반만큼 안으로
        drawOval(BirdPalette.outline, Offset(77.5f, 95.5f), Size(101f, 73f), style = Stroke(7f))
    }

    // 위쪽 날개(꼬리깃) `M118 104C108 68 120 44 148 36c6 26-2 56-24 74z`
    fillStroke(path {
        moveTo(118f, 104f)
        cubicTo(108f, 68f, 120f, 44f, 148f, 36f)
        cubicTo(154f, 62f, 146f, 92f, 124f, 110f)
        close()
    }, BirdPalette.brandDeep, 5.5f)
    // 아래쪽 날개 `M124 162c-6 20 0 36 16 44 8-16 4-36-6-48z`
    fillStroke(path {
        moveTo(124f, 162f)
        cubicTo(118f, 182f, 124f, 198f, 140f, 206f)
        cubicTo(148f, 190f, 144f, 170f, 134f, 158f)
        close()
    }, BirdPalette.brandDeep, 5.5f)
    // 꼬리 깃털 `M82 142L44 130l12 16-16 10 38 6z`
    fillStroke(path {
        moveTo(82f, 142f)
        lineTo(44f, 130f)
        lineTo(56f, 146f)
        lineTo(40f, 156f)
        lineTo(78f, 162f)
        close()
    }, BirdPalette.brandDeep, 5.5f)

    // 배(크림 타원, 회전) `rotate(-10 136 146)` rx24 ry15
    rotate(-10f, center) {
        drawOval(BirdPalette.cream, Offset(112f, 131f), Size(48f, 30f))
    }

    if (isSulky) {
        sulkyEye(158f, 116f)
    } else {
        normalEye(158f, 112f, 160f, 109.5f)
    }

    // 부리 `M182 116l24 8-22 9z`
    beakTriangle(182f, 116f, 206f, 124f, 184f, 133f)
}

// 탭바 아이콘(BirdTabIcon)은 ui/BirdTabIcon.kt에 있다 — 셸 그룹 소유라 파일을 나눴다
