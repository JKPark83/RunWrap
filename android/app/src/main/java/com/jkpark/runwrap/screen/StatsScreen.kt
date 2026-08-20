package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.MonthlySeries
import com.jkpark.runwrap.engine.MonthlyStats
import com.jkpark.runwrap.engine.PersonalRecords
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.engine.displayTitle
import com.jkpark.runwrap.engine.metaLine
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.charts.SparkLine
import com.jkpark.runwrap.ui.charts.TrendLineChart
import com.jkpark.runwrap.ui.charts.WeeklyBarsChart
import com.jkpark.runwrap.ui.theme.Eyebrow
import com.jkpark.runwrap.ui.theme.IndoorBadge
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.rrCard
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/// 발전상 — 월간 집계 + 월별 추이 + PB + 러닝 기록 목록. iOS `StatsScreen.swift` 이식.
/// 리포트 탭의 [이번 주 | 발전상] 세그먼트 아래 본문으로, 세그먼트는 호출자가 넘긴다.
/// iOS와 다른 점: LazyVGrid 대신 Row 2개(2×2 타일), minimumScaleFactor 대신 ellipsis.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    runs: List<RunSummary>,
    segment: @Composable () -> Unit,
    onOpenSession: (RunSummary) -> Unit,
    onReloadHealth: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val now = remember(runs) { Instant.now() }
    var monthIndex by rememberSaveable { mutableIntStateOf(0) }   // 0 = 이번 달
    var progressMetric by rememberSaveable { mutableStateOf(ProgressMetric.PACE) }
    var refreshing by remember { mutableStateOf(false) }

    val months = remember(runs, now) { MonthlyStats.availableMonths(runs, now) }
    val index = min(monthIndex, months.size - 1)
    val stats = remember(runs, index, now) { MonthlyStats.compute(runs, months[index], now) }
    val series = remember(runs, now) { MonthlySeries.compute(runs, now) }
    val records = remember(runs) { PersonalRecords.compute(runs) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try { onReloadHealth() } finally { refreshing = false }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(bottom = 6.dp)) {
                Eyebrow("Monthly & history")
                Text("발전상", Modifier.padding(top = 4.dp),
                     style = RR.display(33.sp), color = RR.text)
            }
            segment()

            MonthSelector(
                label = stats.monthLabel,
                canOlder = index < months.size - 1,
                canNewer = index > 0,
                onOlder = { monthIndex = min(index + 1, months.size - 1) },
                onNewer = { monthIndex = maxOf(index - 1, 0) },
            )
            MonthDistanceCard(stats)
            TileGrid(stats)
            if (series != null || records.isNotEmpty()) {
                ProgressSection(series, records, progressMetric,
                                onSelectMetric = { progressMetric = it },
                                onOpenSession = onOpenSession)
            }
            SessionList(stats, onOpenSession)
        }
    }
}

// MARK: - 월 선택

@Composable
private fun MonthSelector(
    label: String,
    canOlder: Boolean,
    canNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RR.surface, RoundedCornerShape(14.dp))
            .border(1.dp, RR.line, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 달",
            tint = if (canOlder) RR.brand else RR.text3,
            modifier = Modifier
                .clickable(enabled = canOlder, onClick = onOlder)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .size(18.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold,
             fontFamily = FontFamily.Monospace, color = RR.text)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달",
            tint = if (canNewer) RR.brand else RR.text3,
            modifier = Modifier
                .clickable(enabled = canNewer, onClick = onNewer)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .size(18.dp),
        )
    }
}

// MARK: - 누적 거리

@Composable
private fun MonthDistanceCard(stats: MonthlyStats) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("누적 거리", fontSize = 12.sp, color = RR.text3)
                Row {
                    Text(Format.km(stats.totalKm), style = RR.numeral(48.sp), color = RR.text,
                         modifier = Modifier.alignByBaseline())
                    Text(" km", fontSize = 15.sp, color = RR.text3,
                         modifier = Modifier.alignByBaseline())
                }
            }
            Spacer(Modifier.weight(1f))
            stats.deltaPct?.let { delta ->
                Column(horizontalAlignment = Alignment.End,
                       verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    DeltaBadge(delta)
                    Text(stats.deltaCaption, fontSize = 10.sp,
                         fontFamily = FontFamily.Monospace, color = RR.text3)
                }
            }
        }
        Box(Modifier.padding(top = 16.dp)) {
            WeeklyBarsChart(weeks = stats.weeks, chartHeight = 54.dp)
        }
    }
}

