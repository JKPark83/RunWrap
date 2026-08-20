package com.jkpark.runwrap.screen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.AirGrade
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.BatteryEngine
import com.jkpark.runwrap.engine.BatteryReport
import com.jkpark.runwrap.engine.GrowthEngine
import com.jkpark.runwrap.engine.GrowthState
import com.jkpark.runwrap.engine.LevelEngine
import com.jkpark.runwrap.engine.PersonalRecords
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.TodayVerdict
import com.jkpark.runwrap.engine.TodayVerdictEngine
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.store.AirQualityStore
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.store.WeatherStore
import com.jkpark.runwrap.ui.BirdView
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.charts.BatteryGauge
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.ToneBadge
import com.jkpark.runwrap.ui.theme.rrCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.launch

/// 홈 탭 — 새 성장 스테이지와 **오늘의 판단 카드** (기획서 v0.8 §6, 시안 1f/1g/1h).
/// iOS `HomeScreen.swift` 이식. Android M5 범위 차이:
/// - 도감·세러모니·'오늘' 시트는 미이식 — 도감 버튼을 빼고, 날씨 타일 탭은
///   권한 거부 상태(설정으로 유도)에만 반응한다
/// - 날씨 위치 권한은 iOS의 첫 사용 요청에 맞춰 첫 노출 때 시스템 다이얼로그로 요청한다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    runs: List<RunSummary>,
    vitals: VitalsSnapshot?,
    weatherStore: WeatherStore,
    airStore: AirQualityStore,
    settings: SettingsStore,
    onSelectReport: () -> Unit,
    onOpenSession: (RunSummary) -> Unit,
    onOpenSettings: () -> Unit,
    onReloadHealth: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val levelRaw by settings.levelV2.collectAsState(initial = "")
    val weeklyGoal by settings.weeklyGoal.collectAsState(initial = 2)
    val onboardedAt by settings.onboardedAt.collectAsState(initial = 0L)
    val promotionDeclinedAt by settings.promotionDeclinedAt.collectAsState(initial = 0L)
    val cycleStartedAtRaw by settings.cycleStartedAt.collectAsState(initial = 0L)
    val maxStage by settings.maxStage.collectAsState(initial = 1)
    val raceGoalRaw by settings.raceGoal.collectAsState(initial = "")
    val raceGoalSec by settings.raceGoalSec.collectAsState(initial = 0)
    val raceDateRaw by settings.raceDate.collectAsState(initial = 0L)

    val weatherState by weatherStore.state.collectAsState()
    val airState by airStore.state.collectAsState()

    // 위치 권한 → 날씨 로드. iOS는 스플래시가 하는 일이지만 Android는 홈 첫 노출에서 잇는다
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { scope.launch { weatherStore.refresh() } }
    LaunchedEffect(Unit) {
        if (weatherStore.hasLocationPermission) weatherStore.load()
        else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    // 날씨(위치) 결론이 나면 대기질을 잇는다 — iOS의 coordinate 구독 대응
    LaunchedEffect(weatherState) {
        if (weatherState !is WeatherStore.State.Idle && weatherState !is WeatherStore.State.Loading) {
            airStore.refresh(weatherStore.coordinate.value)
        }
    }

    val now = remember(runs) { Instant.now() }
    val cycleStartedAt = when {
        cycleStartedAtRaw > 0 -> Instant.ofEpochSecond(cycleStartedAtRaw)
        onboardedAt > 0 -> Instant.ofEpochSecond(onboardedAt)
        // 먼 과거: 기록 전체를 XP로 세는 편이 "성장은 되돌리지 않는다" 원칙에 맞다 (iOS 동일)
        else -> Instant.EPOCH
    }
    val growth = GrowthEngine.state(runs, cycleStartedAt, maxStage, weeklyGoal, now)
    // 이번 사이클 최고 단계를 올려 둔다 — 다음 실행에서 표시 단계가 내려가지 않게 하는 하한
    LaunchedEffect(growth.stage.raw, maxStage) {
        if (growth.stage.raw > maxStage) settings.setMaxStage(growth.stage.raw)
    }

    val level = RunnerLevel.fromStorage(levelRaw) ?: RunnerLevel.BEGINNER
    val promotion = promotionOffer(runs, level, promotionDeclinedAt, now)

    Column(Modifier.fillMaxSize()) {
        HomeHeader(onOpenSettings)

        if (runs.isEmpty()) {
            FirstLaunchBody(growth)
        } else {
            val battery = vitals?.let { BatteryEngine.compute(it, runs, now) }
            val weatherInput = weatherInput(weatherState)
            val guide = trainingGuide(runs, level, raceGoalRaw, raceGoalSec, raceDateRaw,
                                      battery?.tone, now)
            val verdict = TodayVerdictEngine.verdict(
                runs = runs, battery = battery, weather = weatherInput, guide = guide,
                hasRaceGoal = RaceDistance.fromStorage(raceGoalRaw) != null,
                weeklyGoal = weeklyGoal, level = level, now = now,
            )
            val air = (airState as? AirQualityStore.State.Loaded)?.quality
            val lastRun = runs.maxByOrNull { it.start }

            var refreshing by remember { mutableStateOf(false) }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try {
                            // 건강 데이터와, 위치부터 다시 잡은 날씨·대기질을 함께 갱신 (iOS 동일)
                            onReloadHealth()
                            weatherStore.refresh()
                            airStore.refresh(weatherStore.coordinate.value)
                        } finally {
                            refreshing = false
                        }
                    }
                },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 승급 카드가 뜨면 새를 216 → 172로 줄여 카드 자리를 만든다 (시안 1h)
                    val birdSize = if (promotion == null) 216.dp else 172.dp
                    BirdView(growth.stage, growth.isSulky, Modifier.size(birdSize))

                    StageName(growth, Modifier.padding(top = 6.dp))
                    XpGauge(growth.progress, Modifier.padding(top = 14.dp))
                    XpText(growth, Modifier.padding(top = 10.dp))

                    if (promotion != null) {
                        PromotionCard(
                            target = promotion,
                            evidence = promotionEvidence(runs, now),
                            now = now,
                            onAccept = {
                                scope.launch {
                                    settings.setLevelV2(promotion.storageValue)
                                    // 수락했으면 거절 기록은 의미가 없다 — 지워 둔다
                                    settings.setPromotionDeclinedAt(0)
                                }
                            },
                            onDecline = {
                                scope.launch { settings.setPromotionDeclinedAt(now.epochSecond) }
                            },
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }

                    if (verdict != null) {
                        VerdictCard(
                            verdict = verdict, battery = battery, weather = weatherInput,
                            air = air, now = now,
                            onTap = { kind ->
                                when (kind) {
                                    TodayVerdict.Line.Kind.BATTERY,
                                    TodayVerdict.Line.Kind.SESSION -> onSelectReport()
                                    TodayVerdict.Line.Kind.WEATHER ->
                                        // 권한 거부 상태에선 앱 안에서 다시 물을 수 없다 — 설정으로.
                                        // 그 외에는 무동작: '오늘' 시트는 미이식 (M5 범위)
                                        if (weatherState is WeatherStore.State.Denied) {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                    TodayVerdict.Line.Kind.RECOVERY ->
                                        lastRun?.let(onOpenSession)
                                }
                            },
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }

                    ChipRow(runs, weeklyGoal, now, onOpenSession, Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

// MARK: - 파생값 (iOS HomeScreen의 private 함수들)

private fun weatherInput(state: WeatherStore.State): TodayVerdictEngine.WeatherInput =
    when (state) {
        WeatherStore.State.Idle, WeatherStore.State.Loading ->
            TodayVerdictEngine.WeatherInput.Loading
        is WeatherStore.State.Loaded -> TodayVerdictEngine.WeatherInput.Current(state.weather)
        WeatherStore.State.Denied -> TodayVerdictEngine.WeatherInput.Denied
        WeatherStore.State.Unavailable -> TodayVerdictEngine.WeatherInput.Unavailable
    }

/// 주간 처방 — 리포트 탭과 같은 방식으로 만든다 (목표 레이스가 없으면 null)
private fun trainingGuide(
    runs: List<RunSummary>,
    level: RunnerLevel,
    raceGoalRaw: String,
    raceGoalSec: Int,
    raceDateRaw: Long,
    batteryTone: com.jkpark.runwrap.ui.theme.RRTone?,
    now: Instant,
): TrainingGuide? {
    val race = RaceDistance.fromStorage(raceGoalRaw) ?: return null
    return TrainingGuideEngine(now, level).guide(
        runs = runs, records = PersonalRecords.compute(runs), race = race,
        goalSec = if (raceGoalSec > 0) raceGoalSec.toDouble() else null,
        raceDate = if (raceDateRaw > 0) Instant.ofEpochSecond(raceDateRaw) else null,
        batteryTone = batteryTone,
    )
}

/// 실데이터 승급 후보. 거절한 지 4주가 안 지났으면 다시 묻지 않는다 (기획서 §3)
private fun promotionOffer(
    runs: List<RunSummary>,
    level: RunnerLevel,
    declinedAtEpoch: Long,
    now: Instant,
): RunnerLevel? {
    if (declinedAtEpoch > 0 && now.epochSecond - declinedAtEpoch < 28 * 86_400) return null
    return LevelEngine.promotionCandidate(level, runs, now)
}

/// 승급 근거가 된 세션 — LevelEngine 판정 조건(최근 4주 · 10km 이상 · 60분 이내)과 같은 창
private fun promotionEvidence(runs: List<RunSummary>, now: Instant): RunSummary? {
    val fourWeeksAgo = now.minusSeconds(28L * 86_400)
    return runs
        .filter { it.start >= fourWeeksAgo && it.start <= now }
        .filter { (it.distanceKm ?: 0.0) >= 10 && it.durationSec <= 3_600 }
        .maxByOrNull { it.start }
}

/// "어제 러닝" / "7일 전 러닝" — 시안 1f는 상대 표기를 쓴다
private fun relativeRunLabel(run: RunSummary, now: Instant): String {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(
        run.start.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate(),
    )
    return when {
        days < 1 -> "오늘 러닝"
        days == 1L -> "어제 러닝"
        else -> "${days}일 전 러닝"
    }
}

/// "5.2km · 5′41″/km" — 페이스를 낼 수 없을 만큼 짧으면 거리만 (미노출 가드)
private fun runValueLine(run: RunSummary): String {
    val km = run.distanceKm ?: return Format.duration(run.durationSec)
    val pace = run.paceSecPerKm ?: return "${Format.km(km)}km"
    return "${Format.km(km)}km · ${Format.paceKm(pace)}"
}

/// 이번 ISO 주(월요일 시작)에 시작한 러닝 수
private fun weekRunCount(runs: List<RunSummary>, now: Instant): Int {
    val zone = ZoneId.systemDefault()
    val monday = now.atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStart = monday.atStartOfDay(zone).toInstant()
    return runs.count { it.start >= weekStart && it.start <= now }
}

/// "지난주" / "이번 주" / "3주 전" — 근거 세션이 언제였는지 앞머리
private fun relativePeriod(run: RunSummary, now: Instant): String {
    val zone = ZoneId.systemDefault()
    val adjuster = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
    val weeks = ChronoUnit.WEEKS.between(
        run.start.atZone(zone).toLocalDate().with(adjuster),
        now.atZone(zone).toLocalDate().with(adjuster),
    )
    return when {
        weeks < 1 -> "이번 주"
        weeks == 1L -> "지난주"
        else -> "${weeks}주 전"
    }
}

/// Swift `.rounded()` 대응 — 0에서 먼 쪽으로 반올림 (체감온도는 음수가 있을 수 있다)
private fun roundedInt(x: Double): Int =
    (if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)).toInt()

/// WMO 코드 → 하늘 상태 글리프. iOS는 SF Symbol을 팔레트 틴트로 쓰지만
/// Android엔 대응 아이콘이 없어 이모지로 대체한다 (M5 트레이드오프)
private fun conditionEmoji(code: Int?): String? = when (code) {
    null -> null
    0, 1 -> "☀️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..57 -> "🌦️"
    in 61..67 -> "🌧️"
    in 71..77 -> "❄️"
    in 80..82 -> "🌧️"
    85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "☁️"
}

// MARK: - 헤더

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("런미새", style = RR.display(18.sp), color = RR.text)
        Spacer(Modifier.weight(1f))
        // 설정 진입 — 리포트 탭까지 가지 않아도 홈에서 바로 연다 (iOS의 필 스타일)
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenSettings)
                .background(RR.surface, RoundedCornerShape(8.dp))
                .border(1.dp, RR.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Icon(
                Icons.Filled.Settings, contentDescription = "설정",
                tint = RR.text, modifier = Modifier.size(14.dp),
            )
        }
    }
}

// MARK: - 첫 실행 (시안 1g)

/// 기록이 없을 때 — 알과 안내 문장만. 브리핑·칩은 통째로 감춘다 (미노출 가드)
@Composable
private fun FirstLaunchBody(growth: GrowthState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        BirdView(growth.stage, isSulky = false, Modifier.size(212.dp))
        StageName(growth, Modifier.padding(top = 6.dp))
        XpGauge(growth.progress, Modifier.padding(top = 14.dp))
        XpText(growth, Modifier.padding(top = 10.dp))

        Text(
            "첫 러닝을 기다리고 있어요. 알도 기다리고 있습니다.",
            Modifier
                .padding(top = 26.dp)
                .widthIn(max = 300.dp)
                .rrCard()
                .padding(16.dp),
            fontSize = 15.sp, lineHeight = 24.sp,
            textAlign = TextAlign.Center, color = RR.text2,
        )

        Spacer(Modifier.weight(1f))
    }
}

