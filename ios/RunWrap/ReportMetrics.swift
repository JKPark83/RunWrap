import Foundation

/// 주간 리포트 화면용 구조화 지표 — Insight(문장)와 같은 산식·가드를 쓰되,
/// 카드/차트가 그릴 수 있도록 수치를 그대로 노출한다.
///
/// 산식과 미노출 가드는 ReportEngine 주석 참조. 여기서도 동일하게,
/// 표본이 부족하면 해당 카드를 아예 만들지 않는다(nil).
struct WeeklyReport {
    struct WeekBar: Identifiable {
        let label: String     // "W32", "1주" 등
        let km: Double
        let isCurrent: Bool
        let index: Int
        var id: Int { index }
    }

    struct DistanceCard {
        let tone: RRTone
        let weeks: [WeekBar]       // 최근 6개 달력 주 (차트용)
        let recent7Km: Double      // 롤링 최근 7일 (판정 기준)
        let previous7Km: Double    // 롤링 이전 7일
        let capKm: Double          // 이전 7일 × 1.1 (10% 룰 상한)
        let changePct: Double
        var overKm: Double { recent7Km - capKm }
    }

    struct AcwrCard {
        let tone: RRTone
        let acute: Double          // 최근 7일 km
        let chronic: Double        // 4주 주평균 km
        let ratio: Double
    }

    struct EfficiencyCard {
        let tone: RRTone
        let points: [Double]       // 주별 평균 EF (오래된 → 최신, 최대 8주)
        let recentEF: Double       // 최근 2주 평균
        let previousEF: Double     // 직전 2주 평균
        let changePct: Double
        let referenceHR: Double    // 표본 러닝의 평균 심박 (페이스 환산 기준)
        var recentPaceSec: Double { 60_000 / (recentEF * referenceHR) }
        var previousPaceSec: Double { 60_000 / (previousEF * referenceHR) }
        var paceDeltaSec: Double { previousPaceSec - recentPaceSec }  // 양수면 빨라짐
    }

    let weekNumber: Int
    let dateRange: String          // "8.3 – 8.9"
    let distance: DistanceCard?
    let acwr: AcwrCard?
    let efficiency: EfficiencyCard?

    var isEmpty: Bool { distance == nil && acwr == nil && efficiency == nil }

    /// 상세 화면 첫 문장 — 가장 나쁜 톤 기준으로 한 주를 요약한다
    var headline: String {
        let tones = [distance?.tone, acwr?.tone, efficiency?.tone].compactMap { $0 }
        if tones.contains(.overload) { return "몸보다 훈련량이 앞서 나간 한 주였습니다." }
        if tones.contains(.caution) { return "조금 무리했거나 리듬이 흔들린 한 주였습니다." }
        if tones.contains(.improving) { return "몸이 좋아지고 있는 한 주였습니다." }
        return "안정적으로 리듬을 지킨 한 주였습니다."
    }

    /// 다음 주 제안 — 과부하면 안전 상한을 계산해 감량 폭을 제시한다
    var suggestion: String? {
        guard let d = distance else { return nil }
        let overloaded = d.tone == .overload || (acwr.map { $0.ratio > 1.3 } ?? false)
        if overloaded {
            var upper = d.capKm
            if let a = acwr { upper = min(upper, a.chronic * 1.3) }
            let lower = upper * 0.93
            return String(format: "주간 %.0f–%.0f km로 줄이면 안전 구간으로 돌아옵니다. 롱런 하나를 회복 주행으로 바꾸면 충분해요.", lower, upper)
        }
        return "지금 리듬 그대로 이어가면 됩니다. 다음 주에도 증가 폭 10% 이내를 지켜보세요."
    }
}

extension ReportEngine {
    func weeklyReport(from runs: [RunSummary]) -> WeeklyReport {
        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작
        calendar.timeZone = .current
        let week = calendar.dateInterval(of: .weekOfYear, for: now)
            ?? DateInterval(start: now, duration: 7 * 86_400)
        let range = "\(shortDate(week.start)) – \(shortDate(week.end.addingTimeInterval(-1)))"

        return WeeklyReport(weekNumber: calendar.component(.weekOfYear, from: now),
                            dateRange: range,
                            distance: distanceCard(runs, calendar: calendar, currentWeek: week),
                            acwr: acwrCard(runs),
                            efficiency: efficiencyCard(runs))
    }

    // MARK: - 카드 계산

