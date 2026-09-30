import Testing
@testable import RunWrap

/// 스트릭 소형 카드 엔진 — 2주 미만 미노출 가드, 이정표(4·8·12·26·52) 톤 분기,
/// 일반 주의 헤드라인·캡션을 검증한다. 입력이 주 수뿐이라 날짜 주입이 필요 없다.
struct StreakEngineTests {
    @Test("미노출 가드 — 0주·1주는 카드를 내지 않는다")
    func underMinWeeksHidden() {
        #expect(StreakEngine.card(streakWeeks: 0) == nil)
        #expect(StreakEngine.card(streakWeeks: 1) == nil)
    }

    @Test("2주 연속 — 유지 톤 + 헤드라인·이어가기 캡션")
    func twoWeeksSteady() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 2))
        #expect(card.weeks == 2)
        #expect(card.tone == .steady)
        #expect(card.headline == "2주 연속 달리는 중")
        #expect(card.caption == "이번 주도 한 번만 나가면 이어집니다")
    }

    @Test("이정표 4·8·12·26·52주에 정확히 닿으면 좋아지는 중 톤")
    func milestonesImproving() throws {
        for weeks in [4, 8, 12, 26, 52] {
            let card = try #require(StreakEngine.card(streakWeeks: weeks))
            #expect(card.tone == .improving)
            #expect(card.headline == "\(weeks)주 연속 달리는 중")
            #expect(card.caption.hasPrefix("\(weeks)주 이정표!"))
        }
    }

    @Test("4주 이정표 캡션 — 한 달을 꽉 채웠다는 문구")
    func fourWeeksCaption() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 4))
        #expect(card.caption == "4주 이정표! 한 달을 꽉 채우셨어요")
    }

    @Test("이정표가 아닌 5주는 유지 톤")
    func fiveWeeksSteady() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 5))
        #expect(card.tone == .steady)
        #expect(card.caption == "이번 주도 한 번만 나가면 이어집니다")
    }
}
