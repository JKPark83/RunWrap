import Foundation

/// 기간별 심박존 분포 + 80/20 강도 배분 (이슈 #165) — 최근 28일 러닝의 존별 누적 시간.
///
/// 근거: Seiler & Kjerland(2006) — 엘리트 지구력 선수는 훈련 시간의 약 80%를 LT1(1차 젖산 역치)
/// 아래에서 보낸다. 기획서 §4.9의 "장거리/스피드 밸런스 — 80/20 원칙과 비교"를 세션 유형 추정이
/// 아니라 실제 심박 시간으로 본다. Z1+Z2(강도 비율 0.7 미만)를 이지 강도로 친다.
struct ZoneDistribution: Equatable {
    struct WeekBar: Equatable {
        /// 달력 주(ISO 8601, 월요일 시작) 시작일
        let weekStart: Date
        /// "8월 2째주" — Format.weekLabel
        let label: String
        /// Z1~Z5 누적 초
        let zoneSeconds: [Double]
    }

    /// 최근 4개 달력 주, 오래된 → 최신 (세션이 없는 주는 0으로 채운다)
    let weeks: [WeekBar]
    /// 28일 누적 Z1~Z5 비율 (합 1)
    let zoneShare: [Double]
    /// Z1+Z2 비율 — 80/20 판정 기준
    let easyShare: Double
    /// 히스토그램이 있는 28일 창 세션 수
    let sessionCount: Int
    let tone: RRTone
}

enum ZoneDistributionEngine {
    /// 미노출 가드 — 28일 창에 심박 기록이 있는 세션이 이보다 적으면 카드를 내지 않는다.
    /// 주 2회 × 4주: 몇 번의 세션으로 강도 배분을 단정하면 "틀린 인사이트"가 된다
    static let minSessions = 8
    /// 이지 비율 판정 경계 — 0.80 이상 유지(Seiler 80/20), 0.70 이상 주의, 그 밑은 과부하
    static let steadyEasyShare = 0.80
    static let cautionEasyShare = 0.70

    /// 존 경계는 표시 시점의 `profile`로 적용한다 — 히스토그램은 bpm 단위라 심박 기준을 바꿔도 다시 계산된다.
    /// 존 매핑은 세션 상세(TrainingGuideEngine.heartRateZones)와 같은 `zoneIndex(intensity:)`.
    /// 창은 28일 전 자정 ~ now. 주 막대는 최근 4개 달력 주라 4주 전 주의 일부(최대 6일)는
    /// 누적 비율에만 들어가고 막대에는 없다.
    static func compute(histograms: [UUID: ZoneHistogram],
                        runs: [RunSummary],
                        profile: HeartRateProfile,
                        now: Date,
                        calendar: Calendar = .current) -> ZoneDistribution? {
        let windowStart = calendar.startOfDay(for: now.addingTimeInterval(-28 * 86_400))
        // 빈 히스토그램(심박 샘플 없는 세션)은 표본으로 세지 않는다
        let sessions = runs
            .filter { $0.start >= windowStart && $0.start <= now }
            .compactMap { run -> (start: Date, zones: [Double])? in
                guard let histogram = histograms[run.id], !histogram.secondsByBpm.isEmpty else { return nil }
                return (run.start, zoneSeconds(histogram, profile: profile))
            }
        guard sessions.count >= minSessions else { return nil }

        let totals = sum(sessions.map(\.zones))
        let total = totals.reduce(0, +)
        guard total > 0 else { return nil }
        let easyShare = (totals[0] + totals[1]) / total

        // 주 경계는 Format.weekLabel과 같은 ISO 8601 달력 주 (ReportEngine.weekBars와 같은 방식)
        var iso = Calendar(identifier: .iso8601)
        iso.timeZone = calendar.timeZone
        let currentWeekStart = iso.dateInterval(of: .weekOfYear, for: now)?.start ?? now
        let weeks = (0..<4).reversed().map { back -> ZoneDistribution.WeekBar in
            let start = iso.date(byAdding: .weekOfYear, value: -back, to: currentWeekStart)!
            let end = iso.date(byAdding: .weekOfYear, value: 1, to: start)!
            let inWeek = sessions.filter { $0.start >= start && $0.start < end }
            return .init(weekStart: start, label: Format.weekLabel(weekStart: start),
                         zoneSeconds: sum(inWeek.map(\.zones)))
        }

        let tone: RRTone = easyShare >= steadyEasyShare ? .steady
            : easyShare >= cautionEasyShare ? .caution
            : .overload
        return ZoneDistribution(weeks: weeks,
                                zoneShare: totals.map { $0 / total },
                                easyShare: easyShare,
                                sessionCount: sessions.count,
                                tone: tone)
    }

    /// 히스토그램 → Z1~Z5 초
    private static func zoneSeconds(_ histogram: ZoneHistogram, profile: HeartRateProfile) -> [Double] {
        var seconds = [Double](repeating: 0, count: 5)
        for (bpm, sec) in histogram.secondsByBpm {
            seconds[TrainingGuideEngine.zoneIndex(intensity: profile.intensity(of: Double(bpm)))] += sec
        }
        return seconds
    }

    /// 세션별 Z1~Z5 초를 존별로 합산 — 비어 있으면 0 다섯 개
    private static func sum(_ zones: [[Double]]) -> [Double] {
        zones.reduce(into: [Double](repeating: 0, count: 5)) { acc, session in
            for zone in 0..<5 { acc[zone] += session[zone] }
        }
    }
}
