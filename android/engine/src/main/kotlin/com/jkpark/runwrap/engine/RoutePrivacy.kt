package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/// 공유 카드 경로 프라이버시 — 러닝은 대개 집에서 출발·도착하므로 SNS에 올리는 스토리 카드에서
/// 경로 양끝을 잘라 거주지가 드러나지 않게 한다 (이슈 #84, Strava 프라이버시 존 방식).
/// 분 단위 출발 시각은 생활 패턴(매일 몇 시에 집을 나서는지)을 드러낼 수 있어 처음에는 카드 날짜 줄을
/// 아침·낮·저녁·밤 시간대로만 흐렸다 (이슈 #191). 지금은 시작~종료 시각을 보여 주는 쪽이 기본이고,
/// 공유 시트의 "시각 표시" 토글로 끄면 예전처럼 시간대로 흐린다 (이슈 #221). 가림 반경은 세 단계에서 고른다 (이슈 #191).
/// 순수 로직이라 UI를 모른다 — 잘라낸 좌표만 돌려주고 그리기는 `RouteSnapshot`이 한다.
object RoutePrivacy {
    /// 양끝을 가릴 반경 — 아파트 단지·좁은 동네는 300m로 부족할 수 있어 세 단계를 둔다 (이슈 #191)
    enum class Radius(val rawValue: Int) {
        m300(300),
        m500(500),
        m1000(1_000);

        val label: String
            get() = when (this) {
                m300 -> "300m"
                m500 -> "500m"
                m1000 -> "1km"
            }

        val meters: Double get() = rawValue.toDouble()
    }

    val defaultRadius: Radius = Radius.m300
    /// 가림 반경 UserDefaults 키 — rawValue(Int)로 저장한다
    const val radiusKey = "share.trimRadius"

    /// 저장된 값이 없거나(0) 선택지에 없는 값이면 기본 반경으로 되돌린다
    fun radius(rawValue: Int): Radius = Radius.entries.firstOrNull { it.rawValue == rawValue } ?: defaultRadius

    /// 시작점에서 누적 거리 `meters`까지, 끝점에서 거슬러 `meters`까지의 좌표를 잘라낸다.
    /// 잘라낸 뒤 2개 미만(짧은 코스)이면 선을 그을 수 없으므로 빈 배열 — 경로 없이 카드를 만든다.
    /// 거리는 직선 반경이 아니라 경로를 따라 쌓은 누적 거리다 (`CLLocation.distance(from:)`).
    /// (Android: CoreLocation이 없어 반지름 6,371km 하버사인으로 잰다 — 차이는 ±0.5% 안이고,
    /// iOS 테스트도 경계에 좌표가 걸리지 않게 간격을 골라 둔다)
    fun trimmed(route: List<GeoPoint>, meters: Double = 300.0): List<GeoPoint> {
        if (route.size < 2) return emptyList()
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(route[i - 1], route[i])
        }
        val total = cumulative[route.size - 1]
        val kept = route.indices
            .filter { cumulative[it] >= meters && total - cumulative[it] >= meters }
            .map { route[it] }
        return if (kept.size >= 2) kept else emptyList()
    }

    /// 두 좌표의 대권 거리(m) — 하버사인, 지구 반지름 6,371km
    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val toRad = PI / 180
        val dLat = (b.lat - a.lat) * toRad
        val dLon = (b.lon - a.lon) * toRad
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * toRad) * cos(b.lat * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(min(1.0, sqrt(h)))
    }

    /// 공유 카드 날짜 줄용 시간대 — 분 단위 출발 시각 대신 쓴다.
    /// 경계(가정): 아침 5~11시, 낮 11~17시, 저녁 17~21시, 밤 21~5시 (이슈 #191)
    fun timeOfDay(date: Instant, zone: ZoneId): String = when (date.atZone(zone).hour) {
        in 5 until 11 -> "아침"
        in 11 until 17 -> "낮"
        in 17 until 21 -> "저녁"
        else -> "밤"
    }

    /// 카드 날짜 줄 — 미니멀·사진 카드가 같이 쓴다 (ko_KR 요일).
    /// `end`를 주면 "2026.09.30 (수) 06:12~07:05", 없으면 "2026.09.30 (수) 아침" (이슈 #221).
    /// 자정을 넘긴 러닝도 날짜는 시작일 하나만 적는다 — "23:40~00:25"
    fun cardDateLine(date: Instant, end: Instant? = null, zone: ZoneId): String {
        // 기기 달력이 불기·일본력이어도 서기 연도로 찍도록 그레고리력을 쓰고 시간대만 따른다
        val d = date.atZone(zone)
        val weekday = "월화수목금토일"[d.dayOfWeek.value - 1]
        val line = d.year.toString().padStart(4, '0') + "." +
            d.monthValue.toString().padStart(2, '0') + "." +
            d.dayOfMonth.toString().padStart(2, '0') + " ($weekday)"
        if (end == null) return line + " " + timeOfDay(date, zone)
        fun hm(t: Instant) = t.atZone(zone).let {
            it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
        }
        return line + " " + hm(date) + "~" + hm(end)
    }
}
