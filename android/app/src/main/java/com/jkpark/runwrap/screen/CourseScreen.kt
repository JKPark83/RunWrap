package com.jkpark.runwrap.screen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jkpark.runwrap.BuildConfig
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.CoursePOI
import com.jkpark.runwrap.engine.CourseSupplyEngine
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GPXParser
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.engine.NearbySupplyEngine
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.store.CoursePOIStore
import com.jkpark.runwrap.store.LocationProvider
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.store.appSupportDir
import com.jkpark.runwrap.ui.ChartCallout
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RouteSnapshot
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.rrStatusBarScrim
import com.jkpark.runwrap.ui.rrTracksScroll
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// '코스' 탭 — 급수·화장실·편의점을 짚어 준다 (기획서 §4.13, 계획서 M12-3).
/// 위치도 코스 파일도 기기 밖으로 나가지 않는다 — 번들 POI와 온디바이스 매칭만 한다.
///
/// **두 가지 모드.** 들어오면 먼저 **현재 위치** 기준으로 주변 보급을 보여주고,
/// GPX를 올리면 **코스** 기준("몇 km 지점")으로 바뀐다. 둘을 한 화면에 둔 이유는
/// 묻는 게 결국 같아서다 — "물 어디서 마시지". 다만 답의 단위가 달라
/// (직선 몇 m / 코스 몇 km) 엔진과 행 표기를 나눠 둔다.
/// (Android: iOS의 @State·@StateObject는 CourseViewModel이 갖는다. 지도는 MapKit 대신 Google Maps —
///  키(MAPS_API_KEY)가 없으면 지도 자리에 빈 카드를 둔다)
class CourseViewModel(context: Context, private val settings: SettingsStore) : ViewModel() {
    /// 리스트와 지도가 함께 보는 선택 상태 — 어느 지점인지(poi)와 말풍선 문구(caption).
    /// 두 모드가 거리 단위를 달리 쓰므로(직선 m / 코스 km) 문구는 만든 쪽이 넣어 준다
    data class Selection(val poi: CoursePOI, val caption: String)

    /// 지도 카메라 이동 요청 — iOS `camera = .region(…)` 대응. 화면이 적용한 뒤 consumeCamera로 지운다.
    /// animateMs가 null이면 애니메이션 없이 옮긴다
    data class CameraRequest(val seq: Long, val region: RouteSnapshot.Region, val animateMs: Int?)

    data class UiState(
        /// 끊긴 구간마다 나뉜 코스 좌표 — 지도도 세그먼트별로 따로 그어 점프를 잇지 않는다 (#150)
        val course: List<List<GeoPoint>> = emptyList(),
        val courseName: String = "",
        val result: CourseSupplyEngine.Result? = null,
        val nearby: NearbySupplyEngine.Result? = null,
        val notice: String? = null,
        /// 보급 종류 필터 — 켜진 종류만 지도·리스트에 남긴다.
        /// 기본값은 셋 다 켬(= 전체 표시)이라 필터를 모르는 사용자도 종전과 같은 화면을 본다.
        /// 마지막 하나는 끌 수 없다 — 전부 끄면 빈 화면이 되고, "전체 보기"는 셋 다 켠 상태다
        val kindFilter: Set<CoursePOI.Kind> = setOf(CoursePOI.Kind.water, CoursePOI.Kind.toilet, CoursePOI.Kind.convenience),
        /// 리스트·지도가 공유하는 선택 지점. null이면 아무것도 고르지 않은 상태
        val selected: Selection? = null,
        /// 위치 갱신 버튼 진행 표시 — 위치가 오거나 실패하면 내린다
        val isRefreshing: Boolean = false,
        val camera: CameraRequest? = null,
    )

    val store = CoursePOIStore(context)
    /// 보급 지점은 100m 단위가 의미를 가져 날씨(km)보다 정밀한 위치를 요청한다
    val location = LocationProvider(context, fine = true)
    private val resolver = context.applicationContext.contentResolver
    /// 마지막 코스 파일 — 재진입 시 유지. 이름은 설정의 lastCourseName (iOS @AppStorage와 같은 키)
    private val lastCourseFile = File(appSupportDir(context), "LastCourse.gpx")
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private var cameraSeq = 0L

