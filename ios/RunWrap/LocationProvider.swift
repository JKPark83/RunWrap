import Foundation
import CoreLocation

/// 현재 위치 1회 조회 — CLLocationManager WhenInUse 래퍼 (계획서 M6).
/// HealthStore의 enum State 패턴을 따른다. 위치는 좌표를 쓰고 버릴 뿐 저장하지 않는다.
///
/// 정확도는 쓰는 쪽이 정한다. 날씨는 km면 충분하지만(격자 자체가 그 단위다),
/// 코스 탭의 주변 보급은 300m 밖 음수대를 짚어야 해서 km 오차로는 순서가 뒤집힌다.
@MainActor
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum State: Equatable {
        case idle
        case loading
        case located(CLLocationCoordinate2D)
        case denied      // 권한 거부 — 화면은 안내 문구로 대체한다
        case failed

        static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.loading, .loading), (.denied, .denied), (.failed, .failed):
                true
            case (.located(let a), .located(let b)):
                a.latitude == b.latitude && a.longitude == b.longitude
            default:
                false
            }
        }
    }

    @Published private(set) var state: State = .idle
    /// 받은 좌표가 흐린지(대략적 위치) — 설정의 '정확한 위치'가 꺼졌거나 오차가 큰 경우 true.
    /// 날씨는 km 단위라 이 값을 보지 않는다. 주변 보급처럼 100m가 의미를 갖는 쪽만 가드로 쓴다 (이슈 #74)
    @Published private(set) var isCoarse = false

    private let manager = CLLocationManager()

    /// - Parameter accuracy: 기본값은 날씨용 km 정확도. 보급 지점처럼 100m 단위가
    ///   의미를 갖는 쪽은 `kCLLocationAccuracyNearestTenMeters`를 넘긴다
    init(accuracy: CLLocationAccuracy = kCLLocationAccuracyKilometer) {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = accuracy
    }

    func request() {
        isCoarse = false
        switch manager.authorizationStatus {
        case .denied, .restricted:
            state = .denied
        case .notDetermined:
            state = .loading
            manager.requestWhenInUseAuthorization()  // 응답은 didChangeAuthorization으로
        default:
            state = .loading
            manager.requestLocation()
        }
    }

    /// '정확한 위치'가 꺼진 사용자에게 이번 사용 동안만 정확한 위치를 청한다.
    /// 목적 문구는 Info.plist `NSLocationTemporaryUsageDescriptionDictionary`의 `NearbySupply` 키다.
    /// 허락받으면 좌표를 다시 받고, 거절되면 isCoarse를 그대로 둬 안내 카드가 남는다.
    /// 완료 블록은 매니저를 만든 런루프(메인)에서 불린다 — CLLocationManager.h
    func requestFullAccuracy() {
        manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "NearbySupply") { [weak self] _ in
            Task { @MainActor in
                guard let self, self.manager.accuracyAuthorization == .fullAccuracy else { return }
                self.request()
            }
        }
    }

    /// 좌표가 흐린지 판정 — 순수 함수라 테스트한다.
    /// 대략적 위치(reducedAccuracy)는 1~20km 단위로 뭉개져 온다. 주변 보급 반경이 1km라
    /// 오차가 수백 m만 넘어도 가까운 순서가 뒤집히므로 500m를 넘으면 흐리다고 본다.
    /// 음수 horizontalAccuracy는 좌표가 무효라는 뜻이라 역시 흐린 쪽으로 친다 (CLLocation 문서)
    nonisolated static func isCoarse(reducedAccuracy: Bool,
                                     horizontalAccuracy: CLLocationAccuracy,
                                     threshold: CLLocationAccuracy = 500) -> Bool {
        reducedAccuracy || horizontalAccuracy < 0 || horizontalAccuracy > threshold
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedWhenInUse, .authorizedAlways:
                if case .loading = state { self.manager.requestLocation() }
            case .denied, .restricted:
                state = .denied
            default:
                break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager,
                                     didUpdateLocations locations: [CLLocation]) {
        let coordinate = locations.first?.coordinate
        let coarse = locations.first.map {
            Self.isCoarse(reducedAccuracy: manager.accuracyAuthorization == .reducedAccuracy,
                          horizontalAccuracy: $0.horizontalAccuracy)
        } ?? false
        Task { @MainActor in
            // state보다 먼저 세운다 — state의 onChange에서 검색 가드가 이 값을 본다
            isCoarse = coarse
            if let coordinate { state = .located(coordinate) } else { state = .failed }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager,
                                     didFailWithError error: Error) {
        Task { @MainActor in state = .failed }
    }
}
