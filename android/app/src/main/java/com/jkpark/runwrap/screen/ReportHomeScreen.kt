package com.jkpark.runwrap.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.BatteryEngine
import com.jkpark.runwrap.engine.BatteryReport
import com.jkpark.runwrap.engine.DemoData
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.FormTrend
import com.jkpark.runwrap.engine.GrowthKey
import com.jkpark.runwrap.engine.HrrTrend
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RaceOutlookEngine
import com.jkpark.runwrap.engine.RaceRecord
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.ReportCard
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.ReportGate
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.StreakCard
import com.jkpark.runwrap.engine.StreakEngine
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.TrainingLoad
import com.jkpark.runwrap.engine.TrainingLoadEngine
import com.jkpark.runwrap.engine.TrainingPlan
import com.jkpark.runwrap.engine.TrainingPlanEngine
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.engine.Vo2MaxTrend
import com.jkpark.runwrap.engine.WalkRunEngine
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.ZoneDistribution
import com.jkpark.runwrap.engine.ZoneDistributionEngine
import com.jkpark.runwrap.engine.ZoneHistogram
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.hrrTrend
import com.jkpark.runwrap.engine.instantSince1970
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.engine.vo2MaxTrend
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.ui.AcwrGauge
import com.jkpark.runwrap.ui.BatteryGauge
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RRSegmented
import com.jkpark.runwrap.ui.ToneBadge
import com.jkpark.runwrap.ui.TrendLineChart
import com.jkpark.runwrap.ui.WeeklyBarsChart
import com.jkpark.runwrap.ui.ZoneBarView
import com.jkpark.runwrap.ui.ZoneStackedBarsChart
import com.jkpark.runwrap.ui.color
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import com.jkpark.runwrap.ui.softColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/// 런미새 리포트 탭 — 상단 세그먼트로 [내 상태 | 이번달 | 나의 성장기]를 가른다 (이슈 #21).
///
/// v0.7에서 통계 탭이 이 화면의 세그먼트로 흡수됐고, #21에서 월간(이번달)과
/// 장기 추이(나의 성장기)로 다시 나뉘었다. 다이어트/훈련 목적별 배치 분기는 없다 —
/// 목적은 문장의 강조점만 바꾸고, 어떤 카드를 보여줄지는 레벨 게이트(`ReportGate`, §4)가 정한다.
/// (Android: 세그먼트 Picker → RRSegmented. 이번달·성장기 화면은 각자 스크롤 상태와 상태바 스크림을 갖는다)
@Composable
fun ReportHomeScreen(
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
    onOpenRecap: (RecapPeriod) -> Unit,
    onOpenTrainingPlan: (TrainingPlan) -> Unit,
) {
    val health = LocalAppContainer.current.health
    val state by health.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val runs = (state as? HealthStore.State.Loaded)?.runs

    /// [내 상태 | 이번달 | 나의 성장기] 세그먼트 — 세 화면이 같은 컨트롤을 공유한다
    val segment: @Composable () -> Unit = {
        RRSegmented(listOf("내 상태", "이번달", "나의 성장기"), tab, { tab = it })
    }

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        when {
            runs == null -> Unit
            runs.isEmpty() -> EmptyReportScreen()
            // 이번달 — 옛 통계 탭의 월간 본문 (§6 "리포트 탭 상단 세그먼트로 흡수")
            tab == 1 -> StatsScreen(segment, onOpenSession, onOpenRecap)
            // 나의 성장기 — 장기 추이 차트 3종 + PB 목록 (이슈 #21)
            tab == 2 -> GrowthScreen(segment, onOpenSession)
            else -> MyStateTab(runs, segment, onOpenTrainingPlan)
        }
    }
}

/// 내 상태 세그먼트 — 설정·스토어 값을 모아 ViewModel에 넘기고 결과만 그린다
@Composable
private fun MyStateTab(
    runs: List<RunSummary>,
    segment: @Composable () -> Unit,
    onOpenTrainingPlan: (TrainingPlan) -> Unit,
) {
    val container = LocalAppContainer.current
    val health = container.health
    val settings = container.settings
    val vitals by health.vitals.collectAsStateWithLifecycle()
    val vo2Max by health.vo2Max.collectAsStateWithLifecycle()
    val hrr by health.hrrTrend.collectAsStateWithLifecycle()
    val hrMaxEstimate by health.hrMaxEstimate.collectAsStateWithLifecycle()
    val restingHR by health.restingHRBpm.collectAsStateWithLifecycle()
    val histograms by health.zoneHistograms.collectAsStateWithLifecycle()
    /// 직접 입력한 대회 기록 (이슈 #35) — 대회 예측의 표본으로 경쟁한다
    val raceRecords by container.raceRecords.records.collectAsStateWithLifecycle()
    val levelRaw by settings.rememberSetting(ProfileKey.levelV2, RunnerLevel.beginner.rawValue)
    val raceGoalRaw by settings.rememberSetting(ProfileKey.raceGoal, "")
    val raceGoalSec by settings.rememberSetting(ProfileKey.raceGoalSec, 0)
    val raceDateRaw by settings.rememberSetting(ProfileKey.raceDate, 0.0)
    val weeklyGoal by settings.rememberSetting(ProfileKey.weeklyGoal, 2)
    val cycleStartedAtRaw by settings.rememberSetting(GrowthKey.cycleStartedAt, 0.0)
    val onboardedAtRaw by settings.rememberSetting(ProfileKey.onboardedAt, 0.0)
    // 심박 기준 (이슈 #56) — 0/빈 문자열이면 미설정 → 추정·건강 앱 값. 해석은 엔진 한 곳
    val hrMaxManual by settings.rememberSetting(ProfileKey.hrMaxManual, 0)
    val restingHRManual by settings.rememberSetting(ProfileKey.restingHRManual, 0)
    val hrZoneMethodRaw by settings.rememberSetting(ProfileKey.hrZoneMethod, "")

    val model = containerViewModel { ReportHomeViewModel() }
    val input = ReportHomeInput(
        runs, vitals, vo2Max, hrr, hrMaxEstimate, restingHR, histograms, raceRecords,
        levelRaw, raceGoalRaw, raceGoalSec, raceDateRaw, weeklyGoal, cycleStartedAtRaw, onboardedAtRaw,
        hrMaxManual, restingHRManual, hrZoneMethodRaw,
    )
    LaunchedEffect(input) { model.update(input) }
    val data by model.result.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    /// 상단 스크림 표시 — 본문이 상태바 밑으로 밀려 올라갔을 때만 (이슈 #211)
    val scrolled = scroll.rrTracksScroll()
    data?.let {
        ReportRefreshBox(Modifier.rrStatusBarScrim(visible = scrolled)) {
            ReportHomeContent(it, scroll, Modifier.statusBarsPadding(), segment = segment,
                              onOpenTrainingPlan = onOpenTrainingPlan)
        }
    }
}

// MARK: - 계산 (ViewModel)

/// 리포트 탭 세그먼트 화면들이 같이 쓰는 엔진 계산 ViewModel — 입력이 바뀌면 이전 계산을 취소하고
/// `Dispatchers.Default`에서 다시 계산한다. 화면마다 하위 클래스를 따로 둔다(viewModel 키가 클래스 이름이다)
internal abstract class ReportTabViewModel<I, O> : ViewModel() {
    private val _result = MutableStateFlow<O?>(null)
    val result: StateFlow<O?> = _result.asStateFlow()
    private var job: Job? = null

    protected abstract fun compute(input: I): O