    private func distanceCard(_ runs: [RunSummary], calendar: Calendar,
                              currentWeek: DateInterval) -> WeeklyReport.DistanceCard? {
        let recent = windowKm(runs, fromDaysAgo: 7, toDaysAgo: 0)
        let previous = windowKm(runs, fromDaysAgo: 14, toDaysAgo: 7)
        guard previous >= 3 else { return nil }  // ReportEngine과 동일 가드
        let change = (recent - previous) / previous * 100

        // 차트: 최근 6개 달력 주 합계 (판정은 롤링 7일, 차트는 달력 주 — 라벨이 명확하다)
        let weeks: [WeeklyReport.WeekBar] = (0..<6).reversed().enumerated().map { index, back in
            let start = calendar.date(byAdding: .weekOfYear, value: -back, to: currentWeek.start)!
            let end = calendar.date(byAdding: .weekOfYear, value: 1, to: start)!
            let km = runs.filter { $0.start >= start && $0.start < end }
                .compactMap(\.distanceKm).reduce(0, +)
            return WeeklyReport.WeekBar(label: "W\(calendar.component(.weekOfYear, from: start))",
                                        km: km, isCurrent: back == 0, index: index)
        }

        let tone: RRTone = change >= 10 ? .overload : (change < -30 ? .caution : .steady)
        return WeeklyReport.DistanceCard(tone: tone, weeks: weeks,
                                         recent7Km: recent, previous7Km: previous,
                                         capKm: previous * 1.1, changePct: change)
    }

    private func acwrCard(_ runs: [RunSummary]) -> WeeklyReport.AcwrCard? {
        guard let oldest = runs.map(\.start).min(),
              oldest <= now.addingTimeInterval(-21 * 86_400) else { return nil }
        let acute = windowKm(runs, fromDaysAgo: 7, toDaysAgo: 0)
        let chronic = windowKm(runs, fromDaysAgo: 28, toDaysAgo: 0) / 4
        guard chronic >= 3 else { return nil }
        let ratio = acute / chronic
        let tone: RRTone = ratio >= 1.5 ? .overload
            : ratio >= 1.3 ? .caution
            : ratio >= 0.8 ? .steady
            : .caution  // 급감도 리듬 관점에서는 주의
        return WeeklyReport.AcwrCard(tone: tone, acute: acute, chronic: chronic, ratio: ratio)
    }

    private func efficiencyCard(_ runs: [RunSummary]) -> WeeklyReport.EfficiencyCard? {
        func ef(_ run: RunSummary) -> Double? {
            guard let pace = run.paceSecPerKm, let hr = run.avgHeartRate, hr > 0 else { return nil }
            return (60_000 / pace) / hr
        }
        let recentRuns = runs.filter { $0.start >= day(-14) }
        let previousRuns = runs.filter { $0.start >= day(-28) && $0.start < day(-14) }
        let recent = recentRuns.compactMap(ef)
        let previous = previousRuns.compactMap(ef)
        guard recent.count >= 3, previous.count >= 3 else { return nil }

        let hrSamples = (recentRuns + previousRuns).compactMap(\.avgHeartRate)
        let referenceHR = hrSamples.reduce(0, +) / Double(hrSamples.count)

        // 라인 차트: 최근 8주를 주 단위로 평균 (표본 없는 주는 건너뛴다)
        let points: [Double] = (0..<8).reversed().compactMap { back in
            let samples = runs.filter { $0.start >= day(-7 * (back + 1)) && $0.start < day(-7 * back) }
                .compactMap(ef)
            guard !samples.isEmpty else { return nil }
            return samples.reduce(0, +) / Double(samples.count)
        }

        let recentAvg = recent.reduce(0, +) / Double(recent.count)
        let previousAvg = previous.reduce(0, +) / Double(previous.count)
        let change = (recentAvg - previousAvg) / previousAvg * 100
        let tone: RRTone = change >= 3 ? .improving : (change < -3 ? .caution : .steady)
        return WeeklyReport.EfficiencyCard(tone: tone, points: points,
                                           recentEF: recentAvg, previousEF: previousAvg,
                                           changePct: change, referenceHR: referenceHR)
    }

    // MARK: - 헬퍼 (ReportEngine의 private 헬퍼와 동일 정의)

    private func day(_ offset: Int) -> Date {
        now.addingTimeInterval(TimeInterval(offset) * 86_400)
    }

    private func windowKm(_ runs: [RunSummary], fromDaysAgo: Int, toDaysAgo: Int) -> Double {
        runs.filter { $0.start >= day(-fromDaysAgo) && $0.start < day(-toDaysAgo) }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }

    private func shortDate(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.month, .day], from: date)
        return "\(c.month ?? 0).\(c.day ?? 0)"
    }
}

// MARK: - 통계 탭 (월간)

/// 통계 화면의 월 단위 집계
struct MonthlyStats {
    let monthLabel: String            // "2026년 8월"
    let totalKm: Double
    let deltaPct: Double?             // 전월 대비 누적 거리 증감 (전월 기록 있을 때만)
    let weeks: [WeeklyReport.WeekBar] // 월 내 주차 합계 ("1주"…)
    let avgPaceSec: Double?
    let paceDeltaSec: Double?         // 전월 대비 (음수 = 빨라짐)
    let avgHeartRate: Double?
    let heartRateDelta: Double?
    let count: Int
    let perWeek: Double
    let totalDurationSec: Double
    let pacePoints: [Double]          // 러닝별 페이스 (오래된 → 최신, 스파크라인)
    let heartRatePoints: [Double]
    let runs: [RunSummary]            // 해당 월, 최신순

