import Foundation
import Testing
@testable import RunWrap

/// 경로 솎기 — 원본 TrackPoint는 전부 보관하고 표시 직전에만 ~600점으로 줄인다 (#222 선행).
struct TrackPointTests {
    private let start = ISO8601DateFormatter().date(from: "2026-10-01T06:00:00Z")!

    /// 위도만 i씩 늘어나는 n개 점 — 몇 번째 점이 남았는지 위도로 바로 알 수 있다
    private func points(_ n: Int) -> [TrackPoint] {
        (0..<n).map { i in
            TrackPoint(lat: Double(i), lon: 127, time: start.addingTimeInterval(Double(i)),
                       elevationM: nil, horizontalAccuracyM: 5, speedMps: nil)
        }
    }

    @Test("솎기 — 3000점은 stride 5로 600점이 되고 첫 점을 유지한다")
    func thinsLargeRoute() throws {
        // 3000 / 600 = 5 → 0, 5, 10, …, 2995번 점 = 600개
        let coordinates = points(3_000).thinnedCoordinates()
        #expect(coordinates.count == 600)
        #expect(try #require(coordinates.first).latitude == 0)
        #expect(coordinates[1].latitude == 5)
    }

    @Test("솎기 — 1000점 이하는 stride 1이라 그대로 남는다")
    func keepsSmallRoute() {
        // 1000 / 600 = 1 (정수 나눗셈) → 솎지 않는다
        let route = points(1_000)
        #expect(route.thinned() == route)
        #expect(route.thinnedCoordinates().map(\.latitude) == route.map(\.lat))
    }

    @Test("솎기 — 빈 경로는 빈 좌표를 돌려준다")
    func emptyRoute() {
        #expect([TrackPoint]().thinnedCoordinates().isEmpty)
    }
}
