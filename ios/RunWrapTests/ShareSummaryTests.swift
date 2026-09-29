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
}
