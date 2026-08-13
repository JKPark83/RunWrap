import Foundation

/// 성장 시스템 단계 — 알에서 나는 새까지 6단계 (기획서 §5)
///
/// 임계값은 누적 XP 기준. `next`가 nil이면 성조(사이클 완성)다.
enum GrowthStage: Int, CaseIterable {
    case egg = 1
    case crackedEgg = 2
    case hatchling = 3
    case fledgling = 4
    case flapping = 5
    case flying = 6

    var label: String {
        switch self {
        case .egg: "알"
        case .crackedEgg: "금 간 알"
        case .hatchling: "부화"
        case .fledgling: "어린 새"
        case .flapping: "날갯짓"
        case .flying: "나는 새"
        }
    }

    /// 이 단계 진입에 필요한 누적 XP (기획서 §5 표)
    var threshold: Int {
        switch self {
        case .egg: 0
        case .crackedEgg: 50
        case .hatchling: 200
        case .fledgling: 500
        case .flapping: 1_000
        case .flying: 1_800
        }
    }

    var next: GrowthStage? {
        GrowthStage(rawValue: rawValue + 1)
    }
}

/// 홈 화면이 그리는 성장 상태 스냅샷 — GrowthEngine.state()의 반환값
struct GrowthState {
    let xp: Int
    let stage: GrowthStage
    /// 현재 단계 임계값 이후로 쌓인 XP (게이지 분자)
    let xpIntoStage: Int
    /// 다음 단계까지 남은 XP. 성조(flying)면 nil
    let xpToNextStage: Int?
    /// 게이지 채움 비율 0...1. 성조는 1.0
    let progress: Double
    /// 마지막 러닝으로부터 7일 이상 지났는지 — 새 표정 분기용 (기획서 §5)
    let isSulky: Bool
    /// 마지막 러닝으로부터 경과일. 러닝이 아예 없으면 nil
    let daysSinceLastRun: Int?
}

/// 성장 시스템 — 알에서 나는 새까지 XP·단계를 계산하는 순수 로직 (기획서 §5)
///
/// **XP 원장을 저장하지 않는다.** `cycleStartedAt` 이후의 HealthKit 러닝 이력에서
/// 매번 결정론적으로 재계산한다 — 저장·동기화 문제가 사라진다.
/// 저장할 값은 주간 목표·사이클 시작 시각·이번 사이클 최고 단계·수집 도감뿐이며,
/// 이 엔진은 그 저장값(cycleStartedAt, maxStage)을 입력으로만 받는다.
enum GrowthEngine {
    /// 러닝 1회 완료(1km 이상) 기본 보상
    private static let baseXp = 10
    /// 거리 1km당 보너스
    private static let perKmXp = 1
    /// 세션당 거리 보너스 상한 (21km ≈ 하프 지점, 그 이상은 과부하 비보상)
    private static let perSessionDistanceCap = 21
    /// 주간 목표 달성 보너스
    private static let weeklyGoalXp = 30
    /// 주간 목표 4주 연속 달성 보너스 (연속 4주 단위마다 1회)
    private static let streakBonusXp = 50
    private static let streakBonusIntervalWeeks = 4
    /// 몰아 뛰기 방지 — 하루(사용자 캘린더 기준) XP 상한
    private static let dailyXpCap = 40

    /// 러닝 이력·사이클 시작 시각·주간 목표로 현재 성장 상태를 산출한다.
    ///
    /// - Parameters:
    ///   - runs: 전체 러닝 이력 (온보딩 이전 이력 포함해도 무방 — cycleStartedAt으로 걸러낸다)
    ///   - cycleStartedAt: 이번 사이클(첫 사이클 = 온보딩) 시작 시각. 이전 러닝은 XP 미산입
    ///   - maxStage: 저장된 이번 사이클 최고 도달 단계 (rawValue). "성장은 되돌리지 않는다" 하한
    ///   - weeklyGoal: 주간 목표 러닝 횟수 (Q5 초기값 · 설정에서 변경)
    ///   - now: 판정 기준 시각 (결정론을 위한 주입)
    static func state(runs: [RunSummary], cycleStartedAt: Date, maxStage: Int,
                       weeklyGoal: Int, now: Date) -> GrowthState {
        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작 — streakWeeks와 동일 방식
        calendar.timeZone = .current

        let cycleRuns = runs.filter { $0.start >= cycleStartedAt && $0.start <= now }

        let xp = totalXp(runs: cycleRuns, cycleStartedAt: cycleStartedAt,
                          weeklyGoal: weeklyGoal, calendar: calendar, now: now)
        let computedStage = stage(forXp: xp)
        let savedStage = GrowthStage(rawValue: maxStage) ?? .egg
        // 표시 단계 = max(계산 단계, 저장된 최고 단계) — 되돌리지 않는다
        let displayStage = GrowthStage(rawValue: max(computedStage.rawValue, savedStage.rawValue)) ?? computedStage

        let (xpIntoStage, xpToNextStage, progress) = gauge(xp: xp, stage: displayStage)

        let lastRun = runs.map(\.start).max()
        let daysSinceLastRun = lastRun.map {
            calendar.dateComponents([.day], from: calendar.startOfDay(for: $0), to: calendar.startOfDay(for: now)).day ?? 0
        }
        // 러닝이 아예 없으면 시무룩이 아니다 — 첫 실행 상태는 시무룩과 다르다
        let isSulky = (daysSinceLastRun ?? 0) >= 7 && lastRun != nil

        return GrowthState(xp: xp, stage: displayStage, xpIntoStage: xpIntoStage,
                            xpToNextStage: xpToNextStage, progress: progress,
                            isSulky: isSulky, daysSinceLastRun: daysSinceLastRun)
    }