    fun update(input: I) {
        job?.cancel()
        job = viewModelScope.launch { _result.value = withContext(Dispatchers.Default) { compute(input) } }
    }
}

private data class ReportHomeInput(
    val runs: List<RunSummary>,
    val vitals: VitalsSnapshot?,
    val vo2Max: List<Pair<Instant, Double>>,
    val hrr: List<Pair<Instant, Double>>,
    val hrMaxEstimate: TrainingGuideEngine.HrMaxEstimate,
    val restingHR: Double?,
    val histograms: Map<String, ZoneHistogram>,
    val raceRecords: List<RaceRecord>,
    val levelRaw: String,
    val raceGoalRaw: String,
    val raceGoalSec: Int,
    val raceDateRaw: Double,
    val weeklyGoal: Int,
    val cycleStartedAtRaw: Double,
    val onboardedAtRaw: Double,
    val hrMaxManual: Int,
    val restingHRManual: Int,
    val hrZoneMethodRaw: String,
)

/// 리포트 본문 재료 — iOS `ReportHomeContent`의 입력과 같다. 샘플 시트는 report·battery만 채운다
private class ReportHomeData(
    val report: WeeklyReport,
    /// 체력 배터리 — 활력징후 기준선이 부족하면 nil (안내 카드로 대체)
    val battery: BatteryReport? = null,
    /// 레벨 — 카드 포함 여부(ReportGate)와 문장 난이도를 정한다. 집계는 레벨과 무관하게 동일
    val level: RunnerLevel = RunnerLevel.beginner,
    /// 심폐 체력(VO₂max) 추이 — 목적 무관 노출 (표본 부족이면 엔진이 nil을 준다)
    val vo2Max: Vo2MaxTrend? = null,
    /// 심박 회복(HRR) 추이 — 심폐 체력 카드의 보조 라인 (제안 문서 B1, 표본 부족이면 nil)
    val hrr: HrrTrend? = null,
    /// 주간 케이던스 추이 — 최근 28일 케이던스 표본이 부족하면 엔진이 nil을 준다 (계획서 M4)
    val form: FormTrend? = null,
    /// 최근 28일 심박존 분포·80/20 강도 배분 — 심박 기록 세션 8회 미만이면 엔진이 nil을 준다 (이슈 #165)
    val zoneDistribution: ZoneDistribution? = null,
    /// TRIMP 기반 체력·피로·폼 — 심박 세션 이력 42일 미만이거나 8회 미만이면 엔진이 nil (이슈 #177)
    val trainingLoad: TrainingLoad? = null,
    /// 걷뛰 처방 — 런린이 전용 (§4). 사이클 시작 시각이 없으면 엔진이 nil을 준다
    val walkRun: WalkRunEngine.Plan? = null,
    /// 대회 목표 상태 — 배터리 카드 아래 독립 카드 재료 (이슈 #21·#119). 샘플 시트에서는 nil
    val raceStatus: RaceOutlookEngine.Status? = null,
    /// 주차별 훈련 계획 — race 카드의 계획 화면 링크 재료 (이슈 #189).
    /// 표본 부족·대회 24주 초과면 엔진이 nil을 준다. 샘플 시트에서는 nil
    val trainingPlan: TrainingPlan? = null,
)

private class ReportHomeViewModel : ReportTabViewModel<ReportHomeInput, ReportHomeData>() {
    override fun compute(input: ReportHomeInput): ReportHomeData = with(input) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val level = RunnerLevel.fromRawValue(levelRaw) ?: RunnerLevel.beginner
        /// 존·노력도·세션 상세가 공유하는 심박 기준 — 수동 > 추정 우선순위는 엔진이 정한다 (이슈 #56)
        val heartRate = TrainingGuideEngine.heartRateProfile(
            estimate = hrMaxEstimate, manualHrMax = hrMaxManual, manualRestingHR = restingHRManual,
            measuredRestingHR = restingHR, zoneMethodRaw = hrZoneMethodRaw,
        )
        val race = RaceDistance.fromRawValue(raceGoalRaw)
        val raceDate = if (raceDateRaw > 0) instantSince1970(raceDateRaw) else null
        /// 걷뛰 사이클 시작 시각 — 없으면 nil을 줘서 카드를 아예 내지 않는다.
        /// 홈(성장)은 같은 상황에서 `.distantPast`로 떨어뜨리지만 여기서는 그러면 안 된다 —
        /// 성장은 "기록 전체를 세는" 쪽이 유리한 반면, 걷뛰는 경과 주차가 곧 처방 강도라
        /// 먼 과거를 넣으면 8주차(뛰기 5분)가 나와 입문자에게 과한 처방이 된다.
        /// 표본이 아니라 기준점이 없는 경우지만, 판단 근거가 없으면 내지 않는 원칙은 같다
        val cycleStartedAt = when {
            cycleStartedAtRaw > 0 -> instantSince1970(cycleStartedAtRaw)
            onboardedAtRaw > 0 -> instantSince1970(onboardedAtRaw)
            else -> null
        }
        ReportHomeData(
            report = ReportEngine(now, level).weeklyReport(runs, zone),
            battery = vitals?.let { BatteryEngine.compute(it, runs, now, zone) },
            level = level,
            vo2Max = ReportEngine.vo2MaxTrend(vo2Max, now, zone),
            hrr = ReportEngine.hrrTrend(hrr, now, zone),
            form = FormTrend.compute(runs, now),
            zoneDistribution = ZoneDistributionEngine.compute(histograms, runs, heartRate, now, zone),
            trainingLoad = TrainingLoadEngine.compute(runs, heartRate, now, zone),
            walkRun = WalkRunEngine.plan(cycleStartedAt, weeklyGoal, runs, now, zone),
            raceStatus = RaceOutlookEngine.status(
                race = race, goalSec = raceGoalSec.toDouble(), raceDate = raceDate,
                runs = runs, now = now, zone = zone,
                // 폴백 190은 노력도 근거가 아니다 — nil로 넘겨 Riegel 유지
                hrMaxBpm = heartRate.reliableHrMax,
                vo2MaxSamples = vo2Max.map { TrainingGuideEngine.Vo2MaxSample(it.first, it.second) },
                raceRecords = raceRecords,
            ),
            /// 주차별 훈련 계획 (이슈 #189) — 목표 종목·대회 날짜가 모두 있을 때만 계산한다.
            /// 표본·지평 가드는 엔진이 nil로 처리한다
            trainingPlan = if (race != null && raceDate != null) {
                TrainingPlanEngine.plan(runs, race, level, raceDate, now, zone)
            } else null,
        )
    }
}

// MARK: - 공용 (이번달·성장기도 쓴다)

/// iOS `.refreshable { await health.load() }` 대응 — 당겨서 새로고침.
/// 화면을 떠나도 불러오기가 중간에 끊기지 않게 앱 범위 스코프에서 돈다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportRefreshBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val container = LocalAppContainer.current
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            container.scope.launch {
                container.health.load()
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = RR.surface, color = RR.brand,
            )
        },
        content = content,
    )
}

/// iOS `.font(.system(size:weight:)).lineSpacing(n)` — lineSpacing은 줄 높이에 더한다
internal fun reportText(size: Float, weight: FontWeight = FontWeight.Normal, lineSpacing: Float = 0f): TextStyle =
    TextStyle(
        fontSize = size.sp, fontWeight = weight,
        lineHeight = if (lineSpacing > 0f) (size * 1.2f + lineSpacing).sp else TextUnit.Unspecified,
    )

