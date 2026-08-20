import Foundation
import CoreLocation

/// 현재 위치 날씨 스토어 — 홈 판단 카드의 날씨 재료를 만든다 (계획서 M6).
///
/// 원래 HomeScreen이 `@State`로 들고 있던 로딩인데, 기동 스플래시가
/// "날씨가 준비될 때까지" 홈 노출을 미루려면 홈 밖(RootView)에서 진행 상태를
/// 알아야 해서 스토어로 올라왔다. 위치 권한 → 좌표 → 날씨 조회 → 수분 알람
/// 재예약까지가 `load()` 하나에 묶인 기동 로딩의 본체다.
@MainActor
final class WeatherStore: ObservableObject {
    enum State: Equatable {
        case idle
        case loading
        case loaded(CurrentWeather)
        case denied        // 위치 권한 거부 — 앱 안에서 다시 물을 수 없어 설정으로 보낸다
        case unavailable   // 위치 확인 실패 또는 네트워크 실패
    }

    @Published private(set) var state: State = .idle

    /// 결론이 났는가 — 스플래시 해제 조건. 성공뿐 아니라 거부·실패도 결론이다:
    /// 어차피 더 기다려도 값이 생기지 않으므로 홈을 열고 힌트 문구로 안내한다.
    var isSettled: Bool {
        switch state {
        case .idle, .loading: false
        case .loaded, .denied, .unavailable: true
        }
    }

    /// km 정확도면 충분하다 — 날씨 격자 자체가 그 단위다 (LocationProvider 주석 참조)
    private let location = LocationProvider()
    private let client = WeatherClient()

    /// 기동 로딩 — 위치의 결론(좌표·거부·실패)을 기다렸다가 날씨를 조회한다.
    /// 권한 다이얼로그가 떠 있는 동안은 .loading으로 머문다 — 무한 대기의
    /// 안전망(타임아웃)은 스플래시를 쥔 RootView 몫이다.
    func load() async {
        guard case .idle = state else { return }
        state = .loading
        location.request()

        // LocationProvider는 델리게이트 기반이라 @Published 스트림으로 결론만 소비한다
        var coordinate: CLLocationCoordinate2D?
        for await locationState in location.$state.values {
            switch locationState {
            case .idle, .loading:
                continue
            case .located(let located):
                coordinate = located
            case .denied:
                state = .denied
                return
            case .failed:
                state = .unavailable
                return
            }
            break
        }
        guard let coordinate else {
            state = .unavailable
            return
        }

        do {
            let current = try await client.current(latitude: coordinate.latitude,
                                                   longitude: coordinate.longitude)
            state = .loaded(current)
            // 수분 알람 갱신 (계획서 M9) — 날씨를 받아온 이 시점이 당일분 예약 트리거다.
            // '오늘'이 시트가 된 뒤로 이 호출이 앱의 유일한 정기 트리거가 됐다
            await NotificationScheduler.rescheduleHydration(forecastMaxC: current.forecastMaxC)
        } catch {
            state = .unavailable
        }
    }
}
