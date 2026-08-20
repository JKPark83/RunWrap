package com.jkpark.runwrap.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.jkpark.runwrap.BuildConfig
import com.jkpark.runwrap.engine.DriftEngine
import com.jkpark.runwrap.engine.HeatEngine
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.engine.displayTitle
import com.jkpark.runwrap.health.GeoPoint
import com.jkpark.runwrap.health.WorkoutDetail
import com.jkpark.runwrap.health.WorkoutDetailStore
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.charts.SplitBarsChart
import com.jkpark.runwrap.ui.charts.ZoneBarView
import com.jkpark.runwrap.ui.theme.Eyebrow
import com.jkpark.runwrap.ui.theme.IndoorBadge
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.RRTone
import com.jkpark.runwrap.ui.theme.ToneBadge
import com.jkpark.runwrap.ui.theme.rrCard
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/// 세션 상세 — 지도 헤더 + 지표 그리드 + 열 보정 + 구간 페이스 + 드리프트 + 심박 존.
/// iOS `SessionDetailScreen.swift` 이식 (계획서 M5). iOS 대비 빠진 섹션과 이유:
/// - 주법(러닝 다이내믹스) — HC에 진폭·접촉시간 레코드가 없어 섹션째 제외 (계획서 §2.2)
/// - 스토리 공유 카드 — M5 범위 제외 (커트라인 확정)
/// - 지표 그리드의 상승 고도 — HC ElevationGained가 선언 권한에 없어 칼로리로 대체
@Composable
fun SessionDetailScreen(
    run: RunSummary,
    /// 이번 주가 과부하일 때만 전달 — 이 세션의 기여도를 배지로 보여준다 (iOS 동일)
    weeklyContext: WeeklyReport.DistanceCard? = null,
    /// HC에는 프로필이 없다 — 온보딩(#12)이 받은 생년을 넘기면 HRmax가 정확해진다
    birthYear: Int? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(run.id) { WorkoutDetailStore(context) }
    val detail by store.detail.collectAsState()
    val isLoading by store.isLoading.collectAsState()
    LaunchedEffect(run.id) { store.load(run, birthYear) }

    // 경로 세션별 동의 런처 — 자기 앱 소유 세션은 바로 Data가 와서 실기기 타 앱 세션에서만 뜬다
    val routeLauncher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        route?.let { granted ->
            store.applyConsentRoute(granted.route.map { GeoPoint(it.latitude, it.longitude) })
        }
    }

    Box(Modifier.fillMaxSize().background(RR.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 실내 세션은 경로가 없어 지도 헤더 자체를 걸어 두지 않는다 (기획서 §4.6)
            if (!run.isIndoor) {
                MapHeader(detail, isLoading, run,
                          onRequestConsent = { routeLauncher.launch(run.id) })
            }

            Column(
                Modifier.padding(horizontal = 18.dp)
                    // 지도 헤더가 없으면 뒤로가기 버튼 자리 확보 (iOS 동일)
                    .padding(top = if (run.isIndoor) 44.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Eyebrow(dateLineFormat.format(run.start))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(run.displayTitle, style = RR.display(27.sp), color = RR.text)
                        if (run.isIndoor) IndoorBadge()
                    }
                }

                contributionBadge(run, weeklyContext)?.let { badge ->
                    Text(
                        badge,
                        Modifier.background(RR.dangSoft, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = RR.dang,
                    )
                }

                StatsGrid(run, detail)

                // 야외 + 날씨 메타데이터 + 열 점수 38 초과일 때만 — 수치 가드는 HeatEngine이 건다
                val heat = if (!run.isIndoor) {
                    run.paceSecPerKm?.let {
                        HeatEngine.adjustment(it, run.weatherTempC, run.weatherHumidityPct)
                    }
                } else null
                heat?.let { HeatCard(it) }

                detail?.takeIf { it.splits.size >= 3 }?.let { SplitsCard(it) }
                detail?.drift?.let { DriftCard(it) }
                detail?.let { d -> d.zones?.let { ZonesCard(it, d) } }

                if (run.isIndoor) {
                    Text(
                        "실내 러닝에는 경로·고도 데이터가 없어서 해당 섹션이 표시되지 않아요.",
                        Modifier.padding(horizontal = 4.dp),
                        fontSize = 11.5.sp, lineHeight = 17.sp, color = RR.text3,
                    )
                }
            }
        }

        BackButton(onBack)
    }
}

