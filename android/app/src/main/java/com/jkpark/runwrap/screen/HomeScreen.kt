package com.jkpark.runwrap.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.AirGrade
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.AirQualityEngine
import com.jkpark.runwrap.engine.BatteryEngine
import com.jkpark.runwrap.engine.BatteryReport
import com.jkpark.runwrap.engine.CollectionEngine
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GrowthEngine
import com.jkpark.runwrap.engine.GrowthKey
import com.jkpark.runwrap.engine.GrowthStage
import com.jkpark.runwrap.engine.GrowthState
import com.jkpark.runwrap.engine.LevelEngine
import com.jkpark.runwrap.engine.PBBaseline
import com.jkpark.runwrap.engine.PBBaselineCache
import com.jkpark.runwrap.engine.PBEngine
import com.jkpark.runwrap.engine.PersonalRecords
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.ProgressSnapshot
import com.jkpark.runwrap.engine.PromotionEvidence
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RaceFormat
import com.jkpark.runwrap.engine.RaceKey
import com.jkpark.runwrap.engine.RecapEngine
import com.jkpark.runwrap.engine.RecapKey
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.RunWindowEngine
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.Shoe
import com.jkpark.runwrap.engine.ShoeEngine
import com.jkpark.runwrap.engine.ShoeKey
import com.jkpark.runwrap.engine.TodayVerdict
import com.jkpark.runwrap.engine.TodayVerdictEngine
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.WeeklyGoalChangeLog
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.instantSince1970
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.engine.timeIntervalSince1970
import com.jkpark.runwrap.health.HealthPermissions
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.store.AirQualityStore
import com.jkpark.runwrap.store.DemoMode
import com.jkpark.runwrap.store.RaceStore
import com.jkpark.runwrap.store.WeatherStore
import com.jkpark.runwrap.store.appSupportDir
import com.jkpark.runwrap.ui.BatteryGauge
import com.jkpark.runwrap.ui.BirdView
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RegisterBadge
import com.jkpark.runwrap.ui.ShoeEditSheet
import com.jkpark.runwrap.ui.ShoeImage
import com.jkpark.runwrap.ui.ToneBadge
import com.jkpark.runwrap.ui.WeatherCondition
import com.jkpark.runwrap.ui.color
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/// 홈 탭 — 새 성장 스테이지와 **오늘의 판단 카드** (기획서 v0.8 §6, 시안 1f/1g/1h).
///
/// 새가 첫 화면의 주인공이고, 그 아래가 "오늘 뛸까 말까"에 답하는 판단 카드다.
/// 상세 지표·차트는 전부 리포트 탭의 일이다. 데이터 가공은 하지 않는다 —
/// XP·단계는 `GrowthEngine`, 오늘의 판단은 `TodayVerdictEngine`, 승급 판정은 `LevelEngine`이 낸다.
///
/// 날씨(위치)는 `WeatherStore`가 기동 시점에 조회를 마치고 내려준다 — 홈이 뜨기 전에
/// 스플래시가 그 완료를 기다리는 구조라, 여기서는 읽기만 하고 로딩을 시작하지 않는다.
/// (Android: 위치·Health Connect 권한 요청은 화면 몫이라(P3 계약) 홈이 맡는다 — 허용되면 스토어를 다시 부른다.
///  '오늘'·결산·대회 상세·마지막 러닝 상세는 iOS의 시트·푸시 대신 콜백으로 셸(RootView)이 route를 연다)
private class HomeViewModel(context: Context) : ViewModel() {
    /// 홈 날씨 타일의 미세·초미세 등급 재료 — 좌표는 WeatherStore의 위치 결론을 같이 쓴다.
    /// '오늘' 시트의 스토어와 별개 인스턴스지만 1시간 디스크 캐시를 공유해 중복 조회는 없다
    val airQuality = AirQualityStore(context)
}

private fun ts(size: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size.sp, fontWeight = weight)

