package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 플라이오버 (이슈 #224) — 시간 비례 키프레임·heading 이동 평균·진행률 → 경로 위 보간
class FlyoverEngineTests {
    private val start = Instant.parse("2026-10-01T06:00:00Z")

    /// 북쪽으로 0.001°(≈111m)씩 가는 41점 — 앞 20구간은 20초씩(빠름), 뒤 20구간은 40초씩(느림).
    /// 총 1,200초 = 앞 400초 + 뒤 800초
    private fun route(): List<TrackPoint> {
        var elapsed = 0L
        return (0..40).map { i ->
            if (i > 0) elapsed += if (i <= 20) 20 else 40
            TrackPoint(lat = 37.5 + i * 0.001, lon = 127.0, time = start.plusSeconds(elapsed),
                       elevationM = null, horizontalAccuracyM = 5.0, speedMps = null)
        }
    }

    @Test
    @DisplayName("키프레임 — duration 합이 재생 길이(18초)와 같다")
    fun durationsSumToPlayback() {
        val keyframes = FlyoverEngine.keyframes(assertNotNull(FlyoverEngine.track(route())))
        assertEquals(FlyoverEngine.keyframeCount + 1, keyframes.size)
        assertEquals(0.0, keyframes[0].durationSec)
        assertEquals(18.0, keyframes.sumOf { it.durationSec }, 1e-9)
    }

    @Test
    @DisplayName("키프레임 — 빨리 달린 구간이 짧은 duration을 받는다")
    fun fastSegmentsAreShorter() {
        // 거리 4등분 → 10·20·30구간 지점의 경과 200·400·800·1,200초
        // duration = 구간 소요 / 1,200 × 18 → 0, 3, 3, 6, 6
        val track = assertNotNull(FlyoverEngine.track(route()))
        val durations = FlyoverEngine.keyframes(track, count = 4).map { it.durationSec }
        listOf(0.0, 3.0, 3.0, 6.0, 6.0).zip(durations).forEach { (e, d) -> assertEquals(e, d, 1e-9) }
    }

    @Test
    @DisplayName("진행률 보간 — t=0은 시작점, t=1은 끝점에서 끝난다")
    fun progressEndpoints() {
        val points = route()
        val track = assertNotNull(FlyoverEngine.track(points))
        val first = FlyoverEngine.frame(track, 0.0)
        assertEquals(points[0].lat, first.lat)
        assertEquals(points[0].lon, first.lon)
        assertEquals(0.0, first.distanceM)
        assertEquals(0.0, first.elapsedSec)
        assertNull(first.paceSecPerKm)   // 100m 미만 — 표본 부족
        val last = FlyoverEngine.frame(track, 1.0)
        assertEquals(points[40].lat, last.lat, 1e-12)
        assertEquals(1_200.0, last.elapsedSec)
        assertEquals(41, last.passedCount)
        assertEquals(track.totalM, last.distanceM, 1e-6)
    }

    @Test
    @DisplayName("진행률 보간 — 위치는 거리가 아니라 경과 시간 비례로 움직이고 페이스는 직전 500m 평균이다")
    fun progressFollowsTime() {
        val track = assertNotNull(FlyoverEngine.track(route()))
        // t=0.5 → 600초 = 빠른 20구간(400초) + 느린 5구간(200초) → 25번 점에 도착
        val mid = FlyoverEngine.frame(track, 0.5)
        assertEquals(26, mid.passedCount)
        assertEquals(track.cumulativeM[25], mid.distanceM, 1e-6)
        // 끝 지점의 직전 500m는 전부 느린 구간 — 111.19m당 40초 → ≈ 359.7초/km
        val pace = assertNotNull(FlyoverEngine.frame(track, 1.0).paceSecPerKm)
        assertEquals(40 / track.cumulativeM[1] * 1_000, pace, 0.01)
    }

    @Test
    @DisplayName("heading — 350°→10°로 꺾여도 한 바퀴 돌지 않게 연속값으로 이어진다")
    fun headingUnwraps() {
        // 앞 20구간은 북북서(-10°), 뒤 20구간은 북북동(+10°) 방향
        var lat = 37.5
        var lon = 127.0
        val points = (0..40).map { i ->
            if (i > 0) {
                val deg = (if (i <= 20) -10.0 else 10.0) * PI / 180
                lat += 0.001 * cos(deg)
                lon += 0.001 * sin(deg)
            }
            TrackPoint(lat = lat, lon = lon, time = start.plusSeconds(i * 30L),
                       elevationM = null, horizontalAccuracyM = 5.0, speedMps = null)
        }
        val headings = FlyoverEngine.keyframes(assertNotNull(FlyoverEngine.track(points))).map { it.headingDeg }
        assertTrue(headings.all { abs(it) < 30 })
        assertTrue(headings.zipWithNext().all { (a, b) -> abs(b - a) < 10 })
    }

    @Test
    @DisplayName("기록 거리 보정 — 세션 거리를 주면 누적 거리를 그 길이로 맞추고 페이스도 그 거리로 낸다")
    fun scalesToRecordedDistance() {
        // 40구간 1,200초를 6km로 맞춘다 → 끝 거리 6,000m, 뒤 구간 150m당 40초 → 266.7초/km
        val track = assertNotNull(FlyoverEngine.track(route(), distanceM = 6_000.0))
        val last = FlyoverEngine.frame(track, 1.0)
        assertEquals(6_000.0, last.distanceM, 1e-6)
        assertEquals(40.0 / 150 * 1_000, assertNotNull(last.paceSecPerKm), 0.01)
    }

    @Test
    @DisplayName("표본 부족 가드 — 경로 점이 20개 미만이면 플라이오버를 내지 않는다")
    fun tooFewPoints() {
        assertNull(FlyoverEngine.track(route().take(19)))
        assertNotNull(FlyoverEngine.track(route().take(20)))
    }
}
