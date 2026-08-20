package com.jkpark.runwrap.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.health.WorkoutDetail
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.theme.RR
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/// 공용 차트 8종 — iOS `RRCharts.swift`를 수치 그대로 Compose로 이식.
/// 규칙(작업 지침): 막대 위 값 상시 표시, 좁으면 탭 콜아웃(ChartCallout)으로 대체,
/// 추세 차트는 포인트 탭으로 값 확인. 색은 전부 RR 토큰만 쓴다.

/// 차트 탭 콜아웃 — 선택한 지점의 시기·수치를 담는 작은 말풍선
@Composable
fun ChartCallout(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(RR.surface2, RoundedCornerShape(7.dp))
            .border(1.dp, RR.line, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace, color = RR.text,
        maxLines = 1, softWrap = false,
    )
}

/// 주간 막대 차트 — 주 수가 넘치면 가로 스크롤(최신 주가 오른쪽 끝),
/// 막대 탭으로 콜아웃 토글. 상한선(cap)은 위험색 점선.
@Composable
fun WeeklyBarsChart(
    weeks: List<WeeklyReport.WeekBar>,
    currentColor: Color? = null,
    cap: Double? = null,
    capLabel: String? = null,
    chartHeight: Dp = 76.dp,
    valueText: (Double) -> String = { Format.km(it) + " km" },
    barValueText: (Double) -> String = { Format.km(it) },
) {
    val current = currentColor ?: RR.brand
    val surface = RR.surface
    val dang = RR.dang
    val text3 = RR.text3
    var selected by remember { mutableStateOf<Int?>(null) }   // WeekBar.index

    // 스크롤 모드에서 주 하나가 차지하는 최소 폭 — "12월 4째주" 라벨이 들어가는 폭
    val minSlot = 56.dp
    val labelHeight = 22.dp
    // 막대 위 값 텍스트가 차지하는 높이 — 막대·상한선 스케일은 이만큼 뺀 높이를 쓴다
    val valueReserve = 13.dp
    val peak = maxOf(weeks.maxOfOrNull { it.km } ?: 1.0, cap ?: 0.0)
    val scaleMax = if (peak > 0) peak * 1.08 else 1.0

    BoxWithConstraints(Modifier.fillMaxWidth().height(chartHeight + labelHeight)) {
        val count = maxOf(weeks.size, 1)
        val scrollable = minSlot * count > maxWidth
        val slot = if (scrollable) minSlot else maxWidth / count
        val contentWidth = slot * count
        val scroll = rememberScrollState()
        // 최신 주부터 보인다 — 측정 뒤 오른쪽 끝으로
        LaunchedEffect(scroll.maxValue, scrollable) {
            if (scrollable && scroll.maxValue != Int.MAX_VALUE) scroll.scrollTo(scroll.maxValue)
        }

        Box(Modifier.fillMaxSize().horizontalScroll(scroll, enabled = scrollable)) {
            Column(Modifier.width(contentWidth)) {
                Box(Modifier.height(chartHeight)) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                        weeks.forEach { week ->
                            val barH = maxOf(
                                4.dp,   // 0이어도 흔적은 보이게
                                (chartHeight - valueReserve) * (week.km / scaleMax).toFloat(),
                            )
                            val dimmed = selected != null && selected != week.index
                            Column(
                                Modifier
                                    .width(slot)
                                    .fillMaxHeight()
                                    .alpha(if (dimmed) 0.45f else 1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        selected = if (selected == week.index) null else week.index
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                if (week.km > 0) {
                                    Text(
                                        barValueText(week.km),
                                        fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = if (week.isCurrent) FontWeight.SemiBold
                                                     else FontWeight.Normal,
                                        color = if (week.isCurrent) current else text3,
                                        maxLines = 1, softWrap = false,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                }
                                Box(
                                    Modifier
                                        .width(maxOf(6.dp, slot - 10.dp))
                                        .height(barH)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (week.isCurrent) current else RR.barFill),
                                )
                            }
                        }
                    }

                    if (cap != null && cap <= scaleMax) {
                        val capY = chartHeight - (chartHeight - valueReserve) * (cap / scaleMax).toFloat()
                        Canvas(Modifier.fillMaxSize()) {
                            drawLine(
                                dang.copy(alpha = 0.65f),
                                Offset(0f, capY.toPx()), Offset(size.width, capY.toPx()),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                                ),
                            )
                        }
                        if (capLabel != null) {
                            Text(
                                capLabel,
                                Modifier.offset(x = 2.dp, y = maxOf(0.dp, capY - 15.dp)),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = dang,
                            )
                        }
                    }

                    selected?.let { sel ->
                        weeks.firstOrNull { it.index == sel }?.let { week ->
                            // 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
                            val x = (slot * (sel + 0.5f))
                                .coerceIn(62.dp, maxOf(contentWidth - 62.dp, 62.dp))
                            ChartCallout(
                                "${week.label} · ${valueText(week.km)}",
                                Modifier.positionAt(x, 13.dp),
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    weeks.forEach { week ->
                        Text(
                            week.label,
                            Modifier.width(slot),
                            fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (week.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (week.isCurrent) current else text3,
                            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }

        // 잘린 게 아니라 과거로 이어진다는 신호 — 스크롤 가능할 때만
        if (scrollable) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(22.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(surface, surface.copy(alpha = 0f)))
                    ),
            )
        }
    }
}

/// ACWR 반원 게이지 — 0.5~2.0, 안전(0.8~1.3)/주의/위험 구간 표시.
/// 시안 좌표계 320×152 기준: 중심 (160,124), 반지름 100 — 폭에 비례해 스케일.
@Composable
fun AcwrGauge(ratio: Double, modifier: Modifier = Modifier) {
    val barFill = RR.barFill
    val pos = RR.pos
    val warn = RR.warn
    val dang = RR.dang
    val textColor = RR.text

    // 값 → 각도: 0.5가 왼쪽(180°), 2.0이 오른쪽(360°)
    fun angle(v: Double): Float =
        (180.0 + (v.coerceIn(0.5, 2.0) - 0.5) / 1.5 * 180.0).toFloat()

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(320f / 152f)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 320f
            val center = Offset(160f * s, 124f * s)
            val radius = 100f * s
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            listOf(
                Triple(0.5, 0.8, barFill),
                Triple(0.8, 1.3, pos),
                Triple(1.3, 1.5, warn),
                Triple(1.5, 2.0, dang),
            ).forEach { (a, b, color) ->
                drawArc(
                    color, angle(a), angle(b) - angle(a), useCenter = false,
                    topLeft = arcTopLeft, size = arcSize,
                    style = Stroke(15f * s, cap = StrokeCap.Butt),
                )
            }
            // 값 위치 마커 — 호를 가로지르는 짧은 눈금 (바늘은 가운데 숫자를 관통해 겹친다)
            val rad = Math.toRadians(angle(ratio).toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            drawLine(
                textColor,
                Offset(center.x + dx * (radius - 13f * s), center.y + dy * (radius - 13f * s)),
                Offset(center.x + dx * (radius + 13f * s), center.y + dy * (radius + 13f * s)),
                strokeWidth = 4f * s, cap = StrokeCap.Round,
            )
        }

        val sd = maxWidth / 320.dp   // 디자인 단위 → dp 배율
        Text(
            "%.2f".format(ratio),
            Modifier.positionAt((160 * sd).dp, (100 * sd).dp),
            fontSize = (36 * sd).sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = textColor,
        )
        Text(
            "0.5", Modifier.positionAt((60 * sd).dp, (138 * sd).dp),
            fontSize = (10 * sd).sp, fontFamily = FontFamily.Monospace, color = RR.text3,
        )
        Text(
            // 초록 호와 겹치지 않는 오목면 안쪽
            "0.8–1.3 안전", Modifier.positionAt((138 * sd).dp, (54 * sd).dp),
            fontSize = (10 * sd).sp, fontFamily = FontFamily.Monospace, color = pos,
        )
        Text(
            "2.0", Modifier.positionAt((260 * sd).dp, (138 * sd).dp),
            fontSize = (10 * sd).sp, fontFamily = FontFamily.Monospace, color = RR.text3,
        )
    }
}

/// 추세 라인 차트 — 그라디언트 채움 + 끝점 도트 (EF 카드).
/// 차트를 탭하면 가장 가까운 점의 시기·수치를 콜아웃으로 띄운다 (다시 탭하면 닫힘).
@Composable
fun TrendLineChart(
    points: List<Double>,
    tint: Color? = null,
    height: Dp = 96.dp,
    endLabels: Pair<String, String>? = null,
    pointLabels: List<String>? = null,
    valueText: (Double) -> String = { "%.2f".format(it) },
) {
    val color = tint ?: RR.pos
    val lineColor = RR.line
    val surface = RR.surface
    var selected by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { height.toPx() }
            val insetPx = with(density) { 10.dp.toPx() }
            val pts = remember(points, widthPx, heightPx) {
                trendPoints(points, widthPx, heightPx, insetPx)
            }

            Canvas(
                Modifier.fillMaxSize().pointerInput(pts) {
                    detectTapGestures { tap ->
                        val nearest = pts.indices.minByOrNull { abs(pts[it].x - tap.x) }
                            ?: return@detectTapGestures
                        selected = if (selected == nearest) null else nearest
                    }
                },
            ) {
                // 기준선
                drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height),
                         strokeWidth = 1.dp.toPx())
                if (pts.size >= 2) {
                    val fill = Path().apply {
                        moveTo(pts.first().x, size.height)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, size.height)
                        close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
                            startY = 0f, endY = size.height,
                        ),
                    )
                    val line = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(line, color, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round,
                                                         join = StrokeJoin.Round))
                    // 끝점 도트 — tint 채움 + surface 링
                    drawCircle(color, 5.dp.toPx(), pts.last())
                    drawCircle(surface, 5.dp.toPx(), pts.last(), style = Stroke(2.5.dp.toPx()))

                    selected?.let { i ->
                        if (i in pts.indices) {
                            val p = pts[i]
                            // 선택 표식 — 세로 가이드선 + 링 도트
                            drawLine(
                                lineColor, Offset(p.x, size.height), Offset(p.x, p.y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(3.dp.toPx(), 3.dp.toPx())
                                ),
                            )
                            drawCircle(color, 5.dp.toPx(), p)
                            drawCircle(surface, 5.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                        }
                    }
                }
            }

            selected?.let { i ->
                if (i in pts.indices && i in points.indices) {
                    val p = pts[i]
                    val xDp = with(density) { p.x.toDp() }
                    val yDp = with(density) { p.y.toDp() }
                    val label = pointLabels?.getOrNull(i)
                    ChartCallout(
                        label?.let { "$it · ${valueText(points[i])}" } ?: valueText(points[i]),
                        Modifier.positionAt(
                            xDp.coerceIn(56.dp, maxOf(maxWidth - 56.dp, 56.dp)),
                            maxOf(yDp - 24.dp, 12.dp),
                        ),
                    )
                }
            }
        }

        endLabels?.let { (left, right) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(left, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = RR.text3)
                Text(right, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = RR.text3)
            }
        }
    }
}

