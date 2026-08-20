import Foundation

/// 런미새 리포트('내 상태') 화면용 구조화 지표 — Insight(문장)와 같은 산식·가드를 쓰되,
/// 카드/차트가 그릴 수 있도록 수치를 그대로 노출한다.
/// 판정·헤더 표기는 달력 주가 아니라 롤링 최근 7일 기준이다 (이슈 #21).
///
/// 산식과 미노출 가드는 ReportEngine 주석 참조. 여기서도 동일하게,
/// 표본이 부족하면 해당 카드를 아예 만들지 않는다(nil).
struct WeeklyReport {
    struct WeekBar: Identifiable {
        let label: String     // "8월 2째주", "1주" 등
        let km: Double
        let isCurrent: Bool
        let index: Int
        var id: Int { index }
    }

    struct DistanceCard {
        let tone: RRTone
        let weeks: [WeekBar]       // 기록 전체 달력 주, 최소 6주 (차트용 — 가로 스크롤)
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
        let points: [Double]       // 주별 평균 EF (오래된 → 최신, 기록 전체 — 표본 없는 주 제외)
        let pointLabels: [String]  // points와 병행 — "3주 전"·"이번 주" (탭 콜아웃용)
        let recentEF: Double       // 최근 2주 평균
        let previousEF: Double     // 직전 2주 평균
        let changePct: Double
        let referenceHR: Double    // 표본 러닝의 평균 심박 (페이스 환산 기준)
        var recentPaceSec: Double { 60_000 / (recentEF * referenceHR) }
        var previousPaceSec: Double { 60_000 / (previousEF * referenceHR) }
        var paceDeltaSec: Double { previousPaceSec - recentPaceSec }  // 양수면 빨라짐
    }

    let dateRange: String          // "8.15 – 8.21" — 롤링 최근 7일 (오늘 포함)
    let distance: DistanceCard?
    let acwr: AcwrCard?
    let efficiency: EfficiencyCard?
    let streakWeeks: Int           // 주 1회 이상 달린 ISO 주 연속 개수
    let weekRunCount: Int          // 최근 7일 러닝 횟수 (streak 카드 캡션·알림 본문용)

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
        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작 — 주별 차트용
        calendar.timeZone = .current
        let week = calendar.dateInterval(of: .weekOfYear, for: now)
            ?? DateInterval(start: now, duration: 7 * 86_400)
        // 헤더 표기·횟수는 달력 주가 아니라 롤링 최근 7일 (이슈 #21) — 오늘 포함
        let range = "\(shortDate(day(-6))) – \(shortDate(now))"