@Composable
private fun DeltaBadge(pct: Double) {
    val up = pct >= 0
    Row(
        Modifier
            .background(if (up) RR.posSoft else RR.barFill, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (up) RR.pos else RR.text2
        Text(if (up) "▲" else "▼", fontSize = 10.sp, color = color)
        Text("%.1f%%".format(Locale.ROOT, abs(pct)), fontSize = 12.sp,
             fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
    }
}

// MARK: - 2×2 지표 타일

@Composable
private fun TileGrid(stats: MonthlyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                label = "평균 페이스",
                value = stats.avgPaceSec?.let(Format::pace) ?: "—",
                unit = null,
                delta = stats.paceDeltaSec?.let { delta ->
                    val sec = abs(delta).roundToInt()
                    val sign = if (delta <= 0) "−" else "+"
                    "$sign$sec″ /km" to (if (delta <= 0) RR.pos else RR.warn)
                },
                spark = stats.pacePoints.takeIf { it.size >= 2 }
                    ?.let { Triple(it, RR.pos, true) },
            )
            Tile(
                label = "평균 심박",
                value = stats.avgHeartRate?.let { "${it.roundToInt()}" } ?: "—",
                unit = if (stats.avgHeartRate != null) "bpm" else null,
                delta = stats.heartRateDelta?.let { delta ->
                    val sign = if (delta <= 0) "−" else "+"
                    "$sign${abs(delta).roundToInt()} bpm" to RR.text3
                },
                spark = stats.heartRatePoints.takeIf { it.size >= 2 }
                    ?.let { Triple(it, RR.text3, false) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                label = "러닝 횟수",
                value = "${stats.count}",
                unit = "회",
                delta = "주 %.1f회".format(Locale.ROOT, stats.perWeek) to RR.text3,
                spark = null,
            )
            Tile(
                label = "누적 시간",
                value = Format.duration(stats.totalDurationSec),
                unit = null,
                delta = null,
                spark = null,
            )
        }
    }
}