    /// 누적 XP → 단계. 임계값을 순서대로 넘는 마지막 단계를 고른다.
    private static func stage(forXp xp: Int) -> GrowthStage {
        var result = GrowthStage.egg
        for candidate in GrowthStage.allCases where xp >= candidate.threshold {
            result = candidate
        }
        return result
    }

    /// 현재 단계 안에서의 진행률 — (단계 내 XP, 다음 단계까지 남은 XP, 게이지 비율)
    private static func gauge(xp: Int, stage: GrowthStage) -> (Int, Int?, Double) {
        let intoStage = max(0, xp - stage.threshold)
        guard let next = stage.next else {
            return (intoStage, nil, 1.0)
        }
        let span = next.threshold - stage.threshold
        let remaining = max(0, next.threshold - xp)
        let progress = span > 0 ? min(1.0, Double(intoStage) / Double(span)) : 1.0
        return (intoStage, remaining, progress)
    }

    /// 이번 사이클 총 XP = 세션 XP 합(하루 상한 적용) + 주간 목표 달성 보너스 + 4주 연속 보너스
    private static func totalXp(runs: [RunSummary], cycleStartedAt: Date, weeklyGoal: Int,
                                 calendar: Calendar, now: Date) -> Int {
        sessionXp(runs: runs, calendar: calendar)
            + weeklyBonusXp(runs: runs, cycleStartedAt: cycleStartedAt, weeklyGoal: weeklyGoal,
                             calendar: calendar, now: now)
    }

    /// 러닝 세션 XP — 날짜별로 묶어 하루 상한 40을 적용한 합
    private static func sessionXp(runs: [RunSummary], calendar: Calendar) -> Int {
        let byDay = Dictionary(grouping: runs) { calendar.startOfDay(for: $0.start) }
        return byDay.values.reduce(0) { total, dayRuns in
            let dayXp = dayRuns.reduce(0) { $0 + xp(for: $1) }
            return total + min(dailyXpCap, dayXp)
        }
    }

    /// 러닝 1회 XP — 1km 미만은 0(완료 인정 안 함). 그 외 기본 10 + km당 1(세션당 21 상한)
    private static func xp(for run: RunSummary) -> Int {
        guard let km = run.distanceKm, km >= 1.0 else { return 0 }
        let distanceBonus = min(perSessionDistanceCap, Int(km.rounded(.down)) * perKmXp)
        return baseXp + distanceBonus
    }

    /// 주간 목표 달성(+30) 및 4주 연속 달성(+50, 4주 단위마다) 보너스.
    /// 완결된 주(사이클 시작 주 ~ 이전 주. 진행 중인 이번 주는 제외 — streakWeeks와 동일한 원칙)만 판정한다.
    private static func weeklyBonusXp(runs: [RunSummary], cycleStartedAt: Date, weeklyGoal: Int,
                                       calendar: Calendar, now: Date) -> Int {
        guard weeklyGoal > 0,
              let cycleStart = calendar.dateInterval(of: .weekOfYear, for: cycleStartedAt)?.start,
              let currentWeekStart = calendar.dateInterval(of: .weekOfYear, for: now)?.start
        else {
            return 0
        }

        let countsByWeek: [Date: Int] = Dictionary(
            grouping: runs.compactMap { calendar.dateInterval(of: .weekOfYear, for: $0.start)?.start },
            by: { $0 }
        ).mapValues(\.count)

        // cycleStart 주부터 currentWeekStart 이전 주(완결된 주)까지 오래된 순으로 순회
        var weeks: [Date] = []
        var cursor = cycleStart
        while cursor < currentWeekStart {
            weeks.append(cursor)
            guard let next = calendar.date(byAdding: .weekOfYear, value: 1, to: cursor) else { break }
            cursor = next
        }

        var bonus = 0
        var consecutive = 0
        for week in weeks {
            let achieved = (countsByWeek[week] ?? 0) >= weeklyGoal
            if achieved {
                bonus += weeklyGoalXp
                consecutive += 1
                if consecutive % streakBonusIntervalWeeks == 0 {
                    bonus += streakBonusXp
                }
            } else {
                consecutive = 0
            }
        }
        return bonus
    }
}
