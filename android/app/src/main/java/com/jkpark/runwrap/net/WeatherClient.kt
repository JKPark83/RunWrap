package com.jkpark.runwrap.net

import com.jkpark.runwrap.engine.CurrentWeather
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// Open-Meteo 현재 날씨 — iOS `WeatherClient.swift` 이식 (동일 엔드포인트·응답 모델).
/// 요청에는 좌표(위도·경도)만 실린다 — 건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
/// 좌표는 소수 2자리(≈1km)로 낮춰 보낸다 — 날씨에는 충분하고 정확한 위치 노출을 줄인다.
object WeatherClient {
    /// 기동 흐름이 이 요청의 결론을 기다린다 — 기본 무제한 타임아웃은 기동을 볼모로
    /// 잡으므로 iOS와 같은 10초로 묶는다. 실패도 "결론"이라 홈을 열 수 있다
    private const val TIMEOUT_MS = 10_000

    suspend fun current(latitude: Double, longitude: Double): CurrentWeather {
        val query = listOf(
            "latitude" to String.format(Locale.US, "%.2f", latitude),
            "longitude" to String.format(Locale.US, "%.2f", longitude),
            "current" to "temperature_2m,apparent_temperature," +
                "relative_humidity_2m,wind_speed_10m,precipitation,weather_code,uv_index",
            "wind_speed_unit" to "ms",              // 복장 룰이 m/s 기준 (계획서 M6)
            "daily" to "temperature_2m_max",        // 수분 알람: 오늘 최고기온 (계획서 M9)
            "forecast_days" to "1",
            "timezone" to "auto",
        ).joinToString("&") { (name, value) -> "$name=$value" }
        val body = httpGet("https://api.open-meteo.com/v1/forecast?$query")
        return decode(body)
    }

    /// 응답 디코드 — fixture 테스트를 위해 네트워크와 분리 (iOS와 동일 패턴)
    fun decode(body: String): CurrentWeather {
        val response = json.decodeFromString(Response.serializer(), body)
        return CurrentWeather(
            temperatureC = response.current.temperature_2m,
            apparentC = response.current.apparent_temperature,
            humidityPct = response.current.relative_humidity_2m,
            windMs = response.current.wind_speed_10m,
            precipitationMm = response.current.precipitation,
            forecastMaxC = response.daily?.temperature_2m_max?.firstOrNull(),
            weatherCode = response.current.weather_code,
            uvIndex = response.current.uv_index,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /// Open-Meteo 응답 스키마 그대로 — 필드명은 API의 snake_case를 따른다
    @Serializable
    private data class Response(val current: Current, val daily: Daily? = null) {
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
        data class Daily(val temperature_2m_max: List<Double>)   // forecast_days=1 → 오늘 하루치
    }
}

/// GET 한 번 — 의존성 없이 HttpURLConnection으로 충분하다 (외부 통신은 날씨·대기질 둘뿐)
internal suspend fun httpGet(url: String, timeoutMs: Int = 10_000): String =
    withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