/// "8.21 (금) · 06:12" — iOS dateLine의 "M.d (E) · HH:mm" 그대로
private val dateLineFormat =
    DateTimeFormatter.ofPattern("M.d (E) · HH:mm", Locale.KOREAN).withZone(SEOUL)

/// 과부하 주간에 이 세션이 최근 7일 거리의 40% 이상이면 맥락 배지 (iOS 동일)
private fun contributionBadge(run: RunSummary, context: WeeklyReport.DistanceCard?): String? {
    val card = context ?: return null
    val km = run.distanceKm ?: return null
    if (card.recent7Km <= 0) return null
    if (run.start < Instant.now().minusSeconds(7 * 86_400)) return null
    val share = km / card.recent7Km
    if (share < 0.4) return null
    return "이번 주 거리의 ${(share * 100).roundToInt()}%가 이 한 번에서 나왔어요"
}

// MARK: - 지도 헤더

@Composable
private fun MapHeader(
    detail: WorkoutDetail?,
    isLoading: Boolean,
    run: RunSummary,
    onRequestConsent: () -> Unit,
) {
    val route = detail?.route.orEmpty()
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        when {
            // 키가 없으면 SDK가 빈 타일만 그린다 — 지도 대신 빈 상태로 가드 (계획서 리스크 표)
            BuildConfig.MAPS_API_KEY.isEmpty() ->
                MapPlaceholder("지도 키가 없어 경로를 그릴 수 없어요")
            route.size >= 2 -> RouteMap(route)
            detail?.routeConsentRequired == true -> RouteConsentBox(onRequestConsent)
            else -> MapPlaceholder(if (isLoading) "경로를 불러오는 중" else "경로 기록이 없어요")
        }

        // 상단 그라데이션 — 상태바·뒤로가기 버튼 가독성 (iOS 동일)
        Box(
            Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopStart)
                .background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent))),
        )

        run.distanceKm?.let { km ->
            Text(
                "러닝 경로 · ${Format.km(km)} km",
                Modifier.align(Alignment.BottomStart).padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, color = Color.White,
            )
        }
    }
}

/// 정적 경로 지도 — iOS Map(interactionModes: []) 대응. 제스처·컨트롤 전부 끈다
@Composable
private fun RouteMap(route: List<GeoPoint>) {
    val points = remember(route) { route.map { LatLng(it.latitude, it.longitude) } }
    val bounds = remember(points) {
        LatLngBounds.builder().apply { points.forEach(::include) }.build()
    }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bounds.center, 13f)
    }
    // GoogleMap 콘텐츠는 별도 컴포지션(MapApplier)이라 토큰 읽기를 여기서 끝낸다
    val brand = RR.brand
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camera,
        uiSettings = MapUiSettings(
            compassEnabled = false, indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false, myLocationButtonEnabled = false,
            rotationGesturesEnabled = false, scrollGesturesEnabled = false,
            scrollGesturesEnabledDuringRotateOrZoom = false, tiltGesturesEnabled = false,
            zoomControlsEnabled = false, zoomGesturesEnabled = false,
        ),
        // 타일이 준비된 뒤에야 경로 전체가 화면에 맞게 잡힌다 (bounds는 지도 크기가 필요)
        onMapLoaded = { camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 48)) },
    ) {
        Polyline(
            points = points,
            color = brand,
            width = 11f,
            startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
        )
    }
}

@Composable
private fun MapPlaceholder(message: String) {
    Box(Modifier.fillMaxSize().background(RR.surface2), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🗺️", fontSize = 24.sp)
            Text(message, fontSize = 12.5.sp, color = RR.text3)
        }
    }
}

