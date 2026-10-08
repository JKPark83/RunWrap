package com.jkpark.runwrap.net

import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.AirQualityClient
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.delay

/// 에어코리아 측정소별 실시간 측정정보 요청 (data.go.kr 15073861, 이슈 #8) — iOS `AirQualityClient.current` ↔ `fetch`.
/// 요청에는 측정소명과 인증키만 실린다 — 사용자 좌표·건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
/// 디코드·재시도 판단은 엔진 `object AirQualityClient`에 있다.
///
/// 홈 진입 후 백그라운드로 도는 조회지만 날씨와 같은 10초 상한을 둔다 —
/// 오래 기다린 끝의 낡은 수치보다 "안 보여주기"가 낫다 (미노출 가드).
/// 전체 상한은 이 재시도 루프가 지키고, 요청 1회는 시도 1회 타임아웃만 건다 (이슈 #157)
suspend fun AirQualityClient.fetch(stationName: String, serviceKey: String): AirQuality {
    val url = airQualityUrl(stationName, serviceKey)
    val deadline = System.nanoTime() + (totalBudget * 1e9).toLong()
    fun remaining() = (deadline - System.nanoTime()) / 1e9
    var lastError: Throwable = AirQualityClient.ClientError.badResponse
    var attempt = 0
    while (true) {
        if (attempt > 0) {
            // 재시도 횟수 소진 또는 10초 상한 임박이면 retryDelay가 nil
            val wait = retryDelay(attempt, remaining()) ?: break
            delay((wait * 1000).toLong())
        }
        attempt++
        val timeout = (attemptTimeout(remaining()) * 1000).toLong().coerceAtLeast(1)
        val response = try {
            httpGet(url, timeout)
        } catch (e: IOException) {
            // UnknownHostException(오프라인) 등은 다시 불러도 같다 — 타임아웃만 재시도
            if (!shouldRetry(status = null, error = e)) throw e
            lastError = e
            continue
        }
        if (shouldRetry(status = response.status, error = null)) {
            // 5xx라도 본문이 한도·키 오류면 다시 불러도 같다 — 재시도 없이 바로 알린다
            if (decodeError(response.body, stationName) == AirQualityClient.ClientError.quotaExceeded) {
                throw AirQualityClient.ClientError.quotaExceeded
            }
            lastError = AirQualityClient.ClientError.badResponse
            continue
        }
        // 오류 본문(한도 초과·키 오류)은 decode가 quotaExceeded/badResponse로 가른다
        val quality = decode(response.body, stationName)
        if (response.status !in 200 until 300) throw AirQualityClient.ClientError.badResponse
        return quality
    }
    throw lastError
}

/// 요청 URL — data.go.kr 인증키는 "디코딩 키"(+·/·= 포함 원본)를 쓴다. 값마다 한 번 퍼센트 인코딩해
/// +는 %2B로 나간다 (iOS는 URLComponents가 남긴 +를 %2B로 직접 치환한다 — 서버가 받는 값은 같다)
internal fun airQualityUrl(stationName: String, serviceKey: String): URL {
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    val query = listOf(
        "serviceKey" to serviceKey,
        "returnType" to "json",
        "stationName" to stationName,
        "dataTerm" to "DAILY",
        "numOfRows" to "1",   // 최신 1건이면 충분
        "pageNo" to "1",
        "ver" to "1.3",       // 1.3부터 PM 1시간 등급(pm10/pm25Grade1h) 포함
    ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    return URL("https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty?$query")
}