@Composable
internal fun ReportIcon(name: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Icon(RRIcons.named(name), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/// 화면 상단 아이브로 + "런미새 리포트" 제목
@Composable
internal fun ReportTitle(eyebrow: String) {
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Eyebrow(eyebrow)
        Text("런미새 리포트", style = RR.display(33.sp), color = RR.text)
    }
}

/// 리포트 탭 세그먼트 화면의 스크롤 본문 — 좌우 18 · 위 8 · 아래 26, 카드 간격 12.
/// 상태바 밑까지 깔리고 본문만 상태바 아래에서 시작한다(스크림은 호출부)
@Composable
internal fun ReportScrollColumn(
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    bottom: Dp = 26.dp,
    spacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .then(modifier)
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

// MARK: - 리포트 본문

/// 카드 노출 = 미노출 가드(엔진 nil) AND 레벨 게이트.
/// 순서가 중요하다 — `if let`으로 표본을 먼저 확인하고 게이트를 뒤에 건다 (ReportGate 주석)
/// - isSample: 샘플 리포트 시트에서는 상세 이동 대신 배너를 단다
/// - segment: [내 상태 | 이번달 | 나의 성장기] 세그먼트 — 샘플 시트에서는 넘기지 않아 nil이다
@Composable
private fun ReportHomeContent(
    data: ReportHomeData,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    isSample: Boolean = false,
    segment: (@Composable () -> Unit)? = null,
    onOpenTrainingPlan: (TrainingPlan) -> Unit = {},
) {
    val report = data.report
    val level = data.level
    ReportScrollColumn(scroll, modifier) {
        // 날짜 배지는 #21에서 삭제 — 기간은 아이브로에 함께 적는다
        ReportTitle("최근 7일 · ${report.dateRange}")

        if (segment != null) Box(Modifier.padding(bottom = 2.dp)) { segment() }

        val battery = data.battery
        if (battery != null && ReportGate.shows(ReportCard.battery, level)) {
            BatteryCard(battery, level)
        } else if (!isSample) {
            BatteryHintCard()
        }

        // 연속 달린 주 — 레벨 무관 소형 카드, 2주 미만이면 엔진이 nil (이슈 #184)
        StreakEngine.card(report.streakWeeks, report.ranThisWeek)?.let { StreakCardView(it) }

        // 배터리와 따로 그린다 — 배터리가 nil이어도 D-day·예상 기록은 보여야 한다 (이슈 #119)
        data.raceStatus?.let { RaceOutlookCard(it, data.trainingPlan, isSample, onOpenTrainingPlan) }

        // 걷뛰는 런린이에게서 걷어낸 지표들(ACWR·EF·VO₂max·주법)의 자리를 대신 채운다.
        // 그래서 위쪽 — 배터리 바로 다음 — 에 둔다 (§4 "더하는 차별화")
        val walkRun = data.walkRun
        if (walkRun != null && ReportGate.shows(ReportCard.walkRun, level)) WalkRunCard(walkRun)

        // 차트(이력)는 증가율 가드와 무관하게 그린다 — 비율·톤·상한만 distance가 있을 때 (이슈 #91)
        if (report.weeks.isNotEmpty() && ReportGate.shows(ReportCard.distance, level)) {
            DistanceCardView(report.distance, report.weeks, level)
        }
        val acwr = report.acwr
        if (acwr != null && ReportGate.shows(ReportCard.acwr, level)) AcwrCardView(acwr)
        val trainingLoad = data.trainingLoad
        if (trainingLoad != null && ReportGate.shows(ReportCard.trainingLoad, level)) TrainingLoadCard(trainingLoad)
        val zoneDistribution = data.zoneDistribution
        if (zoneDistribution != null && ReportGate.shows(ReportCard.zoneBalance, level)) ZoneBalanceCard(zoneDistribution)
        val efficiency = report.efficiency
        if (efficiency != null && ReportGate.shows(ReportCard.efficiency, level)) EfficiencyCardView(efficiency)

        val vo2Max = data.vo2Max
        if (vo2Max != null && ReportGate.shows(ReportCard.vo2Max, level)) Vo2MaxCard(vo2Max, data.hrr, level)

        val form = data.form
        if (form != null && ReportGate.shows(ReportCard.form, level)) FormTrendCard(form, level)

        // 레벨 게이트까지 거친 판정 카드 기준 — 런린이가 숨겨진 ACWR·EF 때문에
        // 안내도 판정 카드도 없는 빈 화면이 되지 않게 한다 (이슈 #119)
        if (report.visibleCards(level).isEmpty()) InsufficientCard()
    }
}

/// 판정·지표 카드 공통 틀 — 패딩(위 20 · 좌우 18 · 아래 bottom) + rrCard
@Composable
private fun ReportCardBox(bottom: Dp = 18.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .rrCard()
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = bottom),
    ) { content() }
}

@Composable
private fun Headline(text: String, top: Dp = 13.dp) {
    Text(text, style = reportText(21f, FontWeight.Bold, 4f), color = RR.text, modifier = Modifier.padding(top = top))
}

@Composable
private fun Caption(text: String, top: Dp = 7.dp) {
    Text(text, style = reportText(13f), color = RR.text2, modifier = Modifier.padding(top = top))
}

@Composable
private fun CardDivider(top: Dp) {
    HorizontalDivider(Modifier.padding(top = top), thickness = Dp.Hairline, color = RR.line)
}

/// "N%" 큰 숫자 + 오른쪽 작은 설명 — 배터리·강도 배분 카드
@Composable
private fun BigPercentRow(value: String, color: Color, label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = mono(42.sp, FontWeight.Bold), color = color, modifier = Modifier.alignByBaseline())
        Text("%", style = reportText(16f, FontWeight.SemiBold), color = RR.text3, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        Text(label, style = reportText(11f), color = RR.text3, modifier = Modifier.alignByBaseline())
    }
}

// MARK: 심폐 체력 카드 (VO₂max)

/// 심폐 체력 추이 카드 — 목적과 무관한 기초 체력 지표라 모든 프로필에 노출한다
@Composable
private fun Vo2MaxCard(v: Vo2MaxTrend, hrr: HrrTrend?, level: RunnerLevel) {
    ReportCardBox {
        CardHeader("lungs.fill", "심폐 체력", "VO2MAX", v.tone.color, v.tone.softColor, info = CardInfoText.vo2Max)
        Headline(vo2MaxHeadline(v, level))
        Caption("이번 주 평균 ${fmt(v.current, 1)} ml/kg/min · 최근 12주 추이 · 워치 추정 기준")

        // 심박 회복(HRR) 보조 라인 — 러닝 직후 1분 심박 하락 폭, 클수록 회복이 빠르다 (제안 문서 B1)
        if (hrr != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ReportIcon("arrow.clockwise.heart", hrr.tone.color, 13.dp)
                Text(hrrLine(hrr), style = reportText(12f), color = RR.text2)
            }
        }

        TrendLineChart(
            points = v.points,
            modifier = Modifier.padding(top = 12.dp),
            tint = v.tone.color,
            endLabels = if (v.pointLabels.size >= 2) v.pointLabels.first() to v.pointLabels.last() else null,
            pointLabels = v.pointLabels,
            valueText = { fmt(it, 1) },
        )
    }
}

