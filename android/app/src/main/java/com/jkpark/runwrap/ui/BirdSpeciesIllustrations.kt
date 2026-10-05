package com.jkpark.runwrap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import com.jkpark.runwrap.engine.BirdSpecies

/// 종별 성조 일러스트 — 도감·수집 세러모니에서 종마다 다른 새를 보여준다 (기획서 §5·§10).
///
/// 실제 에셋(`bird-adult-sparrow` …)이 오기 전까지의 자리표시지만, 도감의 재미는
/// "참새와 백조가 다르게 생겼다"에서 오므로 실루엣·색·무늬를 종마다 따로 그린다.
/// `BirdView`와 같은 240×240 좌표계·두꺼운 윤곽선 문법을 쓰고, 오른쪽으로 나는 옆모습이다.
/// 종의 크기 차이(작은 목표 = 작은 새)는 그림 안에서 배율로 표현한다.
@Composable
fun SpeciesBirdView(species: BirdSpecies, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val s = size.width / 240f
        val k = bodyScale(species)
        // 240 좌표계로 맞춘 뒤, 종별 배율은 칸 중심(120,125)을 기준으로 건다
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            translate(120f, 125f)
            scale(k, k, pivot = Offset.Zero)
            translate(-120f, -125f)
        }) {
            // 배율이 작은 새도 윤곽선 굵기는 같게 보이도록 되돌린다
            val pen = BirdPen(this, 5.5f / k)
            when (species) {
                BirdSpecies.sparrow -> sparrow(pen)
                BirdSpecies.swallow -> swallow(pen)
                BirdSpecies.falcon -> falcon(pen)
                BirdSpecies.goose -> goose(pen)
                BirdSpecies.crane -> crane(pen)
                BirdSpecies.swan -> swan(pen)
            }
        }
    }
}

/// 큰 목표일수록 큰 새 — 기러기·두루미·백조는 그림 자체가 칸을 가득 채운다
private fun bodyScale(species: BirdSpecies): Float = when (species) {
    BirdSpecies.sparrow -> 0.8f
    BirdSpecies.swallow -> 0.9f
    BirdSpecies.falcon -> 0.96f
    BirdSpecies.goose, BirdSpecies.crane, BirdSpecies.swan -> 1f
}

// MARK: - 참새: 통통한 몸 · 밤색 정수리 · 뺨의 검은 점 · 날개 흰 띠

private fun sparrow(pen: BirdPen) {
    pen.speedLines()
    pen.part("M 80 148 L 40 136 L 50 154 L 38 166 L 82 166 Z", SpeciesPalette.sparrowDark)
    pen.part("M 118 170 C 112 190 118 206 134 212 C 142 196 138 180 130 166 Z", SpeciesPalette.sparrowDark)
    pen.part("E 124 138 56 44 -10", SpeciesPalette.sparrow, marks = listOf(
        "E 134 164 42 22 -10" to BirdPalette.cream,          // 배
        "E 156 95 40 16 14" to SpeciesPalette.chestnut,      // 정수리
        "E 152 133 15 11 0" to BirdPalette.cream,            // 흰 뺨
        "E 151 134 6 5 0" to BirdPalette.outline,            // 뺨의 검은 점
        "E 174 147 10 13 -25" to BirdPalette.outline,        // 턱받이
    ))
    pen.part("M 106 122 C 94 88 102 60 128 44 C 140 72 134 104 122 128 Z", SpeciesPalette.sparrowDark, marks = listOf(
        "M 96 98 L 140 84 L 140 93 L 96 107 Z" to BirdPalette.cream,   // 날개 띠
    ))
    pen.part("M 176 116 L 198 126 L 177 135 Z", SpeciesPalette.beakDark)
    pen.eye(160f, 117f)
}

// MARK: - 제비: 가는 몸 · 낫 모양 날개 · 깊게 갈라진 꼬리 · 붉은 멱

