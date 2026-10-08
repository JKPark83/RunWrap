import Foundation

/// 경로 한 점의 원본 — 위경도에 시각·고도·수평 정확도·속도를 함께 보관한다.
/// 엔진 계층이 CoreLocation을 모르게 하려고 `CLLocation` 대신 이 값 타입을 쓴다.
/// GPX 내보내기(#222)·페이스 색 경로(#223)·#224가 모두 시각·고도·속도를 필요로 해서
/// 조회 시점에 솎지 않고 전부 보관한다 — 솎기는 표시 직전(`thinnedCoordinates`)에 한다.
struct TrackPoint: Equatable, Sendable {
    let lat: Double
    let lon: Double
    let time: Date
    /// 고도(m) — 수직 정확도가 음수(측정 무효)면 nil
    let elevationM: Double?
    let horizontalAccuracyM: Double
    /// 속도(m/s) — 기기가 속도를 내지 못하면(음수) nil
    let speedMps: Double?
}

extension Array where Element == TrackPoint {
    /// 폴리라인은 ~600점이면 충분 — 과한 포인트는 솎는다 (stride = count / max, 첫 점부터 stride 간격)
    /// ponytail: 정수 나눗셈 stride라 max의 2배 미만 입력은 그대로 남는다(기존 동작 유지)
    func thinned(max: Int = 600) -> [TrackPoint] {
        let stride = Swift.max(1, count / max)
        return enumerated().filter { $0.offset % stride == 0 }.map(\.element)
    }
}
