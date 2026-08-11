import Foundation
import CoreLocation

/// 현재 위치 1회 조회 — CLLocationManager WhenInUse 래퍼 (계획서 M6).
/// HealthStore의 enum State 패턴을 따른다. 위치는 날씨 요청 좌표로만 쓰고 저장하지 않는다.
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

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer  // 날씨용 — 정밀 위치 불필요
    }

    func request() {
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
        Task { @MainActor in
            if let coordinate { state = .located(coordinate) } else { state = .failed }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager,
                                     didFailWithError error: Error) {
        Task { @MainActor in state = .failed }
    }
}
