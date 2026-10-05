package com.jkpark.runwrap.engine

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/// 에어코리아 대기질 순수 로직 — 최근접 측정소 탐색·공식 등급 매핑·캐시 신선도·호출 차단 (이슈 #8, #83).
///
/// KOGL 제3유형(출처표시·변경금지) 준수: 등급을 자체 산식으로 재가공하지 않는다.
/// API가 내려주는 공식 등급(1~4)을 그대로 쓰고, 응답에 등급이 빠진 항목만
/// 에어코리아 공식 등급 구간표(airkorea.or.kr 통합대기환경지수 안내)로 보완한다.
/// 화면 문구도 공식 4등급(좋음/보통/나쁨/매우나쁨)을 그대로 쓴다.

/// 전국 측정소 한 곳 — 번들 AirStations.json 항목 (tools/air-quality 산출 포맷과 1:1).
/// 최근접 탐색을 기기에서 하기 위한 정적 데이터다 — 사용자 좌표는 밖으로 나가지 않는다.
@Serializable
data class AirStation(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/// AirStations.json 전체 — generatedAt은 갱신 배치(air-stations.yml)가 찍는다
@Serializable
data class AirStationFile(
    val generatedAt: String,
    val stations: List<AirStation>,
)

/// 에어코리아 공식 4등급 — API grade 값(1~4)과 같은 rawValue.
/// Comparable은 "PM 중 나쁜 쪽" 선택(representativeGrade)이 요구한다 — 클수록 나쁘다
/// (Android: Kotlin enum은 선언 순서로 비교되며 rawValue 순서와 같다. Codable은 Int rawValue로 인코딩 — AirGradeSerializer)
@Serializable(with = AirGradeSerializer::class)
enum class AirGrade(val rawValue: Int) {
    good(1), moderate(2), bad(3), veryBad(4);

    /// 에어코리아 공식 등급 문구 그대로 (KOGL 변경금지 — 다른 표현으로 바꾸지 않는다)
    val label: String
        get() = when (this) {
            good -> "좋음"
            moderate -> "보통"
            bad -> "나쁨"
            veryBad -> "매우나쁨"
        }

    /// 등급 → 카드 톤. 색은 화면(Theme)이 정한다 — 엔진은 RRTone까지만
    val tone: RRTone
        get() = when (this) {
            good -> RRTone.improving
            moderate -> RRTone.steady
            bad -> RRTone.caution
            veryBad -> RRTone.overload
        }

    companion object {
        fun fromRawValue(rawValue: Int): AirGrade? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// Swift `enum AirGrade: Int, Codable` 인코딩 — rawValue 정수 그대로. 모르는 값은 디코드 실패
object AirGradeSerializer : KSerializer<AirGrade> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AirGrade", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: AirGrade) {
        encoder.encodeInt(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): AirGrade {
        val raw = decoder.decodeInt()
        return AirGrade.fromRawValue(raw)
            ?: throw SerializationException("AirGrade rawValue $raw 없음")
    }
}

/// 측정소 실시간 수치 스냅샷 — 클라이언트 디코드 결과이자 캐시 저장 단위.
/// 값이 nil인 항목은 측정소 통신장애·점검 등으로 원 데이터가 없는 것 — 화면은 그 항목을 그리지 않는다
@Serializable
data class AirQuality(
    val stationName: String,
    /// 측정 기준 시각 — API 원문 그대로 ("2026-08-20 14:00")
    val dataTime: String? = null,
    /// 미세먼지 PM10 (㎍/㎥)
    val pm10: Double? = null,
    /// 초미세먼지 PM2.5 (㎍/㎥)
    val pm25: Double? = null,
    /// 오존 (ppm)
    val o3: Double? = null,
    /// 통합대기환경지수 CAI
    val khai: Double? = null,
    val pm10Grade: AirGrade? = null,
    val pm25Grade: AirGrade? = null,
    val o3Grade: AirGrade? = null,
    val khaiGrade: AirGrade? = null,
)

object AirQualityEngine {
    /// 지구 반지름 6,371km 기준 위도 1도의 미터 — Course/NearbySupplyEngine과 같은 상수
    private const val metersPerDegree = 111_195.0

    /// 최근접 측정소 — 등장방형 평면 투영 직선거리(수십 km 스케일이면 충분).
    ///
    /// 미노출 가드: 최근접이 maxMeters(기본 30km) 밖이면 nil — 해외 등 커버리지 밖에서
    /// 엉뚱한 측정소 수치를 "현재 위치 공기"로 내보이지 않는다.
    fun nearestStation(to: GeoPoint, stations: List<AirStation>,
                       maxMeters: Double = 30_000.0): AirStation? {
        val point = to
        val lonScale = metersPerDegree * cos(point.lat * PI / 180)
        var best: Pair<AirStation, Double>? = null
        for (station in stations) {
            val dx = (station.lon - point.lon) * lonScale
            val dy = (station.lat - point.lat) * metersPerDegree
            val meters = hypot(dx, dy)
            if (best == null || meters < best.second) best = station to meters
        }
        if (best == null || !(best.second <= maxMeters)) return null
        return best.first
    }

    // MARK: 공식 등급 구간표 폴백 — 응답에 등급이 없을 때만 쓴다 (에어코리아 등급 기준)

    /// PM10 (㎍/㎥): 0–30 좋음 · 31–80 보통 · 81–150 나쁨 · 151~ 매우나쁨
    fun pm10Grade(value: Double): AirGrade = when {
        value <= 30 -> AirGrade.good
        value <= 80 -> AirGrade.moderate
        value <= 150 -> AirGrade.bad
        else -> AirGrade.veryBad
    }

    /// PM2.5 (㎍/㎥): 0–15 좋음 · 16–35 보통 · 36–75 나쁨 · 76~ 매우나쁨
    fun pm25Grade(value: Double): AirGrade = when {
        value <= 15 -> AirGrade.good
        value <= 35 -> AirGrade.moderate
        value <= 75 -> AirGrade.bad
        else -> AirGrade.veryBad
    }

    /// 오존 (ppm): 0–0.030 좋음 · –0.090 보통 · –0.150 나쁨 · 0.151~ 매우나쁨
    fun o3Grade(value: Double): AirGrade = when {
        value <= 0.030 -> AirGrade.good
        value <= 0.090 -> AirGrade.moderate
        value <= 0.150 -> AirGrade.bad
        else -> AirGrade.veryBad
    }

    /// 통합대기환경지수 CAI: 0–50 좋음 · –100 보통 · –250 나쁨 · 251~ 매우나쁨
    fun khaiGrade(value: Double): AirGrade = when {
        value <= 50 -> AirGrade.good
        value <= 100 -> AirGrade.moderate
        value <= 250 -> AirGrade.bad
        else -> AirGrade.veryBad
    }

    // MARK: 대표 등급·미노출 가드·캐시

    /// 카드 배지의 대표 등급 — 통합지수(6개 오염물질을 묶는 공식 지수)가 있으면 그것,
    /// 없으면 PM 두 등급 중 나쁜 쪽. 어느 쪽이든 공식 등급을 고를 뿐 새 등급을 만들지 않는다
    fun representativeGrade(quality: AirQuality): AirGrade? {
        quality.khaiGrade?.let { return it }
        return listOfNotNull(quality.pm25Grade, quality.pm10Grade).maxOrNull()
    }

    /// PM 수치가 하나도 없으면 지표를 내지 않는다 — 통신장애 측정소의 빈 응답 가드
    fun hasReading(quality: AirQuality): Boolean =
        quality.pm10 != null || quality.pm25 != null

    /// 캐시 신선도 (이슈 #83) — 받은 시각이 아니라 측정 시각(dataTime) 기준이다.
    /// 정시 측정값은 약 15분 뒤 공개되므로 만료 = 측정 정시 + 1시간 + 20분 — 14:55에 받은
    /// 14:00 값은 15:20에 만료돼, 15:15쯤 공개되는 15:00 값을 받으러 간다.
    /// dataTime을 못 읽으면 기존 규칙(받은 지 1시간)으로 폴백한다 (이슈 #8).
    /// 다만 받은 지 minAge(10분) 안이면 dataTime과 무관하게 신선하다 — 측정소 공개가 늦어
    /// 서버가 여전히 지난 정시 값을 주면 받자마자 만료돼, 진입·새로고침마다 한도를 쓰게 된다
    fun isFresh(dataTime: String?, fetchedAt: Instant, now: Instant,
                maxAge: Double = 3_600.0, minAge: Double = 10.0 * 60): Boolean {
        // 기기 시계 역행(미래 fetchedAt)은 신선으로 치지 않는다 — 캐시를 다시 받는 쪽이 안전
        val age = now.timeIntervalSince1970 - fetchedAt.timeIntervalSince1970
        if (!(age >= 0)) return false
        if (age < minAge) return true
        val measured = measuredAt(dataTime)
        if (measured != null) {
            return now < measured.plusSeconds(publishedExpiry)
        }
        return age < maxAge
    }

    /// 측정값 만료 오프셋 — 다음 정시(+1시간) 값의 공개 지연 약 15분에 여유 5분 (이슈 #83)
    private const val publishedExpiry: Long = 3_600L + 20 * 60

    /// 측정 시각이 now보다 maxLag(기본 3시간) 이상 뒤처지면 지표를 내지 않는다 (이슈 #83) —
    /// 측정소 지연·장애로 낡은 값이 "지금 공기"로 보이지 않게 하는 hasReading과 같은 미노출 가드.
    /// dataTime을 못 읽으면 판단 근거가 없으므로 막지 않는다 (신선도도 fetchedAt 폴백과 같은 태도)
    fun isRecent(dataTime: String?, now: Instant, maxLag: Double = 3.0 * 3_600): Boolean {
        val measured = measuredAt(dataTime) ?: return true
        return now.timeIntervalSince1970 - measured.timeIntervalSince1970 < maxLag
    }

    /// API dataTime("yyyy-MM-dd HH:mm", KST) → Date. 형식이 어긋나면 nil.
    /// 에어코리아는 자정 측정값을 전날 "24:00"으로 표기한다 — DateFormatter는 이를 거부하므로
    /// 성분을 직접 읽어 Calendar에 넘긴다(시 24는 다음 날 00:00으로 넘어간다)
    /// (Android: iOS Calendar의 관대한 넘김(2월 30일 → 3월 2일)과 같게 1일 기준 날짜·시·분을 더해 만든다)
    fun measuredAt(dataTime: String?): Instant? {
        if (dataTime == null) return null
        val parts = dataTime.split('-', ' ', ':')
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }
        if (!(parts.size == 5 && parts[1] in 1..12 && parts[2] in 1..31 &&
              parts[3] in 0..24 && parts[4] in 0..59)) {
            return null
        }
        return try {
            LocalDate.of(Math.toIntExact(parts[0]), parts[1].toInt(), 1)
                .plusDays(parts[2] - 1)
                .atStartOfDay()
                .plusHours(parts[3])
                .plusMinutes(parts[4])
                .atZone(KST)
                .toInstant()
        } catch (e: DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    // MARK: negative cache — 실패 뒤 호출 차단 (이슈 #83)

    /// 차단 해제 시각 — data.go.kr 트래픽 한도는 인증키당 일 단위라 한도 초과(키 오류 포함)는
    /// 다음 KST 자정까지, 그 밖의 실패(5xx 재시도 소진·네트워크)는 10분만 막는다
    fun blockedUntil(quotaExceeded: Boolean, now: Instant): Instant {
        if (!quotaExceeded) return now.plusSeconds(10L * 60)
        val today = now.atZone(KST).toLocalDate()
        return today.plusDays(1).atStartOfDay(KST).toInstant()
    }

    /// 차단 중인지 — 기록이 없거나 해제 시각이 지났으면 네트워크를 탄다
    fun isBlocked(blockedUntil: Instant?, now: Instant): Boolean {
        if (blockedUntil == null) return false
        return now < blockedUntil
    }

    // 한국 달력 — dataTime과 data.go.kr 일 한도 모두 KST 기준이라 사용자 시간대와 무관하게 고정
    // (Android: EngineSupport의 KST를 쓴다)
}
