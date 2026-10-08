package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlin.math.roundToInt
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.jkpark.runwrap.engine.FlyoverEngine
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RRSegmented
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard

/// 앱 안 경로 플라이오버 재생 (이슈 #224, #232, iOS `RouteFlyoverScreen.swift`) — 기울인 지도 위를 카메라가 경로를 따라 거리 비례 길이(km당 10초, 20~180초)로 날아간다.
/// 카메라는 매 프레임 현재 위치 점 위에 직접 놓는다(`camera.move`) — 따로 도는 애니메이션은 점과 어긋나고 제스처에 끊긴다(#232).
/// (Android: 3D 지형(`elevation: .realistic`)이 없어 기울기 + 3D 건물만 쓴다 — docs/parity.md)
/// 점·지나온 경로·HUD는 같은 재생 시계(withFrameNanos)에서 그린다. 키프레임 배치·방위 보간은 `FlyoverEngine`이 정한다.
@Composable
fun RouteFlyoverScreen(track: FlyoverEngine.Track, onClose: () -> Unit) {
    val keyframes = remember(track) { FlyoverEngine.keyframes(track) }
    val points = remember(track) { track.points.map { LatLng(it.lat, it.lon) } }
    var playID by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    /// 재생 배속 — 재생 중 HUD 세그먼트로 바꾼다. 경과는 매 프레임 (프레임 간격 × 배속)을 쌓으므로 바꿔도 위치가 튀지 않는다 (iOS와 같은 0.5·1·2배)
    var speed by remember { mutableDoubleStateOf(1.0) }
    var mapLoaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = cameraAt(keyframes[0]) }
    val density = LocalDensity.current

    LaunchedEffect(playID, mapLoaded) {
        // 지도가 뜨기 전에 시계를 돌리면 카메라만 늦게 출발해 점과 어긋난다
        if (!mapLoaded) return@LaunchedEffect
        playing = true
        progress = 0.0
        camera.move(CameraUpdateFactory.newCameraPosition(cameraAt(keyframes[0])))
        var last = withFrameNanos { it }
        var elapsed = 0.0
        while (progress < 1) {
            val now = withFrameNanos { it }
            elapsed += (now - last) / 1e9 * speed
            last = now
            progress = elapsed / track.playbackSec
            // 재생 중 핀치로 바꾼 줌은 유지하고 중심·방위만 매 프레임 덮어쓴다 (#232)
            val f = FlyoverEngine.frame(track, progress)
            camera.move(CameraUpdateFactory.newCameraPosition(CameraPosition(
                LatLng(f.lat, f.lon), camera.position.zoom, cameraPitch,
                (((FlyoverEngine.heading(keyframes, elapsed) % 360) + 360) % 360).toFloat(),
            )))
        }
        playing = false
        // 끝나면 천천히 빠져나와 뛰어온 코스 전체를 보여준다 (iOS outroSec 6초, 사방 25% 여백)
        val bounds = LatLngBounds.builder().also { b -> points.forEach { b.include(it) } }.build()
        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, with(density) { 72.dp.toPx() }.roundToInt()), 6_000)
    }

    val frame = FlyoverEngine.frame(track, if (playing) progress else 1.0)
    val here = LatLng(frame.lat, frame.lon)
    Box(Modifier.fillMaxSize().background(RR.bg)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            // muted 지도 스타일(iOS)은 Google 지도에 없다 — 건물을 끄고 경로를 굵게·흰 테두리로 도드라지게 (#232)
            properties = MapProperties(isBuildingEnabled = false),
            uiSettings = MapUiSettings(
                compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false,
                myLocationButtonEnabled = false, zoomControlsEnabled = false,
                // 재생 중엔 줌만 허용 — 카메라가 매 프레임 중심을 덮어쓰므로 이동·회전·기울기는 막는다 (#232)
                rotationGesturesEnabled = !playing, scrollGesturesEnabled = !playing,
                scrollGesturesEnabledDuringRotateOrZoom = false, tiltGesturesEnabled = !playing,
                zoomGesturesEnabled = true,
            ),
            onMapLoaded = { mapLoaded = true },
        ) {
            val passed = points.take(frame.passedCount) + here
            Polyline(
                points = points, color = RR.brand.copy(alpha = 0.35f), width = with(density) { 6.dp.toPx() },
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
            )
            // 지나온 경로 — 흰 테두리(halo) 위에 브랜드 색. #222 RoutePaceEngine 머지 후 페이스 색 구간(Polyline 여러 개)으로 교체한다
            Polyline(
                points = passed, color = RR.onBrand, width = with(density) { 10.dp.toPx() },
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND, zIndex = 1f,
            )
            Polyline(
                points = passed, color = RR.brand, width = with(density) { 6.dp.toPx() },
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND, zIndex = 2f,
            )
            // 현재 위치 점 — 반지름은 m 단위라 카메라 줌(17)에서 iOS 16pt 점과 비슷한 크기로 맞췄다
            Circle(
                center = here, radius = 8.0, fillColor = RR.brand, strokeColor = RR.onBrand,
                strokeWidth = with(density) { 3.dp.toPx() }, zIndex = 3f,
            )
        }

        Hud(frame, playing, speed, onSpeed = { speed = it }, onReplay = { playID += 1 }, Modifier.align(Alignment.BottomCenter))

        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 7.dp, top = 1.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).background(RR.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(RRIcons.named("xmark"), "닫기", Modifier.size(15.dp), tint = RR.text)
            }
        }
    }
}

/// 카메라 — iOS 거리 800m·기울기 60°에 맞춘 줌 17·기울기 60°
private const val cameraPitch = 60f
private fun cameraAt(k: FlyoverEngine.Keyframe): CameraPosition =
    CameraPosition(LatLng(k.lat, k.lon), 17f, cameraPitch, (((k.headingDeg % 360) + 360) % 360).toFloat())

@Composable
private val speeds = listOf(0.5, 1.0, 2.0)

@Composable
private fun Hud(
    frame: FlyoverEngine.Frame, playing: Boolean, speed: Double,
    onSpeed: (Double) -> Unit, onReplay: () -> Unit, modifier: Modifier,
) {
    Column(
        modifier
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 12.dp)
            .fillMaxWidth()
            .rrCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Stat("거리", "${Format.km(frame.distanceM / 1_000)} km", Modifier.weight(1f))
            Stat("시간", Format.duration(frame.elapsedSec), Modifier.weight(1f))
            Stat("페이스", frame.paceSecPerKm?.let(Format::paceKm) ?: "—", Modifier.weight(1f))
        }
        if (playing) {
            RRSegmented(
                options = speeds.map { if (it == 0.5) "0.5×" else "${it.toInt()}×" },
                selected = speeds.indexOf(speed),
                onSelect = { onSpeed(speeds[it]) },
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onReplay)
                    .background(RR.brand)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RRIcons.named("arrow.counterclockwise"), null, Modifier.size(15.dp), tint = RR.onBrand)
                Text("다시 재생", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RR.onBrand)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = RR.text3)
        Text(value, style = mono(17.sp, FontWeight.Bold), color = RR.text)
    }
}
