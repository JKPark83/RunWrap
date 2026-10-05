package com.jkpark.runwrap.engine

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/// iOS AirQualityEngineTests.swift의 일곱 스위트를 @Nested로 옮긴다.
class AirQualityEngineTests {

    /// 최근접 측정소 탐색 — 등장방형 투영 거리와 커버리지 밖 가드 검증
    @Nested
    inner class AirQualityNearestStationTests {
        // 서울 도심 언저리의 합성 측정소 2곳 — 좌표는 테스트용 임의값
        private val stations = listOf(
            AirStation(name = "가까운곳", lat = 37.57, lon = 126.98),
            AirStation(name = "먼곳", lat = 37.50, lon = 127.10),
        )

        @Test
        @DisplayName("직선거리가 가장 짧은 측정소를 고른다")
        fun nearest() {
            // (37.565, 126.975) → 가까운곳 약 0.7km, 먼곳 약 13km
            val station = AirQualityEngine.nearestStation(to = GeoPoint(lat = 37.565, lon = 126.975),
                                                          stations = stations)
            assertEquals("가까운곳", station?.name)
        }

        @Test
        @DisplayName("커버리지 밖 가드 — 최근접이 30km 밖(도쿄)이면 nil")
        fun outOfCoverage() {
            val station = AirQualityEngine.nearestStation(to = GeoPoint(lat = 35.68, lon = 139.69),
                                                          stations = stations)
            assertNull(station)
        }

        @Test
        @DisplayName("빈 목록 — nil (번들이 아직 채워지지 않은 상태의 미노출 가드)")
        fun emptyStations() {
            val station = AirQualityEngine.nearestStation(to = GeoPoint(lat = 37.5, lon = 127.0),
                                                          stations = emptyList())
            assertNull(station)
        }
    }