/// 홈 탭 루트
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSelectReport: () -> Unit,
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
    onOpenToday: () -> Unit,
    onOpenRecap: (RecapPeriod) -> Unit,
    onOpenRace: (RaceEngine.Entry) -> Unit,
    onOpenCollection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val settings = container.settings
    val health = container.health
    val weather = container.weather
    val shoes = container.shoes
    val raceStore = container.raceStore
    val model = containerViewModel { HomeViewModel(it.context) }
    val airQuality = model.airQuality

    val healthState by health.state.collectAsStateWithLifecycle()
    val weatherState by weather.state.collectAsStateWithLifecycle()
    val coordinate by weather.coordinate.collectAsStateWithLifecycle()
    val airState by airQuality.state.collectAsStateWithLifecycle()
    val vitals by health.vitals.collectAsStateWithLifecycle()
    val bestEffortPending by health.bestEffortPending.collectAsStateWithLifecycle()
    val shoeList by shoes.shoes.collectAsStateWithLifecycle()
    val defaultShoeID by shoes.defaultShoeID.collectAsStateWithLifecycle()
    val raceState by raceStore.state.collectAsStateWithLifecycle()

    /// 날씨 타일 재시도 중 — refresh()는 .loading을 거치지 않아 타일에 진행을 따로 알리고 연타를 막는다
    var retryingWeather by remember { mutableStateOf(false) }
    /// 재시도 후에도 실패 — 접근성 알림(iOS AccessibilityNotification.Announcement)을 liveRegion으로 낸다
    var weatherRetryFailed by remember { mutableStateOf(false) }

    var levelRaw by settings.rememberSetting(ProfileKey.levelV2, RunnerLevel.beginner.rawValue)
    /// 주간 목표 — 온보딩 Q5에서 항상 먼저 쓰이므로 이 기본값은 사실상 안전망이다.
    /// 값은 `OnboardingFlowScreen`의 미응답 기본값(2)과 맞춰 둔다
    val weeklyGoal by settings.rememberSetting(ProfileKey.weeklyGoal, 2)
    /// 주간 목표 변경 이력 (이슈 #108, #116) — 설정·재진단이 기록한다. 바뀌면 다시 그리도록 관찰한다
    val weeklyGoalChangesData by settings.rememberSetting(
        ProfileKey.weeklyGoalChanges, { settings.string(ProfileKey.weeklyGoalChanges) }, { _: String? -> })
    val onboardedAtRaw by settings.rememberSetting(ProfileKey.onboardedAt, 0.0)
    var promotionDeclinedAtRaw by settings.rememberSetting(ProfileKey.promotionDeclinedAt, 0.0)
    var cycleStartedAtRaw by settings.rememberSetting(GrowthKey.cycleStartedAt, 0.0)
    var maxStage by settings.rememberSetting(GrowthKey.maxStage, GrowthStage.egg.rawValue)
    var raceGoalRaw by settings.rememberSetting(ProfileKey.raceGoal, "")
    var raceGoalSec by settings.rememberSetting(ProfileKey.raceGoalSec, 0)
    /// 세러모니에서 수집될 새 종을 정하는 목표 — 사이클 시작 때 고정한 값 (이슈 #110).
    /// 옵셔널인 이유: 키가 없는(도입 전) 사용자를 가려 현재 목표로 한 번 보정하기 위해서다
    var cycleGoalRaw by settings.rememberSetting(
        GrowthKey.cycleGoal, { settings.string(GrowthKey.cycleGoal) },
        { v: String? -> if (v == null) settings.remove(GrowthKey.cycleGoal) else settings.set(GrowthKey.cycleGoal, v) })
    var cycleGoalSecRaw by settings.rememberSetting(
        GrowthKey.cycleGoalSec,
        { if (settings.contains(GrowthKey.cycleGoalSec)) settings.int(GrowthKey.cycleGoalSec) else null },
        { v: Int? -> if (v == null) settings.remove(GrowthKey.cycleGoalSec) else settings.set(GrowthKey.cycleGoalSec, v) })
    var deferredSpeciesRaw by settings.rememberSetting(GrowthKey.deferredSpecies, "")
    val raceDateRaw by settings.rememberSetting(ProfileKey.raceDate, 0.0)

    // 성장 상태가 바뀌는 지점(단계 상승·사이클 전환·승급)에서 백업 스냅샷을 갱신한다 (이슈 #29)
    var showsCeremony by remember { mutableStateOf(false) }
    /// 수집 확정 때 도감 저장이 실패했는지 — 세러모니 위에 알림을 띄운다 (이슈 #67)
    var showsCollectFailed by remember { mutableStateOf(false) }
    // PB 축하 (이슈 #21) — 홈 진입 때 베이스라인과 비교해 새 기록이면 한 번만 띄운다
    var showsPBCongrats by remember { mutableStateOf(false) }
    var newPBs by remember { mutableStateOf(emptyList<PersonalRecords.Entry>()) }
    // 결산 리캡 (이슈 #167) — 월초·연말연초 홈 카드. 열어 보거나 X를 누른 기간은 다시 띄우지 않는다
    var recapDismissedMonth by settings.rememberSetting(RecapKey.dismissedMonth, "")
    var recapDismissedYear by settings.rememberSetting(RecapKey.dismissedYear, "")
    // 러닝화 카드 (이슈 #171, #206) — 판단 카드 아래에 상시 노출. 교체 안내도 이 카드의 각 행이 맡는다
    var editingShoe by remember { mutableStateOf<Shoe?>(null) }
    var editingShoeIsNew by remember { mutableStateOf(false) }
    // 러닝 후 러닝화 묻기 팝업 (이슈 #206) — 기준 시각 이후의 새 러닝을 카드로 넘기며 고른다
    var shoePromptedThrough by settings.rememberSetting(ShoeKey.promptedThrough, 0.0)
    var shoePromptOptOut by settings.rememberSetting(ShoeKey.promptOptOut, false)
    var showsShoePrompt by remember { mutableStateOf(false) }
    /// 띄울 때 고정한 러닝 목록 — 배정이 바뀌어도 시트 아래에서 카드가 바뀌지 않게 한다
    var shoePromptRuns by remember { mutableStateOf(emptyList<RunSummary>()) }
    // 목표 대회 카드 (이슈 #172) — 대회 상세의 '목표 대회로 지정'이 정한 대회. 목록은 앱 단일 RaceStore
    val targetRaceID by settings.rememberSetting(RaceKey.targetID, 0)
    var isRefreshing by remember { mutableStateOf(false) }

    // 권한 요청 (Android) — 프로세스마다 한 번만 묻는다. Health Connect 시트가 먼저, 위치가 그다음
    var askedHealth by rememberSaveable { mutableStateOf(false) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 거부면 스토어가 이미 Denied — 허용됐을 때만 위치부터 다시 잡는다
        if (granted) container.scope.launch { weather.refreshIfStale() }
    }
    fun askLocationIfNeeded() {
        if (askedLocation) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        askedLocation = true
        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        // 결과와 무관하게 다시 읽는다 (iOS health.load가 시트를 띄우는 것에 대응)
        container.scope.launch { health.connect() }
        askLocationIfNeeded()
    }
    LaunchedEffect(healthState) {
        val runs = (healthState as? HealthStore.State.Loaded)?.runs ?: return@LaunchedEffect
        // 복원 직후에는 didConnectHealth를 믿지 않고 실제 권한으로 본다 — 기록이 없을 때만 다시 묻는다
        if (runs.isEmpty() && !askedHealth && !DemoMode.isActive(settings) && !health.hasPermissions()) {
            askedHealth = true
            healthLauncher.launch(HealthPermissions.standard)
        } else {
            askLocationIfNeeded()
        }
    }

    /// 사이클 목표 키가 없는 기존 사용자(이슈 #110 이전 설치·복원)는 지금의 목표를 한 번 복사해 고정한다.
    /// 이후 설정에서 목표를 바꿔도 이번 사이클의 새 종류는 그대로다
    LaunchedEffect(Unit) {
        if (cycleGoalRaw == null) cycleGoalRaw = raceGoalRaw
        if (cycleGoalSecRaw == null) cycleGoalSecRaw = raceGoalSec
    }
    // 구독 시점의 현재 좌표부터 흘러온다 — 첫 노출과 늦은 위치 결론(스플래시 타임아웃 경로)을 한 줄로 처리
    LaunchedEffect(coordinate) {
        val c = coordinate ?: return@LaunchedEffect
        model.viewModelScope.launch { airQuality.load(c.lat, c.lon) }
    }
    // 포그라운드 복귀 — load()는 첫 조회만 하므로 낡은 대기질은 여기서 갱신한다 (이슈 #69).
    // 좌표는 직전 위치 결론이다 — 같은 순간 루트가 날씨를 새로 받는 중이어도 측정소가
    // 바뀔 만큼 이동한 경우가 아니면 결과가 같고, 다음 복귀·당겨서 새로고침이 따라잡는다
    LifecycleResumeEffect(Unit) {
        weather.coordinate.value?.let { c -> model.viewModelScope.launch { airQuality.refreshIfStale(c.lat, c.lon) } }
        onPauseOrDispose { }
    }
    // 목표 대회 카드 재료 — 대회 탭을 열지 않았어도 목표가 있으면 목록을 불러온다 (이슈 #172)
    LaunchedEffect(targetRaceID) {
        if (targetRaceID != 0) raceStore.load()
    }

    val runs = (healthState as? HealthStore.State.Loaded)?.runs
    if (runs == null) {
        // 로딩·실패는 RootView가 이미 분기한다 — 여기서는 배경만 유지한다
        Box(Modifier.fillMaxSize().background(RR.bg))
        return
    }

    val isDemo = DemoMode.isActive(settings)
    val now = Instant.now()
    val zone = ZoneId.systemDefault()

    /// 사이클 시작 시각 — 미설정(0)이면 온보딩 시각, 그마저 없으면 먼 과거로 둔다.
    /// 먼 과거를 쓰는 이유: 기록 전체를 XP로 세는 편이, 사이클을 오늘로 잡아 XP를 0으로
    /// 리셋해 버리는 것보다 "성장은 되돌리지 않는다" 원칙에 맞다.
    val cycleStartedAt = when {
        cycleStartedAtRaw > 0 -> instantSince1970(cycleStartedAtRaw)
        onboardedAtRaw > 0 -> instantSince1970(onboardedAtRaw)
        // Swift Date.distantPast(0001-01-01Z) — Instant.MIN은 엔진의 atZone에서 DateTimeException을 던진다
        else -> Instant.parse("0001-01-01T00:00:00Z")
    }
    /// 주간 목표 변경 이력 — 비어 있으면 모든 주를 현재 목표로 판정한다 (이슈 #108, #116).
    /// 새 키가 아직 없으면 읽기 헬퍼가 #108의 옛 두 키를 1건짜리 이력으로 이관한다 — 첫 렌더부터
    /// 옛 기록으로 판정해야 이관 전 계산이 maxStage를 부풀리지 않는다
    val weeklyGoalChanges = weeklyGoalChangesData?.let { WeeklyGoalChangeLog.decode(it) }
        ?: WeeklyGoalChangeLog.load(settings)
    /// 이번 사이클 목표 — 키가 아직 없으면 현재 목표로 대신한다 (보정 저장 전 첫 렌더 대비)
    val cycleGoal = RaceDistance.fromRawValue(cycleGoalRaw ?: raceGoalRaw)
    val cycleGoalSec = cycleGoalSecRaw ?: raceGoalSec

    val growth = GrowthEngine.state(runs = runs, cycleStartedAt = cycleStartedAt, maxStage = maxStage,
                                    weeklyGoal = weeklyGoal, weeklyGoalChanges = weeklyGoalChanges,
                                    now = now, zone = zone)
    val level = RunnerLevel.fromRawValue(levelRaw) ?: RunnerLevel.beginner

    /// 성장 상태 변경 직후의 스냅샷 백업 — 실패해도 다음 트리거에서 다시 올라간다
    fun scheduleBackup() = container.backup.backupIfChanged()

    /// 지금 수집될 새 종과 근거 기록 — 세러모니 표시와 실제 수집이 같은 판정을 쓴다.
    /// 목표가 아니라 이번 사이클에 실제로 달린 기록으로 정한다
    fun pendingBird(runs: List<RunSummary>) = CollectionEngine.earned(runs, since = cycleStartedAt)

    /// 표시 단계를 최고 단계에 기록하고, 성조면 세러모니를 띄운다 — 홈 진입·단계 변화 두 곳에서 부른다 (이슈 #60)
    fun syncStage(stage: GrowthStage, runs: List<RunSummary>) {
        // 데모(합성 데이터)는 표시만 한다 — 최고 단계·세러모니·사이클 전환을 저장하면
        // 데모를 꺼도 부풀려진 단계와 가짜 새가 남고 백업까지 올라간다 (이슈 #44).
        // 세러모니가 뜨지 않으면 startNewCycle도 불리지 않는다
        if (DemoMode.isActive(settings)) return
        // 이번 사이클 최고 단계를 올려 둔다 — 다음 실행에서 표시 단계가 내려가지 않게 하는 하한.
        if (stage.rawValue > maxStage) {
            maxStage = stage.rawValue
            ProgressSnapshot.markLocalChanged(settings, Instant.now())
            scheduleBackup()
        }
        // 성조에 도달했는데 아직 수집하지 않았다면 세러모니를 띄운다.
        // 판정은 표시 단계로 한다 — XP가 흔들려도 한 번 성조가 됐으면 성조다.
        // 이미 떠 있으면 다시 세우지 않는다. "조금 더 키우기"로 미룬 종 그대로면 조용히 두고,
        // 그 뒤 기록으로 종이 올랐으면 다시 축하한다
        if (!showsCeremony && CollectionEngine.hasReachedAdult(stage)
            && deferredSpeciesRaw != pendingBird(runs).species.rawValue) showsCeremony = true
    }

    /// 수집 확정 — 도감에 넣고 새 사이클을 시작한다 (기획서 §5).
    ///
    /// 사이클 전환의 **유일한 지점**이다. 순서가 중요하다: 도감에 먼저 넣고
    /// 사이클을 초기화한다 — 반대로 하면 저장에 실패했을 때 새를 잃는다.
    /// `cycleStartedAt`을 지금으로 옮기면 XP는 자동으로 0부터 다시 쌓인다
    /// (XP 원장을 저장하지 않는 설계라 리셋할 값이 따로 없다).
    /// - Returns: 도감 저장 성공 여부. 실패하면 사이클을 그대로 두고 알림만 띄운다 (이슈 #67)
    fun startNewCycle(runs: List<RunSummary>, goal: RaceDistance?, goalSeconds: Int, now: Instant): Boolean {
        val saved = container.collection.add(
            CollectionEngine.collect(runs, cycleStartedAt = cycleStartedAt, now = now, zone = zone,
                                     id = UUID.randomUUID().toString().uppercase()))
        if (!saved) {
            showsCollectFailed = true
            return false
        }
        raceGoalRaw = goal?.rawValue ?: ""
        raceGoalSec = goalSeconds
        // 새 사이클의 목표를 고정한다 — 다음 세러모니의 목표 추천 기준이 된다 (이슈 #110)
        cycleGoalRaw = goal?.rawValue ?: ""
        cycleGoalSecRaw = goalSeconds
        cycleStartedAtRaw = now.timeIntervalSince1970
        maxStage = GrowthStage.egg.rawValue
        deferredSpeciesRaw = ""
        // 새 사이클 = 새 식별자 — 백업 스냅샷 병합의 사이클 경계 (이슈 #29)
        settings.set(GrowthKey.cycleID, UUID.randomUUID().toString().uppercase())
        ProgressSnapshot.markLocalChanged(settings, now)
        scheduleBackup()
        return true
    }

    /// PB 갱신 감지 (이슈 #21) — 베이스라인과 비교해 새 기록이 있으면 한 번 축하한다.
    /// 첫 비교(베이스라인 없음)는 조용히 씨만 뿌린다 — 기존 기록 전부를 축하하면 소음이다.
    /// 세러모니와 겹치면 이번에는 베이스라인을 남겨 두고 미룬다 — 다음 진입 때 다시 잡힌다.
    fun checkNewPBs(runs: List<RunSummary>) {
        if (DemoMode.isActive(settings)) return   // 합성 데이터 기록으로는 축하하지 않는다
        // 베스트 에포트 백필 중이면 미룬다 — 일부만 계산된 기록으로 시드하면, 나중에 계산된
        // 옛 세션의 기록이 "새 PB"로 축하된다 (이슈 #166)
        if (health.bestEffortPending.value != 0) return
        val current = PersonalRecords.compute(runs, health.bestEfforts.value)
        if (current.isEmpty()) return
        val dir = appSupportDir(context)
        val fresh = PBEngine.newRecords(current, PBBaselineCache.load(dir))
        // 러닝화 팝업이 떠 있어도 미룬다 — 시트는 한 번에 하나라 겹치면 축하가 뜨지 못하고 사라진다 (이슈 #206)
        if (fresh.isNotEmpty() && (showsCeremony || showsShoePrompt)) return
        PBBaselineCache.save(PBBaseline.make(from = current), dir)
        if (fresh.isNotEmpty()) {
            newPBs = fresh
            showsPBCongrats = true
        }
    }

    /// 러닝 후 러닝화 묻기 (이슈 #206) — 기준 시각(`ShoeKey.promptedThrough`) 이후의 새 러닝을 팝업으로 묻는다.
    /// 첫 비교(기준 없음)는 조용히 기준만 심는다 — 기존 기록 전부를 묻지 않는다(PB 베이스라인과 같은 방식).
    /// 세러모니·PB 축하가 떠 있으면 기준을 남겨 두고 미룬다 — 둘의 onDismiss가 다시 부른다.
    /// 기준은 팝업이 닫힐 때 올린다
    fun checkNewRunsForShoe(runs: List<RunSummary>) {
        if (showsShoePrompt) return
        val now = Instant.now()
        if (DemoMode.isActive(settings)) return   // 실기기 데모(합성 데이터)로는 묻지 않는다
        if (!(shoePromptedThrough > 0)) {
            shoePromptedThrough = now.timeIntervalSince1970
            return
        }
        val baseline = instantSince1970(shoePromptedThrough)
        // '다시 보지 않기' — 기준만 따라 올려, 나중에 다시 켜도 지난 러닝이 쏟아지지 않게 한다
        if (shoePromptOptOut) {
            val latest = runs.maxOfOrNull { it.start }?.timeIntervalSince1970
            if (latest != null && latest > shoePromptedThrough) shoePromptedThrough = latest
            return
        }
        val pending = ShoeEngine.pendingRuns(runs, promptedThrough = baseline, now = now)
        // PB 백필이 끝나기 전에는 띄우지 않는다(PB 축하가 먼저). 다른 시트가 떠 있으면 다음 기회로 미룬다 —
        // 시트 위에 또 띄우면 표시되지 않은 채 플래그만 남는다
        if (pending.isEmpty() || showsCeremony || showsPBCongrats || health.bestEffortPending.value != 0
            || editingShoe != null) return
        shoePromptRuns = pending
        showsShoePrompt = true
    }

    // 홈 진입 순서: 세러모니 → PB → 러닝화 (이슈 #206). LaunchedEffect는 첫 구성 때도 돌아 iOS onAppear를 겸한다
    // 포그라운드 복귀·당겨서 새로고침으로 단계가 오르면 홈이 이미 떠 있어 onAppear가 다시 불리지 않는다 —
    // 단계 변화에도 같은 기록·백업·세러모니를 건다 (이슈 #60)
    LaunchedEffect(growth.stage) { syncStage(growth.stage, runs) }
    // 베스트 에포트 백필이 끝나면(남은 개수 0) 미뤄 둔 PB 감지를 다시 건다 (이슈 #166)
    LaunchedEffect(bestEffortPending) {
        if (bestEffortPending == 0) {
            checkNewPBs(runs)
            checkNewRunsForShoe(runs)   // 백필을 기다리던 러닝화 팝업 — PB가 떴으면 그 뒤로 미뤄진다
        }
    }
    // 포그라운드 복귀·당겨서 새로고침으로 새 러닝이 들어오면 홈이 이미 떠 있어 onAppear가 다시 불리지 않는다 (이슈 #206)
    LaunchedEffect(runs.map { it.id }) {
        syncStage(growth.stage, runs)   // 세러모니가 먼저 — 같은 갱신에서 단계가 올랐으면 팝업이 양보한다
        checkNewRunsForShoe(runs)
    }

    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        // 고정 헤더 — 아래 스크롤 본문이 헤더·상태바 뒤로 비치지 않게 바탕을 깔고 위에 둔다 (이슈 #211)
        Header(showsCollection = runs.isNotEmpty(), onOpenCollection = onOpenCollection, onOpenSettings = onOpenSettings)

        if (runs.isEmpty()) {
            FirstLaunchBody(growth, isDemo, onCollect = { showsCeremony = true })
        } else {
            val loadedAir = (airState as? AirQualityStore.State.Loaded)?.quality
            /// 스토어의 위치·네트워크 상태를 엔진이 아는 값으로 접는다 (엔진은 둘 다 모른다)
            val weatherInput = when (val s = weatherState) {
                WeatherStore.State.Idle, WeatherStore.State.Loading -> TodayVerdictEngine.WeatherInput.loading
                is WeatherStore.State.Loaded ->
                    TodayVerdictEngine.WeatherInput.current(s.weather, RunWindowEngine.bestWindow(s.weather.hourly, now))
                WeatherStore.State.Denied -> TodayVerdictEngine.WeatherInput.denied
                WeatherStore.State.Unavailable -> TodayVerdictEngine.WeatherInput.unavailable
            }
            val battery = vitals?.let { BatteryEngine.compute(it, runs, now, zone) }
            /// 주간 처방 — 리포트 탭과 같은 방식으로 만든다 (목표 레이스가 없으면 nil)
            val guide: TrainingGuide? = RaceDistance.fromRawValue(raceGoalRaw)?.let { race ->
                TrainingGuideEngine(now = now, zone = zone, level = level)
                    .guide(runs, race = race,
                           goalSec = if (raceGoalSec > 0) raceGoalSec.toDouble() else null,
                           raceDate = if (raceDateRaw > 0) instantSince1970(raceDateRaw) else null,
                           batteryTone = battery?.tone)
            }
            val verdict = TodayVerdictEngine.verdict(
                runs = runs, battery = battery, weather = weatherInput, guide = guide,
                hasRaceGoal = RaceDistance.fromRawValue(raceGoalRaw) != null,
                weeklyGoal = weeklyGoal, level = level,
                air = loadedAir?.let { AirQualityEngine.representativeGrade(it) },
                now = now, zone = zone)
            /// 실데이터 승급 후보. 거절한 지 4주가 안 지났으면 다시 묻지 않는다.
            val promotion = if (promotionDeclinedAtRaw > 0
                && now.timeIntervalSince1970 - promotionDeclinedAtRaw < 28 * 86_400) null
            else LevelEngine.promotionCandidate(level, runs, now)
            val last = runs.maxByOrNull { it.start }
            /// 목표 대회 항목 — 목록에 없거나 대회일이 지났으면(entries가 이미 뺀다) nil → 카드 미노출
            val targetRace = (raceState as? RaceStore.State.Loaded)?.file?.takeIf { targetRaceID != 0 }?.let { file ->
                RaceEngine.entries(file.races.filter { it.id == targetRaceID }, now).firstOrNull()
            }
            /// 노출할 결산 카드 — 날짜·닫힘 판정은 엔진, 기록 3회 미만 기간은 열어 봐야 빈 화면이라 뺀다
            val recapPrompts = RecapEngine.promptKinds(now, zone, recapDismissedMonth, recapDismissedYear)
                .filter { RecapEngine.hasEnoughRuns(it, runs, zone) }
            /// 열어 보거나 닫으면 그 기간 키를 남긴다 — 다음 진입부터 카드가 뜨지 않는다
            fun dismissRecap(period: RecapPeriod) {
                val key = RecapEngine.dismissKey(period, zone)
                when (period) {
                    is RecapPeriod.month -> recapDismissedMonth = key
                    is RecapPeriod.year -> recapDismissedYear = key
                }
            }

            /// 네 줄의 목적지 — 재료를 만든 화면으로 보낸다 (기획서 v0.8 §6).
            /// 배터리·권장 세션은 둘 다 리포트 탭의 카드라 같은 곳으로 간다.
            fun tap(kind: TodayVerdict.Line.Kind) {
                when (kind) {
                    TodayVerdict.Line.Kind.battery, TodayVerdict.Line.Kind.session -> onSelectReport()
                    TodayVerdict.Line.Kind.weather -> when (weather.state.value) {
                        // 권한을 거부한 상태에서는 앱 안에서 다시 물을 수 없다 — 설정으로 보낸다
                        // (Android: 위치 서비스가 꺼졌으면 위치 설정, 아니면 앱 권한 설정)
                        WeatherStore.State.Denied -> context.startActivity(
                            if (weather.servicesDisabled.value) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)))
                        WeatherStore.State.Unavailable -> {
                            // 조회 실패 — 열어 봐야 같은 실패 카드라 그 자리에서 다시 불러온다.
                            // 대기질은 새 좌표가 오면 LaunchedEffect(coordinate)가 따라 채운다
                            if (retryingWeather) return
                            retryingWeather = true
                            weatherRetryFailed = false
                            container.scope.launch {
                                weather.refresh()
                                retryingWeather = false
                                if (weather.state.value is WeatherStore.State.Unavailable) weatherRetryFailed = true
                            }
                        }
                        else -> onOpenToday()
                    }
                    TodayVerdict.Line.Kind.recovery -> last?.let { onOpenSession(it, null) }
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    // 건강 데이터와, 위치부터 다시 잡은 날씨·대기질을 함께 갱신한다.
                    // 대기질은 새 위치 결론이 재료라 날씨 뒤에 순서대로, 건강 데이터만 병렬로.
                    // (Android: 재구성에 취소되지 않도록 앱 스코프에서 돌린다 — iOS의 비구조 Task에 대응)
                    isRefreshing = true
                    container.scope.launch {
                        val healthReload = async { health.load() }
                        weather.refresh()
                        weather.coordinate.value?.let { airQuality.refresh(it.lat, it.lon) }
                        healthReload.await()
                        isRefreshing = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                // 시안(1f) 216보다 작게 — 아래 카드가 첫 화면에 더 올라오도록 새·단계 영역을 줄였다.
                // 승급 카드가 뜨면 한 번 더 줄여 카드 자리를 만든다 (시안 1h)
                val birdSize = if (promotion == null) 152.dp else 124.dp
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BirdView(growth.stage, Modifier.size(birdSize), isSulky = growth.isSulky)
                    StageName(growth)
                    XpGauge(growth.progress, Modifier.padding(top = 10.dp))
                    Box(Modifier.padding(top = 8.dp)) { XpText(growth, isDemo, onCollect = { showsCeremony = true }) }

                    if (promotion != null) {
                        PromotionCard(
                            promotion, now, zone,
                            onAccept = {
                                levelRaw = promotion.target.rawValue
                                ProgressSnapshot.markLocalChanged(settings, Instant.now())
                                // 수락했으면 거절 기록은 의미가 없다 — 지워 둔다
                                promotionDeclinedAtRaw = 0.0
                                scheduleBackup()
                            },
                            onDecline = { promotionDeclinedAtRaw = now.timeIntervalSince1970 },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }

                    if (verdict != null) {
                        VerdictCard(verdict, battery, weatherInput, loadedAir, retryingWeather, weatherRetryFailed,
                                    now, zone, onTap = ::tap, modifier = Modifier.padding(top = 14.dp))
                    }

                    // 매일·매주 바뀌는 칩을 러닝화 카드보다 먼저 — 러닝화 카드는 켤레 수만큼 길어져
                    // 아래에 두면 칩이 첫 화면 밖으로 밀린다
                    // 기록 줄이 두 줄로 넘어가도 두 칩 높이를 맞춘다
                    EqualHeightRow(
                        spacing = 10.dp, modifier = Modifier.padding(top = 10.dp),
                        cells = listOfNotNull(
                            last?.let { run -> @Composable { LastRunChip(run, now, zone) { onOpenSession(run, null) } } },
                            @Composable { WeeklyGoalChip(weekRunCount(runs, now, zone), weeklyGoal) },
                        ),
                    )

                    // 러닝화 (이슈 #206) — 신발이 없으면 '다시 보지 않기' 전까지만 등록 권유로 보인다
                    if (shoeList.isNotEmpty() || !shoePromptOptOut) {
                        HomeShoeCard(
                            shoeList, defaultShoeID, mileage = { shoes.mileage(it, runs) },
                            onEdit = { shoe, isNew ->
                                editingShoeIsNew = isNew
                                editingShoe = shoe
                            },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    // 목표 대회 (이슈 #172) — 대회 목록에 있고 대회일이 지나지 않았을 때만
                    if (targetRace != null) {
                        TargetRaceCard(targetRace, Modifier.padding(top = 10.dp)) { onOpenRace(targetRace) }
                    }

                    for (period in recapPrompts) {
                        RecapPromptCard(
                            title = recapPromptTitle(period, now, zone),
                            subtitle = RecapEngine.periodLabel(period, zone) + " 결산이 준비됐어요",
                            onOpen = {
                                dismissRecap(period)
                                onOpenRecap(period)
                            },
                            onDismiss = { dismissRecap(period) },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            // 러닝화 등록·편집 (이슈 #206) — 설정과 같은 시트
            editingShoe?.let { shoe ->
                ShoeEditSheet(
                    shoe = shoe, isNew = editingShoeIsNew,
                    isDefault = if (editingShoeIsNew) defaultShoeID == null else defaultShoeID == shoe.id,
                    onSave = { s, isDefault -> shoes.save(s, isDefault, runs) },
                    onDelete = { shoes.remove(shoe) },
                    onDismiss = { editingShoe = null },
                )
            }
        }
    }

    if (showsCeremony) {
        val earned = pendingBird(runs)
        // 세러모니 → PB → 러닝화 순서 (이슈 #206)
        val close = {
            showsCeremony = false
            checkNewPBs(runs)
            checkNewRunsForShoe(runs)
        }
        val later = {
            deferredSpeciesRaw = earned.species.rawValue
            close()
        }
        // (Android: 뒤로가기는 "조금 더 키우기"와 같다 — iOS 전체 화면 커버는 스와이프로 닫히지 않는다)
        Dialog(
            onDismissRequest = later,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            CeremonyScreen(
                species = earned.species,
                goalLabel = earned.label,
                cycleStartedAt = cycleStartedAt,
                cycleGoal = cycleGoal,
                cycleGoalSeconds = cycleGoalSec,
                currentGoal = RaceDistance.fromRawValue(raceGoalRaw),
                currentGoalSeconds = raceGoalSec,
                onLater = later,
                onFinish = { newGoal, newSeconds ->
                    startNewCycle(runs, newGoal, newSeconds, Instant.now()).also { if (it) close() }
                },
            )
            // 세러모니는 저장 실패 시 닫히지 않으므로 알림도 그 위에 건다 — 홈에 걸면 커버에 가려진다
            if (showsCollectFailed) {
                AlertDialog(
                    onDismissRequest = { showsCollectFailed = false },
                    confirmButton = { TextButton({ showsCollectFailed = false }) { Text("확인") } },
                    title = { Text("도감에 담지 못했어요") },
                    text = { Text("저장 공간을 확인한 뒤 다시 시도해 주세요. 새는 그대로 기다리고 있어요.") },
                )
            }
        }
    }

    if (showsPBCongrats) {
        PBCongratsSheet(newPBs) {
            showsPBCongrats = false
            checkNewRunsForShoe(runs)
        }
    }

    if (showsShoePrompt) {
        // 닫히면(확인·스와이프) 마지막 카드의 러닝까지 물어본 것으로 기준을 올린다 —
        // 팝업에 밀려 미뤄 둔 PB 축하가 있으면 이어서 다시 건다 (이슈 #206)
        RunShoePromptSheet(shoePromptRuns, onOptOut = { shoePromptOptOut = true }) {
            showsShoePrompt = false
            val latest = shoePromptRuns.lastOrNull()?.start?.timeIntervalSince1970
            if (latest != null && latest > shoePromptedThrough) shoePromptedThrough = latest
            checkNewPBs(runs)
        }
    }
}

// MARK: - 헤더

@Composable
private fun Header(showsCollection: Boolean, onOpenCollection: () -> Unit, onOpenSettings: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().background(RR.bg).padding(start = 20.dp, end = 20.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("런미새", style = RR.display(18.sp), color = RR.text)
        Spacer(Modifier.weight(1f))
        if (showsCollection) {
            Row(
                Modifier.height(40.dp).clip(shape).background(RR.surface).border(1.dp, RR.line, shape)
                    .clickable(role = Role.Button, onClick = onOpenCollection).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named("book.closed"), null, tint = RR.text, modifier = Modifier.size(17.dp))
                Text("도감", style = ts(15f, FontWeight.SemiBold), color = RR.text)
            }
        }
        // 설정 진입 — 리포트 탭까지 가지 않아도 홈에서 바로 연다 (도감과 같은 필 스타일)
        Box(
            Modifier.minimumInteractiveComponentSize().size(40.dp).clip(shape).background(RR.surface)
                .border(1.dp, RR.line, shape).clickable(role = Role.Button, onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RRIcons.named("gearshape.fill"), "설정", tint = RR.text, modifier = Modifier.size(20.dp))
        }
    }
}

// MARK: - 첫 실행 (시안 1g)

/// 기록이 없을 때 — 알과 안내 문장만. 브리핑·칩은 통째로 감춘다 (미노출 가드).
@Composable
private fun FirstLaunchBody(growth: GrowthState, isDemo: Boolean, onCollect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(12.dp))
        BirdView(growth.stage, Modifier.size(212.dp), isSulky = false)
        Box(Modifier.padding(top = 6.dp)) { StageName(growth) }
        XpGauge(growth.progress, Modifier.padding(top = 14.dp))
        Box(Modifier.padding(top = 10.dp)) { XpText(growth, isDemo, onCollect) }
        Text(
            "첫 러닝을 기다리고 있어요. 알도 기다리고 있습니다.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, textAlign = TextAlign.Center),
            color = RR.text2,
            modifier = Modifier.padding(top = 26.dp).widthIn(max = 300.dp).fillMaxWidth().rrCard().padding(16.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

// MARK: - 스테이지 텍스트

/// "{이름} · {N}단계[ · 시무룩]" — 이름만 디스플레이 서체, 나머지는 본문 서체 한 줄.
/// 여기서 쓰는 이름은 지금 단계 라벨이다 (종 이름 — 참새·제비 — 은 §5 도감 범위).
@Composable
private fun StageName(growth: GrowthState) {
    Row {
        Text(growth.stage.label, style = RR.display(23.sp), color = RR.text, modifier = Modifier.alignByBaseline())
        Text(
            " · ${growth.stage.rawValue}단계" + (if (growth.isSulky) " · 시무룩" else ""),
            style = ts(13f, FontWeight.SemiBold), color = RR.text3, modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun XpText(growth: GrowthState, isDemo: Boolean, onCollect: () -> Unit) {
    // 성조면 수집 버튼 — "조금 더 키우기"로 미뤄도 여기서 언제든 다시 연다.
    // 데모는 수집을 저장하지 않으므로(syncStage와 같은 가드) 문구만 둔다
    val toNext = growth.xpToNextStage
    if (toNext == null && !isDemo) {
        Text(
            "도감에 넣기", style = ts(13f, FontWeight.Bold), color = RR.onBrand,
            modifier = Modifier.minimumInteractiveComponentSize().clip(CircleShape).background(RR.brand)
                .clickable(role = Role.Button, onClick = onCollect).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        Text(
            if (toNext != null) "다음 단계까지 $toNext XP" else "성조 도달 — 세러모니가 기다려요",
            style = mono(11.5.sp, FontWeight.SemiBold).copy(letterSpacing = 0.46.sp),  // 시안 letter-spacing .04em × 11.5px
            color = RR.text2,
        )
    }
}

// MARK: - 칩 2개

/// SwiftUI HStack + `.fixedSize(vertical:)` + `.frame(maxHeight: .infinity)` 대응 — 칸을 가장 높은 칸에 맞춘다.
/// (Android: IntrinsicSize는 BatteryGauge의 BoxWithConstraints가 지원하지 않아, 한 번 재 보고 그 높이로 다시 그린다)
@Composable
private fun EqualHeightRow(spacing: Dp, cells: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val gap = spacing.roundToPx()
        val cellW = ((constraints.maxWidth - gap * (cells.size - 1)) / cells.size).coerceAtLeast(0)
        val height = cells.indices.maxOf { i ->
            subcompose("probe$i", cells[i]).maxOf { it.measure(Constraints(minWidth = cellW, maxWidth = cellW)).height }
        }
        val placeables = cells.indices.map { i -> subcompose("cell$i", cells[i]).map { it.measure(Constraints.fixed(cellW, height)) } }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, ps -> ps.forEach { it.place(i * (cellW + gap), 0) } }
        }
    }
}

@Composable
private fun LastRunChip(run: RunSummary, now: Instant, zone: ZoneId, onClick: () -> Unit) {
    Row(
        Modifier.rrCard().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(relativeRunLabel(run, now, zone), style = ts(11f), color = RR.text3)
            // 반쪽 폭 칩이라 '10.0km · 6′06″/km'가 넘친다 — 줄바꿈 대신 한 줄에 맞춰 살짝 줄인다
            FitText(runValueLine(run), ts(14.5f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), RR.text, 0.8f)
        }
        Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun WeeklyGoalChip(done: Int, weeklyGoal: Int) {
    Column(
        Modifier.rrCard().padding(horizontal = 14.dp, vertical = 13.dp),
        // iOS frame(maxHeight: .infinity, alignment: .leading) — 늘어난 칩 안에서 세로 가운데
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Text("이번 주 목표", style = ts(11f), color = RR.text3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$done / ${weeklyGoal}회", style = ts(14.5f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                 color = RR.text)
            GoalDots(total = weeklyGoal, done = done)
        }
    }
}

/// "어제 러닝" / "7일 전 러닝" — 시안 1f는 상대 표기를 쓴다
private fun relativeRunLabel(run: RunSummary, now: Instant, zone: ZoneId): String {
    val days = ChronoUnit.DAYS.between(run.start.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
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

/// 주간 목표 칩 횟수 — 성장 엔진 주간 목표 판정과 같은 1km 이상 기준
private fun weekRunCount(runs: List<RunSummary>, now: Instant, zone: ZoneId): Int {
    // ISO 주(월요일 시작) — iOS Calendar(.iso8601) + .current
    val weekStart = now.atZone(zone).toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant()
    return runs.count { it.start >= weekStart && it.start <= now && GrowthEngine.countsAsCompletedRun(it) }
}

/// "지난달 결산 보기" / "올해 결산 보기" / "지난해 결산 보기"(1월 1~7일)
private fun recapPromptTitle(period: RecapPeriod, now: Instant, zone: ZoneId): String = when (period) {
    is RecapPeriod.month -> "지난달 결산 보기"
    is RecapPeriod.year ->
        if (period.date.atZone(zone).year == now.atZone(zone).year) "올해 결산 보기" else "지난해 결산 보기"
}

// MARK: - 하위 뷰

/// 시트 하단 브랜드 버튼 — PB 축하·러닝화 팝업 공용 (16 bold, 높이 52, r14)
@Composable
private fun SheetButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(shape).background(RR.brand)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = ts(16f, FontWeight.Bold), color = RR.onBrand)
    }
}

/// PB 축하 시트 (이슈 #21) — 새 기록이 잡힌 홈 진입에서 한 번만 뜬다.
/// 메달 색은 성장기 PB 목록과 같은 매핑(RR.medalColor)을 쓴다.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PBCongratsSheet(entries: List<PersonalRecords.Entry>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = RR.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(RRIcons.named("medal.fill"), null, tint = RR.medalColor(entries.firstOrNull()?.label ?: ""),
                 modifier = Modifier.padding(top = 34.dp).size(48.dp))
            Text("새 기록입니다!", style = RR.display(26.sp), color = RR.text, modifier = Modifier.padding(top = 14.dp))
            Text("최고 기록을 갈아치우셨네요. 성장기에 바로 새겨 두었습니다.", style = ts(13.5f), color = RR.text2,
                 modifier = Modifier.padding(top = 6.dp))

            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp).fillMaxWidth().rrCard()) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RRIcons.named("medal.fill"), null, tint = RR.medalColor(entry.label),
                             modifier = Modifier.width(24.dp).height(20.dp))
                        Text(entry.label, style = mono(13.sp, FontWeight.Bold), color = RR.text)
                        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                        Text(Format.duration(entry.timeSec), style = mono(17.sp, FontWeight.Bold), color = RR.brand)
                    }
                    if (index < entries.size - 1) {
                        HorizontalDivider(Modifier.padding(start = 52.dp), thickness = Dp.Hairline, color = RR.line)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            SheetButton("계속 달리기", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 18.dp)) {
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/// 결산 리캡 진입 카드 (이슈 #167) — 판단 카드 아래, 칩 위. 탭하면 결산 화면, X는 이번 기간 닫기
@Composable
private fun RecapPromptCard(title: String, subtitle: String, onOpen: () -> Unit, onDismiss: () -> Unit,
                            modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().rrCard().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile("sparkles", 19.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = ts(14.5f, FontWeight.Bold), color = RR.text)
                Text(subtitle, style = ts(11.5f), color = RR.text3)
            }
            Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(14.dp))
        }
        Box(
            Modifier.minimumInteractiveComponentSize().size(28.dp).clickable(role = Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RRIcons.named("xmark"), "결산 카드 닫기", tint = RR.text3, modifier = Modifier.size(14.dp))
        }
    }
}

