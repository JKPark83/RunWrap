import Foundation

/// 월간·연간 결산 리캡 (이슈 #167) — 달력 월·달력 연 단위로 총량·하이라이트·새 기록·강도 배분을
/// 한 번에 묶고, 존댓말 위트 톤(기획서 v0.4 §4.11)의 마무리 한 줄을 결정론적으로 고른다.
///
/// 왜: 주간 리포트는 "지금 상태"를 말하지만, 한 달·한 해를 돌아보는 계기가 앱에 없었다.
/// 새 산식은 만들지 않는다 — PR은 `PersonalRecords.compute`(#166 베스트 에포트),
/// 강도 배분은 `ZoneDistributionEngine`(#165)과 같은 존 매핑·가드·톤 경계를 그대로 쓴다.
/// 순수 로직: Foundation만 쓰고 `now`·`calendar`를 주입받아 결정론적이다.

/// 결산 기간 — 연관값은 그 기간 안의 아무 시각(보통 기간 시작일)
enum RecapPeriod: Hashable, Identifiable {
    case month(Date)
    case year(Date)

    /// `.sheet(item:)`용 — 같은 기준 시각이면 같은 시트
    var id: String {
        switch self {
        case .month(let date): "month-\(date.timeIntervalSince1970)"
        case .year(let date): "year-\(date.timeIntervalSince1970)"
        }
    }

    /// 달력 월·달력 연 구간 [start, end)
    func interval(calendar: Calendar) -> DateInterval {
        switch self {
        case .month(let date): calendar.dateInterval(of: .month, for: date)!
        case .year(let date): calendar.dateInterval(of: .year, for: date)!
        }
    }

    /// 같은 길이의 직전 기간 — 지난달·지난해
    func previous(calendar: Calendar) -> RecapPeriod {
        let start = interval(calendar: calendar).start
        switch self {
        case .month: return .month(calendar.date(byAdding: .month, value: -1, to: start)!)
        case .year: return .year(calendar.date(byAdding: .year, value: -1, to: start)!)
        }
    }
}

struct Recap {
    /// 카드 1 — 총량. 런린이는 거리 수치를 숨기고 횟수·시간을 크게 (ReportGate §4 "문장만")
    struct Totals {
        let distanceKm: Double
        let count: Int
        let durationSec: Double
        let showsDistance: Bool
    }

    /// 카드 2 — 하이라이트. 둘 다 없으면 카드째 nil
    struct Highlights {
        /// 거리 최대 세션
        let longest: RunSummary?
        /// 평균 페이스 최소 세션 — 3km 이상만 후보
        let fastest: RunSummary?
    }

    /// 카드 4 — 강도 배분 (월간만). ZoneDistributionEngine.easyShare 결과
    struct Intensity {
        let easyShare: Double
        let tone: RRTone
        let sessions: Int
    }

    /// "2026년 8월 결산" / "2026년 결산"
    let title: String
    /// "2026년 8월" / "2026년" — 공유 카드 아이브로
    let periodLabel: String
    let period: RecapPeriod
    let totals: Totals
    let highlights: Highlights?
    /// 카드 3 — 이 기간에 세운 거리별 최고 기록. 비어 있으면 카드 생략
    let records: [PersonalRecords.Entry]
    let intensity: Intensity?
    /// 카드 5 — 마무리 한 줄
    let closingLine: String
    /// 직전 기간 대비 거리 증감(%) — 직전 기간 거리가 3km 미만이면 nil (MonthlyStats.deltaPct와 같은 가드)
    let deltaPct: Double?
}

enum RecapEngine {
    /// 미노출 가드 — 기간 안 러닝이 이보다 적으면 결산할 게 없다
    static let minRuns = 3
    /// 가장 빠른 세션 후보의 최소 거리(km) — 1~2km 짧은 질주가 "가장 빠른 세션"을 차지하지 않게
    static let fastestMinKm = 3.0
    /// 마무리 문장의 증감 분기 폭(%) — 주간 거리 10% 규칙과 같은 폭. 이 안이면 "꾸준함" 문장
    static let closingDeltaPct = 10.0