/// 추세 차트 좌표 정규화 — 세로는 0.18h(위)~0.88h(아래), 가로는 양끝 10px 인셋
private fun trendPoints(points: List<Double>, w: Float, h: Float, inset: Float): List<Offset> {
    if (points.size < 2) return emptyList()
    val low = points.min()
    val span = maxOf(points.max() - low, 0.0001)
    val usableW = w - inset * 2
    val top = h * 0.18f
    val bottom = h * 0.88f
    return points.mapIndexed { i, v ->
        Offset(
            inset + usableW * i / (points.size - 1),
            bottom - (bottom - top) * ((v - low) / span).toFloat(),
        )
    }
}

/// 작은 스파크라인 (통계 타일). invert=true면 값이 작을수록 위(페이스용).
@Composable
fun SparkLine(
    points: List<Double>,
    tint: Color? = null,
    invert: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val color = tint ?: RR.text3
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        if (points.size < 2) return@Canvas
        val low = points.min()
        val span = maxOf(points.max() - low, 0.0001)
        val path = Path()
        points.forEachIndexed { i, v ->
            var t = ((v - low) / span).toFloat()
            if (!invert) t = 1f - t
            val x = size.width * i / (points.size - 1)
            val y = size.height * (0.12f + 0.76f * t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round,
                                             join = StrokeJoin.Round))
    }
}