    /// 공식 등급 구간표 폴백 — 에어코리아 등급 기준 경계값 검증 (응답에 등급이 없을 때만 쓰인다)
    @Nested
    inner class AirGradeMappingTests {
        @Test
        @DisplayName("PM2.5 — 15/16, 35/36, 75/76 경계에서 등급이 바뀐다")
        fun pm25Boundaries() {
            assertEquals(AirGrade.good, AirQualityEngine.pm25Grade(15.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.pm25Grade(16.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.pm25Grade(35.0))
            assertEquals(AirGrade.bad, AirQualityEngine.pm25Grade(36.0))
            assertEquals(AirGrade.bad, AirQualityEngine.pm25Grade(75.0))
            assertEquals(AirGrade.veryBad, AirQualityEngine.pm25Grade(76.0))
        }

        @Test
        @DisplayName("PM10 — 30/31, 80/81, 150/151 경계에서 등급이 바뀐다")
        fun pm10Boundaries() {
            assertEquals(AirGrade.good, AirQualityEngine.pm10Grade(30.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.pm10Grade(31.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.pm10Grade(80.0))
            assertEquals(AirGrade.bad, AirQualityEngine.pm10Grade(81.0))
            assertEquals(AirGrade.bad, AirQualityEngine.pm10Grade(150.0))
            assertEquals(AirGrade.veryBad, AirQualityEngine.pm10Grade(151.0))
        }

        @Test
        @DisplayName("오존 — 0.030/0.031, 0.090/0.091, 0.150/0.151 경계에서 등급이 바뀐다")
        fun o3Boundaries() {
            assertEquals(AirGrade.good, AirQualityEngine.o3Grade(0.030))
            assertEquals(AirGrade.moderate, AirQualityEngine.o3Grade(0.031))
            assertEquals(AirGrade.moderate, AirQualityEngine.o3Grade(0.090))
            assertEquals(AirGrade.bad, AirQualityEngine.o3Grade(0.091))
            assertEquals(AirGrade.bad, AirQualityEngine.o3Grade(0.150))
            assertEquals(AirGrade.veryBad, AirQualityEngine.o3Grade(0.151))
        }

        @Test
        @DisplayName("통합지수 — 50/51, 100/101, 250/251 경계에서 등급이 바뀐다")
        fun khaiBoundaries() {
            assertEquals(AirGrade.good, AirQualityEngine.khaiGrade(50.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.khaiGrade(51.0))
            assertEquals(AirGrade.moderate, AirQualityEngine.khaiGrade(100.0))
            assertEquals(AirGrade.bad, AirQualityEngine.khaiGrade(101.0))
            assertEquals(AirGrade.bad, AirQualityEngine.khaiGrade(250.0))
            assertEquals(AirGrade.veryBad, AirQualityEngine.khaiGrade(251.0))
        }

        @Test
        @DisplayName("등급 → 톤 — 좋음 improving부터 매우나쁨 overload까지 순서대로")
        fun tones() {
            assertEquals(RRTone.improving, AirGrade.good.tone)
            assertEquals(RRTone.steady, AirGrade.moderate.tone)
            assertEquals(RRTone.caution, AirGrade.bad.tone)
            assertEquals(RRTone.overload, AirGrade.veryBad.tone)
        }
    }

    /// 대표 등급·미노출 가드·캐시 신선도
    @Nested
    inner class AirQualityGuardTests {
        private fun quality(pm10: Double? = null, pm25: Double? = null,
                            pm10Grade: AirGrade? = null, pm25Grade: AirGrade? = null,
                            khaiGrade: AirGrade? = null): AirQuality =
            AirQuality(stationName = "측정소", dataTime = null,
                       pm10 = pm10, pm25 = pm25, o3 = null, khai = null,
                       pm10Grade = pm10Grade, pm25Grade = pm25Grade,
                       o3Grade = null, khaiGrade = khaiGrade)

        @Test
        @DisplayName("대표 등급 — 통합지수 등급이 있으면 PM보다 우선한다")
        fun representativePrefersKhai() {
            val grade = AirQualityEngine.representativeGrade(
                quality(pm10Grade = AirGrade.veryBad, pm25Grade = AirGrade.veryBad, khaiGrade = AirGrade.moderate))
            assertEquals(AirGrade.moderate, grade)
        }

        @Test
        @DisplayName("대표 등급 — 통합지수가 없으면 PM 두 등급 중 나쁜 쪽")
        fun representativeWorstPM() {
            val grade = AirQualityEngine.representativeGrade(
                quality(pm10Grade = AirGrade.good, pm25Grade = AirGrade.bad))
            assertEquals(AirGrade.bad, grade)
        }

        @Test
        @DisplayName("대표 등급 — 등급이 하나도 없으면 nil (배지 미노출)")
        fun representativeNone() {
            assertNull(AirQualityEngine.representativeGrade(quality()))
        }

        @Test
        @DisplayName("표본 부족 가드 — PM 수치가 하나도 없으면 지표를 내지 않는다")
        fun hasReading() {
            assertFalse(AirQualityEngine.hasReading(quality()))
            assertTrue(AirQualityEngine.hasReading(quality(pm10 = 34.0)))
            assertTrue(AirQualityEngine.hasReading(quality(pm25 = 19.0)))
        }

        @Test
        @DisplayName("캐시 폴백 1시간 — dataTime이 없으면 59분 전은 신선, 61분 전·미래 시각은 아니다")
        fun freshness() {
            val now = iso("2026-08-20T10:00:00+09:00")
            assertTrue(AirQualityEngine.isFresh(dataTime = null, fetchedAt = now.plusSeconds(-59L * 60), now = now))
            assertFalse(AirQualityEngine.isFresh(dataTime = null, fetchedAt = now.plusSeconds(-61L * 60), now = now))
            // 기기 시계 역행(미래 fetchedAt)은 신선으로 치지 않는다 — 캐시를 다시 받는 쪽이 안전
            assertFalse(AirQualityEngine.isFresh(dataTime = null, fetchedAt = now.plusSeconds(600), now = now))
            // 형식이 어긋난 dataTime도 같은 폴백
            assertTrue(AirQualityEngine.isFresh(dataTime = "측정중", fetchedAt = now.plusSeconds(-59L * 60), now = now))
        }
    }

    /// 측정 시각(dataTime) 기반 신선도·낡은 측정값 가드 (이슈 #83) — 모든 시각은 KST 고정
    @Nested
    inner class AirQualityFreshnessTests {
        private fun kst(text: String): Instant = iso("$text+09:00")

        @Test
        @DisplayName("dataTime 파싱 — KST로 읽고, 자정 표기 \"24:00\"은 다음 날 00:00")
        fun measuredAt() {
            assertEquals(kst("2026-08-20T14:00:00"), AirQualityEngine.measuredAt("2026-08-20 14:00"))
            assertEquals(kst("2026-08-21T00:00:00"), AirQualityEngine.measuredAt("2026-08-20 24:00"))
            assertNull(AirQualityEngine.measuredAt(null))
            assertNull(AirQualityEngine.measuredAt("-"))
            assertNull(AirQualityEngine.measuredAt("2026-08-20"))
            // 달·일 범위 밖은 Calendar가 다음 달로 넘기지 않도록 거른다
            assertNull(AirQualityEngine.measuredAt("2026-13-01 10:00"))
            assertNull(AirQualityEngine.measuredAt("2026-08-32 10:00"))
        }

        @Test
        @DisplayName("만료 = 측정 정시 + 1시간 20분 — 14:55에 받은 14:00 값은 15:20에 만료된다")
        fun dataTimeExpiry() {
            val fetchedAt = kst("2026-08-20T14:55:00")
            // 14:00 + 80분 = 15:20 → 15:19는 신선, 15:20은 만료
            assertTrue(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00", fetchedAt = fetchedAt,
                                                now = kst("2026-08-20T15:19:00")))
            assertFalse(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00", fetchedAt = fetchedAt,
                                                 now = kst("2026-08-20T15:20:00")))
            // 예전 규칙(받은 지 1시간 → 15:55까지)이면 신선이었을 15:30 — 이제는 15:00 값을 받으러 간다
            assertFalse(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00", fetchedAt = fetchedAt,
                                                 now = kst("2026-08-20T15:30:00")))
        }

        @Test
        @DisplayName("재조회 하한 10분 — 서버가 아직 지난 정시 값을 줘도 받은 지 10분 안은 신선하다")
        fun minimumRefetchInterval() {
            // 15:20에 다시 받았는데 15:00 값 공개가 늦어 또 14:00 값 → dataTime 기준으론 받자마자 만료
            val fetchedAt = kst("2026-08-20T15:20:00")
            assertTrue(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00", fetchedAt = fetchedAt,
                                                now = kst("2026-08-20T15:29:00")))
            // 15:20 + 10분 = 15:30 → 이때부터 다시 받으러 간다
            assertFalse(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00", fetchedAt = fetchedAt,
                                                 now = kst("2026-08-20T15:30:00")))
        }

        @Test
        @DisplayName("dataTime이 있어도 미래 fetchedAt(시계 역행)은 신선으로 치지 않는다")
        fun futureFetchedAt() {
            val now = kst("2026-08-20T14:30:00")
            assertFalse(AirQualityEngine.isFresh(dataTime = "2026-08-20 14:00",
                                                 fetchedAt = now.plusSeconds(600), now = now))
        }

        @Test
        @DisplayName("낡은 측정값 가드 — now보다 3시간 이상 뒤처지면 미노출, 못 읽으면 막지 않는다")
        fun recent() {
            val now = kst("2026-08-20T17:00:00")
            // 17:00 − 14:01 = 2시간 59분 → 노출, 17:00 − 14:00 = 정확히 3시간 → 미노출
            assertTrue(AirQualityEngine.isRecent(dataTime = "2026-08-20 14:01", now = now))
            assertFalse(AirQualityEngine.isRecent(dataTime = "2026-08-20 14:00", now = now))
            assertFalse(AirQualityEngine.isRecent(dataTime = "2026-08-19 17:00", now = now))
            assertTrue(AirQualityEngine.isRecent(dataTime = null, now = now))
        }
    }

