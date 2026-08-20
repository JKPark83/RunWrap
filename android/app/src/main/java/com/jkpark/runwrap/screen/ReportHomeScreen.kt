package com.jkpark.runwrap.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jkpark.runwrap.engine.BatteryEngine
import com.jkpark.runwrap.engine.BatteryReport
import com.jkpark.runwrap.engine.CrossTraining
import com.jkpark.runwrap.engine.CrossTrainingEngine
import com.jkpark.runwrap.engine.DatedSample
import com.jkpark.runwrap.engine.FormTrend
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.ReportCard
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.ReportGate
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.TodayWorkout
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.engine.Vo2MaxTrend
import com.jkpark.runwrap.engine.WalkRunEngine
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.vo2MaxTrend
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.health.DemoData
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.charts.AcwrGauge
import com.jkpark.runwrap.ui.charts.BatteryGauge
import com.jkpark.runwrap.ui.charts.TrendLineChart
import com.jkpark.runwrap.ui.charts.WeeklyBarsChart
import com.jkpark.runwrap.ui.theme.Eyebrow
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.RRTone
import com.jkpark.runwrap.ui.theme.ToneBadge
import com.jkpark.runwrap.ui.theme.rrCard
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/// 리포트 탭 — [이번 주 | 발전상] 세그먼트 아래 주간 카드 묶음 (기획서 §4).
/// iOS `ReportHomeScreen.swift` 이식. 카드 노출은 전부 `ReportGate` 레벨 게이트를 거치고,
/// 지표가 null이면(표본 부족) 카드를 아예 그리지 않는다 — "틀린 인사이트는 없느니만 못하다".
/// iOS와 다른 점: HRR 줄 없음(HC에 심박 회복 레코드가 없다), SF Symbol 대신 이모지 타일,
/// 워치·권한 안내 문구는 갤럭시 워치/헬스 커넥트 기준으로 바꿨다.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportHomeScreen(
    runs: List<RunSummary>,
    vitals: VitalsSnapshot?,
    vo2Samples: List<Pair<Instant, Double>>,
    crossTrainings: List<CrossTraining>,
    settings: SettingsStore,
    onOpenDetail: (WeeklyReport, TrainingGuide?) -> Unit,
    onOpenSession: (RunSummary) -> Unit,
    onReloadHealth: suspend () -> Unit,
) {
    val levelRaw by settings.levelV2.collectAsState(initial = "")
    val weeklyGoal by settings.weeklyGoal.collectAsState(initial = 2)
    val onboardedAt by settings.onboardedAt.collectAsState(initial = 0L)
    val cycleStartedAtRaw by settings.cycleStartedAt.collectAsState(initial = 0L)
    val raceGoalRaw by settings.raceGoal.collectAsState(initial = "")
    val raceGoalSec by settings.raceGoalSec.collectAsState(initial = 0)
    val raceDateRaw by settings.raceDate.collectAsState(initial = 0L)

    if (runs.isEmpty()) {
        EmptyReportScreen(onReloadHealth)
        return
    }

    val scope = rememberCoroutineScope()
    val now = remember(runs) { Instant.now() }
    val level = RunnerLevel.fromStorage(levelRaw) ?: RunnerLevel.BEGINNER

    var tab by rememberSaveable { mutableStateOf(ReportTab.THIS_WEEK) }
    val segment: @Composable () -> Unit = { SegmentControl(tab) { tab = it } }

    when (tab) {
        ReportTab.THIS_WEEK -> {
            val battery = vitals?.let { BatteryEngine.compute(it, runs, now) }
            val report = ReportEngine(now, level).weeklyReport(runs)
            val vo2 = vo2MaxTrend(vo2Samples.map { DatedSample(it.first, it.second) }, now)
            val cross = CrossTrainingEngine.weekly(crossTrainings, runs, now)
            val form = FormTrend.compute(runs, now)
            val guide = trainingGuide(runs, level, raceGoalRaw, raceGoalSec, raceDateRaw,
                                      battery?.tone, now)
            val today = guide?.let {
                TrainingGuideEngine(now, level).todayWorkout(
                    runs = runs, guide = it, batteryTone = battery?.tone, weeklyGoal = weeklyGoal,
                )
            }
            // 걷뛰 주차는 처방 강도라서, 홈의 EPOCH 폴백과 달리 시작점을 모르면 안 낸다 (iOS 동일)
            val cycleStartedAt = when {
                cycleStartedAtRaw > 0 -> Instant.ofEpochSecond(cycleStartedAtRaw)
                onboardedAt > 0 -> Instant.ofEpochSecond(onboardedAt)
                else -> null
            }
            val walkRun = WalkRunEngine.plan(cycleStartedAt, weeklyGoal, runs, now)

            var refreshing by remember { mutableStateOf(false) }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try { onReloadHealth() } finally { refreshing = false }
                    }
                },
            ) {
                ReportHomeContent(
                    report = report, battery = battery, level = level,
                    vo2Max = vo2, cross = cross, form = form,
                    guide = guide, today = today, walkRun = walkRun,
                    isSample = false, segment = segment,
                    onOpenDetail = { onOpenDetail(report, guide) },
                )
            }
        }
        ReportTab.PROGRESS -> StatsScreen(runs, segment, onOpenSession, onReloadHealth)
    }
}

