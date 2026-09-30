import Foundation
import Testing
@testable import RunWrap

/// 대회 → 캘린더 이벤트 시각 검증 (이슈 #172) — now = 2026-08-12 09:00 KST 고정
struct RaceCalendarTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-12T09:00:00+09:00")!

    private func entry(startTime: String?) throws -> RaceEngine.Entry {
        let race = Race(id: 1, name: "테스트 대회", date: "2026-10-04", startTime: startTime)
        return try #require(RaceEngine.entries(from: [race], now: now).first)
    }

    @Test("출발 시각이 있으면 — 그 시각(KST)부터 4시간짜리 이벤트")
    func timedEvent() throws {
        // 10/4 09:30 KST 출발 → 09:30~13:30 KST
        let schedule = RaceCalendar.schedule(for: try entry(startTime: "09:30"))
        #expect(!schedule.isAllDay)
        #expect(schedule.start == ISO8601DateFormatter().date(from: "2026-10-04T09:30:00+09:00"))
        #expect(schedule.end == ISO8601DateFormatter().date(from: "2026-10-04T13:30:00+09:00"))
    }

    @Test("출발 시각이 없거나 형식이 어긋나면 — 대회일 종일 이벤트")
    func allDayEvent() throws {
        let midnight = ISO8601DateFormatter().date(from: "2026-10-04T00:00:00+09:00")
        for startTime in [nil, "9시", "25:00", "08:3"] as [String?] {
            let schedule = RaceCalendar.schedule(for: try entry(startTime: startTime))
            #expect(schedule.isAllDay)
            #expect(schedule.start == midnight)
            #expect(schedule.end == midnight)
        }
    }
}
