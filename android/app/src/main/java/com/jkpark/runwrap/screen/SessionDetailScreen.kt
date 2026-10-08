package com.jkpark.runwrap.screen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jkpark.runwrap.AppContainer
import com.jkpark.runwrap.BuildConfig
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.CourseMatchEngine
import com.jkpark.runwrap.engine.DriftEngine
import com.jkpark.runwrap.engine.FormAdvice
import com.jkpark.runwrap.engine.FormEngine
import com.jkpark.runwrap.engine.FormSnapshot
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GPXWriter
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.engine.FlyoverEngine
import com.jkpark.runwrap.engine.RoutePaceEngine
import com.jkpark.runwrap.engine.TrackPoint
import com.jkpark.runwrap.engine.thinned
import com.jkpark.runwrap.engine.HeartRateProfile
import com.jkpark.runwrap.engine.HeartRateZoneMethod
import com.jkpark.runwrap.engine.HeatEngine
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RoutePrivacy
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.ShareSummary
import com.jkpark.runwrap.engine.Shoe
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.WorkoutDetail
import com.jkpark.runwrap.engine.displayTitle
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.health.WorkoutDetailStore
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.ForceLightScheme
import com.jkpark.runwrap.ui.IndoorBadge
import com.jkpark.runwrap.ui.PhotoCardView
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RRSegmented
import com.jkpark.runwrap.ui.RouteSnapshot
import com.jkpark.runwrap.ui.ShareCardRenderer
import com.jkpark.runwrap.ui.ShareCardView
import com.jkpark.runwrap.ui.ShoeEditSheet
import com.jkpark.runwrap.ui.ShoeImage
import com.jkpark.runwrap.ui.ShoeView
import com.jkpark.runwrap.ui.SplitBarsChart
import com.jkpark.runwrap.ui.ToneBadge
import com.jkpark.runwrap.ui.TrendLineChart
import com.jkpark.runwrap.ui.ZoneBarView
import com.jkpark.runwrap.ui.color
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// 세션 상세 — 지도 헤더 + 지표 그리드 + 구간 페이스 + 심박 존 (시안 "세션 상세 · 지도")
/// (Android: MapKit 대신 Google Maps(maps-compose). 키(MAPS_API_KEY)가 비면 지도를 만들지 않고 빈 카드를 그린다.
///  Android 14는 운동 경로 읽기를 세션마다 동의받아서, 경로가 동의를 기다리면 지도 자리에 '경로 보기 허용' 버튼을 둔다)
///
/// - weeklyContext: 이번 주가 과부하일 때만 전달 — 이 세션의 기여도를 배지로 보여준다
/// - onShoePromptOptOut: 러닝 후 러닝화 묻기 팝업의 한 장으로 쓸 때만 전달 (이슈 #206) — 러닝화 행 대신 고르는 목록을 펼치고
///   뒤로가기를 숨긴다(닫기는 팝업의 확인 버튼). 값은 '다시 보지 않기' 동작
@Composable
fun SessionDetailScreen(
    run: RunSummary,
    weeklyContext: WeeklyReport.DistanceCard? = null,
    onShoePromptOptOut: (() -> Unit)? = null,
    onBack: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val health = container.health
    val shoes = container.shoes
    val settings = container.settings
    // 홈 팝업은 세션 여러 장을 한 화면에 띄운다 — 세션마다 스토어를 따로 쥐도록 run.id를 키로 건다
    // (containerViewModel은 키를 받지 않아 같은 생성 방식에 키만 더했다)
    val store = viewModel(key = "SessionDetail-${run.id}") { SessionDetailViewModel(container) }.store
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val isPrompt = onShoePromptOptOut != null

    val healthState by health.state.collectAsStateWithLifecycle()
    val hrMaxEstimate by health.hrMaxEstimate.collectAsStateWithLifecycle()
    val restingHRBpm by health.restingHRBpm.collectAsStateWithLifecycle()
    val detail by store.detail.collectAsStateWithLifecycle()
    // 경로 원본은 전부 보관하고 지도·공유 카드에는 표시 직전에 ~600점으로 솎아 넘긴다 (#222 선행)
    val routePoints = remember(detail?.route) { detail?.route.orEmpty().thinned() }
    val isLoading by store.isLoading.collectAsStateWithLifecycle()
    val loadFailed by store.loadFailed.collectAsStateWithLifecycle()
    val formSnapshots by store.formSnapshots.collectAsStateWithLifecycle()
    val isLoadingSnapshots by store.isLoadingSnapshots.collectAsStateWithLifecycle()
    val routeConsentRequired by store.routeConsentRequired.collectAsStateWithLifecycle()
    val courseMatches by store.courseMatches.collectAsStateWithLifecycle()
    /// 러닝화 (이슈 #171) — 이 세션에 신은 신발을 바꾼다
    val allShoes by shoes.shoes.collectAsStateWithLifecycle()
    val defaultShoeID by shoes.defaultShoeID.collectAsStateWithLifecycle()
    val assignments by shoes.assignments.collectAsStateWithLifecycle()

    var showShare by remember { mutableStateOf(false) }
    var showsGPXExport by remember { mutableStateOf(false) }
    /// 경로 플라이오버 (이슈 #224) — 지도 키가 있고 플라이오버 화면과 같은 가드(FlyoverEngine.track)를
    /// 통과할 때만 진입 버튼을 건다. 버튼만 따로 거르면 빈 다이얼로그가 뜬다
    var showsFlyover by remember { mutableStateOf(false) }
    /// 표시용 솎기(~600점)로 충분 — 원본 시각이 남아 있어 시간 비례 재생이 그대로다
    val flyoverTrack = remember(detail?.route, run.distanceKm) {
        FlyoverEngine.track(detail?.route.orEmpty().thinned(), distanceM = run.distanceKm?.let { it * 1_000 })
    }
    val canFlyover = BuildConfig.MAPS_API_KEY.isNotEmpty() && flyoverTrack != null
    var showsShoePicker by remember { mutableStateOf(false) }
    var showsShoeEditor by remember { mutableStateOf(false) }
    // 심박 기준 (이슈 #56) — 0/빈 문자열이면 미설정 → 추정·헬스 커넥트 값. 해석은 엔진 한 곳
    val hrMaxManual by settings.rememberSetting(ProfileKey.hrMaxManual, 0)
    val restingHRManual by settings.rememberSetting(ProfileKey.restingHRManual, 0)
    val hrZoneMethodRaw by settings.rememberSetting(ProfileKey.hrZoneMethod, "")

    /// 존·노력도·세션 상세가 공유하는 심박 기준 — 수동 > 추정 우선순위는 엔진이 정한다 (이슈 #56)
    val heartRate = TrainingGuideEngine.heartRateProfile(
        estimate = hrMaxEstimate, manualHrMax = hrMaxManual, manualRestingHR = restingHRManual,
        measuredRestingHR = restingHRBpm, zoneMethodRaw = hrZoneMethodRaw,
    )
    val latestHeartRate by rememberUpdatedState(heartRate)
    /// 러닝화 자동 지정의 재료 — SettingsScreen.loadedRuns와 같은 방식 (이슈 #206)
    val loadedRuns = (healthState as? HealthStore.State.Loaded)?.runs.orEmpty()

    /// 주법 기준선 재료로 전체 목록을 넘긴다 — 창·표본 가드는 엔진이 건다 (계획서 M4).
    /// 진입 시와 조회 실패 뒤 다시 시도가 같은 경로를 탄다 (이슈 #102)
    val load: suspend () -> Unit = {
        val all = (health.state.value as? HealthStore.State.Loaded)?.runs.orEmpty()
        store.load(run, others = all, heartRate = latestHeartRate)
    }
    LaunchedEffect(run.id) { load() }
    LaunchedEffect(run.id) {
        // 진입 시 목록이 로드 전이었다면 빈 기준선으로 끝났다 — 로드되면 스냅샷만 다시 부른다 (이슈 #92)
        // (iOS onChange처럼 지금 값은 건너뛰고 바뀔 때만)
        health.state.drop(1).collect { state ->
            if (state is HealthStore.State.Loaded) {
                store.reloadSnapshots(state.runs, excluding = run)
                store.loadCourse(run, state.runs)
            }
        }
    }
    // (Android 전용) 운동 경로 세션별 동의 — 결과(거절이면 빈 목록)를 상세에 싣는다
    val routeConsent = rememberLauncherForActivityResult(store.routeConsentContract) {
        store.applyConsentedRoute(it)
        // 동의로 경로가 들어오면 지문을 다시 만든다 (이슈 #223)
        scope.launch { store.loadCourse(run, (health.state.value as? HealthStore.State.Loaded)?.runs.orEmpty()) }
    }

    /// 이 세션에 배정된 신발 — 없음 표식·삭제된 신발이면 null (ShoeStore.shoe(runID)와 같은 규칙을 상태에서 읽는다)
    val assigned = assignments[run.id]?.let { id -> allShoes.firstOrNull { it.id == id } }
    val activeShoes = allShoes.filter { !it.isRetired }
    /// 고를 신발이 있으면(현역 신발 또는 이미 지정된 신발) 피커, 없으면 등록 시트 (이슈 #206)
    val canPickShoe = activeShoes.isNotEmpty() || assigned != null
    val mileage: (Shoe) -> Int = { shoes.mileage(it, loadedRuns).swiftRoundedInt() }
    val assign: (String?) -> Unit = { shoes.assign(run.id, it) }

    /// 야외 + 날씨 메타데이터 + 열 점수 38 초과일 때만 — 수치 가드는 HeatEngine이 건다
    val heatAdjustment = run.paceSecPerKm?.takeIf { !run.isIndoor }?.let {
        HeatEngine.adjustment(paceSecPerKm = it, tempC = run.weatherTempC, humidityPct = run.weatherHumidityPct)
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().background(RR.bg)) {
        // 뒤로가기 버튼 줄(위 8 + 34dp)까지 덮어 스크롤한 카드 글자가 버튼 밑에서 겹쳐 보이지 않게 한다 (이슈 #211)
        Box(Modifier.fillMaxSize().rrStatusBarScrim(belowTop = if (isPrompt) 0.dp else 46.dp, visible = scroll.rrTracksScroll())) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // 지도 헤더는 상태바 밑까지 깔고, 실내는 지도 헤더가 없어 뒤로가기 버튼 자리를 확보한다
                    .then(if (run.isIndoor) Modifier.statusBarsPadding().padding(top = 44.dp) else Modifier)
                    .padding(bottom = if (isPrompt) 56.dp else 26.dp),   // 팝업에서는 페이지 점 자리
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 실내 세션은 경로가 없어 지도 헤더 자체를 걸어 두지 않는다 (기획서 §4.6)
                if (!run.isIndoor) {
                    MapHeader(
                        run = run, route = routePoints, splitCount = detail?.splits?.size ?: 0, isLoading = isLoading, loadFailed = loadFailed,
                        consentRequired = routeConsentRequired, onConsent = { routeConsent.launch(run.id) },
                        onFlyover = if (canFlyover) ({ showsFlyover = true }) else null,
                    )
                }

                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Eyebrow(dateLine(run.start, zone))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(run.displayTitle(zone), style = RR.display(27.sp), color = RR.text)
                            if (run.isIndoor) IndoorBadge()
                        }
                    }

                    contributionBadge(run, weeklyContext, Instant.now(), zone)?.let { badge ->
                        Text(
                            badge, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = RR.dang,
                            modifier = Modifier
                                .background(RR.dangSoft, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }

                    Column(Modifier.rrCard().padding(horizontal = 18.dp)) {
                        StatsGrid(run, detail)
                        detail?.effort?.let { effort ->
                            // 그리드 아래 한 줄 — Apple 운동 노력도가 있을 때만 (iOS 18+, 이슈 #178)
                            HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(RRIcons.named("flame"), null, Modifier.size(13.dp), tint = RR.text3)
                                Text(
                                    "노력도 ${effort.score.swiftRoundedInt()}/10 · ${effort.label} · ${effort.sourceLabel}",
                                    style = TextStyle(fontSize = 13.sp), color = RR.text2,
                                )
                            }
                        }
                    }

                    if (isPrompt) {
                        // 러닝화 묻기 (이슈 #206) — 현역 신발이 있으면 이미지 목록에서 탭해 바로 배정,
                        // 없으면 등록 권유 + '다시 보지 않기'
                        if (activeShoes.isEmpty()) {
                            NoShoeBody(
                                onRegister = { showsShoeEditor = true },
                                onOptOut = {
                                    onShoePromptOptOut?.invoke()
                                    onBack()
                                },
                            )
                        } else {
                            Text(
                                "어떤 러닝화를 신었나요?",
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            ShoeList(activeShoes, assigned?.id, mileage, assign, onAdd = { showsShoeEditor = true })
                        }
                    } else {
                        ShoeRow(assigned, canPickShoe, mileage) {
                            if (canPickShoe) showsShoePicker = true else showsShoeEditor = true
                        }
                    }
                    heatAdjustment?.let { HeatCard(it) }
                    if (loadFailed) {
                        LoadFailedCard(enabled = !isLoading) { scope.launch { load() } }
                    }
                    detail?.takeIf { it.splits.size >= 3 }?.let { SplitsCard(it) }
                    detail?.let { RoutePaceEngine.elevationProfile(it.route) }?.let { ElevationCard(it) }
                    courseMatches?.let { m -> CourseMatchEngine.standing(run, m)?.let { CourseCard(m, it, zone) } }
                    detail?.drift?.let { DriftCard(it, heatAdjustment) }
                    detail?.let { d -> d.zones?.let { ZonesCard(it, d) } }
                    detail?.takeIf(::hasDynamics)?.let { FormCard(run, it, formSnapshots, isLoadingSnapshots) }
                    if (run.isIndoor) {
                        Text(
                            "실내 러닝에는 경로·고도 데이터가 없어서 해당 섹션이 표시되지 않아요.",
                            style = body(11.5f, 3f), color = RR.text3,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    // 경로 로딩 중에 열면 카드에 경로가 빠진다 — 불러오는 동안은 막는다 (이슈 #84)
                    ShareSection(enabled = !isLoading) { showShare = true }
                    // 경로가 없는 세션(실내 등)은 내보낼 것이 없어 버튼을 숨긴다 (미노출 원칙, 이슈 #222)
                    if ((detail?.route?.size ?: 0) >= 2) GPXRow { showsGPXExport = true }
                }
            }
        }

        if (!isPrompt) {
            OverlayCircleButton(
                "chevron.left", "뒤로", enabled = true, onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 7.dp, top = 1.dp),
            )
            // 뒤로가기 맞은편 공유 — 맨 아래 공유 카드와 같은 시트를 연다 (이슈 #210).
            // 경로 로딩 중에 열면 카드에 경로가 빠진다 — 공유 카드와 같이 막는다 (이슈 #84)
            OverlayCircleButton(
                "square.and.arrow.up", "스토리 카드로 공유", enabled = !isLoading, onClick = { showShare = true },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 7.dp, top = 1.dp),
            )
        }
    }

    if (showsFlyover && flyoverTrack != null) {
        // (Android: 뒤로가기로도 닫힌다 — iOS 전체 화면 커버는 닫기 버튼으로만 닫힌다)
        Dialog(
            onDismissRequest = { showsFlyover = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            RouteFlyoverScreen(flyoverTrack, onClose = { showsFlyover = false })
        }
    }
    if (showShare) {
        ShareSheet(
            run = run, detail = detail, route = routePoints,
            /// 카드 하단 주간 요약 — 이 세션 기준 7일 러닝 횟수·거리 (기획서 §4.4, 이슈 #92)
            weeklySummary = (healthState as? HealthStore.State.Loaded)?.let {
                ShareSummary.weeklyLine(it.runs, run.start, Instant.now(), zone)
            },
            onDismiss = { showShare = false },
        )
    }
    if (showsGPXExport) {
        detail?.let { GPXExportSheet(run, it, onDismiss = { showsGPXExport = false }) }
    }
    if (showsShoePicker) {
        ShoePickerSheet(activeShoes, assigned?.id, mileage, onDismiss = { showsShoePicker = false }) { id ->
            assign(id)
            // 고른 신발이 바뀌면 바로 닫는다 (iOS onChange(shoe(forRun:)?.id))
            if (id != assigned?.id) showsShoePicker = false
        }
    }
    // 새 신발 등록 — 러닝화 행과 팝업 목록이 같이 쓴다. 등록 시각이 러닝보다 늦어
    // 자동 배정 대상이 아니므로 명시적으로 배정한다 (이슈 #206)
    if (showsShoeEditor) {
        val newShoe = remember { Shoe(name = "", createdAt = Instant.now()) }
        ShoeEditSheet(
            shoe = newShoe, isNew = true, isDefault = defaultShoeID == null,
            onSave = { saved, isDefault ->
                shoes.save(saved, isDefault, loadedRuns)
                shoes.assign(run.id, saved.id)
            },
            onDelete = {},
            onDismiss = { showsShoeEditor = false },
        )
    }
}