    init {
        viewModelScope.launch {
            location.state.collect { new ->
                searchNearby()
                // 갱신은 답이 나오면 끝난다 — 거부·실패도 답이다(각각 안내 카드로 바뀐다)
                if (new is LocationProvider.State.Located || new is LocationProvider.State.Denied ||
                    new is LocationProvider.State.Failed) _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /// 화면 진입마다 부른다 (iOS `.task`). 현재 위치가 필요하면 true — 권한 요청·위치 요청은 화면이 한다
    suspend fun start(): Boolean {
        store.load()
        // 탭 재진입마다 다시 돈다 — 이미 분석한 코스가 있으면 복원·재분석을 건너뛴다 (#147)
        if (_ui.value.result != null) return false
        val restored = restoreLastCourse()
        analyze()
        // 되살린 코스가 분석되지 않으면 저장본을 지운다 — 재진입마다 같은 실패를 되풀이하지 않게 (감사 M12).
        // POI 로드 실패로 분석을 못 한 경우는 코스 탓이 아니니 남긴다
        if (restored && _ui.value.result == null && store.state.value is CoursePOIStore.State.Loaded) {
            lastCourseFile.delete()
            settings.remove(LAST_COURSE_NAME)
        }
        // 코스가 복원됐다면 주변 검색은 건너뛴다 — 화면에 안 쓸 위치를 굳이 받지 않는다
        return _ui.value.result == null
    }

    /// 위치와 POI가 **둘 다** 준비된 순간에만 검색한다.
    /// POI는 start()에서 await한 뒤라 이미 있고, 늦게 오는 쪽은 위치다 —
    /// 그래서 호출 지점이 둘(위치 상태 수집 + 코스 지우기)이고 가드는 한 벌이다
    fun searchNearby() {
        // 코스 모드에서는 현재 위치가 기준이 아니다 — 늦게 도착한 위치가 코스 카메라를 흔들지 않게 한다
        if (_ui.value.result != null) return
        val point = (location.state.value as? LocationProvider.State.Located)?.point ?: return
        val file = (store.state.value as? CoursePOIStore.State.Loaded)?.file ?: return
        // 흐린 좌표로는 검색하지 않는다 — km 오차면 가까운 순서가 뒤집혀 틀린 답이 된다 (이슈 #74)
        if (location.isCoarse.value) {
            _ui.update { it.copy(nearby = null) }
            return
        }
        val nearby = NearbySupplyEngine.search(point, file.pois, NEARBY_RADIUS)
        // 반경 1km가 화면에 다 들어오도록 지름(2km)보다 조금 넉넉하게 잡는다.
        // 새 위치를 받을 때마다 카메라를 되돌린다 — 손으로 끌어 옮겼어도 여기서 복귀한다
        _ui.update {
            it.copy(nearby = nearby, selected = null,
                    camera = cameraRequest(metersRegion(point, NEARBY_RADIUS * 2.4), 300))
        }
    }

    /// 위치를 다시 받아 핀·리스트·거리 표기를 새 좌표 기준으로 바꾼다.
    /// 좌표가 그대로여도 state가 loading을 거쳐 다시 located가 되므로 카메라 복귀는 항상 일어난다
    fun refreshLocation() {
        if (_ui.value.isRefreshing) return
        _ui.update { it.copy(isRefreshing = true) }
        location.request()
    }

    // MARK: 코스 적용 · 분석

    /// 고른 파일을 읽어 적용한다. 파일 읽기는 백그라운드에서 — 큰 GPX가 메인 스레드를 붙잡지 않게 (#147)
    fun import(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { readLimited(uri) }
            when {
                read === TOO_LARGE -> _ui.update { it.copy(notice = "파일이 너무 커요. GPX는 20MB까지만 읽을 수 있어요.") }
                read == null -> _ui.update { it.copy(notice = "파일을 여는 데 실패했어요. 다른 앱에서 내보낸 GPX인지 확인해 주세요.") }
                else -> apply(read, withContext(Dispatchers.IO) { displayName(uri) })
            }
        }
    }

    /// 상한까지만 읽는다 — 수십 MB XML을 통째로 메모리에 올려 파싱하는 일을 막는다 (#147).
    /// 넘으면 TOO_LARGE, 못 읽으면 null
    private fun readLimited(uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1_024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                if (out.size() > MAX_FILE_BYTES) return TOO_LARGE
            }
            out.toByteArray()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /// iOS `url.deletingPathExtension().lastPathComponent` 대응 — 문서 제공자가 주는 표시 이름에서 확장자를 뗀다
    private fun displayName(uri: Uri): String {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        return name.substringBeforeLast('.')
    }

    private suspend fun apply(data: ByteArray, name: String) {
        val points = withContext(Dispatchers.Default) { GPXParser.parseSegments(data) }
        if (points.isEmpty()) {
            _ui.update { it.copy(notice = "이 파일에는 경로가 없어요. 지점(웨이포인트)만 있는 GPX일 수 있으니 트랙이 담긴 파일로 부탁드려요.") }
            return
        }
        // 분석이 유효할 때만 상태를 바꾼다 — 짧은 코스가 보던 코스·저장본을 덮어쓰지 않게 (이슈 #149).
        // 실패한 파일을 남기면 탭 재진입마다 되살아나므로 저장도 성공한 코스만 한다 (감사 M12).
        // POI가 아직 로드 중이면 분석이 미뤄진 것뿐이니(start()가 로드 뒤 다시 분석) 코스 탓으로 보지 않고 저장한다
        var analyzed: CourseSupplyEngine.Result? = null
        val file = (store.state.value as? CoursePOIStore.State.Loaded)?.file
        if (file != null) {
            // 매칭 계산은 백그라운드에서 (#147)
            analyzed = withContext(Dispatchers.Default) { CourseSupplyEngine.analyze(segments = points, pois = file.pois) }
            if (analyzed == null) {
                _ui.update { it.copy(notice = SHORT_COURSE_NOTICE) }
                return
            }
        }
        _ui.update { it.copy(notice = null, course = points, courseName = name) }
        analyzed?.let(::applyResult)
        withContext(Dispatchers.IO) {
            try { lastCourseFile.writeBytes(data) } catch (_: IOException) {}
        }
        settings.set(LAST_COURSE_NAME, name)
    }

    /// 되살린 코스를 분석한다 — start() 복원 경로 전용. 올린 파일은 apply가 분석을 먼저 해 본다
    private suspend fun analyze() {
        val file = (store.state.value as? CoursePOIStore.State.Loaded)?.file ?: return
        val analyzed = _ui.value.course
        if (analyzed.isEmpty()) return
        // 매칭 계산은 백그라운드에서 (#147). 기다리는 사이 코스가 바뀌었으면(새 파일·지우기) 옛 결과는 버린다
        val fresh = withContext(Dispatchers.Default) { CourseSupplyEngine.analyze(segments = analyzed, pois = file.pois) }
        if (_ui.value.course !== analyzed) return
        if (fresh != null) applyResult(fresh)
        else _ui.update { it.copy(result = null, selected = null, notice = SHORT_COURSE_NOTICE) }
    }

    /// 분석 결과를 화면에 반영한다 — `course`를 먼저 바꿔 둬야 카메라가 새 코스를 잡는다
    private fun applyResult(analyzed: CourseSupplyEngine.Result) {
        // 새 코스가 통째로 보이게 카메라를 잡는다
        _ui.update {
            it.copy(result = analyzed, selected = null,
                    camera = cameraRequest(RouteSnapshot.region(it.course.flatten()), null))
        }
    }

    /// 저장된 마지막 코스를 되살린다 — 되살렸으면 true
    private suspend fun restoreLastCourse(): Boolean {
        if (_ui.value.course.isNotEmpty()) return false
        val points = withContext(Dispatchers.IO) {
            try { GPXParser.parseSegments(lastCourseFile.readBytes()) } catch (_: IOException) { emptyList() }
        }
        // 읽는 사이 사용자가 새 코스를 올렸으면 그쪽이 우선이다
        if (points.isEmpty() || _ui.value.course.isNotEmpty()) return false
        _ui.update { it.copy(course = points, courseName = settings.string(LAST_COURSE_NAME).orEmpty()) }
        return true
    }

    /// 올린 코스를 지우고 현재 위치 모드로 돌아간다 — 캐시 파일까지 지워야 재진입 시 안 살아난다.
    /// 위치를 새로 받아야 하면 true (권한 요청은 화면 몫)
    fun clearCourse(): Boolean {
        lastCourseFile.delete()
        settings.remove(LAST_COURSE_NAME)
        _ui.update { it.copy(course = emptyList(), courseName = "", result = null, notice = null, selected = null) }
        if (location.state.value is LocationProvider.State.Located) {
            searchNearby()
            return false
        }
        return true
    }

    // MARK: 선택 · 필터

    /// 종류를 켜고 끈다. 마지막 하나는 끄지 않는다 —
    /// 전부 꺼진 지도는 정보가 없고, 사용자가 원한 건 "고르기"이지 "비우기"가 아니다
    fun toggleKind(kind: CoursePOI.Kind) = _ui.update {
        // 고른 지점이 방금 숨겨졌을 수도 있다 — 선택은 필터를 만질 때 접는다
        val filter = when {
            kind !in it.kindFilter -> it.kindFilter + kind
            it.kindFilter.size > 1 -> it.kindFilter - kind
            else -> it.kindFilter
        }
        it.copy(selected = null, kindFilter = filter)
    }

    /// 지점 하나를 골라 지도를 그리로 옮긴다. 같은 지점을 다시 누르면 접는다
    fun select(poi: CoursePOI, caption: String) = _ui.update {
        if (it.selected?.poi == poi) it.copy(selected = null)
        else it.copy(selected = Selection(poi, caption),
                     camera = cameraRequest(metersRegion(GeoPoint(poi.lat, poi.lon), FOCUS_METERS), 250))
    }

    fun deselect() = _ui.update { it.copy(selected = null) }

    fun consumeCamera(seq: Long) = _ui.update { if (it.camera?.seq == seq) it.copy(camera = null) else it }

    private fun cameraRequest(region: RouteSnapshot.Region, animateMs: Int?) =
        CameraRequest(++cameraSeq, region, animateMs)

    /// MKCoordinateRegion(center:latitudinalMeters:longitudinalMeters:) 대응 — 위도 1도 111,195m, 경도는 cos(위도) 배
    private fun metersRegion(center: GeoPoint, meters: Double) = RouteSnapshot.Region(
        center.lat, center.lon, meters / 111_195.0, meters / (111_195.0 * cos(Math.toRadians(center.lat))))

    private companion object {
        const val LAST_COURSE_NAME = "lastCourseName"
        /// 주변 검색 반경 — 뛰어서 몇 분 거리. 1km면 왕복 10분 남짓이라 "들를 만한" 상한이다
        const val NEARBY_RADIUS = 1_000.0
        /// 지점 하나를 골랐을 때 잡아 주는 지도 폭 — 골목이 보이면서 주변 맥락도 남는 정도
        const val FOCUS_METERS = 500.0
        /// GPX 파일 크기 상한 — 이보다 크면 읽지 않는다. 풀코스 1초 기록도 수 MB라 넉넉하고,
        /// 수십 MB XML을 통째로 메모리에 올려 파싱하는 일을 막는다 (#147)
        const val MAX_FILE_BYTES = 20 * 1_048_576
        val TOO_LARGE = ByteArray(0)
        const val SHORT_COURSE_NOTICE = "코스가 500m보다 짧아서 분석을 접었어요. 이 정도면 보급 없이도 완주하실 거라 믿어요."
    }
}

/// 핀 탭 판정 여유(dp) — 핀(16dp)보다 넉넉해야 손가락으로 짚힌다
private const val PIN_TAP_SLOP = 22f

/// 코스 탭 루트
@Composable
fun CourseScreen() {
    val model = containerViewModel { CourseViewModel(it.context, it.settings) }
    val ui by model.ui.collectAsStateWithLifecycle()
    val storeState by model.store.state.collectAsStateWithLifecycle()
    val locationState by model.location.state.collectAsStateWithLifecycle()
    val isCoarse by model.location.isCoarse.collectAsStateWithLifecycle()
    val servicesDisabled by model.location.servicesDisabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = rememberScrollState()

    // 위치 권한은 화면이 요청한다 — 응답 뒤 다시 request()를 불러야 Denied/Located가 정해진다
    val permission = rememberLauncherForActivityResult(RequestMultiplePermissions()) { model.location.request() }
    // '대략적 위치'만 허용된 사용자에게 정확한 위치를 다시 청한다 (Android 12+ 정밀 업그레이드 다이얼로그)
    val precise = rememberLauncherForActivityResult(RequestMultiplePermissions()) { model.location.requestFullAccuracy() }
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val requestLocation = {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            model.location.request()
        } else permission.launch(locationPermissions)
    }
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let(model::import) }

