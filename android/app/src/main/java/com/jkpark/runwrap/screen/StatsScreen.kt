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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.MonthlyStats
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RecapEngine
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.displayTitle
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.metaLine
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.IndoorBadge
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.SparkLine
import com.jkpark.runwrap.ui.WeeklyBarsChart
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/// 이번달 — 월간 집계 + 러닝 기록 목록 (시안 "통계 · 월간/기록").
///
/// v0.7에서 독립 탭이 사라지고 리포트 탭의 세그먼트가 됐다 (기획서 §6).
/// 이슈 #21에서 장기 추이·PB 목록은 "나의 성장기"(GrowthScreen)로 분리됐다.
/// (Android: 결산 리캡 시트·세션 상세는 라우트 콜백으로 연다)
@Composable
fun StatsScreen(
    /// [내 상태 | 이번달 | 나의 성장기] 세그먼트 — 리포트 탭이 넘긴다
    segment: (@Composable () -> Unit)? = null,
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
    onOpenRecap: (RecapPeriod) -> Unit,
) {
    val state by LocalAppContainer.current.health.state.collectAsStateWithLifecycle()
    var monthIndex by rememberSaveable { mutableIntStateOf(0) }   // availableMonths 기준 (0 = 이번 달)
    val runs = (state as? HealthStore.State.Loaded)?.runs
    val model = containerViewModel { StatsViewModel() }
    LaunchedEffect(runs, monthIndex) { if (runs != null) model.update(StatsInput(runs, monthIndex)) }
    val data by model.result.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled = scroll.rrTracksScroll()

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        val d = data ?: return@Box
        ReportRefreshBox(Modifier.rrStatusBarScrim(visible = scrolled)) {
            ReportScrollColumn(scroll, Modifier.statusBarsPadding()) {
                ReportTitle("월별 기록")
                if (segment != null) Box(Modifier.padding(bottom = 2.dp)) { segment() }

                MonthSelector(d, onMove = { monthIndex = it })
                RecapRow(d, onOpenRecap)
                DistanceCard(d.stats)
                TileGrid(d.stats)
                SessionList(d.stats) { onOpenSession(it, d.overloadContext) }
            }
        }
    }
}

private data class StatsInput(val runs: List<RunSummary>, val monthIndex: Int)

private class StatsData(
    val monthCount: Int,
    val index: Int,
    val stats: MonthlyStats,
    val monthly: RecapPeriod,
    val monthEnabled: Boolean,
    /// 연간 결산 메뉴 — (기간, 라벨, 활성)
    val years: List<Triple<RecapPeriod, String, Boolean>>,
    /// 세션 상세의 맥락 배지용 — 이번 주 리포트가 과부하일 때만 전달.
    /// 세션과 무관한 값이라 목록을 그릴 때 한 번만 계산한다 (이슈 #158)
    val overloadContext: WeeklyReport.DistanceCard?,
)

private class StatsViewModel : ReportTabViewModel<StatsInput, StatsData?>() {
    override fun compute(input: StatsInput): StatsData? {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val runs = input.runs
        // availableMonths는 이번 달을 항상 담지만, 빈 배열이면 months[-1]로 크래시하므로 한 번 더 막는다 (이슈 #68)
        val months = MonthlyStats.availableMonths(runs, now, zone)
        if (months.isEmpty()) return null
        val index = minOf(input.monthIndex, months.size - 1)
        val month = months[index]
        // "이 달 결산 보기" + 연간 결산 메뉴(선택한 달의 연도와 그 전 연도). 기록 3회 미만 기간은 비활성
        val monthly = RecapPeriod.month(month)
        val thisYear = month.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone)
        val years = listOf(thisYear, thisYear.minusYears(1)).map { y ->
            val period = RecapPeriod.year(y.toInstant())
            Triple<RecapPeriod, String, Boolean>(
                period, RecapEngine.periodLabel(period, zone) + " 결산", RecapEngine.hasEnoughRuns(period, runs, zone),
            )
        }
        return StatsData(
            monthCount = months.size,
            index = index,
            stats = MonthlyStats.compute(runs, month, now, zone),
            monthly = monthly,
            monthEnabled = RecapEngine.hasEnoughRuns(monthly, runs, zone),
            years = years,
            overloadContext = ReportEngine(now).weeklyReport(runs, zone).distance?.takeIf { it.tone == RRTone.overload },
        )
    }
}

