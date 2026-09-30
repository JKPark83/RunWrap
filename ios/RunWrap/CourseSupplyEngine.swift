import Foundation

/// 번들 CoursePOI.json의 한 항목 — tools/course-poi 파이프라인 산출 포맷과 1:1.
/// 필드명이 1글자(k/n/la/lo)인 것은 55,000건 JSON 용량 절약 때문 (계획서 M12-1).
struct CoursePOI: Decodable, Equatable, Sendable {
    /// Hashable은 화면의 종류 필터(Set<Kind>)가 요구한다 — RawRepresentable로 자동 합성되지만
    /// 의존을 명시해 둔다 (CourseScreen.kindFilter)
    enum Kind: String, Decodable, Hashable, CaseIterable, Sendable {
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
/// 끊긴 구간(세그먼트 사이·1km 넘는 점프)은 거리에도 매칭에도 넣지 않는다 (#150).
///
/// 미노출 가드: 포인트 2개 미만 또는 유효 총거리 500m 미만이면 nil —
/// "틀린 인사이트는 없느니만 못하다."
enum CourseSupplyEngine {
    /// Sendable — 코스 화면이 분석을 백그라운드 태스크에서 돌려 결과를 넘긴다 (#147)
    struct Match: Equatable, Sendable {
        let poi: CoursePOI
        let courseKm: Double      // 코스 시작점부터 누적 km
        let detourMeters: Double  // 코스에서 수직으로 벗어난 거리
    }

    struct Result: Equatable, Sendable {
        let totalKm: Double
        let matches: [Match]      // courseKm 오름차순
    }

    /// 지구 반지름 6,371km 기준 위도 1도의 미터 (R·π/180) — 경도는 코스 중앙 위도의 cos 배
    private static let metersPerDegree = 111_195.0
    private static let minPoints = 2
    private static let minCourseMeters = 500.0
    /// 직전에 남긴 점에서 이보다 가까운 점은 솎아낸다 (#147) — 줄어든 폴리라인은 원래 점에서
    /// 최대 5m 벗어나므로 매칭 오차 ≤ 5m로 반경 150m 대비 무시할 만하고,
    /// 신호 대기·저속 구간에 1초마다 쌓이는 겹친 점이 사라져 검사할 선분이 준다
    private static let thinMeters = 5.0

    /// 매칭 대상 선분 — 투영 좌표(m)와 코스 시작점부터의 누적 거리(m)
    private struct Leg {
        let ax, ay, bx, by: Double
        let startMeters, endMeters: Double
    }

    /// 격자 인덱스의 셀 좌표
    private struct Cell: Hashable {
        let x, y: Int
    }

    static func analyze(course: [GeoPoint], pois: [CoursePOI],
                        radiusMeters: Double = 150) -> Result? {
        analyze(segments: [course], pois: pois, radiusMeters: radiusMeters)
    }

