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

    @Test("주간 목표 — 1km 미만 러닝은 횟수에 세지 않아 0.5km 3회로는 목표 3회를 달성하지 못한다")
    func subKmRunsDoNotCountTowardWeeklyGoal() {
        // 지난주(완결)에 0.5km 3회 — 세션 XP 0, 1km 기준으로 세면 0회 → 보너스 없음
        let runs = [run(daysAgo: 8, km: 0.5), run(daysAgo: 7, km: 0.5), run(daysAgo: 6, km: 0.5)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 3, now: now)
        #expect(state.xp == 0)
        #expect(!GrowthEngine.countsAsCompletedRun(run(daysAgo: 1, km: 0.5)))
        #expect(GrowthEngine.countsAsCompletedRun(run(daysAgo: 1, km: 1)))
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

    @Test("주간 목표 변경 — 이번 주에 3→1로 낮춰도 완결된 과거 주(러닝 1~2회)에는 +30이 붙지 않는다")
    func weeklyGoalLoweredDoesNotRewardPastWeeks() {
        // 지난주(08-03 주) 3km 2회, 그 전 주(07-27 주) 3km 1회. 세션 XP: 13 * 3 = 39.
        // 변경 시각이 이번 주(08-10 주)라 완결된 두 주 모두 변경 전 목표 3으로 판정 → 둘 다 미달, 보너스 0
        let runs = [run(daysAgo: 6, km: 3), run(daysAgo: 8, km: 3), run(daysAgo: 15, km: 3)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 1,
                                        weeklyGoalChange: (at: now, before: 3), now: now)
        #expect(state.xp == 39)
    }

    @Test("주간 목표 변경 — 변경한 주까지는 옛 목표, 다음 주부터는 새 목표 1회로 달성한다")
    func weeklyGoalChangeAppliesFromNextWeek() {
        // 변경 시각 = 14일 전(07-30 목, 07-27 주)에 3→1.
        // 07-27 주(변경한 주) 3km 1회 → 옛 목표 3으로 판정 → 미달.
        // 08-03 주(변경 다음 주) 3km 1회 → 새 목표 1로 판정 → +30 (연속 1주라 연속 보너스 없음).
        // 세션 XP: 13 * 2 = 26. 합계 26 + 30 = 56
        let runs = [run(daysAgo: 15, km: 3), run(daysAgo: 8, km: 3)]
        let changedAt = now.addingTimeInterval(-14 * 86_400)
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 1,
                                        weeklyGoalChange: (at: changedAt, before: 3), now: now)
        #expect(state.xp == 26 + 30)
    }

    @Test("주간 목표 변경 정보가 nil이면 기존처럼 모든 완결된 주를 현재 목표로 판정한다")
    func weeklyGoalChangeNilKeepsLegacyBehavior() {
        // 첫 테스트와 같은 러닝. 현재 목표 1로 두 주(07-27 주 1회, 08-03 주 2회) 모두 달성 → +30 * 2.
        // 연속 2주라 4주 연속 보너스 없음. 세션 XP 39 + 60 = 99
        let runs = [run(daysAgo: 6, km: 3), run(daysAgo: 8, km: 3), run(daysAgo: 15, km: 3)]
        let state = GrowthEngine.state(runs: runs, cycleStartedAt: farPastCycleStart,
                                        maxStage: 1, weeklyGoal: 1,
                                        weeklyGoalChange: nil, now: now)
        #expect(state.xp == 39 + 60)
    }

    @Test("주간 목표 변경 기록 — 첫 변경·다른 주 변경은 새로 쓰고, 같은 주 재변경은 첫 변경 전 값을 유지한다")
    func recordWeeklyGoalChange() {
        // 기록 없음 → (now, 직전 값 3)
        let first = GrowthEngine.recordWeeklyGoalChange(previous: nil, oldGoal: 3, now: now)
        #expect(first.at == now)
        #expect(first.before == 3)

        // 같은 주(08-10 주) 안에서 2→1로 다시 바꿔도 기록(08-13, 3)을 그대로 둔다
        let sameWeek = GrowthEngine.recordWeeklyGoalChange(previous: first, oldGoal: 2,
                                                          now: now.addingTimeInterval(86_400))
        #expect(sameWeek.at == now)
        #expect(sameWeek.before == 3)

        // 다음 주(7일 뒤)에 바꾸면 (그 시각, 직전 값 1)로 새로 쓴다
        let nextWeekTime = now.addingTimeInterval(7 * 86_400)
        let nextWeek = GrowthEngine.recordWeeklyGoalChange(previous: first, oldGoal: 1, now: nextWeekTime)
        #expect(nextWeek.at == nextWeekTime)
        #expect(nextWeek.before == 1)
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
