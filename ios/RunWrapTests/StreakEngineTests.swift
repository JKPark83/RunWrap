import Testing
@testable import RunWrap

/// 스트릭 소형 카드 엔진 — 2주 미만 미노출 가드, 이정표(4·8·12·26·52) 톤 분기,
/// 일반 주의 헤드라인·캡션을 검증한다. 입력이 주 수·이번 주 러닝 여부뿐이라 날짜 주입이 필요 없다.
struct StreakEngineTests {
    @Test("미노출 가드 — 0주·1주는 카드를 내지 않는다")
    func underMinWeeksHidden() {
        #expect(StreakEngine.card(streakWeeks: 0, ranThisWeek: false) == nil)
        #expect(StreakEngine.card(streakWeeks: 1, ranThisWeek: true) == nil)
    }

    @Test("2주 연속 — 유지 톤 + 헤드라인·이어가기 캡션")
    func twoWeeksSteady() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 2, ranThisWeek: false))
        #expect(card.weeks == 2)
        #expect(card.tone == .steady)
        #expect(card.headline == "2주 연속 달리는 중")
        #expect(card.caption == "이번 주도 한 번만 나가면 이어집니다")
    }

    @Test("이정표 4·8·12·26·52주에 정확히 닿으면 좋아지는 중 톤")
    func milestonesImproving() throws {
        for weeks in [4, 8, 12, 26, 52] {
            let card = try #require(StreakEngine.card(streakWeeks: weeks, ranThisWeek: false))
            #expect(card.tone == .improving)
            #expect(card.headline == "\(weeks)주 연속 달리는 중")
            #expect(card.caption.hasPrefix("\(weeks)주 이정표!"))
        }
    }

    @Test("4주 이정표 캡션 — 한 달을 꽉 채웠다는 문구")
    func fourWeeksCaption() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 4, ranThisWeek: false))
        #expect(card.caption == "4주 이정표! 한 달을 꽉 채우셨어요")
    }

    @Test("이정표가 아닌 5주는 유지 톤")
    func fiveWeeksSteady() throws {
        let card = try #require(StreakEngine.card(streakWeeks: 5, ranThisWeek: false))
        #expect(card.tone == .steady)
        #expect(card.caption == "이번 주도 한 번만 나가면 이어집니다")
    }

    @Test("이번 주에 이미 달렸으면 캡션이 다음 주로 넘어간다 (이슈 #195)")
    func ranThisWeekCaption() throws {
        let ran = try #require(StreakEngine.card(streakWeeks: 3, ranThisWeek: true))
        #expect(ran.caption == "이번 주 몫은 채우셨어요, 다음 주에 이어 가요")
        #expect(ran.tone == .steady)

        let notYet = try #require(StreakEngine.card(streakWeeks: 3, ranThisWeek: false))
        #expect(notYet.caption == "이번 주도 한 번만 나가면 이어집니다")
    }

    @Test("이정표 캡션은 이번 주 러닝 여부와 무관하다 (이슈 #195)")
    func milestoneIgnoresRanThisWeek() throws {
        let ran = try #require(StreakEngine.card(streakWeeks: 8, ranThisWeek: true))
        let notYet = try #require(StreakEngine.card(streakWeeks: 8, ranThisWeek: false))
        #expect(ran == notYet)
        #expect(ran.caption == "8주 이정표! 두 달째 꾸준하시네요")
    }
}
