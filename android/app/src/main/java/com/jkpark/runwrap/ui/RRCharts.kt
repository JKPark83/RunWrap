package com.jkpark.runwrap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.TrendLineChartText
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.WorkoutDetail
import com.jkpark.runwrap.engine.ZoneDistribution
import com.jkpark.runwrap.engine.ZoneStackedBarsChartText
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.swiftRoundedInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// 시안의 SVG 차트들을 SwiftUI Path로 옮긴 경량 차트 모음.
// 데이터 축·색은 전부 호출부(카드)가 결정한다.
// (Android: Swift Charts·Shape 대신 Compose Canvas·레이아웃으로 직접 그린다 — 차트 라이브러리 없음.
// VoiceOver의 label/value는 contentDescription/stateDescription으로 옮겼다)

/// 주간 막대 차트 — 마지막(현재) 주 강조 + 선택적 상한 점선.
/// 주가 많아 폭이 모자라면 슬롯 폭을 유지한 채 가로 스크롤로 넘긴다 (최신 주가
/// 오른쪽 끝, 과거는 왼쪽으로 스크롤). 각 막대 위에 값을 작게 상시 표시하고
/// (작업 지침 차트 규칙), 막대를 탭하면 주·수치 콜아웃을 띄운다.
@Composable
fun WeeklyBarsChart(
    weeks: List<WeeklyReport.WeekBar>,
    modifier: Modifier = Modifier,
    currentColor: Color = RR.brand,
    cap: Double? = null,
    capLabel: String? = null,
    chartHeight: Dp = 76.dp,
    /// 탭 콜아웃 수치 표기 — 다이어트 카드는 kcal을 주입한다
    valueText: (Double) -> String = { Format.km(it) + " km" },
    /// 막대 위 상시 표시용 짧은 수치 — 단위 없이 숫자만 (슬롯 폭이 좁다)
    barValueText: (Double) -> String = { Format.km(it) },
    /// false면 막대 위 값과 콜아웃 수치를 감춘다 — 런린이 주간 거리 "문장만" (§4, 이슈 #119)
    showsValues: Boolean = true,
) {
    var selected by remember { mutableStateOf<Int?>(null) }   // WeekBar.index

    /// 스크롤 모드에서 주 하나가 차지하는 최소 폭 — "12월 4째주" 라벨이 들어가는 폭
    val minSlot = 56.dp
    val labelHeight = 22.dp
    /// 막대 위 값 텍스트가 차지하는 높이 — 막대·상한선 스케일은 이만큼 뺀 높이를 쓴다
    val valueReserve = 15.dp

    val peak = maxOf(weeks.maxOfOrNull { it.km } ?: 1.0, cap ?: 0.0)
    val scaleMax = if (peak > 0) peak * 1.08 else 1.0

    fun barHeight(km: Double): Dp {
        val minimal = 4.dp  // 0이어도 흔적은 보이게
        return max(minimal, (chartHeight - valueReserve) * (km / scaleMax).toFloat())
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(chartHeight + labelHeight)) {
        val count = maxOf(weeks.size, 1)
        val scrollable = minSlot * count > maxWidth
        val slot = if (scrollable) minSlot else maxWidth / count
        val width = slot * count

        // 최신 주부터 보인다 — reverseScrolling이라 스크롤 0이 오른쪽 끝 (iOS defaultScrollAnchor(.trailing))
        Column(
            Modifier
                .horizontalScroll(rememberScrollState(), enabled = scrollable, reverseScrolling = true)
                .width(width),
        ) {
            Box(Modifier.height(chartHeight)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    for (week in weeks) {
                        Column(
                            Modifier
                                .width(slot)
                                .height(chartHeight)
                                .alpha(if (selected == null || selected == week.index) 1f else 0.45f)
                                .clickable { selected = if (selected == week.index) null else week.index }
                                // VoiceOver: 막대 하나 = 요소 하나, 콜아웃과 같은 주·수치 문자열 (이슈 #160)
                                .semantics {
                                    contentDescription = week.label
                                    if (showsValues) stateDescription = valueText(week.km)
                                },
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (showsValues && week.km > 0) {
                                FitText(
                                    barValueText(week.km),
                                    mono(10.sp, if (week.isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                                    if (week.isCurrent) currentColor else RR.text3,
                                    minScale = 0.7f,
                                    modifier = Modifier.clearAndSetSemantics {},   // 막대 요소의 값으로 읽는다
                                )
                            }
                            Box(
                                Modifier
                                    .width(max(6.dp, slot - 10.dp))
                                    .height(barHeight(week.km))
                                    .background(if (week.isCurrent) currentColor else RR.barFill, RoundedCornerShape(5.dp)),
                            )
                        }
                    }
                }

                if (cap != null && cap <= scaleMax) {
                    val y = chartHeight - (chartHeight - valueReserve) * (cap / scaleMax).toFloat()
                    val capColor = RR.dang.copy(alpha = 0.65f)
                    Canvas(Modifier.width(width).height(1.dp).offset(y = y)) {
                        drawLine(
                            capColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    if (capLabel != null) {
                        Text(
                            capLabel, style = mono(10.sp), color = RR.dang,
                            modifier = Modifier.offset(x = 2.dp, y = max(0.dp, y - 15.dp)),
                        )
                    }
                }

                val week = selected?.let { s -> weeks.firstOrNull { it.index == s } }
                if (week != null) {
                    // 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
                    val margin = 62.dp
                    val x = slot * (week.index + 0.5f)
                    ChartCallout(
                        if (showsValues) "${week.label} · ${valueText(week.km)}" else week.label,
                        Modifier.centerAt(min(max(x, margin), max(width - margin, margin)), 13.dp),
                    )
                }
            }

            // 주 라벨은 막대 요소의 라벨로 읽는다 (이슈 #160)
            Row(Modifier.padding(top = 8.dp).clearAndSetSemantics {}) {
                for (week in weeks) {
                    FitText(
                        week.label,
                        mono(10.sp, if (week.isCurrent) FontWeight.Bold else FontWeight.Normal)
                            .copy(textAlign = TextAlign.Center),
                        if (week.isCurrent) currentColor else RR.text3,
                        minScale = 0.7f,
                        modifier = Modifier.width(slot),
                    )
                }
            }
        }

        // 잘린 게 아니라 과거로 이어진다는 신호 — 스크롤 가능할 때만
        if (scrollable) {
            Box(
                Modifier
                    .width(22.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(RR.surface, RR.surface.copy(alpha = 0f)))),
            )
        }
    }
}

/// 차트 탭 콜아웃 — 선택한 지점의 시기·수치를 담는 작은 말풍선
@Composable
fun ChartCallout(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Text(
        text,
        style = mono(10.sp, FontWeight.SemiBold),
        color = RR.text,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(RR.surface2, shape)
            .border(1.dp, RR.line, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/// ACWR 반원 게이지 — 0.5~2.0, 안전(0.8~1.3)/주의/위험 구간 표시
@Composable
fun AcwrGauge(ratio: Double, modifier: Modifier = Modifier) {
    /// 안전 구간 표기 — 게이지 라벨과 VoiceOver 값이 같이 쓴다
    val safeZoneText = "0.8–1.3 안전"

    /// 값 → 각도: 0.5가 왼쪽(180°), 2.0이 오른쪽(360°)
    fun angle(value: Double): Float = (180 + (value.coerceIn(0.5, 2.0) - 0.5) / 1.5 * 180).toFloat()

    val barFill = RR.barFill
    val pos = RR.pos
    val warn = RR.warn
    val dang = RR.dang
    val text = RR.text

    // 시안 좌표계 320×152 기준: 중심 (160,124), 반지름 100
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(320f / 152f)
            // VoiceOver: 게이지 전체를 한 요소로 — 현재 값 + 안전 구간 (이슈 #160)
            .clearAndSetSemantics {
                contentDescription = "ACWR"
                stateDescription = fmt(ratio, 2) + ", " + safeZoneText
            },
    ) {
        val s = maxWidth / 320.dp
        Canvas(Modifier.fillMaxSize()) {
            val sp = size.width / 320
            val center = Offset(160 * sp, 124 * sp)
            val radius = 100 * sp

            fun segment(from: Double, to: Double, color: Color) {
                drawArc(
                    color, startAngle = angle(from), sweepAngle = angle(to) - angle(from), useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = 15 * sp, cap = StrokeCap.Butt),
                )
            }
            segment(0.5, 0.8, barFill)
            segment(0.8, 1.3, pos)
            segment(1.3, 1.5, warn)
            segment(1.5, 2.0, dang)

            // 값 위치 마커 — 호를 가로지르는 짧은 눈금. 예전의 중심축 바늘은 가운데
            // 큰 숫자를 관통해 겹쳐 보였다 — 바늘 대신 호 위 마커로 위치만 표시한다.
            val a = Math.toRadians(angle(ratio).toDouble())
            val inner = Offset(center.x + cos(a).toFloat() * (radius - 13 * sp), center.y + sin(a).toFloat() * (radius - 13 * sp))
            val outer = Offset(center.x + cos(a).toFloat() * (radius + 13 * sp), center.y + sin(a).toFloat() * (radius + 13 * sp))
            drawLine(text, inner, outer, strokeWidth = 4 * sp, cap = StrokeCap.Round)
        }

        // 글자도 게이지와 함께 비례 축소된다 (iOS `size: 36 * s`) — 시스템 글꼴 배율을 타면 호와 겹치므로 dp 기준
        val fontScale = LocalDensity.current.fontScale
        fun size(value: Float) = (value * s / fontScale).sp
        Text(
            fmt(ratio, 2), style = mono(size(36f), FontWeight.Bold), color = RR.text,
            modifier = Modifier.centerAt(160.dp * s, (124 - 24).dp * s),
        )
        Text("0.5", style = mono(size(10f)), color = RR.text3,
             modifier = Modifier.centerAt(60.dp * s, 138.dp * s))
        Text(safeZoneText, style = mono(size(10f)), color = RR.pos,
             modifier = Modifier.centerAt(138.dp * s, 54.dp * s))  // 초록 호와 겹치지 않는 오목면 안쪽
        Text("2.0", style = mono(size(10f)), color = RR.text3,
             modifier = Modifier.centerAt(260.dp * s, 138.dp * s))
    }
}

/// 추세 라인 차트 — 그라디언트 채움 + 끝점 도트 (EF 카드).
/// 차트를 탭하면 가장 가까운 점의 시기·수치를 콜아웃으로 띄운다 (다시 탭하면 닫힘).
@Composable
fun TrendLineChart(
    points: List<Double>,
    modifier: Modifier = Modifier,
    tint: Color = RR.pos,
    height: Dp = 96.dp,
    endLabels: Pair<String, String>? = null,  // (왼쪽, 오른쪽) 축 라벨
    /// 탭 콜아웃에 함께 보여줄 시기 라벨 (points와 병행 배열)
    pointLabels: List<String>? = null,
    /// 탭 콜아웃 수치 표기 — EF·kg·페이스 등 단위는 호출부가 정한다
    valueText: (Double) -> String = { fmt(it, 2) },
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val line = RR.line
    val surface = RR.surface

    Column(
        modifier
            .fillMaxWidth()
            // VoiceOver: 차트 전체를 한 요소로 — 첫 점 → 마지막 점 요약 (이슈 #160)
            .clearAndSetSemantics {
                contentDescription = "추세"
                stateDescription = TrendLineChartText.accessibilitySummary(points, pointLabels, valueText)
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val density = LocalDensity.current
            val pts = normalized(points, w, h, inset = with(density) { 10.dp.toPx() })

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pts) {
                        detectTapGestures { location ->
                            val nearest = pts.indices.minByOrNull { abs(pts[it].x - location.x) }
                                ?: return@detectTapGestures
                            selected = if (selected == nearest) null else nearest
                        }
                    },
            ) {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

                if (pts.size >= 2) {
                    val area = Path().apply {
                        moveTo(pts[0].x, size.height)
                        for (pt in pts) lineTo(pt.x, pt.y)
                        lineTo(pts.last().x, size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(tint.copy(alpha = 0.28f), tint.copy(alpha = 0f))))

                    val stroke = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (pt in pts.drop(1)) lineTo(pt.x, pt.y)
                    }
                    drawPath(stroke, tint, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                    drawCircle(tint, 5.dp.toPx(), pts.last())
                    drawCircle(surface, 5.dp.toPx(), pts.last(), style = Stroke(2.5.dp.toPx()))

                    // 선택 표식 — 세로 가이드선 + 링 도트 (+ 아래 콜아웃)
                    val sel = selected
                    if (sel != null && sel in pts.indices) {
                        val pt = pts[sel]
                        drawLine(
                            line, Offset(pt.x, size.height), pt, strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                        )
                        drawCircle(tint, 5.dp.toPx(), pt)
                        drawCircle(surface, 5.dp.toPx(), pt, style = Stroke(2.5.dp.toPx()))
                    }
                }
            }

            val sel = selected
            if (pts.size >= 2 && sel != null && sel in pts.indices) {
                val pt = pts[sel]
                val label = pointLabels?.getOrNull(sel)
                val m = 56.dp
                val x = with(density) { pt.x.toDp() }
                val y = with(density) { pt.y.toDp() }
                ChartCallout(
                    label?.let { "$it · ${valueText(points[sel])}" } ?: valueText(points[sel]),
                    Modifier.centerAt(min(max(x, m), max(maxWidth - m, m)), max(y - 24.dp, 12.dp)),
                )
            }
        }

        if (endLabels != null) {
            Row {
                Text(endLabels.first, style = mono(10.sp), color = RR.text3)
                Spacer(Modifier.weight(1f))
                Text(endLabels.second, style = mono(10.sp), color = RR.text3)
            }
        }
    }
}

/// TrendLineChart의 점 좌표(px) — 위아래 여백 18%/12%, 좌우 inset
private fun normalized(points: List<Double>, width: Float, height: Float, inset: Float): List<Offset> {
    if (points.size < 2) return emptyList()
    val low = points.min()
    val high = points.max()
    val span = maxOf(high - low, 0.0001)
    val usableW = width - inset * 2
    val top = height * 0.18f
    val bottom = height * 0.88f
    return points.mapIndexed { i, v ->
        Offset(inset + usableW * i / (points.size - 1), bottom - (bottom - top) * ((v - low) / span).toFloat())
    }
}

/// 작은 스파크라인 (통계 타일)
@Composable
fun SparkLine(
    points: List<Double>,
    modifier: Modifier = Modifier,
    tint: Color = RR.text3,
    /// true면 값이 작을수록 위(페이스처럼 낮을수록 좋은 지표)
    invert: Boolean = false,
) {
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        if (points.size < 2) return@Canvas
        val low = points.min()
        val high = points.max()
        val span = maxOf(high - low, 0.0001)
        val path = Path()
        points.forEachIndexed { i, v ->
            var t = ((v - low) / span).toFloat()
            if (!invert) t = 1 - t
            val x = size.width * i / (points.size - 1)
            val y = size.height * (0.12f + 0.76f * t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/// 구간별(km) 페이스 막대 — 평균 대비 느린 구간은 경고색.
/// 구간이 많아 폭이 모자라면 막대 폭을 유지한 채 가로 스크롤로 넘긴다
/// (예전에는 폭에 억지로 밀어넣어 롱런 후반 구간이 카드 밖으로 잘렸다).
/// 막대가 좁아 값 상시 표시가 불가능한 차트 — 탭 콜아웃으로 대신한다 (작업 지침 차트 규칙).
@Composable
fun SplitBarsChart(
    splits: List<WorkoutDetail.Split>,
    modifier: Modifier = Modifier,
    slowThresholdSec: Double = 8.0,
    height: Dp = 64.dp,
) {
    var selected by remember { mutableStateOf<Int?>(null) }   // Split.index

    /// 스크롤 모드에서 막대 하나가 차지하는 최소 폭 (막대 + 간격)
    val slot = 10.dp
    val gap = 3.dp
    val axisHeight = 13.dp

    val avgPace = splits.sumOf { it.paceSecPerKm } / maxOf(splits.size, 1)

    BoxWithConstraints(modifier.fillMaxWidth().height(height + axisHeight + 4.dp)) {
        val needed = slot * splits.size
        val width = max(maxWidth, needed)
        val scrollable = needed > maxWidth
        val barW = max(3.dp, (width - gap * (splits.size - 1)) / splits.size)

        Column(
            Modifier
                .horizontalScroll(rememberScrollState(), enabled = scrollable)
                .width(width),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 막대
            val speeds = splits.map { 1 / it.paceSecPerKm }
            val minSpeed = speeds.minOrNull() ?: 1.0
            val span = maxOf((speeds.maxOrNull() ?: 1.0) - minSpeed, 0.0001)
            Box(Modifier.height(height)) {
                Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
                    for (split in splits) {
                        val t = ((1 / split.paceSecPerKm - minSpeed) / span).toFloat()
                        Box(
                            Modifier
                                .width(barW)
                                .fillMaxHeight()
                                .clickable { selected = if (selected == split.index) null else split.index }
                                // VoiceOver: 막대 하나 = 요소 하나, 콜아웃과 같은 구간·페이스 문자열 (이슈 #160)
                                .semantics {
                                    contentDescription = "${split.index}km"
                                    stateDescription = Format.pace(split.paceSecPerKm)
                                },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(height * (0.45f + 0.55f * t))
                                    .alpha(if (selected == null || selected == split.index) 1f else 0.55f)
                                    .background(
                                        if (split.paceSecPerKm > avgPace + slowThresholdSec) RR.warn else RR.brand.copy(alpha = 0.8f),
                                        RoundedCornerShape(3.dp),
                                    ),
                            )
                        }
                    }
                }

                val offset = selected?.let { s -> splits.indexOfFirst { it.index == s } } ?: -1
                if (offset >= 0) {
                    // 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
                    val margin = 56.dp
                    val x = (barW + gap) * offset + barW / 2
                    ChartCallout(
                        "${splits[offset].index}km · " + Format.pace(splits[offset].paceSecPerKm),
                        Modifier.centerAt(min(max(x, margin), max(width - margin, margin)), 13.dp),
                    )
                }
            }

            // km 눈금 — 막대와 같은 슬롯에 얹어 스크롤해도 어긋나지 않는다
            // 라벨이 서로 붙지 않는 최소 간격 (30pt 확보)
            val step = listOf(1, 2, 5, 10, 20, 50).firstOrNull { (barW + gap) * it >= 30.dp } ?: 100
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.clearAndSetSemantics {},   // km 눈금은 막대 요소의 라벨로 읽는다 (이슈 #160)
            ) {
                for (split in splits) {
                    Box(Modifier.size(barW, axisHeight), contentAlignment = Alignment.Center) {
                        if (split.index == 1 || split.index % step == 0) {
                            Text(
                                "${split.index}", style = mono(10.sp), color = RR.text3, softWrap = false,
                                modifier = Modifier.wrapContentSize(unbounded = true),
                            )
                        }
                    }
                }
            }
        }

        // 잘린 게 아니라 이어진다는 신호 — 스크롤 가능할 때만
        if (scrollable) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(22.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(RR.surface.copy(alpha = 0f), RR.surface))),
            )
        }
    }
}

