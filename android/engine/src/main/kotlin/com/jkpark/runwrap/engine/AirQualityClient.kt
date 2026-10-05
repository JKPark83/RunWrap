package com.jkpark.runwrap.engine

import java.net.SocketTimeoutException
import kotlin.math.min
import kotlinx.serialization.Serializable

/// 에어코리아 측정소별 실시간 측정정보 클라이언트 (data.go.kr 15073861, 이슈 #8).
/// WeatherClient와 같은 패턴 — URLComponents + async/await, 디코드는 fixture 테스트를 위해 분리.
/// 요청에는 측정소명과 인증키만 실린다 — 사용자 좌표·건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
///
/// 게이트웨이 방어 (이슈 #83): 5xx·타임아웃은 짧은 백오프로 최대 2회 재시도하고(전체 10초 안),
/// 한도 초과·키 오류는 quotaExceeded로 따로 알려 스토어가 자정까지 호출을 멈추게 한다.
///
/// (Android: 순수부 — ClientError·decode·retryDelay·attemptTimeout·shouldRetry만 엔진에 둔다.
///  요청(`current(stationName:serviceKey:)`)·세션·인증키는 :app net/AirQualityClient.kt가 맡고,
///  그 재시도 루프가 쓰도록 totalBudget·maxAttemptTimeout·decodeError를 공개한다)
object AirQualityClient {
    sealed class ClientError : Exception() {
        /// resultCode ≠ "00"(한도·키 오류 제외)·항목 없음·HTTP 오류·JSON이 아닌 본문 — 스토어는 unavailable로 접는다
        data object badResponse : ClientError() {
            private fun readResolve(): Any = badResponse
        }

        /// 트래픽 한도 초과(resultCode 22) 또는 인증키 오류(30·31) — 그날은 다시 불러도 같은 답이다
        data object quotaExceeded : ClientError() {
            private fun readResolve(): Any = quotaExceeded
        }
    }

    /// 재시도 백오프 — 같은 게이트웨이를 쓰는 tools/air-quality/fetch_stations.py는 504가 절반가량이라
    /// 재시도가 필수지만, 앱은 홈 진입 흐름이라 짧게 두 번만 (0.5초 → 1초)
    private val retryDelays: List<Double> = listOf(0.5, 1.0)
    /// 재시도를 포함한 전체 상한 — 날씨와 같은 10초
    const val totalBudget: Double = 10.0
    /// 시도 1회의 타임아웃 — 10초를 첫 시도에 다 쓰면 멈춘 응답(타임아웃)은 재시도할 틈이 없다.
    /// 3초씩 끊으면 세 번 모두 멈춰도 3 + 0.5 + 3 + 1 + 2.5 = 10초 안에 끝난다
    const val maxAttemptTimeout: Double = 3.0

    /// attempt(1부터)번째 재시도 전 백오프 — 백오프 뒤 최소 1초는 시도할 수 있을 때만 준다.
    /// nil이면 포기한다 (재시도 횟수 소진 또는 10초 상한 임박)
    fun retryDelay(attempt: Int, remaining: Double): Double? {
        if (attempt - 1 !in retryDelays.indices) return null
        val delay = retryDelays[attempt - 1]
        if (!(remaining > delay + 1)) return null
        return delay
    }

    /// 시도 1회의 타임아웃 — 남은 예산과 maxAttemptTimeout(3초) 중 작은 쪽
    fun attemptTimeout(remaining: Double): Double = min(remaining, maxAttemptTimeout)

    /// decode가 던질 오류만 꺼낸다 — 성공하면 nil
    fun decodeError(data: ByteArray, stationName: String): Throwable? =
        try {
            decode(data, stationName = stationName)
            null
        } catch (e: ClientError) {
            e
        }

    /// 재시도 판단 — 5xx(게이트웨이 SERVICETIMEOUT 504 등)와 URLError.timedOut만 일시 장애로 본다.
    /// 4xx·오프라인·취소 등은 다시 불러도 같으므로 즉시 포기한다
    /// (Android: URLError.timedOut ↔ HttpURLConnection의 connect/read 타임아웃 `SocketTimeoutException`)
    fun shouldRetry(status: Int?, error: Throwable?): Boolean {
        if (error != null) {
            return error is SocketTimeoutException
        }
        if (status == null) return false
        return status in 500 until 600
    }

