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
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/// 앱 안 경로 플라이오버 재생 (이슈 #224, iOS `RouteFlyoverScreen.swift`) — 기울인 지도 위를 카메라가 경로를 따라 18초 동안 날아간다.
/// (Android: Google 지도에는 iOS `mapCameraKeyframeAnimator`가 없어 키프레임마다 `CameraPositionState.animate`를
///  이어 부른다. 3D 지형(`elevation: .realistic`)도 없어 기울기 + 3D 건물만 쓴다 — docs/parity.md)
/// 점·지나온 경로·HUD는 같은 재생 시계(withFrameNanos)에서 그린다. 키프레임 배치·보간은 `FlyoverEngine`이 정한다.
@Composable
fun RouteFlyoverScreen(track: FlyoverEngine.Track, onClose: () -> Unit) {
    val keyframes = remember(track) { FlyoverEngine.keyframes(track) }
    val points = remember(track) { track.points.map { LatLng(it.lat, it.lon) } }
    var playID by remember { mutableIntStateOf(0) }
    /// 재생 중에는 지도 제스처를 막는다 — 제스처가 들어오면 animate가 취소된다
    var playing by remember { mutableStateOf(true) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    var mapLoaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = cameraAt(keyframes[0]) }

    LaunchedEffect(playID, mapLoaded) {
        // 지도가 뜨기 전에 시계를 돌리면 카메라만 늦게 출발해 점과 어긋난다
        if (!mapLoaded) return@LaunchedEffect
        playing = true
        progress = 0.0
        camera.move(CameraUpdateFactory.newCameraPosition(cameraAt(keyframes[0])))
        coroutineScope {
            launch {
                for (k in keyframes.drop(1)) {
                    camera.animate(CameraUpdateFactory.newCameraPosition(cameraAt(k)), max(1, (k.durationSec * 1_000).roundToInt()))
                }
            }
            val start = withFrameNanos { it }
            while (progress < 1) {
                val now = withFrameNanos { it }
                progress = (now - start) / 1e9 / FlyoverEngine.playbackSec
            }
        }
        playing = false
    }

    val frame = FlyoverEngine.frame(track, if (playing) progress else 1.0)
    val here = LatLng(frame.lat, frame.lon)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(RR.bg)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isBuildingEnabled = true),
            uiSettings = MapUiSettings(
                compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false,
                myLocationButtonEnabled = false, zoomControlsEnabled = false,
                rotationGesturesEnabled = !playing, scrollGesturesEnabled = !playing,
                scrollGesturesEnabledDuringRotateOrZoom = !playing, tiltGesturesEnabled = !playing,
                zoomGesturesEnabled = !playing,
            ),
            onMapLoaded = { mapLoaded = true },
        ) {
            Polyline(
                points = points, color = RR.brand.copy(alpha = 0.3f), width = with(density) { 4.dp.toPx() },
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
            )
            // 지나온 경로 — #222 RoutePaceEngine 머지 후 페이스 색 구간(Polyline 여러 개)으로 교체한다
            Polyline(
                points = points.take(frame.passedCount) + here, color = RR.brand, width = with(density) { 5.dp.toPx() },
                startCap = RoundCap(), endCap = RoundCap(), jointType = JointType.ROUND,
            )
            // 현재 위치 점 — 반지름은 m 단위라 카메라 줌(17)에서 iOS 16pt 점과 비슷한 크기로 맞췄다
            Circle(
                center = here, radius = 7.0, fillColor = RR.brand, strokeColor = RR.onBrand,
                strokeWidth = with(density) { 3.dp.toPx() },
            )
        }

        Hud(frame, playing, onReplay = { playID += 1 }, Modifier.align(Alignment.BottomCenter))

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
private fun cameraAt(k: FlyoverEngine.Keyframe): CameraPosition =
    CameraPosition(LatLng(k.lat, k.lon), 17f, 60f, (((k.headingDeg % 360) + 360) % 360).toFloat())

@Composable
private fun Hud(frame: FlyoverEngine.Frame, playing: Boolean, onReplay: () -> Unit, modifier: Modifier) {
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
        if (!playing) {
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