private fun vo2MaxHeadline(v: Vo2MaxTrend, level: RunnerLevel): String {
    val delta = v.delta ?: return if (level == RunnerLevel.beginner) "심폐 체력 기록이 쌓이는 중이에요"
    else "VO₂max ${fmt(v.current, 1)}"
    if (level == RunnerLevel.beginner) {
        // 문장 분기는 엔진이 정한 톤을 그대로 따른다 — 화면에서 임계값을 재판정하지 않는다
        return when (v.tone) {
            RRTone.improving -> "심폐 체력이 좋아지고 있어요"
            RRTone.caution -> "심폐 체력이 살짝 내려왔어요"
            else -> "심폐 체력이 잘 유지되고 있어요"
        }
    }
    return when (v.tone) {
        RRTone.improving -> "VO₂max가 ${v.spanWeeks}주 전보다 ${fmt(abs(delta), 1)} 올랐어요"
        RRTone.caution -> "VO₂max가 ${v.spanWeeks}주 전보다 ${fmt(abs(delta), 1)} 내려왔어요"
        else -> "VO₂max ${fmt(v.current, 1)} — 안정적으로 유지 중이에요"
    }
}

/// 심박 회복 한 줄 — 사실(수치) 먼저, 위트는 뒤 (제안 문서 B1)
private fun hrrLine(hrr: HrrTrend): String {
    val base = "심박 회복 ${hrr.current.swiftRoundedInt()} bpm"
    val delta = hrr.delta
    if (delta == null || hrr.spanWeeks <= 0) return "$base — 러닝 직후 1분에 이만큼 내려와요"
    return when (hrr.tone) {
        RRTone.improving -> "$base · ${hrr.spanWeeks}주 전보다 +${fmt(abs(delta), 0)} — 회복 엔진도 좋아지는 중"
        RRTone.caution -> "$base · ${hrr.spanWeeks}주 전보다 −${fmt(abs(delta), 0)} — 회복 쪽도 챙겨 주세요"
        else -> "$base · ${hrr.spanWeeks}주 전과 비슷하게 유지 중"
    }
}

/// 카드 공통 헤더 — 아이콘 타일 + 제목 + 보조 코드(지표 약어 등, 제목과 뜻이 겹치면 nil — 이슈 #213). 판정 카드는 톤 배지를 오른쪽에 단다.
/// 시안의 점 배지를 아이콘 타일로 확장해 카드마다 시각 정체성을 준다 (확장 요구, 2026-08-11)
/// info를 주면 제목 옆에 작은 ⓘ가 붙는다 — 누르면 지표 설명 팝오버 (확장 요구, 2026-08-12)
@Composable
private fun CardHeader(
    icon: String, title: String, code: String?,
    tint: Color, soft: Color, tone: RRTone? = null, info: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).background(soft, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            ReportIcon(icon, tint, 17.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = reportText(14.5f, FontWeight.Bold), color = RR.text)
                if (info != null) CardInfoButton(title, info)
            }
            if (code != null) {
                Text(code, style = mono(9.5.sp, FontWeight.SemiBold).copy(letterSpacing = 1.2.sp), color = RR.text3)
            }
        }
        if (tone != null) ToneBadge(tone)
    }
}

// MARK: 체력 배터리 카드

@Composable
private fun BatteryCard(battery: BatteryReport, level: RunnerLevel) {
    ReportCardBox(bottom = 16.dp) {
        CardHeader("bolt.heart.fill", "체력 배터리", battery.statusLabel,
                   battery.tone.color, battery.tone.softColor, info = CardInfoText.battery)
        Headline(battery.headline)
        BigPercentRow("${battery.level}", battery.tone.color, "남은 체력")
        BatteryGauge(battery.level, Modifier.padding(top = 6.dp))
        CardDivider(14.dp)

        Column(Modifier.padding(top = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            battery.factors.forEach { FactorRow(it, level) }
        }

        Text("갤럭시 워치가 잰 지난밤 활력징후를 최근 4주의 내 기준선과 비교한 추정치예요",
             style = reportText(11.5f, lineSpacing = 3f), color = RR.text3, modifier = Modifier.padding(top = 13.dp))

        Disclaimer("건강 상태를 진단하거나 의학적 조언을 하지 않습니다. 통증이나 이상이 있다면 전문가와 상담하세요.")
    }
}

@Composable
private fun FactorRow(factor: BatteryReport.Factor, level: RunnerLevel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { ReportIcon(factor.systemImage, RR.text3, 14.dp) }
        Text(factor.name, style = reportText(13.5f, FontWeight.SemiBold), color = RR.text)
        Spacer(Modifier.weight(1f))
        Text(factorDetail(factor, level), style = mono(12.sp), color = RR.text2)
        Text(
            when {
                factor.points > 0 -> "+${factor.points}"
                factor.points < 0 -> "−${-factor.points}"
                else -> "±0"
            },
            style = mono(12.5.sp, FontWeight.Bold).copy(textAlign = TextAlign.End),
            color = when {
                factor.points > 0 -> RR.pos
                factor.points < 0 -> RR.dang
                else -> RR.text3
            },
            modifier = Modifier.width(34.dp),
        )
    }
}

/// 팩터 수치가 묶인 카드가 이 레벨에서 수치를 숨기면 숫자 없는 문장으로 바꾼다 (이슈 #125).
/// 기여 점수는 배터리 합산에 이미 들어가 있으므로 점수 표기는 그대로 둔다.
private fun factorDetail(factor: BatteryReport.Factor, level: RunnerLevel): String {
    val gate = factor.gate
    if (gate == null || ReportGate.showsNumbers(gate, level)) return factor.detail
    return when (gate) {
        ReportCard.acwr -> "이번 주 훈련량이 몸보다 앞섰어요"
        else -> factor.detail
    }
}

/// 활력징후가 아직 부족할 때 — 무엇이 쌓이면 보이는지 알려준다
@Composable
private fun BatteryHintCard() {
    Row(Modifier.fillMaxWidth().rrCard().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportIcon("battery.50percent", RR.text3, 23.dp, Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("체력 배터리를 준비하고 있어요", style = reportText(14.5f, FontWeight.Bold), color = RR.text)
            Text("갤럭시 워치를 차고 자면 심박 변이·안정 심박·수면이 쌓여요. 내 기준선(7일)이 모이면 남은 체력을 배터리로 보여드립니다.",
                 style = reportText(12.5f, lineSpacing = 4f), color = RR.text2)
        }
    }
}

// MARK: 스트릭 소형 카드 — 이슈 #184