private fun swallow(pen: BirdPen) {
    pen.speedLines()
    pen.part("M 88 124 L 18 96 L 60 134 L 14 168 L 90 146 Z", SpeciesPalette.navyDark)
    pen.part("M 126 152 C 124 182 110 206 84 222 C 92 196 100 174 108 150 Z", SpeciesPalette.navyDark)
    pen.part("E 128 134 54 30 -8", SpeciesPalette.navy, marks = listOf(
        "E 134 155 52 20 -8" to BirdPalette.cream,           // 배
        "E 172 139 15 12 0" to BirdPalette.brandDeep,        // 붉은 멱
        "E 179 115 9 7 0" to BirdPalette.brandDeep,          // 이마
    ))
    pen.part("M 124 118 C 118 84 92 50 44 28 C 62 60 82 96 104 128 Z", SpeciesPalette.navyDark)
    pen.line("M 112 110 C 104 88 90 66 72 50", width = 0.55f)
    pen.part("M 180 121 L 198 127 L 180 133 Z", SpeciesPalette.beakDark)
    pen.eye(162f, 123f)
}

// MARK: - 매: 뾰족한 날개 · 갈고리 부리 · 눈 밑 수염 무늬 · 가슴 가로줄

private fun falcon(pen: BirdPen) {
    pen.part("M 84 136 L 30 118 L 26 150 L 82 158 Z", SpeciesPalette.slate, marks = listOf(
        "M 22 112 L 42 118 L 38 158 L 18 152 Z" to SpeciesPalette.slateDark,   // 꼬리 끝 띠
        "M 54 122 L 63 125 L 60 158 L 51 156 Z" to SpeciesPalette.slateDark,
    ))
    pen.part("M 120 164 C 112 188 116 208 130 222 C 140 202 140 182 132 162 Z", SpeciesPalette.slateDark)
    pen.part("E 126 134 56 38 -10", SpeciesPalette.slate, marks = listOf(
        "E 140 157 42 22 -10" to BirdPalette.cream,          // 가슴·배
        "E 156 100 40 17 14" to SpeciesPalette.slateDark,    // 머리 두건
        "M 156 118 C 154 136 160 148 170 152 C 172 138 170 126 168 118 Z" to SpeciesPalette.slateDark,   // 수염 무늬
    ))
    for (bar in listOf("M 118 153 L 130 155", "M 136 161 L 148 161", "M 122 167 L 134 169", "M 146 151 L 155 150")) {
        pen.line(bar, SpeciesPalette.slateDark, width = 0.65f)
    }
    pen.part("M 108 116 C 100 76 114 40 150 16 C 154 54 146 94 130 124 Z", SpeciesPalette.slateDark)
    pen.line("M 136 50 C 140 70 136 92 126 110", SpeciesPalette.slate, width = 0.55f)
    pen.part("M 178 113 L 192 115 L 192 129 L 178 131 Z", SpeciesPalette.yellow)   // 납막
    pen.part("M 192 115 C 204 115 209 124 204 138 C 200 131 196 129 192 129 Z", SpeciesPalette.beakDark)
    pen.ctx.drawPath(svgPath("E 164 114 9.5 9.5 0"), SpeciesPalette.yellow)   // 노란 눈테
    pen.eye(164f, 114f)
    pen.line("M 152 102 L 177 108")   // 눈썹뼈 — 매의 날카로운 인상
}

// MARK: - 기러기: 앞으로 뻗은 긴 목 · 넓은 날개 · 주황 부리 · 흰 허리

private fun goose(pen: BirdPen) {
    pen.part("M 58 142 L 22 130 L 32 148 L 20 160 L 60 162 Z", SpeciesPalette.gooseDark)
    pen.part("M 102 176 C 96 198 102 214 118 222 C 126 206 122 190 112 174 Z", SpeciesPalette.gooseDark)
    pen.part("M 214 82 L 235 92 L 212 98 Z", SpeciesPalette.orange)
    // 목·머리와 몸통을 한 도형으로 합쳐 이음매 선을 없앤다
    pen.part("E 104 148 56 36 -6 " +
             "M 142 122 C 162 114 178 100 186 86 C 190 72 206 70 214 80 C 220 90 216 102 206 106 C 194 124 172 144 150 154 Z",
             SpeciesPalette.goose, marks = listOf(
        "M 150 110 L 230 60 L 230 120 L 160 160 Z" to SpeciesPalette.gooseDark,   // 목·머리는 짙게
        "E 216 88 6 9 0" to BirdPalette.cream,               // 부리 뿌리의 흰 이마
        "E 110 171 46 18 -6" to SpeciesPalette.gooseLight,   // 배
        "E 52 152 14 26 0" to BirdPalette.cream,             // 흰 허리
    ))
    pen.part("M 92 128 C 80 92 90 58 122 32 C 134 64 128 102 114 134 Z", SpeciesPalette.gooseDark)
    pen.line("M 112 62 C 116 82 114 104 107 120", SpeciesPalette.goose, width = 0.55f)
    pen.line("M 99 82 C 101 96 101 110 99 122", SpeciesPalette.goose, width = 0.55f)
    pen.eye(203f, 88f, r = 4.5f)
}

