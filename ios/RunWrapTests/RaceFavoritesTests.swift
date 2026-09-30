import Foundation
import Testing
@testable import RunWrap

/// 대회 즐겨찾기 직렬화 검증 (이슈 #172) — @AppStorage 문자열 ↔ 대회 번호 배열
struct RaceFavoritesTests {
    @Test("왕복 — 인코딩한 문자열을 디코딩하면 순서까지 그대로 돌아온다")
    func roundTrip() {
        let ids = [3_512, 17, 42]
        let encoded = RaceFavorites.encode(ids)
        #expect(encoded == "[3512,17,42]")
        #expect(RaceFavorites.decode(encoded) == ids)
        #expect(RaceFavorites.decode(RaceFavorites.encode([])) == [])
    }

    @Test("토글 — 없으면 뒤에 붙이고, 있으면 뺀다")
    func toggled() {
        // [1, 2] + 3 → [1, 2, 3], 다시 2를 누르면 → [1, 3]
        let added = RaceFavorites.toggled([1, 2], 3)
        #expect(added == [1, 2, 3])
        #expect(RaceFavorites.toggled(added, 2) == [1, 3])
        #expect(RaceFavorites.toggled([], 7) == [7])
        #expect(RaceFavorites.toggled([7], 7) == [])
    }

    @Test("깨진 값 — 빈 문자열·JSON 아님·타입 불일치는 즐겨찾기 없음으로 읽는다")
    func brokenInput() {
        #expect(RaceFavorites.decode("") == [])
        #expect(RaceFavorites.decode("1,2,3") == [])
        #expect(RaceFavorites.decode("[\"a\"]") == [])
        #expect(RaceFavorites.decode("{\"id\":1}") == [])
    }
}
