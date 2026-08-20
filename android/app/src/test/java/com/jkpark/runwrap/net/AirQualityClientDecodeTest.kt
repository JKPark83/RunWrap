package com.jkpark.runwrap.net

import com.jkpark.runwrap.engine.AirGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/// 응답 디코드 — 측정소별 실시간 측정정보(ver 1.3) 응답 축약 fixture 검증.
/// iOS `AirQualityClientDecodeTests` 이식 (4개 전부).
class AirQualityClientDecodeTest {
    @Test
    fun `정상 응답 - 문자열 수치와 공식 등급을 그대로 파싱한다`() {
        val json = """
            {"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",
            "pm10Value":"34","pm25Value":"19","o3Value":"0.031","khaiValue":"68",
            "pm10Grade1h":"2","pm25Grade1h":"2","o3Grade":"2","khaiGrade":"2"}]},
            "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """.trimIndent()
        val quality = AirQualityClient.decode(json, stationName = "중구")
        assertEquals("중구", quality.stationName)
        assertEquals("2026-08-20 14:00", quality.dataTime)
        assertEquals(34.0, quality.pm10!!, 0.0)
        assertEquals(19.0, quality.pm25!!, 0.0)
        assertEquals(0.031, quality.o3!!, 0.0)
        assertEquals(68.0, quality.khai!!, 0.0)
        assertEquals(AirGrade.MODERATE, quality.pm25Grade)
        assertEquals(AirGrade.MODERATE, quality.khaiGrade)
    }

    @Test
    fun `결측 - 수치는 null로 접고, 등급이 빠진 항목만 수치로 보완한다`() {
        // pm25는 수치만 있고 등급이 "-" → 공식 구간표 폴백(80 → 매우나쁨).
        // o3·khai는 통신장애로 수치·등급 모두 "-" → 전부 null
        val json = """
            {"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",
            "pm10Value":"-","pm25Value":"80","o3Value":"-","khaiValue":"-",
            "pm10Grade1h":"-","pm25Grade1h":"-","o3Grade":"-","khaiGrade":"-"}]},
            "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """.trimIndent()
        val quality = AirQualityClient.decode(json, stationName = "중구")
        assertNull(quality.pm10)
        assertNull(quality.pm10Grade)
        assertEquals(80.0, quality.pm25!!, 0.0)
        assertEquals(AirGrade.VERY_BAD, quality.pm25Grade)
        assertNull(quality.o3)
        assertNull(quality.khai)
        assertNull(quality.khaiGrade)
    }

    @Test
    fun `오류 응답 - resultCode가 00이 아니면 throw, 스토어가 unavailable로 접는다`() {
        val json = """
            {"response":{"body":{"totalCount":0,"items":[]},
            "header":{"resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","resultCode":"30"}}}
        """.trimIndent()
        assertThrows(AirQualityClient.BadResponseException::class.java) {
            AirQualityClient.decode(json, stationName = "중구")
        }
    }

    @Test
    fun `빈 응답 - 항목이 없으면 throw`() {
        val json = """
            {"response":{"body":{"totalCount":0,"items":[]},
            "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """.trimIndent()
        assertThrows(AirQualityClient.BadResponseException::class.java) {
            AirQualityClient.decode(json, stationName = "중구")
        }
    }
}
