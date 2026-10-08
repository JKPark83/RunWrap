import CoreLocation

/// `TrackPoint` → 지도용 좌표 변환 — 엔진 파일(`TrackPoint.swift`)이 CoreLocation을 모르게 따로 둔다.
/// 지도·공유 카드·경로 가림(RoutePrivacy) 호출부가 표시 직전에 쓴다 (#222 선행).
extension Array where Element == TrackPoint {
    func thinnedCoordinates(max: Int = 600) -> [CLLocationCoordinate2D] {
        thinned(max: max).map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
    }
}
