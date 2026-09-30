import Foundation

/// 훈련 계획 캘린더 엔진 (이슈 #189) — 목표 대회 날짜에서 역산한 주차별 처방.
/// 순수 로직: Foundation만 쓰고 now·레벨·달력을 값으로 주입받아 결정론적이다.
///
/// 새 산식이 아니라 `TrainingGuideEngine.guide()`의 **이번 주** 처방 가정을 미래 주로
/// 그대로 늘린 것이다 — 그래서 현재 주의 단계·주간 거리는 guide()와 항상 같다 (테스트로 고정).
/// - 단계: 주 k(현재 주 = 0)의 남은 일수를 `max(0, daysToRace − 7k)`로 보고
///   `TrainingGuideEngine.phase`에 넣는다 (피크 3주·강화 4주·테이퍼 종목별 — guide()와 같은 가정).
///   대회 주간은 대회 날짜가 속한 마지막 달력 주 하나만이다 — 그 앞 주는 남은 일수를 7일 이상으로 받친다.
/// - 볼륨: 만성 부하(최근 28일 km ÷ 4)에서 10% 룰로 주마다 ×1.1씩 올리고
///   종목·레벨별 피크 주간 거리(`peakWeeklyKm`)에서 멈춘다 (10% 룰 — Gabbett 2016, guide()와 같은 상한).
///   주 k 범위 = (k == 0 ? 만성 부하 : 전 주 상한) … min(만성 부하 × 1.1^(k+1), 피크).
///   현재 주는 guide()의 `만성 부하 … min(만성 부하 × 1.1, 피크)`와 같다.
/// - 테이퍼: 마지막 기초·강화·피크 주의 상한(없으면 min(만성 부하, 피크))의 60~70%,
///   대회 주간은 40~50% — guide()와 같은 배수 (일반 플랜 관례, 가정).
/// - LSD: 주간 하한 × 25% … 상한 × 35%, 대회 주간은 0 — guide()의 배터리 비제한 경로와 같다.
///   계획은 배터리를 보지 않는다 — 배터리는 '오늘' 신호이고 계획은 몇 주짜리 구조라서다.
///   오늘 컨디션에 따른 하향은 홈 판정·이번 주 처방이 맡는다.
/// - 퀄리티: `TrainingGuideEngine.qualityMix(phase:level:batteryLimited: false)`.
///
/// 가드 (guide()와 같음 + 지평): 가장 오래된 러닝이 21일 전보다 최근이거나 만성 부하가
/// 주 3km 미만이면 nil. 대회가 지났거나 24주보다 멀어도 nil — 24주 뒤 볼륨을 오늘의
/// 만성 부하로 외삽하는 건 근거가 없다. "틀린 인사이트는 없느니만 못하다."

struct TrainingPlan: Equatable {
    struct Week: Equatable, Identifiable {
        /// ISO 8601 달력 주(월요일 시작) — ZoneDistributionEngine.compute와 같은 경계
        let weekStart: Date
        /// "10월 2째주" — Format.weekLabel(weekStart:)
        let label: String
        /// 지난 주(계획 없음)는 nil
        let phase: TrainingGuide.Phase?
        /// 계획 주간 거리(km) — 지난 주는 nil
        let weeklyKmLow: Double?
        let weeklyKmHigh: Double?
        /// 롱런(LSD) 거리(km) — 지난 주는 nil, 대회 주간은 0
        let lsdKmLow: Double?
        let lsdKmHigh: Double?
        /// 퀄리티 세션 권장 횟수 — 지난 주는 0
        let tempoCount: Int
        let intervalCount: Int
        /// 실제 달린 거리(km) — 지난 주는 그 주 합계, 이번 주는 지금까지 합계, 미래 주는 nil
        let actualKm: Double?
        let isCurrent: Bool

        var id: Date { weekStart }
    }

    let race: RaceDistance
    let raceDate: Date
    /// D-day (대회 당일 = 0)
    let daysToRace: Int
    /// 이 종목·레벨의 권장 피크 주간 거리 — 점증이 여기서 멈춘다
    let peakWeeklyKm: Double
    /// 오래된 → 대회 주간. 지난 주(최대 4개) + 이번 주 + 미래 주
    let weeks: [Week]
}

enum TrainingPlanEngine {
    /// 대회가 이보다 멀면 nil — 24주 뒤 볼륨을 오늘 만성 부하로 외삽하면 근거가 없다
    static let maxHorizonWeeks = 24
    /// 나란히 보여줄 지난 주 수
    static let pastWeeks = 4

