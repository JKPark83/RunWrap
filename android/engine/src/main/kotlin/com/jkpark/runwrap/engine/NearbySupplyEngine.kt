package com.jkpark.runwrap.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/// 현재 위치 주변 보급 검색 엔진 — 코스 없이 "지금 내 근처"를 답한다.
///
/// **왜 CourseSupplyEngine과 나누는가.** 그쪽은 코스 폴리라인에 수선을 내려
/// "몇 km 지점, 몇 m 이탈"을 계산한다. 여기는 기준이 점 하나라 진행 방향도 누적 거리도
/// 없고, 답도 "직선 몇 m"뿐이다. 같은 함수에 억지로 합치면 두 의미의 거리가 한 필드에
/// 섞여 화면이 무엇을 그리는지 알 수 없게 된다.
///
/// 산식: 국지 등장방형 평면(m) 투영 후 직선거리. 반경 안 후보만 채택하고 가까운 순 정렬.
/// 도보 경로가 아니라 직선거리다 — 화면 문구도 "직선"임을 밝힌다.
object NearbySupplyEngine {
    data class Match(
        val poi: CoursePOI,
        /// 현재 위치에서 직선 거리(m)
        val meters: Double,
    )

    data class Result(
        val center: GeoPoint,
        val radiusMeters: Double,
        /// meters 오름차순
        val matches: List<Match>,
    )

    /// 지구 반지름 6,371km 기준 위도 1도의 미터 — CourseSupplyEngine과 같은 상수를 쓴다
    private const val metersPerDegree = 111_195.0

    /// 종류별 상한 — 편의점처럼 밀집한 종류가 목록을 독식하지 않게 한다.
    /// 반경 안에 30개가 있어도 사용자가 실제로 갈 곳은 가까운 몇 개뿐이다
    private const val maxPerKind = 10

    /// 현재 위치 반경 안의 보급 지점을 가까운 순으로 찾는다.
    ///
    /// 미노출 가드: 반경이 0 이하면 검색 자체가 성립하지 않는다 → nil.
    /// 반경 안에 아무것도 없는 경우는 nil이 아니라 **빈 matches**를 돌려준다 —
    /// "찾아봤지만 없다"와 "찾을 수 없다"는 화면에서 다른 문구여야 한다.
    fun search(center: GeoPoint, pois: List<CoursePOI>, radiusMeters: Double = 1_000.0): Result? {
        if (!(radiusMeters > 0)) return null

        val lonScale = metersPerDegree * cos(center.lat * PI / 180)
        // bbox 1차 필터 — 전국 55,000건 중 주변만 정밀 계산 (CourseSupplyEngine과 같은 전략)
        val latPad = radiusMeters / metersPerDegree
        val lonPad = radiusMeters / lonScale

        val matches = mutableListOf<Match>()
        for (poi in pois) {
            if (!(abs(poi.lat - center.lat) <= latPad && abs(poi.lon - center.lon) <= lonPad)) continue
            val dx = (poi.lon - center.lon) * lonScale
            val dy = (poi.lat - center.lat) * metersPerDegree
            val distance = hypot(dx, dy)
            if (!(distance <= radiusMeters)) continue
            matches.add(Match(poi, distance))
        }

        matches.sortBy { it.meters }

        // 종류별로 가까운 순 상한을 적용한 뒤 다시 거리순으로 합친다
        val countByKind = HashMap<CoursePOI.Kind, Int>()
        val capped = mutableListOf<Match>()
        for (match in matches) {
            val count = countByKind[match.poi.kind] ?: 0
            if (count >= maxPerKind) continue
            countByKind[match.poi.kind] = count + 1
            capped.add(match)
        }

        return Result(center, radiusMeters, capped)
    }
}