    /// 응답 디코드 — 수치는 문자열로 오고 결측·통신장애는 "-"다. 숫자로 못 읽으면 nil로 접고,
    /// 등급은 API 공식 등급을 우선 쓰되 빠진 항목만 공식 구간표로 보완한다 (AirQualityEngine 참조)
    ///
    /// 오류 판별 (이슈 #83): resultCode 22·30·31은 quotaExceeded, 그 밖의 오류는 badResponse.
    /// 게이트웨이는 한도·인증 오류를 returnType과 무관하게 XML로 주기도 한다 — `<`로 시작하는 본문은
    /// badResponse로 접되, returnReasonCode가 한도·키 오류면 quotaExceeded로 올린다
    fun decode(data: ByteArray, stationName: String): AirQuality {
        val text = data.decodeToString().trim()
        if (text.startsWith("<")) {
            val blocked = quotaCodes.any { text.contains("<returnReasonCode>$it</returnReasonCode>") }
            throw if (blocked) ClientError.quotaExceeded else ClientError.badResponse
        }
        val response = try {
            EngineJson.decodeFromString<Response>(data.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw ClientError.badResponse
        }
        if (response.response.header.resultCode in quotaCodes) {
            throw ClientError.quotaExceeded
        }
        val item = response.response.body?.items?.firstOrNull()
        if (response.response.header.resultCode != "00" || item == null) {
            throw ClientError.badResponse
        }
        val pm10 = number(item.pm10Value)
        val pm25 = number(item.pm25Value)
        val o3 = number(item.o3Value)
        val khai = number(item.khaiValue)
        return AirQuality(
            stationName = stationName,
            dataTime = item.dataTime,
            pm10 = pm10,
            pm25 = pm25,
            o3 = o3,
            khai = khai,
            pm10Grade = grade(item.pm10Grade1h) ?: pm10?.let(AirQualityEngine::pm10Grade),
            pm25Grade = grade(item.pm25Grade1h) ?: pm25?.let(AirQualityEngine::pm25Grade),
            o3Grade = grade(item.o3Grade) ?: o3?.let(AirQualityEngine::o3Grade),
            khaiGrade = grade(item.khaiGrade) ?: khai?.let(AirQualityEngine::khaiGrade))
    }

    /// 22 LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR · 30 SERVICE_KEY_IS_NOT_REGISTERED_ERROR ·
    /// 31 DEADLINE_HAS_EXPIRED_ERROR — 셋 다 인증키 단위라 당일 재호출이 무의미하다
    private val quotaCodes: Set<String> = setOf("22", "30", "31")

    private fun number(raw: String?): Double? = raw?.let(::swiftDouble)

    private fun grade(raw: String?): AirGrade? = raw?.toIntOrNull()?.let(AirGrade::fromRawValue)

    private val decimalPattern = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val hexPattern = Regex("[+-]?0[xX]([0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)([pP][+-]?\\d+)?")

    /// Swift `Double(String)` 대응 (GPXParser의 같은 이름 헬퍼 사본) — Kotlin `toDoubleOrNull`과 달리
    /// 앞뒤 공백·접미사(d/f)를 받지 않고, 지수 없는 16진("0x1A")은 받는다. nan·inf는 실제 응답에 없어 null로 돌려준다
    private fun swiftDouble(text: String): Double? = when {
        decimalPattern.matches(text) -> text.toDouble()
        hexPattern.matches(text) -> (if (text.contains('p', ignoreCase = true)) text else text + "p0").toDouble()
        else -> null
    }

    /// 에어코리아 응답 스키마 그대로 — 필드명은 API의 camelCase를 따른다.
    /// 모든 수치가 문자열 타입인 것도 API 원문이다
    @Serializable
    private data class Response(
        val response: Inner,
    ) {
        @Serializable
        data class Header(
            val resultCode: String,
        )

        @Serializable
        data class Item(
            val dataTime: String? = null,
            val pm10Value: String? = null,
            val pm25Value: String? = null,
            val o3Value: String? = null,
            val khaiValue: String? = null,
            val pm10Grade1h: String? = null,
            val pm25Grade1h: String? = null,
            val o3Grade: String? = null,
            val khaiGrade: String? = null,
        )

        @Serializable
        data class Body(
            val items: List<Item>,
        )

        @Serializable
        data class Inner(
            val header: Header,
            val body: Body? = null,
        )
    }
}
