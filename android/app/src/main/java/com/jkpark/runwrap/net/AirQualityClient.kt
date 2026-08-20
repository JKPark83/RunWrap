package com.jkpark.runwrap.net

import com.jkpark.runwrap.engine.AirGrade
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.AirQualityEngine
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// 에어코리아 측정소별 실시간 측정정보 클라이언트 — iOS `AirQualityClient.swift` 이식
/// (data.go.kr 15073861, 이슈 #8. 동일 엔드포인트·응답 모델).
/// 요청에는 측정소명과 인증키만 실린다 — 사용자 좌표·건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
object AirQualityClient {
    /// resultCode ≠ "00" 또는 응답에 항목이 없음 — 스토어는 unavailable로 접는다
    class BadResponseException : Exception("에어코리아 응답 오류")

    suspend fun current(stationName: String, serviceKey: String): AirQuality {
        // data.go.kr 인증키는 "디코딩 키"(+·/·= 포함 원본)를 쓴다 — URLEncoder가
        // +를 %2B로 인코딩하므로 iOS의 수동 치환에 해당하는 처리가 자동으로 된다
        val query = listOf(
            "serviceKey" to serviceKey,
            "returnType" to "json",
            "stationName" to stationName,
            "dataTerm" to "DAILY",
            "numOfRows" to "1",     // 최신 1건이면 충분
            "pageNo" to "1",
            "ver" to "1.3",         // 1.3부터 PM 1시간 등급(pm10/pm25Grade1h) 포함
        ).joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        val body = httpGet(
            "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty?$query")
        return decode(body, stationName)
    }

    /// 응답 디코드 — 수치는 문자열로 오고 결측·통신장애는 "-"다. 숫자로 못 읽으면 null로 접고,
    /// 등급은 API 공식 등급을 우선 쓰되 빠진 항목만 공식 구간표로 보완한다 (AirQualityEngine 참조)
    fun decode(body: String, stationName: String): AirQuality {
        val response = json.decodeFromString(Response.serializer(), body)
        if (response.response.header.resultCode != "00") throw BadResponseException()
        val item = response.response.body?.items?.firstOrNull() ?: throw BadResponseException()
        val pm10 = item.pm10Value?.toDoubleOrNull()
        val pm25 = item.pm25Value?.toDoubleOrNull()
        val o3 = item.o3Value?.toDoubleOrNull()
        val khai = item.khaiValue?.toDoubleOrNull()
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
            khaiGrade = grade(item.khaiGrade) ?: khai?.let(AirQualityEngine::khaiGrade),
        )
    }

    private fun grade(raw: String?): AirGrade? = raw?.toIntOrNull()?.let(AirGrade::fromRaw)

    private val json = Json { ignoreUnknownKeys = true }

    /// 에어코리아 응답 스키마 그대로 — 필드명은 API의 camelCase를 따른다.
    /// 모든 수치가 문자열 타입인 것도 API 원문이다
    @Serializable
    private data class Response(val response: Inner) {
        @Serializable
        data class Inner(val header: Header, val body: Body? = null)

        @Serializable
        data class Header(val resultCode: String)

        @Serializable
        data class Body(val items: List<Item>)

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
    }
}
