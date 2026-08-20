package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/// 좌표 한 점 — iOS `GPXParser.swift`의 GeoPoint 이식.
/// GPX 파싱(코스 탭)은 화면 계층과 함께 이식한다 (M5) — M3에서는 측정소 거리 계산 입력으로만 쓴다
data class GeoPoint(val lat: Double, val lon: Double)

/// 에어코리아 측정소 한 곳 — 번들 측정소 JSON에서 읽는다 (디코딩은 클라이언트 몫, M4)
data class AirStation(val name: String, val lat: Double, val lon: Double)

/// 번들 측정소 파일 스키마
data class AirStationFile(val generatedAt: String, val stations: List<AirStation>)

/// 대기질 4등급 — 에어코리아 통합대기환경지수(CAI) 등급 체계.
/// 선언 순서 = raw 순서라 enum 자연 비교(ordinal)가 "나쁜 쪽이 크다"로 동작한다
enum class AirGrade(val raw: Int) {
    GOOD(1), MODERATE(2), BAD(3), VERY_BAD(4);

    val label: String
        get() = when (this) {
            GOOD -> "좋음"
            MODERATE -> "보통"
            BAD -> "나쁨"
            VERY_BAD -> "매우나쁨"
        }

    val tone: RRTone
        get() = when (this) {
            GOOD -> RRTone.IMPROVING
            MODERATE -> RRTone.STEADY
            BAD -> RRTone.CAUTION
            VERY_BAD -> RRTone.OVERLOAD
        }

    companion object {
        fun fromRaw(raw: Int): AirGrade? = entries.find { it.raw == raw }
    }
}

/// 측정소 실시간 대기질 스냅샷 — API 응답을 클라이언트(M4)가 이 형태로 디코딩한다
data class AirQuality(
    val stationName: String,
    val dataTime: String? = null,
    val pm10: Double? = null,    // 미세먼지 ㎍/㎥
    val pm25: Double? = null,    // 초미세먼지 ㎍/㎥
    val o3: Double? = null,      // 오존 ppm
    val khai: Double? = null,    // 통합대기환경지수
    val pm10Grade: AirGrade? = null,
    val pm25Grade: AirGrade? = null,
    val o3Grade: AirGrade? = null,
    val khaiGrade: AirGrade? = null,
)

/// 대기질 엔진 — 가까운 측정소 선택, 등급 폴백 계산, 신선도 판정. 순수 로직.
/// iOS `AirQualityEngine.swift` 이식 (네트워크 클라이언트 AirQualityClient는 M4).
object AirQualityEngine {
    /// 위도 1도 ≈ 111,195m — 30km 안 측정소 고르기엔 평면 근사로 충분하다
    private const val METERS_PER_DEGREE = 111_195.0

    /// 현재 위치에서 가장 가까운 측정소 — maxMeters(기본 30km) 밖이면 null
    /// (엉뚱한 지역의 수치를 보여주느니 안 보여준다)
    fun nearestStation(
        point: GeoPoint,
        stations: List<AirStation>,
        maxMeters: Double = 30_000.0,
    ): AirStation? {
        // 경도 1도의 거리는 위도에 따라 줄어든다 — cos 보정
        val lonScale = METERS_PER_DEGREE * cos(point.lat * PI / 180)
        val best = stations.minByOrNull { distanceMeters(point, it, lonScale) } ?: return null
        return if (distanceMeters(point, best, lonScale) > maxMeters) null else best
    }

    private fun distanceMeters(point: GeoPoint, station: AirStation, lonScale: Double): Double {
        val dx = (station.lon - point.lon) * lonScale
        val dy = (station.lat - point.lat) * METERS_PER_DEGREE
        return hypot(dx, dy)
    }

    // MARK: - 등급 폴백 — API가 등급 필드를 비워 보낼 때 수치로 직접 판정 (환경부 고시 구간)

    fun pm10Grade(value: Double): AirGrade = when {
        value <= 30 -> AirGrade.GOOD
        value <= 80 -> AirGrade.MODERATE
        value <= 150 -> AirGrade.BAD
        else -> AirGrade.VERY_BAD
    }

    fun pm25Grade(value: Double): AirGrade = when {
        value <= 15 -> AirGrade.GOOD
        value <= 35 -> AirGrade.MODERATE
        value <= 75 -> AirGrade.BAD
        else -> AirGrade.VERY_BAD
    }

    fun o3Grade(value: Double): AirGrade = when {
        value <= 0.030 -> AirGrade.GOOD
        value <= 0.090 -> AirGrade.MODERATE
        value <= 0.150 -> AirGrade.BAD
        else -> AirGrade.VERY_BAD
    }

    fun khaiGrade(value: Double): AirGrade = when {
        value <= 50 -> AirGrade.GOOD
        value <= 100 -> AirGrade.MODERATE
        value <= 250 -> AirGrade.BAD
        else -> AirGrade.VERY_BAD
    }

    /// 대표 등급 — 통합지수(khai) 등급 우선, 없으면 미세먼지 두 등급 중 나쁜 쪽
    fun representativeGrade(quality: AirQuality): AirGrade? =
        quality.khaiGrade ?: listOfNotNull(quality.pm25Grade, quality.pm10Grade).maxOrNull()

    /// 먼지 수치가 하나라도 있어야 카드를 보여줄 가치가 있다
    fun hasReading(quality: AirQuality): Boolean = quality.pm10 != null || quality.pm25 != null

    /// 신선도 — 받아온 지 1시간 넘은 측정값은 안 보여준다. 시계가 뒤로 간 경우(age < 0)도 무효
    fun isFresh(fetchedAt: Instant, now: Instant, maxAgeSec: Double = 3_600.0): Boolean {
        val age = secondsBetween(fetchedAt, now)
        return age >= 0 && age < maxAgeSec
    }
}