/// 화면 소유 스토어 — 세부 기록 조회 상태를 세션 단위로 쥔다
private class SessionDetailViewModel(container: AppContainer) : ViewModel() {
    val store = WorkoutDetailStore(container.context, container.settings)
}

/// SwiftUI `.font(.system(size:))` + `.lineSpacing(spacing)` — 줄 간격은 기본 줄 높이(1.2배)에 더한다
private fun body(size: Float, spacing: Float = 0f, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    fontSize = size.sp, fontWeight = weight,
    lineHeight = if (spacing > 0f) (size * 1.2f + spacing).sp else TextUnit.Unspecified,
)

private val cardTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)

// MARK: 텍스트 조각

/// "9.30 (수) · 07:12" — iOS DateFormatter ko_KR "M.d (E) · HH:mm"을 직접 조립한다
private fun dateLine(start: Instant, zone: ZoneId): String {
    val d = start.atZone(zone)
    val weekday = "월화수목금토일"[d.dayOfWeek.value - 1]
    return "${d.monthValue}.${d.dayOfMonth} ($weekday) · " +
        d.hour.toString().padStart(2, '0') + ":" + d.minute.toString().padStart(2, '0')
}

/// 과부하 주간에 이 세션이 최근 7일 거리의 40% 이상이면 맥락 배지.
/// 기간 판정은 분모(recent7Km)와 같은 창 — 6일 전 자정부터 (이슈 #75)
private fun contributionBadge(run: RunSummary, context: WeeklyReport.DistanceCard?, now: Instant, zone: ZoneId): String? {
    val ctx = context ?: return null
    val km = run.distanceKm ?: return null
    if (!(ctx.recent7Km > 0)) return null
    val windowStart = now.minusSeconds(6L * 86_400).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    if (run.start < windowStart) return null
    val share = km / ctx.recent7Km
    if (!(share >= 0.4)) return null
    return "이번 주 거리의 ${(share * 100).swiftRoundedInt()}%가 이 한 번에서 나왔어요"
}

