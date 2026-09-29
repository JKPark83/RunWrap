import CoreLocation
import Testing
@testable import RunWrap

/// 흐린 좌표 판정 검증 (이슈 #74).
/// 주변 보급 반경이 1km라 오차 500m를 넘으면 가까운 순서가 뒤집힐 수 있어 흐리다고 본다.
@Suite("위치 정확도 판정")
struct LocationProviderTests {
    @Test("정확한 위치 허용 + 오차 수십 m — 흐리지 않다")
    func precise() {
        #expect(!LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 15))
    }

    @Test("'정확한 위치'가 꺼지면 오차 값과 무관하게 흐리다")
    func reducedAccuracy() {
        // 대략적 위치는 1~20km로 뭉개지지만 horizontalAccuracy가 작게 와도 권한 쪽을 믿는다
        #expect(LocationProvider.isCoarse(reducedAccuracy: true, horizontalAccuracy: 15))
        #expect(LocationProvider.isCoarse(reducedAccuracy: true, horizontalAccuracy: 3_000))
    }

    @Test("경계값 — 500m까지는 쓰고, 넘으면 흐리다")
    func threshold() {
        #expect(!LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 500))
        #expect(LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 500.1))
        #expect(LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 1_000))
    }

    @Test("음수 오차는 무효 좌표라 흐린 쪽으로 친다")
    func invalid() {
        #expect(LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: -1))
    }

    @Test("임계값을 넘겨 받으면 그 값으로 판정한다")
    func customThreshold() {
        #expect(LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 150,
                                          threshold: 100))
        #expect(!LocationProvider.isCoarse(reducedAccuracy: false, horizontalAccuracy: 80,
                                           threshold: 100))
    }
}