/// 34×34 brandSoft 바탕의 브랜드 아이콘 — 결산·목표 대회 카드의 머리 아이콘
@Composable
private fun IconTile(symbol: String, iconSize: Dp) {
    Box(
        Modifier.size(34.dp).background(RR.brandSoft, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(RRIcons.named(symbol), null, tint = RR.brand, modifier = Modifier.size(iconSize))
    }
}

/// 러닝화 카드 (이슈 #206) — 판단 카드 아래, 목표 대회 카드 위. 은퇴하지 않은 신발마다 한 줄씩
/// (설정의 러닝화 행과 같은 모양) + 끝에 '러닝화 추가'. 교체 기준을 넘은 신발은 그 행에서 주의 문구를 낸다.
/// 신발이 하나도 없으면 등록 권유 한 줄로 바뀐다 — '다시 보지 않기' 뒤에는 홈이 카드 자체를 걸지 않는다
@Composable
private fun HomeShoeCard(
    shoes: List<Shoe>,
    defaultShoeID: String?,
    mileage: (Shoe) -> Double,
    /// 편집할 신발과 신규 여부 — 시트는 홈이 띄운다
    onEdit: (Shoe, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newShoe = { Shoe(name = "", createdAt = Instant.now()) }
    Column(modifier.fillMaxWidth().rrCard()) {
        Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) { Eyebrow("러닝화") }
        if (shoes.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("러닝화를 등록하면 누적 거리로 교체 시점을 알려드려요", style = ts(13.5f), color = RR.text2,
                     modifier = Modifier.weight(1f))
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(RR.brand)
                        .clickable(role = Role.Button) { onEdit(newShoe(), true) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("등록", style = ts(13.5f, FontWeight.Bold), color = RR.onBrand)
                }
            }
        } else {
            for (shoe in shoes.filter { !it.isRetired }) {
                ShoeRow(shoe, isDefault = defaultShoeID == shoe.id, mileage = mileage(shoe)) { onEdit(shoe, false) }
                HorizontalDivider(Modifier.padding(start = 68.dp), thickness = Dp.Hairline, color = RR.line)
            }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(newShoe(), true) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named("plus.circle.fill"), null, tint = RR.brand, modifier = Modifier.size(19.dp))
                Text("러닝화 추가", style = ts(14f, FontWeight.SemiBold), color = RR.brand)
            }
        }
    }
}

