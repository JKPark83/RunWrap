package com.jkpark.runwrap.screen

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.NotificationScheduler
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.ReportCache
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.ReportSnapshot
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.store.DemoMode
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.store.appSupportDir
import com.jkpark.runwrap.store.disableTogglesIfDenied
import com.jkpark.runwrap.store.rescheduleWeekly
import com.jkpark.runwrap.ui.BirdTabIcon
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// 앱 루트 — 온보딩 완료 여부와 연결 상태에 따라 설문 / 로딩 / 오류 / 메인 탭 분기.
/// HealthStore는 RunWrapApp이 소유하고 environmentObject로 내려온다 (계획서 M8).
///
/// v0.7에서 순서가 바뀌었다: **설문이 먼저, 권한 요청이 나중**이다 (기획서 §2).
/// 설문은 자기 신고라 HealthKit이 필요 없고, 맥락(내 레벨·내 알)을 만든 뒤 권한을 요청해야
/// 수락률이 오른다. 그래서 분기 기준이 `health.state`가 아니라 레벨 저장값이다.
/// (Android: 스토어는 `LocalAppContainer`로 받는다. iOS RunWrapApp의 scenePhase 훅(포그라운드 재계산·백그라운드 백업)도
///  여기 `LifecycleResumeEffect`에 있다. 신규 설치 CloudKit 복원·복원 선택 시트·백업 병합 반영은 ios-only —
///  Auto Backup이 첫 실행 전에 파일째 복원하므로 기다릴 복원도, 물어볼 후보도 없다. 그래서 복원 불가 안내 캡슐도 없다.
///  권한 요청(HC·위치·알림)은 루트가 아니라 화면이 한다 — 루트는 load()만 부른다)
@Composable
fun RootView() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val health = container.health
    val weather = container.weather
    val settings = container.settings
    val healthState by health.state.collectAsState()
    val weatherState by weather.state.collectAsState()
    /// 온보딩 설문 완료 여부 — 빈 문자열이면 아직 레벨이 없다 (= 설문 미완료)
    val levelRaw by settings.rememberSetting(ProfileKey.levelV2, "")
    var didConnectHealth by settings.rememberSetting("didConnectHealth", false)
    /// v0.6 이하 사용자에게 재온보딩 사유를 한 번 알려준다
    var isReturningUser by rememberSaveable { mutableStateOf(false) }
    /// 스플래시 안전망 — 기동이 병적으로 늘어지면 홈부터 연다.
    /// 이때 날씨 타일은 "불러오는 중" 힌트로 남고, 로딩이 끝나는 즉시 값으로 바뀐다
    var splashTimedOut by remember { mutableStateOf(false) }

    /// 스플래시를 유지할 조건 — 건강 데이터가 로딩 중이거나, 날씨가 아직 결론이 없을 때.
    /// 건강 쪽 실패·미지원은 스플래시가 아니라 전용 안내 화면으로 보낸다.
    val weatherSettled = remember(weatherState) { weather.isSettled }
    val isBooting = when (healthState) {
        HealthStore.State.Idle, HealthStore.State.Loading -> true
        is HealthStore.State.Loaded -> !(weatherSettled || splashTimedOut)
        HealthStore.State.Unavailable, is HealthStore.State.Failed -> false
    }

    val branch = when {
        levelRaw.isEmpty() -> RootBranch.onboarding
        isBooting -> RootBranch.splash
        else -> when (healthState) {
            HealthStore.State.Unavailable -> RootBranch.unavailable
            is HealthStore.State.Failed -> RootBranch.failed
            is HealthStore.State.Loaded -> RootBranch.main
            // isBooting이 이미 걸러서 오지 않는 가지 — when 완전성용
            HealthStore.State.Idle, HealthStore.State.Loading -> RootBranch.splash
        }
    }

    // 스플래시 → 홈은 교차 페이드로 잇는다 — 런치 스크린류 화면의 관례
    AnimatedContent(
        targetState = branch,
        transitionSpec = { fadeIn(tween(350, easing = EaseOut)) togetherWith fadeOut(tween(350, easing = EaseOut)) },
        label = "root",
    ) { target ->
        when (target) {
            // 설문이 먼저다 — 설문은 자기 신고라 HealthKit 권한이 필요 없다 (기획서 §2)
            RootBranch.onboarding -> Box(Modifier.fillMaxSize()) {
                OnboardingFlowScreen()
                // 재온보딩 안내는 설문 위에 얹어서 한 번 보여준다.
                // 설문 화면의 인터페이스를 건드리지 않으려고 여기서 덮는다.
                if (isReturningUser) {
                    Notice("런미새가 새 단장을 했어요. 1분만 다시 알려주세요.",
                        onDismiss = { isReturningUser = false },
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            // 기동 로딩(건강 데이터 + 현재 위치 날씨)이 끝날 때까지 홈 노출을 미룬다
            RootBranch.splash -> SplashScreen()
            // (Android: iOS "HealthKit을 지원하는 iPhone이 필요합니다." — HC 미설치·업데이트 필요 기기)
            RootBranch.unavailable -> UnavailableView(
                icon = "heart.slash", title = "이 기기에서는 쓸 수 없어요",
                description = "Health Connect를 지원하는 기기가 필요합니다.",
            )
            RootBranch.failed -> UnavailableView(
                icon = "exclamationmark.triangle", title = "불러오지 못했어요",
                description = (healthState as? HealthStore.State.Failed)?.message ?: "",
                action = "다시 시도" to { container.scope.launch { health.load() } },
            )
            RootBranch.main -> MainTabs()
        }
    }

    // 기동 로딩은 앱 수명 스코프에서 돈다 — 액티비티가 다시 만들어져도 끊기지 않게 (AppContainer.scope 주석)
    LaunchedEffect(Unit) {
        detectReturningUser(settings, levelRaw.isEmpty()) { isReturningUser = true }
        if (levelRaw.isNotEmpty()) {
            container.scope.launch {
                // 온보딩을 마친 사용자는 바로 조회 (권한 시트는 이미 설문 끝에서 지났다)
                if (health.state.value == HealthStore.State.Idle) health.load()
                // 기존 사용자의 로컬 상태를 백업 대상으로 알린다 (이슈 #29)
                container.backup.backupIfChanged()
            }
            // 건강 데이터와 별개 트랙 — 위치 권한이 남아 있으면 다이얼로그가 스플래시 위에 뜬다
            container.scope.launch { weather.load() }
        }
    }
    LaunchedEffect(levelRaw.isEmpty()) {
        // 안전망 시계는 온보딩이 끝난 시점부터 잰다
        if (levelRaw.isEmpty()) return@LaunchedEffect
        // 취소는 타임아웃이 아니다 — delay가 끝까지 잤을 때만 안전망을 발동한다
        delay(SPLASH_TIMEOUT_MS)
        splashTimedOut = true
    }
    // 설문을 막 마친 직후 — 권한 시트가 끝났으니 데이터를 읽는다 (iOS onChange — 첫 값은 건너뛴다)
    var lastLevelRaw by rememberSaveable { mutableStateOf(levelRaw) }
    LaunchedEffect(levelRaw) {
        if (levelRaw == lastLevelRaw) return@LaunchedEffect
        lastLevelRaw = levelRaw
        if (levelRaw.isEmpty()) return@LaunchedEffect
        isReturningUser = false
        container.scope.launch { health.load() }
        container.scope.launch { weather.load() }
    }
    LaunchedEffect(healthState) {
        val loaded = healthState as? HealthStore.State.Loaded ?: return@LaunchedEffect
        didConnectHealth = true
        // 러닝화 자동 배정 (이슈 #171) — 렌더 중이 아니라 목록이 로드된 시점에.
        // 데모 모드면 스토어가 스스로 건너뛴다(합성 러닝 ID가 실제 shoes.json에 쌓이지 않게)
        container.shoes.syncAssignments(loaded.runs)
    }

    // iOS RunWrapApp·RootView의 scenePhase 훅 — 포그라운드 진입과 백그라운드 진입
    LifecycleResumeEffect(Unit) {
        container.scope.launch { refreshCacheAndReschedule(context, health, settings) }
        // 포그라운드 복귀 — 날씨가 낡았으면 다시 받아 수분 알람까지 재예약한다 (이슈 #69).
        // 기동 로딩 전·중이면 refresh()가 스스로 건너뛴다
        if (settings.string(ProfileKey.levelV2).orEmpty().isNotEmpty()) {
            container.scope.launch { weather.refreshIfStale() }
        }
        onPauseOrDispose {
            // 설정 변경(주간 목표·대회 목표 등)까지 훑어 담는 안전망 백업 —
            // 내용이 마지막 업로드와 같으면 아무것도 하지 않는다 (이슈 #29)
            container.backup.backupIfChanged()
        }
    }
}

/// 안전망 상한 — 위치 권한이 있으면 날씨가 홈보다 먼저다(요구사항). 정상 실패 경로는
/// 위치 실패·네트워크 타임아웃(WeatherClient 10초)이 이보다 먼저 결론을 내므로,
/// 이 값은 그마저 안 오는 병적 상황(권한 다이얼로그 방치 등) 전용이다
private const val SPLASH_TIMEOUT_MS = 20_000L

private enum class RootBranch { onboarding, splash, unavailable, failed, main }

/// 포그라운드 진입: 이미 로딩된 목록이 있으면 새로 고침 → 캐시 갱신 → 주간 알림 재예약.
/// 최초 기동은 RootView의 connect/load가 담당하므로 여기서는 건드리지 않는다.
/// 첫 로드가 실패(.failed)로 끝났으면 포그라운드 복귀 때 다시 시도한다 (이슈 #58).
/// 설정 앱에서 알림을 꺼 두고 돌아왔으면 켜진 알림 토글부터 끈다 (이슈 #94)
private suspend fun refreshCacheAndReschedule(context: Context, health: HealthStore,
                                              settings: SettingsStore) {
    NotificationScheduler.disableTogglesIfDenied(context)
    when (health.state.value) {
        is HealthStore.State.Loaded, is HealthStore.State.Failed -> health.load()
        else -> Unit
    }
    // 데모 수치는 캐시하지 않는다 — 주간 알림 본문으로 나가면 안 된다 (이슈 #44)
    val state = health.state.value
    if (state is HealthStore.State.Loaded && !DemoMode.isActive(settings)) {
        val dir = appSupportDir(context)
        if (state.runs.isNotEmpty()) {
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val level = RunnerLevel.fromRawValue(settings.string(ProfileKey.levelV2) ?: "") ?: RunnerLevel.beginner
            val report = ReportEngine(now = now).weeklyReport(from = state.runs, zone = zone)
            ReportCache.save(ReportSnapshot.make(report, state.runs, level, now, zone), dir)
        } else {
            // 기록이 비었으면(삭제·권한 회수) 옛 스냅샷이 알림으로 나가지 않게 지운다 (이슈 #61)
            ReportCache.clear(dir)
        }
    }
    NotificationScheduler.rescheduleWeekly(context)
}

/// v1 프로필을 갖고 있던 기존 사용자인지 판정한다.
/// v1 키는 한 번 읽고 지운다 — 다음 실행부터는 신규 사용자와 같은 경로를 탄다.
private fun detectReturningUser(settings: SettingsStore, levelEmpty: Boolean,
                                onReturning: () -> Unit) {
    val legacyKey = "profile.didSet"
    if (!levelEmpty || !settings.bool(legacyKey)) return
    onReturning()
    settings.remove(legacyKey)
}

/// 설문 위에 얹는 한 줄 안내 캡슐 — 재온보딩 안내. 탭하면 닫힌다
@Composable
private fun Notice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = 12.5.sp, textAlign = TextAlign.Center),
        color = RR.text2,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(RR.surface)
            .border(BorderStroke(1.dp, RR.line), RoundedCornerShape(percent = 50))
            // (Android: iOS 힌트 "탭하면 안내를 닫아요" — TalkBack은 "두 번 탭하여 ○○"로 읽어 동작 이름만 준다)
            .clickable(role = Role.Button, onClickLabel = "안내 닫기", onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    )
}