// MARK: - 두루미: 붉은 정수리 · 검은 목 · 뒤로 뻗은 긴 다리 · 검은 날개 끝

private fun crane(pen: BirdPen) {
    pen.line("M 66 150 L 14 172")
    pen.line("M 66 158 L 18 186")
    pen.part("M 74 124 C 52 120 34 132 26 152 C 44 150 60 150 78 152 Z", SpeciesPalette.ink)   // 검은 꽁지깃
    pen.part("M 102 164 C 94 190 100 212 118 226 C 126 206 122 184 112 162 Z", SpeciesPalette.white)
    pen.part("M 217 77 L 236 91 L 213 95 Z", SpeciesPalette.beakOlive)
    pen.part("E 104 142 52 30 -6 " +
             "M 140 126 C 162 120 182 102 192 84 C 196 70 212 68 220 78 C 226 88 220 100 210 102 C 198 122 174 142 148 152 Z",
             SpeciesPalette.white, marks = listOf(
        "E 108 163 44 12 -6" to SpeciesPalette.shade,        // 배 그늘
        "M 152 106 L 232 56 L 232 116 L 166 156 Z" to SpeciesPalette.ink,   // 검은 목
        "E 198 98 9 13 35" to SpeciesPalette.white,          // 눈 뒤로 흐르는 흰 줄
        "E 211 72 13 7 15" to SpeciesPalette.red,            // 붉은 정수리
    ))
    pen.part("M 92 124 C 76 86 84 46 118 14 C 134 50 128 96 114 130 Z", SpeciesPalette.white, marks = listOf(
        "M 118 14 C 134 50 128 96 114 130 L 103 124 C 116 92 120 54 108 22 Z" to SpeciesPalette.ink,   // 검은 둘째날개깃
    ))
    pen.ctx.drawPath(svgPath("E 209 84 6 6 0"), SpeciesPalette.white)
    pen.eye(209f, 84f, r = 3.5f)
}

// MARK: - 백조: S자 목 · 물결 진 큰 날개 · 주황 부리와 검은 혹

private fun swan(pen: BirdPen) {
    pen.part("M 52 144 L 20 126 L 32 150 L 50 162 Z", SpeciesPalette.white)
    pen.part("M 203 68 L 228 84 L 198 88 Z", SpeciesPalette.orange)
    pen.part("E 100 152 56 34 -4 " +
             "M 134 134 C 150 136 168 128 170 112 C 172 98 164 90 168 76 C 172 60 192 56 202 66 " +
             "C 210 76 206 88 196 90 C 188 92 190 102 192 114 C 194 138 170 160 144 162 Z",
             SpeciesPalette.white, marks = listOf(
        "E 104 177 48 14 -4" to SpeciesPalette.shade,        // 배 그늘
        "E 205 71 6 6 0" to SpeciesPalette.ink,              // 부리 뿌리의 검은 혹
    ))
    // 깃 끝이 물결 진 큰 날개
    pen.part("M 86 132 C 66 96 72 54 104 22 C 110 32 112 42 110 52 C 120 46 127 50 126 62 " +
             "C 135 59 139 68 133 80 C 133 100 125 120 112 138 Z", SpeciesPalette.white)
    pen.line("M 104 52 C 104 76 100 100 94 118", SpeciesPalette.shadeDeep, width = 0.55f)
    pen.line("M 120 66 C 120 86 114 106 106 124", SpeciesPalette.shadeDeep, width = 0.55f)
    pen.eye(192f, 72f, r = 4f)
}

/// 종별 일러스트 고정색 — `BirdPalette`와 같은 이유로 RR 토큰 대신 고정색을 쓴다
/// (크림·다크 배경 양쪽에서 윤곽선으로 대비를 잡는 그림이라 테마를 따라 바뀌면 안 된다).
private object SpeciesPalette {
    val sparrow = Color(0xFFC98F54)
    val sparrowDark = Color(0xFF8C5B31)
    val chestnut = Color(0xFF7A4325)
    val navy = Color(0xFF2F4A8F)
    val navyDark = Color(0xFF1E2F5E)
    val slate = Color(0xFF748494)
    val slateDark = Color(0xFF3F4A56)
    val yellow = Color(0xFFF4B93A)
    val goose = Color(0xFFA88B6A)
    val gooseDark = Color(0xFF6E5642)
    val gooseLight = Color(0xFFDCCBB2)
    val orange = Color(0xFFF28C28)
    val white = Color(0xFFFDFCF8)
    val shade = Color(0xFFE3E7EE)
    val shadeDeep = Color(0xFFB9C0CC)
    val ink = Color(0xFF2A2824)
    val red = Color(0xFFE0362B)
    val beakDark = Color(0xFF4E4840)
    val beakOlive = Color(0xFFB9A873)
}