    LaunchedEffect(Unit) { if (model.start()) requestLocation() }

    /// 모드는 상태가 아니라 결과에서 파생된다 — 코스 결과가 있으면 코스, 없으면 주변
    val isCourse = ui.result != null
    // 카메라는 모드마다 따로 둔다 — 한 상태를 두 지도가 번갈아 붙잡지 않게
    val camera = key(isCourse) { rememberCameraPositionState() }
    val density = LocalDensity.current
    val mapWidthPx = LocalWindowInfo.current.containerSize.width - with(density) { 36.dp.roundToPx() }
    val mapHeightPx = with(density) { 300.dp.roundToPx() }
    LaunchedEffect(ui.camera, camera) {
        val request = ui.camera ?: return@LaunchedEffect
        val r = request.region
        val bounds = LatLngBounds(LatLng(r.centerLat - r.latDelta / 2, r.centerLon - r.lonDelta / 2),
                                  LatLng(r.centerLat + r.latDelta / 2, r.centerLon + r.lonDelta / 2))
        // 키가 없으면 지도 자체가 없다 — 움직일 카메라도 없으니 요청만 소비한다
        if (BuildConfig.MAPS_API_KEY.isNotEmpty()) {
            // CameraUpdateFactory는 지도가 한 번 만들어져야 채워진다 — 요청이 지도보다 먼저 오면 NPE라 직접 초기화한다
            MapsInitializer.initialize(context)
            // 지도 크기를 직접 넘긴다 — 지도가 아직 배치되기 전이어도 영역을 잡을 수 있게
            val update = CameraUpdateFactory.newLatLngBounds(bounds, mapWidthPx.coerceAtLeast(1), mapHeightPx, 0)
            request.animateMs?.let { camera.animate(update, it) } ?: camera.move(update)
        }
        model.consumeCamera(request.seq)
    }