/// iOS `ContentUnavailableView` 대응 — 아이콘·제목·설명(+버튼)을 가운데 쌓는다
@Composable
private fun UnavailableView(icon: String, title: String, description: String,
                            action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().background(RR.bg).padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(RRIcons.named(icon), contentDescription = null, tint = RR.text3, modifier = Modifier.size(48.dp))
        Text(title, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            color = RR.text, modifier = Modifier.padding(top = 14.dp))
        Text(description, style = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center),
            color = RR.text2, modifier = Modifier.padding(top = 8.dp))
        if (action != null) {
            Text(action.first,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = RR.onBrand,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(RR.brand)
                    .clickable(role = Role.Button, onClick = action.second)
                    .padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/// 4탭 구조 — 홈 / 리포트 / 코스 / 대회 (기획서 v0.8 §6).
/// '오늘'은 탭에서 빠져 홈 판단 카드의 날씨 줄에서 여는 시트가 됐다 —
/// 날씨 한 줄이 홈으로 올라온 뒤로는 매일 탭을 하나 차지할 만큼 자주 볼 화면이 아니다.
/// (Android: 탭마다 NavigationStack 대신 NavHost 하나에 탭별 중첩 그래프를 두고,
///  탭을 바꿀 때 saveState/restoreState로 탭별 스택을 보존한다. 탭바는 iOS 탭바 모양을 직접 그린다)
@Composable
private fun MainTabs() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    /// 판단 카드의 배터리·권장 세션 줄이 리포트 탭으로 넘기려면 선택 탭을 여기서 쥐어야 한다
    var selection by rememberSaveable { mutableStateOf(Tab.home) }
    LaunchedEffect(destination) {
        Tab.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.hasRoute(tab.graph) } == true }
            ?.let { selection = it }
    }
    val select: (Tab) -> Unit = { tab ->
        selection = tab
        nav.navigate(tab.graphRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // iOS에서 시트였던 화면은 탭바까지 덮는다
    val showsTabBar = destination?.hierarchy?.none { d -> sheetRoutes.any { d.hasRoute(it) } } ?: true

    Column(Modifier.fillMaxSize().background(RR.bg)) {
        Box(Modifier.weight(1f)) { RootNavHost(nav, onSelectReport = { select(Tab.report) }) }
        if (showsTabBar) TabBar(selection, select)
    }
}

private val sheetRoutes: List<KClass<*>> = listOf(Routes.Today::class, Routes.Recap::class, Routes.Rediagnosis::class)

private enum class Tab(val label: String, val graph: KClass<*>, val graphRoute: Any) {
    home("홈", Routes.HomeGraph::class, Routes.HomeGraph),
    report("리포트", Routes.ReportGraph::class, Routes.ReportGraph),
    course("코스", Routes.CourseGraph::class, Routes.CourseGraph),
    race("대회", Routes.RacesGraph::class, Routes.RacesGraph);

    /// 새 아이콘은 SF Symbol이 아니라 직접 그린 벡터다 (BirdTabIcon 주석 참고)
    val icon: ImageVector
        get() = when (this) {
            home -> BirdTabIcon.image
            report -> RRIcons.named("figure.run")
            course -> RRIcons.named("map")
            race -> RRIcons.named("flag.checkered")
        }
}

/// iOS 기본 탭바 모양 — 표면색 + 위쪽 헤어라인, 49 높이, 아이콘 위·라벨 아래. 선택은 RR.brand(iOS `.tint`)
@Composable
private fun TabBar(selection: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(RR.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 0.5.dp, color = RR.line)
        Row(Modifier.fillMaxWidth().height(49.dp)) {
            for (tab in Tab.entries) {
                val tint = if (tab == selection) RR.brand else RR.text3
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = tab == selection, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium), color = tint)
                }
            }
        }
    }
}

