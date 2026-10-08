package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 경로 솎기 — 원본 TrackPoint는 전부 보관하고 표시 직전에만 ~600점으로 줄인다 (#222 선행).
class TrackPointTests {
    private val start = Instant.parse("2026-10-01T06:00:00Z")

    /// 위도만 i씩 늘어나는 n개 점 — 몇 번째 점이 남았는지 위도로 바로 알 수 있다
    private fun points(n: Int): List<TrackPoint> = (0 until n).map { i ->
        TrackPoint(lat = i.toDouble(), lon = 127.0, time = start.plusSeconds(i.toLong()),
                   elevationM = null, horizontalAccuracyM = 5.0, speedMps = null)
    }

    @Test
    @DisplayName("솎기 — 3000점은 stride 5로 600점이 되고 첫 점을 유지한다")
    fun thinsLargeRoute() {
        // 3000 / 600 = 5 → 0, 5, 10, …, 2995번 점 = 600개
        val coordinates = points(3_000).thinnedCoordinates()
        assertEquals(600, coordinates.size)
        assertEquals(0.0, coordinates.first().lat)
        assertEquals(5.0, coordinates[1].lat)
    }

    @Test
    @DisplayName("솎기 — 1000점 이하는 stride 1이라 그대로 남는다")
    fun keepsSmallRoute() {
        // 1000 / 600 = 1 (정수 나눗셈) → 솎지 않는다
        val route = points(1_000)
        assertEquals(route, route.thinned())
        assertEquals(route.map { it.lat }, route.thinnedCoordinates().map { it.lat })
    }

    @Test
    @DisplayName("솎기 — 빈 경로는 빈 좌표를 돌려준다")
    fun emptyRoute() {
        assertTrue(emptyList<TrackPoint>().thinnedCoordinates().isEmpty())
    }
}
