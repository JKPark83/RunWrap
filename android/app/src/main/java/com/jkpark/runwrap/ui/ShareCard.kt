package com.jkpark.runwrap.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.engine.RoutePaceEngine
import com.jkpark.runwrap.engine.RoutePrivacy
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.TrackPoint
import com.jkpark.runwrap.engine.swiftRoundedInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/// 인스타그램 스토리 공유 카드 — 기획서 §4.4, 계획서 M5.
/// 9:16(360×640pt, @3x = 1080×1920px) 고정 카드 2종(미니멀 데이터형·사진 배경형)과
/// 경로 스냅샷·렌더 유틸. 라이브 Map은 ImageRenderer가 그리지 못해
/// 경로는 MKMapSnapshotter 정적 이미지 위에 polyline을 직접 그려 만든다.
/// (Android: 지도 타일 없이 경로만 Canvas로 그린다 — GoogleMap 스냅샷·Static Maps API는
/// 경로 좌표를 기기 밖으로 보내거나 캡처가 불안정해 쓰지 않는다)

// MARK: - 미니멀 데이터형 카드

/// [route]는 호출부가 이미 `RoutePrivacy.trimmed`로 다듬은 경로 — 2점 미만이면 수치 블록을 보인다
/// (iOS는 `routeImage: UIImage?`를 받는다)
@Composable
fun ShareCardView(
    run: RunSummary,
    zones: List<Double>? = null,
    route: List<TrackPoint>? = null,
    /// "최근 7일 3회 · 24.5 km" — 세션 목록에서 계산해 넘긴다 (기획서 §4.4 주간 요약)
    weeklySummary: String? = null,
) = FixedFontScale {
    val cardShape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .size(360.dp, 640.dp)
            .background(RR.bg)
            .padding(28.dp),
    ) {
        Eyebrow("런미새 · " + RoutePrivacy.cardDateLine(run.start, ZoneId.systemDefault()))

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                run.distanceKm?.let(Format::km) ?: "—", Modifier.alignByBaseline(),
                style = mono(62.sp, FontWeight.ExtraBold), color = RR.text,
            )
            Text("km", Modifier.alignByBaseline(), style = mono(20.sp, FontWeight.Bold), color = RR.text3)
        }

        Row(Modifier.padding(top = 20.dp)) {
            Stat("평균 페이스", run.paceSecPerKm?.let(Format::pace) ?: "—", "/km")
            Stat("시간", Format.duration(run.durationSec), "h:m:s")
            Stat("평균 심박", run.avgHeartRate?.let { "${it.swiftRoundedInt()}" } ?: "—", "bpm")
        }

        if (route != null && route.size >= 2) {
            RouteCanvas(
                route,
                Modifier
                    .padding(top = 22.dp)
                    .fillMaxWidth()
                    .height(204.dp)
                    .clip(cardShape)
                    .background(RR.surface2)
                    .border(1.dp, RR.line, cardShape),
            )
        } else {
            // 경로 이미지가 없으면(실내·경로 숨기기·트림 후 잔여 없음) 지도 대신 수치 강조 블록 (계획서 M5 완료 기준, 이슈 #84)
            Column(
                Modifier
                    .padding(top = 22.dp)
                    .fillMaxWidth()
                    .height(204.dp)
                    .background(RR.surface2, cardShape)
                    .border(1.dp, RR.line, cardShape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 경로를 숨긴 야외 러닝도 이 블록을 쓴다 — 트레드밀로 오표기하지 않는다 (이슈 #84)
                Text(
                    if (run.isIndoor) "TREADMILL RUN" else "OUTDOOR RUN",
                    style = mono(11.sp, FontWeight.SemiBold).copy(letterSpacing = 1.4.sp),
                    color = RR.text3,
                )
                Row {
                    Stat("시간", Format.duration(run.durationSec), "h:m:s")
                    Stat("칼로리", run.calories?.let(Format::kcal) ?: "—", "kcal")
                }
            }
        }

        if (zones != null) {
            ZoneBarView(zones, Modifier.padding(top = 22.dp))
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)

        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                weeklySummary ?: "RUNNER REPORT",
                style = mono(12.sp, FontWeight.SemiBold), color = RR.text2,
            )
            Spacer(Modifier.weight(1f))
            Text("런미새", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = RR.brand)
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, unit: String, white: Boolean = false, valueSize: Float = 22f) {
    // 사진 카드(white)는 모드 무관 고정 흑백 — PhotoCardView 주석 참조
    val dim = if (white) Color.White.copy(alpha = 0.65f) else RR.text3
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = TextStyle(fontSize = 11.sp), color = dim)
        FitText(value, mono(valueSize.sp, FontWeight.Bold), if (white) Color.White else RR.text, minScale = 0.6f)
        Text(unit, style = mono(10.5.sp), color = dim)
    }
}