    val file = (storeState as? CoursePOIStore.State.Loaded)?.file
    val nearby = ui.nearby
    val result = ui.result

    // 상단 스크림 표시 — 본문이 상태바 밑으로 밀려 올라갔을 때만 (이슈 #211)
    Box(Modifier.fillMaxSize().background(RR.bg).rrStatusBarScrim(visible = scroll.rrTracksScroll())) {
        Column(
            Modifier.verticalScroll(scroll).statusBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Eyebrow("보급 가이드")
                Text(if (isCourse) "코스, 미리 짚어 드려요" else "지금 근처, 짚어 드려요",
                     style = RR.display(27.sp), color = RR.text)
            }

            // 두 모드 모두에서 보인다 — 코스를 보다가 잘못된 GPX를 골라도 이유를 알 수 있게 (이슈 #148).
            // 코스 모드에서는 닫기 없이 다음 성공 업로드나 코스 지우기에서 지운다
            ui.notice?.let { NoticeCard("코스를 읽지 못했어요", it, "map") }

            val uploadButtons = @Composable { compact: Boolean ->
                UploadButtons(
                    compact = compact,
                    onUpload = {
                        // MIME이 기기·제공자마다 달라(gpx가 octet-stream으로 오기도 한다) 넓게 받고, 내용은 파서가 판정한다
                        importer.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml",
                                                "application/octet-stream", "*/*"))
                    },
                    onClear = { if (model.clearCourse()) requestLocation() },
                )
            }

            if (storeState is CoursePOIStore.State.Failed) {
                NoticeCard("보급 데이터를 불러오지 못했어요",
                           "앱을 껐다 다시 열어 주세요. 계속 그러면 재설치가 필요할 수 있어요.",
                           "exclamationmark.triangle")
            } else if (result != null) {
                val visible = result.matches.filter { it.poi.kind in ui.kindFilter }
                val pins = visible.map { it.poi to courseCaption(it) }
                MapCard(
                    badge = "${ui.courseName} · ${Format.km(result.totalKm)}km",
                    camera = camera, pins = pins, selected = ui.selected, showsUser = false,
                    onSelect = model::select, onDeselect = model::deselect,
                    course = ui.course,
                )
                ListCard(
                    title = "보급 지점", ui = ui,
                    count = { kind -> result.matches.count { it.poi.kind == kind } },
                    isEmpty = result.matches.isEmpty(),
                    emptyText = "코스 150m 안에서는 보급 지점을 못 찾았어요. 물통을 챙기시는 편이 마음 편하겠어요.",
                    // 코스엔 보급이 있는데 지금 켠 종류만 없는 경우 — 위 두 문장과 원인이 다르다
                    filteredText = "고르신 종류는 이 코스에 없어요. 위 버튼으로 다른 종류를 켜 보세요.",
                    rows = visible.map {
                        SupplyRowData("${Format.km(it.courseKm)}km", it.poi,
                                      "${it.poi.kind.label} · 코스에서 ${it.detourMeters.swiftRoundedInt()}m", courseCaption(it))
                    },
                    onToggle = model::toggleKind, onSelect = model::select,
                )
                if (result.matches.none { it.poi.kind == CoursePOI.Kind.water }) WaterGapNote()
                uploadButtons(true)
                Attribution(file?.generatedAt)
            } else {
                when (locationState) {
                    LocationProvider.State.Idle, LocationProvider.State.Loading -> LocatingCard()
                    // 위치 거부 안내 + 설정 바로가기 (이슈 #94) — 버튼 스타일은 정확도 안내 카드와 같다
                    LocationProvider.State.Denied -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 휴대전화 전체 위치 서비스가 꺼진 경우도 Denied로 오므로 문구를 시스템 스위치 쪽으로 바꾼다
                        if (servicesDisabled) {
                            NoticeCard("휴대전화의 위치 서비스가 꺼져 있어요",
                                       "설정 > 위치에서 위치 사용을 켜 주시면 지금 근처의 보급 지점을 짚어 드릴게요. 그동안은 아래에서 GPX 코스를 올려 주셔도 됩니다.",
                                       "location.slash")
                        } else {
                            NoticeCard("위치를 쓸 수 없어요",
                                       "설정 앱 → 애플리케이션 → 런미새 → 권한에서 위치를 허용해 주시면 지금 근처의 보급 지점을 짚어 드릴게요. 그동안은 아래에서 GPX 코스를 올려 주셔도 됩니다.",
                                       "location.slash")
                        }
                        BorderedButton("설정 열기", "gearshape") {
                            context.startActivity(
                                if (servicesDisabled) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)))
                        }
                    }
                    LocationProvider.State.Failed ->
                        NoticeCard("위치를 못 받았어요",
                                   "실내나 지하에서는 위치를 잡기 어려울 수 있어요. 잠시 뒤 다시 들어와 주세요.",
                                   "location.slash")
                    is LocationProvider.State.Located -> when {
                        // '정확한 위치'가 꺼져 좌표가 km 단위로 흐린 경우 — 틀린 거리를 보여주느니 목록을 내지 않는다 (이슈 #74)
                        isCoarse -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NoticeCard("정확한 위치가 꺼져 있어요",
                                       "지금은 대략적인 위치만 받고 있어서 보급 지점까지의 거리가 km 단위로 어긋날 수 있어요. 이번만 정확한 위치를 허락해 주시면 제대로 짚어 드릴게요.",
                                       "location.viewfinder")
                            BorderedButton("이번만 정확한 위치 쓰기", "location") { precise.launch(locationPermissions) }
                        }
                        nearby != null -> {
                            val visible = nearby.matches.filter { it.poi.kind in ui.kindFilter }
                            val pins = visible.map { it.poi to nearbyCaption(it) }
                            Box {
                                // 정수 km로 찍는다 — 아래 리스트 문구("반경 1km 안에서는…")와 표기를 맞춘다
                                MapCard(
                                    badge = "현재 위치 · 반경 ${(nearby.radiusMeters / 1_000).toInt()}km",
                                    camera = camera, pins = pins, selected = ui.selected, showsUser = true,
                                    onSelect = model::select, onDeselect = model::deselect,
                                )
                                RefreshButton(ui.isRefreshing, model::refreshLocation, Modifier.align(Alignment.TopEnd))
                            }
                            ListCard(
                                title = "지금 근처 보급 지점", ui = ui,
                                count = { kind -> nearby.matches.count { it.poi.kind == kind } },
                                isEmpty = nearby.matches.isEmpty(),
                                emptyText = "반경 1km 안에서는 보급 지점을 못 찾았어요. 물통을 챙기시는 편이 마음 편하겠어요.",
                                // 근처엔 보급이 있는데 지금 켠 종류만 없는 경우 — 위 문장과 원인이 다르다
                                filteredText = "고르신 종류는 이 근처에 없어요. 위 버튼으로 다른 종류를 켜 보세요.",
                                rows = visible.map {
                                    SupplyRowData("${it.meters.swiftRoundedInt()}m", it.poi,
                                                  "${it.poi.kind.label} · 직선거리", nearbyCaption(it))
                                },
                                onToggle = model::toggleKind, onSelect = model::select,
                            )
                            if (nearby.matches.none { it.poi.kind == CoursePOI.Kind.water }) WaterGapNote()
                        }
                        // 위치는 받았는데 POI가 아직 안 올라온 찰나 — 로딩과 같은 카드로 덮는다
                        else -> LocatingCard()
                    }
                }
                uploadButtons(false)
                Attribution(file?.generatedAt)
            }
        }
    }
}