/// 목적어 조사 — iOS `RaceDistance.objectParticle` 대응 (화면 문장용, 네 종목 모두 "를")
internal val RaceDistance.objectParticle: String get() = "를"

/// 주간 처방 — HomeScreen과 같은 방식 (목표 레이스가 없으면 null)
private fun trainingGuide(
    runs: List<RunSummary>,
    level: RunnerLevel,
    raceGoalRaw: String,
    raceGoalSec: Int,
    raceDateRaw: Long,
    batteryTone: RRTone?,
    now: Instant,
): TrainingGuide? {
    val race = RaceDistance.fromStorage(raceGoalRaw) ?: return null
    return TrainingGuideEngine(now, level).guide(
        runs = runs, records = com.jkpark.runwrap.engine.PersonalRecords.compute(runs), race = race,
        goalSec = if (raceGoalSec > 0) raceGoalSec.toDouble() else null,
        raceDate = if (raceDateRaw > 0) Instant.ofEpochSecond(raceDateRaw) else null,
        batteryTone = batteryTone,
    )
}

// MARK: - 세그먼트

private enum class ReportTab(val label: String) {
    THIS_WEEK("이번 주"), PROGRESS("발전상")
}

/// iOS segmented Picker 대응 — surface2 트랙 위에 선택 탭만 surface 캡슐
@Composable
private fun SegmentControl(selected: ReportTab, onSelect: (ReportTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(RR.surface2)
            .padding(2.dp),
    ) {
        ReportTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) RR.surface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label, fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) RR.text else RR.text2,
                )
            }
        }
    }
}

// MARK: - 본문 (카드 묶음)

/// 카드 순서는 iOS와 동일: 배터리 → 걷뛰 → 거리 → 부하 → 심박 효율 → 크로스 →
/// 심폐 → 주법 → 훈련 가이드. 샘플 시트에서도 같은 본문을 재사용한다.
@Composable
private fun ReportHomeContent(
    report: WeeklyReport,
    battery: BatteryReport?,
    level: RunnerLevel,
    vo2Max: Vo2MaxTrend? = null,
    cross: CrossTrainingEngine.Summary? = null,
    form: FormTrend? = null,
    guide: TrainingGuide? = null,
    today: TodayWorkout? = null,
    walkRun: WalkRunEngine.Plan? = null,
    isSample: Boolean = false,
    segment: (@Composable () -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReportHeader(report)
        segment?.invoke()

        if (battery != null && ReportGate.shows(ReportCard.BATTERY, level)) {
            BatteryCardView(battery)
        } else if (!isSample) {
            BatteryHintCard()
        }
        if (walkRun != null && ReportGate.shows(ReportCard.WALK_RUN, level)) {
            WalkRunCardView(walkRun)
        }
        report.distance?.let {
            if (ReportGate.shows(ReportCard.DISTANCE, level)) DistanceCardView(it)
        }
        report.acwr?.let {
            if (ReportGate.shows(ReportCard.ACWR, level)) AcwrCardView(it)
        }
        report.efficiency?.let {
            if (ReportGate.shows(ReportCard.EFFICIENCY, level)) EfficiencyCardView(it)
        }
        cross?.let {
            if (ReportGate.shows(ReportCard.CROSS_TRAINING, level)) CrossTrainingCardView(it)
        }
        vo2Max?.let {
            if (ReportGate.shows(ReportCard.VO2_MAX, level)) Vo2MaxCardView(it, level)
        }
        form?.let {
            if (ReportGate.shows(ReportCard.FORM, level)) FormTrendCardView(it, level)
        }
        guide?.let {
            if (ReportGate.shows(ReportCard.TRAINING_GUIDE, level)) {
                TrainingGuideCardView(it, today, level)
            }
        }

        if (report.isEmpty) InsufficientCard()
        if (!isSample && !report.isEmpty && onOpenDetail != null) DetailButton(onOpenDetail)
    }
}