// MARK: - 스테이지 텍스트

/// "{이름} · {N}단계[ · 시무룩]" — 이름만 디스플레이 서체, 나머지는 본문 서체 한 줄
@Composable
private fun StageName(growth: GrowthState, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(
            growth.stage.label, style = RR.display(23.sp), color = RR.text,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            " · ${growth.stage.raw}단계" + if (growth.isSulky) " · 시무룩" else "",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.text3,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun XpText(growth: GrowthState, modifier: Modifier = Modifier) {
    Text(
        growth.xpToNextStage?.let { "다음 단계까지 $it XP" } ?: "성조 도달 — 세러모니가 기다려요",
        modifier,
        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace, letterSpacing = 0.46.sp, color = RR.text2,
    )
}

/// XP 게이지 — 시안 210×8, radius 4, surface2 바탕 + line 테두리, 안쪽 brand 채움
@Composable
private fun XpGauge(progress: Double, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0.0, 1.0)
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(width = 210.dp, height = 8.dp)
            .clip(shape)
            .background(RR.surface2)
            .border(1.dp, RR.line, shape),
    ) {
        Box(
            Modifier
                .width((210 * clamped).dp)
                .fillMaxHeight()
                .background(RR.brand, shape),
        )
    }
}

/// 주간 목표 점 — 달성 brand 채움 / 미달 surface2 + line 테두리 (시안 7×7 radius 4)
@Composable
private fun GoalDots(total: Int, done: Int) {
    val shape = RoundedCornerShape(4.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxOf(0, total)) { index ->
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (index < done) RR.brand else RR.surface2, shape)
                    .then(if (index >= done) Modifier.border(1.dp, RR.line, shape) else Modifier),
            )
        }
    }
}