/// 구간별(km) 페이스 막대 — 평균 대비 느린 구간은 경고색.
/// 구간이 많아 폭이 모자라면 막대 폭을 유지한 채 가로 스크롤로 넘긴다.
/// 막대가 좁아 값 상시 표시가 불가능한 차트 — 탭 콜아웃으로 대신한다 (작업 지침 차트 규칙).
@Composable
fun SplitBarsChart(
    splits: List<WorkoutDetail.Split>,
    slowThresholdSec: Double = 8.0,
    height: Dp = 64.dp,
) {
    if (splits.isEmpty()) return
    val brand = RR.brand
    val warn = RR.warn
    val surface = RR.surface
    val text3 = RR.text3
    var selected by remember { mutableStateOf<Int?>(null) }   // Split.index

    // 스크롤 모드에서 막대 하나가 차지하는 최소 폭 (막대 + 간격)
    val slot = 10.dp
    val gap = 3.dp
    val axisHeight = 13.dp
    val avgPace = splits.sumOf { it.paceSecPerKm } / splits.size
    val speeds = splits.map { 1.0 / it.paceSecPerKm }
    val minSpeed = speeds.min()
    val span = maxOf(speeds.max() - minSpeed, 0.0001)

    BoxWithConstraints(Modifier.fillMaxWidth().height(height + 4.dp + axisHeight)) {
        val needed = slot * splits.size
        val scrollable = needed > maxWidth
        val width = maxOf(maxWidth, needed)
        val barW = maxOf(3.dp, (width - gap * (splits.size - 1)) / splits.size)
        // 라벨이 서로 붙지 않는 최소 간격 (30dp 확보)
        val step = listOf(1, 2, 5, 10, 20, 50).firstOrNull { (barW + gap) * it >= 30.dp } ?: 100
        val scroll = rememberScrollState()

        Box(Modifier.fillMaxSize().horizontalScroll(scroll, enabled = scrollable)) {
            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.height(height)) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        splits.forEach { split ->
                            val t = ((1.0 / split.paceSecPerKm - minSpeed) / span).toFloat()
                            val slow = split.paceSecPerKm > avgPace + slowThresholdSec
                            val dimmed = selected != null && selected != split.index
                            Box(
                                Modifier
                                    .width(barW)
                                    .height(height * (0.45f + 0.55f * t))
                                    .alpha(if (dimmed) 0.55f else 1f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (slow) warn else brand.copy(alpha = 0.8f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        selected = if (selected == split.index) null
                                                   else split.index
                                    },
                            )
                        }
                    }

                    selected?.let { sel ->
                        val offset = splits.indexOfFirst { it.index == sel }
                        if (offset >= 0) {
                            // 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
                            val x = ((barW + gap) * offset + barW / 2)
                                .coerceIn(56.dp, maxOf(width - 56.dp, 56.dp))
                            ChartCallout(
                                "${splits[offset].index}km · " +
                                    Format.pace(splits[offset].paceSecPerKm),
                                Modifier.positionAt(x, 13.dp),
                            )
                        }
                    }
                }

                // km 눈금 — 막대와 같은 슬롯에 얹어 스크롤해도 어긋나지 않는다
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    splits.forEach { split ->
                        Box(Modifier.width(barW).height(axisHeight),
                            contentAlignment = Alignment.Center) {
                            if (split.index == 1 || split.index % step == 0) {
                                Text(
                                    "${split.index}",
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    color = text3, maxLines = 1, softWrap = false,
                                )
                            }
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
                    .background(
                        Brush.horizontalGradient(listOf(surface.copy(alpha = 0f), surface))
                    ),
            )
        }
    }
}

