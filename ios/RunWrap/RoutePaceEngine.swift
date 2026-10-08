import Foundation

/// 경로 위 페이스 색 구간 · km 마커 · 고도 프로필 (이슈 #222 §2).
/// 순간 페이스는 GPS 노이즈가 커서 그대로 칠하면 얼룩이 된다 — 앞뒤 100m(합 200m) 이동 창으로
/// 평활한 뒤 세션 중앙값 대비 비율로 `RRTone` 4단계에 매핑한다. 수평 정확도가 나쁜 구간은
/// 판정하지 않고 tone nil(화면은 회색)로 둔다 — "틀린 인사이트는 없느니만 못하다".
/// 거리는 `GPXParser.roughMeters`(등장방형 근사)로 잰다 — 표시용이라 정밀도는 중요치 않다.
enum RoutePaceEngine {
    struct Segment: Equatable {
        /// 이웃 구간과 경계 점을 공유한다 — 폴리라인이 끊기지 않게
        let points: [TrackPoint]
        /// nil = 정확도 부족·판정 불가(회색)
        let tone: RRTone?
    }

    struct ProfilePoint: Equatable {
        let distanceKm: Double
        let elevationM: Double
    }

    /// 평활 창 반폭(m) — 점 앞뒤로 이만큼, 합 200m (이슈 #222: 200~250m 이동 창)
    static let halfWindowMeters = 100.0
    /// 이보다 수평 정확도가 나쁜(또는 음수=무효) 점이 닿는 구간은 판정하지 않는다
    static let maxAccuracyM = 50.0
    /// 표본 가드 — 점·거리가 이보다 적으면 구간을 내지 않는다
    static let minPoints = 10
    static let minDistanceMeters = 500.0

    /// 페이스 색 구간 — 표본이 부족하거나 판정 가능한 구간이 하나도 없으면 nil(호출부는 단색 경로)
    static func segments(_ points: [TrackPoint]) -> [Segment]? {
        guard points.count >= minPoints else { return nil }
        let cumulative = cumulativeMeters(points)
        guard let total = cumulative.last, total >= minDistanceMeters else { return nil }

        // 점마다 앞뒤 halfWindow 창 안 첫·끝 점 사이의 평균 페이스(초/km) — 두 포인터로 O(n).
        // 시작·끝 근처는 창을 줄이지 않고 경로 안쪽으로 민다 — 줄이면 첫·끝 100m가 판정 불가(회색)가 된다
        var paces: [Double?] = []
        var low = 0, high = 0
        for i in points.indices {
            let windowStart = max(0, min(cumulative[i] - halfWindowMeters, total - 2 * halfWindowMeters))
            while cumulative[low] < windowStart { low += 1 }
            while high + 1 < points.count, cumulative[high + 1] <= windowStart + 2 * halfWindowMeters { high += 1 }
            let meters = cumulative[high] - cumulative[low]
            let seconds = points[high].time.timeIntervalSince(points[low].time)
            // 창이 반도 안 차면(점 간격이 너무 넓음) 판정하지 않는다
            paces.append(meters >= halfWindowMeters && seconds > 0 ? seconds / meters * 1_000 : nil)
        }
        let valid = paces.compactMap { $0 }.sorted()
        guard !valid.isEmpty else { return nil }
        let median = valid[valid.count / 2]

        func accurate(_ p: TrackPoint) -> Bool { (0...maxAccuracyM).contains(p.horizontalAccuracyM) }
        let legTones: [RRTone?] = (0..<points.count - 1).map { i in
            guard accurate(points[i]), accurate(points[i + 1]), let pace = paces[i] else { return nil }
            return tone(ratio: pace / median)
        }
        guard legTones.contains(where: { $0 != nil }) else { return nil }

        var result: [Segment] = []
        var startIndex = 0
        for i in 1...legTones.count where i == legTones.count || legTones[i] != legTones[startIndex] {
            result.append(Segment(points: Array(points[startIndex...i]), tone: legTones[startIndex]))
            startIndex = i
        }
        return result
    }

    /// 중앙값 대비 페이스 비율(클수록 느림) → 톤. 경계 ±5%·+15%는 가정값
    /// (스플릿 막대의 '평균 대비 느린 구간' 경고와 같은 결 — 빠름은 개선, 많이 처지면 과부하색)
    static func tone(ratio: Double) -> RRTone {
        if ratio <= 0.95 { return .improving }
        if ratio <= 1.05 { return .steady }
        if ratio <= 1.15 { return .caution }
        return .overload
    }

    /// km 마커 — 누적 거리가 k km를 처음 넘는 점, 1…count km. 경로가 짧으면 그만큼만
    static func kmMarkers(_ points: [TrackPoint], count: Int) -> [TrackPoint] {
        guard count > 0 else { return [] }
        let cumulative = cumulativeMeters(points)
        var markers: [TrackPoint] = []
        for (i, meters) in cumulative.enumerated() where meters >= Double(markers.count + 1) * 1_000 {
            markers.append(points[i])
            if markers.count == count { break }
        }
        return markers
    }

    /// 고도 프로필 — 누적 거리 축을 `samples`등분해 가장 가까운 고도 점을 고른다(인덱스 간격 = 거리 간격).
    /// 고도 있는 점이 minPoints 미만이거나 거리가 0이면 nil(미노출)
    static func elevationProfile(_ points: [TrackPoint], samples: Int = 60) -> [ProfilePoint]? {
        let cumulative = cumulativeMeters(points)
        let withElevation = points.indices.compactMap { i in
            points[i].elevationM.map { (meters: cumulative[i], elevation: $0) }
        }
        guard withElevation.count >= minPoints, samples >= 2,
              let first = withElevation.first, let last = withElevation.last,
              last.meters > first.meters else { return nil }
        var j = 0
        return (0..<samples).map { s in
            let target = first.meters + (last.meters - first.meters) * Double(s) / Double(samples - 1)
            while j + 1 < withElevation.count,
                  abs(withElevation[j + 1].meters - target) <= abs(withElevation[j].meters - target) { j += 1 }
            return ProfilePoint(distanceKm: target / 1_000, elevationM: withElevation[j].elevation)
        }
    }

    private static func cumulativeMeters(_ points: [TrackPoint]) -> [Double] {
        var result: [Double] = []
        result.reserveCapacity(points.count)
        for i in points.indices {
            let leg = i == 0 ? 0 : GPXParser.roughMeters(GeoPoint(lat: points[i - 1].lat, lon: points[i - 1].lon),
                                                         GeoPoint(lat: points[i].lat, lon: points[i].lon))
            result.append((result.last ?? 0) + leg)
        }
        return result
    }
}