/// 문구·톤은 StreakEngine이 정한다 — 여기서는 그리기만
@Composable
private fun StreakCardView(streak: StreakCard) {
    Row(
        Modifier.fillMaxWidth().rrCard().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReportIcon("flame.fill", streak.tone.color, 23.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(streak.headline, style = reportText(15f, FontWeight.Bold), color = RR.text)
            Text(streak.caption, style = reportText(12.5f), color = RR.text2)
        }
        Text(
            "${streak.weeks}주", style = reportText(12f, FontWeight.ExtraBold), color = streak.tone.color,
            modifier = Modifier.background(streak.tone.softColor, CircleShape).padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

// MARK: 대회 목표 카드 (배터리 카드 아래 독립 카드, 이슈 #21·#119)

/// 설정 상태에 따라 D-day·예상 완주 기록을 보여준다.
/// 상태 판정은 RaceOutlookEngine이 한다 — 여기서는 switch로 그리기만.
/// 예전엔 배터리 카드 하단 섹션이라 배터리가 nil이면 함께 사라졌다 (이슈 #119).
/// 미설정이면 카드를 아예 그리지 않는다 — 설정 유도는 홈 브리핑이 맡는다.
@Composable
private fun RaceOutlookCard(
    status: RaceOutlookEngine.Status,
    trainingPlan: TrainingPlan?,
    isSample: Boolean,
    onOpenTrainingPlan: (TrainingPlan) -> Unit,
) {
    if (status is RaceOutlookEngine.Status.notConfigured) return
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Eyebrow("목표 대회")
        val planLink: @Composable (Int) -> Unit = { days ->
            TrainingPlanLink(days, trainingPlan, isSample, onOpenTrainingPlan)
        }
        when (status) {
            RaceOutlookEngine.Status.notConfigured -> Unit
            is RaceOutlookEngine.Status.raceFinished ->
                SmallNote("${status.race.label} 대회 날짜가 지났어요 — 설정에서 다음 대회 목표를 정해 주세요",
                          Modifier.padding(top = 12.dp))
            is RaceOutlookEngine.Status.awaitingRecords ->
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DDayChip(status.daysToRace, status.race)
                    SmallNote("최근 12주 안에 목표 종목을 예측할 만큼 긴 러닝이 있으면 예상 완주 기록도 보여드려요 — 설정에서 지난 대회 기록을 입력해도 돼요")
                    planLink(status.daysToRace)
                }
            is RaceOutlookEngine.Status.ready -> {
                val outlook = status.outlook
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DDayChip(outlook.daysToRace, outlook.race)
                    // 심박 재료가 있으면 구간(빠른 끝~느린 끝), 없으면 단일 값 + 페이스 (이슈 #34)
                    val fast = outlook.predictedFastSec
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = RR.text)) { append("예상 완주 ") }
                            withStyle(SpanStyle(color = outlook.tone.color)) {
                                append(if (fast != null) "${Format.duration(fast)}~${Format.duration(outlook.predictedSec)}"
                                       else Format.duration(outlook.predictedSec))
                            }
                            if (fast == null) {
                                withStyle(SpanStyle(color = RR.text2)) { append(" · " + Format.paceKm(outlook.predictedPaceSecPerKm)) }
                            }
                        },
                        style = reportText(16f, FontWeight.Bold),
                    )
                    SmallNote(outlookCaption(outlook))
                    planLink(outlook.daysToRace)
                }
            }
        }
    }
}

@Composable
private fun SmallNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = reportText(11.5f, lineSpacing = 3f), color = RR.text3, modifier = modifier)
}

/// 주차별 훈련 계획 화면 링크 (이슈 #189) — 계획이 nil이면 이유를 한 줄로 안내한다.
/// 이유 구분은 D-day만으로 한다: 24주보다 멀면 지평 가드, 아니면 표본 가드
@Composable
private fun TrainingPlanLink(
    daysToRace: Int, trainingPlan: TrainingPlan?, isSample: Boolean,
    onOpenTrainingPlan: (TrainingPlan) -> Unit,
) {
    if (isSample) return
    if (trainingPlan != null) {
        Row(
            Modifier
                .padding(top = 2.dp)
                .clickable(role = Role.Button) { onOpenTrainingPlan(trainingPlan) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("주차별 훈련 계획 보기", style = reportText(12.5f, FontWeight.SemiBold), color = RR.brand)
            ReportIcon("chevron.right", RR.brand, 11.dp)
        }
    } else {
        SmallNote(
            if (daysToRace / 7 > TrainingPlanEngine.maxHorizonWeeks) "대회 ${TrainingPlanEngine.maxHorizonWeeks}주 전부터 주차별 계획을 보여드려요"
            else "러닝 기록이 3주 이상 쌓이면 주차별 훈련 계획도 보여드려요",
        )
    }
}

/// "D-38 · 풀코스" — 대회 당일은 "D-day"
@Composable
private fun DDayChip(days: Int, race: RaceDistance) {
    Text(
        "${if (days == 0) "D-day" else "D-$days"} · ${race.label}",
        style = reportText(11.5f, FontWeight.SemiBold), color = RR.brand,
        modifier = Modifier.background(RR.brandSoft, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/// "목표 4:00:00 · 최근 4주 기록 기준 Riegel 예측 · 8월 평년 더위 보정 +12초/km".
/// 표본 창은 고정이 아니라 엔진이 고른 값이다 — 4주에 러닝이 없으면 12주로 넓어지고,
/// 그 사실을 문구에 그대로 드러낸다 (이슈 #24·#34).
/// 더위 보정은 대회 장소를 모르는 채 쓰는 서울 평년값 근사라 "평년"을 밝힌다.
/// 표본 세션의 더위를 제거했으면 그 사실도 밝힌다 (이슈 #33) — 보정이 겹칠수록
/// 근거를 숨기면 숫자에 대한 불신만 커진다
private fun outlookCaption(outlook: RaceOutlookEngine.Outlook): String {
    // 직접 입력한 대회 기록이 근거면 표본 창 대신 그 사실을 밝힌다 (이슈 #35)
    val basis = if (outlook.isRaceRecord) "입력한 대회" else TrainingGuideEngine.sampleWindowLabel(outlook.sampleWindowDays)
    var caption = "목표 ${Format.duration(outlook.goalSec)} · $basis 기록 기준 Riegel 예측"
    if (outlook.predictedFastSec != null) caption += " · 빠른 끝은 대회 노력도(EF) 환산"
    // 배율 → 기록 변화율(%): 배율 0.97 = 기록 3% 단축 = 체력 상승 (이슈 #34)
    val fitnessPct = (1 - outlook.fitnessRatio) * 100
    if (abs(fitnessPct) >= 0.5) caption += " · 심폐 추세 ${fmt(fitnessPct, 0, plus = true)}% 반영"
    if (outlook.sampleHeatDeltaSecPerKm > 0) {
        // 대회 기록은 세션 날씨 대신 기록 월 평년값으로 중립 환산한다 (이슈 #100)
        val sec = fmt(outlook.sampleHeatDeltaSecPerKm, 0)
        caption += if (outlook.isRaceRecord) " · 기록 월 평년 더위 −${sec}초/km 반영" else " · 훈련 더위 −${sec}초/km 반영"
    }
    if (outlook.heatDeltaSecPerKm > 0) {
        caption += " · ${outlook.raceMonth}월 평년 더위 보정 +${fmt(outlook.heatDeltaSecPerKm, 0)}초/km"
    }
    return caption
}

// MARK: 주간 거리 카드

/// `card`가 nil이면(이전 7일 3km 미만 — 증가율 가드) 차트만 그리고 증감 대신 안내 한 줄을 둔다.
/// 런린이는 km 수치(막대 값·상한 라벨·하단 지표)를 감추고 문장만 남긴다 (§4 "문장만", 이슈 #119)
@Composable
private fun DistanceCardView(card: WeeklyReport.DistanceCard?, weeks: List<WeeklyReport.WeekBar>, level: RunnerLevel) {
    val overloaded = card?.tone == RRTone.overload
    val cap = if (overloaded) card?.capKm else null
    val showsNumbers = ReportGate.showsNumbers(ReportCard.distance, level)
    ReportCardBox(bottom = 16.dp) {
        CardHeader("figure.run", "주간 거리", null,
                   card?.tone?.color ?: RR.brand, card?.tone?.softColor ?: RR.brandSoft,
                   tone = card?.tone, info = CardInfoText.distance)

        if (card != null) {
            Text(distanceHeadline(card), style = reportText(23f, FontWeight.Bold, 4f), modifier = Modifier.padding(top = 13.dp))
        } else {
            Text("비교할 이전 7일 기록이 모이면 증감을 알려드려요", style = reportText(14f), color = RR.text2,
                 modifier = Modifier.padding(top = 13.dp))
        }

        WeeklyBarsChart(
            weeks = weeks,
            modifier = Modifier.padding(top = 16.dp),
            currentColor = if (overloaded) RR.dang else RR.brand,
            cap = cap,
            capLabel = if (showsNumbers) cap?.let { "+10% 상한 ${fmt(it, 1)} km" } else null,
            showsValues = showsNumbers,
        )

        if (card != null && showsNumbers) {
            CardDivider(12.dp)
            Row(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("최근 7일", Format.km(card.recent7Km), "km", RR.text)
                Metric("이전 7일", Format.km(card.previous7Km), "km", RR.text2)
                if (card.overKm > 0) Metric("초과분", "+" + Format.km(card.overKm), "km", RR.dang)
                else Metric("상한 여유", Format.km(-card.overKm), "km", RR.pos)
            }
        }
    }
}

/// 판정은 롤링 7일 창(달력 주 아님)이라 "지난주" 표현을 쓰지 않는다 —
/// 차트(달력 주)와 기준이 달라 주 초반엔 방향이 반대로 보일 수 있기 때문.
/// 하단 지표 라벨("최근 7일"/"이전 7일")과 같은 말로 맞춘다.
@Composable
private fun distanceHeadline(card: WeeklyReport.DistanceCard): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = RR.text)) { append("최근 7일 거리가 그 전 7일보다 ") }
    withStyle(SpanStyle(color = card.tone.color)) { append(fmt(abs(card.changePct), 0) + "%") }
    withStyle(SpanStyle(color = RR.text)) { append(if (card.changePct >= 0) " 늘었어요" else " 줄었어요") }
}

@Composable
private fun RowScope.Metric(label: String, value: String, unit: String, color: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = reportText(11f), color = RR.text3)
        Text(
            buildAnnotatedString {
                withStyle(mono(17.sp, FontWeight.Bold).toSpanStyle().copy(color = color)) { append(value) }
                withStyle(SpanStyle(fontSize = 11.sp, color = RR.text3)) { append(" $unit") }
            },
        )
    }
}