// MARK: 지도

/// 말풍선 문구 — 이름이 길면 잘라 쓴다. 지도 폭을 넘기면 말풍선이 잘려 보인다
private fun nearbyCaption(match: NearbySupplyEngine.Match) =
    "${shortName(match.poi.name)} · ${match.meters.swiftRoundedInt()}m"

private fun courseCaption(match: CourseSupplyEngine.Match) =
    "${shortName(match.poi.name)} · ${Format.km(match.courseKm)}km 지점"

private fun shortName(name: String) = if (name.length > 12) name.take(12) + "…" else name

/// 지도 카드 — 주변·코스 모드 공용. 코스 모드면 폴리라인을, 주변 모드면 내 위치 점을 그린다
@Composable
private fun MapCard(
    badge: String,
    camera: CameraPositionState,
    pins: List<Pair<CoursePOI, String>>,
    selected: CourseViewModel.Selection?,
    showsUser: Boolean,
    onSelect: (CoursePOI, String) -> Unit,
    onDeselect: () -> Unit,
    course: List<List<GeoPoint>> = emptyList(),
) {
    val shape = RoundedCornerShape(24.dp)
    val density = LocalDensity.current
    val brand = RR.brand
    Box(Modifier.fillMaxWidth().height(300.dp).clip(shape).background(RR.surface2).border(1.dp, RR.line, shape)) {
        // 키가 없는 빌드는 지도를 만들지 않는다 — 빈 카드 위에 배지만 남긴다
        if (BuildConfig.MAPS_API_KEY.isNotEmpty()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                // 내 위치 점은 권한이 있을 때만(주변 모드는 Located 상태에서만 이 카드가 선다)
                properties = MapProperties(isMyLocationEnabled = showsUser),
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = false, zoomControlsEnabled = false, rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false, mapToolbarEnabled = false, indoorLevelPickerEnabled = false,
                ),
                // 다크 모드는 지도 자체 색 구성으로 따라간다 — 스타일 JSON(색 리터럴) 없이
                mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
                // 지도 탭 한 번으로 선택과 해제를 모두 판정한다.
                // 핀은 16dp라 손가락으로 정확히 짚기 어려워 가까운 핀을 여유 반경 안에서 찾아 준다.
                // 반경 밖이면 "빈 곳을 눌렀다"로 보고 선택을 접는다
                onMapClick = click@{ latLng ->
                    val projection = camera.projection ?: return@click
                    val tap = projection.toScreenLocation(latLng)
                    val nearest = pins.map { pin ->
                        val p = projection.toScreenLocation(LatLng(pin.first.lat, pin.first.lon))
                        pin to hypot((p.x - tap.x).toFloat(), (p.y - tap.y).toFloat())
                    }.minByOrNull { it.second }
                    if (nearest != null && nearest.second <= with(density) { PIN_TAP_SLOP.dp.toPx() }) {
                        onSelect(nearest.first.first, nearest.first.second)
                    } else if (selected != null) onDeselect()
                },
                mapViewFactory = { ctx, options -> ScrollableMapView(ctx, options) },
            ) {
                course.forEach { segment ->
                    Polyline(points = segment.map { LatLng(it.lat, it.lon) }, color = brand, width = with(density) { 4.dp.toPx() },
                             startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND)
                }
                pins.forEach { (poi, caption) ->
                    val isSelected = selected?.poi == poi
                    val color = poi.kind.color
                    MarkerComposable(
                        isSelected, caption, color,
                        state = rememberUpdatedMarkerState(LatLng(poi.lat, poi.lon)),
                        contentDescription = caption,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = if (isSelected) 1f else 0f,
                        onClick = { onSelect(poi, caption); true },
                    ) { PoiPin(poi, caption, isSelected, color) }
                }
            }
        }
        MapBadge(badge)
    }
}