// MARK: 지도 헤더

@Composable
private fun MapHeader(
    run: RunSummary,
    route: List<TrackPoint>,
    splitCount: Int,
    isLoading: Boolean,
    loadFailed: Boolean,
    consentRequired: Boolean,
    onConsent: () -> Unit,
    onFlyover: (() -> Unit)?,
) {
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        if (route.size >= 2 && BuildConfig.MAPS_API_KEY.isNotEmpty()) {
            RouteMap(route, splitCount, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().background(RR.surface2),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(RRIcons.named("map"), null, Modifier.size(26.dp), tint = RR.text3)
                when {
                    // (Android 전용) 경로 읽기 동의 전 — 세션마다 시스템 동의 화면을 띄운다
                    consentRequired -> Text(
                        "경로 보기 허용",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClick = onConsent)
                            .background(RR.brandSoft)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                    // 지도 키가 없는 빌드는 경로가 있어도 지도를 그리지 않는다 — 문구 없이 빈 카드
                    route.size >= 2 -> {}
                    else -> Text(
                        if (isLoading) "경로를 불러오는 중" else if (loadFailed) "경로를 불러오지 못했어요" else "경로 기록이 없어요",
                        style = TextStyle(fontSize = 12.5.sp), color = RR.text3,
                    )
                }
            }
        }

        // 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (히어로 그라데이션·거리 배지·뒤로 버튼)
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent))),
        )

        run.distanceKm?.let { km ->
            Text(
                "러닝 경로 · ${Format.km(km)} km",
                style = mono(11.5.sp, FontWeight.SemiBold), color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // 경로 플라이오버 진입 (이슈 #224) — 거리 배지와 같은 오버레이 스타일
        if (onFlyover != null) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(role = Role.Button, onClick = onFlyover)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named("play.fill"), null, Modifier.size(12.dp), tint = Color.White)
                Text("플라이오버", style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        }
    }
}

