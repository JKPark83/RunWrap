import Foundation
import Testing
@testable import RunWrap

/// 발전상 레이어 검증 — PR(베스트 에포트 최소값, 이슈 #166) 선택과 월별 시리즈의 표본 가드
/// now = 2026-08-10(월) 18:00 KST — daysAgo 1~9 = 8월, 33~40 = 7월.
struct ProgressStatsTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    private func run(daysAgo: Double, km: Double,
                     minPerKm: Double = 6, hr: Double? = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }

    // MARK: PR

    @Test("PR 판정 — 세션별 베스트 에포트 중 최소 기록과 달성일을 고른다 (이슈 #166)")
    func personalRecordsPicksBest() throws {
        let bestFiveK = run(daysAgo: 40, km: 5.4, minPerKm: 5.0)
        let slower = run(daysAgo: 10, km: 5.2, minPerKm: 5.5)
        let long = run(daysAgo: 20, km: 10.5, minPerKm: 5.8)
        let uncomputed = run(daysAgo: 5, km: 5.8, minPerKm: 4.0)   // 백필 전 — 표에 없다
        let efforts: BestEffortTable = [
            slower.id: [1_000: 320, 5_000: 1_650],
            bestFiveK.id: [1_000: 290, 5_000: 1_500],
            long.id: [1_000: 330, 5_000: 1_700, 10_000: 3_480],
        ]
        let entries = PersonalRecords.compute(runs: [slower, bestFiveK, long, uncomputed],
                                              efforts: efforts)
        // 하프·풀 기록 없음 → 항목 자체 미포함. 1K는 에포트가 있어도 PB 종목이 아니다
        #expect(entries.map(\.label) == ["5K", "10K"])

        let fiveK = try #require(entries.first { $0.label == "5K" })
        #expect(fiveK.timeSec == 1_500)             // min(1650, 1500, 1700)
        #expect(fiveK.date == bestFiveK.start)
        #expect(fiveK.run.id != uncomputed.id)      // 평균 페이스가 가장 빨라도 계산 전이면 후보 아님

        let tenK = try #require(entries.first { $0.label == "10K" })
        #expect(tenK.timeSec == 3_480)
        #expect(tenK.run.id == long.id)
    }

    @Test("PR — 긴 세션 안 구간 기록도 후보, 못 채운 거리는 후보 아님 (이슈 #91·#166)")
    func personalRecordsFromLongRunSegment() throws {
        // 예전 산식은 완주 거리 ∈ [D×0.995, D×1.10]만 봤다 — 12km 세션은 5K·10K 모두 범위 밖이었다.
        // 베스트 에포트는 세션 안 가장 빠른 5km 구간(1,400초)을 그대로 5K 기록으로 쓴다
        let long = run(daysAgo: 3, km: 12, minPerKm: 5.5)
        // 4.97km 세션은 엔진이 5K를 못 채워 1K만 낸다 → 5K 후보가 아니다
        let short = run(daysAgo: 4, km: 4.97, minPerKm: 5.0)
        let efforts: BestEffortTable = [
            long.id: [1_000: 270, 5_000: 1_400, 10_000: 3_200],
            short.id: [1_000: 285],
        ]
        let entries = PersonalRecords.compute(runs: [long, short], efforts: efforts)
        let fiveK = try #require(entries.first { $0.label == "5K" })
        #expect(fiveK.timeSec == 1_400)
        #expect(fiveK.run.id == long.id)
        // 4.97km 세션만 있으면 5K 항목이 없다
        #expect(PersonalRecords.compute(runs: [short], efforts: efforts)
            .contains { $0.label == "5K" } == false)
    }

    @Test("PR — 베스트 에포트가 없거나(미계산) 빈 dict(1K 미만)면 빈 배열")
    func personalRecordsEmpty() {
        let short = run(daysAgo: 3, km: 0.8)
        #expect(PersonalRecords.compute(runs: [short], efforts: [:]).isEmpty)
        #expect(PersonalRecords.compute(runs: [short], efforts: [short.id: [:]]).isEmpty)
    }

    // MARK: 페이스 타당 범위 가드 (이슈 #76)

    @Test("페이스 가드 — 시간 0초면 nil")
    func paceNilWhenZeroDuration() {
        let zero = RunSummary(id: UUID(), start: now, durationSec: 0,
                              distanceMeters: 5_200, avgHeartRate: 150)
        #expect(zero.paceSecPerKm == nil)
    }

    @Test("페이스 가드 — 2:00/km·25:00/km처럼 비현실 페이스는 nil")
    func paceNilWhenOutOfRange() {
        #expect(run(daysAgo: 1, km: 5, minPerKm: 2).paceSecPerKm == nil)    // 120초/km < 150
        #expect(run(daysAgo: 1, km: 5, minPerKm: 25).paceSecPerKm == nil)   // 1500초/km > 1200
    }

    @Test("페이스 가드 — 정상 페이스와 경계값 150·1200초/km는 값을 낸다")
    func paceValueInRange() throws {
        let normal = try #require(run(daysAgo: 1, km: 5, minPerKm: 5.5).paceSecPerKm)
        #expect(abs(normal - 330) < 0.01)   // 5:30/km
        let fastest = try #require(run(daysAgo: 1, km: 5, minPerKm: 2.5).paceSecPerKm)
        #expect(abs(fastest - 150) < 0.01)  // 2:30/km — 하한 포함
        let slowest = try #require(run(daysAgo: 1, km: 5, minPerKm: 20).paceSecPerKm)
        #expect(abs(slowest - 1_200) < 0.01) // 20:00/km — 상한 포함
    }

    @Test("회귀 — 시간 0초인 5.2km 기록이 섞여도 5K PB는 그대로, 월 EF는 유한하다")
    func zeroDurationRunDoesNotBecomePBOrInfiniteEF() throws {
        let runs = [run(daysAgo: 1, km: 5.2, minPerKm: 5.0),   // 5K 베스트 에포트 1500초
                    run(daysAgo: 5, km: 8, minPerKm: 6),       // EF (60000/360)/150 ≈ 1.1111
                    run(daysAgo: 9, km: 8, minPerKm: 6),
                    run(daysAgo: 33, km: 8, minPerKm: 6)]
        let broken = RunSummary(id: UUID(), start: now.addingTimeInterval(-3 * 86_400),
                                durationSec: 0, distanceMeters: 5_200, avgHeartRate: 150)

        // 가드 전에는 페이스 0 → 5K 0초가 영구 PB가 됐다. 이제 PR은 세션 시간이 아니라
        // 거리 샘플의 베스트 에포트를 쓴다 — 시간 0초 세션은 샘플이 없어 빈 dict (이슈 #166)
        let efforts: BestEffortTable = [runs[0].id: [1_000: 290, 5_000: 1_500], broken.id: [:]]
        let fiveK = try #require(PersonalRecords.compute(runs: runs + [broken], efforts: efforts)
            .first { $0.label == "5K" })
        #expect(abs(fiveK.timeSec - 1500) < 0.01)
        #expect(fiveK.run.id != broken.id)

        // 가드 전에는 60000/0 = inf가 월평균 EF를 inf로 만들었다.
        // 8월 유효 표본 3회: (1.3333 + 1.1111 + 1.1111) / 3 ≈ 1.1852
        let series = try #require(MonthlySeries.compute(runs: runs + [broken], now: now))
        let august = try #require(series.points.last?.avgEF)
        #expect(august.isFinite)
        #expect(abs(august - 1.1852) < 0.001)
    }

    // MARK: 월별 시리즈

    @Test("월 시리즈 — 7·8월 집계가 오래된 → 최신 순서로 나온다")
    func monthlySeriesOrderAndValues() throws {
        let runs = [run(daysAgo: 1, km: 10, minPerKm: 6),     // 8.9
                    run(daysAgo: 5, km: 10, minPerKm: 6),     // 8.5 — 8월 합계 20km, 페이스 360
                    run(daysAgo: 33, km: 8, minPerKm: 6.5),   // 7.8
                    run(daysAgo: 38, km: 8, minPerKm: 6.5)]   // 7.3 — 7월 합계 16km, 페이스 390
        let series = try #require(MonthlySeries.compute(runs: runs, now: now))
        #expect(series.points.count == 2)

        let july = series.points[0]
        #expect(july.label == "7월")
        #expect(abs(july.totalKm - 16) < 0.01)
        #expect(july.avgPaceSec != nil && abs(july.avgPaceSec! - 390) < 0.01)
        #expect(july.avgEF == nil)  // 심박 세션 2회 — 3회 미만 가드

        let august = series.points[1]
        #expect(august.label == "8월")
        #expect(abs(august.totalKm - 20) < 0.01)
        #expect(august.avgPaceSec != nil && abs(august.avgPaceSec! - 360) < 0.01)
    }

    @Test("월 EF — 심박 세션 3회 이상인 달만 점이 있다")
    func monthlyEFGuard() throws {
        let runs = [run(daysAgo: 1, km: 8, minPerKm: 6, hr: 150),   // 8.9
                    run(daysAgo: 5, km: 8, minPerKm: 6, hr: 150),   // 8.5
                    run(daysAgo: 9, km: 8, minPerKm: 6, hr: 150),   // 8.1 — 8월 3회
                    run(daysAgo: 33, km: 8, minPerKm: 6, hr: 150),  // 7.8
                    run(daysAgo: 38, km: 8, minPerKm: 6, hr: 150)]  // 7.3 — 7월 2회
        let series = try #require(MonthlySeries.compute(runs: runs, now: now))
        // EF = (60000 / 360) ÷ 150 = 166.67 m/min ÷ 150 bpm ≈ 1.1111
        let august = try #require(series.points.last)
        #expect(august.avgEF != nil && abs(august.avgEF! - 1.1111) < 0.001)
        #expect(series.points.first?.avgEF == nil)
    }

    @Test("월 시리즈 가드 — 기록 있는 월이 2개 미만이면 nil")
    func monthlySeriesGuard() {
        let augustOnly = [run(daysAgo: 1, km: 10), run(daysAgo: 5, km: 10)]
        #expect(MonthlySeries.compute(runs: augustOnly, now: now) == nil)
        #expect(MonthlySeries.compute(runs: [], now: now) == nil)
    }
}
