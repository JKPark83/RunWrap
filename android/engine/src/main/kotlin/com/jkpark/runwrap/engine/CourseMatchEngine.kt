package com.jkpark.runwrap.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlinx.serialization.Serializable

/// 같은 코스 판정 (이슈 #223, 리서치 문서 §06) — Strava Matched Activities 방식(시작·끝·방향·거리).
/// "반포 5km를 지난달보다 얼마나 빨리 뛰었나"를 보여 주는 재료. 경로 전체 대신 점 몇 개(지문)만 비교한다.
/// 순수 로직 — 거리는 등장방형 근사(`GPXParser.roughMeters`)로 잰다. 수 km 코스에서 오차는 무시할 만하다.
///
/// 허용 오차는 엄격하게 시작한다(Strava는 비공개): 시작·끝 반경 150m, 거리 ±5%, 중간점 3개 각 200m 이내.
/// **오탐(다른 코스를 같은 코스로 묶음)은 "없느니만 못한 인사이트"라 0건이 목표** — 실기록으로 재 보고 조정한다.
object CourseMatchEngine {
    @Serializable
    data class Fingerprint(
        val start: GeoPoint,
        val end: GeoPoint,
        val distanceM: Double,
        /// 시작→끝 방위(도, 북 0·동 90) — 루프 코스에서는 무의미해 비교하지 않는다
        val bearingDeg: Double,
        /// 경로 길이 25·50·75% 지점 — 왕복 코스·역방향 구분용
        val waypoints: List<GeoPoint>,
    )

    /// iOS `standing` 반환 튜플 `(ordinal:rank:)`
    data class Standing(val ordinal: Int, val rank: Int?)

    const val endpointToleranceM = 150.0
    const val distanceTolerance = 0.05
    const val waypointToleranceM = 200.0
    /// 시작·끝이 이보다 가까우면 루프(순환·왕복) — 시작·끝 허용 반경 두 배
    const val loopThresholdM = 300.0
    /// 루프가 아닐 때 방위 허용 차
    const val bearingToleranceDeg = 30.0
    /// 같은 코스 기록이 이보다 적으면 비교하지 않는다 — 추세를 말할 표본이 아니다
    const val minimumMatches = 3

    /// 경로 원본 → 지문. 점이 2개 미만이거나 경로 길이·거리가 0이면 null
    fun fingerprint(route: List<TrackPoint>, distanceM: Double): Fingerprint? {
        if (route.size < 2 || !(distanceM > 0)) return null
        val points = route.map { GeoPoint(it.lat, it.lon) }
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + GPXParser.roughMeters(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (!(total > 0)) return null
        // 누적 거리가 비율을 처음 넘는 점 — 1초 간격 기록이라 보간하지 않는다
        val waypoints = listOf(0.25, 0.5, 0.75).map { fraction ->
            val index = cumulative.indexOfFirst { it >= total * fraction }
            points[if (index >= 0) index else points.size - 1]
        }
        return Fingerprint(start = points.first(), end = points.last(), distanceM = distanceM,
                           bearingDeg = bearing(points.first(), points.last()),
                           waypoints = waypoints)
    }

    fun isSameCourse(a: Fingerprint, b: Fingerprint): Boolean {
        // 거리: 짧은 쪽 대비 ±5% — 기준을 짧은 쪽으로 둬 a·b 순서와 무관하다
        if (!(abs(a.distanceM - b.distanceM) <= min(a.distanceM, b.distanceM) * distanceTolerance)) return false
        if (!(GPXParser.roughMeters(a.start, b.start) <= endpointToleranceM)) return false
        if (!(GPXParser.roughMeters(a.end, b.end) <= endpointToleranceM)) return false
        if (a.waypoints.size != b.waypoints.size) return false
        if (!a.waypoints.zip(b.waypoints).all { (p, q) -> GPXParser.roughMeters(p, q) <= waypointToleranceM }) {
            return false
        }
        // 루프 코스(시작≈끝)는 방위가 무의미 — 방향은 위 중간점이 가른다
        val isLoop = GPXParser.roughMeters(a.start, a.end) < loopThresholdM
        if (isLoop) return true
        val diff = abs(a.bearingDeg - b.bearingDeg) % 360
        return min(diff, 360 - diff) <= bearingToleranceDeg
    }

    /// 대상과 같은 코스인 기록(시작 시각 오름차순). history에 대상 세션 자신도 넣는다 —
    /// 같은 코스 기록이 3회 미만이면 null (추세를 말할 표본이 아니다)
    fun matches(target: Fingerprint, history: List<Pair<RunSummary, Fingerprint>>): List<RunSummary>? {
        val runs = history.filter { isSameCourse(target, it.second) }.map { it.first }.sortedBy { it.start }
        return if (runs.size >= minimumMatches) runs else null
    }

    /// 이 세션이 몇 번째 완주인지(1부터, 시작 시각 순)와 페이스 순위(1 = 최고 기록).
    /// 페이스가 없는 세션(RunSummary.paceSecPerKm null)은 순위를 매기지 않는다
    fun standing(run: RunSummary, matches: List<RunSummary>): Standing? {
        val index = matches.indexOfFirst { it.id == run.id }
        if (index < 0) return null
        val rank = run.paceSecPerKm?.let { pace ->
            matches.mapNotNull { it.paceSecPerKm }.count { it < pace } + 1
        }
        return Standing(ordinal = index + 1, rank = rank)
    }

    /// 시작→끝 방위(도) — 등장방형 근사, 0..<360
    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val east = (b.lon - a.lon) * cos((a.lat + b.lat) / 2 * PI / 180)
        val north = b.lat - a.lat
        val deg = atan2(east, north) * 180 / PI
        return if (deg < 0) deg + 360 else deg
    }
}