    /// negative cache — 차단 해제 시각·차단 판정 (이슈 #83)
    @Nested
    inner class AirQualityBlockTests {
        @Test
        @DisplayName("한도 초과 — 다음 KST 자정까지 막는다 (기기 시간대와 무관)")
        fun quotaUntilMidnight() {
            // KST 23:30 → 같은 날 밤 자정(다음 날 00:00)
            assertEquals(iso("2026-08-21T00:00:00+09:00"),
                         AirQualityEngine.blockedUntil(quotaExceeded = true, now = iso("2026-08-20T23:30:00+09:00")))
            // UTC 16:30 = KST 다음 날 01:30 → UTC 날짜가 아니라 KST 날짜 기준 다음 자정(22일 00:00)
            assertEquals(iso("2026-08-22T00:00:00+09:00"),
                         AirQualityEngine.blockedUntil(quotaExceeded = true, now = iso("2026-08-20T16:30:00Z")))
            // 정확히 자정에 막히면 하루 뒤 자정까지
            assertEquals(iso("2026-08-22T00:00:00+09:00"),
                         AirQualityEngine.blockedUntil(quotaExceeded = true, now = iso("2026-08-21T00:00:00+09:00")))
        }

        @Test
        @DisplayName("그 밖의 실패 — 10분만 막는다")
        fun otherFailureTenMinutes() {
            val now = iso("2026-08-20T14:00:00+09:00")
            assertEquals(now.plusSeconds(600),
                         AirQualityEngine.blockedUntil(quotaExceeded = false, now = now))
        }

        @Test
        @DisplayName("차단 판정 — 기록 없음·해제 시각 도달은 통과, 그 전은 차단")
        fun isBlocked() {
            val until = iso("2026-08-21T00:00:00+09:00")
            assertFalse(AirQualityEngine.isBlocked(blockedUntil = null, now = until))
            assertTrue(AirQualityEngine.isBlocked(blockedUntil = until, now = until.plusSeconds(-1)))
            assertFalse(AirQualityEngine.isBlocked(blockedUntil = until, now = until))
        }
    }