@Composable
private fun ShoeRow(shoe: Shoe, isDefault: Boolean, mileage: Double, onClick: () -> Unit) {
    val progress = ShoeEngine.progress(mileage, shoe.replaceKm)
    val overshoot = ShoeEngine.overshoot(mileage, shoe.replaceKm)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShoeImage(shoe, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shoe.name, style = ts(15f, FontWeight.SemiBold), color = RR.text, maxLines = 1,
                     modifier = Modifier.weight(1f, fill = false))
                if (isDefault) {
                    Text("기본", style = ts(10f, FontWeight.SemiBold), color = RR.brand,
                         modifier = Modifier.background(RR.brandSoft, RoundedCornerShape(4.dp))
                             .padding(horizontal = 6.dp, vertical = 2.5.dp))
                }
            }
            Text("누적 ${mileage.swiftRoundedInt()} km / ${shoe.replaceKm.toInt()} km", style = ts(12.5f), color = RR.text2)
            Box(Modifier.fillMaxWidth().height(4.dp).background(RR.barFill, CircleShape)) {
                Box(Modifier.fillMaxWidth(progress.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                        .background(ShoeEngine.tone(progress).color, CircleShape))
                // 기준을 넘긴 몫은 막대 끝에 과부하 색으로 덧칠한다 — 꽉 찬 막대만으로는 초과가 안 보인다
                Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(overshoot.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                        .background(RRTone.overload.color, CircleShape))
            }
            if (ShoeEngine.needsReplacement(shoe, mileage)) {
                Text("교체를 생각해 볼 때예요", style = ts(11.5f, FontWeight.SemiBold), color = RRTone.caution.color)
            }
        }
        Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(14.dp))
    }
}