/// Z1~Z5 색 — 주별 누적 막대(ZoneStackedBarsChart)와 공용 (iOS `ZoneBarView.colors`)
private val zoneColors: List<Color>
    @Composable @ReadOnlyComposable
    get() = listOf(RR.barFill, RR.pos.copy(alpha = 0.55f), RR.pos, RR.warn, RR.dang)

/// 심박 존 분포 바 + 존별 퍼센트
@Composable
fun ZoneBarView(
    /// Z1~Z5 비율 (합 1.0)
    fractions: List<Double>,
    modifier: Modifier = Modifier,
) {
    val colors = zoneColors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val gaps = 2.dp * (fractions.size - 1)
            val width = maxWidth
            Row(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                fractions.forEachIndexed { i, f ->
                    Box(
                        Modifier
                            .width(max(0.dp, (width - gaps) * f.toFloat()))
                            .fillMaxHeight()
                            .background(colors[minOf(i, colors.size - 1)]),
                    )
                }
            }
        }

        val top = fractions.maxOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fractions.forEachIndexed { i, f ->
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Z${i + 1}", style = TextStyle(fontSize = 10.sp), color = RR.text3)
                    Text(
                        "${(f * 100).swiftRoundedInt()}%",
                        style = mono(12.sp, FontWeight.SemiBold),
                        color = if (f == top) RR.text else RR.text2,
                    )
                }
            }
        }
    }
}