/// 조작 없는 지도 + 경로 선 — 경로 전체가 보이도록 RouteSnapshot.region 영역에 맞춘다.
/// 페이스 색 구간마다 Polyline을 따로 칠한다 — 구간을 못 내면(표본 부족) 단색 brand.
/// 각 km 지점에 작은 번호 점을 얹는다 (이슈 #222)
@Composable
private fun RouteMap(route: List<TrackPoint>, splitCount: Int, modifier: Modifier) {
    val region = remember(route) { RouteSnapshot.region(route.map { GeoPoint(it.lat, it.lon) }) }
    val points = remember(route) { route.map { LatLng(it.lat, it.lon) } }
    val segments = remember(route) { RoutePaceEngine.segments(route) }
    val markers = remember(route, splitCount) { RoutePaceEngine.kmMarkers(route, splitCount) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(region.centerLat, region.centerLon), 14f)
    }
    val bounds = remember(region) {
        LatLngBounds(
            LatLng(region.centerLat - region.latDelta / 2, region.centerLon - region.lonDelta / 2),
            LatLng(region.centerLat + region.latDelta / 2, region.centerLon + region.lonDelta / 2),
        )
    }
    val strokeWidth = with(LocalDensity.current) { 4.dp.toPx() }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        uiSettings = MapUiSettings(
            compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false,
            myLocationButtonEnabled = false, rotationGesturesEnabled = false, scrollGesturesEnabled = false,
            scrollGesturesEnabledDuringRotateOrZoom = false, tiltGesturesEnabled = false,
            zoomControlsEnabled = false, zoomGesturesEnabled = false,
        ),
        onMapLoaded = { camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 0)) },
    ) {
        if (segments != null) {
            segments.forEach { segment ->
                Polyline(
                    points = segment.points.map { LatLng(it.lat, it.lon) }, color = segment.color, width = strokeWidth,
                    startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
                )
            }
        } else {
            Polyline(
                points = points, color = RR.brand, width = strokeWidth,
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
            )
        }
        markers.forEachIndexed { index, marker ->
            MarkerComposable(
                index, state = rememberUpdatedMarkerState(LatLng(marker.lat, marker.lon)),
                anchor = Offset(0.5f, 0.5f),
            ) {
                Box(
                    Modifier.size(15.dp).background(RR.surface, CircleShape).border(1.dp, RR.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = mono(8.5.sp, FontWeight.Bold), color = RR.text)
                }
            }
        }
    }
    if (segments != null) {
        Box(modifier.padding(14.dp), contentAlignment = Alignment.BottomEnd) { PaceLegend() }
    }
}

/// 페이스 색 범례 — 빠름(개선) → 느림(과부하).
/// 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (흰 글자·검정 배경)
@Composable
private fun PaceLegend() {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "경로 색은 구간 페이스 — 초록이 빠르고 빨강이 느려요" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text("빠름", style = style, color = Color.White)
        listOf(RRTone.improving, RRTone.steady, RRTone.caution, RRTone.overload).forEach { tone ->
            Box(Modifier.size(12.dp, 4.dp).background(tone.color, CircleShape))
        }
        Text("느림", style = style, color = Color.White)
    }
}

/// 지도 위 원형 버튼(뒤로·공유) — 34dp 원, 탭 영역은 48dp (iOS rrTapTarget 대응, 그만큼 바깥 여백을 줄였다)
@Composable
private fun OverlayCircleButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님
        Box(Modifier.size(34.dp).background(Color.Black.copy(alpha = 0.42f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(RRIcons.named(icon), label, Modifier.size(16.dp), tint = Color.White)
        }
    }
}

// MARK: 조회 실패

/// 세부 기록 조회 실패 — 경로·스플릿·존 카드 자리에 다시 시도를 띄운다 (이슈 #102)
@Composable
private fun LoadFailedCard(enabled: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().rrCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("세부 기록을 불러오지 못했어요", style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = RR.text2)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onRetry)
                .background(RR.brandSoft)
                .alpha(if (enabled) 1f else 0.5f)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RRIcons.named("arrow.clockwise"), null, Modifier.size(15.dp), tint = RR.brand)
            Text("다시 시도", style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = RR.brand)
        }
    }
}

// MARK: 지표 그리드 (3×2)

@Composable
private fun StatsGrid(run: RunSummary, detail: WorkoutDetail?) {
    val cells = listOf(
        Triple("거리", run.distanceKm?.let(Format::km) ?: "—", "km"),
        Triple("시간", Format.duration(run.durationSec), "h:m:s"),
        Triple("평균 페이스", run.paceSecPerKm?.let(Format::pace) ?: "—", "/km"),
        Triple("평균 심박", run.avgHeartRate?.let { "${it.swiftRoundedInt()}" } ?: "—", "bpm"),
        Triple("케이던스", detail?.cadenceSpm?.let { "${it.swiftRoundedInt()}" } ?: "—", "spm"),
        Triple("상승 고도", detail?.elevationM?.let { "${it.swiftRoundedInt()}" } ?: "—", "m"),
    )
    Column {
        cells.chunked(3).forEachIndexed { row, rowCells ->
            if (row > 0) HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
            Row { rowCells.forEach { MetricCell(it, valueSize = 20f) } }
        }
    }
}

/// 라벨 · 큰 값 · 단위 세 줄 — 지표 그리드와 주법 그리드가 같이 쓴다
@Composable
private fun RowScope.MetricCell(cell: Triple<String, String, String>, valueSize: Float) {
    Column(Modifier.weight(1f).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(cell.first, style = TextStyle(fontSize = 11.sp), color = RR.text3)
        FitText(cell.second, mono(valueSize.sp, FontWeight.Bold), RR.text, minScale = 0.6f)
        Text(cell.third, style = mono(10.5.sp), color = RR.text3)
    }
}

// MARK: 러닝화 (이슈 #171)

/// 사진 + "러닝화 / 페가수스 41" + 누적 거리 — 탭하면 은퇴하지 않은 신발 + "없음" 중에서 고른다.
/// 신발이 없으면 "등록하기"로 항상 노출해 등록 후 이 러닝에 바로 지정한다 (이슈 #206)
@Composable
private fun ShoeRow(assigned: Shoe?, canPickShoe: Boolean, mileage: (Shoe) -> Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rrCard()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (assigned != null) ShoeImage(assigned, Modifier.size(44.dp)) else ShoeView(Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("러닝화", style = TextStyle(fontSize = 12.sp), color = RR.text3)
            Text(
                if (canPickShoe) assigned?.name ?: "없음" else "등록하기",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = RR.text, maxLines = 1,
            )
        }
        if (assigned != null) {
            Text("누적 ${mileage(assigned)} km", style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
        }
        Icon(RRIcons.named("chevron.right"), null, Modifier.size(13.dp), tint = RR.text3)
    }
}

/// 러닝화 고르기 시트 — 러닝 후 팝업과 같은 사진 목록(ShoeList)을 쓴다. 고르면 바로 닫힌다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoePickerSheet(
    activeShoes: List<Shoe>,
    selectedID: String?,
    mileage: (Shoe) -> Int,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RR.bg,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("이 러닝에 신은 러닝화", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = RR.text)
            // 고르기 시트에서는 추가를 끈다 — 시트 위에 등록 시트를 또 띄우지 않으려고
            ShoeList(activeShoes, selectedID, mileage, onPick, onAdd = null)
        }
    }
}