// MARK: - 칩 2개

@Composable
private fun ChipRow(
    runs: List<RunSummary>,
    weeklyGoal: Int,
    now: Instant,
    onOpenSession: (RunSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val last = runs.maxByOrNull { it.start }
        if (last != null) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .rrCard()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenSession(last) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                    Text(relativeRunLabel(last, now), fontSize = 11.sp, color = RR.text3)
                    Text(
                        runValueLine(last),
                        fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = RR.text3, modifier = Modifier.size(16.dp),
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .rrCard()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val done = weekRunCount(runs, now)
            Text("이번 주 목표", fontSize = 11.sp, color = RR.text3)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$done / ${weeklyGoal}회",
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                )
                GoalDots(total = weeklyGoal, done = done)
            }
        }
    }
}

// MARK: - 승급 제안 카드 (시안 1h)

/// 1회 노출, 거절하면 4주 뒤에 다시 묻는다. 승급만 있고 강등은 없다 (기획서 §3)
@Composable
private fun PromotionCard(
    target: RunnerLevel,
    evidence: RunSummary?,
    now: Instant,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(RR.surface, shape)
            .border(1.5.dp, RR.brand, shape)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "승급 제안",
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.26.sp, color = RR.brand,
        )

        val evidenceKm = evidence?.distanceKm
        Text(
            buildAnnotatedString {
                if (evidence != null && evidenceKm != null) {
                    append("${relativePeriod(evidence, now)} ${Format.km(evidenceKm)}km를 ")
                    append("${Format.duration(evidence.durationSec)}에 달리셨더라고요. 리포트를 ")
                } else {
                    append("최근 기록을 보니 한 단계 올려도 되겠어요. 리포트를 ")
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(target.label) }
                append(" 수준으로 올려드릴까요?")
            },
            fontSize = 14.5.sp, lineHeight = 22.5.sp, color = RR.text,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val buttonShape = RoundedCornerShape(9.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(buttonShape)
                    .clickable(onClick = onAccept)
                    .background(RR.brand, buttonShape)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "좋아요, 승급할게요",
                    fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(buttonShape)
                    .clickable(onClick = onDecline)
                    .background(RR.surface, buttonShape)
                    .border(1.dp, RR.line, buttonShape)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "지금은 괜찮아요",
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                )
            }
        }

        Text("괜찮다고 하시면 4주 동안 다시 묻지 않아요", fontSize = 11.sp, color = RR.text3)
    }
}