/// 세로 스크롤 안의 지도 — 손가락이 닿으면 부모(스크롤)가 끌기를 가로채지 않게 해 지도를 위아래로도 옮길 수 있게 한다
@SuppressLint("ViewConstructor")
private class ScrollableMapView(context: Context, options: com.google.android.gms.maps.GoogleMapOptions) :
    MapView(context, options) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }
}

/// 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (핀·mapBadge의 흰/검).
/// 고른 핀은 크기 + 바깥 링 + 위 말풍선으로 구분한다 — 같은 색 핀이 여럿이어도 한눈에 짚인다.
/// (Android: 마커 앵커를 핀 중심(0.5, 0.5)에 두려고 말풍선과 같은 크기의 투명 자리를 아래에도 둔다)
@Composable
private fun PoiPin(poi: CoursePOI, caption: String, isSelected: Boolean, color: Color) {
    val dot = @Composable { size: Int, border: Float, iconSize: Int ->
        Box(Modifier.size(size.dp).background(color, CircleShape).border(border.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(RRIcons.named(poi.kind.symbol), null, Modifier.size(iconSize.dp), tint = Color.White)
        }
    }
    if (!isSelected) {
        dot(16, 1.5f, 7)
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ChartCallout(caption)
        Box(Modifier.size(36.dp).border(6.dp, color.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
            dot(26, 2f, 11)
        }
        ChartCallout(caption, Modifier.alpha(0f))
    }
}

/// 지도 왼쪽 위에 둔다 — 아래 모서리는 Google 로고가 차지해 겹친다
@Composable
private fun MapBadge(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
        color = Color.White,
        modifier = Modifier.padding(12.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/// 위치 갱신 버튼 — 주변 모드에만 둔다.
/// 코스 모드는 기준이 현재 위치가 아니고, 권한 거부 상태에서는 지도 대신 안내 카드가 서므로
/// 이 버튼도 함께 사라진다 (이슈 #4)
@Composable
private fun RefreshButton(isRefreshing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.padding(12.dp)
            .minimumInteractiveComponentSize()
            .size(38.dp)
            .background(RR.surface, CircleShape)
            .border(1.dp, RR.line, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = !isRefreshing, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "현재 위치 갱신" },
        contentAlignment = Alignment.Center,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), color = RR.text3, strokeWidth = 2.dp)
        } else {
            Icon(RRIcons.named("location.fill"), null, Modifier.size(16.dp), tint = RR.brand)
        }
    }
}