// MARK: 결산 리캡 (이슈 #167)

@Composable
private fun RecapRow(d: StatsData, onOpenRecap: (RecapPeriod) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecapLabel(
                "이 달 결산 보기", "sparkles", if (d.monthEnabled) RR.brand else RR.text3,
                Modifier
                    .weight(1f)
                    .background(RR.surface, shape)
                    .border(1.dp, RR.line, shape)
                    .clickable(enabled = d.monthEnabled, role = Role.Button) { onOpenRecap(d.monthly) }
                    .padding(vertical = 10.dp),
            )
            Box {
                RecapLabel(
                    "연간 결산", "calendar", RR.text,
                    Modifier
                        .background(RR.surface, shape)
                        .border(1.dp, RR.line, shape)
                        .clickable(role = Role.Button) { menuOpen = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = RR.surface) {
                    d.years.forEach { (period, label, enabled) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menuOpen = false; onOpenRecap(period) },
                            enabled = enabled,
                            colors = MenuDefaults.itemColors(textColor = RR.text, disabledTextColor = RR.text3),
                        )
                    }
                }
            }
        }
        if (!d.monthEnabled) {
            Text("기록 3회 이상이면 결산이 열립니다", style = reportText(11.5f), color = RR.text3,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/// SwiftUI `Label(title, systemImage:)` — 아이콘 + 글자
@Composable
private fun RecapLabel(text: String, icon: String, color: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        ReportIcon(icon, color, 14.dp)
        Text(text, style = reportText(13f, FontWeight.SemiBold), color = color)
    }
}

// MARK: 월 선택

@Composable
private fun MonthSelector(d: StatsData, onMove: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(RR.surface, shape)
            .border(1.dp, RR.line, shape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonthChevron("chevron.left", "이전 달", d.index < d.monthCount - 1) { onMove(minOf(d.index + 1, d.monthCount - 1)) }
        Spacer(Modifier.weight(1f))
        Text(d.stats.monthLabel, style = reportText(15f, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), color = RR.text)
        Spacer(Modifier.weight(1f))
        MonthChevron("chevron.right", "다음 달", d.index > 0) { onMove(maxOf(d.index - 1, 0)) }
    }
}

@Composable
private fun MonthChevron(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ReportIcon(icon, if (enabled) RR.brand else RR.text3, 16.dp)
    }
}

// MARK: 누적 거리

@Composable
private fun DistanceCard(stats: MonthlyStats) {
    Column(Modifier.fillMaxWidth().rrCard().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("누적 거리", style = reportText(12f), color = RR.text3)
                Text(buildAnnotatedString {
                    withStyle(RR.numeral(48.sp).toSpanStyle().copy(color = RR.text)) { append(Format.km(stats.totalKm)) }
                    withStyle(SpanStyle(fontSize = 15.sp, color = RR.text3)) { append(" km") }
                })
            }
            val delta = stats.deltaPct
            if (delta != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    DeltaBadge(delta)
                    Text(stats.deltaCaption, style = mono(10.sp), color = RR.text3)
                }
            }
        }
        WeeklyBarsChart(stats.weeks, Modifier.padding(top = 16.dp), chartHeight = 54.dp)
    }
}

@Composable
private fun DeltaBadge(pct: Double) {
    val up = pct >= 0
    val color = if (up) RR.pos else RR.text2
    Row(
        Modifier
            .background(if (up) RR.posSoft else RR.barFill, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (up) "▲" else "▼", style = reportText(10f), color = color)
        Text(fmt(abs(pct), 1) + "%", style = mono(12.sp, FontWeight.Bold), color = color)
    }
}

// MARK: 2×2 지표 타일

private class Spark(val points: List<Double>, val tint: Color, val invert: Boolean)