// MARK: - 주별 심박존 누적 막대

/// 주별 Z1~Z5 누적 시간 스택 막대 (이슈 #165) — 아래부터 Z1…Z5, 색은 ZoneBarView와 같다.
/// 4주뿐이라 슬롯이 넓어 막대 위에 그 주 총 시간을 상시 표시하고(작업 지침 차트 규칙),
/// 막대를 탭하면 주·총 시간·이지 비율 콜아웃을 띄운다.
/// (Android: 콜아웃·VoiceOver 수치 `valueText`는 엔진 `ZoneStackedBarsChartText`에 있다)
@Composable
fun ZoneStackedBarsChart(
    /// 오래된 → 최신 — 마지막이 이번 주
    weeks: List<ZoneDistribution.WeekBar>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 84.dp,
) {
    var selected by remember { mutableStateOf<Int?>(null) }   // weeks 인덱스
    val colors = zoneColors

    val labelHeight = 22.dp
    /// 막대 위 값 텍스트가 차지하는 높이 — 막대 스케일은 이만큼 뺀 높이를 쓴다
    val valueReserve = 15.dp

    val peak = weeks.maxOfOrNull { it.zoneSeconds.sum() } ?: 0.0
    val scaleMax = if (peak > 0) peak * 1.08 else 1.0

    BoxWithConstraints(modifier.fillMaxWidth().height(chartHeight + labelHeight)) {
        val width = maxWidth
        val slot = width / maxOf(weeks.size, 1)
        Column {
            Box(Modifier.height(chartHeight)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    weeks.forEachIndexed { index, week ->
                        val total = week.zoneSeconds.sum()
                        val barHeight = (chartHeight - valueReserve) * (total / scaleMax).toFloat()
                        val barWidth = max(6.dp, min(slot - 16.dp, 40.dp))
                        Column(
                            Modifier
                                .width(slot)
                                .height(chartHeight)
                                .alpha(if (selected == null || selected == index) 1f else 0.45f)
                                .clickable { selected = if (selected == index) null else index }
                                // VoiceOver: 막대 하나 = 요소 하나, 콜아웃과 같은 주·수치 문자열 (이슈 #160)
                                .semantics {
                                    contentDescription = week.label
                                    stateDescription = ZoneStackedBarsChartText.valueText(week)
                                },
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (total > 0) {
                                FitText(
                                    Format.duration(total), mono(10.sp), RR.text3, minScale = 0.7f,
                                    modifier = Modifier.clearAndSetSemantics {},   // 막대 요소의 값으로 읽는다
                                )
                                Column(Modifier.width(barWidth).clip(RoundedCornerShape(5.dp))) {
                                    for (zone in 4 downTo 0) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(barHeight * (week.zoneSeconds[zone] / total).toFloat())
                                                .background(colors[zone]),
                                        )
                                    }
                                }
                            } else {
                                // 달리지 않은 주 — 0이어도 흔적은 보이게
                                Box(Modifier.size(barWidth, 4.dp).background(RR.line, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }

                val sel = selected
                if (sel != null && sel in weeks.indices) {
                    // 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
                    val margin = 70.dp
                    val x = slot * (sel + 0.5f)
                    ChartCallout(
                        "${weeks[sel].label} · ${ZoneStackedBarsChartText.valueText(weeks[sel])}",
                        Modifier.centerAt(min(max(x, margin), max(width - margin, margin)), 13.dp),
                    )
                }
            }

            // 주 라벨은 막대 요소의 라벨로 읽는다 (이슈 #160)
            Row(Modifier.padding(top = 8.dp).clearAndSetSemantics {}) {
                weeks.forEachIndexed { index, week ->
                    val isCurrent = index == weeks.size - 1
                    FitText(
                        week.label,
                        mono(10.sp, if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                            .copy(textAlign = TextAlign.Center),
                        if (isCurrent) RR.text else RR.text3,
                        minScale = 0.7f,
                        modifier = Modifier.width(slot),
                    )
                }
            }
        }
    }
}

// MARK: - 체력 배터리 게이지

/// 가로 배터리 모양 잔량 게이지 — 몸통을 10칸으로 나눠 잔량만큼 칸을 채우고
/// 오른쪽에 단자를 붙인다. 실제 배터리 인디케이터의 관습을 그대로 따르는 표현이라
/// 숫자를 읽지 않아도 "몇 칸 남았는지"로 잔량이 먼저 보인다.
///
/// 색은 톤(tint)을 그대로 쓰지 않고 잔량 구간으로 정한다 — 배터리 관습상
/// 넉넉하면 초록, 부족하면 노랑·빨강이라 톤 색(brand 등)을 쓰면 오히려 헷갈린다.
@Composable
fun BatteryGauge(
    level: Int,          // 0–100
    modifier: Modifier = Modifier,
) {
    /// 칸 개수 — 10칸이면 한 칸이 10%라 눈으로 세기 쉽다
    val cellCount = 10

    /// 잔량 구간별 색: 50% 이상 초록 / 20% 이상 노랑 / 그 아래 빨강.
    /// 배터리 인디케이터 관습(20% 이하가 경고)에 맞춘 경계라 BatteryEngine의
    /// 톤 경계와는 일부러 다르다 — 여기서 말하는 건 "충전 상태"지 훈련 판정이 아니다.
    val fillColor = when {
        level >= 50 -> RR.pos
        level >= 20 -> RR.warn
        else -> RR.dang
    }

    /// 채워진 칸 수 — 0%가 아니면 최소 1칸은 남겨 "완전 방전"과 구분한다
    val filledCells = if (level <= 0) 0
    else (level.toDouble() / 100 * cellCount).swiftRoundedInt().coerceIn(1, cellCount)

    Row(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .clearAndSetSemantics {
                contentDescription = "남은 체력"
                stateDescription = "$level 퍼센트"
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val inset = 3.dp
            val gap = 2.5.dp
            val inner = maxWidth - inset * 2
            val cellW = (inner - gap * (cellCount - 1)) / cellCount
            val shape = RoundedCornerShape(5.dp)

            Row(
                Modifier
                    .fillMaxSize()
                    .background(RR.surface2.copy(alpha = 0.5f), shape)
                    .border(1.2.dp, RR.line, shape)
                    .padding(inset),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (index in 0 until cellCount) {
                    Box(
                        Modifier
                            .width(max(2.dp, cellW))
                            .fillMaxHeight()
                            .background(
                                // SwiftUI .opacity(0.45)는 토큰의 알파에 곱한다 — copy(alpha = 0.45f)로 덮으면 회색 칸이 된다
                                if (index < filledCells) fillColor else RR.barFill.let { it.copy(alpha = it.alpha * 0.45f) },
                                RoundedCornerShape(1.5.dp),
                            ),
                    )
                }
            }
        }
        // 배터리 단자
        Box(Modifier.size(3.5.dp, 10.dp).background(RR.line, RoundedCornerShape(1.5.dp)))
    }
}

// MARK: - 공용 헬퍼 (iOS 대응 없음)

/// SwiftUI `.font(.system(size:weight:design: .monospaced))`
internal fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontSize = size, fontWeight = weight, fontFamily = FontFamily.Monospace)

/// SwiftUI `.lineLimit(1).minimumScaleFactor(minScale)` — 폭이 모자라면 글자를 minScale배까지 줄인다
@Composable
internal fun FitText(text: String, style: TextStyle, color: Color, minScale: Float, modifier: Modifier = Modifier) {
    BasicText(
        text, modifier, style = style.copy(color = color), maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * minScale, maxFontSize = style.fontSize),
    )
}

/// SwiftUI `.position(x:y:)` + `.fixedSize()` — 자식의 중심을 부모 좌상단 기준 (x, y)에 둔다.
/// 부모 레이아웃에는 자리를 차지하지 않는다 (ZStack 위에 띄우는 콜아웃·라벨용)
private fun Modifier.centerAt(x: Dp, y: Dp): Modifier = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(0, 0) { p.place((x.toPx() - p.width / 2f).toInt(), (y.toPx() - p.height / 2f).toInt()) }
}