/// onAdd: null이면 '러닝화 추가' 행을 숨긴다
@Composable
private fun ShoeList(
    activeShoes: List<Shoe>,
    selectedID: String?,
    mileage: (Shoe) -> Int,
    onPick: (String?) -> Unit,
    onAdd: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().rrCard()) {
        activeShoes.forEach { shoe ->
            ChoiceRow(isSelected = selectedID == shoe.id, onClick = { onPick(shoe.id) }) {
                ShoeImage(shoe, Modifier.size(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(shoe.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = RR.text, maxLines = 1)
                    Text("누적 ${mileage(shoe)} km", style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
                }
            }
            HorizontalDivider(Modifier.padding(start = 68.dp), thickness = Dp.Hairline, color = RR.line)
        }
        ChoiceRow(isSelected = selectedID == null, onClick = { onPick(null) }) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(RRIcons.named("nosign"), null, Modifier.size(19.dp), tint = RR.text3)
            }
            Text("없음", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = RR.text, modifier = Modifier.weight(1f))
        }
        if (onAdd != null) {
            HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onAdd)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named("plus.circle.fill"), null, Modifier.size(18.dp), tint = RR.brand)
                Text("러닝화 추가", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RR.brand)
            }
        }
    }
}

/// content는 체크 표시 앞까지 폭을 채운다(weight) — 체크는 항상 오른쪽 끝
@Composable
private fun ChoiceRow(isSelected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (isSelected) Icon(RRIcons.named("checkmark"), null, Modifier.size(16.dp), tint = RR.brand)
    }
}

/// 신발이 없을 때 — 등록 권유 + 등록 + 다시 보지 않기
@Composable
private fun NoShoeBody(onRegister: () -> Unit, onOptOut: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().rrCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShoeView(Modifier.size(96.dp))
        Text(
            "러닝화를 등록하면 누적 거리로 교체 시점을 알려드려요",
            style = TextStyle(fontSize = 14.5.sp, textAlign = TextAlign.Center), color = RR.text2,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onRegister)
                .background(RR.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("러닝화 등록", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.brand)
        }
        Text(
            "다시 보지 않기", style = TextStyle(fontSize = 13.sp), color = RR.text3,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClick = onOptOut)
                .padding(horizontal = 8.dp),
        )
    }
}

// MARK: 열 보정 페이스 (제안 문서 A1)

@Composable
private fun HeatCard(heat: HeatEngine.Adjustment) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("열 보정 페이스", style = cardTitle, color = RR.text)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Format.pace(heat.adjustedPaceSecPerKm), Modifier.alignByBaseline(), style = mono(26.sp, FontWeight.Bold), color = RR.text)
            Text("/km 상당", Modifier.alignByBaseline(), style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
            Spacer(Modifier.weight(1f))
            Text(
                "더위 몫 ${heat.deltaSecPerKm.swiftRoundedInt()}초/km",
                Modifier
                    .alignByBaseline()
                    .background(RR.warnSoft, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = RR.warn,
            )
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = RR.text2)) {
                    append("기온 ${heat.tempC.swiftRoundedInt()}°C · 습도 ${heat.humidityPct.swiftRoundedInt()}%에서 뛰었어요. 서늘한 날이었다면 ")
                }
                withStyle(SpanStyle(color = RR.pos, fontWeight = FontWeight.SemiBold)) { append(Format.paceKm(heat.adjustedPaceSecPerKm)) }
                withStyle(SpanStyle(color = RR.text2)) { append(" 수준 — 더위 몫까지 뛰었으니 오늘 기록, 억울해하지 않으셔도 됩니다.") }
            },
            style = body(12.5f, 4f),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// MARK: 심박 드리프트 (Pw:HR 디커플링, 제안 문서 A2)

@Composable
private fun DriftCard(drift: DriftEngine.Result, heat: HeatEngine.Adjustment?) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("심박 드리프트", style = cardTitle, color = RR.text)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(drift.decouplingPct, 1, plus = true) + "%", style = mono(26.sp, FontWeight.Bold), color = drift.tone.color)
            Spacer(Modifier.weight(1f))
            ToneBadge(drift.tone)
        }
        Text(driftSentence(drift, heat), style = body(12.5f, 4f), color = RR.text2, modifier = Modifier.padding(top = 10.dp))
    }
}

/// 사실 먼저, 위트는 뒤 — 톤별 문장은 Friel 5% 기준을 그대로 옮긴다.
/// 열 보정 카드가 뜬 더운 날(heat non-null)의 caution은 원인을 유산소 기반으로 단정하지 않는다 —
/// 더위도 후반 심박을 끌어올린다. 엔진은 날씨를 모르므로 톤은 그대로, 문장만 화면에서 바꾼다 (이슈 #100)
private fun driftSentence(drift: DriftEngine.Result, heat: HeatEngine.Adjustment?): String = when {
    drift.tone == RRTone.improving ->
        "후반에 오히려 심박 효율이 좋아졌어요. 엔진이 늦게 데워지는 타입이거나 컨디션이 계속 올라왔거나 — 어느 쪽이든 좋은 신호입니다."
    drift.tone == RRTone.caution && heat != null ->
        "후반 심박이 ${drift.decouplingPct.swiftRoundedInt()}% 더 들었지만, 더위로 오른 몫이 섞여 있어요. 더운 날엔 흔한 일이라 유산소 기반 문제로 단정하진 않을게요 — 선선한 날 한 번 더 재 보시죠."
    drift.tone == RRTone.caution ->
        "같은 페이스인데 후반 심박이 ${drift.decouplingPct.swiftRoundedInt()}% 더 들었어요. 이 거리엔 유산소 기반이 아직 덜 자랐다는 신호 — 편한 페이스 러닝을 늘리면 따라옵니다."
    else -> "전·후반 심박 효율 차이가 5% 안이에요. 오늘 페이스는 몸이 끝까지 감당했다는 뜻입니다."
}

// MARK: 구간별 페이스