    /// 결산 가능 여부 — 기간 안 러닝 3회 이상. 화면의 버튼 활성·홈 카드 노출이 compute와 같은 기준을 쓴다
    static func hasEnoughRuns(_ period: RecapPeriod, runs: [RunSummary],
                              calendar: Calendar = .current) -> Bool {
        sessions(in: period.interval(calendar: calendar), from: runs).count >= minRuns
    }

    static func compute(period: RecapPeriod,
                        runs: [RunSummary],
                        efforts: BestEffortTable,
                        histograms: [UUID: ZoneHistogram],
                        profile: HeartRateProfile,
                        level: RunnerLevel,
                        now: Date,
                        calendar: Calendar = .current) -> Recap? {
        let interval = period.interval(calendar: calendar)
        let inPeriod = sessions(in: interval, from: runs)
        guard inPeriod.count >= minRuns else { return nil }

        let totalKm = inPeriod.compactMap(\.distanceKm).reduce(0, +)
        let totals = Recap.Totals(distanceKm: totalKm,
                                  count: inPeriod.count,
                                  durationSec: inPeriod.map(\.durationSec).reduce(0, +),
                                  showsDistance: ReportGate.showsNumbers(.distance, level: level))

        // 하이라이트 — 동률이면 먼저 달린 세션
        let ordered = inPeriod.sorted { $0.start < $1.start }
        let longest = ordered.filter { ($0.distanceKm ?? 0) > 0 }
            .reduce(nil as RunSummary?) { best, run in
                (best?.distanceKm ?? 0) >= (run.distanceKm ?? 0) ? best : run
            }
        let fastest = ordered.filter { ($0.distanceKm ?? 0) >= fastestMinKm && $0.paceSecPerKm != nil }
            .reduce(nil as RunSummary?) { best, run in
                guard let best else { return run }
                return best.paceSecPerKm! <= run.paceSecPerKm! ? best : run
            }
        let highlights = (longest == nil && fastest == nil)
            ? nil : Recap.Highlights(longest: longest, fastest: fastest)

        // 새 기록 — 기간 끝까지의 전체 기록으로 PR을 뽑고, 그 PR을 세운 세션이 기간 안인 것만
        let records = PersonalRecords.compute(runs: runs.filter { $0.start < interval.end },
                                              efforts: efforts)
            .filter { $0.run.start >= interval.start && $0.run.start < interval.end }

        // 강도 배분 — 월간만, 레벨 게이트(런린이 미노출)는 리포트 탭 카드와 같다
        var intensity: Recap.Intensity?
        if case .month = period, ReportGate.shows(.zoneBalance, level: level),
           let share = ZoneDistributionEngine.easyShare(histograms: histograms, runs: runs,
                                                        profile: profile, in: interval) {
            intensity = Recap.Intensity(easyShare: share.share, tone: share.tone, sessions: share.sessions)
        }

        // 직전 기간 — 진행 중인 기간은 직전 기간의 같은 경과 일수까지만 견준다 (MonthlyStats와 같은 방식).
        // 전체와 견주면 기간 초반에는 무조건 크게 줄어든 것처럼 보인다
        let previousInterval = period.previous(calendar: calendar).interval(calendar: calendar)
        var previousEnd = previousInterval.end
        if interval.contains(now) {
            let elapsedDays = (calendar.dateComponents([.day], from: interval.start, to: now).day ?? 0) + 1
            previousEnd = min(calendar.date(byAdding: .day, value: elapsedDays, to: previousInterval.start)!,
                              previousInterval.end)
        }
        let previousKm = runs.filter { $0.start >= previousInterval.start && $0.start < previousEnd }
            .compactMap(\.distanceKm).reduce(0, +)
        let deltaPct = previousKm >= 3 ? (totalKm - previousKm) / previousKm * 100 : nil

        let isFirst = !runs.contains { $0.start < interval.start }
        let label = periodLabel(period, calendar: calendar)
        return Recap(title: label + " 결산",
                     periodLabel: label,
                     period: period,
                     totals: totals,
                     highlights: highlights,
                     records: records,
                     intensity: intensity,
                     closingLine: closingLine(period: period, isFirst: isFirst,
                                              deltaPct: deltaPct, count: inPeriod.count),
                     deltaPct: deltaPct)
    }

