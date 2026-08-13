import Foundation
import Testing
@testable import RunWrap

/// 홈 브리핑 엔진(HomeBriefingEngine) 검증 — 우선순위 사다리와 미노출 가드.
/// now는 2026-08-13(목) — ISO 주 기준 이번 주 시작은 2026-08-10(월)이라
/// "이번 주"에 들어가는 러닝은 daysAgo 0~3이다.
struct HomeBriefingEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func run(daysAgo: Double, km: Double, paceSec: Double = 360) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * paceSec,
                   distanceMeters: km * 1000,
                   avgHeartRate: 150)
    }

    /// 러닝 이력에서 실제 GrowthState를 뽑아 쓴다 — 시무룩 판정을 손으로 흉내 내지 않기 위해
    private func growth(_ runs: [RunSummary], weeklyGoal: Int = 3) -> GrowthState {
        GrowthEngine.state(runs: runs, cycleStartedAt: .distantPast,
                            maxStage: 1, weeklyGoal: weeklyGoal, now: now)
    }

    private func report(_ runs: [RunSummary]) -> WeeklyReport {
        ReportEngine(now: now, level: .intermediate).weeklyReport(from: runs)
    }

    // MARK: - 미노출 가드

    @Test("미노출 가드 — 러닝 기록이 하나도 없으면 브리핑을 내지 않는다")
    func noRunsNoBriefing() {
        let line = HomeBriefingEngine.briefing(runs: [], growth: growth([]), report: nil,
                                                battery: nil, weeklyGoal: 3,
                                                weatherLine: "오늘은 덥습니다", now: now)
        #expect(line == nil)
    }

    @Test("미노출 가드 — 재료가 전부 부족하면 날씨 한 줄로 떨어진다")
    func fallsBackToWeather() {
        // 이번 주에 아직 안 뛰었고(4일 전 1회), 지난주 비교 표본도 없고, 배터리도 없다
        let runs = [run(daysAgo: 4, km: 5)]
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: nil, weeklyGoal: 3,
                                                weatherLine: "오전 9시부터 체감 31°C를 넘어요.", now: now)
        #expect(line == "오전 9시부터 체감 31°C를 넘어요.")
    }

    @Test("미노출 가드 — 재료도 날씨도 없으면 nil")
    func noMaterialNoLine() {
        let runs = [run(daysAgo: 4, km: 5)]
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == nil)
    }

    // MARK: - ① 과부하·부상 경고가 최우선

    @Test("우선순위 ① — 부하 과부하면 꾸준함 칭찬 대신 경고 문장이 나온다")
    func overloadWinsOverPraise() {
        // 4주 이상 이력 + 최근 7일 급증 → ACWR overload 구간을 만든다
        var runs: [RunSummary] = []
        for week in 1...5 {
            runs.append(run(daysAgo: Double(week) * 7 + 1, km: 5))
        }
        // 이번 주에 몰아서 뛴다 (급성 부하 폭증)
        runs += [run(daysAgo: 0, km: 15), run(daysAgo: 1, km: 15), run(daysAgo: 2, km: 15)]

        let r = report(runs)
        // 전제 확인 — 이 시나리오가 실제로 과부하 톤이어야 테스트가 의미를 가진다
        #expect(r.acwr?.tone == .overload || r.distance?.tone == .overload)

        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: r,
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line != nil)
        // 칭찬 문장("멋있습니다")이 아니라 감량 권고 문장이어야 한다
        #expect(line?.contains("멋있습니다") == false)
        #expect(line?.contains("쉬어") == true || line?.contains("접어") == true)
    }

    // MARK: - ② 컨디션 변화

    @Test("우선순위 ② — 부하가 정상이고 배터리가 주의면 배터리 문장이 나온다")
    func batteryBeatsConsistency() {
        let runs = [run(daysAgo: 0, km: 5), run(daysAgo: 2, km: 5), run(daysAgo: 8, km: 5)]
        let battery = BatteryReport(level: 32, tone: .caution, statusLabel: "주의",
                                    headline: "회복이 덜 됐어요.", factors: [])
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: battery, weeklyGoal: 3, now: now)
        #expect(line == "체력 배터리 32% — 회복이 덜 됐어요.")
    }

    @Test("배터리가 좋아도 75% 미만이면 굳이 말하지 않는다 — 꾸준함으로 넘어간다")
    func mildBatteryIsSilent() {
        let runs = [run(daysAgo: 0, km: 5), run(daysAgo: 2, km: 5)]
        let battery = BatteryReport(level: 62, tone: .improving, statusLabel: "양호",
                                    headline: "괜찮아요.", factors: [])
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: battery, weeklyGoal: 3, now: now)
        // 배터리 문장이 아니라 주간 목표 진행 문장이 나온다
        #expect(line?.contains("체력 배터리") == false)
        #expect(line?.contains("2번째 러닝") == true)
    }

    // MARK: - ③ 꾸준함

    @Test("시무룩 — 7일 이상 공백이면 시안 1f(brfS) 문장을 그대로 낸다")
    func sulkyLine() {
        let runs = [run(daysAgo: 7, km: 5.2)]
        let state = growth(runs)
        #expect(state.isSulky)  // 전제 확인

        let line = HomeBriefingEngine.briefing(runs: runs, growth: state, report: report(runs),
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == "일주일 만이에요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.")
    }

    @Test("공백이 길어지면 앞머리만 바뀐다 — 뒷문장은 유지")
    func longerGapPhrase() {
        let runs = [run(daysAgo: 30, km: 5)]
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: report(runs),
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == "한 달 가까이 못 뵀어요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.")
    }

    @Test("주간 목표만 있을 때 — 목표를 채우면 칭찬 문장")
    func weeklyGoalAchieved() {
        // 이번 주(daysAgo 0~3) 3회 — report 없이 목표 진행만으로 판단
        let runs = [run(daysAgo: 0, km: 5), run(daysAgo: 1, km: 5), run(daysAgo: 3, km: 5)]
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == "이번 주 목표 3회, 벌써 채우셨어요. 새가 아주 흡족해합니다.")
    }

    @Test("주간 목표만 있을 때 — 미달이면 남은 횟수를 알려준다")
    func weeklyGoalRemaining() {
        let runs = [run(daysAgo: 0, km: 5), run(daysAgo: 2, km: 5)]
        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: nil,
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == "이번 주 2번째 러닝. 목표 3회까지 1번 남았어요.")
    }

    @Test("지난주 대비 증가 — 10% 룰 안(+8%)이면 시안 1f(brfN)의 '딱 좋은 증가폭' 문장")
    func growthPraiseLine() {
        // 이전 7일(daysAgo 7~14) 10km, 최근 7일(daysAgo 0~7) 10.8km → +8% (10% 룰 안이라 과부하 아님)
        // ※ windowKm의 최근 창은 [now-7d, now) 반열림이라 daysAgo: 0(= now 정각)은 제외된다.
        //   경계에 걸치지 않도록 0.5일 전으로 둔다.
        let runs = [run(daysAgo: 8, km: 10),
                    run(daysAgo: 0.5, km: 3.6), run(daysAgo: 1, km: 3.6), run(daysAgo: 3, km: 3.6)]
        let r = report(runs)
        #expect(r.distance?.tone != .overload)  // 전제: 과부하 문장으로 새지 않는다

        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: r,
                                                battery: nil, weeklyGoal: 3, now: now)
        #expect(line == "이번 주 3번째 러닝. 지난주보다 8% 늘었어요 — 딱 좋은 증가폭입니다. 정상은 아니지만 멋있습니다.")
    }

    @Test("우선순위 ① — 증가폭이 10% 룰을 넘으면 칭찬 대신 감량 권고가 나온다")
    func distanceOverloadLine() {
        // 이전 7일 10km, 최근 7일 15km → +50% (10% 룰 초과)
        // ※ 최근 창은 [now-7d, now) 반열림 — daysAgo: 0은 제외되므로 0.5일 전에 둔다.
        let runs = [run(daysAgo: 8, km: 10),
                    run(daysAgo: 0.5, km: 5), run(daysAgo: 1, km: 5), run(daysAgo: 3, km: 5)]
        let r = report(runs)
        #expect(r.distance?.tone == .overload)  // 전제 확인
        #expect(r.acwr == nil)                  // 이력 3주 미만이라 ACWR은 계산되지 않는다

        let line = HomeBriefingEngine.briefing(runs: runs, growth: growth(runs), report: r,
                                                battery: nil, weeklyGoal: 3, now: now)
        // 안전선(이전 7일 × 1.1 = 11.0km)을 4.0km 넘겼다
        #expect(line == "지난주보다 +50% 늘었어요 — 안전선을 4.0km 넘겼습니다. 다음 주는 조금 접어 두세요.")
    }

    @Test("이번 주 기록이 없으면 꾸준함 칭찬을 만들지 않는다")
    func noRunThisWeekNoPraise() {
        // 5일 전 = 2026-08-08(토) → 지난주. 시무룩(7일)에는 못 미친다
        let runs = [run(daysAgo: 5, km: 5), run(daysAgo: 6, km: 5)]
        let state = growth(runs)
        #expect(!state.isSulky)  // 전제 확인

        let line = HomeBriefingEngine.briefing(runs: runs, growth: state, report: report(runs),
                                                battery: nil, weeklyGoal: 3,
                                                weatherLine: "날씨 한 줄", now: now)
        #expect(line == "날씨 한 줄")
    }
}
