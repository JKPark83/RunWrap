package com.jkpark.runwrap.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/// 번들 CoursePOI.json의 한 항목 — tools/course-poi 파이프라인 산출 포맷과 1:1.
/// 필드명이 1글자(k/n/la/lo)인 것은 55,000건 JSON 용량 절약 때문 (계획서 M12-1).
@Serializable
data class CoursePOI(
    @SerialName("k") val kind: Kind,
    @SerialName("n") val name: String,
    @SerialName("la") val lat: Double,
    @SerialName("lo") val lon: Double,
) {
    /// Hashable은 화면의 종류 필터(Set<Kind>)가 요구한다 — RawRepresentable로 자동 합성되지만
    /// 의존을 명시해 둔다 (CourseScreen.kindFilter)
    @Serializable
    enum class Kind(val rawValue: String) {
        @SerialName("c") convenience("c"),  // 편의점
        @SerialName("t") toilet("t"),       // 화장실
        @SerialName("w") water("w"),        // 음수대
    }
}

/// CoursePOI.json 전체 — generatedAt은 화면 하단 "데이터 기준일" 표기에 쓴다 (기획서 §4.13).
@Serializable
data class CoursePOIFile(
    val generatedAt: String,
    val pois: List<CoursePOI>,
)

/// 코스 보급 매칭 엔진 — GPX 코스 폴리라인 주변의 급수·화장실·편의점을
/// "코스 몇 km 지점, 몇 m 이탈"로 계산한다 (기획서 §4.13, 계획서 M12-2).
///
/// 산식: 코스 좌표를 국지 등장방형 평면(m)으로 투영한 뒤(러닝 거리 수십 km에서
/// 하버사인과의 오차 0.1% 미만), 각 POI에서 최근접 세그먼트로 수선을 내려
/// 이탈 거리와 누적 지점을 얻는다. 반경 안 후보만 채택하고 km 순으로 정렬.
/// 코스가 같은 곳을 두 번 지나면(왕복 등) 가장 가까운 통과 지점 한 번만 잡는다.
/// 끊긴 구간(세그먼트 사이·1km 넘는 점프)은 거리에도 매칭에도 넣지 않는다 (#150).
///
/// 미노출 가드: 포인트 2개 미만 또는 유효 총거리 500m 미만이면 nil —
/// "틀린 인사이트는 없느니만 못하다."
object CourseSupplyEngine {
    /// Sendable — 코스 화면이 분석을 백그라운드 태스크에서 돌려 결과를 넘긴다 (#147)
    data class Match(
        val poi: CoursePOI,
        val courseKm: Double,      // 코스 시작점부터 누적 km
        val detourMeters: Double,  // 코스에서 수직으로 벗어난 거리
    )

    data class Result(
        val totalKm: Double,
        val matches: List<Match>,  // courseKm 오름차순
    )

    /// 지구 반지름 6,371km 기준 위도 1도의 미터 (R·π/180) — 경도는 코스 중앙 위도의 cos 배
    private const val metersPerDegree = 111_195.0
    private const val minPoints = 2
    private const val minCourseMeters = 500.0
    /// 직전에 남긴 점에서 이보다 가까운 점은 솎아낸다 (#147) — 줄어든 폴리라인은 원래 점에서
    /// 최대 5m 벗어나므로 매칭 오차 ≤ 5m로 반경 150m 대비 무시할 만하고,
    /// 신호 대기·저속 구간에 1초마다 쌓이는 겹친 점이 사라져 검사할 선분이 준다
    private const val thinMeters = 5.0

    /// 매칭 대상 선분 — 투영 좌표(m)와 코스 시작점부터의 누적 거리(m)
    private class Leg(
        val ax: Double, val ay: Double, val bx: Double, val by: Double,
        val startMeters: Double, val endMeters: Double,
    )

    /// 격자 인덱스의 셀 좌표
    private data class Cell(val x: Int, val y: Int)

    fun analyze(course: List<GeoPoint>, pois: List<CoursePOI>, radiusMeters: Double = 150.0): Result? =
        analyze(segments = listOf(course), pois = pois, radiusMeters = radiusMeters)

    /// 끊긴 구간을 세그먼트로 나눠 받는다 — 누적 km는 세그먼트를 넘어 이어지되
    /// 세그먼트 사이 점프 길이는 더하지 않고 매칭 대상에서도 뺀다 (#150)
    @JvmName("analyzeSegments")  // List<GeoPoint> 오버로드와 JVM 시그니처가 겹친다
    fun analyze(segments: List<List<GeoPoint>>, pois: List<CoursePOI>, radiusMeters: Double = 150.0): Result? {
        val course = segments.flatten()
        if (course.size < minPoints) return null
        // 비유한·범위 밖 좌표는 거리·bbox 계산을 NaN으로 오염시키고, 격자 셀 Int 변환에서 트랩한다 —
        // 파서가 걸러도 엔진이 직접 막는다 (감사 M12, #147)
        if (!course.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }) return null

