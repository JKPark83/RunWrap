package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jkpark.runwrap.engine.ReportEngine
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.weeklyReport
import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import com.jkpark.runwrap.health.HealthConnectStore
import com.jkpark.runwrap.store.AirQualityStore
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.store.WeatherStore
import com.jkpark.runwrap.ui.theme.RR

/// 앱 루트 — iOS RootView 대응. 하단 탭 2개(홈·리포트)로 화면을 전환한다 (계획서 M5).
/// iOS는 4탭(홈·리포트·코스·대회)이지만 Android M5 범위는 리포트 코어 2탭이다.
/// 스토어는 전부 여기서 소유한다 — 탭을 오가도 다시 로드하지 않기 위해서다 (iOS 동일).
/// levelV2가 비어 있으면(설문 미완료) 탭 대신 온보딩을 전면에 띄운다. 판정은 시작 시
/// 1회만 래치한다 — 설문 도중 levelV2가 저장돼도 권한 요청이 끝날 때까지 온보딩
/// 화면이 유지돼야 권한 런처 콜백이 유실되지 않는다.
@Composable
fun RootView() {
    val context = LocalContext.current.applicationContext
    val healthStore = remember { HealthConnectStore(context) }
    val weatherStore = remember { WeatherStore(context) }
    val airStore = remember { AirQualityStore() }
    val settings = remember { SettingsStore(context) }

    // 온보딩 분기 — 로딩 전(null)에는 배경만 그려서, 이미 온보딩을 마친 사용자에게
    // 설문이 깜빡 비치지 않게 한다
    var needsOnboarding by remember { mutableStateOf<Boolean?>(null) }
    val levelRaw: String? by settings.levelV2.collectAsState(initial = null)
    LaunchedEffect(levelRaw) {
        val raw = levelRaw
        if (needsOnboarding == null && raw != null) needsOnboarding = raw.isEmpty()
    }
    when (needsOnboarding) {
        null -> {
            Box(Modifier.fillMaxSize().background(RR.bg))
            return
        }
        true -> {
            OnboardingFlowScreen(settings, onFinish = { needsOnboarding = false })
            return
        }
        else -> Unit
    }

    // 온보딩을 지난 뒤에야 로드한다 — HC 권한 요청이 끝난 다음이어야 첫 조회가 성공한다
    LaunchedEffect(Unit) { healthStore.load() }
    val healthState by healthStore.state.collectAsState()
    val vitals by healthStore.vitals.collectAsState()
    val vo2Samples by healthStore.vo2Max.collectAsState()
    val crossTrainings by healthStore.crossTrainings.collectAsState()

    // 생년 — 세션 상세 HRmax(Tanaka) 재료. 0 = 미설정이라 null로 바꿔 넘긴다
    val birthYear by settings.birthYear.collectAsState(initial = 0)

    // 리포트 상세로 넘길 (주간 리포트, 훈련 가이드) — nav 인자로 싣기엔 커서 상태로 든다
    var detail by remember { mutableStateOf<Pair<WeeklyReport, TrainingGuide?>?>(null) }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = RR.bg,
        bottomBar = {
            NavigationBar(containerColor = RR.surface) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RR.brand,
                            selectedTextColor = RR.brand,
                            indicatorColor = RR.brandSoft,
                            unselectedIconColor = RR.text3,
                            unselectedTextColor = RR.text3,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        when (val state = healthState) {
            HealthConnectStore.State.Idle, HealthConnectStore.State.Loading ->
                CenteredNote(Modifier.padding(innerPadding)) {
                    CircularProgressIndicator(color = RR.brand)
                }
            HealthConnectStore.State.Unavailable ->
                CenteredNote(Modifier.padding(innerPadding)) {
                    Text(
                        "헬스 커넥트를 쓸 수 없어요.\n삼성헬스 동기화와 권한을 확인해 주세요.",
                        color = RR.text3, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            is HealthConnectStore.State.Failed ->
                CenteredNote(Modifier.padding(innerPadding)) {
                    Text(
                        "기록을 읽지 못했어요.\n${state.message}",
                        color = RR.text3, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }
            is HealthConnectStore.State.Loaded -> NavHost(
                nav,
                startDestination = Tab.Home.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Tab.Home.route) {
                    HomeScreen(
                        runs = state.runs,
                        vitals = vitals,
                        weatherStore = weatherStore,
                        airStore = airStore,
                        settings = settings,
                        onSelectReport = {
                            nav.navigate(Tab.Report.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenSession = { run -> nav.navigate("session/${run.id}") },
                        onOpenSettings = { nav.navigate("settings") },
                        onReloadHealth = { healthStore.load() },
                    )
                }
                composable(Tab.Report.route) {
                    ReportHomeScreen(
                        runs = state.runs,
                        vitals = vitals,
                        vo2Samples = vo2Samples,
                        crossTrainings = crossTrainings,
                        settings = settings,
                        onOpenDetail = { report, guide ->
                            detail = report to guide
                            nav.navigate("reportDetail")
                        },
                        onOpenSession = { run -> nav.navigate("session/${run.id}") },
                        onReloadHealth = { healthStore.load() },
                    )
                }
                composable("reportDetail") {
                    detail?.let { (report, guide) ->
                        ReportDetailScreen(report, guide, onBack = { nav.popBackStack() })
                    }
                }
                composable("session/{id}") { entry ->
                    val id = entry.arguments?.getString("id")
                    val run = state.runs.firstOrNull { it.id == id }
                    if (run != null) {
                        // 과부하 주간에만 기여도 배지 맥락을 넘긴다 (iOS StatsScreen.weeklyContext 동일)
                        val weekly = remember(state.runs) {
                            ReportEngine(Instant.now()).weeklyReport(state.runs)
                                .distance?.takeIf { it.tone == RRTone.OVERLOAD }
                        }
                        SessionDetailScreen(run, weeklyContext = weekly,
                                            birthYear = birthYear.takeIf { it > 0 },
                                            onBack = { nav.popBackStack() })
                    }
                }
                composable("settings") {
                    SettingsScreen(settings, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/// 하단 탭 정의 — 라우트 문자열이 NavHost 목적지 키를 겸한다.
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "홈", Icons.Filled.Home),
    Report("report", "리포트", Icons.Filled.DateRange),
}

@Composable
private fun CenteredNote(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        content()
    }
}