/// 240 좌표계에 도형을 찍는 펜 — 채움·무늬·윤곽선 순서를 한 곳에 묶는다
/// @property width 윤곽선 굵기 (도형 바깥으로 보이는 두께)
private class BirdPen(val ctx: DrawScope, val width: Float) {
    /// 윤곽선(2배 굵기) → 채움 → 무늬 순으로 그린다. 채움이 선의 안쪽 절반을 덮어
    /// 바깥 절반만 남으므로, 여러 하위 경로를 한 도형으로 합쳐도 이음매 선이 생기지 않는다.
    /// 무늬는 도형 안으로 잘라 윤곽선을 침범하지 않게 한다
    fun part(d: String, color: Color, marks: List<Pair<String, Color>> = emptyList()) {
        val path = svgPath(d)
        ctx.drawPath(path, BirdPalette.outline,
                     style = Stroke(width * 2, cap = StrokeCap.Round, join = StrokeJoin.Round))
        ctx.drawPath(path, color)
        ctx.clipPath(path) {
            for ((mark, markColor) in marks) drawPath(svgPath(mark), markColor)
        }
    }

    /// 깃 결·다리 같은 선. width는 윤곽선 굵기에 대한 배수
    fun line(d: String, color: Color = BirdPalette.outline, width: Float = 1f) {
        ctx.drawPath(svgPath(d), color,
                     style = Stroke(this.width * width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /// 눈 + 반짝임 — `BirdView`의 NormalEye와 같은 비율
    fun eye(x: Float, y: Float, r: Float = 6.5f) {
        ctx.drawCircle(BirdPalette.outline, r, Offset(x, y))
        val h = r * 0.34f
        ctx.drawCircle(Color.White, h, Offset(x + r * 0.3f, y - r * 0.38f))
    }

    /// `BirdView` 나는 새와 같은 속도선 — 작은 새에만 쓴다 (큰 새는 칸을 꽉 채운다)
    fun speedLines() {
        line("M 22 92 L 46 92", BirdPalette.hint)
        line("M 4 114 L 24 114", BirdPalette.hint)
    }
}

/// 절대좌표 SVG 부분집합(M L Q C Z)에 타원 `E cx cy rx ry 회전°`를 더한 최소 파서.
/// 토큰은 공백으로만 나눈다 — 그림 좌표를 시안 path처럼 한 줄로 적기 위한 것
/// (Android: 타원은 시계 방향으로 넣는다 — CGPath `addEllipse`와 같은 방향이라야 nonzero 채움에서
///  몸통 타원과 목 경로가 겹치는 자리가 구멍 없이 합쳐진다)
private fun svgPath(d: String): Path {
    val p = Path()
    var command = "M"
    val n = mutableListOf<Float>()
    for (token in d.split(' ').filter { it.isNotEmpty() }) {
        val value = token.toDoubleOrNull()
        if (value == null) {
            command = token
            n.clear()
            if (command == "Z") p.close()
            continue
        }
        n.add(value.toFloat())
        when {
            command == "M" && n.size == 2 -> p.moveTo(n[0], n[1])
            command == "L" && n.size == 2 -> p.lineTo(n[0], n[1])
            command == "Q" && n.size == 4 -> p.quadraticTo(n[0], n[1], n[2], n[3])
            command == "C" && n.size == 6 -> p.cubicTo(n[0], n[1], n[2], n[3], n[4], n[5])
            command == "E" && n.size == 5 -> {
                val ellipse = Path()
                ellipse.addOval(Rect(-n[2], -n[3], n[2], n[3]), Path.Direction.Clockwise)
                ellipse.transform(Matrix().apply { rotateZ(n[4]) })
                ellipse.translate(Offset(n[0], n[1]))
                p.addPath(ellipse)
            }
            else -> continue
        }
        n.clear()
    }
    return p
}