/// 러닝 후 러닝화 묻기 팝업 (이슈 #206) — 새 러닝을 한 장씩 넘기며 신은 러닝화를 고른다.
/// 각 장은 세션 상세 화면 그대로(지도·지표·구간·심박 존)이고, 러닝화 행 자리에 고르는 목록이 들어간다.
/// 자동 배정(기본 신발)이 먼저 돌아 있어 그 신발이 체크된 채로 열린다 — 그대로 닫으면 기본 신발로 남는다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunShoePromptSheet(
    /// 띄울 때 고정한 새 러닝 — 오래된 순
    runs: List<RunSummary>,
    onOptOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { runs.size }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = RR.bg, dragHandle = null) {
        Column(Modifier.fillMaxHeight()) {
            Box(Modifier.weight(1f)) {
                HorizontalPager(pager, key = { runs[it].id }) { index ->
                    SessionDetailScreen(runs[index], onShoePromptOptOut = onOptOut)
                }
                if (runs.size > 1) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                            .background(RR.surface2, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(runs.size) { i ->
                            Box(Modifier.size(7.dp).background(if (i == pager.currentPage) RR.text else RR.text3, CircleShape))
                        }
                    }
                }
            }
            val hasNext = pager.currentPage < runs.size - 1
            SheetButton(if (hasNext) "다음" else "확인", Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 18.dp)) {
                if (hasNext) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                else scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/// 목표 대회 카드 (이슈 #172) — 판단 카드 아래, 결산 카드 위. 탭하면 대회 상세
@Composable
private fun TargetRaceCard(entry: RaceEngine.Entry, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth().rrCard().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile("flag.checkered", 19.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("목표 대회 ${RaceFormat.dDay(entry.dDay)} · ${entry.race.name}", style = ts(14.5f, FontWeight.Bold),
                 color = RR.text, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(RaceFormat.fullDate(entry.raceDate), style = ts(11.5f), color = RR.text3)
                RegisterBadge(entry.status)
            }
        }
        Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(14.dp))
    }
}