@Composable
private fun ReportHeader(report: WeeklyReport) {
    Column {
        Eyebrow(report.weekLabel)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("주간 리포트", style = RR.display(33.sp), color = RR.text)
            Spacer(Modifier.weight(1f))
            Text(
                report.dateRange,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, color = RR.text2,
                modifier = Modifier
                    .background(RR.surface, RoundedCornerShape(9.dp))
                    .border(1.dp, RR.line, RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

// MARK: - 카드 공통 부품

/// 아이콘 타일 + 제목(+ⓘ) + 코드 + 톤 배지. iOS는 SF Symbol을 톤 색으로 틴트하지만
/// Android엔 대응 아이콘이 없어 이모지를 soft 배경 타일에 얹는다 (홈 탭과 같은 트레이드오프)
@Composable
private fun CardHeader(
    icon: String,
    title: String,
    code: String,
    soft: Color,
    tone: RRTone? = null,
    info: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(soft, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = RR.text)
                if (info != null) CardInfoButton(title, info)
            }
            Text(
                code, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, color = RR.text3,
            )
        }
        tone?.let { ToneBadge(it) }
    }
}

/// ⓘ 탭 → 산식·기준 설명. iOS는 popover지만 Compose 표준에는 없어 다이얼로그로 낸다
@Composable
private fun CardInfoButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Icon(
        Icons.Filled.Info, contentDescription = "$title 설명", tint = RR.text3,
        modifier = Modifier.size(13.dp).clickable { open = true },
    )
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier
                    .widthIn(max = 292.dp)
                    .background(RR.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, RR.line, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = RR.text)
                Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = RR.text2)
            }
        }
    }
}

/// 라벨 위 · 값+단위 아래 — 카드 하단 3분할 지표 셀
@Composable
private fun RowScope.MetricCell(label: String, value: String, unit: String, color: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 11.sp, color = RR.text3)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = color,
                )) { append(value) }
                if (unit.isNotEmpty()) {
                    withStyle(SpanStyle(fontSize = 11.sp, color = RR.text3)) { append(" $unit") }
                }
            },
        )
    }
}

@Composable
private fun CardDisclaimer(text: String) {
    Text(
        text, Modifier.padding(top = 12.dp),
        fontSize = 11.sp, lineHeight = 16.sp, color = RR.text3,
    )
}

// MARK: - 체력 배터리

@Composable
private fun BatteryCardView(battery: BatteryReport) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "⚡", "체력 배터리", battery.statusLabel,
            soft = battery.tone.softColor, info = CardInfoText.BATTERY,
        )
        Text(
            battery.headline, Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                "${battery.level}", style = RR.numeral(42.sp), color = battery.tone.color,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "%", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RR.text3,
                modifier = Modifier.alignByBaseline().padding(start = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "남은 체력", fontSize = 11.sp, color = RR.text3,
                modifier = Modifier.alignByBaseline(),
            )
        }
        BatteryGauge(battery.level, Modifier.fillMaxWidth().padding(top = 10.dp))
        HorizontalDivider(Modifier.padding(top = 14.dp), color = RR.line)
        Column(
            Modifier.padding(top = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            battery.factors.forEach { factor ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        factor.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        color = RR.text, modifier = Modifier.weight(1f),
                    )
                    Text(
                        factor.detail, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, color = RR.text2,
                    )
                    Text(
                        pointsLabel(factor.points),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            factor.points > 0 -> RR.pos
                            factor.points < 0 -> RR.dang
                            else -> RR.text3
                        },
                        textAlign = TextAlign.End, modifier = Modifier.width(34.dp),
                    )
                }
            }
        }
        Text(
            "워치가 잰 지난밤 활력징후를 최근 4주의 내 기준선과 비교한 추정치예요",
            Modifier.padding(top = 12.dp), fontSize = 11.5.sp, color = RR.text3,
        )
        CardDisclaimer("건강 상태를 진단하거나 의학적 조언을 하지 않습니다. 통증이나 이상이 있다면 전문가와 상담하세요.")
    }
}

private fun pointsLabel(points: Int): String = when {
    points > 0 -> "+$points"
    points < 0 -> "−${-points}"
    else -> "±0"
}

/// 활력징후가 아직 없을 때 — 배터리 카드 자리에 준비 안내 (iOS batteryHintCard)
@Composable
private fun BatteryHintCard() {
    Row(
        Modifier.fillMaxWidth().rrCard().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔋", fontSize = 20.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "체력 배터리를 준비하고 있어요",
                fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = RR.text,
            )
            Text(
                "워치를 차고 자면 심박 변이·안정 심박·수면이 쌓여요. " +
                    "내 기준선(7일)이 모이면 남은 체력을 배터리로 보여드립니다.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = RR.text2,
            )
        }
    }
}

// MARK: - 걷뛰 프로그램

