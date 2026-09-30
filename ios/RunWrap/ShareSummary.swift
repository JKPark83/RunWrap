import Foundation

/// 공유 카드 하단 주간 요약 문구 (기획서 §4.4) — 순수 로직: now를 주입받아 결정론적이다.
///
/// 창은 '지금'이 아니라 그 세션 기준 달력 7일 — [startOfDay(run.start − 6일), run.start 다음 자정).
/// '지금' 기준이면 과거 세션 카드에 현재 주 수치가 찍힌다 (이슈 #92). 달력 창 방식은 이슈 #75와 같다.
enum ShareSummary {
    /// "최근 7일 3회 · 24.5 km" — 창 안 기록이 없으면 nil (카드는 기본 문구로 대체한다)
    static func weeklyLine(runs: [RunSummary], sessionStart: Date, now: Date,
                           calendar: Calendar = .current) -> String? {
        let start = calendar.startOfDay(for: sessionStart.addingTimeInterval(-6 * 86_400))
        guard let end = calendar.date(byAdding: .day, value: 1,
                                      to: calendar.startOfDay(for: sessionStart)) else { return nil }
        let week = runs.filter { $0.start >= start && $0.start < end }
        guard !week.isEmpty else { return nil }
        let km = week.compactMap(\.distanceKm).reduce(0, +)
        // 오늘 세션이면 창이 '최근 7일'과 같다 — 지난 세션은 그날(카드 헤더 날짜)까지 7일로 적는다
        let label = calendar.isDate(sessionStart, inSameDayAs: now) ? "최근 7일" : "그날까지 7일"
        return "\(label) \(week.count)회 · \(Format.km(km)) km"
    }
}
