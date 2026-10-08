package com.jkpark.runwrap.net

import com.jkpark.runwrap.engine.CurrentWeather
import com.jkpark.runwrap.engine.WeatherClient
import com.jkpark.runwrap.engine.fmt
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/// Open-Meteo 현재 날씨 — 앱에서 유일한 네트워크 코드 (기획서 §4.10, 계획서 M6).
/// 요청에는 좌표(위도·경도)만 실린다 — 건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
/// 좌표는 소수 2자리(≈1km)로 낮춰 보낸다 — 날씨에는 충분하고 정확한 위치 노출을 줄인다.
/// (Android: 모델·decode는 엔진 `object WeatherClient`, 요청만 여기 확장 함수로 둔다. iOS `current(latitude:longitude:)` ↔ `fetch`)
///
/// 기동 스플래시가 이 요청의 결론을 기다린다 — 기본 60초 타임아웃은 기동을 볼모로
/// 잡으므로 10초로 묶는다. 실패도 "결론"이라 스플래시가 홈을 열 수 있다 (RootView 참조).
/// timeoutIntervalForRequest는 바이트가 올 때마다 다시 시작되는 무응답 간격이라 전체 상한이 아니다 —
/// 느리게 조금씩 오는 응답이 10초를 넘기지 않도록 Resource 상한도 같이 건다 (이슈 #157)
suspend fun WeatherClient.fetch(latitude: Double, longitude: Double): CurrentWeather {
    val query = listOf(
        "latitude" to fmt(latitude, 2),
        "longitude" to fmt(longitude, 2),
        "current" to "temperature_2m,apparent_temperature," +
            "relative_humidity_2m,wind_speed_10m,precipitation,weather_code,uv_index",
        "wind_speed_unit" to "ms",  // 복장 룰이 m/s 기준 (계획서 M6)
        "daily" to "temperature_2m_max",  // 수분 알람: 오늘 최고기온 (계획서 M9)
        // 달리기 좋은 시간 추천 (이슈 #173) — 같은 요청에 시간대별 예보만 더 받는다.
        // 보내는 값은 그대로 좌표뿐. 저녁에도 24시간이 이어지도록 이틀치를 받는다
        "hourly" to "temperature_2m,apparent_temperature,relative_humidity_2m," +
            "precipitation_probability,precipitation,wind_speed_10m,weather_code",
        "forecast_days" to "2",
        "timezone" to "auto",
    ).joinToString("&") { (name, value) -> "$name=$value" }
    val response = httpGet(URL("https://api.open-meteo.com/v1/forecast?$query"), timeoutMillis = 10_000)
    return decode(response.body, now = Instant.now())
}

/// HTTP 응답 — 상태 코드와 본문(2xx가 아니면 오류 본문)
internal class HttpResponse(val status: Int, val body: ByteArray)

/// GET 1회 (날씨·대기질·대회정보 공용). `timeoutMillis`는 iOS timeoutIntervalForRequest와 ForResource 둘 다에 대응한다 —
/// HttpURLConnection의 connect/read 타임아웃은 무응답 간격이라, 코루틴 `withTimeout`으로 전체 상한을 걸고 넘기면 연결을 끊는다.
/// 전체 상한 초과도 `SocketTimeoutException`으로 낸다 — AirQualityClient.shouldRetry가 URLError.timedOut처럼 보게.
/// 호출부 취소(화면 이탈)는 CancellationException 그대로 전파된다
internal suspend fun httpGet(url: URL, timeoutMillis: Long, useCaches: Boolean = true): HttpResponse =
    withContext(Dispatchers.IO) {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis.toInt()
        connection.readTimeout = timeoutMillis.toInt()
        connection.useCaches = useCaches
        try {
            withTimeout(timeoutMillis) {
                val read = async {
                    val status = connection.responseCode
                    val stream = if (status in 200 until 300) connection.inputStream else connection.errorStream
                    HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
                }
                // 블로킹 읽기는 취소에 반응하지 않는다 — 시간 초과·취소 때 연결을 끊어 읽기를 풀어 준다
                try { read.await() } finally { connection.disconnect() }
            }
        } catch (_: TimeoutCancellationException) {
            throw SocketTimeoutException("timed out after $timeoutMillis ms")
        }
    }
