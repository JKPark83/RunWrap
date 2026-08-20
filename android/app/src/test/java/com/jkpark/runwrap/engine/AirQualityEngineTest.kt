package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 최근접 측정소 탐색 — 등장방형 투영 거리와 커버리지 밖 가드 검증.
/// iOS `AirQualityNearestStationTests` 이식 (3개 전부).
class AirQualityNearestStationTest {
    // 서울 도심 언저리의 합성 측정소 2곳 — 좌표는 테스트용 임의값
    private val stations = listOf(
        AirStation(name = "가까운곳", lat = 37.57, lon = 126.98),
        AirStation(name = "먼곳", lat = 37.50, lon = 127.10),
    )

    @Test
    fun `직선거리가 가장 짧은 측정소를 고른다`() {
        // (37.565, 126.975) → 가까운곳 약 0.7km, 먼곳 약 13km
        val station = AirQualityEngine.nearestStation(GeoPoint(lat = 37.565, lon = 126.975), stations)
        assertEquals("가까운곳", station?.name)
    }

    @Test
    fun `커버리지 밖 가드 - 최근접이 30km 밖(도쿄)이면 null`() {
        assertNull(AirQualityEngine.nearestStation(GeoPoint(lat = 35.68, lon = 139.69), stations))
    }

    @Test
    fun `빈 목록 - null (번들이 아직 채워지지 않은 상태의 미노출 가드)`() {
        assertNull(AirQualityEngine.nearestStation(GeoPoint(lat = 37.5, lon = 127.0), emptyList()))
    }
}

/// 공식 등급 구간표 폴백 — 에어코리아 등급 기준 경계값 검증 (응답에 등급이 없을 때만 쓰인다).
/// iOS `AirGradeMappingTests` 이식 (5개 전부).
class AirGradeMappingTest {
    @Test
    fun `PM2_5 - 15와 16, 35와 36, 75와 76 경계에서 등급이 바뀐다`() {
        assertEquals(AirGrade.GOOD, AirQualityEngine.pm25Grade(15.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.pm25Grade(16.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.pm25Grade(35.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.pm25Grade(36.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.pm25Grade(75.0))
        assertEquals(AirGrade.VERY_BAD, AirQualityEngine.pm25Grade(76.0))
    }

    @Test
    fun `PM10 - 30과 31, 80과 81, 150과 151 경계에서 등급이 바뀐다`() {
        assertEquals(AirGrade.GOOD, AirQualityEngine.pm10Grade(30.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.pm10Grade(31.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.pm10Grade(80.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.pm10Grade(81.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.pm10Grade(150.0))
        assertEquals(AirGrade.VERY_BAD, AirQualityEngine.pm10Grade(151.0))
    }

    @Test
    fun `오존 - 0_030과 0_031, 0_090과 0_091, 0_150과 0_151 경계에서 등급이 바뀐다`() {
        assertEquals(AirGrade.GOOD, AirQualityEngine.o3Grade(0.030))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.o3Grade(0.031))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.o3Grade(0.090))
        assertEquals(AirGrade.BAD, AirQualityEngine.o3Grade(0.091))
        assertEquals(AirGrade.BAD, AirQualityEngine.o3Grade(0.150))
        assertEquals(AirGrade.VERY_BAD, AirQualityEngine.o3Grade(0.151))
    }

    @Test
    fun `통합지수 - 50과 51, 100과 101, 250과 251 경계에서 등급이 바뀐다`() {
        assertEquals(AirGrade.GOOD, AirQualityEngine.khaiGrade(50.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.khaiGrade(51.0))
        assertEquals(AirGrade.MODERATE, AirQualityEngine.khaiGrade(100.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.khaiGrade(101.0))
        assertEquals(AirGrade.BAD, AirQualityEngine.khaiGrade(250.0))
        assertEquals(AirGrade.VERY_BAD, AirQualityEngine.khaiGrade(251.0))
    }

    @Test
    fun `등급에서 톤으로 - 좋음 improving부터 매우나쁨 overload까지 순서대로`() {
        assertEquals(RRTone.IMPROVING, AirGrade.GOOD.tone)
        assertEquals(RRTone.STEADY, AirGrade.MODERATE.tone)
        assertEquals(RRTone.CAUTION, AirGrade.BAD.tone)
        assertEquals(RRTone.OVERLOAD, AirGrade.VERY_BAD.tone)
    }
}

/// 대표 등급·미노출 가드·캐시 신선도. iOS `AirQualityGuardTests` 이식 (5개 전부).
class AirQualityGuardTest {
    private fun quality(
        pm10: Double? = null,
        pm25: Double? = null,
        pm10Grade: AirGrade? = null,
        pm25Grade: AirGrade? = null,
        khaiGrade: AirGrade? = null,
    ) = AirQuality(
        stationName = "측정소", dataTime = null,
        pm10 = pm10, pm25 = pm25, o3 = null, khai = null,
        pm10Grade = pm10Grade, pm25Grade = pm25Grade,
        o3Grade = null, khaiGrade = khaiGrade,
    )

    @Test
    fun `대표 등급 - 통합지수 등급이 있으면 PM보다 우선한다`() {
        val grade = AirQualityEngine.representativeGrade(
            quality(pm10Grade = AirGrade.VERY_BAD, pm25Grade = AirGrade.VERY_BAD,
                    khaiGrade = AirGrade.MODERATE))
        assertEquals(AirGrade.MODERATE, grade)
    }

    @Test
    fun `대표 등급 - 통합지수가 없으면 PM 두 등급 중 나쁜 쪽`() {
        val grade = AirQualityEngine.representativeGrade(
            quality(pm10Grade = AirGrade.GOOD, pm25Grade = AirGrade.BAD))
        assertEquals(AirGrade.BAD, grade)
    }

    @Test
    fun `대표 등급 - 등급이 하나도 없으면 null (배지 미노출)`() {
        assertNull(AirQualityEngine.representativeGrade(quality()))
    }

    @Test
    fun `표본 부족 가드 - PM 수치가 하나도 없으면 지표를 내지 않는다`() {
        assertFalse(AirQualityEngine.hasReading(quality()))
        assertTrue(AirQualityEngine.hasReading(quality(pm10 = 34.0)))
        assertTrue(AirQualityEngine.hasReading(quality(pm25 = 19.0)))
    }

    @Test
    fun `캐시 1시간 - 59분 전은 신선, 61분 전과 미래 시각은 아니다`() {
        val now = OffsetDateTime.parse("2026-08-20T10:00:00+09:00").toInstant()
        assertTrue(AirQualityEngine.isFresh(fetchedAt = now.minusSeconds(59 * 60), now = now))
        assertFalse(AirQualityEngine.isFresh(fetchedAt = now.minusSeconds(61 * 60), now = now))
        // 기기 시계 역행(미래 fetchedAt)은 신선으로 치지 않는다 — 캐시를 다시 받는 쪽이 안전
        assertFalse(AirQualityEngine.isFresh(fetchedAt = now.plusSeconds(600), now = now))
    }
}
