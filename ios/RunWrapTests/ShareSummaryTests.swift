import Foundation
import Testing
@testable import RunWrap

/// 공유 카드 주간 요약 — 창이 '지금'이 아니라 세션 기준 달력 7일인지 검증한다 (이슈 #92).
/// 캘린더는 Asia/Seoul 고정. now = 2026-08-10T09:00:00Z (월 18:00 KST).
struct ShareSummaryTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!
    var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }

    private func run(_ iso: String, km: Double) -> RunSummary {
        RunSummary(id: UUID(), start: ISO8601DateFormatter().date(from: iso)!,
                   durationSec: 3_600, distanceMeters: km * 1_000, avgHeartRate: 150,
                   cadenceSpm: 170)
    }

    @Test("과거 세션 — 그 세션 기준 달력 7일(6일 전 자정~다음 자정)만 세고 '그날까지 7일'로 적는다")
    func pastSessionWindow() throws {
        // 세션: 7.20 07:00 KST → 창 [7.14 00:00, 7.21 00:00) KST
        let session = run("2026-07-19T22:00:00Z", km: 10)
        let runs = [
            session,
            run("2026-07-13T14:59:00Z", km: 5),   // 7.13 23:59 KST — 창 직전, 제외
            run("2026-07-13T15:00:00Z", km: 4),   // 7.14 00:00 KST — 창 시작, 포함
            run("2026-07-20T14:00:00Z", km: 3),   // 7.20 23:00 KST — 같은 날 뒤 기록, 포함
            run("2026-07-20T15:00:00Z", km: 8),   // 7.21 00:00 KST — 창 끝, 제외
            run("2026-08-09T22:00:00Z", km: 12),  // 이번 주 기록 — '지금' 기준이면 섞였을 값
        ]
        let line = ShareSummary.weeklyLine(runs: runs, sessionStart: session.start,
                                           now: now, calendar: calendar)
        #expect(line == "그날까지 7일 3회 · 17.0 km")  // 10 + 4 + 3
    }

    @Test("오늘 세션 — 창이 최근 7일과 같아 '최근 7일'로 적는다")
    func todaySessionWindow() {
        // 세션: 8.10 07:00 KST → 창 [8.4 00:00, 8.11 00:00) KST
        let session = run("2026-08-09T22:00:00Z", km: 10)
        let runs = [session,
                    run("2026-08-03T15:00:00Z", km: 5),   // 8.4 00:00 KST — 포함
                    run("2026-08-03T14:00:00Z", km: 7)]   // 8.3 23:00 KST — 제외
        let line = ShareSummary.weeklyLine(runs: runs, sessionStart: session.start,
                                           now: now, calendar: calendar)
        #expect(line == "최근 7일 2회 · 15.0 km")
    }

    @Test("창 안 기록이 없으면 nil — 카드는 기본 문구로 대체한다")
    func emptyWindow() {
        let session = run("2026-07-19T22:00:00Z", km: 10)
        #expect(ShareSummary.weeklyLine(runs: [], sessionStart: session.start,
                                        now: now, calendar: calendar) == nil)
    }

    // MARK: - 구간 페이스 표 (이슈 #221)

    @Test("구간 표 — 10구간 이하는 1km마다 한 줄, 가장 빠른 구간 하나만 강조")
    func splitRowsPerKm() {
        let rows = ShareSummary.splitRows(paces: [340, 330, 330, 350, 360])
        #expect(rows.map(\.label) == ["1km", "2km", "3km", "4km", "5km"])
        // 2·3km가 330초로 같으면 앞 구간(2km)만 강조
        #expect(rows.map(\.isFastest) == [false, true, false, false, false])
        #expect(ShareSummary.splitRows(paces: Array(repeating: 330, count: 10)).count == 10)
    }

    @Test("구간 표 — 하프(21구간)는 3km씩 묶어 7줄")
    func splitRowsHalfGrouped() {
        // 1~20km는 330초, 21km는 300초 → 3km 평균 330초 × 6줄 + 19–21km (330+330+300)/3 = 320초
        var paces = Array(repeating: 330.0, count: 20)
        paces.append(300)
        let rows = ShareSummary.splitRows(paces: paces)
        #expect(rows.count == 7)
        #expect(rows.first == .init(label: "1–3km", paceSecPerKm: 330, isFastest: false))
        #expect(rows.last == .init(label: "19–21km", paceSecPerKm: 320, isFastest: true))
    }

    @Test("구간 표 — 풀(42구간)은 5km씩 9줄, 마지막은 남은 2km, 묶음 페이스는 평균")
    func splitRowsFullGrouped() {
        // 1~5km: 320·330·340·330·330 → 평균 330초
        let paces = [320.0, 330, 340, 330, 330] + Array(repeating: 350.0, count: 37)
        let rows = ShareSummary.splitRows(paces: paces)
        #expect(rows.count == 9)
        #expect(rows[0] == .init(label: "1–5km", paceSecPerKm: 330, isFastest: true))
        #expect(rows[8] == .init(label: "41–42km", paceSecPerKm: 350, isFastest: false))
    }

    @Test("구간 표 — 3구간 미만이면 표를 내지 않는다")
    func splitRowsTooShort() {
        #expect(ShareSummary.splitRows(paces: [330, 340]).isEmpty)
        #expect(ShareSummary.splitRows(paces: []).isEmpty)
    }
}