// MARK: ACWR 카드

@Composable
private fun AcwrCardView(card: WeeklyReport.AcwrCard) {
    ReportCardBox {
        CardHeader("speedometer", "훈련 부하", "ACWR", card.tone.color, card.tone.softColor,
                   tone = card.tone, info = CardInfoText.acwr)
        Headline(
            when (card.tone) {
                RRTone.overload -> "훈련량이 회복 범위를 넘었어요"
                RRTone.caution -> if (card.ratio >= 1.3) "회복보다 훈련량이 앞서 있어요" else "훈련량이 평소보다 크게 줄었어요"
                else -> "훈련과 회복이 균형을 이루고 있어요"
            },
        )
        AcwrGauge(card.ratio, Modifier.padding(top = 6.dp))
        Caption("최근 7일 부하가 4주 평균의 ${fmt(card.ratio, 2)}배 · 1.5 초과는 위험", top = 2.dp)
    }
}

// MARK: 체력·피로·폼 카드 (TRIMP CTL·ATL·TSB) — 이슈 #177

@Composable
private fun TrainingLoadCard(load: TrainingLoad) {
    val zone = ZoneId.systemDefault()
    ReportCardBox {
        CardHeader("waveform.path.ecg", "체력·피로·폼", "TSB", load.tone.color, load.tone.softColor,
                   tone = load.tone, info = CardInfoText.trainingLoad)
        // 문장 분기는 엔진이 정한 폼 구간을 그대로 따른다 — 화면에서 TSB를 재판정하지 않는다
        Headline(
            when (load.band) {
                TrainingLoad.Band.overload -> "피로가 체력을 크게 앞섰어요 — 며칠 쉬어 가요"
                TrainingLoad.Band.productive -> "체력이 쌓이는 구간이에요 — 좋은 피로예요"
                TrainingLoad.Band.maintain -> "체력과 피로가 균형이에요"
                TrainingLoad.Band.fresh -> "몸이 가벼운 상태예요 — 대회를 뛰기 좋아요"
                TrainingLoad.Band.detraining -> "훈련이 뜸해 체력이 내려가고 있어요"
            },
        )

        // CTL 28일 추세 — 탭하면 그날 체력 콜아웃
        TrendLineChart(
            points = load.points.map { it.ctl },
            modifier = Modifier.padding(top = 12.dp),
            tint = load.tone.color,
            height = 84.dp,
            endLabels = "4주 전" to "오늘",
            // "8/13" — 추세 콜아웃 날짜
            pointLabels = load.points.map { p -> p.day.atZone(zone).let { "${it.monthValue}/${it.dayOfMonth}" } },
            valueText = { fmt(it, 0) },
        )

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadTile("체력 CTL", fmt(load.ctl, 0), RR.text)
            LoadTile("피로 ATL", fmt(load.atl, 0), RR.text)
            LoadTile("폼 TSB", fmt(load.tsb, 0, plus = true), load.tone.color)
        }

        Caption("최근 42일 심박 세션 ${load.sessionCount}회 · 심박 강도×시간(TRIMP)을 누적한 값이에요", top = 12.dp)
    }
}

@Composable
private fun RowScope.LoadTile(label: String, value: String, color: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = reportText(11f), color = RR.text3)
        Text(value, style = mono(20.sp, FontWeight.Bold), color = color)
    }
}

// MARK: 강도 배분 카드 (80/20) — 이슈 #165

@Composable
private fun ZoneBalanceCard(z: ZoneDistribution) {
    ReportCardBox {
        CardHeader("heart.text.square.fill", "강도 배분", "80/20", z.tone.color, z.tone.softColor,
                   tone = z.tone, info = CardInfoText.zoneBalance)
        // 문장 분기는 엔진이 정한 톤을 그대로 따른다 — 화면에서 임계값을 재판정하지 않는다
        Headline(
            when (z.tone) {
                RRTone.overload -> "쉬운 날이 없으십니다. 80%는 대화가 되는 속도로"
                RRTone.caution -> "이지런이 조금 빠르십니다 — 편한 날은 더 편하게"
                else -> "이지런을 이지하게, 잘 지키고 계십니다"
            },
        )
        BigPercentRow(String.format("%d", (z.easyShare * 100).swiftRoundedInt()), z.tone.color, "이지(Z1–Z2) 비율 · 목표 80%")
        ZoneStackedBarsChart(z.weeks, Modifier.padding(top = 12.dp))
        ZoneBarView(z.zoneShare, Modifier.padding(top = 14.dp))
        Text("최근 28일 ${z.sessionCount}회 러닝의 심박 시간 · 존 경계는 설정의 심박 기준",
             style = reportText(11.5f), color = RR.text3, modifier = Modifier.padding(top = 12.dp))
    }
}

// MARK: 심박 효율 카드

@Composable
private fun EfficiencyCardView(card: WeeklyReport.EfficiencyCard) {
    ReportCardBox {
        CardHeader("heart.fill", "심박 효율", null, card.tone.color, card.tone.softColor,
                   tone = card.tone, info = CardInfoText.efficiency)
        Text(efficiencyHeadline(card), style = reportText(21f, FontWeight.Bold, 4f), modifier = Modifier.padding(top = 13.dp))
        Caption("${card.referenceHR.swiftRoundedInt()} bpm 기준 · ${Format.pace(card.previousPaceSec)} → ${Format.pace(card.recentPaceSec)}")
        TrendLineChart(
            points = card.points,
            modifier = Modifier.padding(top = 12.dp),
            tint = card.tone.color,
            endLabels = if (card.pointLabels.size >= 2) card.pointLabels.first() to card.pointLabels.last() else null,
            pointLabels = card.pointLabels,
            valueText = { "EF " + fmt(it, 2) },
        )
    }
}