        return WeeklyReport(dateRange: range,
                            distance: distanceCard(runs, calendar: calendar, currentWeek: week),
                            acwr: acwrCard(runs),
                            efficiency: efficiencyCard(runs),
                            streakWeeks: Self.streakWeeks(runs: runs, now: now),
                            weekRunCount: runs.filter { $0.start >= day(-7) && $0.start < now }.count)
    }

    // MARK: - 카드 계산

    private func distanceCard(_ runs: [RunSummary], calendar: Calendar,
                              currentWeek: DateInterval) -> WeeklyReport.DistanceCard? {
        let recent = windowKm(runs, fromDaysAgo: 7, toDaysAgo: 0)
        let previous = windowKm(runs, fromDaysAgo: 14, toDaysAgo: 7)
        guard previous >= 3 else { return nil }  // ReportEngine과 동일 가드
        let change = (recent - previous) / previous * 100

        // 차트: 기록 전체 달력 주 합계 (판정은 롤링 7일, 차트는 달력 주 — 라벨이 명확하다).
        // 지난 주들은 차트의 가로 스크롤로 본다 — 최소 6주는 채워 그린다.
        let span = Self.chartWeekSpan(runs, calendar: calendar, currentWeek: currentWeek)
        let weeks: [WeeklyReport.WeekBar] = (0..<span).reversed().enumerated().map { index, back in
            let start = calendar.date(byAdding: .weekOfYear, value: -back, to: currentWeek.start)!
            let end = calendar.date(byAdding: .weekOfYear, value: 1, to: start)!
            let km = runs.filter { $0.start >= start && $0.start < end }
                .compactMap(\.distanceKm).reduce(0, +)
            return WeeklyReport.WeekBar(label: Format.weekLabel(weekStart: start),
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

        // 라인 차트: 기록 전체를 롤링 7일 단위로 평균 (표본 없는 주는 건너뛴다, 최소 8주 창)
        let weekSpan = runs.map(\.start).min().map {
            max(8, Int(ceil(now.timeIntervalSince($0) / (7 * 86_400))))
        } ?? 8
        var points: [Double] = []
        var pointLabels: [String] = []
        for back in (0..<weekSpan).reversed() {
            let samples = runs.filter { $0.start >= day(-7 * (back + 1)) && $0.start < day(-7 * back) }
                .compactMap(ef)
            guard !samples.isEmpty else { continue }
            points.append(samples.reduce(0, +) / Double(samples.count))
            pointLabels.append(back == 0 ? "이번 주" : "\(back)주 전")
        }

        let recentAvg = recent.reduce(0, +) / Double(recent.count)
        let previousAvg = previous.reduce(0, +) / Double(previous.count)
        let change = (recentAvg - previousAvg) / previousAvg * 100
        let tone: RRTone = change >= 3 ? .improving : (change < -3 ? .caution : .steady)
        return WeeklyReport.EfficiencyCard(tone: tone, points: points, pointLabels: pointLabels,
                                           recentEF: recentAvg, previousEF: previousAvg,
                                           changePct: change, referenceHR: referenceHR)
    }

    // MARK: - 헬퍼 (ReportEngine의 private 헬퍼와 동일 정의)

    /// 막대 차트에 그릴 달력 주 수 — 가장 오래된 기록의 주부터 이번 주까지, 최소 6주
    private static func chartWeekSpan(_ runs: [RunSummary], calendar: Calendar,
                                      currentWeek: DateInterval) -> Int {
        guard let oldest = runs.map(\.start).min(),
              let oldestWeek = calendar.dateInterval(of: .weekOfYear, for: oldest)?.start,
              let back = calendar.dateComponents([.weekOfYear], from: oldestWeek,
                                                 to: currentWeek.start).weekOfYear
        else { return 6 }
        return max(6, back + 1)
    }

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

// MARK: - streak · 주간 추이 골격

extension ReportEngine {
    /// 주 1회 이상 달린 ISO 주가 이어지는 개수 — now가 속한 주부터 거꾸로 센다.
    /// 진행 중인 이번 주는 아직 안 달렸어도 단절로 치지 않는다 — 매주 월요일 아침
    /// streak이 0으로 초기화되는 오판을 막는다 (가정: 지난주까지의 연속은 유지).
    static func streakWeeks(runs: [RunSummary], now: Date) -> Int {
        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작
        calendar.timeZone = .current
        let ranWeeks = Set(runs.compactMap {
            calendar.dateInterval(of: .weekOfYear, for: $0.start)?.start
        })
        guard var cursor = calendar.dateInterval(of: .weekOfYear, for: now)?.start else { return 0 }
        if !ranWeeks.contains(cursor) {
            cursor = calendar.date(byAdding: .weekOfYear, value: -1, to: cursor)!
        }
        var count = 0
        while ranWeeks.contains(cursor) {
            count += 1
            cursor = calendar.date(byAdding: .weekOfYear, value: -1, to: cursor)!
        }
        return count
    }

    /// VO₂max·HRR 추이가 공유하는 골격 — ISO 주 평균 시리즈 + 4주 전 대비 변화량
    private struct WeeklyTrendSeries {
        let points: [Double]       // 주 평균 (오래된 → 최신, 표본 있는 주만)
        let weekStarts: [Date]     // points와 병행하는 주 시작일
        let current: Double        // 최신 주 평균
        let delta: Double?         // spanWeeks주 전 대비 (비교할 주가 있을 때만)
        let spanWeeks: Int
    }

    /// 창 안 표본 3개 미만이면 nil. 4주 전 주에 표본이 없으면 그보다 오래된
    /// 가장 가까운 주와 비교한다 (VO₂max 추정도, HRR도 매주 기록되지 않는다).
    private static func weeklyTrendSeries(samples: [(date: Date, value: Double)],
                                          now: Date, windowDays: Double) -> WeeklyTrendSeries? {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let recent = samples.filter {
            $0.date >= now.addingTimeInterval(-windowDays * 86_400) && $0.date <= now
        }
        guard recent.count >= 3 else { return nil }

        var byWeek: [Date: [Double]] = [:]
        for sample in recent {
            guard let week = calendar.dateInterval(of: .weekOfYear, for: sample.date)?.start
            else { continue }
            byWeek[week, default: []].append(sample.value)
        }
        let weeks = byWeek.keys.sorted()
        let averages = weeks.map { byWeek[$0]!.reduce(0, +) / Double(byWeek[$0]!.count) }
        guard let latestWeek = weeks.last, let current = averages.last else { return nil }

        let target = calendar.date(byAdding: .weekOfYear, value: -4, to: latestWeek)!
        var delta: Double?
        var spanWeeks = 0
        if let baseline = weeks.lastIndex(where: { $0 <= target }) {
            delta = current - averages[baseline]
            spanWeeks = calendar.dateComponents([.weekOfYear],
                                                from: weeks[baseline], to: latestWeek).weekOfYear ?? 4
        }
        return WeeklyTrendSeries(points: averages, weekStarts: weeks,
                                 current: current, delta: delta, spanWeeks: spanWeeks)
    }
}

// MARK: - 심폐 체력 (VO₂max 추이)

/// 심폐 체력 추이 카드 — 워치가 야외 러닝·걷기에서 추정한 VO₂max(ml/kg/min)의
/// 주 단위 평균. 러닝 목적과 무관한 기초 체력 지표라 모든 프로필에 노출한다.
struct Vo2MaxTrend {
    let tone: RRTone
    let points: [Double]       // 주 평균 ml/kg/min (오래된 → 최신, 표본 있는 주만)
    let pointLabels: [String]  // points와 병행 — "8월 2째주" (탭 콜아웃·축 라벨용)
    let current: Double        // 최신 주 평균
    let delta: Double?         // spanWeeks주 전 대비 변화량 (비교할 주가 있을 때만)
    let spanWeeks: Int         // 비교 구간 주 수 — "N주 전보다 …" 문장용
}

extension ReportEngine {
    /// VO₂max 표본 → ISO 주 단위 평균 + 4주 전 대비 변화량.
    /// 미노출 가드(가정): 최근 12주 추정 기록 3회 미만이면 nil.
    /// 워치 추정치는 회당 편차가 있어 주 평균으로 누르고, ±1.0 ml/kg/min 미만
    /// 변화는 유지로 판정한다 (가정 — 오차 범위 안 변동에 톤을 매기지 않는다).
    static func vo2MaxTrend(samples: [(date: Date, value: Double)], now: Date) -> Vo2MaxTrend? {
        guard let series = weeklyTrendSeries(samples: samples, now: now, windowDays: 84)
        else { return nil }
        let tone: RRTone = switch series.delta {
        case .some(let d) where d >= 1.0: .improving
        case .some(let d) where d <= -1.0: .caution
        default: .steady
        }
        return Vo2MaxTrend(tone: tone, points: series.points,
                           pointLabels: series.weekStarts.map { Format.weekLabel(weekStart: $0) },
                           current: series.current, delta: series.delta,
                           spanWeeks: series.spanWeeks)
    }
}

// MARK: - 심박 회복 (HRR 추이)

/// 심박 회복(HRR) 추이 — 야외 러닝 종료 후 1분간 심박 하락 폭(bpm)의 주 단위 평균.
/// 심폐 체력 카드의 보조 라인 재료 (제안 문서 B1). 클수록 회복이 빠르다 (Cole 1999).
/// 차트 없이 한 줄로만 보여줘 points는 두지 않는다.
struct HrrTrend {
    let tone: RRTone
    let current: Double        // 최신 주 평균 bpm
    let delta: Double?         // spanWeeks주 전 대비 변화량 (비교할 주가 있을 때만)
    let spanWeeks: Int
}

extension ReportEngine {
    /// HRR 표본 → ISO 주 단위 평균 + 4주 전 대비 변화량.
    /// 미노출 가드(가정): 최근 12주 기록 3회 미만이면 nil — 야외 러닝을 해야만 쌓이는 지표라
    /// 표본이 적을 때가 많다. 회당 편차가 커 주 평균으로 누르고, ±2 bpm 미만 변화는 유지 판정.
    static func hrrTrend(samples: [(date: Date, value: Double)], now: Date) -> HrrTrend? {
        guard let series = weeklyTrendSeries(samples: samples, now: now, windowDays: 84)
        else { return nil }
        let tone: RRTone = switch series.delta {
        case .some(let d) where d >= 2: .improving
        case .some(let d) where d <= -2: .caution
        default: .steady
        }
        return HrrTrend(tone: tone, current: series.current,
                        delta: series.delta, spanWeeks: series.spanWeeks)
    }
}

// MARK: - 통계 탭 (월간)

/// 통계 화면의 월 단위 집계
struct MonthlyStats {
    let monthLabel: String            // "2026년 8월"
    let totalKm: Double
    let deltaPct: Double?             // 지난달 같은 구간 대비 누적 거리 증감 (지난달 기록 있을 때만)
    /// 비교에 쓴 지난달 구간의 일수 — 진행 중인 달일 때만 값이 있다(nil = 지난달 전체)
    let comparisonDays: Int?
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

    /// 증감 배지 밑에 붙는 비교 기준 — 무엇과 견준 수치인지 밝힌다
    var deltaCaption: String {
        guard let comparisonDays else { return "지난달 대비" }
        return "지난달 1–\(comparisonDays)일 대비"
    }

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

    static func compute(runs: [RunSummary], month: Date, now: Date = Date()) -> MonthlyStats {
        let calendar = Calendar.current
        let interval = calendar.dateInterval(of: .month, for: month)!
        let inMonth = runs.filter { interval.contains($0.start) }.sorted { $0.start > $1.start }

        // 비교 구간 — 진행 중인 달은 지난달의 "오늘과 같은 날짜"까지만 본다.
        // 지난달 전체와 견주면 월초에는 무조건 크게 줄어든 것처럼 보인다.
        let previousMonth = calendar.date(byAdding: .month, value: -1, to: interval.start)!
        let previousInterval = calendar.dateInterval(of: .month, for: previousMonth)!
        let previousEnd: Date
        let comparisonDays: Int?
        if interval.contains(now) {
            let elapsed = (calendar.dateComponents([.day], from: interval.start, to: now).day ?? 0) + 1
            // 지난달이 더 짧으면(예: 3월 30일 → 2월) 그 달 끝에서 멈춘다
            previousEnd = min(calendar.date(byAdding: .day, value: elapsed,
                                            to: previousInterval.start)!,
                              previousInterval.end)
            comparisonDays = calendar.dateComponents([.day], from: previousInterval.start,
                                                     to: previousEnd).day
        } else {
            previousEnd = previousInterval.end
            comparisonDays = nil
        }
        let inPrevious = runs.filter { $0.start >= previousInterval.start && $0.start < previousEnd }

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
                            comparisonDays: comparisonDays,
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