/// 탭 4개 그래프 + 상세 route. 화면은 콜백만 받고, 여기서 navigate로 잇는다 (Routes.kt 콜백 계약)
@Composable
private fun RootNavHost(nav: NavHostController, onSelectReport: () -> Unit) {
    val back: () -> Unit = { nav.popBackStack() }
    val openSession = { run: RunSummary, context: WeeklyReport.DistanceCard? ->
        nav.navigate(sessionDetailRoute(run, context))
    }
    val openRecap = { period: RecapPeriod -> nav.navigate(recapRoute(period)) }
    val openRace = { entry: RaceEngine.Entry -> nav.navigate(raceDetailRoute(entry)) }

    NavHost(nav, startDestination = Routes.HomeGraph) {
        navigation<Routes.HomeGraph>(startDestination = Routes.HomeRoot) {
            composable<Routes.HomeRoot> {
                HomeScreen(
                    onSelectReport = onSelectReport,
                    onOpenSession = openSession,
                    onOpenToday = { nav.navigate(Routes.Today) },
                    onOpenRecap = openRecap,
                    onOpenRace = openRace,
                    onOpenCollection = { nav.navigate(Routes.Collection) },
                    onOpenSettings = { nav.navigate(Routes.Settings) },
                )
            }
        }
        navigation<Routes.ReportGraph>(startDestination = Routes.ReportRoot) {
            composable<Routes.ReportRoot> {
                ReportHomeScreen(
                    onOpenSession = openSession,
                    onOpenRecap = openRecap,
                    onOpenTrainingPlan = { nav.navigate(trainingPlanRoute(it)) },
                )
            }
        }
        navigation<Routes.CourseGraph>(startDestination = Routes.CourseRoot) {
            composable<Routes.CourseRoot> { CourseScreen() }
        }
        navigation<Routes.RacesGraph>(startDestination = Routes.RacesRoot) {
            composable<Routes.RacesRoot> { RaceListScreen(onOpenRace = openRace) }
        }

        composable<Routes.SessionDetail> {
            val route = it.toRoute<Routes.SessionDetail>()
            SessionDetailScreen(run = route.run, weeklyContext = route.weeklyContext, onBack = back)
        }
        composable<Routes.RaceDetail> { RaceDetailScreen(entry = it.toRoute<Routes.RaceDetail>().entry, onBack = back) }
        composable<Routes.Collection> { CollectionScreen(onBack = back) }
        composable<Routes.Settings> {
            SettingsScreen(onBack = back, onOpenRediagnosis = { nav.navigate(Routes.Rediagnosis) })
        }
        composable<Routes.TrainingPlanDetail> {
            TrainingPlanScreen(plan = it.toRoute<Routes.TrainingPlanDetail>().plan, onBack = back)
        }
        composable<Routes.Today> { TodayScreen(onBack = back) }
        composable<Routes.Recap> { RecapScreen(period = it.toRoute<Routes.Recap>().period, onBack = back) }
        composable<Routes.Rediagnosis> {
            // 다시 진단받기 — 이전 답을 프리필하지 않는 건 의도다 (기획서 §7, iOS SettingsScreen)
            OnboardingFlowScreen(isRediagnosis = true, onFinish = back)
        }
    }
}
