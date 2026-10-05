package com.jkpark.runwrap.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jkpark.runwrap.BuildConfig
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.ProgressSnapshot
import com.jkpark.runwrap.engine.RaceCalendar
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RaceFavorites
import com.jkpark.runwrap.engine.RaceFormat
import com.jkpark.runwrap.engine.RaceKey
import com.jkpark.runwrap.engine.timeIntervalSince1970
import com.jkpark.runwrap.store.add
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RegisterBadge
import com.jkpark.runwrap.ui.rrCard
import java.time.Instant
import kotlin.math.cos
import kotlin.math.log2

/// 대회 상세 — 종목·일시·장소·접수기간 + 참가하기 버튼 (기획서 §4.14, 계획서 M13-3).
/// 참가비·기념품은 로드런에 구조화 필드가 없다 — 기타소개(note)에 담긴 경우만
/// '대회 소개'로 보여주고, 없으면 아예 표시하지 않는다 (미노출 가드).
/// (Android: 내비게이션 바는 화면이 직접 그린다 — 뒤로 버튼 + 가운데 제목)
@Composable
fun RaceDetailScreen(
    entry: RaceEngine.Entry,
    onBack: () -> Unit = {},
) {
    val race = entry.race
    val container = LocalAppContainer.current
    val context = LocalContext.current
    // 즐겨찾기·캘린더·목표 대회 (이슈 #172)
    /// 대회 목록 스토어 — 즐겨찾기가 바뀌면 접수 알림을 다시 건다
    val raceStore = container.raceStore
    var favoritesRaw by container.settings.rememberSetting(RaceKey.favorites, "")
    var targetID by container.settings.rememberSetting(RaceKey.targetID, 0)
    /// 목표 대회 지정 때 대회일을 넣는다 — 설정 '대회 날짜'와 같은 저장 형식(timeIntervalSince1970)
    var raceDateRaw by container.settings.rememberSetting(ProfileKey.raceDate, 0.0)
    // (Android: 캘린더 앱의 새 일정 화면을 여는 것까지만 알 수 있어 '추가됐어요' 잠금·접근 거부 안내는 없다)
    var showsCalendarFailed by remember { mutableStateOf(false) }

    val isFavorite = race.id in RaceFavorites.decode(favoritesRaw)
    val isTarget = targetID == race.id

    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        NavBar("대회 상세", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // MARK: 헤더
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(RaceFormat.dDay(entry.dDay))
                    RegisterBadge(entry.status)
                    Spacer(Modifier.weight(1f))
                    /// 헤더 우측 별 — 즐겨찾기는 목록 필터와 접수 알림의 대상이 된다
                    Box(
                        Modifier
                            .size(36.dp)
                            .toggleable(value = isFavorite, role = Role.Checkbox) {
                                favoritesRaw = RaceFavorites.encode(RaceFavorites.toggled(RaceFavorites.decode(favoritesRaw), race.id))
                                raceStore.rescheduleRaceAlarms()
                            }
                            .semantics { contentDescription = "즐겨찾기" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            RRIcons.named(if (isFavorite) "star.fill" else "star"), contentDescription = null,
                            tint = if (isFavorite) RR.warn else RR.text3, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(race.name, style = RR.display(27.sp).lineSpacing(5), color = RR.text)
            }

            race.categories?.takeIf { it.isNotEmpty() }?.let { CategoryChips(it) }
            race.imageUrl?.takeIf { it.isNotEmpty() }?.let { PosterCard(it) }
            InfoCard(entry)
            val lat = race.lat
            val lon = race.lon
            if (lat != null && lon != null) MapCard(lat, lon, title = race.place ?: race.name)
            race.note?.let { NoteCard(it) }

            // MARK: 즐겨찾기·캘린더·목표 대회 (이슈 #172)
            /// 캘린더 추가 + 목표 대회 지정 — 참가하기 버튼 위 한 줄
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("캘린더에 추가", "calendar.badge.plus", Modifier.weight(1f)) {
                    try {
                        RaceCalendar.add(context, entry)
                    } catch (_: ActivityNotFoundException) {
                        showsCalendarFailed = true
                    }
                }
                ActionButton(
                    if (isTarget) "목표 대회 해제" else "목표 대회로 지정",
                    if (isTarget) "flag.slash" else "flag",
                    Modifier.weight(1f),
                ) {
                    /// 지정하면 대회일을 설정의 '대회 날짜'로 넣어 대회 목표(훈련 가이드 D-day)를 켠다 —
                    /// 종목·목표 기록은 사용자가 설정에서 그대로 관리한다. 해제는 지정만 지우고 대회 날짜는 남긴다
                    if (isTarget) {
                        targetID = 0
                    } else {
                        targetID = race.id
                        raceDateRaw = entry.raceDate.timeIntervalSince1970
                        // 대회 날짜는 iCloud 진행도 스냅샷에 담긴다 — 설정 화면과 같이 병합 기준 시각을 갱신한다 (이슈 #130)
                        ProgressSnapshot.markLocalChanged(defaults = container.settings, now = Instant.now())
                    }
                }
            }

            race.homepage?.takeIf { it.isNotEmpty() }?.let { homepage ->
                JoinButton {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(homepage)))
                    } catch (_: ActivityNotFoundException) {
                        // 열 수 있는 브라우저가 없으면 아무것도 하지 않는다 (iOS Link와 같다)
                    }
                }
            }

            Text(
                "자료: 로드런(roadrun.co.kr). 대회 내용은 주최 측 사정으로 바뀔 수 있어요 — 참가 전에 대회 홈페이지에서 꼭 확인해 주세요.",
                style = TextStyle(fontSize = 11.5.sp).lineSpacing(3),
                color = RR.text3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
            )
        }
    }

    if (showsCalendarFailed) {
        AlertDialog(
            onDismissRequest = { showsCalendarFailed = false },
            confirmButton = { TextButton(onClick = { showsCalendarFailed = false }) { Text("확인") } },
            title = { Text("캘린더에 추가하지 못했어요") },
            text = { Text("잠시 후 다시 시도해 주세요.") },
        )
    }
}