/// HC 세션별 경로 동의 배너 — iOS에는 없는 개념 (ExerciseRouteResult.ConsentRequired)
@Composable
private fun RouteConsentBox(onRequestConsent: () -> Unit) {
    Box(Modifier.fillMaxSize().background(RR.surface2), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🗺️", fontSize = 24.sp)
            Text("경로를 보려면 헬스 커넥트 동의가 필요해요",
                 fontSize = 12.5.sp, color = RR.text3)
            Text(
                "경로 보기 동의",
                Modifier.background(RR.brand, RoundedCornerShape(9.dp))
                    .clickable(onClick = onRequestConsent)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier.padding(start = 14.dp, top = 8.dp).size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로",
             Modifier.size(18.dp), tint = Color.White)
    }
}

// MARK: - 지표 그리드 (3×2)

@Composable
private fun StatsGrid(run: RunSummary, detail: WorkoutDetail?) {
    Column(Modifier.fillMaxWidth().rrCard().padding(horizontal = 18.dp)) {
        Row {
            StatCell("거리", run.distanceKm?.let(Format::km) ?: "—", "km")
            StatCell("시간", Format.duration(run.durationSec), "h:m:s")
            StatCell("평균 페이스", run.paceSecPerKm?.let(Format::pace) ?: "—", "/km")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(RR.line))
        Row {
            StatCell("평균 심박", run.avgHeartRate?.let { "${it.roundToInt()}" } ?: "—", "bpm")
            StatCell("케이던스", detail?.cadenceSpm?.let { "${it.roundToInt()}" } ?: "—", "spm")
            // iOS의 상승 고도 자리 — HC ElevationGained가 선언 권한에 없어 칼로리로 대체
            StatCell("칼로리", run.calories?.let { "${it.roundToInt()}" } ?: "—", "kcal")
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String, unit: String) {
    Column(
        Modifier.weight(1f).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontSize = 11.sp, color = RR.text3)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold,
             fontFamily = FontFamily.Monospace, color = RR.text, maxLines = 1)
        Text(unit, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = RR.text3)
    }
}

// MARK: - 열 보정 페이스 (제안 문서 A1)

@Composable
private fun HeatCard(heat: HeatEngine.Adjustment) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        CardTitleRow("열 보정 페이스", "heat adjusted")

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(Format.pace(heat.adjustedPaceSecPerKm), fontSize = 26.sp,
                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                 color = RR.text)
            Text("/km 상당", Modifier.padding(start = 6.dp, bottom = 3.dp),
                 fontSize = 11.5.sp, color = RR.text3)
            Spacer(Modifier.weight(1f))
            Text(
                "더위 몫 ${heat.deltaSecPerKm.roundToInt()}초/km",
                Modifier.background(RR.warnSoft, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = RR.warn,
            )
        }

        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = RR.text2)) {
                    append("기온 ${heat.tempC.roundToInt()}°C · 습도 " +
                           "${heat.humidityPct.roundToInt()}%에서 뛰었어요. 서늘한 날이었다면 ")
                }
                withStyle(SpanStyle(color = RR.pos, fontWeight = FontWeight.SemiBold)) {
                    append(Format.paceKm(heat.adjustedPaceSecPerKm))
                }
                withStyle(SpanStyle(color = RR.text2)) {
                    append(" 수준 — 더위 몫까지 뛰었으니 오늘 기록, 억울해하지 않으셔도 됩니다.")
                }
            },
            Modifier.padding(top = 10.dp),
            fontSize = 12.5.sp, lineHeight = 18.sp,
        )
    }
}

// MARK: - 구간별 페이스