        // 국지 평면 투영 — 기준점은 첫 좌표, 경도 축척은 위도 범위 중앙의 cos
        val lats = course.map { it.lat }
        val lons = course.map { it.lon }
        val refLat = (lats.min() + lats.max()) / 2
        val lonScale = metersPerDegree * cos(refLat * PI / 180)
        // 위도가 범위 밖이거나 비유한이면 축척이 0 이하·NaN — 아래 lonPad가 음수가 되어
        // 하한 > 상한인 ClosedRange를 만들며 트랩한다. 파서가 걸러도 엔진이 직접 막는다 (감사 M12)
        if (!(lonScale > 0)) return null
        val origin = course[0]
        fun projectX(p: GeoPoint) = (p.lon - origin.lon) * lonScale
        fun projectY(p: GeoPoint) = (p.lat - origin.lat) * metersPerDegree

        // 선분·누적 거리 전처리 — 세그먼트 사이 점프는 선분을 만들지 않는다 (기록 트랙의 1km 넘는
        // 간격은 GPXParser가 세그먼트 경계로 끊어 넘긴다)
        val legs = mutableListOf<Leg>()
        var totalMeters = 0.0
        for (segment in segments) {
            val first = segment.firstOrNull() ?: continue
            var lastX = projectX(first)
            var lastY = projectY(first)
            for (i in 1 until segment.size) {
                val x = projectX(segment[i])
                val y = projectY(segment[i])
                val d = hypot(x - lastX, y - lastY)
                // 솎아내기 — 세그먼트 마지막 점은 코스 끝이라 남긴다
                if (d < thinMeters && i < segment.size - 1) continue
                legs.add(Leg(lastX, lastY, x, y, totalMeters, totalMeters + d))
                totalMeters += d
                lastX = x
                lastY = y
            }
        }
        if (!(totalMeters.isFinite() && totalMeters >= minCourseMeters)) return null

        // 코스 bbox + 버퍼로 1차 필터 — 전국 55,000건 중 코스 주변만 정밀 계산 (계획서 M12-2)
        val buffer = max(300.0, radiusMeters)
        val latPad = buffer / metersPerDegree
        val lonPad = buffer / lonScale
        val latRange = (lats.min() - latPad)..(lats.max() + latPad)
        val lonRange = (lons.min() - lonPad)..(lons.max() + lonPad)

        // 격자 인덱스 — 선분이 지나는 셀(선분 bbox)마다 등록하고, POI는 자기 셀 ±1만 검사한다 (#147).
        // 셀 한 변(buffer) ≥ 반경이라 반경 안에 드는 선분 위의 점은 POI 셀 ±1 안에 있다 → 완전 탐색과 결과 동일
        fun cell(v: Double): Int = floor(v / buffer).toInt()
        val grid = HashMap<Cell, MutableList<Int>>()
        for ((index, leg) in legs.withIndex()) {
            for (cx in cell(min(leg.ax, leg.bx))..cell(max(leg.ax, leg.bx))) {
                for (cy in cell(min(leg.ay, leg.by))..cell(max(leg.ay, leg.by))) {
                    grid.getOrPut(Cell(cx, cy)) { mutableListOf() }.add(index)
                }
            }
        }

        val matches = mutableListOf<Match>()
        for (poi in pois) {
            if (poi.lat !in latRange || poi.lon !in lonRange) continue
            val px = (poi.lon - origin.lon) * lonScale
            val py = (poi.lat - origin.lat) * metersPerDegree
            val cx = cell(px)
            val cy = cell(py)
            val candidates = mutableListOf<Int>()
            for (dx in -1..1) {
                for (dy in -1..1) grid[Cell(cx + dx, cy + dy)]?.let { candidates.addAll(it) }
            }
            // 코스 순서대로 봐야 동점일 때 완전 탐색과 같은 선분(앞선 통과)을 고른다
            candidates.sort()

            // 최근접 선분에 수선 — 선분 밖이면 끝점으로 클램프
            var bestDist: Double? = null
            var bestMeters = 0.0
            for (i in candidates) {
                val leg = legs[i]
                val ax = leg.ax; val ay = leg.ay; val bx = leg.bx; val by = leg.by
                val segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                val t = if (segLen2 == 0.0) 0.0
                    else max(0.0, min(1.0, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2))
                val dist = hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
                val meters = leg.startMeters + t * (leg.endMeters - leg.startMeters)
                if (bestDist == null || dist < bestDist) {
                    bestDist = dist
                    bestMeters = meters
                }
            }
            if (bestDist != null && bestDist <= radiusMeters) {
                matches.add(Match(poi, courseKm = bestMeters / 1_000, detourMeters = bestDist))
            }
        }

        return Result(totalKm = totalMeters / 1_000, matches = matches.sortedBy { it.courseKm })
    }
}