/// 내비게이션 바 — 뒤로 버튼 + 인라인 제목 (iOS `.navigationBarTitleDisplayMode(.inline)`)
@Composable
private fun NavBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RRIcons.named("chevron.left"), contentDescription = "뒤로", tint = RR.brand, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = RR.text,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/// 종목 칩 — 종목이 많은 대회(풀·하프·10km·5km…)는 가로 스크롤로 흘린다
@Composable
private fun CategoryChips(categories: List<String>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (category in categories) {
            Text(
                category,
                style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = RR.brand,
                modifier = Modifier
                    .background(RR.brandSoft, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

/// 대회 포스터 — 크롤러가 홈페이지에서 뽑은 대표 이미지(imageUrl)를 원본 비율로 보여준다.
/// 목록의 소형 썸네일과 같은 원본이고, 로딩 실패면 자리를 차지하지 않는다 (#32)
@Composable
private fun PosterCard(url: String) {
    RemoteImage(url) { phase ->
        when (phase) {
            is RemoteImagePhase.Success -> {
                val shape = RoundedCornerShape(20.dp)
                Image(
                    phase.image, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(phase.image.width.toFloat() / phase.image.height)
                        .clip(shape)
                        .border(1.dp, RR.line, shape),
                )
            }
            RemoteImagePhase.Empty ->
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RR.text3)
                }
            RemoteImagePhase.Failure -> Unit
        }
    }
}

// MARK: 정보 카드

@Composable
private fun InfoCard(entry: RaceEngine.Entry) {
    val race = entry.race
    var dateLine = RaceFormat.fullDate(entry.raceDate)
    race.startTime?.let { dateLine += " $it 출발" }
    Column(
        Modifier.fillMaxWidth().rrCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        InfoRow("calendar", "일시", dateLine)
        race.place?.let { place ->
            InfoRow("mappin.and.ellipse", "장소", listOfNotNull(race.region, place).joinToString(" · "))
        }
        RaceFormat.registerPeriodLong(race)?.let { InfoRow("square.and.pencil", "접수기간", it) }
        race.host?.let { InfoRow("person.2", "주최", it) }
    }
}

@Composable
private fun InfoRow(symbol: String, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(18.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
            Icon(RRIcons.named(symbol), contentDescription = null, tint = RR.text3, modifier = Modifier.size(13.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TextStyle(fontSize = 10.5.sp), color = RR.text3)
            Text(value, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium).lineSpacing(3), color = RR.text)
        }
    }
}

/// 대회장 지도 — 로드런 상세의 좌표를 그대로 쓴다. 보기 전용(스크롤 방해 금지)
/// (Android: Google 지도 라이트 모드(정지 이미지)로 그린다. 키가 없으면 지도 자리만 빈 카드로 남긴다)
@Composable
private fun MapCard(lat: Double, lon: Double, title: String) {
    val shape = RoundedCornerShape(20.dp)
    val frame = Modifier.fillMaxWidth().height(170.dp).clip(shape).border(1.dp, RR.line, shape)
    if (BuildConfig.MAPS_API_KEY.isEmpty()) {
        Box(frame.background(RR.surface2), contentAlignment = Alignment.Center) {
            Icon(RRIcons.named("map"), contentDescription = null, tint = RR.text3, modifier = Modifier.size(22.dp))
        }
        return
    }
    val coordinate = LatLng(lat, lon)
    // MapKit 1,800m 영역을 170dp 높이에 맞추는 줌 — 256dp 타일 기준 dp당 미터 = 156543.03·cos(위도)/2^줌
    val zoom = log2(156_543.03 * cos(Math.toRadians(lat)) * 170 / 1_800).toFloat()
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(coordinate, zoom) }
    val hue = FloatArray(3).also { android.graphics.Color.colorToHSV(RR.brand.toArgb(), it) }[0]
    GoogleMap(
        modifier = frame,
        cameraPositionState = cameraState,
        googleMapOptionsFactory = { GoogleMapOptions().liteMode(true).camera(CameraPosition.fromLatLngZoom(coordinate, zoom)) },
        uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, scrollGesturesEnabled = false,
            zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false),
        onMapClick = {},   // 라이트 모드 기본 동작(지도 앱 열기)을 막는다 — 보기 전용
    ) {
        Marker(
            state = rememberUpdatedMarkerState(coordinate),
            title = title,
            icon = BitmapDescriptorFactory.defaultMarker(hue),
            onClick = { true },
        )
    }
}

