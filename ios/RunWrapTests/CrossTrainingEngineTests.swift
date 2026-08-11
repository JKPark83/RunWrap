import Foundation
import Testing
@testable import RunWrap

/// 크로스 트레이닝 주간 요약 엔진 — 미노출 가드(0개·20분 미만·걷기 소음)와
/// headline 분기(러닝 급감 vs 유지), detail의 ACWR 비반영 고지를 검증한다.
struct CrossTrainingEngineTests {
    private let now = ISO8601DateFormatter().date(from: "2026-08-12T10:00:00+09:00")!

    private func daysAgo(_ n: Double) -> Date {
        now.addingTimeInterval(-n * 86_400)
    }

    private func cross(daysAgo: Double, minutes: Double, kind: CrossTraining.Kind) -> CrossTraining {
        CrossTraining(start: self.daysAgo(daysAgo), durationSec: minutes * 60, kind: kind, kcal: nil)
    }

    private func run(daysAgo: Double, km: Double) -> RunSummary {
        RunSummary(id: UUID(), start: self.daysAgo(daysAgo), durationSec: km * 300,
                  distanceMeters: km * 1000, avgHeartRate: nil)
    }

    @Test("세션이 하나도 없으면 nil")
    func noSessions() {
        #expect(CrossTrainingEngine.weekly(cross: [], runs: [], now: now) == nil)
    }

    @Test("20분 미만 가드 — 요가 15분 하나뿐이면 nil")
    func under20MinutesGuarded() {
        let sessions = [cross(daysAgo: 1, minutes: 15, kind: .yoga)]
        #expect(CrossTrainingEngine.weekly(cross: sessions, runs: [], now: now) == nil)
    }

    @Test("걷기 30분 가드 — 25분 걷기 하나는 제외되어 세션 0개, nil")
    func shortWalkExcluded() {
        let sessions = [cross(daysAgo: 1, minutes: 25, kind: .walking)]
        #expect(CrossTrainingEngine.weekly(cross: sessions, runs: [], now: now) == nil)
    }

    @Test("자전거 90분 + 근력 40분 — breakdown 정렬·합계 검증")
    func breakdownAggregates() {
        let sessions = [
            cross(daysAgo: 2, minutes: 40, kind: .strength),
            cross(daysAgo: 3, minutes: 90, kind: .cycling),
        ]
        let summary = CrossTrainingEngine.weekly(cross: sessions, runs: [], now: now)
        #expect(summary?.sessionCount == 2)
        #expect(summary?.totalMinutes == 130)  // 90 + 40
        #expect(summary?.breakdown == [
            .init(label: "자전거", minutes: 90, count: 1),
            .init(label: "근력", minutes: 40, count: 1),
        ])
    }

    @Test("러닝 반토막 주간 — headline이 '부하는 이어갔다' 계열")
    func headlineWhenRunningDropped() {
        let sessions = [cross(daysAgo: 2, minutes: 90, kind: .cycling)]
        let runs = [
            run(daysAgo: 10, km: 20),  // 지난주 20km
            run(daysAgo: 2, km: 8),    // 이번 주 8km — 20km의 60% ≤ 80%, 20% 이상 감소
        ]
        let summary = CrossTrainingEngine.weekly(cross: sessions, runs: runs, now: now)
        #expect(summary?.headline.contains("줄었지만") == true)
        #expect(summary?.headline.contains("자전거") == true)
    }

    @Test("러닝 유지 주간 — headline이 '부지런히 병행' 계열")
    func headlineWhenRunningMaintained() {
        let sessions = [cross(daysAgo: 2, minutes: 90, kind: .cycling)]
        let runs = [
            run(daysAgo: 10, km: 20),  // 지난주 20km
            run(daysAgo: 2, km: 19),   // 이번 주 19km — 20% 미만 감소
        ]
        let summary = CrossTrainingEngine.weekly(cross: sessions, runs: runs, now: now)
        #expect(summary?.headline.contains("부지런히") == true)
    }

    @Test("8일 전 세션은 집계 창 밖 — nil")
    func outsideWindowExcluded() {
        let sessions = [cross(daysAgo: 8, minutes: 60, kind: .cycling)]
        #expect(CrossTrainingEngine.weekly(cross: sessions, runs: [], now: now) == nil)
    }

    @Test("detail은 ACWR 비반영을 명시한다")
    func detailMentionsACWR() {
        let sessions = [cross(daysAgo: 2, minutes: 60, kind: .swimming)]
        let summary = CrossTrainingEngine.weekly(cross: sessions, runs: [], now: now)
        #expect(summary?.detail.contains("ACWR") == true)
    }
}