// 같은 코스 (이슈 #223) — 본인 화면에만, 공유 카드에는 싣지 않는다
@Composable
private fun CourseCard(matches: List<RunSummary>, standing: CourseMatchEngine.Standing, zone: ZoneId) {
    val paced = matches.filter { it.paceSecPerKm != null }
    val labels = paced.map { r -> r.start.atZone(zone).let { "${it.monthValue}/${it.dayOfMonth}" } }
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Row {
            Text("같은 코스 ${standing.ordinal}번째", style = cardTitle, color = RR.text,
                 modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text("${matches.size}번 완주", style = mono(11.5.sp), color = RR.text3, modifier = Modifier.alignByBaseline())
        }
        Text(
            buildAnnotatedString {
                val rank = standing.rank
                when {
                    rank == null -> withStyle(SpanStyle(color = RR.text2)) { append("이 코스를 ${paced.size}번 달린 기록이 있어요.") }
                    rank == 1 -> {
                        withStyle(SpanStyle(color = RR.text2)) { append("이 코스 ") }
                        withStyle(SpanStyle(color = RR.pos, fontWeight = FontWeight.SemiBold)) { append("최고 기록") }
                        withStyle(SpanStyle(color = RR.text2)) { append("이에요. 같은 길에서 스스로를 이기셨습니다.") }
                    }
                    else -> {
                        withStyle(SpanStyle(color = RR.text2)) { append("이 코스 ${paced.size}번 중 ") }
                        withStyle(SpanStyle(color = RR.text, fontWeight = FontWeight.SemiBold)) { append("${rank}위") }
                        withStyle(SpanStyle(color = RR.text2)) { append(" 기록이에요.") }
                    }
                }
            },
            style = body(13f, 4f),
            modifier = Modifier.padding(top = 9.dp),
        )
        if (paced.size >= 2) {
            TrendLineChart(
                points = paced.mapNotNull { it.paceSecPerKm },
                modifier = Modifier.padding(top = 14.dp),
                tint = RR.brand,
                endLabels = labels.first() to labels.last(),
                pointLabels = labels,
                valueText = { Format.paceKm(it) },
            )
        }
        Text("회차별 평균 페이스 · 내려갈수록 빨라진 것", style = body(11.5f, 0f), color = RR.text3,
             modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SplitsCard(detail: WorkoutDetail) {
    val avg = detail.splits.sumOf { it.paceSecPerKm } / detail.splits.size
    val lastQuarter = detail.splits.takeLast(max(detail.splits.size / 4, 1))
    val lastAvg = lastQuarter.sumOf { it.paceSecPerKm } / lastQuarter.size
    val drift = (lastAvg - avg).swiftRoundedInt()
    val count = lastQuarter.size

    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("구간별 페이스", style = cardTitle, color = RR.text)
        Text(
            buildAnnotatedString {
                when {
                    drift >= 5 -> {
                        withStyle(SpanStyle(color = RR.text2)) { append("후반 $count km에서 평균보다 ") }
                        withStyle(SpanStyle(color = RR.warn, fontWeight = FontWeight.SemiBold)) { append("${drift}초") }
                        withStyle(SpanStyle(color = RR.text2)) { append(" 느려졌습니다. 페이스 유지 실패 구간이 있어요.") }
                    }
                    drift <= -5 -> {
                        withStyle(SpanStyle(color = RR.text2)) { append("후반 $count km를 평균보다 ") }
                        withStyle(SpanStyle(color = RR.pos, fontWeight = FontWeight.SemiBold)) { append("${-drift}초") }
                        withStyle(SpanStyle(color = RR.text2)) { append(" 빠르게 마쳤습니다. 네거티브 스플릿이에요.") }
                    }
                    else -> withStyle(SpanStyle(color = RR.text2)) { append("처음부터 끝까지 페이스가 고르게 유지됐습니다.") }
                }
            },
            style = body(13f, 4f),
            modifier = Modifier.padding(top = 9.dp),
        )
        SplitBarsChart(detail.splits, Modifier.padding(top = 14.dp))
    }
}

// MARK: 고도 프로필 (이슈 #222)

@Composable
private fun ElevationCard(profile: List<RoutePaceEngine.ProfilePoint>) {
    val elevations = profile.map { it.elevationM }
    val low = (elevations.minOrNull() ?: 0.0).swiftRoundedInt()
    val high = (elevations.maxOrNull() ?: 0.0).swiftRoundedInt()
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("고도 프로필", style = cardTitle, color = RR.text)
        Text(
            "최저 ${low}m · 최고 ${high}m",
            style = TextStyle(fontSize = 13.sp), color = RR.text2,
            modifier = Modifier.padding(top = 9.dp),
        )
        TrendLineChart(
            elevations, Modifier.padding(top = 14.dp), tint = RR.brand,
            endLabels = "0 km" to "${Format.km(profile.lastOrNull()?.distanceKm ?: 0.0)} km",
            pointLabels = profile.map { "${Format.km(it.distanceKm)} km" },
            valueText = { "${it.swiftRoundedInt()}m" },
        )
    }
}

// MARK: 심박 구간

@Composable
private fun ZonesCard(zones: List<Double>, detail: WorkoutDetail) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("심박 구간", style = cardTitle, color = RR.text)
        ZoneBarView(zones, Modifier.padding(top = 14.dp))
        detail.heartRate?.let { hr ->
            val caption = TextStyle(fontSize = 11.sp)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // 세션 최고 심박 — HRmax 대비 %로 강도를 한눈에 (제안 문서 A4).
                // 직접 입력한 HRmax면 "추정"을 뗀다 (이슈 #56)
                detail.maxHeartRateBpm?.let { peak ->
                    val estimated = if (hr.hrMaxSource == HeartRateProfile.Source.manual) "" else "추정 "
                    Text(
                        "최고 심박 ${peak.swiftRoundedInt()} bpm · ${estimated}HRmax의 ${(peak / hr.hrMax * 100).swiftRoundedInt()}%",
                        style = caption, color = RR.text3,
                    )
                }
                // 존 방식·HRmax 출처 — 어떤 기준으로 나눈 존인지 밝힌다 (이슈 #56)
                Text(zoneBasisLine(hr), style = caption, color = RR.text3)
                if (hr.hrMaxSource == HeartRateProfile.Source.fallback) {
                    // HC엔 생년월일이 없어 iOS 문구의 "건강 앱에 생년월일을 넣거나" 갈래를 뺀다 (PLATFORM_COMMON HRmax 결정)
                    Text("설정에서 최대 심박을 입력하면 더 정확해져요", style = caption, color = RR.text3)
                }
            }
        }
    }
}

/// "Karvonen(HRR) 기준 · HRmax 186 bpm(관찰 최대) · 안정 53 bpm"
private fun zoneBasisLine(hr: HeartRateProfile): String {
    var line = "${hr.zoneMethod.label} 기준 · HRmax ${hr.hrMax.swiftRoundedInt()} bpm(${hr.hrMaxSource.label})"
    val rest = hr.restingHR
    if (hr.zoneMethod == HeartRateZoneMethod.karvonen && rest != null) {
        line += " · 안정 ${rest.swiftRoundedInt()} bpm"
    }
    return line
}

// MARK: 주법 (러닝 다이내믹스) — 기획서 §4.8, 계획서 M4

/// 다이내믹스가 하나라도 있어야 카드를 건다 — 실내(미기록)·구형 워치의 이중 미노출 가드
private fun hasDynamics(detail: WorkoutDetail): Boolean =
    detail.verticalOscillationCm != null || detail.groundContactMs != null ||
        detail.strideLengthM != null || detail.runningPowerW != null