@Composable
private fun efficiencyHeadline(card: WeeklyReport.EfficiencyCard): AnnotatedString = buildAnnotatedString {
    val delta = abs(card.paceDeltaSec).swiftRoundedInt()
    if (delta < 2) {
        withStyle(SpanStyle(color = RR.text)) { append("같은 심박에서 페이스를 유지하고 있어요") }
        return@buildAnnotatedString
    }
    withStyle(SpanStyle(color = RR.text)) { append("같은 심박에서 페이스가 ") }
    withStyle(SpanStyle(color = card.tone.color)) { append("${delta}초") }
    withStyle(SpanStyle(color = RR.text)) { append(if (card.paceDeltaSec > 0) " 빨라졌어요" else " 느려졌어요") }
}

// MARK: 주법 추이 카드 (케이던스 2주 비교) — 기획서 §4.8, 계획서 M4

@Composable
private fun FormTrendCard(form: FormTrend, level: RunnerLevel) {
    ReportCardBox {
        CardHeader("shoeprints.fill", "주법 리듬", "케이던스", form.tone.color, form.tone.softColor,
                   tone = form.tone, info = CardInfoText.cadence)
        Headline(formHeadline(form, level))
        Caption("케이던스 최근 2주 평균 ${fmt(form.recentSpm, 0)} spm · 이전 2주 ${fmt(form.previousSpm, 0)} spm")
    }
}

private fun formHeadline(form: FormTrend, level: RunnerLevel): String {
    val delta = abs(form.deltaSpm).swiftRoundedInt()
    return when (form.tone) {
        RRTone.improving -> if (level == RunnerLevel.beginner) "발걸음이 조금 더 잦고 가벼워졌어요"
        else "케이던스가 2주 전보다 $delta spm 올랐어요"
        RRTone.caution -> if (level == RunnerLevel.beginner) "발걸음 수가 줄었어요 — 보폭이 커졌을 수 있어요"
        else "케이던스가 2주 전보다 $delta spm 내렸어요"
        else -> "케이던스가 평소 리듬을 유지하고 있어요"
    }
}

// MARK: 걷뛰 카드 — 기획서 §4

/// 걷뛰 카드 — 런린이가 오늘 그대로 따라 할 수 있는 한 세션 처방 (§4).
/// 수치를 감춘 다른 런린이 카드와 달리 여기서는 분·세트를 그대로 보여준다 —
/// 해석해야 하는 지표가 아니라 실행하는 지시라서 숫자가 곧 내용이다.
@Composable
private fun WalkRunCard(plan: WalkRunEngine.Plan) {
    ReportCardBox(bottom = 16.dp) {
        CardHeader("figure.walk", "걷뛰 프로그램", null, RR.brand, RR.brandSoft, info = CardInfoText.walkRun)
        Headline(plan.headline)
        Caption("${plan.weekBadge} · 한 번에 ${plan.totalMinutes.toInt()}분")
        CardDivider(14.dp)

        // 걷기·뛰기 비율 막대 — 주차가 오를수록 뛰기(브랜드색)가 걷기를 밀어낸다.
        // 8주차는 걷기가 0이라 막대가 통째로 뛰기가 된다
        WalkRunRatioBar(plan)

        Row(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("걷기", Format.walkRunMinutes(plan.walkMinutes), "분", RR.text2)
            Metric("뛰기", Format.walkRunMinutes(plan.runMinutes), "분", RR.brand)
            Metric("세트", "${plan.sets}", "회", RR.text)
        }

        Text(plan.progressLine, style = reportText(12.5f), color = RR.text3, modifier = Modifier.padding(top = 12.dp))
    }
}

/// 한 세트 안의 걷기:뛰기 시간 비율 막대
@Composable
private fun WalkRunRatioBar(plan: WalkRunEngine.Plan) {
    val total = plan.walkMinutes + plan.runMinutes
    val shape = RoundedCornerShape(5.dp)
    BoxWithConstraints(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .height(10.dp)
            .clearAndSetSemantics {
                contentDescription = "걷기 ${Format.walkRunMinutes(plan.walkMinutes)}분, 뛰기 ${Format.walkRunMinutes(plan.runMinutes)}분 비율"
            },
    ) {
        val walkWidth = (maxWidth - 3.dp) * (plan.walkMinutes / total).toFloat()
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // 걷기가 0인 8주차에는 막대를 그리지 않는다 (폭 0짜리 조각을 남기지 않으려고)
            if (plan.walkMinutes > 0) Box(Modifier.width(walkWidth.coerceAtLeast(0.dp)).fillMaxSize().background(RR.brandSoft, shape))
            Box(Modifier.weight(1f).fillMaxSize().background(RR.brand, shape))
        }
    }
}

/// 카드 하단 면책 문구 — 심사 지침 1.4.1은 건강 관련 해석에 의학적 조언이 아님을
/// 밝히라고 요구한다. 훈련 처방·체력 배터리처럼 행동을 유도하는 카드에 붙인다.
@Composable
private fun Disclaimer(text: String) {
    Text(text, style = reportText(11f, lineSpacing = 3f), color = RR.text3, modifier = Modifier.padding(top = 12.dp))
}

// MARK: 표본 부족 안내

@Composable
private fun InsufficientCard() {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("아직 해석할 만큼 기록이 쌓이지 않았어요", style = reportText(17f, FontWeight.Bold), color = RR.text)
        Text("지표마다 필요한 최소 기록이 다릅니다. 주간 거리 비교는 2주, 부하 지표(ACWR)는 4주치가 쌓이면 계산돼요. 틀린 해석을 보여드리지 않기 위해서예요.",
             style = reportText(13.5f, lineSpacing = 4f), color = RR.text2)
    }
}

// MARK: - 지표 설명 팝오버

/// 카드 제목 옆 ⓘ 버튼 — 이 지표가 무엇이고 어떤 값이 좋은지 짧게 설명한다.
/// 두세 문장으로 끝낸다.
/// (Android: popover 대신 작은 Dialog — 바깥을 누르거나 뒤로가기로 닫는다)
@Composable
private fun CardInfoButton(title: String, text: String) {
    var isPresented by remember { mutableStateOf(false) }
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button) { isPresented = true }
            .semantics { contentDescription = "$title 설명" },
        contentAlignment = Alignment.Center,
    ) {
        ReportIcon("info.circle", RR.text3, 14.dp)
    }
    if (isPresented) {
        Dialog(onDismissRequest = { isPresented = false }) {
            Column(
                Modifier
                    .width(292.dp)
                    .background(RR.surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(title, style = reportText(14.5f, FontWeight.Bold), color = RR.text)
                Text(text, style = reportText(13f, lineSpacing = 4f), color = RR.text2)
            }
        }
    }
}