    static func plan(runs: [RunSummary], race: RaceDistance, level: RunnerLevel,
                     raceDate: Date, now: Date, calendar: Calendar = .current) -> TrainingPlan? {
        // 가드: guide()와 같은 3주(21일)·만성 부하 주 3km (이슈 #49)
        guard let oldest = runs.map(\.start).min(),
              oldest <= now.addingTimeInterval(-21 * 86_400) else { return nil }
        let chronic = km(runs, from: now.addingTimeInterval(-28 * 86_400), to: now, inclusive: false) / 4
        guard chronic >= 3 else { return nil }

        // 주 경계는 Format.weekLabel과 같은 ISO 8601 달력 주 (ZoneDistributionEngine과 같은 방식)
        var iso = Calendar(identifier: .iso8601)
        iso.timeZone = calendar.timeZone
        // 날짜 차이(일) — 자정 경계 기준 (TrainingGuideEngine.days와 같은 방식, 그쪽은 private)
        guard let daysToRace = iso.dateComponents([.day],
                                                  from: iso.startOfDay(for: now),
                                                  to: iso.startOfDay(for: raceDate)).day,
              daysToRace >= 0, daysToRace / 7 <= maxHorizonWeeks,
              let currentWeekStart = iso.dateInterval(of: .weekOfYear, for: now)?.start,
              let raceWeekStart = iso.dateInterval(of: .weekOfYear, for: raceDate)?.start
        else { return nil }

        func weekStart(_ offset: Int) -> Date {
            iso.date(byAdding: .weekOfYear, value: offset, to: currentWeekStart)!
        }

        // 지난 주 — 기록 시작 이후에 시작한 주만. 기록 없는 주가 "실제 0km"로 오해되지 않게
        let past = (1...pastWeeks).reversed().compactMap { back -> TrainingPlan.Week? in
            let start = weekStart(-back)
            guard start >= oldest else { return nil }
            return TrainingPlan.Week(weekStart: start, label: Format.weekLabel(weekStart: start),
                                     phase: nil,
                                     weeklyKmLow: nil, weeklyKmHigh: nil,
                                     lsdKmLow: nil, lsdKmHigh: nil,
                                     tempoCount: 0, intervalCount: 0,
                                     actualKm: km(runs, from: start, to: weekStart(-back + 1),
                                                  inclusive: false),
                                     isCurrent: false)
        }

        // 이번 주부터 대회 날짜가 속한 주까지
        let futureCount = (iso.dateComponents([.weekOfYear], from: currentWeekStart,
                                              to: raceWeekStart).weekOfYear ?? 0) + 1
        let peak = TrainingGuideEngine.peakWeeklyKm(race: race, level: level)
        var lastBuiltHigh: Double?   // 마지막 기초·강화·피크 주의 상한 — 테이퍼 감량의 기준
        let future = (0..<futureCount).map { k -> TrainingPlan.Week in
            let start = weekStart(k)
            // 대회 주간은 마지막(대회 날짜가 속한) 달력 주 하나뿐이다 — 대회가 오늘보다 이른 요일이면
            // 굴러가는 식 `daysToRace − 7k`가 그 앞 주에서도 6일 이하로 떨어져 대회 주간이 둘이 되므로
            // 마지막 주가 아니면 7일 이상으로 받쳐 단계가 테이퍼 밑으로 내려가지 않게 한다
            let isLast = k == futureCount - 1
            let remaining = isLast ? max(0, daysToRace - 7 * k) : max(7, daysToRace - 7 * k)
            let phase = TrainingGuideEngine.phase(daysToRace: remaining, race: race)
            let (low, high): (Double, Double)
            switch phase {
            case .taper:
                let built = lastBuiltHigh ?? min(chronic, peak)
                (low, high) = (built * 0.6, built * 0.7)
            case .raceWeek:
                let built = lastBuiltHigh ?? min(chronic, peak)
                (low, high) = (built * 0.4, built * 0.5)
            default:
                // 10% 룰 점증 — 주 k 상한 = min(만성 × 1.1^(k+1), 피크), 하한 = 전 주 상한
                let built = min(chronic * pow(1.1, Double(k + 1)), peak)
                let floor = k == 0 ? chronic : min(chronic * pow(1.1, Double(k)), peak)
                (low, high) = (floor, max(floor, built))
                lastBuiltHigh = high
            }
            let quality = TrainingGuideEngine.qualityMix(phase: phase, level: level,
                                                         batteryLimited: false)
            return TrainingPlan.Week(weekStart: start, label: Format.weekLabel(weekStart: start),
                                     phase: phase,
                                     weeklyKmLow: low, weeklyKmHigh: high,
                                     lsdKmLow: phase == .raceWeek ? 0 : low * 0.25,
                                     lsdKmHigh: phase == .raceWeek ? 0 : high * 0.35,
                                     tempoCount: quality.tempo, intervalCount: quality.interval,
                                     actualKm: k == 0 ? km(runs, from: start, to: now, inclusive: true) : nil,
                                     isCurrent: k == 0)
        }

        return TrainingPlan(race: race, raceDate: raceDate, daysToRace: daysToRace,
                            peakWeeklyKm: peak, weeks: past + future)
    }

    /// [from, to) 또는 [from, to] 창에 시작한 러닝의 거리 합(km) — 거리 없는 세션은 건너뛴다
    private static func km(_ runs: [RunSummary], from: Date, to: Date, inclusive: Bool) -> Double {
        runs.filter { $0.start >= from && (inclusive ? $0.start <= to : $0.start < to) }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }
}
