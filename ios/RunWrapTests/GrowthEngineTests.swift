import Foundation
import Testing
@testable import RunWrap

/// 성장 시스템(GrowthEngine) 검증 — XP 산식·단계 경계·되돌리지 않음·시무룩 판정.
/// now는 2026-08-13(목) — ISO 주 기준 이번 주 시작은 2026-08-10(월).
struct GrowthEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!
    /// 별다른 지정이 없으면 사이클은 아주 오래 전에 시작한 것으로 두어
    /// "cycleStartedAt 필터"가 결과에 끼어들지 않게 한다.
    let farPastCycleStart = ISO8601DateFormatter().date(from: "2020-01-01T00:00:00Z")!

    private func run(daysAgo: Double, km: Double) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * 6 * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: 150)
    }

    @Test("XP 산식 — 5km 러닝 1회 = 기본 10 + 거리 5 = 15")
    func basicSessionXp() {
        let runs = [run(daysAgo: 1, km: 5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.xp == 15)
    }

    @Test("세션 상한 — 30km 러닝 = 기본 10 + 거리 보너스 21(상한) = 31")
    func sessionDistanceBonusCap() {
        // 30km → floor(30) * 1 = 30, 세션당 21 상한 적용 → 10 + 21 = 31
        let runs = [run(daysAgo: 1, km: 30)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.xp == 31)
    }

    @Test("하루 상한 40 — 같은 날 20km + 20km는 (10+20)+(10+20)=60이 아니라 40으로 잘린다")
    func dailyXpCap() {
        // 각 세션 20km → 10 + min(21, 20) = 30. 두 세션 합 60 → 하루 상한 40
        let runs = [run(daysAgo: 1, km: 20), run(daysAgo: 1.1, km: 20)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.xp == 40)
    }

    @Test("1km 미만은 완료로 인정하지 않아 XP 0")
    func belowOneKmYieldsZeroXp() {
        let runs = [run(daysAgo: 1, km: 0.5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.xp == 0)
        #expect(state.stage == .egg)
    }

    @Test("주간 목표 달성 — 완결된 지난주에 목표 2회를 채우면 세션 XP 외에 +30")
    func weeklyGoalBonus() {
        // 지난주(2026-08-03 월 ~ 08-09 일)에 3km 러닝 2회 = 목표 2회 달성.
        // 세션 XP: (10+3) * 2 = 26. 주간 보너스 +30. 이번 주(진행 중)는 판정 제외.
        let runs = [run(daysAgo: 8, km: 3), run(daysAgo: 6, km: 3)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 2, now: now)
        #expect(state.xp == 26 + 30)
    }

    @Test("4주 연속 달성 — 완결된 4주 각각 목표 채우면 주당 +30 네 번 + 연속 보너스 +50")
    func fourWeekStreakBonus() {
        // cycleStartedAt을 4주 전 월요일로 맞추고, 그 이후 완결된 4개 ISO 주 각각에
        // 목표 1회(1km 러닝, XP 11)를 채운다. 이번 주(진행 중)는 판정에서 제외되므로
        // daysAgo는 8, 15, 22, 29일 전(각기 다른 완결된 주)로 배치한다.
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let cycleStart = calendar.date(byAdding: .weekOfYear, value: -4,
                                        to: calendar.dateInterval(of: .weekOfYear, for: now)!.start)!
        let runs = [run(daysAgo: 8, km: 1), run(daysAgo: 15, km: 1),
                    run(daysAgo: 22, km: 1), run(daysAgo: 29, km: 1)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: cycleStart,
                                        maxStage: 1, weeklyGoal: 1, now: now)
        // 세션 XP: 11 * 4 = 44. 주간 보너스: 30 * 4 = 120. 4주 연속 보너스: +50.
        #expect(state.xp == 44 + 120 + 50)
    }

    @Test("단계 경계 — XP 정확히 50이면 금 간 알")
    func stageBoundaryAtFifty() {
        // 기본 10 + 거리 보너스 40(40km, 21 상한 미적용 구간 아님 주의: 40km는 21 상한 걸림)
        // 대신 정확히 50을 만들기 위해 두 세션으로 구성: 10km(10+10=20) + 20km(10+20=30) = 50
        let runs = [run(daysAgo: 1, km: 10), run(daysAgo: 3, km: 20)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.xp == 50)
        #expect(state.stage == .crackedEgg)
        #expect(state.xpIntoStage == 0)
        #expect(state.xpToNextStage == 150)  // 부화(200) - 50
    }

    @Test("성장은 되돌리지 않는다 — maxStage가 계산값보다 높으면 그 값을 쓴다")
    func maxStageWins() {
        let runs = [run(daysAgo: 1, km: 5)]  // XP 15 → 계산 단계는 알(egg)
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: GrowthStage.fledgling.rawValue, weeklyGoal: 0, now: now)
        #expect(state.stage == .fledgling)
    }

    @Test("시무룩 — 마지막 러닝 7일 전이면 true")
    func sulkyAtSevenDays() {
        let runs = [run(daysAgo: 7, km: 5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.isSulky == true)
        #expect(state.daysSinceLastRun == 7)
    }

    @Test("시무룩 아님 — 마지막 러닝 6일 전이면 false")
    func notSulkyAtSixDays() {
        let runs = [run(daysAgo: 6, km: 5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.isSulky == false)
        #expect(state.daysSinceLastRun == 6)
    }

    @Test("시무룩 아님 — 러닝이 아예 없으면 false (첫 실행 상태는 시무룩이 아니다)")
    func notSulkyWhenNoRuns() {
        let state = GrowthEngine.state(runs: [], cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        #expect(state.isSulky == false)
        #expect(state.daysSinceLastRun == nil)
    }

    @Test("cycleStartedAt 이전 러닝은 XP에 산입되지 않는다")
    func runsBeforeCycleStartExcluded() {
        // cycleStartedAt을 3일 전으로 설정 — 그 이전(5일 전) 러닝은 무시되어야 한다
        let cycleStart = now.addingTimeInterval(-3 * 86_400)
        let runs = [run(daysAgo: 5, km: 10), run(daysAgo: 1, km: 5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: cycleStart,
                                        maxStage: 1, weeklyGoal: 0, now: now)
        // 5일 전 10km(XP 20)는 제외, 1일 전 5km(XP 15)만 산입
        #expect(state.xp == 15)
    }
}