    /// 마무리 한 줄 — 첫 결산 > 증가 > 감소 > 꾸준함 순으로 하나만 (기획서 v0.4 §4.11 존댓말 위트 톤).
    /// 증감은 ±10% 밖일 때만 말한다 — 그 안이거나 비교할 수 없으면 횟수로 꾸준함을 칭찬한다
    static func closingLine(period: RecapPeriod, isFirst: Bool, deltaPct: Double?, count: Int) -> String {
        let isMonth: Bool
        if case .month = period { isMonth = true } else { isMonth = false }
        if isFirst {
            return "첫 결산입니다. 시작이 반이라는 말, 오늘은 믿어 보겠습니다."
        }
        if let deltaPct, deltaPct >= closingDeltaPct {
            return "\(isMonth ? "지난달" : "지난해")보다 \(Int(deltaPct.rounded()))% 더 달리셨습니다. "
                + "새가 살찌는 소리가 들립니다."
        }
        if let deltaPct, deltaPct <= -closingDeltaPct {
            return "쉬어 가는 \(isMonth ? "달" : "해")도 훈련입니다. 몸이 고마워하고 있을 겁니다."
        }
        return "\(count)번을 달리셨습니다. 꾸준함은 배신하지 않습니다 — 새도 마찬가지고요."
    }

    // MARK: 홈 카드 노출

    /// 홈 결산 카드 — 매월 1~7일엔 지난달, 12월 25일~1월 7일엔 올해(1월이면 지난해).
    /// 열어 보거나 닫은 기간(저장 키가 같음)은 뺀다. 순서는 월간 → 연간.
    /// 표본 가드(`hasEnoughRuns`)는 러닝 목록을 가진 화면이 따로 건다
    static func promptKinds(now: Date, dismissedMonth: String, dismissedYear: String,
                            calendar: Calendar = .current) -> [RecapPeriod] {
        let day = calendar.component(.day, from: now)
        let month = calendar.component(.month, from: now)
        let thisMonth = calendar.dateInterval(of: .month, for: now)!.start
        let thisYear = calendar.dateInterval(of: .year, for: now)!.start

        var kinds: [RecapPeriod] = []
        if day <= 7 {
            let last = RecapPeriod.month(calendar.date(byAdding: .month, value: -1, to: thisMonth)!)
            if dismissKey(for: last, calendar: calendar) != dismissedMonth { kinds.append(last) }
        }
        let yearly: RecapPeriod? = (month == 12 && day >= 25) ? .year(thisYear)
            : (month == 1 && day <= 7) ? .year(calendar.date(byAdding: .year, value: -1, to: thisYear)!)
            : nil
        if let yearly, dismissKey(for: yearly, calendar: calendar) != dismissedYear {
            kinds.append(yearly)
        }
        return kinds
    }

    /// 닫힘 저장 키 — 월간 "yyyy-MM", 연간 "yyyy" (RecapKey)
    static func dismissKey(for period: RecapPeriod, calendar: Calendar = .current) -> String {
        let start = period.interval(calendar: calendar).start
        let year = calendar.component(.year, from: start)
        switch period {
        case .month: return String(format: "%04d-%02d", year, calendar.component(.month, from: start))
        case .year: return String(format: "%04d", year)
        }
    }

    // MARK: 내부

    /// "2026년 8월" / "2026년"
    static func periodLabel(_ period: RecapPeriod, calendar: Calendar = .current) -> String {
        let start = period.interval(calendar: calendar).start
        let year = calendar.component(.year, from: start)
        switch period {
        case .month: return "\(year)년 \(calendar.component(.month, from: start))월"
        case .year: return "\(year)년"
        }
    }

    /// 기간 [start, end) 안의 러닝 — 다음 기간 자정 시작 세션이 두 기간에 겹치지 않게 끝은 뺀다
    private static func sessions(in interval: DateInterval, from runs: [RunSummary]) -> [RunSummary] {
        runs.filter { $0.start >= interval.start && $0.start < interval.end }
    }
}
