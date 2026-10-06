package com.jkpark.runwrap.engine

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 현재 위치 주변 보급 검색 엔진 검증.
/// 기준점은 석촌호수 남단(37.5100, 127.1000) 고정 — 위도 37.51에서
/// 위도 1도 = 111,195m, 경도 1도 = 111,195 × cos(37.51°) ≈ 88,197m다.
@DisplayName("주변 보급 검색 엔진")
class NearbySupplyEngineTests {
    private val center = GeoPoint(lat = 37.5100, lon = 127.1000)

    /// 위도만 dy미터 북쪽으로 옮긴 POI — 경도 축척을 안 타서 기대값이 정확하다
    private fun poi(kind: CoursePOI.Kind, north: Double, name: String = "테스트"): CoursePOI =
        CoursePOI(kind = kind, name = name, lat = center.lat + north / 111_195.0, lon = center.lon)

    @Test
    @DisplayName("반경 안은 거리와 함께 잡고, 반경 밖은 버린다")
    fun radius() {
        val pois = listOf(poi(CoursePOI.Kind.water, north = 300.0, name = "가까운 음수대"),
                          poi(CoursePOI.Kind.toilet, north = 1_500.0, name = "먼 화장실"))
        val result = assertNotNull(NearbySupplyEngine.search(center = center, pois = pois, radiusMeters = 1_000.0))
        assertEquals(1, result.matches.size)
        assertEquals("가까운 음수대", result.matches[0].poi.name)
        // 300m를 넣었으니 오차 1m 안에서 300이 나와야 한다
        assertTrue(abs(result.matches[0].meters - 300) < 1)
    }

    @Test
    @DisplayName("가까운 순으로 정렬한다 — 입력 순서와 무관하게")
    fun sorted() {
        val pois = listOf(poi(CoursePOI.Kind.convenience, north = 800.0, name = "먼 편의점"),
                          poi(CoursePOI.Kind.water, north = 100.0, name = "가까운 음수대"),
                          poi(CoursePOI.Kind.toilet, north = 400.0, name = "중간 화장실"))
        val result = assertNotNull(NearbySupplyEngine.search(center = center, pois = pois, radiusMeters = 1_000.0))
        assertEquals(listOf("가까운 음수대", "중간 화장실", "먼 편의점"), result.matches.map { it.poi.name })
    }

    @Test
    @DisplayName("반경 경계 — 정확히 반경 위의 지점은 포함한다")
    fun boundary() {
        val result = assertNotNull(NearbySupplyEngine.search(center = center,
                                                             pois = listOf(poi(CoursePOI.Kind.water, north = 500.0)),
                                                             radiusMeters = 500.0))
        assertEquals(1, result.matches.size)
    }

    @Test
    @DisplayName("주변에 아무것도 없으면 nil이 아니라 빈 목록 — '없다'와 '못 찾는다'는 다르다")
    fun emptyIsNotNil() {
        val result = assertNotNull(NearbySupplyEngine.search(center = center,
                                                             pois = listOf(poi(CoursePOI.Kind.water, north = 5_000.0)),
                                                             radiusMeters = 1_000.0))
        assertTrue(result.matches.isEmpty())
    }

    @Test
    @DisplayName("미노출 가드 — 반경이 0 이하면 검색이 성립하지 않아 nil")
    fun guardRadius() {
        assertNull(NearbySupplyEngine.search(center = center, pois = listOf(poi(CoursePOI.Kind.water, north = 10.0)),
                                             radiusMeters = 0.0))
        assertNull(NearbySupplyEngine.search(center = center, pois = listOf(poi(CoursePOI.Kind.water, north = 10.0)),
                                             radiusMeters = -100.0))
    }

    @Test
    @DisplayName("종류별 상한 10개 — 편의점이 밀집해도 목록을 독식하지 않는다")
    fun perKindCap() {
        // 편의점 15개(10~150m)와 음수대 1개(200m)
        val pois = (1..15).map { poi(CoursePOI.Kind.convenience, north = it * 10.0, name = "편의점$it") }.toMutableList()
        pois.add(poi(CoursePOI.Kind.water, north = 200.0, name = "음수대"))
        val result = assertNotNull(NearbySupplyEngine.search(center = center, pois = pois, radiusMeters = 1_000.0))
        val convenienceCount = result.matches.count { it.poi.kind == CoursePOI.Kind.convenience }
        assertEquals(10, convenienceCount)
        // 상한에 걸려도 음수대는 살아남는다 — 상한은 종류마다 따로 센다
        assertTrue(result.matches.any { it.poi.kind == CoursePOI.Kind.water })
    }

    @Test
    @DisplayName("상한 적용 후에도 전체 정렬은 거리순을 유지한다")
    fun sortedAfterCap() {
        val pois = (1..12).map { poi(CoursePOI.Kind.convenience, north = it * 10.0, name = "편의점$it") }.toMutableList()
        pois.add(poi(CoursePOI.Kind.water, north = 35.0, name = "음수대"))
        val result = assertNotNull(NearbySupplyEngine.search(center = center, pois = pois, radiusMeters = 1_000.0))
        val distances = result.matches.map { it.meters }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    @DisplayName("결과에 기준점과 반경을 그대로 담는다 — 화면이 지도 카메라에 쓴다")
    fun echoesCenter() {
        val result = assertNotNull(NearbySupplyEngine.search(center = center, pois = emptyList(), radiusMeters = 800.0))
        assertEquals(center, result.center)
        assertEquals(800.0, result.radiusMeters)
    }
}