@Composable
private fun FormCard(run: RunSummary, detail: WorkoutDetail, snapshots: List<FormSnapshot>, isLoadingSnapshots: Boolean) {
    val session = FormSnapshot(
        id = run.id, start = run.start,
        cadenceSpm = detail.cadenceSpm ?: run.cadenceSpm,
        verticalOscillationCm = detail.verticalOscillationCm,
        groundContactMs = detail.groundContactMs,
    )
    // 기준선 창은 '지금'이 아니라 세션 직전 28일 — 과거 세션을 그 이후 기록과 비교하지 않는다 (이슈 #92)
    val engine = FormEngine(now = run.start)
    val advice = engine.baseline(snapshots, excluding = run.id)?.let { engine.advice(session, it) }
    val cells = listOf(
        Triple("수직 진폭", detail.verticalOscillationCm?.let { fmt(it, 1) } ?: "—", "cm"),
        Triple("지면 접촉", detail.groundContactMs?.let { "${it.swiftRoundedInt()}" } ?: "—", "ms"),
        Triple("보폭", detail.strideLengthM?.let { fmt(it, 2) } ?: "—", "m"),
        Triple("러닝 파워", detail.runningPowerW?.let { "${it.swiftRoundedInt()}" } ?: "—", "W"),
    )

    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("주법", style = cardTitle, color = RR.text)
        Row(Modifier.padding(top = 4.dp)) { cells.forEach { MetricCell(it, valueSize = 17f) } }
        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
        Box(Modifier.padding(top = 13.dp)) {
            when {
                !advice.isNullOrEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    advice.forEach { AdviceRow(it) }
                }
                advice != null -> Text(
                    "평소 주법 리듬을 그대로 유지했어요. 지금 폼이 흔들리지 않게 이어가면 됩니다.",
                    style = body(12.5f, 4f), color = RR.text2,
                )
                // 조회 중 빈 스냅샷으로 표본 부족 안내가 뜨지 않게 (이슈 #92)
                isLoadingSnapshots -> Text("주법 기준선을 불러오는 중…", style = body(12.5f, 4f), color = RR.text3)
                else -> Text(
                    "이 러닝 전 4주 야외 러닝이 5회 모이면 내 기준선과 비교한 주법 조언이 나와요.",
                    style = body(12.5f, 4f), color = RR.text3,
                )
            }
        }
    }
}

@Composable
private fun AdviceRow(item: FormAdvice) {
    val isSensor = item.kind == FormAdvice.Kind.sensor
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            RRIcons.named(if (isSensor) "exclamationmark.triangle.fill" else "shoeprints.fill"), null,
            Modifier.padding(top = 2.dp).size(12.dp), tint = if (isSensor) RR.warn else RR.brand,
        )
        Text(item.message, style = body(12.5f, 4f), color = RR.text2)
    }
}

// MARK: 스토리 공유 (기획서 §4.4, 계획서 M5)

@Composable
private fun ShareSection(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rrCard()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.size(52.dp, 92.dp).background(RR.brandSoft, RoundedCornerShape(9.dp)),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(RRIcons.named("square.and.arrow.up"), null, Modifier.size(17.dp), tint = RR.brand)
            Text("9:16", style = mono(9.sp), color = RR.brand)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("스토리 카드로 공유", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = RR.text)
            Text(
                "경로와 핵심 지표를 9:16 카드로 만들어 저장하거나 스토리에 올려보세요.",
                style = body(12.5f, 3f), color = RR.text3,
            )
        }
        Icon(RRIcons.named("chevron.right"), null, Modifier.size(14.dp), tint = RR.text3)
    }
}

/// GPX 내보내기 — 공유 카드 아래 한 줄. 시트에서 가림 여부를 고르고 공유한다 (이슈 #222)
/// (Android: SF `doc.badge.arrow.up` 대응 아이콘이 없어 공유 아이콘을 쓴다)
@Composable
private fun GPXRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .rrCard()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named("square.and.arrow.up"), null, Modifier.size(14.dp), tint = RR.brand)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("GPX 파일로 내보내기", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = RR.text)
            Text("Strava·Garmin 등에 경로·심박을 옮겨요", style = TextStyle(fontSize = 12.5.sp), color = RR.text3)
        }
        Icon(RRIcons.named("chevron.right"), null, Modifier.size(13.dp), tint = RR.text3)
    }
}

// MARK: - 공유 시트 (계획서 M5)

