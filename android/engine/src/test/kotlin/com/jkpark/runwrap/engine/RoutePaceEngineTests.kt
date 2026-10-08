package com.jkpark.runwrap.engine

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 페이스 색 경로 · km 마커 · 고도 프로필 (이슈 #222 §2) — 평활·미노출 가드.
class RoutePaceEngineTests {
    private val start = iso("2026-10-08T06:00:00Z")
    /// 위도 0.0001°(≈11.12m, GPXParser 기준 111,195m/°) 간격 직선 — 100m 반창에 정확히 8다리가 든다.
    /// legSeconds[i] = i번 점 → i+1번 점 소요 초
    private fun line(legSeconds: List<Double>, accuracy: (Int) -> Double = { 5.0 },
                     elevation: (Int) -> Double? = { null }): List<TrackPoint> {
        var time = start
        return (0..legSeconds.size).map { i ->
            if (i > 0) time = time.plusMillis((legSeconds[i - 1] * 1_000).toLong())
            TrackPoint(lat = 37.5 + i * 0.0001, lon = 127.0, time = time,
                       elevationM = elevation(i), horizontalAccuracyM = accuracy(i), speedMps = null)
        }
    }

    private fun repeated(value: Double, count: Int) = List(count) { value }

    @Test
    @DisplayName("표본 부족 가드 — 점 10개 미만·거리 500m 미만이면 구간을 내지 않는다")
    fun sampleGuard() {
        assertNull(RoutePaceEngine.segments(line(repeated(3.0, 8))))   // 9점
        assertNull(RoutePaceEngine.segments(line(repeated(3.0, 40))))  // 444.8m
    }

    @Test
    @DisplayName("평활 — 1초·5초가 번갈아도(순간 90↔450초/km) 200m 창 평균은 고르게 steady 한 구간")
    fun smoothsAlternatingLegs() {
        // 가운데 창은 16다리(두 다리 22.24m에 6초로 일정) → 비율 1.0.
        // 시작·끝은 창을 안쪽으로 밀어 17다리(0~200m) → 53/17 또는 49/17초 ÷ 3초 = 1.039·0.961 → ±5% 안 steady
        val points = line((0 until 100).map { if (it % 2 == 0) 1.0 else 5.0 })
        val segments = assertNotNull(RoutePaceEngine.segments(points))
        assertEquals(1, segments.size)
        assertEquals(RRTone.steady, segments[0].tone)
        assertEquals(points, segments[0].points)
    }

    @Test
    @DisplayName("후반 처짐 — 중앙값보다 15% 넘게 느린 끝 구간은 overload, 앞은 steady")
    fun slowFinish() {
        // 앞 100다리 3초, 뒤 50다리 3.6초(+20%) → 중앙값은 앞쪽 페이스, 끝 비율 1.2 → overload
        val points = line(repeated(3.0, 100) + repeated(3.6, 50))
        val segments = assertNotNull(RoutePaceEngine.segments(points))
        assertEquals(RRTone.steady, segments.first().tone)
        assertEquals(RRTone.overload, segments.last().tone)
        // 이웃 구간은 경계 점을 공유한다 — 폴리라인이 끊기지 않는다
        for ((a, b) in segments.zip(segments.drop(1))) assertEquals(a.points.last(), b.points.first())
    }

    @Test
    @DisplayName("정확도 나쁜 구간 — 수평 정확도 50m 초과 점이 닿는 다리는 tone nil(회색)")
    fun poorAccuracyIsGray() {
        // 50~59번 점 정확도 80m → 49~59번 다리가 nil
        val points = line(repeated(3.0, 100), accuracy = { if (it in 50 until 60) 80.0 else 5.0 })
        val segments = assertNotNull(RoutePaceEngine.segments(points))
        assertEquals(listOf(RRTone.steady, null, RRTone.steady), segments.map { it.tone })
        assertEquals(points[49], segments[1].points.first())
        assertEquals(points[60], segments[1].points.last())
    }

    @Test
    @DisplayName("km 마커 — 누적 1km를 처음 넘는 점, 경로가 짧으면 그만큼만")
    fun kmMarkers() {
        // 11.12m 간격 250다리 = 2.78km → 1km는 90번(1000.8m)·2km는 180번 점 (스플릿이 3개라도 2개)
        val points = line(repeated(3.0, 250))
        val markers = RoutePaceEngine.kmMarkers(points, count = 3)
        assertEquals(2, markers.size)
        assertTrue(abs(markers[0].lat - points[90].lat) < 1e-12)
        assertTrue(abs(markers[1].lat - points[180].lat) < 1e-12)
        assertEquals(1, RoutePaceEngine.kmMarkers(points, count = 1).size)
    }

    @Test
    @DisplayName("고도 프로필 — 고도 있는 점이 10개 미만이면 nil")
    fun elevationGuard() {
        val points = line(repeated(3.0, 100), elevation = { if (it < 9) 20.0 else null })
        assertNull(RoutePaceEngine.elevationProfile(points))
    }

    @Test
    @DisplayName("고도 프로필 — 누적 거리 축을 균등 표본으로 나눠 가장 가까운 점의 고도를 쓴다")
    fun elevationProfile() {
        // 100다리 = 1.112km, 고도 = 점 번호(m). 5등분 → 0·25·50·75·100번 점 → 0·25·50·75·100m
        val points = line(repeated(3.0, 100), elevation = { it.toDouble() })
        val profile = assertNotNull(RoutePaceEngine.elevationProfile(points, samples = 5))
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), profile.map { it.elevationM })
        assertTrue(abs(profile.last().distanceKm - 1.11195) < 1e-9)
    }
}
