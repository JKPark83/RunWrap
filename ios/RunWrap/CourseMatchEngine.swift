import Foundation

/// 같은 코스 판정 (이슈 #223, 리서치 문서 §06) — Strava Matched Activities 방식(시작·끝·방향·거리).
/// "반포 5km를 지난달보다 얼마나 빨리 뛰었나"를 보여 주는 재료. 경로 전체 대신 점 몇 개(지문)만 비교한다.
/// 순수 로직 — 거리는 등장방형 근사(`GPXParser.roughMeters`)로 잰다. 수 km 코스에서 오차는 무시할 만하다.
///
/// 허용 오차는 엄격하게 시작한다(Strava는 비공개): 시작·끝 반경 150m, 거리 ±5%, 중간점 3개 각 200m 이내.
/// **오탐(다른 코스를 같은 코스로 묶음)은 "없느니만 못한 인사이트"라 0건이 목표** — 실기록으로 재 보고 조정한다.
enum CourseMatchEngine {
    struct Fingerprint: Codable, Equatable {
        let start: GeoPoint
        let end: GeoPoint
        let distanceM: Double
        /// 시작→끝 방위(도, 북 0·동 90) — 루프 코스에서는 무의미해 비교하지 않는다
        let bearingDeg: Double
        /// 경로 길이 25·50·75% 지점 — 왕복 코스·역방향 구분용
        let waypoints: [GeoPoint]
    }

    static let endpointToleranceM = 150.0
    static let distanceTolerance = 0.05
    static let waypointToleranceM = 200.0
    /// 시작·끝이 이보다 가까우면 루프(순환·왕복) — 시작·끝 허용 반경 두 배
    static let loopThresholdM = 300.0
    /// 루프가 아닐 때 방위 허용 차
    static let bearingToleranceDeg = 30.0
    /// 같은 코스 기록이 이보다 적으면 비교하지 않는다 — 추세를 말할 표본이 아니다
    static let minimumMatches = 3

    /// 경로 원본 → 지문. 점이 2개 미만이거나 경로 길이·거리가 0이면 nil
    static func fingerprint(_ route: [TrackPoint], distanceM: Double) -> Fingerprint? {
        guard route.count >= 2, distanceM > 0 else { return nil }
        let points = route.map { GeoPoint(lat: $0.lat, lon: $0.lon) }
        var cumulative: [Double] = [0]
        for i in 1..<points.count {
            cumulative.append(cumulative[i - 1] + GPXParser.roughMeters(points[i - 1], points[i]))
        }
        guard let total = cumulative.last, total > 0 else { return nil }
        // 누적 거리가 비율을 처음 넘는 점 — 1초 간격 기록이라 보간하지 않는다
        let waypoints = [0.25, 0.5, 0.75].map { fraction in
            points[cumulative.firstIndex { $0 >= total * fraction } ?? points.count - 1]
        }
        return Fingerprint(start: points[0], end: points[points.count - 1], distanceM: distanceM,
                           bearingDeg: bearing(points[0], points[points.count - 1]),
                           waypoints: waypoints)
    }

    static func isSameCourse(_ a: Fingerprint, _ b: Fingerprint) -> Bool {
        // 거리: 짧은 쪽 대비 ±5% — 기준을 짧은 쪽으로 둬 a·b 순서와 무관하다
        guard abs(a.distanceM - b.distanceM) <= min(a.distanceM, b.distanceM) * distanceTolerance,
              GPXParser.roughMeters(a.start, b.start) <= endpointToleranceM,
              GPXParser.roughMeters(a.end, b.end) <= endpointToleranceM,
              a.waypoints.count == b.waypoints.count,
              zip(a.waypoints, b.waypoints).allSatisfy({
                  GPXParser.roughMeters($0, $1) <= waypointToleranceM
              }) else { return false }
        // 루프 코스(시작≈끝)는 방위가 무의미 — 방향은 위 중간점이 가른다
        let isLoop = GPXParser.roughMeters(a.start, a.end) < loopThresholdM
        if isLoop { return true }
        let diff = abs(a.bearingDeg - b.bearingDeg).truncatingRemainder(dividingBy: 360)
        return min(diff, 360 - diff) <= bearingToleranceDeg
    }

    /// 대상과 같은 코스인 기록(시작 시각 오름차순). history에 대상 세션 자신도 넣는다 —
    /// 같은 코스 기록이 3회 미만이면 nil (추세를 말할 표본이 아니다)
    static func matches(of target: Fingerprint,
                        in history: [(RunSummary, Fingerprint)]) -> [RunSummary]? {
        let runs = history.filter { isSameCourse(target, $0.1) }.map(\.0).sorted { $0.start < $1.start }
        return runs.count >= minimumMatches ? runs : nil
    }

    /// 이 세션이 몇 번째 완주인지(1부터, 시작 시각 순)와 페이스 순위(1 = 최고 기록).
    /// 페이스가 없는 세션(RunSummary.paceSecPerKm nil)은 순위를 매기지 않는다
    static func standing(of run: RunSummary, in matches: [RunSummary]) -> (ordinal: Int, rank: Int?)? {
        guard let index = matches.firstIndex(where: { $0.id == run.id }) else { return nil }
        let rank = run.paceSecPerKm.map { pace in
            matches.compactMap(\.paceSecPerKm).filter { $0 < pace }.count + 1
        }
        return (ordinal: index + 1, rank: rank)
    }

    /// 시작→끝 방위(도) — 등장방형 근사, 0..<360
    private static func bearing(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let east = (b.lon - a.lon) * cos((a.lat + b.lat) / 2 * .pi / 180)
        let north = b.lat - a.lat
        let deg = atan2(east, north) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }
}