@Composable
private fun WalkRunCardView(plan: WalkRunEngine.Plan) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader("🚶", "걷뛰 프로그램", "START", soft = RR.brandSoft, info = CardInfoText.WALK_RUN)
        Text(
            plan.headline, Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Text(
            "${plan.weekBadge} · 한 번에 ${plan.totalMinutes.toInt()}분",
            Modifier.padding(top = 6.dp), fontSize = 13.sp, color = RR.text2,
        )
        HorizontalDivider(Modifier.padding(top = 12.dp), color = RR.line)
        // 걷:뛰 비율 막대 — 프로그램이 진행될수록 brand 구간이 길어진다
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (plan.walkMinutes > 0) {
                Box(
                    Modifier.weight(plan.walkMinutes.toFloat()).fillMaxHeight()
                        .background(RR.brandSoft, RoundedCornerShape(5.dp)),
                )
            }
            Box(
                Modifier.weight(plan.runMinutes.toFloat()).fillMaxHeight()
                    .background(RR.brand, RoundedCornerShape(5.dp)),
            )
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("걷기", Format.walkRunMinutes(plan.walkMinutes), "분", RR.text2)
            MetricCell("뛰기", Format.walkRunMinutes(plan.runMinutes), "분", RR.brand)
            MetricCell("세트", "${plan.sets}", "회", RR.text)
        }
        Text(plan.progressLine, Modifier.padding(top = 12.dp), fontSize = 12.5.sp, color = RR.text3)
    }
}

// MARK: - 주간 거리

@Composable
private fun DistanceCardView(card: WeeklyReport.DistanceCard) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "🏃", "주간 거리", "DISTANCE",
            soft = card.tone.softColor, tone = card.tone, info = CardInfoText.DISTANCE,
        )
        Text(
            buildAnnotatedString {
                append("최근 7일 거리가 그 전 7일보다 ")
                withStyle(SpanStyle(color = card.tone.color)) {
                    append("%.0f%%".format(Locale.ROOT, abs(card.changePct)))
                }
                append(if (card.changePct >= 0) " 늘었어요" else " 줄었어요")
            },
            Modifier.padding(top = 14.dp),
            fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Box(Modifier.padding(top = 14.dp)) {
            WeeklyBarsChart(
                weeks = card.weeks,
                currentColor = if (card.tone == RRTone.OVERLOAD) RR.dang else RR.brand,
                cap = card.capKm.takeIf { card.tone == RRTone.OVERLOAD },
                capLabel = "+10%% 상한 %.1f km".format(Locale.ROOT, card.capKm),
            )
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = RR.line)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("최근 7일", Format.km(card.recent7Km), "km", RR.text)
            MetricCell("이전 7일", Format.km(card.previous7Km), "km", RR.text2)
            if (card.overKm > 0) {
                MetricCell("초과분", "+" + Format.km(card.overKm), "km", RR.dang)
            } else {
                MetricCell("상한 여유", Format.km(-card.overKm), "km", RR.pos)
            }
        }
    }
}

// MARK: - 훈련 부하 (ACWR)

@Composable
private fun AcwrCardView(card: WeeklyReport.AcwrCard) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "⏱️", "훈련 부하", "ACWR",
            soft = card.tone.softColor, tone = card.tone, info = CardInfoText.ACWR,
        )
        Text(
            when (card.tone) {
                RRTone.OVERLOAD -> "훈련량이 회복 범위를 넘었어요"
                RRTone.CAUTION ->
                    if (card.ratio >= 1.3) "회복보다 훈련량이 앞서 있어요"
                    else "훈련량이 평소보다 크게 줄었어요"
                else -> "훈련과 회복이 균형을 이루고 있어요"
            },
            Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Box(Modifier.padding(top = 6.dp)) { AcwrGauge(card.ratio) }
        Text(
            "최근 7일 부하가 4주 평균의 %.2f배 · 1.5 초과는 위험".format(Locale.ROOT, card.ratio),
            Modifier.padding(top = 2.dp), fontSize = 13.sp, color = RR.text2,
        )
    }
}

// MARK: - 심박 효율

@Composable
private fun EfficiencyCardView(card: WeeklyReport.EfficiencyCard) {
    val deltaSec = abs(card.paceDeltaSec).roundToInt()
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "❤️", "심박 효율", "EFFICIENCY",
            soft = card.tone.softColor, tone = card.tone, info = CardInfoText.EFFICIENCY,
        )
        Text(
            buildAnnotatedString {
                if (deltaSec < 2) {
                    append("같은 심박에서 페이스를 유지하고 있어요")
                } else {
                    append("같은 심박에서 페이스가 ")
                    withStyle(SpanStyle(color = card.tone.color)) { append("${deltaSec}초") }
                    append(if (card.paceDeltaSec > 0) " 빨라졌어요" else " 느려졌어요")
                }
            },
            Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Text(
            "${card.referenceHR.toInt()} bpm 기준 · " +
                "${Format.pace(card.previousPaceSec)} → ${Format.pace(card.recentPaceSec)}",
            Modifier.padding(top = 7.dp), fontSize = 13.sp, color = RR.text2,
        )
        Box(Modifier.padding(top = 12.dp)) {
            TrendLineChart(
                points = card.points,
                tint = card.tone.color,
                endLabels = card.pointLabels.takeIf { it.size >= 2 }
                    ?.let { it.first() to it.last() },
                pointLabels = card.pointLabels,
                valueText = { "EF %.2f".format(Locale.ROOT, it) },
            )
        }
    }
}