// MARK: 리스트

private data class SupplyRowData(val lead: String, val poi: CoursePOI, val detail: String, val caption: String)

/// 보급 리스트 카드 — 두 모드 공용. 맨 앞 값·빈 문구만 모드마다 다르다
@Composable
private fun ListCard(
    title: String,
    ui: CourseViewModel.UiState,
    count: (CoursePOI.Kind) -> Int,
    isEmpty: Boolean,
    emptyText: String,
    filteredText: String,
    rows: List<SupplyRowData>,
    onToggle: (CoursePOI.Kind) -> Unit,
    onSelect: (CoursePOI, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text)
        KindFilterBar(ui.kindFilter, count, onToggle)
        when {
            isEmpty -> BodyText(emptyText)
            rows.isEmpty() -> BodyText(filteredText)
            else -> Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
                    SupplyRow(row, ui.selected?.poi == row.poi) { onSelect(row.poi, row.caption) }
                }
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp), color = RR.text2)
}

/// 급수·화장실·편의점 필터 버튼 — 각각 아이콘 + 이름 + 개수.
/// 개수 세는 법만 모드마다 다르므로 람다로 받는다.
/// 없는 종류는 버튼도 흐리게 두되 누를 수는 있게 한다 (없다는 사실 자체가 정보다)
@Composable
private fun KindFilterBar(filter: Set<CoursePOI.Kind>, count: (CoursePOI.Kind) -> Int, onToggle: (CoursePOI.Kind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CoursePOI.Kind.water, CoursePOI.Kind.toilet, CoursePOI.Kind.convenience).forEach { kind ->
            val total = count(kind)
            val isOn = kind in filter
            val tint = if (isOn) RR.onBrand else kind.color.copy(alpha = if (total > 0) 1f else 0.45f)
            val shape = RoundedCornerShape(11.dp)
            Row(
                Modifier.weight(1f)
                    .minimumInteractiveComponentSize()
                    .clip(shape)
                    .background(if (isOn) kind.color else kind.softColor)
                    .clickable(role = Role.Button) { onToggle(kind) }
                    .padding(vertical = 9.dp)
                    .clearAndSetSemantics {
                        contentDescription = "${kind.label} ${total}개"
                        stateDescription = if (isOn) "표시 중" else "숨김"
                    },
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named(kind.symbol), null, Modifier.size(12.dp), tint = tint)
                Text(kind.label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = tint)
                Text("$total", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                     color = tint, modifier = Modifier.alpha(0.75f))
            }
        }
    }
}

