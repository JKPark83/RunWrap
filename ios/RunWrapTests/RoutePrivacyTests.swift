import CoreLocation
import Testing
@testable import RunWrap

/// 공유 카드 경로 트림 — 시작·끝 300m를 누적 거리로 잘라내는지 검증한다 (이슈 #84).
struct RoutePrivacyTests {
    /// 위도 1°의 대략적 길이(m) — 경도 고정 남북 직선이라 위도 차만으로 거리가 정해진다
    private let metersPerDegree = 111_195.0

    /// 경도 고정, 북쪽으로 `step`m 간격 직선 경로 — 0, step, 2·step, …, length(m)
    /// 경계(300m)에 좌표가 정확히 걸리지 않도록 step을 고른다 (CLLocation 거리 오차 ±0.5% 대비)
    private func straight(length: Double, step: Double) -> [CLLocationCoordinate2D] {
        let count = Int((length / step).rounded())
        return (0...count).map { i in
            CLLocationCoordinate2D(latitude: 37.5 + Double(i) * step / metersPerDegree,
                                   longitude: 127.0)
        }
    }

    private func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    @Test("직선 1km 경로 — 양끝 300m가 잘리고 320~680m 구간 10개 좌표만 남는다")
    func trimsBothEndsOfOneKilometer() throws {
        // 40m 간격 26개(0~1000m) → 앞에서 300m 이상·뒤에서 300m 이상인 320, 360, …, 680m = 10개
        let route = straight(length: 1_000, step: 40)
        let trimmed = RoutePrivacy.trimmed(route)

        #expect(trimmed.count == 10)
        let first = try #require(trimmed.first)
        let last = try #require(trimmed.last)
        #expect(first.latitude == route[8].latitude)   // 8 × 40 = 320m
        #expect(last.latitude == route[17].latitude)   // 17 × 40 = 680m
        #expect(distance(route[0], first) >= 300)
        #expect(distance(last, route[route.count - 1]) >= 300)
    }

    @Test("500m 코스 — 양끝 300m를 자르면 남는 좌표가 없어 빈 배열")
    func shortRouteBecomesEmpty() {
        // 50m 간격 0~500m → 앞 300m 이상이면서 뒤 300m 이상인 지점이 없다
        #expect(RoutePrivacy.trimmed(straight(length: 500, step: 50)).isEmpty)
    }

    @Test("잘라낸 뒤 1개만 남으면 선을 그을 수 없어 빈 배열")
    func singleRemainingPointBecomesEmpty() {
        // 40m 간격 0~640m → 300m 이상·340m 이하인 320m 지점 하나뿐
        #expect(RoutePrivacy.trimmed(straight(length: 640, step: 40)).isEmpty)
    }

    @Test("좌표가 2개 미만이면 빈 배열")
    func tooFewPointsBecomesEmpty() {
        #expect(RoutePrivacy.trimmed([]).isEmpty)
        #expect(RoutePrivacy.trimmed([CLLocationCoordinate2D(latitude: 37.5, longitude: 127)]).isEmpty)
    }

    @Test("트림은 원본 경로를 바꾸지 않는다")
    func originalRouteIsUntouched() {
        let route = straight(length: 1_000, step: 40)
        let snapshot = route.map(\.latitude)
        _ = RoutePrivacy.trimmed(route)
        #expect(route.count == 26)
        #expect(route.map(\.latitude) == snapshot)
    }
}
