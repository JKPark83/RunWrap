import Foundation
import Testing
@testable import RunWrap

/// 현재 위치 주변 보급 검색 엔진 검증.
/// 기준점은 석촌호수 남단(37.5100, 127.1000) 고정 — 위도 37.51에서
/// 위도 1도 = 111,195m, 경도 1도 = 111,195 × cos(37.51°) ≈ 88,197m다.
@Suite("주변 보급 검색 엔진")
struct NearbySupplyEngineTests {
    let center = GeoPoint(lat: 37.5100, lon: 127.1000)

    /// 위도만 dy미터 북쪽으로 옮긴 POI — 경도 축척을 안 타서 기대값이 정확하다
    private func poi(_ kind: CoursePOI.Kind, north meters: Double,
                     name: String = "테스트") -> CoursePOI {
        CoursePOI(kind: kind, name: name,
                  lat: center.lat + meters / 111_195.0, lon: center.lon)
    }

    @Test("반경 안은 거리와 함께 잡고, 반경 밖은 버린다")
    func radius() throws {
        let pois = [poi(.water, north: 300, name: "가까운 음수대"),
                    poi(.toilet, north: 1_500, name: "먼 화장실")]
        let result = try #require(NearbySupplyEngine.search(center: center, pois: pois,
                                                           radiusMeters: 1_000))
        #expect(result.matches.count == 1)
        #expect(result.matches[0].poi.name == "가까운 음수대")
        // 300m를 넣었으니 오차 1m 안에서 300이 나와야 한다
        #expect(abs(result.matches[0].meters - 300) < 1)
    }

    @Test("가까운 순으로 정렬한다 — 입력 순서와 무관하게")
    func sorted() throws {
        let pois = [poi(.convenience, north: 800, name: "먼 편의점"),
                    poi(.water, north: 100, name: "가까운 음수대"),
                    poi(.toilet, north: 400, name: "중간 화장실")]
        let result = try #require(NearbySupplyEngine.search(center: center, pois: pois,
                                                           radiusMeters: 1_000))
        #expect(result.matches.map(\.poi.name)
            == ["가까운 음수대", "중간 화장실", "먼 편의점"])
    }

    @Test("반경 경계 — 정확히 반경 위의 지점은 포함한다")
    func boundary() throws {
        let result = try #require(NearbySupplyEngine.search(center: center,
                                                           pois: [poi(.water, north: 500)],
                                                           radiusMeters: 500))
        #expect(result.matches.count == 1)
    }

    @Test("주변에 아무것도 없으면 nil이 아니라 빈 목록 — '없다'와 '못 찾는다'는 다르다")
    func emptyIsNotNil() throws {
        let result = try #require(NearbySupplyEngine.search(center: center,
                                                           pois: [poi(.water, north: 5_000)],
                                                           radiusMeters: 1_000))
        #expect(result.matches.isEmpty)
    }

    @Test("미노출 가드 — 반경이 0 이하면 검색이 성립하지 않아 nil")
    func guardRadius() {
        #expect(NearbySupplyEngine.search(center: center, pois: [poi(.water, north: 10)],
                                          radiusMeters: 0) == nil)
        #expect(NearbySupplyEngine.search(center: center, pois: [poi(.water, north: 10)],
                                          radiusMeters: -100) == nil)
    }

    @Test("종류별 상한 10개 — 편의점이 밀집해도 목록을 독식하지 않는다")
    func perKindCap() throws {
        // 편의점 15개(10~150m)와 음수대 1개(200m)
        var pois = (1...15).map { poi(.convenience, north: Double($0) * 10, name: "편의점\($0)") }
        pois.append(poi(.water, north: 200, name: "음수대"))
        let result = try #require(NearbySupplyEngine.search(center: center, pois: pois,
                                                           radiusMeters: 1_000))
        let convenienceCount = result.matches.filter { $0.poi.kind == .convenience }.count
        #expect(convenienceCount == 10)
        // 상한에 걸려도 음수대는 살아남는다 — 상한은 종류마다 따로 센다
        #expect(result.matches.contains { $0.poi.kind == .water })
    }

    @Test("상한 적용 후에도 전체 정렬은 거리순을 유지한다")
    func sortedAfterCap() throws {
        var pois = (1...12).map { poi(.convenience, north: Double($0) * 10, name: "편의점\($0)") }
        pois.append(poi(.water, north: 35, name: "음수대"))
        let result = try #require(NearbySupplyEngine.search(center: center, pois: pois,
                                                           radiusMeters: 1_000))
        let distances = result.matches.map(\.meters)
        #expect(distances == distances.sorted())
    }

    @Test("결과에 기준점과 반경을 그대로 담는다 — 화면이 지도 카메라에 쓴다")
    func echoesCenter() throws {
        let result = try #require(NearbySupplyEngine.search(center: center, pois: [],
                                                           radiusMeters: 800))
        #expect(result.center == center)
        #expect(result.radiusMeters == 800)
    }
}