/// 오늘의 판단 카드 (기획서 v0.8 §6) — 판정 한 줄 + 그 판정의 재료 네 줄.
///
/// 위트는 제목줄이 맡는다 (브리핑 카드가 하던 몫). 재료 줄은 값이 있으면 그대로 보여주고,
/// 없으면 숫자를 지어내지 않고 무엇을 하면 켜지는지만 말한다 — 판정·문구는 전부
/// `TodayVerdictEngine`이 정하고 여기서는 색과 목적지만 붙인다.
@Composable
private fun VerdictCard(
    verdict: TodayVerdict,
    /// 타일 그림의 재료 — 문구는 전부 엔진이 낸 것을 쓰고, 여기서는 같은 값을 그림으로 한 번 더 보여준다
    battery: BatteryReport?,
    weather: TodayVerdictEngine.WeatherInput,
    /// 날씨 타일에 얹는 미세·초미세 등급 요약 — 상세 수치는 '오늘' 화면 몫
    air: AirQuality?,
    /// 조회 실패 타일을 눌러 다시 불러오는 중
    retryingWeather: Boolean,
    /// 다시 불러오기도 실패했는지 — 접근성 알림용
    retryFailed: Boolean,
    now: Instant,
    zone: ZoneId,
    onTap: (TodayVerdict.Line.Kind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().rrCard().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp)) {
        // 배터리가 없으면 판정 자체가 없다 — 배지를 감추고 중립 문구만 남긴다
        verdict.tone?.let { tone ->
            Box(Modifier.padding(bottom = 9.dp)) { ToneBadge(tone, label = verdict.badgeLabel) }
        }
        Text(verdict.headline, style = RR.display(19.sp), color = RR.text, modifier = Modifier.fillMaxWidth())

        // 배터리·날씨는 눈으로 먼저 읽히는 값이라 글자 대신 타일 두 장으로 낸다.
        // 남은 두 줄(권장 세션·회복 경과)은 문장이 곧 값이라 그대로 한 줄씩 둔다
        EqualHeightRow(
            spacing = 9.dp, modifier = Modifier.padding(top = 13.dp),
            cells = listOf(
                @Composable { VerdictTile(verdict.battery, onTap) { BatteryArt(battery, verdict.battery) } },
                @Composable {
                    VerdictTile(verdict.weather, onTap) {
                        WeatherArt(weather, verdict.weather, air, retryingWeather, retryFailed, now, zone)
                    }
                },
            ),
        )

        HorizontalDivider(Modifier.padding(top = 13.dp), thickness = Dp.Hairline, color = RR.line)
        VerdictRow(verdict.session, onTap)
        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
        VerdictRow(verdict.recovery, onTap)
    }
}

