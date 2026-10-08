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

    /// 공유 카드 구간 페이스 표의 한 줄 — "3km 5:41" 또는 묶었을 때 "3–4km 5:41" (이슈 #221)
    struct SplitRow: Equatable {
        let label: String
        let paceSecPerKm: Double
        /// 가장 빠른 줄 — 카드에서 강조한다 (같으면 앞 줄)
        let isFastest: Bool
    }

    /// 1km 스플릿 페이스 → 카드 표 줄. 9:16 카드에 지도와 함께 2열 × 5줄(`maxRows` 10)까지만 들어가므로
    /// 그보다 많으면 연속 구간을 ceil(n / maxRows)km씩 묶어 평균 페이스를 적는다 —
    /// 하프(21구간)는 3km씩 7줄, 풀(42구간)은 5km씩 9줄(마지막은 41–42km). 구간 거리가 같아(1km) 단순 평균이 곧 시간 가중 평균이다.
    /// 3구간 미만이면 표가 의미 없어 빈 배열 — 세션 상세 스플릿 카드와 같은 가드 (미노출 원칙)
    /// 라벨은 배열 위치가 아니라 실제 km 번호(`index`)로 적는다 — ActiveTimeline이 데이터 오류 구간을
    /// 건너뛰어 번호가 비어도(1·2·4km…) 뒤 라벨이 밀리지 않게, 세션 상세 스플릿 차트와 같은 번호를 쓴다.
    static func splitRows(splits: [(index: Int, paceSecPerKm: Double)], maxRows: Int = 10) -> [SplitRow] {
        guard splits.count >= 3, maxRows > 0 else { return [] }
        let size = (splits.count + maxRows - 1) / maxRows
        let groups = stride(from: 0, to: splits.count, by: size).map { start in
            let group = splits[start..<min(start + size, splits.count)]
            let first = group.first!.index, last = group.last!.index
            let label = group.count == 1 ? "\(first)km" : "\(first)–\(last)km"
            return (label, group.map(\.paceSecPerKm).reduce(0, +) / Double(group.count))
        }
        let fastest = groups.indices.min { groups[$0].1 < groups[$1].1 }
        return groups.indices.map {
            SplitRow(label: groups[$0].0, paceSecPerKm: groups[$0].1, isFastest: $0 == fastest)
        }
    }
}