/// 경로만 그린 정적 지도 대용 — iOS 스냅샷 크기(360×240, 세션 상세 호출부)의 좌표계에
/// `RouteSnapshot.region`을 메르카토르로 맞춘 뒤, 블록에 scaledToFill로 채운다
@Composable
/// 지도 헤더와 같은 페이스 색 구간으로 그린다 — 구간을 못 내면(표본 부족) 단색 brand (이슈 #222)
private fun RouteCanvas(route: List<TrackPoint>, modifier: Modifier) {
    val pieces = RoutePaceEngine.segments(route)?.map { it.points to it.color } ?: listOf(route to RR.brand)
    Canvas(modifier) {
        val fill = max(size.width / 360f, size.height / 240f)   // 스냅샷 1pt당 px
        val region = RouteSnapshot.region(route.map { GeoPoint(it.lat, it.lon) })
        fun mercY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        val cy = mercY(region.centerLat)
        val spanX = Math.toRadians(region.lonDelta)
        val spanY = mercY(region.centerLat + region.latDelta / 2) - mercY(region.centerLat - region.latDelta / 2)
        val k = min(360 / spanX, 240 / spanY) * fill   // 라디안당 px — 영역 전체가 보이게 맞춘다
        for ((points, color) in pieces) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = size.width / 2 + (Math.toRadians(p.lon - region.centerLon) * k).toFloat()
                val y = size.height / 2 - ((mercY(p.lat) - cy) * k).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(4.5f * fill, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/// 페이스 색 경로 구간 색 — 판정 불가(정확도 부족, tone nil)는 회색 (이슈 #222).
/// (iOS는 Theme.swift가 위젯 타깃에도 컴파일돼 공유 카드 파일에 둔다 — 같은 자리를 따른다)
val RoutePaceEngine.Segment.color: Color
    @Composable @ReadOnlyComposable get() = tone?.color ?: RR.text3

// MARK: - 사진 배경형 카드

/// 사용자가 고른 사진 위에 어둡기 오버레이 + 핵심 수치.
/// 사진 위 텍스트는 모드와 무관하게 읽혀야 해서 RR 적응 토큰 대신
/// 고정 흑백을 쓴다 — 지도 헤더 오버레이와 같은 예외 (SessionDetailScreen).
@Composable
fun PhotoCardView(run: RunSummary, photo: ImageBitmap? = null) = FixedFontScale {
    Box(Modifier.size(360.dp, 640.dp)) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // 사진 미선택 기본 배경 — 오버레이·텍스트와 같은 고정 무채색 계열
            Box(Modifier.fillMaxSize().background(Color(0.12f, 0.12f, 0.12f)))
        }

        // 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (이 카드의 흰 글자·검정 그라데이션 전부)
        // 수치 가독용 어둡기 오버레이 — 위·아래만 진하게, 가운데는 사진 그대로
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.5f),
                    0.32f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.62f),
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Text(
                "런미새 · ${RoutePrivacy.cardDateLine(run.start, ZoneId.systemDefault())}",
                style = mono(11.sp, FontWeight.SemiBold).copy(letterSpacing = 1.4.sp),
                color = Color.White.copy(alpha = 0.85f),
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    run.distanceKm?.let(Format::km) ?: "—", Modifier.alignByBaseline(),
                    style = mono(58.sp, FontWeight.ExtraBold), color = Color.White,
                )
                Text(
                    "km", Modifier.alignByBaseline(),
                    style = mono(19.sp, FontWeight.Bold), color = Color.White.copy(alpha = 0.7f),
                )
            }

            Row(Modifier.padding(top = 18.dp)) {
                Stat("평균 페이스", run.paceSecPerKm?.let(Format::pace) ?: "—", "/km", white = true, valueSize = 21f)
                Stat("시간", Format.duration(run.durationSec), "h:m:s", white = true, valueSize = 21f)
                Stat("평균 심박", run.avgHeartRate?.let { "${it.swiftRoundedInt()}" } ?: "—", "bpm", white = true, valueSize = 21f)
            }

            Text(
                "런미새", Modifier.padding(top = 22.dp),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

/// 카드는 고정 크기 이미지라 글자가 시스템 글꼴 배율을 따르지 않는다 (iOS `.system(size:)` 고정) —
/// 공유 시트 미리보기와 저장 이미지가 같게 보인다
@Composable
private fun FixedFontScale(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f), content = content)
}