    /// 응답 디코드 — 측정소별 실시간 측정정보(ver 1.3) 응답 축약 fixture 검증
    @Nested
    inner class AirQualityClientDecodeTests {
        private fun data(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

        @Test
        @DisplayName("정상 응답 — 문자열 수치·공식 등급을 그대로 파싱한다")
        fun normal() {
            val json = """{"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",""" +
                """"pm10Value":"34","pm25Value":"19","o3Value":"0.031","khaiValue":"68",""" +
                """"pm10Grade1h":"2","pm25Grade1h":"2","o3Grade":"2","khaiGrade":"2"}]},""" +
                """"header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}"""
            val quality = AirQualityClient.decode(data(json), stationName = "중구")
            assertEquals("중구", quality.stationName)
            assertEquals("2026-08-20 14:00", quality.dataTime)
            assertEquals(34.0, quality.pm10)
            assertEquals(19.0, quality.pm25)
            assertEquals(0.031, quality.o3)
            assertEquals(68.0, quality.khai)
            assertEquals(AirGrade.moderate, quality.pm25Grade)
            assertEquals(AirGrade.moderate, quality.khaiGrade)
        }

        @Test
        @DisplayName("결측 \"-\" — 수치는 nil로 접고, 등급이 빠진 항목만 수치로 보완한다")
        fun missingValues() {
            // pm25는 수치만 있고 등급이 "-" → 공식 구간표 폴백(80 → 매우나쁨).
            // o3·khai는 통신장애로 수치·등급 모두 "-" → 전부 nil
            val json = """{"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",""" +
                """"pm10Value":"-","pm25Value":"80","o3Value":"-","khaiValue":"-",""" +
                """"pm10Grade1h":"-","pm25Grade1h":"-","o3Grade":"-","khaiGrade":"-"}]},""" +
                """"header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}"""
            val quality = AirQualityClient.decode(data(json), stationName = "중구")
            assertNull(quality.pm10)
            assertNull(quality.pm10Grade)
            assertEquals(80.0, quality.pm25)
            assertEquals(AirGrade.veryBad, quality.pm25Grade)
            assertNull(quality.o3)
            assertNull(quality.khai)
            assertNull(quality.khaiGrade)
        }

        @Test
        @DisplayName("오류 응답 — resultCode ≠ 00이면 throw (스토어가 unavailable로 접는다)")
        fun errorResult() {
            val json = """{"response":{"body":{"totalCount":0,"items":[]},""" +
                """"header":{"resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","resultCode":"30"}}}"""
            assertFails {
                AirQualityClient.decode(data(json), stationName = "중구")
            }
        }

        @Test
        @DisplayName("빈 응답 — 항목이 없으면 throw")
        fun emptyItems() {
            val json = """{"response":{"body":{"totalCount":0,"items":[]},""" +
                """"header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}"""
            assertFails {
                AirQualityClient.decode(data(json), stationName = "중구")
            }
        }

        @Test
        @DisplayName("한도 초과 JSON — resultCode 22는 quotaExceeded (스토어가 자정까지 막는다)")
        fun quotaExceeded() {
            val json = """{"response":{"header":{"resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR",""" +
                """"resultCode":"22"}}}"""
            val error = assertFailsWith<AirQualityClient.ClientError> {
                AirQualityClient.decode(data(json), stationName = "중구")
            }
            assertEquals(AirQualityClient.ClientError.quotaExceeded, error)
        }

        @Test
        @DisplayName("키 오류 — resultCode 30·31도 quotaExceeded, 그 밖의 오류 코드는 badResponse")
        fun keyErrors() {
            for ((code, expected) in listOf("30" to AirQualityClient.ClientError.quotaExceeded,
                                            "31" to AirQualityClient.ClientError.quotaExceeded,
                                            "03" to AirQualityClient.ClientError.badResponse)) {
                val json = """{"response":{"body":{"totalCount":0,"items":[]},""" +
                    """"header":{"resultMsg":"ERROR","resultCode":"$code"}}}"""
                val error = assertFailsWith<AirQualityClient.ClientError> {
                    AirQualityClient.decode(data(json), stationName = "중구")
                }
                assertEquals(expected, error)
            }
        }

        @Test
        @DisplayName("XML 본문 — 게이트웨이 한도 오류(returnReasonCode 22)는 quotaExceeded, 그 밖은 badResponse")
        fun xmlBodies() {
            val quota = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>" +
                "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>" +
                "<returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"
            val quotaError = assertFailsWith<AirQualityClient.ClientError> {
                AirQualityClient.decode(data(quota), stationName = "중구")
            }
            assertEquals(AirQualityClient.ClientError.quotaExceeded, quotaError)
            val timeout = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>" +
                "<returnAuthMsg>SERVICETIMEOUT</returnAuthMsg>" +
                "<returnReasonCode>04</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"
            val timeoutError = assertFailsWith<AirQualityClient.ClientError> {
                AirQualityClient.decode(data(timeout), stationName = "중구")
            }
            assertEquals(AirQualityClient.ClientError.badResponse, timeoutError)
        }

        @Test
        @DisplayName("JSON이 아닌 본문 — 디코드 실패도 badResponse로 구분한다")
        fun garbageBody() {
            val error = assertFailsWith<AirQualityClient.ClientError> {
                AirQualityClient.decode(data("Unexpected errors"), stationName = "중구")
            }
            assertEquals(AirQualityClient.ClientError.badResponse, error)
        }
    }

    /// 재시도 판단 — 5xx·타임아웃만 일시 장애로 보고 다시 부른다 (이슈 #83)
    @Nested
    inner class AirQualityClientRetryTests {
        @Test
        @DisplayName("5xx는 재시도, 2xx·4xx·상태 없음은 재시도하지 않는다")
        fun statuses() {
            assertTrue(AirQualityClient.shouldRetry(status = 504, error = null))
            assertTrue(AirQualityClient.shouldRetry(status = 500, error = null))
            assertFalse(AirQualityClient.shouldRetry(status = 200, error = null))
            assertFalse(AirQualityClient.shouldRetry(status = 404, error = null))
            assertFalse(AirQualityClient.shouldRetry(status = 429, error = null))
            assertFalse(AirQualityClient.shouldRetry(status = null, error = null))
        }

        @Test
        @DisplayName("백오프 — 0.5초·1초 두 번만, 백오프 뒤 1초도 못 쓰면 포기")
        fun delays() {
            assertEquals(0.5, AirQualityClient.retryDelay(attempt = 1, remaining = 7.0))
            assertEquals(1.0, AirQualityClient.retryDelay(attempt = 2, remaining = 3.5))
            assertNull(AirQualityClient.retryDelay(attempt = 3, remaining = 9.0))
            // 남은 1.9초 ≤ 1초 백오프 + 1초 시도
            assertNull(AirQualityClient.retryDelay(attempt = 2, remaining = 1.9))
        }

        @Test
        @DisplayName("매번 멈춰 타임아웃돼도 10초 안에 세 번 시도한다 — 시도당 타임아웃 3초 상한")
        fun stalledAttemptsFitBudget() {
            // 3 + 0.5 + 3 + 1 + 2.5 = 10 — 첫 시도가 10초를 다 쓰면 타임아웃 재시도가 불가능하다
            var remaining = 10.0
            var attempts = 0
            while (true) {
                remaining -= AirQualityClient.attemptTimeout(remaining = remaining)
                attempts += 1
                val delay = AirQualityClient.retryDelay(attempt = attempts, remaining = remaining) ?: break
                remaining -= delay
            }
            assertEquals(3, attempts)
            assertTrue(remaining >= 0)
        }

        /// (Android: URLError(.timedOut) ↔ SocketTimeoutException, .notConnectedToInternet ↔ UnknownHostException,
        ///  .cancelled ↔ InterruptedIOException(스레드 인터럽트), CancellationError ↔ 코루틴 CancellationException)
        @Test
        @DisplayName("URLError.timedOut만 재시도 — 오프라인·취소·기타 오류는 즉시 포기")
        fun errors() {
            assertTrue(AirQualityClient.shouldRetry(status = null, error = SocketTimeoutException()))
            assertFalse(AirQualityClient.shouldRetry(status = null, error = UnknownHostException()))
            assertFalse(AirQualityClient.shouldRetry(status = null, error = InterruptedIOException()))
            assertFalse(AirQualityClient.shouldRetry(status = null, error = CancellationException()))
        }
    }
}
