package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.BestEffortTable
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.MonthlySeries
import com.jkpark.runwrap.engine.PersonalRecords
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.TrendLineChart
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import java.time.Instant
import java.time.ZoneId

/// 나의 성장기 — 장기 추이 3종(페이스·EF·거리) + PB 목록 (이슈 #21).
///
/// StatsScreen("발전상" 섹션)에 있던 추이·PB를 세그먼트 화면으로 분리했다.
/// 지표 전환 세그먼트 대신 카드 3장으로 펼쳐 한 화면에서 흐름을 훑게 한다.
/// PB에는 종목별 메달(풀=금·하프=은·10K=동·5K=브랜드색)을 단다.
@Composable
fun GrowthScreen(
    /// [내 상태 | 이번달 | 나의 성장기] 세그먼트 — 리포트 탭이 넘긴다
    segment: (@Composable () -> Unit)? = null,
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
) {
    val health = LocalAppContainer.current.health
    val state by health.state.collectAsStateWithLifecycle()
    val bestEfforts by health.bestEfforts.collectAsStateWithLifecycle()
    val pending by health.bestEffortPending.collectAsStateWithLifecycle()
    val runs = (state as? HealthStore.State.Loaded)?.runs
    val model = containerViewModel { GrowthViewModel() }
    LaunchedEffect(runs, bestEfforts) { if (runs != null) model.update(GrowthInput(runs, bestEfforts)) }
    val data by model.result.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled = scroll.rrTracksScroll()

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        val d = data ?: return@Box
        ReportRefreshBox(Modifier.rrStatusBarScrim(visible = scrolled)) {
            ReportScrollColumn(scroll, Modifier.statusBarsPadding()) {
                ReportTitle("성장과 기록")
                if (segment != null) Box(Modifier.padding(bottom = 2.dp)) { segment() }

                if (d.series != null) {
                    GrowthMetric.entries.forEach { ChartCard(it, d.series) }
                } else {
                    // MonthlySeries 가드(두 달 이상 기록) 미달 — 지표 대신 안내만 낸다
                    Text("아직 성장기를 그리기엔 기록이 부족해요. 두 달 이상 러닝이 쌓이면 추이가 나타납니다.",
                         style = reportText(13.5f, lineSpacing = 4f), color = RR.text3,
                         modifier = Modifier.fillMaxWidth().rrCard().padding(horizontal = 18.dp, vertical = 20.dp))
                }

                if (d.records.isNotEmpty()) {
                    RecordsCard(d.records, pending) { onOpenSession(it, d.overloadContext) }
                } else if (pending > 0) {
                    PendingCaption(pending, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

private data class GrowthInput(val runs: List<RunSummary>, val efforts: BestEffortTable)

private class GrowthData(
    val series: MonthlySeries?,
    val records: List<PersonalRecords.Entry>,
    /// 세션 상세의 맥락 배지용 — 이번 주 리포트가 과부하일 때만 전달 (StatsScreen과 동일).
    /// 세션과 무관한 값이라 목록을 그릴 때 한 번만 계산한다 (이슈 #158)
    val overloadContext: WeeklyReport.DistanceCard?,
)

private class GrowthViewModel : ReportTabViewModel<GrowthInput, GrowthData>() {
    override fun compute(input: GrowthInput): GrowthData {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        return GrowthData(
            series = MonthlySeries.compute(input.runs, now, zone),
            records = PersonalRecords.compute(input.runs, input.efforts),
            overloadContext = ReportEngine(now).weeklyReport(input.runs, zone).distance?.takeIf { it.tone == RRTone.overload },
        )
    }
}

/// 추이 카드 3장 — 카드마다 지표가 고정이다 (전환 세그먼트 없음)
private enum class GrowthMetric(
    val rawValue: String,
    val caption: String,
    /// 표본 가드에 걸려 점이 모자랄 때의 안내 — 지표별로 쌓아야 할 게 다르다
    val emptyText: String,
) {
    pace(
        "페이스", "월 평균 페이스 · 내려갈수록 빨라진 것",
        "월 평균 페이스를 그리기엔 아직 기록이 부족해요. 거리가 찍힌 러닝이 달마다 쌓이면 그려집니다.",
    ),
    ef(
        "EF", "심박당 속도(EF) 월 평균 · 올라갈수록 좋아진 것",
        "이 지표는 아직 월별 기록이 부족해요. 심박이 함께 찍힌 러닝이 달마다 3회쯤 쌓이면 그려집니다.",
    ),
    distance("거리", "월 누적 거리", "월별 기록이 쌓이면 거리 추이가 그려집니다."),
}

// MARK: 추이 카드

@Composable
private fun ChartCard(metric: GrowthMetric, series: MonthlySeries) {
    // 지표별 (축 라벨, 값) 시리즈 — 가드로 점이 없는 달은 건너뛴다
    val points = when (metric) {
        GrowthMetric.pace -> series.points.mapNotNull { p -> p.avgPaceSec?.let { p.label to it } }
        GrowthMetric.ef -> series.points.mapNotNull { p -> p.avgEF?.let { p.label to it } }
        GrowthMetric.distance -> series.points.map { it.label to it.totalKm }
    }
    Column(Modifier.fillMaxWidth().rrCard().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
        Row {
            Text(metric.rawValue, style = reportText(15f, FontWeight.Bold), color = RR.text,
                 modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text("최근 12개월", style = mono(11.5.sp), color = RR.text3, modifier = Modifier.alignByBaseline())
        }

        if (points.size >= 2) {
            TrendLineChart(
                points = points.map { it.second },
                modifier = Modifier.padding(top = 14.dp),
                tint = RR.brand,
                endLabels = points.first().first to points.last().first,
                pointLabels = points.map { it.first },
                // 지표별 탭 콜아웃 수치 표기
                valueText = when (metric) {
                    GrowthMetric.pace -> { v -> Format.paceKm(v) }
                    GrowthMetric.ef -> { v -> "EF " + fmt(v, 2) }
                    GrowthMetric.distance -> { v -> Format.km(v) + " km" }
                },
            )
        } else {
            Text(metric.emptyText, style = reportText(12.5f, lineSpacing = 4f), color = RR.text3,
                 modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
        }

        Text(metric.caption, style = reportText(11.5f), color = RR.text3, modifier = Modifier.padding(top = 8.dp))
    }
}

// MARK: PB 목록

@Composable
private fun RecordsCard(records: List<PersonalRecords.Entry>, pending: Int, onOpen: (RunSummary) -> Unit) {
    // 행마다 전체 주간 리포트를 다시 돌리지 않는다 — 목록 전체에 한 번만 (이슈 #158)
    Column(Modifier.fillMaxWidth().rrCard()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) { Eyebrow("내 PB 목록") }
        records.forEachIndexed { index, entry ->
            RecordRow(entry) { onOpen(entry.run) }
            if (index < records.size - 1) {
                HorizontalDivider(Modifier.padding(start = 66.dp), thickness = Dp.Hairline, color = RR.line)
            }
        }
        if (pending > 0) PendingCaption(pending, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

/// 베스트 에포트 백필이 남았을 때의 안내 — 기동마다 조금씩 계산돼 목록이 채워진다 (이슈 #166)
@Composable
private fun PendingCaption(count: Int, modifier: Modifier) {
    Text("기록 분석 중 · 남은 세션 ${count}개", style = reportText(11.5f), color = RR.text3, modifier = modifier)
}

/// PB 한 줄 — 종목 메달 + 라벨 + 기록 + 날짜 + 셰브런 (이슈 #21에서 메달 추가)
@Composable
private fun RecordRow(entry: PersonalRecords.Entry, onClick: () -> Unit) {
    val date = entry.date.atZone(ZoneId.systemDefault())
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            ReportIcon("medal.fill", RR.medalColor(forPB = entry.label), 22.dp)
        }
        Text(entry.label, style = mono(12.sp, FontWeight.Bold), color = RR.text, modifier = Modifier.width(38.dp))
        Text(Format.duration(entry.timeSec), style = mono(17.sp, FontWeight.Bold), color = RR.text)
        Spacer(Modifier.weight(1f))
        Text("${date.year}.${date.monthValue}.${date.dayOfMonth}", style = mono(11.5.sp), color = RR.text3)
        ReportIcon("chevron.right", RR.text3, 12.dp)
    }
}