// MARK: - 크로스 트레이닝

@Composable
private fun CrossTrainingCardView(cross: CrossTrainingEngine.Summary) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader("🚴", "크로스 트레이닝", "CROSS", soft = RR.brandSoft, info = CardInfoText.CROSS)
        Text(
            cross.headline, Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("이번 주", "${cross.sessionCount}", "회", RR.text)
            MetricCell("총 시간", crossTimeLabel(cross.totalMinutes), "", RR.text)
            cross.breakdown.firstOrNull()?.let {
                MetricCell("가장 많이", it.label, "", RR.text2)
            }
        }
        Text(cross.detail, Modifier.padding(top = 11.dp), fontSize = 12.sp, color = RR.text3)
    }
}

/// "45분" / "2시간" / "1시간 30분"
private fun crossTimeLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0 -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

// MARK: - 심폐 체력 (VO₂max)

/// iOS와 달리 HRR(심박 회복) 줄이 없다 — HC에 대응 레코드 타입이 없다 (계획서 §2.2)
@Composable
private fun Vo2MaxCardView(trend: Vo2MaxTrend, level: RunnerLevel) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader("🫁", "심폐 체력", "VO2MAX", soft = trend.tone.softColor,
                   info = CardInfoText.VO2_MAX)
        Text(
            vo2Headline(trend, level), Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Text(
            "이번 주 평균 %.1f ml/kg/min · 최근 12주 추이 · 워치 추정 기준"
                .format(Locale.ROOT, trend.current),
            Modifier.padding(top = 7.dp), fontSize = 13.sp, color = RR.text2,
        )
        Box(Modifier.padding(top = 12.dp)) {
            TrendLineChart(
                points = trend.points,
                tint = trend.tone.color,
                endLabels = trend.pointLabels.takeIf { it.size >= 2 }
                    ?.let { it.first() to it.last() },
                pointLabels = trend.pointLabels,
                valueText = { "%.1f".format(Locale.ROOT, it) },
            )
        }
    }
}

private fun vo2Headline(trend: Vo2MaxTrend, level: RunnerLevel): String {
    val delta = trend.delta
        ?: return if (level == RunnerLevel.BEGINNER) "심폐 체력 기록이 쌓이는 중이에요"
        else "VO₂max %.1f".format(Locale.ROOT, trend.current)
    if (level == RunnerLevel.BEGINNER) {
        return when (trend.tone) {
            RRTone.IMPROVING -> "심폐 체력이 좋아지고 있어요"
            RRTone.CAUTION -> "심폐 체력이 살짝 내려왔어요"
            else -> "심폐 체력이 잘 유지되고 있어요"
        }
    }
    return when (trend.tone) {
        RRTone.IMPROVING ->
            "VO₂max가 ${trend.spanWeeks}주 전보다 %.1f 올랐어요".format(Locale.ROOT, delta)
        RRTone.CAUTION ->
            "VO₂max가 ${trend.spanWeeks}주 전보다 %.1f 내려왔어요".format(Locale.ROOT, abs(delta))
        else -> "VO₂max %.1f — 안정적으로 유지 중이에요".format(Locale.ROOT, trend.current)
    }
}

// MARK: - 주법 리듬 (케이던스)

@Composable
private fun FormTrendCardView(form: FormTrend, level: RunnerLevel) {
    val delta = abs(form.deltaSpm).roundToInt()
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "👟", "주법 리듬", "CADENCE",
            soft = form.tone.softColor, tone = form.tone, info = CardInfoText.CADENCE,
        )
        Text(
            when (form.tone) {
                RRTone.IMPROVING ->
                    if (level == RunnerLevel.BEGINNER) "발걸음이 조금 더 잦고 가벼워졌어요"
                    else "케이던스가 2주 전보다 $delta spm 올랐어요"
                RRTone.CAUTION ->
                    if (level == RunnerLevel.BEGINNER) "발걸음 수가 줄었어요 — 보폭이 커졌을 수 있어요"
                    else "케이던스가 2주 전보다 $delta spm 내렸어요"
                else -> "케이던스가 평소 리듬을 유지하고 있어요"
            },
            Modifier.padding(top = 14.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Text(
            "케이던스 최근 2주 평균 %.0f spm · 이전 2주 %.0f spm"
                .format(Locale.ROOT, form.recentSpm, form.previousSpm),
            Modifier.padding(top = 7.dp), fontSize = 13.sp, color = RR.text2,
        )
    }
}

// MARK: - 훈련 가이드