// MARK: - 오늘의 판단 카드 (기획서 v0.8 §6)

/// 판정 한 줄 + 그 판정의 재료 네 줄. 판정·문구는 전부 `TodayVerdictEngine`이 정하고
/// 여기서는 색과 목적지만 붙인다.
@Composable
private fun VerdictCard(
    verdict: TodayVerdict,
    battery: BatteryReport?,
    weather: TodayVerdictEngine.WeatherInput,
    air: AirQuality?,
    now: Instant,
    onTap: (TodayVerdict.Line.Kind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .rrCard()
            .padding(top = 15.dp, start = 15.dp, end = 15.dp, bottom = 5.dp),
    ) {
        // 배터리가 없으면 판정 자체가 없다 — 배지를 감추고 중립 문구만 남긴다
        verdict.tone?.let { tone ->
            Box(Modifier.padding(bottom = 9.dp)) { ToneBadge(tone) }
        }

        Text(verdict.headline, style = RR.display(19.sp), color = RR.text)

        // 배터리·날씨는 눈으로 먼저 읽히는 값이라 글자 대신 타일 두 장으로 낸다
        Row(
            Modifier
                .padding(top = 13.dp)
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            VerdictTile(verdict.battery, onTap, Modifier.weight(1f)) {
                BatteryArt(battery, verdict.battery)
            }
            VerdictTile(verdict.weather, onTap, Modifier.weight(1f)) {
                WeatherArt(weather, air, verdict.weather, now)
            }
        }

        HorizontalDivider(Modifier.padding(top = 13.dp), color = RR.line)
        VerdictRow(verdict.session, onTap)
        HorizontalDivider(color = RR.line)
        VerdictRow(verdict.recovery, onTap)
    }
}

