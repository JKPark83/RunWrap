import Foundation
import CoreLocation

/// 공유 카드 경로 프라이버시 — 러닝은 대개 집에서 출발·도착하므로 SNS에 올리는 스토리 카드에서
/// 경로 양끝을 잘라 거주지가 드러나지 않게 한다 (이슈 #84, Strava 프라이버시 존 방식).
/// 순수 로직이라 UI를 모른다 — 잘라낸 좌표만 돌려주고 그리기는 `RouteSnapshot`이 한다.
enum RoutePrivacy {
    /// 시작점에서 누적 거리 `meters`까지, 끝점에서 거슬러 `meters`까지의 좌표를 잘라낸다.
    /// 잘라낸 뒤 2개 미만(짧은 코스)이면 선을 그을 수 없으므로 빈 배열 — 경로 없이 카드를 만든다.
    /// 거리는 직선 반경이 아니라 경로를 따라 쌓은 누적 거리다 (`CLLocation.distance(from:)`).
    static func trimmed(_ route: [CLLocationCoordinate2D],
                        meters: Double = 300) -> [CLLocationCoordinate2D] {
        guard route.count >= 2 else { return [] }
        var cumulative: [Double] = [0]
        cumulative.reserveCapacity(route.count)
        for i in 1..<route.count {
            let previous = CLLocation(latitude: route[i - 1].latitude,
                                      longitude: route[i - 1].longitude)
            let current = CLLocation(latitude: route[i].latitude, longitude: route[i].longitude)
            cumulative.append(cumulative[i - 1] + current.distance(from: previous))
        }
        let total = cumulative[route.count - 1]
        let kept = route.indices
            .filter { cumulative[$0] >= meters && total - cumulative[$0] >= meters }
            .map { route[$0] }
        return kept.count >= 2 ? kept : []
    }
}
