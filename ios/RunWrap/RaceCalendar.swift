import EventKit

/// 대회 캘린더 추가 (이슈 #172) — 상세 화면 '캘린더에 추가' 버튼을 눌렀을 때만 이벤트 1건을 쓴다.
/// iOS 17의 쓰기 전용 권한만 청한다 — 기존 일정은 읽지 않고, 어디에도 전송하지 않는다.
enum RaceCalendarError: Error {
    case denied       // 사용자가 캘린더 쓰기 권한을 거부했다 — 설정으로 안내
    case noCalendar   // 새 이벤트를 받을 기본 캘린더가 없다
}

enum RaceCalendar {
    /// 이벤트 시각 (순수 값) — 출발 시각을 알면 그 시각부터 4시간, 모르면 종일
    struct Schedule: Equatable {
        let start: Date
        let end: Date
        let isAllDay: Bool
    }

    /// 출발 시각이 있을 때 이벤트 길이 — 풀코스 제한시간(보통 5시간) 안쪽의 대략적인 대회 일정
    static let raceDuration: TimeInterval = 4 * 3_600

    /// 대회 → 이벤트 시각. startTime("HH:mm")은 KST 기준이고, 형식이 어긋나면 종일로 낸다
    static func schedule(for entry: RaceEngine.Entry) -> Schedule {
        let fields = entry.race.startTime?.split(separator: ":") ?? []
        let parts = fields.compactMap { Int($0) }
        if parts.count == 2, fields.count == 2, fields[1].count == 2,
           (0..<24).contains(parts[0]), (0..<60).contains(parts[1]),
           let start = RaceEngine.calendar.date(bySettingHour: parts[0], minute: parts[1],
                                                second: 0, of: entry.raceDate) {
            return Schedule(start: start, end: start.addingTimeInterval(raceDuration), isAllDay: false)
        }
        return Schedule(start: entry.raceDate, end: entry.raceDate, isAllDay: true)
    }

    /// 쓰기 전용 권한을 청하고 기본 캘린더에 이벤트 1건을 저장한다 (iOS 17+ API)
    static func add(_ entry: RaceEngine.Entry) async throws {
        let store = EKEventStore()
        guard try await store.requestWriteOnlyAccessToEvents() else { throw RaceCalendarError.denied }
        guard let calendar = store.defaultCalendarForNewEvents else { throw RaceCalendarError.noCalendar }
        let race = entry.race
        let schedule = schedule(for: entry)
        let event = EKEvent(eventStore: store)
        event.calendar = calendar
        event.title = race.name
        event.isAllDay = schedule.isAllDay
        event.startDate = schedule.start
        event.endDate = schedule.end
        if !schedule.isAllDay { event.timeZone = RaceEngine.calendar.timeZone }
        event.location = race.place ?? race.region
        event.url = race.homepage.flatMap(URL.init(string:))
        event.notes = "런미새에서 추가"
        try store.save(event, span: .thisEvent)
    }
}