@Composable
private fun TileGrid(stats: MonthlyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                "평균 페이스", stats.avgPaceSec?.let(Format::pace) ?: "—", null,
                stats.paceDeltaSec?.let { delta ->
                    val sec = abs(delta).swiftRoundedInt()
                    "${if (delta <= 0) "−" else "+"}$sec″ /km" to (if (delta <= 0) RR.pos else RR.warn)
                },
                if (stats.pacePoints.size >= 2) Spark(stats.pacePoints, RR.pos, true) else null,
            )
            Tile(
                "평균 심박", stats.avgHeartRate?.let { "${it.swiftRoundedInt()}" } ?: "—",
                if (stats.avgHeartRate != null) "bpm" else null,
                stats.heartRateDelta?.let { delta ->
                    "${if (delta <= 0) "−" else "+"}${abs(delta).swiftRoundedInt()} bpm" to RR.text3
                },
                if (stats.heartRatePoints.size >= 2) Spark(stats.heartRatePoints, RR.text3, false) else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("러닝 횟수", "${stats.count}", "회", stats.perWeek?.let { "주 ${fmt(it, 1)}회" to RR.text3 }, null)
            Tile("누적 시간", Format.duration(stats.totalDurationSec), null, null, null)
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, unit: String?, delta: Pair<String, Color>?, spark: Spark?) {
    Column(Modifier.weight(1f).rrCard().padding(15.dp).heightIn(min = 108.dp)) {
        Text(label, style = reportText(11.5f), color = RR.text3)
        BasicText(
            buildAnnotatedString {
                withStyle(mono(22.sp, FontWeight.Bold).toSpanStyle().copy(color = RR.text)) { append(value) }
                if (unit != null) withStyle(SpanStyle(fontSize = 0.545.em, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Default, color = RR.text3)) { append(" $unit") }
            },
            Modifier.padding(top = 7.dp),
            style = mono(22.sp, FontWeight.Bold),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 22.sp * 0.7f, maxFontSize = 22.sp),
        )
        if (delta != null) {
            Text(delta.first, style = mono(11.sp), color = delta.second, modifier = Modifier.padding(top = 3.dp))
        }
        if (spark != null) SparkLine(spark.points, Modifier.padding(top = 10.dp), spark.tint, spark.invert)
    }
}

// MARK: 세션 목록

@Composable
private fun SessionList(stats: MonthlyStats, onOpen: (RunSummary) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)) {
            Text("러닝 기록", style = reportText(17f, FontWeight.Bold), color = RR.text,
                 modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text("${stats.count}회", style = mono(11.5.sp), color = RR.text3, modifier = Modifier.alignByBaseline())
        }

        if (stats.runs.isEmpty()) {
            Box(Modifier.fillMaxWidth().rrCard().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                Text("이 달에는 기록이 없어요", style = reportText(13.5f), color = RR.text3)
            }
        } else {
            // 행마다 전체 주간 리포트를 다시 돌리지 않는다 — 목록 전체에 한 번만 (이슈 #158)
            Column(Modifier.fillMaxWidth().rrCard()) {
                stats.runs.forEachIndexed { index, run ->
                    SessionRow(run) { onOpen(run) }
                    if (index < stats.runs.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 66.dp), thickness = Dp.Hairline, color = RR.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(run: RunSummary, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val date = run.start.atZone(zone)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(38.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${date.monthValue}월", style = reportText(11f), color = RR.text3)
            Text(String.format("%02d", date.dayOfMonth), style = mono(17.sp, FontWeight.Bold), color = RR.text)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(run.displayTitle(zone), style = reportText(14.5f, FontWeight.SemiBold), color = RR.text,
                     maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (run.isIndoor) IndoorBadge()
            }
            FitText(run.metaLine, mono(12.sp), RR.text2, 0.8f)
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(run.distanceKm?.let(Format::km) ?: "—", style = mono(16.sp, FontWeight.Bold), color = RR.text)
            Text("km", style = mono(11.sp), color = RR.text3)
        }

        ReportIcon("chevron.right", RR.text3, 12.dp)
    }
}