    /// runs 전체에서 기록이 있는 월 목록 (최신 먼저)
    static func availableMonths(in runs: [RunSummary], now: Date = Date()) -> [Date] {
        let calendar = Calendar.current
        guard let oldest = runs.map(\.start).min() else {
            return [calendar.dateInterval(of: .month, for: now)!.start]
        }
        var months: [Date] = []
        var cursor = calendar.dateInterval(of: .month, for: now)!.start
        let first = calendar.dateInterval(of: .month, for: oldest)!.start
        while cursor >= first {
            months.append(cursor)
            cursor = calendar.date(byAdding: .month, value: -1, to: cursor)!
        }
        return months
    }

    static func compute(runs: [RunSummary], month: Date) -> MonthlyStats {
        let calendar = Calendar.current
        let interval = calendar.dateInterval(of: .month, for: month)!
        let inMonth = runs.filter { interval.contains($0.start) }.sorted { $0.start > $1.start }
        let previousMonth = calendar.date(byAdding: .month, value: -1, to: month)!
        let previousInterval = calendar.dateInterval(of: .month, for: previousMonth)!
        let inPrevious = runs.filter { previousInterval.contains($0.start) }

        func totalKm(_ list: [RunSummary]) -> Double {
            list.compactMap(\.distanceKm).reduce(0, +)
        }
        /// 시간 가중 평균 페이스 = 총 시간 ÷ 총 거리
        func avgPace(_ list: [RunSummary]) -> Double? {
            let km = totalKm(list)
            guard km > 0.1 else { return nil }
            let sec = list.filter { $0.distanceKm != nil }.map(\.durationSec).reduce(0, +)
            return sec / km
        }
        func avgHR(_ list: [RunSummary]) -> Double? {
            let samples = list.compactMap(\.avgHeartRate)
            guard !samples.isEmpty else { return nil }
            return samples.reduce(0, +) / Double(samples.count)
        }

        let total = totalKm(inMonth)
        let previousTotal = totalKm(inPrevious)

        // 월 내 주차 (1일부터 7일 단위 — 달력 주 대신 단순 분할이 라벨과 맞다)
        let dayCount = calendar.range(of: .day, in: .month, for: month)!.count
        let weekCount = Int(ceil(Double(dayCount) / 7))
        let weeks: [WeeklyReport.WeekBar] = (0..<weekCount).map { index in
            let start = interval.start.addingTimeInterval(Double(index) * 7 * 86_400)
            let end = min(start.addingTimeInterval(7 * 86_400), interval.end)
            let km = inMonth.filter { $0.start >= start && $0.start < end }
                .compactMap(\.distanceKm).reduce(0, +)
            return WeeklyReport.WeekBar(label: "\(index + 1)주", km: km,
                                        isCurrent: false, index: index)
        }

        let pace = avgPace(inMonth)
        let previousPace = avgPace(inPrevious)
        let hr = avgHR(inMonth)
        let previousHR = avgHR(inPrevious)
        let ordered = inMonth.sorted { $0.start < $1.start }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "yyyy년 M월"

        return MonthlyStats(monthLabel: formatter.string(from: month),
                            totalKm: total,
                            deltaPct: previousTotal >= 3 ? (total - previousTotal) / previousTotal * 100 : nil,
                            weeks: weeks,
                            avgPaceSec: pace,
                            paceDeltaSec: (pace != nil && previousPace != nil) ? pace! - previousPace! : nil,
                            avgHeartRate: hr,
                            heartRateDelta: (hr != nil && previousHR != nil) ? hr! - previousHR! : nil,
                            count: inMonth.count,
                            perWeek: Double(inMonth.count) / (Double(dayCount) / 7),
                            totalDurationSec: inMonth.map(\.durationSec).reduce(0, +),
                            pacePoints: ordered.compactMap(\.paceSecPerKm),
                            heartRatePoints: ordered.compactMap(\.avgHeartRate),
                            runs: inMonth)
    }
}

// MARK: - 러닝 세션 표시 이름

extension RunSummary {
    /// "일요일 롱런" / "화요일 러닝" — 세션 목록·상세 제목
    var displayTitle: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "EEEE"
        let weekday = formatter.string(from: start)
        let kind = (distanceKm ?? 0) >= 15 ? "롱런" : "러닝"
        return "\(weekday) \(kind)"
    }

    /// "1:52:34 · 5′20″/km · 152 bpm"
    var metaLine: String {
        var parts = [Format.duration(durationSec)]
        if let pace = paceSecPerKm { parts.append(Format.paceKm(pace)) }
        if let hr = avgHeartRate { parts.append("\(Int(hr.rounded())) bpm") }
        return parts.joined(separator: " · ")
    }
}