@Composable
private fun VerdictTile(line: TodayVerdict.Line, onTap: (TodayVerdict.Line.Kind) -> Unit, art: @Composable () -> Unit) {
    // 최소 높이만 주고 위로 붙인다 — 같은 줄의 높은 쪽에 맞춰 늘어나는 건 EqualHeightRow 몫
    Column(
        Modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(10.dp)).background(RR.surface2)
            .clickable { onTap(line.kind) }.padding(start = 12.dp, top = 11.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line.label, style = ts(11.5f), color = RR.text3, modifier = Modifier.weight(1f))
            Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(11.dp))
        }
        art()
    }
}

/// 잔량 막대 + 큰 숫자 — 배터리는 "얼마나 남았나"가 한눈에 들어와야 하는 값이다
@Composable
private fun BatteryArt(battery: BatteryReport?, line: TodayVerdict.Line) {
    if (battery == null) {
        HintArt("applewatch", line)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${battery.level}", style = RR.numeral(25.sp), color = battery.tone.color, modifier = Modifier.alignByBaseline())
            FitText(battery.statusLabel, ts(11.5f, FontWeight.SemiBold), RR.text2, 0.75f, Modifier.alignByBaseline())
        }
        // 리포트 탭과 같은 게이지를 그대로 쓴다 — 칸 수로 잔량이 먼저 읽힌다
        BatteryGauge(battery.level)
    }
}