/// 스타일 토글 + 카드 미리보기 + 사진 저장(add-only) + 공유 시트
/// (Android: 렌더는 미리보기와 별도로 화면 밖 레이어(ShareCardRenderer.Source)에 기록해 두고, 저장·공유를 누를 때 이미지로 꺼낸다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    run: RunSummary,
    /// 존·케이던스·고도·구간 페이스 재료 — 아직 못 불러왔으면 null이고 해당 항목은 카드에서 빠진다 (이슈 #221)
    detail: WorkoutDetail?,
    route: List<TrackPoint>,
    weeklySummary: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    /// 0 = 미니멀, 1 = 사진
    var style by remember { mutableIntStateOf(0) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    /// 경로를 통째로 숨길지 — 다음 공유 때도 기억한다. 양끝 트림은 켜고 끔과 무관하게 항상 적용 (이슈 #84)
    var hidesRoute by settings.rememberSetting("share.hidesRoute", false)
    /// 양끝을 가릴 반경(300/500/1000m) — 다음 공유 때도 기억한다 (이슈 #191)
    var trimRadiusRaw by settings.rememberSetting(RoutePrivacy.radiusKey, RoutePrivacy.defaultRadius.rawValue)
    /// 날짜 줄에 시작~종료 시각을 적을지 — 기본 켜짐, 다음 공유 때도 기억한다 (이슈 #221)
    var showsTime by settings.rememberSetting("share.showsTime", true)
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val radius = RoutePrivacy.radius(trimRadiusRaw)
    // 집 근처가 드러나지 않게 시작·끝을 선택한 반경만큼 잘라낸 경로만 그린다 (이슈 #84·#191)
    val trimmed = remember(route, radius) { RoutePrivacy.trimmed(route, radius.meters) { GeoPoint(it.lat, it.lon) } }
    val layer = rememberGraphicsLayer()

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { decodePhoto(context, uri)?.let { photo = it } }
    }

    /// 미리보기와 저장 이미지 공통 — 기기 모드와 무관하게 늘 라이트로 그린다.
    /// 스토리는 남의 피드에 섞여 보여 다크 카드가 튀므로 한 가지로 고정한다 (이슈 #221)
    val card: @Composable () -> Unit = {
        ForceLightScheme {
            if (style == 0) {
                ShareCardView(
                    run, detail, route = if (hidesRoute) null else trimmed,
                    weeklySummary = weeklySummary, showsTime = showsTime,
                )
            } else {
                PhotoCardView(run, photo, showsTime = showsTime)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RR.bg,
    ) {
        // 저장·공유용 1080×1920 기록 — 자리를 차지하지 않는다
        ShareCardRenderer.Source(layer, card)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("스토리 카드", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = RR.text)

            RRSegmented(listOf("미니멀", "사진"), style, { style = it }, Modifier.padding(horizontal = 60.dp))

            // 사진 카드는 경로를 그리지 않으므로 미니멀 카드에서만 보인다
            if (style == 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(hidesRoute, role = Role.Switch) { hidesRoute = it }
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("경로 숨기기", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.text)
                        Text("집 근처 ${radius.label}는 항상 가려져요", style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
                    }
                    Switch(
                        checked = hidesRoute, onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = RR.onBrand, checkedTrackColor = RR.brand,
                            uncheckedThumbColor = RR.surface, uncheckedTrackColor = RR.barFill, uncheckedBorderColor = RR.line,
                        ),
                    )
                }
                // 가림 반경 선택 — 경로를 통째로 숨기면 의미가 없어 흐리게 막는다 (이슈 #191)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp).alpha(if (hidesRoute) 0.5f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "가릴 반경", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.text,
                        modifier = Modifier.weight(1f),
                    )
                    val radii = RoutePrivacy.Radius.entries
                    RRSegmented(
                        radii.map { it.label }, radii.indexOf(radius),
                        { if (!hidesRoute) trimRadiusRaw = radii[it].rawValue },
                        Modifier.widthIn(max = 190.dp),
                    )
                }
            }

            // 날짜 줄은 두 카드 모두에 있어 스타일과 무관하게 보인다
            // 분 단위 시각 표시 토글 — 끄면 "아침·저녁" 같은 시간대로 흐린다 (이슈 #191·#221)
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(showsTime, role = Role.Switch) { showsTime = it }
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("시각 표시", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.text)
                    Text(
                        if (showsTime) "시작~종료 시각이 보여요" else "아침·저녁처럼 시간대만 보여요",
                        style = TextStyle(fontSize = 11.5.sp), color = RR.text3,
                    )
                }
                Switch(
                    checked = showsTime, onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RR.onBrand, checkedTrackColor = RR.brand,
                        uncheckedThumbColor = RR.surface, uncheckedTrackColor = RR.barFill, uncheckedBorderColor = RR.line,
                    ),
                )
            }

            // 미리보기 — 360×640 카드를 0.52배로 줄여 보인다
            val previewShape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(360.dp * 0.52f, 640.dp * 0.52f)
                    .shadow(14.dp, previewShape, ambientColor = RR.shadowStrong, spotColor = RR.shadowStrong)
                    .clip(previewShape)
                    .border(1.dp, RR.line, previewShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.requiredSize(360.dp, 640.dp).graphicsLayer(scaleX = 0.52f, scaleY = 0.52f)) { card() }
            }

            if (style == 1) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(RRIcons.named("photo"), null, Modifier.size(15.dp), tint = RR.brand)
                    Text(
                        if (photo == null) "배경 사진 선택" else "사진 바꾸기",
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.brand,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val buttonShape = RoundedCornerShape(14.dp)
                ActionButton(
                    "사진에 저장", "square.and.arrow.down", RR.text,
                    Modifier.background(RR.surface, buttonShape).border(1.dp, RR.line, buttonShape),
                    buttonShape,
                ) {
                    scope.launch {
                        val image = ShareCardRenderer.render(layer)
                        val saved = image != null && runCatching { ShareCardRenderer.saveToPhotos(context, image) }.isSuccess
                        saveMessage = if (saved) "사진 앱에 저장했어요" else "저장하지 못했어요 — 기기 저장 공간을 확인해 주세요"
                    }
                }
                ActionButton("공유", "square.and.arrow.up", RR.onBrand, Modifier.background(RR.brand, buttonShape), buttonShape) {
                    scope.launch { ShareCardRenderer.render(layer)?.let { sendImage(context, it) } }
                }
            }

            saveMessage?.let { Text(it, style = TextStyle(fontSize = 12.sp), color = RR.text3) }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    title: String,
    icon: String,
    tint: Color,
    background: Modifier,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .weight(1f)
            .clip(shape)
            .then(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named(icon), null, Modifier.size(16.dp), tint = tint)
        Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/// 사진 선택기 결과 → 카드 배경. 카드가 1080×1920이라 긴 변 2048px 근처로 줄여 읽는다
private suspend fun decodePhoto(context: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 2_048))
            // 레이어 기록·PNG 압축이 픽셀을 읽어야 해서 하드웨어 비트맵을 쓰지 않는다
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/// iOS ShareLink 대응 — 카드 PNG를 cacheDir/share/에 쓰고 FileProvider uri로 공유 시트를 띄운다
private suspend fun sendImage(context: Context, image: ImageBitmap) {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, "runwrap_story.png").also { f ->
                f.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.getOrNull()
    } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, "러닝 스토리 카드"))
}

// MARK: - GPX 내보내기 시트 (이슈 #222)

/// "시작·끝 가리기" 토글 + 공유. 본인이 다른 서비스로 옮기는 용도라 원본이 기대값 — 가리기는 기본 꺼짐.
/// 파일은 임시 디렉터리에 쓰고 ShareLink로 넘긴다 — 앱은 어디에도 보내지 않는다
/// (Android: cacheDir/share/에 쓰고 FileProvider uri로 공유 시트(ACTION_SEND)를 띄운다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GPXExportSheet(run: RunSummary, detail: WorkoutDetail, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    var trims by remember { mutableStateOf(false) }
    /// 공유 카드와 같은 가림 반경을 쓴다 (이슈 #191)
    val trimRadiusRaw by settings.rememberSetting(RoutePrivacy.radiusKey, RoutePrivacy.defaultRadius.rawValue)
    val radius = RoutePrivacy.radius(trimRadiusRaw)
    val points = remember(trims, radius, detail.route) {
        if (trims) RoutePrivacy.trimmed(detail.route, radius.meters) { GeoPoint(it.lat, it.lon) } else detail.route
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RR.bg,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GPX 내보내기", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = RR.text)

            Row(
                Modifier.fillMaxWidth().toggleable(trims, role = Role.Switch) { trims = it },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("시작·끝 가리기", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.text)
                    Text("경로 양끝 ${radius.label}를 빼고 내보내요", style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
                }
                Switch(
                    checked = trims, onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RR.onBrand, checkedTrackColor = RR.brand,
                        uncheckedThumbColor = RR.surface, uncheckedTrackColor = RR.barFill, uncheckedBorderColor = RR.line,
                    ),
                )
            }

            if (points.size >= 2) {
                val buttonShape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(buttonShape)
                        .background(RR.brand, buttonShape)
                        .clickable(role = Role.Button) { scope.launch { sendGPX(context, run, detail, points) } }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(RRIcons.named("square.and.arrow.up"), null, Modifier.size(15.dp), tint = RR.onBrand)
                    Text("GPX 공유", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RR.onBrand)
                }
            } else {
                Text("경로가 너무 짧아 가리면 남는 구간이 없어요", style = TextStyle(fontSize = 12.sp), color = RR.text3)
            }
        }
    }
}

/// "러닝-2026-10-08.gpx"를 cacheDir/share/에 쓰고 공유 시트를 띄운다 — 쓰기에 실패하면 아무것도 하지 않는다
private suspend fun sendGPX(context: Context, run: RunSummary, detail: WorkoutDetail, points: List<TrackPoint>) {
    val zone = ZoneId.systemDefault()
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val gpx = GPXWriter.gpx(
                name = run.displayTitle(zone), start = run.start,
                segments = GPXWriter.segments(points), heartRates = detail.heartRateSamples,
            )
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, GPXWriter.fileName(run.start, zone)).also { it.writeText(gpx) }
        }.getOrNull()
    } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, "GPX 파일"))
}