@Composable
private fun SplitsCard(detail: WorkoutDetail) {
    val paces = detail.splits.map { it.paceSecPerKm }
    val avg = paces.sum() / paces.size
    val lastQuarter = detail.splits.takeLast(maxOf(detail.splits.size / 4, 1))
    val lastAvg = lastQuarter.sumOf { it.paceSecPerKm } / lastQuarter.size
    val drift = (lastAvg - avg).roundToInt()

    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        CardTitleRow("구간별 페이스", "km splits")

        Text(
            buildAnnotatedString {
                when {
                    drift >= 5 -> {
                        withStyle(SpanStyle(color = RR.text2)) {
                            append("후반 ${lastQuarter.size} km에서 평균보다 ")
                        }
                        withStyle(SpanStyle(color = RR.warn, fontWeight = FontWeight.SemiBold)) {
                            append("${drift}초")
                        }
                        withStyle(SpanStyle(color = RR.text2)) {
                            append(" 느려졌습니다. 페이스 유지 실패 구간이 있어요.")
                        }
                    }
                    drift <= -5 -> {
                        withStyle(SpanStyle(color = RR.text2)) {
                            append("후반 ${lastQuarter.size} km를 평균보다 ")
                        }
                        withStyle(SpanStyle(color = RR.pos, fontWeight = FontWeight.SemiBold)) {
                            append("${-drift}초")
                        }
                        withStyle(SpanStyle(color = RR.text2)) {
                            append(" 빠르게 마쳤습니다. 네거티브 스플릿이에요.")
                        }
                    }
                    else -> withStyle(SpanStyle(color = RR.text2)) {
                        append("처음부터 끝까지 페이스가 고르게 유지됐습니다.")
                    }
                }
            },
            Modifier.padding(top = 9.dp),
            fontSize = 13.sp, lineHeight = 19.sp,
        )

        Box(Modifier.padding(top = 14.dp)) { SplitBarsChart(detail.splits) }
    }
}

// MARK: - 심박 드리프트 (Pw:HR 디커플링, 제안 문서 A2)

@Composable
private fun DriftCard(drift: DriftEngine.Result) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        CardTitleRow("심박 드리프트", "hr decoupling")

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "%+.1f%%".format(Locale.ROOT, drift.decouplingPct),
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = drift.tone.color,
            )
            Spacer(Modifier.weight(1f))
            ToneBadge(tone = drift.tone)
        }

        // 사실 먼저, 위트는 뒤 — 톤별 문장은 Friel 5% 기준을 그대로 옮긴다
        Text(
            when (drift.tone) {
                RRTone.IMPROVING ->
                    "후반에 오히려 심박 효율이 좋아졌어요. 엔진이 늦게 데워지는 타입이거나 " +
                        "컨디션이 계속 올라왔거나 — 어느 쪽이든 좋은 신호입니다."
                RRTone.CAUTION ->
                    "같은 페이스인데 후반 심박이 ${drift.decouplingPct.roundToInt()}% 더 들었어요. " +
                        "이 거리엔 유산소 기반이 아직 덜 자랐다는 신호 — 편한 페이스 러닝을 " +
                        "늘리면 따라옵니다."
                else ->
                    "전·후반 심박 효율 차이가 5% 안이에요. 오늘 페이스는 몸이 끝까지 " +
                        "감당했다는 뜻입니다."
            },
            Modifier.padding(top = 10.dp),
            fontSize = 12.5.sp, lineHeight = 18.sp, color = RR.text2,
        )
    }
}

// MARK: - 심박 구간

@Composable
private fun ZonesCard(zones: List<Double>, detail: WorkoutDetail) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("심박 구간", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RR.text)

        Box(Modifier.padding(top = 14.dp)) { ZoneBarView(zones) }

        Column(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // 세션 최고 심박 — HRmax 대비 %로 강도를 한눈에 (제안 문서 A4)
            val peak = detail.maxHeartRateBpm
            val hrMax = detail.hrMaxBpm
            if (peak != null && hrMax != null && hrMax > 0) {
                Text(
                    "최고 심박 ${peak.roundToInt()} bpm · " +
                        (if (detail.hrMaxEstimated) "추정 " else "") +
                        "HRmax의 ${(peak / hrMax * 100).roundToInt()}%",
                    fontSize = 11.sp, color = RR.text3,
                )
            }
            if (detail.hrMaxEstimated) {
                // iOS는 "건강 앱에 생년월일" — Android는 온보딩(#12)이 생년을 받으므로 문안 적응
                Text(
                    "최대 심박 190 bpm 추정 기준 · 설정에서 생년월일을 넣으면 더 정확해져요",
                    fontSize = 11.sp, color = RR.text3,
                )
            }
        }
    }
}

/// 카드 제목 + 오른쪽 영문 코드 라벨 — 이 화면 카드들의 공통 머리 (iOS HStack 대응)
@Composable
private fun CardTitleRow(title: String, code: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RR.text)
        Spacer(Modifier.weight(1f))
        Text(code, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RR.text3)
    }
}