@Composable
private fun TrainingGuideCardView(
    guide: TrainingGuide,
    today: TodayWorkout?,
    level: RunnerLevel,
) {
    Column(
        Modifier.fillMaxWidth().rrCard()
            .padding(top = 20.dp, start = 18.dp, end = 18.dp, bottom = 16.dp),
    ) {
        CardHeader(
            "🎯", "훈련 가이드", "COACH",
            soft = guide.prediction?.tone?.softColor ?: RR.brandSoft,
            info = CardInfoText.GUIDE,
        )
        Text(
            guideHeadline(guide, level), Modifier.padding(top = 13.dp),
            fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        guide.prediction?.let { prediction ->
            Text(
                predictionCaption(prediction),
                Modifier.padding(top = 7.dp), fontSize = 13.sp, color = RR.text2,
            )
        }
        phaseChipText(guide.prescription)?.let { chip ->
            Text(
                chip, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = RR.brand,
                modifier = Modifier
                    .padding(top = 9.dp)
                    .background(RR.brandSoft, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = RR.line)
        Row(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(
                "권장 주간",
                kmRangeText(guide.prescription.weeklyKmLow, guide.prescription.weeklyKmHigh),
                "km", RR.text,
            )
            MetricCell(
                "LSD 목표",
                if (guide.prescription.lsdKmHigh < 1) "—"
                else kmRangeText(guide.prescription.lsdKmLow, guide.prescription.lsdKmHigh),
                "km", RR.text,
            )
            MetricCell("퀄리티", "${guide.prescription.qualityCount}", "회/주", RR.text)
        }
        guide.zones?.let { zones ->
            Column(
                Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("훈련 페이스 — 최근 기록 기준", fontSize = 11.sp, color = RR.text3)
                PaceZoneRow(
                    "이지 · LSD",
                    "${Format.pace(zones.easySecPerKm.start)}~" +
                        Format.pace(zones.easySecPerKm.endInclusive),
                )
                PaceZoneRow("템포런", Format.pace(zones.tempoSecPerKm))
                PaceZoneRow("인터벌", Format.pace(zones.intervalSecPerKm))
                zones.goalSecPerKm?.let { PaceZoneRow("목표 레이스", Format.pace(it)) }
            }
        }
        today?.let { workout ->
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .background(RR.surface2, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("오늘의 훈련", fontSize = 11.sp, color = RR.text3)
                Text(
                    todayHeadline(workout),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RR.text,
                )
                todayReason(workout)?.let {
                    Text(it, fontSize = 11.5.sp, color = RR.text2)
                }
            }
        }
        if (guide.prescription.batteryLimited) {
            Text(
                "체력 배터리가 낮아 이번 주는 LSD 하한으로 줄이고 인터벌을 뺐어요",
                Modifier.padding(top = 10.dp),
                fontSize = 11.5.sp, lineHeight = 16.sp, color = RR.text3,
            )
        }
        CardDisclaimer("훈련 처방은 기록을 바탕으로 한 참고 정보이며 의학적 조언이 아닙니다.")
    }
}

@Composable
private fun PaceZoneRow(name: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 13.sp, color = RR.text2, modifier = Modifier.weight(1f))
        Text(
            "$value/km", fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = RR.text,
        )
    }
}

private fun guideHeadline(guide: TrainingGuide, level: RunnerLevel): String {
    // 8주 내 PR이 없어 예측은 못 해도 처방은 나간다
    val prediction = guide.prediction ?: return "이번 주 훈련 처방이 준비됐어요"
    val time = Format.duration(prediction.predictedSec)
    return if (level == RunnerLevel.BEGINNER) {
        "지금 흐름이면 ${prediction.race.label}${prediction.race.objectParticle} " +
            "${time}에 들어올 수 있어요"
    } else {
        "${prediction.race.label} 예상 완주 $time"
    }
}

private fun predictionCaption(prediction: TrainingGuide.Prediction): String {
    var caption = "${prediction.baseLabel} ${Format.duration(prediction.baseTimeSec)} " +
        "기록 기준 Riegel 예측"
    prediction.goalSec?.let { caption += " · 목표 ${Format.duration(it)}" }
    return caption
}

/// "D-38 · 강화기" — 대회 날짜를 설정했을 때만. 대회 당일은 "D-day · 대회 주간"
private fun phaseChipText(prescription: TrainingGuide.Prescription): String? {
    val phase = prescription.phase ?: return null
    val days = prescription.daysToRace ?: return null
    val dday = if (days == 0) "D-day" else "D-$days"
    return "$dday · ${phase.label}"
}

/// "이지런 6.0km @ 5′40″~6′10″/km" — 형태·거리·페이스를 한 줄로
private fun todayHeadline(workout: TodayWorkout): String {
    when (workout.kind) {
        TodayWorkout.Kind.Rest -> return "휴식 — 오늘은 쉬는 게 훈련이에요"
        TodayWorkout.Kind.DoneCount -> return "완료 — 이번 주 횟수를 다 채웠어요"
        TodayWorkout.Kind.DoneKm -> return "완료 — 이번 주 거리를 다 채웠어요"
        else -> Unit
    }
    var text = workout.kind.label
    if (workout.kind !is TodayWorkout.Kind.Interval) {
        workout.distanceKm?.let { text += " ${Format.km(it)}km" }
    }
    workout.paceSecPerKm?.let { range ->
        text += if (range.endInclusive - range.start < 1) {
            " @ ${Format.paceKm(range.start)}"
        } else {
            " @ ${Format.pace(range.start)}~${Format.pace(range.endInclusive)}/km"
        }
    }
    return text
}

private fun todayReason(workout: TodayWorkout): String? = when (workout.reason) {
    TodayWorkout.Reason.BATTERY -> "체력 배터리가 낮아 오늘은 가볍게 가요"
    TodayWorkout.Reason.HARD_RECENTLY -> "어제 강하게 뛰어서 오늘은 회복 러닝이에요"
    TodayWorkout.Reason.LSD_DUE -> "이번 주 롱런이 아직이에요 — 오늘이 적기예요"
    TodayWorkout.Reason.QUALITY_DUE -> "이번 주 퀄리티 세션 차례예요"
    TodayWorkout.Reason.FILL -> "남은 주간 거리를 나눠 뛰는 날이에요"
    TodayWorkout.Reason.NONE -> null
}

/// "24.5" / "24.5~27.0" — 폭이 0.05km 미만이면 한 값으로 줄인다
internal fun kmRangeText(low: Double, high: Double): String =
    if (high - low < 0.05) "%.1f".format(Locale.ROOT, low)
    else "%.1f~%.1f".format(Locale.ROOT, low, high)

// MARK: - 표본 부족 · 상세 버튼

@Composable
private fun InsufficientCard() {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text(
            "아직 해석할 만큼 기록이 쌓이지 않았어요",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = RR.text,
        )
        Text(
            "지표마다 필요한 최소 기록이 다릅니다. 주간 거리 비교는 2주, 부하 지표(ACWR)는 " +
                "4주치가 쌓이면 계산돼요. 틀린 해석을 보여드리지 않기 위해서예요.",
            Modifier.padding(top = 8.dp), fontSize = 13.5.sp, lineHeight = 20.sp, color = RR.text2,
        )
    }
}