@Composable
private fun RowScope.Tile(
    label: String,
    value: String,
    unit: String?,
    delta: Pair<String, Color>?,
    spark: Triple<List<Double>, Color, Boolean>?,
) {
    Column(
        Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 108.dp)
            .rrCard()
            .padding(15.dp),
    ) {
        Text(label, fontSize = 11.5.sp, color = RR.text3)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = RR.text,
                )) { append(value) }
                unit?.let {
                    withStyle(SpanStyle(fontSize = 12.sp, color = RR.text3)) { append(" $it") }
                }
            },
            Modifier.padding(top = 7.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        delta?.let { (text, color) ->
            Text(text, Modifier.padding(top = 3.dp), fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace, color = color)
        }
        spark?.let { (points, tint, invert) ->
            SparkLine(points = points, tint = tint, invert = invert,
                      modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

// MARK: - 발전상 — 월별 추이 + 최고 기록 (기획서 §4.7)

/// 추이 차트의 지표 세그먼트
private enum class ProgressMetric(val label: String, val caption: String) {
    PACE("페이스", "월 평균 페이스 · 내려갈수록 빨라진 것"),
    EF("EF", "심박당 속도(EF) 월 평균 · 올라갈수록 좋아진 것"),
    DISTANCE("거리", "월 누적 거리"),
}

@Composable
private fun ProgressSection(
    series: MonthlySeries?,
    records: List<PersonalRecords.Entry>,
    metric: ProgressMetric,
    onSelectMetric: (ProgressMetric) -> Unit,
    onOpenSession: (RunSummary) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("발전상", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RR.text)
            Spacer(Modifier.weight(1f))
            Text("최근 12개월", fontSize = 11.5.sp,
                 fontFamily = FontFamily.Monospace, color = RR.text3)
        }
        if (series != null) ProgressChartCard(series, metric, onSelectMetric)
        if (records.isNotEmpty()) RecordsCard(records, onOpenSession)
    }
}

@Composable
private fun ProgressChartCard(
    series: MonthlySeries,
    metric: ProgressMetric,
    onSelectMetric: (ProgressMetric) -> Unit,
) {
    // 가드로 점이 없는 달은 건너뛴다 — 라벨도 함께 걸러 축과 어긋나지 않게
    val points = when (metric) {
        ProgressMetric.PACE -> series.points.mapNotNull { p -> p.avgPaceSec?.let { p.label to it } }
        ProgressMetric.EF -> series.points.mapNotNull { p -> p.avgEF?.let { p.label to it } }
        ProgressMetric.DISTANCE -> series.points.map { it.label to it.totalKm }
    }
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 16.dp, start = 18.dp, end = 18.dp, bottom = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressMetric.entries.forEach { entry ->
                val selected = entry == metric
                Text(
                    entry.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else RR.text2,
                    modifier = Modifier
                        .background(if (selected) RR.brand else RR.surface2, CircleShape)
                        .clickable { onSelectMetric(entry) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (points.size >= 2) {
            Box(Modifier.padding(top = 14.dp)) {
                TrendLineChart(
                    points = points.map { it.second },
                    tint = RR.brand,
                    endLabels = points.first().first to points.last().first,
                    pointLabels = points.map { it.first },
                    valueText = when (metric) {
                        ProgressMetric.PACE -> { v -> Format.paceKm(v) }
                        ProgressMetric.EF -> { v -> "EF %.2f".format(Locale.ROOT, v) }
                        ProgressMetric.DISTANCE -> { v -> Format.km(v) + " km" }
                    },
                )
            }
        } else {
            // EF처럼 월별 표본 가드에 걸린 지표는 점이 모자랄 수 있다
            Text(
                "이 지표는 아직 월별 기록이 부족해요. 심박이 함께 찍힌 러닝이 달마다 3회쯤 쌓이면 그려집니다.",
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                fontSize = 12.5.sp, lineHeight = 18.sp, color = RR.text3,
            )
        }
        Text(metric.caption, Modifier.padding(top = 8.dp), fontSize = 11.5.sp, color = RR.text3)
    }
}

@Composable
private fun RecordsCard(
    records: List<PersonalRecords.Entry>,
    onOpenSession: (RunSummary) -> Unit,
) {
    Column(Modifier.fillMaxWidth().rrCard()) {
        // 추이 차트 카드와 붙어 있어 제목이 없으면 무슨 목록인지 읽히지 않는다
        Box(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp)) {
            Eyebrow("내 PB 목록")
        }
        records.forEachIndexed { index, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSession(entry.run) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = RR.brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(44.dp)
                        .background(RR.brandSoft, RoundedCornerShape(8.dp))
                        .padding(vertical = 5.dp),
                )
                Text(Format.duration(entry.timeSec), fontSize = 17.sp,
                     fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                     color = RR.text)
                Spacer(Modifier.weight(1f))
                Text(recordDateText(entry.date), fontSize = 11.5.sp,
                     fontFamily = FontFamily.Monospace, color = RR.text3)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                     tint = RR.text3, modifier = Modifier.size(14.dp))
            }
            if (index < records.size - 1) {
                HorizontalDivider(Modifier.padding(start = 66.dp), color = RR.line)
            }
        }
    }
}

private fun recordDateText(date: Instant): String =
    DateTimeFormatter.ofPattern("yyyy.M.d", Locale.KOREAN).format(date.atZone(SEOUL))

// MARK: - 세션 목록

@Composable
private fun SessionList(stats: MonthlyStats, onOpenSession: (RunSummary) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("러닝 기록", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RR.text)
            Spacer(Modifier.weight(1f))
            Text("${stats.count} sessions", fontSize = 11.5.sp,
                 fontFamily = FontFamily.Monospace, color = RR.text3)
        }
        if (stats.runs.isEmpty()) {
            Box(Modifier.fillMaxWidth().rrCard().padding(vertical = 28.dp),
                contentAlignment = Alignment.Center) {
                Text("이 달에는 기록이 없어요", fontSize = 13.5.sp, color = RR.text3)
            }
        } else {
            Column(Modifier.fillMaxWidth().rrCard()) {
                stats.runs.forEachIndexed { index, run ->
                    SessionRow(run) { onOpenSession(run) }
                    if (index < stats.runs.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 66.dp), color = RR.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(run: RunSummary, onTap: () -> Unit) {
    val zoned = run.start.atZone(SEOUL)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(38.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${zoned.monthValue}월", fontSize = 11.sp, color = RR.text3)
            Text("%02d".format(zoned.dayOfMonth), fontSize = 17.sp,
                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = RR.text)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(run.displayTitle, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                     color = RR.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (run.isIndoor) IndoorBadge()
            }
            Text(run.metaLine, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                 color = RR.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End,
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(run.distanceKm?.let(Format::km) ?: "—", fontSize = 16.sp,
                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = RR.text)
            Text("km", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RR.text3)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
             tint = RR.text3, modifier = Modifier.size(14.dp))
    }
}