/// 기타소개 원문 — 참가비·기념품 안내가 실려 오는 자리라 그대로 보여준다
@Composable
private fun NoteCard(note: String) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(RR.surface2, shape)
            .border(1.dp, RR.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("대회 소개", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = RR.text)
        Text(note, style = TextStyle(fontSize = 13.sp).lineSpacing(4), color = RR.text2)
    }
}

/// 상세 하단 보조 버튼(캘린더·목표 대회)의 라벨 모양 — 반폭 두 개가 한 줄에 들어가게 줄여 맞춘다
/// (Android: iOS `.bordered` 스타일 — 옅은 브랜드 배경 + 브랜드 글자)
@Composable
private fun ActionButton(title: String, symbol: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(RR.brandSoft)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named(symbol), contentDescription = null, tint = RR.brand, modifier = Modifier.size(15.dp))
        FitText(title, TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), RR.brand, minScale = 0.8f)
    }
}

@Composable
private fun JoinButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RR.brand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RRIcons.named("arrow.up.right"), contentDescription = null, tint = RR.onBrand, modifier = Modifier.size(15.dp))
        Text("참가하기", style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), color = RR.onBrand)
    }
}

/// SwiftUI `.lineSpacing(n)` — 줄 높이를 글자 크기 1.2배 + n으로 잡는다
private fun TextStyle.lineSpacing(spacing: Int): TextStyle = copy(lineHeight = (fontSize.value * 1.2f + spacing).sp)