/// 카드별 설명 문구 — "무엇을 재는지 → 어떤 값이 좋은지" 순서, 두세 문장 (기획서 §4.11 톤).
/// 산식 기준: 10% 룰·ACWR(Gabbett 2016)·EF·Riegel은 각 엔진 주석과 같은 출처를 따른다.
/// (Android: iOS에서도 화면에 쓰이지 않는 calories·weight·streak·guide 문구는 옮기지 않는다)
private object CardInfoText {
    const val battery = "밤사이 활력징후(심박 변이·안정 심박·심박 회복·수면)와 훈련 부하를 합쳐 오늘 쓸 수 있는 체력을 0~100으로 추정해요. 75 이상 충전 충분, 50~74 양호, 25~49 주의, 그 밑은 방전 임박 — 낮은 날은 훈련보다 충전이 먼저예요."
    const val distance = "최근 7일 거리를 그 전 7일과 비교해요. 한 주 증가 폭은 10% 이내가 안전하다는 경험칙(10% 룰)이 기준 — 그보다 빠르게 늘리면 몸이 적응할 시간이 부족해 부상 위험이 커져요."
    const val acwr = "최근 7일 부하 ÷ 최근 4주 주평균이에요. 지금 훈련량이 몸에 익숙한 양의 몇 배인지 보는 지표로, 0.8~1.3이 적정 구간이에요. 1.3을 넘으면 몸보다 훈련이 앞선 상태, 1.5 초과는 부상 위험 구간이에요."
    const val trainingLoad = "세션마다 심박 강도와 시간을 곱한 훈련 자극(TRIMP)을 매일 누적해요. 체력(CTL)은 42일, 피로(ATL)는 7일 가중 평균이고, 폼(TSB)은 체력 − 피로예요. −30 밑이면 과부하, −30~−10은 체력이 쌓이는 구간, +5~+25는 대회 뛰기 좋은 상태, +25 위면 훈련 부족이에요."
    const val zoneBalance = "최근 28일 러닝의 심박 시간을 존(Z1~Z5)별로 모았어요. 엘리트 지구력 선수는 훈련 시간의 약 80%를 대화가 되는 낮은 강도(Z1~Z2)에서 보낸다는 연구(Seiler, 2006)가 기준 — 80% 이상 유지, 70~80% 주의, 70% 밑은 쉬운 날까지 세게 달리는 상태예요. 존 경계는 설정의 심박 기준(최대 심박·Karvonen)을 따라요."
    const val efficiency = "같은 심박으로 얼마나 빨리 달리는지 — 속도를 심박으로 나눈 값이에요. 최근 2주를 그 전 2주와 비교해요. 절대값보다 방향이 중요해서, 오르고 있으면 같은 힘으로 더 멀리 가는 몸이 되고 있다는 뜻이에요."
    const val vo2Max = "운동 중 몸이 쓸 수 있는 산소의 최대치(mL/kg·분)로, 워치가 야외 러닝에서 추정해요. 지구력의 대표 지표라 높을수록 좋지만 나이·성별에 따라 기준이 달라서, 절대값보다 추세가 오르는지를 봐요. 함께 나오는 심박 회복은 러닝 직후 1분간 심박이 내려간 폭 — 클수록 회복 엔진이 좋은 거예요."
    const val cadence = "1분에 발이 땅에 닿는 횟수(spm)예요. 최근 2주를 그 전 2주와 비교해요. 보통 170~180 언저리가 접지 충격이 적고 효율적이라고 알려져 있지만 키·보폭에 따라 달라서, 조금씩 오르는 추세면 충분해요."
    const val walkRun = "걷기와 뛰기를 번갈아 하며 8주에 걸쳐 뛰는 시간을 늘려가는 입문 프로그램이에요. 총 25분은 그대로 두고 걷는 시간을 뛰는 시간으로 바꿔가요. 이번 주에 몇 번 뛰었는지가 아니라 시작한 지 몇 주가 지났는지로 정해지니, 한 주 쉬어도 처방이 뒤로 밀리지 않아요."
}

// MARK: - 빈 상태

@Composable
private fun EmptyReportScreen() {
    var showSample by remember { mutableStateOf(false) }
    ReportRefreshBox {
        ReportScrollColumn(rememberScrollState(), Modifier.statusBarsPadding(), bottom = 0.dp, spacing = 14.dp) {
            ReportTitle("최근 7일")

            Column(
                Modifier
                    .fillMaxWidth()
                    .rrCard()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .background(RR.surface2, RoundedCornerShape(16.dp))
                        .border(1.dp, RR.line, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { ReportIcon("figure.run", RR.text3, 25.dp) }
                Text("아직 분석할 러닝이 없어요", style = reportText(19f, FontWeight.Bold), color = RR.text,
                     modifier = Modifier.padding(top = 16.dp))
                Text("갤럭시 워치로 러닝을 한 번 기록하면 바로 첫 해석이 도착합니다. 부하 지표(ACWR)는 4주치가 모인 뒤 계산돼요.",
                     style = reportText(14f, lineSpacing = 4f).copy(textAlign = TextAlign.Center), color = RR.text2,
                     modifier = Modifier.padding(top = 8.dp))
                // 읽기 권한의 허용 여부는 앱이 조회할 수 없다(애플 정책) —
                // 기록이 있는데도 비어 있는 경우를 대비해 확인 경로를 함께 알려준다
                Text("러닝 기록은 헬스 커넥트에서 읽어옵니다. 기록이 있는데도 비어 있다면 헬스 커넥트 → 앱 권한 → 런미새에서 읽기 권한을 확인해 주세요.",
                     style = reportText(12.5f, lineSpacing = 4f).copy(textAlign = TextAlign.Center), color = RR.text3,
                     modifier = Modifier.padding(top = 10.dp))
                Text(
                    "샘플 리포트 둘러보기",
                    style = reportText(14.5f, FontWeight.Bold), color = RR.onBrand,
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .background(RR.brand, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { showSample = true }
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                )
            }

            SkeletonCards()
        }
    }
    if (showSample) SampleReportSheet { showSample = false }
}

@Composable
private fun SkeletonCards() {
    Column(Modifier.alpha(0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
            SkeletonBlock(Modifier.width(88.dp).height(11.dp))
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                SkeletonBlock(Modifier.widthIn(max = 240.dp).fillMaxWidth().height(19.dp), delayMillis = 200)
            }
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(62.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(0.38f, 0.56f, 0.44f, 0.72f, 0.6f, 0.9f).forEach { h ->
                    Box(Modifier.weight(1f).height(62.dp * h).background(RR.barFill, RoundedCornerShape(5.dp)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(96.dp).rrCard())
    }
}

/// 빈 상태의 스켈레톤 블록 — 은은한 펄스
/// (Android: 모션 줄이기(이슈 #212)는 시스템 "애니메이션 삭제"(animator duration scale 0)가 Compose 애니메이션에 그대로 적용된다)
@Composable
private fun SkeletonBlock(modifier: Modifier, delayMillis: Int = 0) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.9f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse, StartOffset(delayMillis)),
        label = "skeletonAlpha",
    )
    Box(modifier.alpha(pulse).background(RR.barFill, RoundedCornerShape(6.dp)))
}

/// 샘플 리포트 시트 — 합성 데이터로 만든 실제 리포트 + 안내 배너
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleReportSheet(onDismiss: () -> Unit) {
    val sample by produceState<ReportHomeData?>(null) {
        value = withContext(Dispatchers.Default) {
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val runs = DemoData.runs(now)
            ReportHomeData(
                report = ReportEngine(now).weeklyReport(runs, zone),
                battery = BatteryEngine.compute(DemoData.vitals(now), runs, now, zone),
            )
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RR.bg,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("닫기", style = reportText(16f), color = RR.brand) }
            }
            Row(
                Modifier.fillMaxWidth().background(RR.brandSoft).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReportIcon("sparkles", RR.brand, 14.dp)
                Text("합성 데이터로 만든 샘플이에요. 내 기록이 쌓이면 이렇게 해석해 드립니다.",
                     style = reportText(12.5f, FontWeight.Medium), color = RR.brand)
            }
            sample?.let { ReportHomeContent(it, rememberScrollState(), isSample = true) }
        }
    }
}
