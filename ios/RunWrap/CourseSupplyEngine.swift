import Foundation

/// 번들 CoursePOI.json의 한 항목 — tools/course-poi 파이프라인 산출 포맷과 1:1.
/// 필드명이 1글자(k/n/la/lo)인 것은 55,000건 JSON 용량 절약 때문 (계획서 M12-1).
struct CoursePOI: Decodable, Equatable {
    /// Hashable은 화면의 종류 필터(Set<Kind>)가 요구한다 — RawRepresentable로 자동 합성되지만
    /// 의존을 명시해 둔다 (CourseScreen.kindFilter)
    enum Kind: String, Decodable, Hashable, CaseIterable {
        case convenience = "c"  // 편의점
        case toilet = "t"       // 화장실
        case water = "w"        // 음수대
    }

    let kind: Kind
    let name: String
    let lat: Double
    let lon: Double

    private enum CodingKeys: String, CodingKey {
        case kind = "k", name = "n", lat = "la", lon = "lo"
    }
}

/// CoursePOI.json 전체 — generatedAt은 화면 하단 "데이터 기준일" 표기에 쓴다 (기획서 §4.13).
struct CoursePOIFile: Decodable {
    let generatedAt: String
    let pois: [CoursePOI]
}

/// 코스 보급 매칭 엔진 — GPX 코스 폴리라인 주변의 급수·화장실·편의점을
/// "코스 몇 km 지점, 몇 m 이탈"로 계산한다 (기획서 §4.13, 계획서 M12-2).
///
/// 산식: 코스 좌표를 국지 등장방형 평면(m)으로 투영한 뒤(러닝 거리 수십 km에서
/// 하버사인과의 오차 0.1% 미만), 각 POI에서 최근접 세그먼트로 수선을 내려
/// 이탈 거리와 누적 지점을 얻는다. 반경 안 후보만 채택하고 km 순으로 정렬.
/// 코스가 같은 곳을 두 번 지나면(왕복 등) 가장 가까운 통과 지점 한 번만 잡는다.
///
/// 미노출 가드: 포인트 2개 미만 또는 총거리 500m 미만이면 nil —
/// "틀린 인사이트는 없느니만 못하다."
enum CourseSupplyEngine {
    struct Match: Equatable {
        let poi: CoursePOI
        let courseKm: Double      // 코스 시작점부터 누적 km
        let detourMeters: Double  // 코스에서 수직으로 벗어난 거리
    }

    struct Result: Equatable {
        let totalKm: Double
        let matches: [Match]      // courseKm 오름차순
    }

    /// 지구 반지름 6,371km 기준 위도 1도의 미터 (R·π/180) — 경도는 코스 중앙 위도의 cos 배
    private static let metersPerDegree = 111_195.0
    private static let minPoints = 2
    private static let minCourseMeters = 500.0

    static func analyze(course: [GeoPoint], pois: [CoursePOI],
                        radiusMeters: Double = 150) -> Result? {
        guard course.count >= minPoints else { return nil }

        // 국지 평면 투영 — 기준점은 첫 좌표, 경도 축척은 위도 범위 중앙의 cos
        let lats = course.map(\.lat)
        let lons = course.map(\.lon)
        let refLat = (lats.min()! + lats.max()!) / 2
        let lonScale = metersPerDegree * cos(refLat * .pi / 180)
        let origin = course[0]
        let xy = course.map { p in
            (x: (p.lon - origin.lon) * lonScale, y: (p.lat - origin.lat) * metersPerDegree)
        }

        // 세그먼트 누적 거리 전처리
        var cumulative = [0.0]
        for i in 1..<xy.count {
            let d = hypot(xy[i].x - xy[i - 1].x, xy[i].y - xy[i - 1].y)
            cumulative.append(cumulative[i - 1] + d)
        }
        let totalMeters = cumulative.last!
        guard totalMeters >= minCourseMeters else { return nil }

        // 코스 bbox + 버퍼로 1차 필터 — 전국 55,000건 중 코스 주변만 정밀 계산 (계획서 M12-2)
        let buffer = max(300, radiusMeters)
        let latPad = buffer / metersPerDegree
        let lonPad = buffer / lonScale
        let latRange = (lats.min()! - latPad)...(lats.max()! + latPad)
        let lonRange = (lons.min()! - lonPad)...(lons.max()! + lonPad)

        var matches: [Match] = []
        for poi in pois {
            guard latRange.contains(poi.lat), lonRange.contains(poi.lon) else { continue }
            let px = (poi.lon - origin.lon) * lonScale
            let py = (poi.lat - origin.lat) * metersPerDegree

            // 최근접 세그먼트에 수선 — 세그먼트 밖이면 끝점으로 클램프
            var best: (dist: Double, meters: Double)?
            for i in 1..<xy.count {
                let (ax, ay) = xy[i - 1]
                let (bx, by) = xy[i]
                let segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                let t = segLen2 == 0 ? 0
                    : max(0, min(1, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2))
                let dist = hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
                let meters = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1])
                if best == nil || dist < best!.dist { best = (dist, meters) }
            }
            if let best, best.dist <= radiusMeters {
                matches.append(Match(poi: poi,
                                     courseKm: best.meters / 1_000,
                                     detourMeters: best.dist))
            }
        }

        return Result(totalKm: totalMeters / 1_000,
                      matches: matches.sorted { $0.courseKm < $1.courseKm })
    }
}
