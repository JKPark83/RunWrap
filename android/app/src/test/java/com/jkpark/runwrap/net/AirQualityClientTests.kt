package com.jkpark.runwrap.net

import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 대기질 요청 URL 인코딩 (iOS 대응 테스트 없음 — iOS는 URLComponents가 인코딩한다)
class AirQualityClientTests {
    @Test
    @DisplayName("디코딩 인증키의 +·/·=는 한 번만 퍼센트 인코딩되고 측정소명 공백은 %20이다")
    fun encodesServiceKey() {
        val query = airQualityUrl(stationName = "중구 A", serviceKey = "ab+c/d==").query
        assertTrue("serviceKey=ab%2Bc%2Fd%3D%3D&" in query, query)
        assertTrue("stationName=%EC%A4%91%EA%B5%AC%20A&" in query, query)
        assertTrue(query.endsWith("&ver=1.3"), query)
    }
}