@Composable
private fun DetailButton(onTap: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(RR.brand, RR.brand.copy(alpha = 0.82f))), shape,
            )
            .clickable(onClick = onTap)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("리포트 자세히 보기", fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
             color = Color.White)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = Color.White, modifier = Modifier.padding(start = 2.dp).size(16.dp),
        )
    }
}

// MARK: - 빈 상태 + 샘플 시트

/// 러닝 기록이 0건 — 안내 카드 + 스켈레톤 + 샘플 리포트 진입 (iOS EmptyReportScreen)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyReportScreen(onReloadHealth: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var showSample by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

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
            Column {
                Eyebrow("This week")
                Text("주간 리포트", Modifier.padding(top = 4.dp),
                     style = RR.display(33.sp), color = RR.text)
            }
            Column(
                Modifier.fillMaxWidth().rrCard()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .background(RR.surface2, RoundedCornerShape(16.dp))
                        .border(1.dp, RR.line, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🏃", fontSize = 22.sp)
                }
                Text(
                    "아직 분석할 러닝이 없어요", Modifier.padding(top = 16.dp),
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RR.text,
                )
                Text(
                    "워치로 러닝을 한 번 기록하면 바로 첫 해석이 도착합니다. " +
                        "부하 지표(ACWR)는 4주치가 모인 뒤 계산돼요.",
                    Modifier.padding(top = 8.dp),
                    fontSize = 14.sp, lineHeight = 21.sp, color = RR.text2,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "러닝 기록은 헬스 커넥트에서 읽어옵니다. 기록이 있는데도 비어 있다면 " +
                        "삼성헬스의 헬스 커넥트 데이터 공유와 헬스 커넥트의 앱 권한에서 " +
                        "런미새 읽기 허용을 확인해 주세요.",
                    Modifier.padding(top = 10.dp),
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = RR.text3,
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier
                        .padding(top = 20.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(RR.brand, RoundedCornerShape(14.dp))
                        .clickable { showSample = true }
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                ) {
                    Text("샘플 리포트 둘러보기", fontSize = 14.5.sp,
                         fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            SkeletonCards()
        }
    }

    if (showSample) SampleReportSheet(onDismiss = { showSample = false })
}

/// 데이터가 쌓이면 채워질 자리 — 은은한 펄스로 "준비 중"을 암시한다
@Composable
private fun SkeletonCards() {
    val pulse by rememberInfiniteTransition(label = "skeleton")
        .animateFloat(
            initialValue = 0.35f, targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
    Column(
        Modifier.alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().rrCard().padding(16.dp).height(62.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(0.38f, 0.56f, 0.44f, 0.72f, 0.6f, 0.9f).forEach { fraction ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .background(RR.barFill, RoundedCornerShape(4.dp)),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(96.dp).rrCard())
    }
}

/// 합성 데이터 샘플 — iOS SampleReportSheet. 레벨은 기본값(런린이) 그대로라
/// 배터리·거리 카드까지만 보인다 (iOS 동일)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleReportSheet(onDismiss: () -> Unit) {
    val now = remember { Instant.now() }
    val runs = remember(now) { DemoData.runs(now) }
    val report = remember(now) { ReportEngine(now).weeklyReport(runs) }
    val battery = remember(now) { BatteryEngine.compute(DemoData.vitals(now), runs, now) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RR.bg,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    "닫기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RR.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(RR.brandSoft)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✨", fontSize = 12.sp)
                Text(
                    "합성 데이터로 만든 샘플이에요. 내 기록이 쌓이면 이렇게 해석해 드립니다.",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = RR.brand,
                )
            }
            ReportHomeContent(report = report, battery = battery,
                              level = RunnerLevel.BEGINNER, isSample = true)
        }
    }
}