@Composable
private fun VerdictTile(
    line: TodayVerdict.Line,
    onTap: (TodayVerdict.Line.Kind) -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxHeight()
            .defaultMinSize(minHeight = 104.dp)
            .clip(shape)
            .clickable { onTap(line.kind) }
            .background(RR.surface2, shape)
            .padding(top = 11.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.label, fontSize = 11.5.sp, color = RR.text3, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = RR.text3, modifier = Modifier.size(12.dp),
            )
        }
        art()
    }
}

/// 잔량 막대 + 큰 숫자 — 배터리는 "얼마나 남았나"가 한눈에 들어와야 하는 값이다
@Composable
private fun BatteryArt(battery: BatteryReport?, line: TodayVerdict.Line) {
    if (battery == null) {
        HintArt(line) { Text("⌚", fontSize = 21.sp) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${battery.level}", style = RR.numeral(25.sp), color = battery.tone.color,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                battery.statusLabel,
                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = RR.text2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
        // 리포트 탭과 같은 게이지를 그대로 쓴다 — 칸 수로 잔량이 먼저 읽힌다
        BatteryGauge(battery.level, Modifier.fillMaxWidth())
    }
}

/// 하늘 상태 글리프 + 체감온도 + 복장 — '오늘' 시트의 세 카드를 한 장으로 줄인 요약
@Composable
private fun WeatherArt(
    weather: TodayVerdictEngine.WeatherInput,
    air: AirQuality?,
    line: TodayVerdict.Line,
    now: Instant,
) {
    when (weather) {
        is TodayVerdictEngine.WeatherInput.Current -> {
            val parts = TodayVerdictEngine.weatherParts(weather.weather, now)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    conditionEmoji(weather.weather.weatherCode)?.let {
                        Text(it, fontSize = 21.sp, modifier = Modifier.alignByBaseline())
                    }
                    Text(
                        "${roundedInt(weather.weather.apparentC)}",
                        style = RR.numeral(25.sp), color = RR.text,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        "°C 체감", fontSize = 10.5.sp, color = RR.text3,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                parts.outfit?.let { outfit ->
                    Text(
                        if (parts.raining) "비 · $outfit" else outfit,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RR.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 미세·초미세는 등급 문구를 등급 색으로 — 값이 있는 항목만 (미노출 가드)
                if (air != null && (air.pm10Grade != null || air.pm25Grade != null)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AirBadge("미세", air.pm10Grade)
                        AirBadge("초미세", air.pm25Grade)
                    }
                }
            }
        }
        TodayVerdictEngine.WeatherInput.Loading -> HintArt(line) {
            Icon(Icons.Filled.Refresh, null, tint = RR.text3, modifier = Modifier.size(21.dp))
        }
        TodayVerdictEngine.WeatherInput.Denied -> HintArt(line) {
            Icon(Icons.Filled.LocationOn, null, tint = RR.text3, modifier = Modifier.size(21.dp))
        }
        TodayVerdictEngine.WeatherInput.Unavailable -> HintArt(line) {
            Text("☁️", fontSize = 21.sp)
        }
    }
}

/// "미세 좋음" — 항목명은 보조색, 등급 문구는 등급 톤 색. 등급이 없으면 그리지 않는다
@Composable
private fun AirBadge(name: String, grade: AirGrade?) {
    if (grade == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, fontSize = 10.5.sp, color = RR.text3)
        Text(grade.label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = grade.tone.color)
    }
}

/// 값이 없는 타일 — 숫자 자리를 비워 두고 무엇을 하면 켜지는지만 말한다
@Composable
private fun HintArt(line: TodayVerdict.Line, icon: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(lineText(line), fontSize = 12.sp, lineHeight = 16.sp, color = RR.text3)
    }
}

@Composable
private fun VerdictRow(line: TodayVerdict.Line, onTap: (TodayVerdict.Line.Kind) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onTap(line.kind) }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.label, fontSize = 11.5.sp, color = RR.text3, modifier = Modifier.width(66.dp))
        Text(
            lineText(line),
            fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = lineColor(line),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = RR.text3, modifier = Modifier.size(14.dp),
        )
    }
}

private fun lineText(line: TodayVerdict.Line): String = when (val content = line.content) {
    is TodayVerdict.Line.Content.Value -> content.text
    is TodayVerdict.Line.Content.Hint -> content.text
}

/// 유도 문구는 값이 아니므로 한 단계 흐리게 — 값 줄만 톤 색을 입는다
@Composable
private fun lineColor(line: TodayVerdict.Line): Color = when (line.content) {
    is TodayVerdict.Line.Content.Hint -> RR.text3
    is TodayVerdict.Line.Content.Value -> line.tone?.color ?: RR.text
}