/// 보급 한 줄 — 맨 앞 값만 모드마다 다르다 (주변은 "320m", 코스는 "2.4km").
/// 누르면 지도가 그 지점으로 옮겨 가므로 행 자체가 버튼이다
@Composable
private fun SupplyRow(row: SupplyRowData, isSelected: Boolean, onClick: () -> Unit) {
    val kind = row.poi.kind
    Row(
        Modifier.fillMaxWidth()
            // 강조 배경만 글자보다 조금 넓게 — 바깥 음수 패딩으로 행 정렬은 그대로 둔다
            .layout { measurable, constraints ->
                val inset = 8.dp.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = 2 * inset))
                layout(placeable.width - 2 * inset, placeable.height) { placeable.place(-inset, 0) }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) kind.softColor else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "지도에서 위치를 짚어 드려요", onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.lead, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
             color = RR.text, modifier = Modifier.width(56.dp))
        Box(Modifier.size(28.dp).background(kind.softColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(RRIcons.named(kind.symbol), null, Modifier.size(12.dp), tint = kind.color)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.poi.name, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = RR.text,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.detail, style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
        }
    }
}

// MARK: 버튼 · 안내 · 출처

@Composable
private fun LocatingCard() {
    Row(Modifier.fillMaxWidth().rrCard().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = RR.text3, strokeWidth = 2.dp)
        Text("지금 계신 곳을 찾고 있어요", style = TextStyle(fontSize = 13.5.sp), color = RR.text2)
    }
}

@Composable
private fun UploadButtons(compact: Boolean, onUpload: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // iOS .borderedProminent — 앱 tint(brand) 채움
        LabelButton(if (compact) "다른 코스 올리기" else "GPX 코스 올리기", "square.and.arrow.up",
                    RR.onBrand, RR.brand, onUpload)
        if (compact) {
            BorderedButton("현재 위치로 보기", "location", onClear)
        } else {
            Text("달릴 코스를 GPX로 올리시면 '몇 km 지점'까지 짚어 드려요. 코스 파일과 위치는 기기 밖으로 나가지 않아요.",
                 style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp), color = RR.text3,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/// iOS `.buttonStyle(.bordered)` 대응 — 옅은 tint(brand) 배경 + tint 글자
@Composable
private fun BorderedButton(title: String, symbol: String, onClick: () -> Unit) =
    LabelButton(title, symbol, RR.brand, RR.brand.copy(alpha = 0.15f), onClick)

@Composable
private fun LabelButton(title: String, symbol: String, content: Color, background: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named(symbol), null, Modifier.size(16.dp), tint = content)
        Text(title, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = content)
    }
}

/// 음수대 데이터 공백 안내 — 서울·한강 중심이라 없는 게 아니라 "모르는" 것일 수 있다
/// (기획서 §6 제약 · §4.13 미노출 가드)
@Composable
private fun WaterGapNote() {
    Text("음수대 정보는 아직 서울·한강 공원 중심이에요. 여기 안 보여도 실제로는 있을 수 있으니, 미덥지 않으면 물통을 챙겨 주세요.",
         style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp), color = RR.text3,
         modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun NoticeCard(title: String, message: String, symbol: String) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(RRIcons.named(symbol), null, Modifier.size(24.dp), tint = RR.text3)
        Text(title, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Bold), color = RR.text)
        Text(message, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp), color = RR.text2)
    }
}

@Composable
private fun Attribution(generatedAt: String?) {
    if (generatedAt == null) return
    Text("데이터 기준 $generatedAt · 소상공인시장진흥공단·행정안전부·서울열린데이터광장 · © OpenStreetMap 기여자(ODbL)",
         style = TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp), color = RR.text3,
         modifier = Modifier.padding(horizontal = 4.dp))
}

// MARK: - 종류별 표시 매핑 (화면 전용)

private val CoursePOI.Kind.label: String
    get() = when (this) {
        CoursePOI.Kind.convenience -> "편의점"
        CoursePOI.Kind.toilet -> "화장실"
        CoursePOI.Kind.water -> "음수대"
    }

private val CoursePOI.Kind.symbol: String
    get() = when (this) {
        CoursePOI.Kind.convenience -> "cart.fill"
        CoursePOI.Kind.toilet -> "toilet.fill"
        CoursePOI.Kind.water -> "drop.fill"
    }

private val CoursePOI.Kind.color: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        CoursePOI.Kind.convenience -> RR.warn
        CoursePOI.Kind.toilet -> RR.sky   // 브랜드 주황은 위험 신호로 읽혀 중립 청색으로 (화장실 표지 관례)
        CoursePOI.Kind.water -> RR.pos
    }

private val CoursePOI.Kind.softColor: Color
    @Composable @ReadOnlyComposable get() = when (this) {
        CoursePOI.Kind.convenience -> RR.warnSoft
        CoursePOI.Kind.toilet -> RR.skySoft
        CoursePOI.Kind.water -> RR.posSoft
    }