// MARK: - ⓘ 설명 문안 (iOS CardInfoText — 리포트 카드가 쓰는 항목만 이식)

private object CardInfoText {
    /// iOS 원문의 "심박 회복"을 "피부 온도"로 바꿨다 — Android 배터리 재료가 다르다 (계획서 §2.2)
    const val BATTERY = "밤사이 활력징후(심박 변이·안정 심박·피부 온도·수면)와 훈련 부하를 합쳐 " +
        "오늘 쓸 수 있는 체력을 0~100으로 추정해요. 75 이상 충전 충분, 50~74 양호, 25~49 주의, " +
        "그 밑은 방전 임박 — 낮은 날은 훈련보다 충전이 먼저예요."
    const val DISTANCE = "최근 7일 거리를 그 전 7일과 비교해요. 한 주 증가 폭은 10% 이내가 " +
        "안전하다는 경험칙(10% 룰)이 기준 — 그보다 빠르게 늘리면 몸이 적응할 시간이 부족해 " +
        "부상 위험이 커져요."
    const val ACWR = "최근 7일 부하 ÷ 최근 4주 주평균이에요. 지금 훈련량이 몸에 익숙한 양의 " +
        "몇 배인지 보는 지표로, 0.8~1.3이 적정 구간이에요. 1.3을 넘으면 몸보다 훈련이 앞선 상태, " +
        "1.5 초과는 부상 위험 구간이에요."
    const val EFFICIENCY = "같은 심박으로 얼마나 빨리 달리는지 — 속도를 심박으로 나눈 값이에요. " +
        "최근 2주를 그 전 2주와 비교해요. 절대값보다 방향이 중요해서, 오르고 있으면 같은 힘으로 " +
        "더 멀리 가는 몸이 되고 있다는 뜻이에요."
    /// iOS 원문 끝의 HRR(심박 회복) 문장은 뺐다 — Android 카드에 HRR 줄이 없다
    const val VO2_MAX = "운동 중 몸이 쓸 수 있는 산소의 최대치(mL/kg·분)로, 워치가 야외 러닝에서 " +
        "추정해요. 지구력의 대표 지표라 높을수록 좋지만 나이·성별에 따라 기준이 달라서, " +
        "절대값보다 추세가 오르는지를 봐요."
    const val CROSS = "최근 7일의 러닝 외 운동(자전거·근력 등)을 모아 보여드려요. 러닝 거리 " +
        "부하(ACWR)에는 넣지 않는 보조 정보지만, 몸의 피로는 같이 쌓이니 회복을 챙길 때는 " +
        "함께 계산해 주세요."
    const val CADENCE = "1분에 발이 땅에 닿는 횟수(spm)예요. 최근 2주를 그 전 2주와 비교해요. " +
        "보통 170~180 언저리가 접지 충격이 적고 효율적이라고 알려져 있지만 키·보폭에 따라 " +
        "달라서, 조금씩 오르는 추세면 충분해요."
    const val GUIDE = "최근 기록으로 목표 레이스 완주 기록을 예측(Riegel 공식)하고, 같은 기록에서 " +
        "현재 기력(VDOT, Daniels 공식)을 역산해 훈련 페이스와 이번 주 구성을 처방해요. " +
        "페이스는 목표가 아니라 현재 실력 기준이에요 — 목표가 더 빨라도 훈련 페이스를 올리지 " +
        "않아야 다치지 않아요. 대회 날짜를 설정하면 남은 기간에 맞춰 기초→강화→피크→테이퍼 " +
        "단계로 처방이 바뀝니다."
    const val WALK_RUN = "걷기와 뛰기를 번갈아 하며 8주에 걸쳐 뛰는 시간을 늘려가는 입문 " +
        "프로그램이에요. 총 25분은 그대로 두고 걷는 시간을 뛰는 시간으로 바꿔가요. 이번 주에 " +
        "몇 번 뛰었는지가 아니라 시작한 지 몇 주가 지났는지로 정해지니, 한 주 쉬어도 처방이 " +
        "뒤로 밀리지 않아요."
}
