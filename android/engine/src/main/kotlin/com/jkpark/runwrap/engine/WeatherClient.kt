package com.jkpark.runwrap.engine

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/// iOS WeatherClient.swift 중 순수부 — 모델(`CurrentWeather`·`HourlyWeather`)과 응답 디코드(`decode`·`decodeHourly`)만 엔진에 둔다.
/// URL 조립·요청(`current(latitude:longitude:)`, 10초 상한 세션)은 :app net/WeatherClient.kt가 맡는다.
/// (:app은 좌표를 iOS `String(format: "%.2f")`와 같게 `fmt(v, 2)`로 낮춰 보낸다 — 프라이버시 반올림)

/// Open-Meteo 현재 날씨 — 앱에서 유일한 네트워크 코드 (기획서 §4.10, 계획서 M6).
/// 요청에는 좌표(위도·경도)만 실린다 — 건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
/// 좌표는 소수 2자리(≈1km)로 낮춰 보낸다 — 날씨에는 충분하고 정확한 위치 노출을 줄인다.
data class CurrentWeather(
    val temperatureC: Double,
    val apparentC: Double,
    val humidityPct: Double,
    val windMs: Double,
    val precipitationMm: Double,
    /// 오늘 예보 최고기온 — 수분 알람(계획서 M9) 재료. 응답에 daily가 없으면 nil
    val forecastMaxC: Double?,
    /// WMO 날씨 코드 — 상태 아이콘·라벨 매핑은 화면 몫 (응답에 없으면 nil)
    val weatherCode: Int?,
    /// 현재 자외선지수 (응답에 없으면 nil)
    val uvIndex: Double?,
    /// 지금 시각이 든 칸부터 24시간 예보 — 달리기 좋은 시간 추천 재료 (이슈 #173).
    /// 응답에 없거나 모양이 어긋나면 빈 배열 — 화면은 24칸 미만이면 카드를 내지 않는다
    val hourly: List<HourlyWeather> = emptyList(),
)

/// 한 시간 칸 예보 (open-meteo hourly) — time은 그 칸의 시작 시각
data class HourlyWeather(
    val time: Instant,
    val temperatureC: Double,
    val apparentC: Double,
    val humidityPct: Double,
    val precipitationProbabilityPct: Int,
    val precipitationMm: Double,
    val windMs: Double,
    val weatherCode: Int?,
)

object WeatherClient {
    /// 응답 디코드 — fixture 테스트를 위해 네트워크와 분리 (계획서 M6).
    /// `now`는 시간대별 예보를 지금 칸부터 24개로 자르는 기준 (테스트에서 고정 시각 주입)
    /// (Android: iOS 기본값 `Date()`는 두지 않는다 — 호출부가 넘긴다. 디코드 실패는 SerializationException)
    fun decode(data: ByteArray, now: Instant): CurrentWeather {
        val response = EngineJson.decodeFromString<Response>(data.decodeToString())
        return CurrentWeather(temperatureC = response.current.temperature_2m,
                              apparentC = response.current.apparent_temperature,
                              humidityPct = response.current.relative_humidity_2m,
                              windMs = response.current.wind_speed_10m,
                              precipitationMm = response.current.precipitation,
                              forecastMaxC = response.daily?.temperature_2m_max?.firstOrNull(),
                              weatherCode = response.current.weather_code,
                              uvIndex = response.current.uv_index,
                              hourly = decodeHourly(data, now = now))
    }

    /// 시간대별 예보는 부가 정보라 관용 처리한다 — 따로 디코드해서 실패해도 현재 날씨는 살린다.
    /// hourly가 없거나 배열 길이가 서로 다르면 빈 배열, 값이 빠진(null) 칸은 건너뛴다.
    /// time은 timezone=auto라 오프셋 없는 현지 시각 문자열("2026-08-10T15:00")이어서
    /// utc_offset_seconds로 시간대를 붙여 Date로 만든다
    private fun decodeHourly(data: ByteArray, now: Instant): List<HourlyWeather> {
        // try? 디코드와 TimeZone(secondsFromGMT:) 실패(±18시간 밖) 모두 빈 배열
        val response = try {
            EngineJson.decodeFromString<HourlyResponse>(data.decodeToString())
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        val zone = try {
            ZoneOffset.ofTotalSeconds(response.utc_offset_seconds)
        } catch (e: DateTimeException) {
            return emptyList()
        }
        val h = response.hourly
        val count = h.time.size
        if (!listOf(h.temperature_2m.size, h.apparent_temperature.size, h.relative_humidity_2m.size,
                    h.precipitation_probability.size, h.precipitation.size, h.wind_speed_10m.size,
                    h.weather_code.size).all { it == count }) return emptyList()

        val slots: List<HourlyWeather> = (0 until count).mapNotNull { i ->
            val time = parseLocalTime(h.time[i], zone) ?: return@mapNotNull null
            val temperature = h.temperature_2m[i] ?: return@mapNotNull null
            val apparent = h.apparent_temperature[i] ?: return@mapNotNull null
            val humidity = h.relative_humidity_2m[i] ?: return@mapNotNull null
            val probability = h.precipitation_probability[i] ?: return@mapNotNull null
            val precipitation = h.precipitation[i] ?: return@mapNotNull null
            val wind = h.wind_speed_10m[i] ?: return@mapNotNull null
            HourlyWeather(time = time, temperatureC = temperature, apparentC = apparent,
                          humidityPct = humidity,
                          precipitationProbabilityPct = probability.swiftRoundedInt(),
                          precipitationMm = precipitation, windMs = wind,
                          weatherCode = h.weather_code[i])
        }
        // 지금 시각이 든 칸(끝이 now 이후인 첫 칸)부터 24시간
        return slots.dropWhile { it.time.plusSeconds(3_600) <= now }.take(24)
    }

    /// iOS DateFormatter(en_US_POSIX, "yyyy-MM-dd'T'HH:mm", 응답 오프셋) 대응 — 못 읽으면 nil(그 칸을 건너뛴다)
    private fun parseLocalTime(text: String, zone: ZoneOffset): Instant? = try {
        LocalDateTime.parse(text, timeFormat).toInstant(zone)
    } catch (e: DateTimeParseException) {
        null
    }

    private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")

    /// Open-Meteo 응답 스키마 그대로 — 필드명은 API의 snake_case를 따른다
    @Serializable
    private data class Response(
        val current: Current,
        val daily: Daily? = null,
    ) {
        @Serializable
        data class Current(
            val temperature_2m: Double,
            val apparent_temperature: Double,
            val relative_humidity_2m: Double,
            val wind_speed_10m: Double,
            val precipitation: Double,
            val weather_code: Int? = null,     // 구 응답·필드 미지원 대비 옵셔널
            val uv_index: Double? = null,
        )

        @Serializable
        data class Daily(
            val temperature_2m_max: List<Double>,   // 첫 값이 오늘 (forecast_days=2 → 오늘·내일)
        )
    }

    /// 시간대별 예보 스키마 — 값이 빠진 칸(null)이 올 수 있어 원소를 옵셔널로 받는다
    @Serializable
    private data class HourlyResponse(
        val utc_offset_seconds: Int,
        val hourly: Hourly,
    ) {
        @Serializable
        data class Hourly(
            val time: List<String>,
            val temperature_2m: List<Double?>,
            val apparent_temperature: List<Double?>,
            val relative_humidity_2m: List<Double?>,
            val precipitation_probability: List<Double?>,
            val precipitation: List<Double?>,
            val wind_speed_10m: List<Double?>,
            val weather_code: List<Int?>,
        )
    }
}