/// 하늘 상태 아이콘 + 체감온도 + 복장 — '오늘' 화면의 세 카드를 한 장으로 줄인 요약
@Composable
private fun WeatherArt(
    weather: TodayVerdictEngine.WeatherInput,
    line: TodayVerdict.Line,
    air: AirQuality?,
    retryingWeather: Boolean,
    retryFailed: Boolean,
    now: Instant,
    zone: ZoneId,
) {
    when (weather) {
        is TodayVerdictEngine.WeatherInput.current -> {
            val current = weather.weather
            val parts = TodayVerdictEngine.weatherParts(current, now, zone)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    // (Android: Material Symbols는 단색이라 팔레트 두 색 중 tint만 칠한다)
                    WeatherCondition.of(current.weatherCode)?.let { condition ->
                        Icon(RRIcons.named(condition.symbol), null, tint = condition.tint,
                             modifier = Modifier.size(24.dp).alignBy { it.measuredHeight })
                    }
                    Text("${current.apparentC.swiftRoundedInt()}", style = RR.numeral(25.sp), color = RR.text,
                         modifier = Modifier.alignByBaseline())
                    Text("°C 체감", style = ts(10.5f), color = RR.text3, modifier = Modifier.alignByBaseline())
                }
                parts.outfit?.let { outfit ->
                    FitText(if (parts.raining) "비 · $outfit" else outfit, ts(12f, FontWeight.SemiBold), RR.text2, 0.72f)
                }
                // 달리기 좋은 시간 (이슈 #173) — 추천이 없으면 줄을 내지 않는다
                line.caption?.let { caption ->
                    FitText(caption, ts(11.5f, FontWeight.SemiBold), RRTone.improving.color, 0.72f)
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
        TodayVerdictEngine.WeatherInput.loading -> HintArt("arrow.triangle.2.circlepath", line)
        TodayVerdictEngine.WeatherInput.denied -> HintArt("location.slash", line)
        TodayVerdictEngine.WeatherInput.unavailable -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HintArt("icloud.slash", line)
            // 탭이 곧 재시도다 (HomeScreen.tap) — 그 사실을 타일에서 말해 준다
            Text(
                if (retryingWeather) "다시 불러오는 중…" else "눌러서 다시 불러오기",
                style = ts(12f, FontWeight.SemiBold), color = RR.brand,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (retryFailed && !retryingWeather) contentDescription = "날씨를 다시 불러오지 못했어요"
                },
            )
        }
    }
}

/// "미세 좋음" — 항목명은 보조색, 등급 문구는 등급 톤 색. 등급이 없으면 그리지 않는다
@Composable
private fun AirBadge(name: String, grade: AirGrade?) {
    if (grade == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, style = ts(10.5f), color = RR.text3, maxLines = 1)
        Text(grade.label, style = ts(10.5f, FontWeight.Bold), color = grade.tone.color, maxLines = 1)
    }
}

/// 값이 없는 타일 — 숫자 자리를 비워 두고 무엇을 하면 켜지는지만 말한다
@Composable
private fun HintArt(symbol: String, line: TodayVerdict.Line) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(RRIcons.named(symbol), null, tint = RR.text3, modifier = Modifier.size(24.dp))
        Text(lineText(line), style = TextStyle(fontSize = 12.sp, lineHeight = 16.5.sp), color = RR.text3,
             modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VerdictRow(line: TodayVerdict.Line, onTap: (TodayVerdict.Line.Kind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onTap(line.kind) }.padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.label, style = ts(11.5f), color = RR.text3, modifier = Modifier.width(66.dp))
        // 유도 문구는 값이 아니므로 한 단계 흐리게 — 값 줄만 톤 색을 입는다
        val color = if (line.content is TodayVerdict.Line.Content.hint) RR.text3 else line.tone?.color ?: RR.text
        FitText(lineText(line), ts(13.5f, FontWeight.SemiBold), color, 0.72f, Modifier.weight(1f))
        Icon(RRIcons.named("chevron.right"), null, tint = RR.text3, modifier = Modifier.size(12.dp))
    }
}

private fun lineText(line: TodayVerdict.Line): String = when (val c = line.content) {
    is TodayVerdict.Line.Content.value -> c.text
    is TodayVerdict.Line.Content.hint -> c.text
}

/// XP 게이지 — 시안 210×8, radius 4, surface2 바탕 + line 테두리, 안쪽 brand 채움
@Composable
private fun XpGauge(progress: Double, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0.0, 1.0).toFloat()
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.size(210.dp, 8.dp).clip(shape).background(RR.surface2).border(1.dp, RR.line, shape)) {
        Box(Modifier.fillMaxHeight().width(210.dp * clamped).background(RR.brand, shape))
    }
}

/// 주간 목표 점 — 달성 brand 채움 / 미달 surface2 + line 테두리 (시안 7×7 radius 4)
@Composable
private fun GoalDots(total: Int, done: Int) {
    val shape = RoundedCornerShape(4.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(maxOf(0, total)) { index ->
            val base = Modifier.size(7.dp).background(if (index < done) RR.brand else RR.surface2, shape)
            Box(if (index >= done) base.border(1.dp, RR.line, shape) else base)
        }
    }
}

/// 승급 제안 카드 (시안 1h) — 1회 노출, 거절하면 4주 뒤에 다시 묻는다.
/// 승급만 있고 강등은 없다 ("성장은 되돌리지 않는다", 기획서 §3).
@Composable
private fun PromotionCard(
    /// 승급 근거 (LevelEngine 판정 결과) — 제안 레벨과 근거별 본문 문장을 정한다
    evidence: PromotionEvidence,
    now: Instant,
    zone: ZoneId,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val buttonShape = RoundedCornerShape(9.dp)
    Column(
        modifier.fillMaxWidth().background(RR.surface, shape).border(1.5.dp, RR.brand, shape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("승급 제안", style = mono(10.5.sp, FontWeight.SemiBold).copy(letterSpacing = 1.26.sp),  // 시안 letter-spacing .12em × 10.5px
             color = RR.brand)

        // 시안 문구를 근거별 실제 값으로 채운 템플릿 — 레벨명만 볼드로 강조한다 (AnnotatedString 합성)
        val lead = when (evidence) {
            is PromotionEvidence.tenKmPace -> {
                val km = evidence.run.distanceKm ?: 10.0
                // 10km 환산 기록(분) — 엔진 판정식 durationSec / km × 10과 같은 값
                val tenKmMin = (evidence.run.durationSec / km * 10 / 60).swiftRoundedInt()
                "${Format.relativeWeek(evidence.run.start, now, zone)} ${Format.km(km)}km를 " +
                    "10km 환산 ${tenKmMin}분 페이스로 달리셨더라고요. "
            }
            is PromotionEvidence.halfFinish ->
                "${Format.relativeWeek(evidence.run.start, now, zone)} 하프 거리를 완주하셨더라고요. "
            is PromotionEvidence.fullUnder430 ->
                "${Format.relativeWeek(evidence.run.start, now, zone)} 풀 거리를 ${Format.duration(evidence.run.durationSec)}에 " +
                    "달리고 4주 월환산 ${Format.km(evidence.monthlyKm)}km — 런친놈 기준이에요. "
        }
        Text(
            buildAnnotatedString {
                append(lead + "리포트를 ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(evidence.target.label) }
                append(" 수준으로 올려드릴까요?")
            },
            style = TextStyle(fontSize = 14.5.sp, lineHeight = 22.5.sp),
            color = RR.text,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "좋아요, 승급할게요", style = ts(13.5f, FontWeight.Bold).copy(textAlign = TextAlign.Center), color = RR.onBrand,
                modifier = Modifier.weight(1f).clip(buttonShape).background(RR.brand)
                    .clickable(role = Role.Button, onClick = onAccept).padding(vertical = 12.dp),
            )
            Text(
                "지금은 괜찮아요", style = ts(13.5f, FontWeight.SemiBold).copy(textAlign = TextAlign.Center), color = RR.text,
                modifier = Modifier.weight(1f).clip(buttonShape).background(RR.surface).border(1.dp, RR.line, buttonShape)
                    .clickable(role = Role.Button, onClick = onDecline).padding(vertical = 12.dp),
            )
        }

        Text("괜찮다고 하시면 4주 동안 다시 묻지 않아요", style = ts(11f), color = RR.text3)
    }
}