/// 심박 존 분포 바 + 존별 퍼센트 (Z1~Z5, 비율 합 1.0)
@Composable
fun ZoneBarView(fractions: List<Double>) {
    val colors = listOf(RR.barFill, RR.pos.copy(alpha = 0.55f), RR.pos, RR.warn, RR.dang)
    val maxF = fractions.maxOrNull() ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            fractions.forEachIndexed { i, f ->
                Box(
                    Modifier
                        .weight(maxOf(f, 0.0001).toFloat())
                        .fillMaxHeight()
                        .background(colors[minOf(i, colors.size - 1)]),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fractions.forEachIndexed { i, f ->
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Z${i + 1}", fontSize = 10.sp, color = RR.text3)
                    Text(
                        "${(f * 100).roundToInt()}%",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (f == maxF) RR.text else RR.text2,
                    )
                }
            }
        }
    }
}

/// 가로 배터리 모양 잔량 게이지 — 몸통 10칸(한 칸 10%) + 오른쪽 단자.
/// 색은 잔량 구간으로 정한다: 50%↑ 초록 / 20%↑ 노랑 / 그 아래 빨강
/// (배터리 인디케이터 관습 — BatteryEngine의 톤 경계와는 일부러 다르다).
@Composable
fun BatteryGauge(level: Int, modifier: Modifier = Modifier) {
    val fillColor = when {
        level >= 50 -> RR.pos
        level >= 20 -> RR.warn
        else -> RR.dang
    }
    // 0%가 아니면 최소 1칸은 남겨 "완전 방전"과 구분한다
    val filledCells = if (level <= 0) 0
                      else maxOf(1, minOf(10, (level / 10.0).roundToInt()))
    val bodyShape = RoundedCornerShape(5.dp)

    Row(
        modifier.height(26.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(RR.surface2.copy(alpha = 0.5f), bodyShape)
                .border(1.2.dp, RR.line, bodyShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        ) {
            repeat(10) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(
                            if (index < filledCells) fillColor
                            else RR.barFill.copy(alpha = 0.45f)
                        ),
                )
            }
        }
        // 배터리 단자
        Box(
            Modifier
                .size(width = 3.5.dp, height = 10.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(RR.line),
        )
    }
}

/// SwiftUI `.position(x:y:)` 대응 — 자연 크기로 잰 뒤 중심을 부모 좌표 (x, y)에 둔다
private fun Modifier.positionAt(x: Dp, y: Dp): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(0, 0) {
        placeable.place(
            x.roundToPx() - placeable.width / 2,
            y.roundToPx() - placeable.height / 2,
        )
    }
}