    /// 끊긴 구간을 세그먼트로 나눠 받는다 — 누적 km는 세그먼트를 넘어 이어지되
    /// 세그먼트 사이 점프 길이는 더하지 않고 매칭 대상에서도 뺀다 (#150)
    static func analyze(segments: [[GeoPoint]], pois: [CoursePOI],
                        radiusMeters: Double = 150) -> Result? {
        let course = segments.flatMap { $0 }
        guard course.count >= minPoints else { return nil }
        // 비유한·범위 밖 좌표는 거리·bbox 계산을 NaN으로 오염시키고, 격자 셀 Int 변환에서 트랩한다 —
        // 파서가 걸러도 엔진이 직접 막는다 (감사 M12, #147)
        guard course.allSatisfy({ (-90...90).contains($0.lat) && (-180...180).contains($0.lon) }) else { return nil }

        // 국지 평면 투영 — 기준점은 첫 좌표, 경도 축척은 위도 범위 중앙의 cos
        let lats = course.map(\.lat)
        let lons = course.map(\.lon)
        let refLat = (lats.min()! + lats.max()!) / 2
        let lonScale = metersPerDegree * cos(refLat * .pi / 180)
        // 위도가 범위 밖이거나 비유한이면 축척이 0 이하·NaN — 아래 lonPad가 음수가 되어
        // 하한 > 상한인 ClosedRange를 만들며 트랩한다. 파서가 걸러도 엔진이 직접 막는다 (감사 M12)
        guard lonScale > 0 else { return nil }
        let origin = course[0]
        func project(_ p: GeoPoint) -> (x: Double, y: Double) {
            (x: (p.lon - origin.lon) * lonScale, y: (p.lat - origin.lat) * metersPerDegree)
        }

        // 선분·누적 거리 전처리 — 세그먼트 사이 점프는 선분을 만들지 않는다 (기록 트랙의 1km 넘는
        // 간격은 GPXParser가 세그먼트 경계로 끊어 넘긴다)
        var legs: [Leg] = []
        var totalMeters = 0.0
        for segment in segments {
            guard let first = segment.first else { continue }
            var last = project(first)
            for (i, p) in segment.enumerated().dropFirst() {
                let xy = project(p)
                let d = hypot(xy.x - last.x, xy.y - last.y)
                // 솎아내기 — 세그먼트 마지막 점은 코스 끝이라 남긴다
                if d < thinMeters, i < segment.count - 1 { continue }
                legs.append(Leg(ax: last.x, ay: last.y, bx: xy.x, by: xy.y,
                                startMeters: totalMeters, endMeters: totalMeters + d))
                totalMeters += d
                last = xy
            }
        }
        guard totalMeters.isFinite, totalMeters >= minCourseMeters else { return nil }

        // 코스 bbox + 버퍼로 1차 필터 — 전국 55,000건 중 코스 주변만 정밀 계산 (계획서 M12-2)
        let buffer = max(300, radiusMeters)
        let latPad = buffer / metersPerDegree
        let lonPad = buffer / lonScale
        let latRange = (lats.min()! - latPad)...(lats.max()! + latPad)
        let lonRange = (lons.min()! - lonPad)...(lons.max()! + lonPad)

        // 격자 인덱스 — 선분이 지나는 셀(선분 bbox)마다 등록하고, POI는 자기 셀 ±1만 검사한다 (#147).
        // 셀 한 변(buffer) ≥ 반경이라 반경 안에 드는 선분 위의 점은 POI 셀 ±1 안에 있다 → 완전 탐색과 결과 동일
        func cell(_ v: Double) -> Int { Int((v / buffer).rounded(.down)) }
        var grid: [Cell: [Int]] = [:]
        for (index, leg) in legs.enumerated() {
            for cx in cell(min(leg.ax, leg.bx))...cell(max(leg.ax, leg.bx)) {
                for cy in cell(min(leg.ay, leg.by))...cell(max(leg.ay, leg.by)) {
                    grid[Cell(x: cx, y: cy), default: []].append(index)
                }
            }
        }

        var matches: [Match] = []
        for poi in pois {
            guard latRange.contains(poi.lat), lonRange.contains(poi.lon) else { continue }
            let px = (poi.lon - origin.lon) * lonScale
            let py = (poi.lat - origin.lat) * metersPerDegree
            let (cx, cy) = (cell(px), cell(py))
            var candidates: [Int] = []
            for dx in -1...1 {
                for dy in -1...1 { candidates += grid[Cell(x: cx + dx, y: cy + dy)] ?? [] }
            }
            // 코스 순서대로 봐야 동점일 때 완전 탐색과 같은 선분(앞선 통과)을 고른다
            candidates.sort()

            // 최근접 선분에 수선 — 선분 밖이면 끝점으로 클램프
            var best: (dist: Double, meters: Double)?
            for i in candidates {
                let leg = legs[i]
                let (ax, ay, bx, by) = (leg.ax, leg.ay, leg.bx, leg.by)
                let segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                let t = segLen2 == 0 ? 0
                    : max(0, min(1, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2))
                let dist = hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
                let meters = leg.startMeters + t * (leg.endMeters - leg.startMeters)
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
