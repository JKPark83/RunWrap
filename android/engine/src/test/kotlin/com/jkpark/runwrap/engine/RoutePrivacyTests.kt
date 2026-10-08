package com.jkpark.runwrap.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 공유 카드 경로 트림 — 시작·끝 300m를 누적 거리로 잘라내는지 검증한다 (이슈 #84).
class RoutePrivacyTests {
    /// 위도 1°의 대략적 길이(m) — 경도 고정 남북 직선이라 위도 차만으로 거리가 정해진다
    private val metersPerDegree = 111_195.0

    /// 경도 고정, 북쪽으로 `step`m 간격 직선 경로 — 0, step, 2·step, …, length(m)
    /// 경계(300m)에 좌표가 정확히 걸리지 않도록 step을 고른다 (CLLocation 거리 오차 ±0.5% 대비)
    private fun straight(length: Double, step: Double): List<GeoPoint> {
        val count = (length / step).swiftRoundedInt()
        return (0..count).map { i -> GeoPoint(lat = 37.5 + i * step / metersPerDegree, lon = 127.0) }
    }

    /// iOS는 `CLLocation.distance(from:)` — Android는 엔진과 같은 반지름 6,371km 하버사인
    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val toRad = PI / 180
        val dLat = (b.lat - a.lat) * toRad
        val dLon = (b.lon - a.lon) * toRad
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * toRad) * cos(b.lat * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(min(1.0, sqrt(h)))
    }

    @Test
    @DisplayName("직선 1km 경로 — 양끝 300m가 잘리고 320~680m 구간 10개 좌표만 남는다")
    fun trimsBothEndsOfOneKilometer() {
        // 40m 간격 26개(0~1000m) → 앞에서 300m 이상·뒤에서 300m 이상인 320, 360, …, 680m = 10개
        val route = straight(length = 1_000.0, step = 40.0)
        val trimmed = RoutePrivacy.trimmed(route)

        assertEquals(10, trimmed.size)
        val first = assertNotNull(trimmed.firstOrNull())
        val last = assertNotNull(trimmed.lastOrNull())
        assertEquals(route[8].lat, first.lat)   // 8 × 40 = 320m
        assertEquals(route[17].lat, last.lat)   // 17 × 40 = 680m
        assertTrue(distance(route[0], first) >= 300)
        assertTrue(distance(last, route[route.size - 1]) >= 300)
    }

    @Test
    @DisplayName("500m 코스 — 양끝 300m를 자르면 남는 좌표가 없어 빈 배열")
    fun shortRouteBecomesEmpty() {
        // 50m 간격 0~500m → 앞 300m 이상이면서 뒤 300m 이상인 지점이 없다
        assertTrue(RoutePrivacy.trimmed(straight(length = 500.0, step = 50.0)).isEmpty())
    }

    @Test
    @DisplayName("잘라낸 뒤 1개만 남으면 선을 그을 수 없어 빈 배열")
    fun singleRemainingPointBecomesEmpty() {
        // 40m 간격 0~640m → 300m 이상·340m 이하인 320m 지점 하나뿐
        assertTrue(RoutePrivacy.trimmed(straight(length = 640.0, step = 40.0)).isEmpty())
    }

    @Test
    @DisplayName("좌표가 2개 미만이면 빈 배열")
    fun tooFewPointsBecomesEmpty() {
        assertTrue(RoutePrivacy.trimmed(emptyList()).isEmpty())
        assertTrue(RoutePrivacy.trimmed(listOf(GeoPoint(lat = 37.5, lon = 127.0))).isEmpty())
    }

    @Test
    @DisplayName("트림은 원본 경로를 바꾸지 않는다")
    fun originalRouteIsUntouched() {
        val route = straight(length = 1_000.0, step = 40.0)
        val snapshot = route.map { it.lat }
        RoutePrivacy.trimmed(route)
        assertEquals(26, route.size)
        assertEquals(snapshot, route.map { it.lat })
    }

    // MARK: - 가림 반경 선택 (이슈 #191)

    @Test
    @DisplayName("반경 500m — 직선 1.5km 경로의 양끝 500m가 잘리고 첫·끝 좌표가 500~1000m 구간 안")
    fun trimsFiveHundredMeters() {
        // 30m 간격 0~1500m(51개) → 앞 500m 이상·뒤 500m 이상인 510, 540, …, 990m = 17개
        // (경계 500·1000m에 좌표가 걸리지 않게 30m 간격 — CLLocation 거리 오차 대비)
        val route = straight(length = 1_500.0, step = 30.0)
        val trimmed = RoutePrivacy.trimmed(route, meters = RoutePrivacy.Radius.m500.meters)

        assertEquals(17, trimmed.size)
        val first = assertNotNull(trimmed.firstOrNull())
        val last = assertNotNull(trimmed.lastOrNull())
        val startToFirst = distance(route[0], first)
        val startToLast = distance(route[0], last)
        assertTrue(startToFirst >= 500 && startToFirst <= 1_000)
        assertTrue(startToLast >= 500 && startToLast <= 1_000)
        assertTrue(distance(last, route[route.size - 1]) >= 500)
    }

    @Test
    @DisplayName("반경 1km — 1.5km 경로는 양끝을 자르면 남는 게 없어 빈 배열")
    fun oneKilometerRadiusEmptiesShortRoute() {
        // 앞 1000m 이상이면서 뒤 1000m 이상인 지점이 없다 (총 1.5km)
        val route = straight(length = 1_500.0, step = 30.0)
        assertTrue(RoutePrivacy.trimmed(route, meters = RoutePrivacy.Radius.m1000.meters).isEmpty())
    }

    @Test
    @DisplayName("저장값 복원 — 선택지에 있으면 그 반경, 없거나 잘못된 값이면 기본 300m")
    fun radiusFromRawValue() {
        assertEquals(RoutePrivacy.Radius.m500, RoutePrivacy.radius(rawValue = 500))
        assertEquals(RoutePrivacy.Radius.m1000, RoutePrivacy.radius(rawValue = 1_000))
        assertEquals(RoutePrivacy.Radius.m300, RoutePrivacy.radius(rawValue = 0))     // 저장값 없음
        assertEquals(RoutePrivacy.Radius.m300, RoutePrivacy.radius(rawValue = 700))   // 선택지에 없는 값
        assertEquals(RoutePrivacy.Radius.m300, RoutePrivacy.defaultRadius)
    }

    @Test
    @DisplayName("반경 라벨 — 300m·500m·1km")
    fun radiusLabels() {
        assertEquals(listOf("300m", "500m", "1km"), RoutePrivacy.Radius.entries.map { it.label })
        assertEquals(1_000.0, RoutePrivacy.Radius.m1000.meters)
    }

    // MARK: - 시작 시각 흐리기 (이슈 #191)

    /// KST 고정 달력 — 실행 기기 시간대와 무관하게 결정론적으로
    private val kst = KST

    private fun kstDate(iso: String) = iso(iso)

    @Test
    @DisplayName("시간대 경계 — 아침 5~11시, 낮 11~17시, 저녁 17~21시, 밤 21~5시")
    fun timeOfDayBoundaries() {
        assertEquals("아침", RoutePrivacy.timeOfDay(kstDate("2026-09-30T05:00:00+09:00"), zone = kst))
        assertEquals("아침", RoutePrivacy.timeOfDay(kstDate("2026-09-30T10:59:00+09:00"), zone = kst))
        assertEquals("낮", RoutePrivacy.timeOfDay(kstDate("2026-09-30T11:00:00+09:00"), zone = kst))
        assertEquals("저녁", RoutePrivacy.timeOfDay(kstDate("2026-09-30T17:00:00+09:00"), zone = kst))
        assertEquals("밤", RoutePrivacy.timeOfDay(kstDate("2026-09-30T21:00:00+09:00"), zone = kst))
        assertEquals("밤", RoutePrivacy.timeOfDay(kstDate("2026-09-30T04:59:00+09:00"), zone = kst))
    }

    @Test
    @DisplayName("카드 날짜 줄 — 분 단위 시각 대신 날짜·요일·시간대")
    fun cardDateLineHidesMinutes() {
        // 2026-09-30은 수요일, 06:10 → 아침
        val date = kstDate("2026-09-30T06:10:00+09:00")
        assertEquals("2026.09.30 (수) 아침", RoutePrivacy.cardDateLine(date, zone = kst))
    }

    @Test
    @DisplayName("카드 날짜 줄 — 시각 표시를 켜면 시작~종료 시각")
    fun cardDateLineShowsTime() {
        // 06:12 출발 · 53분 뒤 07:05 종료
        val start = kstDate("2026-09-30T06:12:00+09:00")
        val end = kstDate("2026-09-30T07:05:00+09:00")
        assertEquals("2026.09.30 (수) 06:12~07:05", RoutePrivacy.cardDateLine(start, end, zone = kst))
        // 끄면(end 없음) 예전처럼 시간대만
        assertEquals("2026.09.30 (수) 아침", RoutePrivacy.cardDateLine(start, zone = kst))
    }

    @Test
    @DisplayName("카드 날짜 줄 — 자정을 넘긴 러닝은 시작일만 적고 시각은 24시간제로")
    fun cardDateLineAcrossMidnight() {
        val start = kstDate("2026-09-30T23:40:00+09:00")
        val end = kstDate("2026-10-01T00:25:00+09:00")
        assertEquals("2026.09.30 (수) 23:40~00:25", RoutePrivacy.cardDateLine(start, end, zone = kst))
    }
}