// MARK: - 경로 스냅샷

/// 경로 영역 계산 — 공유 카드 경로와 세션 상세·코스 지도 카메라가 공용 (iOS MKCoordinateRegion 대응)
object RouteSnapshot {
    data class Region(val centerLat: Double, val centerLon: Double, val latDelta: Double, val lonDelta: Double)

    /// 경로 전체가 보이도록 여유(1.4배)를 준 지도 영역 — 세션 상세 지도 헤더와 공용
    fun region(route: List<GeoPoint>): Region {
        val lats = route.map { it.lat }
        val lons = route.map { it.lon }
        return Region(
            centerLat = (lats.min() + lats.max()) / 2,
            centerLon = (lons.min() + lons.max()) / 2,
            latDelta = max((lats.max() - lats.min()) * 1.4, 0.008),
            lonDelta = max((lons.max() - lons.min()) * 1.4, 0.008),
        )
    }
}

// MARK: - 렌더 · 저장

/// 카드 뷰 → 1080×1920 이미지 (360×640 @3x — 계획서 M5), 사진 앱 저장(add-only)
object ShareCardRenderer {
    /// 화면에 그리지 않고 카드를 [layer]에 1080×1920px로 기록한다 (iOS ImageRenderer 대응).
    /// 밀도 3·글꼴 배율 1로 고정해 기기 설정과 무관하게 같은 이미지가 나온다.
    /// 호출부는 이 컴포저블을 화면 트리 아무 곳에 두고, 그려진 뒤 [render]를 부른다.
    @Composable
    fun Source(layer: GraphicsLayer, card: @Composable () -> Unit) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(1080, 1920))
                    layout(0, 0) { placeable.place(0, 0) }
                }
                .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
        ) {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) { card() }
        }
    }

    /// 아직 한 번도 그려지지 않았으면 null — 호출부가 실패 문구를 띄운다
    suspend fun render(layer: GraphicsLayer): ImageBitmap? =
        if (layer.size == IntSize.Zero) null else layer.toImageBitmap()

    /// add-only 저장 — MediaStore 삽입은 API 29+에서 권한이 필요 없다 (읽기 권한 미요청)
    suspend fun saveToPhotos(context: Context, image: ImageBitmap) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "runwrap_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/런미새")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert 실패")
        try {
            val written = resolver.openOutputStream(uri)?.use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
            if (!written) throw IOException("PNG 쓰기 실패")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
