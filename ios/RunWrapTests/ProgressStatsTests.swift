import Foundation
import Testing
@testable import RunWrap

/// 발전상 레이어 검증 — PR 범위 판정과 월별 시리즈의 표본 가드
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

    @Test("PR 판정 — [D×0.995, D×1.10] 범위 안 최소 기록과 달성일을 고른다")
    func personalRecordsPicksBest() throws {
        let bestFiveK = run(daysAgo: 40, km: 5.4, minPerKm: 5.0)   // 페이스 300 → 5K 환산 1500초
        let runs = [run(daysAgo: 10, km: 5.2, minPerKm: 5.5),      // 페이스 330 → 1650초 (밀림)
                    bestFiveK,
                    run(daysAgo: 20, km: 10.5, minPerKm: 5.8),     // 페이스 348 → 10K 환산 3480초
                    run(daysAgo: 5, km: 5.8, minPerKm: 4.0)]       // 5.8 > 5.5 — 5K 범위 밖, 10K 미달
        let entries = PersonalRecords.compute(runs: runs)
        #expect(entries.count == 2)  // 하프·풀 기록 없음 → 항목 자체 미포함

        let fiveK = try #require(entries.first { $0.label == "5K" })
        #expect(abs(fiveK.timeSec - 1500) < 0.01)   // 300초/km × 5.0km
        #expect(fiveK.date == bestFiveK.start)

        let tenK = try #require(entries.first { $0.label == "10K" })
        #expect(abs(tenK.timeSec - 3480) < 0.01)    // 348초/km × 10.0km
    }

    @Test("PR 하한 — 워치에 5.00으로 보이는 4.997km는 5K 후보, 4.97km는 아니다 (이슈 #91)")
    func personalRecordsLowerBoundTolerance() throws {
        // 하한 5 × 0.995 = 4.975km. 4.997km(페이스 300) → 5K 환산 300 × 5.0 = 1500초
        let rounded = run(daysAgo: 3, km: 4.997, minPerKm: 5.0)
        let fiveK = try #require(PersonalRecords.compute(runs: [rounded]).first { $0.label == "5K" })
        #expect(abs(fiveK.timeSec - 1500) < 0.01)   // 환산은 여전히 페이스 × 공인 거리
        #expect(fiveK.run.id == rounded.id)

        // 4.97km < 4.975km → 후보 아님
        #expect(PersonalRecords.compute(runs: [run(daysAgo: 3, km: 4.97, minPerKm: 5.0)]).isEmpty)
    }

    @Test("PR — 해당 거리 기록이 하나도 없으면 빈 배열")
    func personalRecordsEmpty() {
        let entries = PersonalRecords.compute(runs: [run(daysAgo: 3, km: 3)])
        #expect(entries.isEmpty)
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
        let runs = [run(daysAgo: 1, km: 5.2, minPerKm: 5.0),   // 페이스 300 → 5K 1500초
                    run(daysAgo: 5, km: 8, minPerKm: 6),       // EF (60000/360)/150 ≈ 1.1111
                    run(daysAgo: 9, km: 8, minPerKm: 6),
                    run(daysAgo: 33, km: 8, minPerKm: 6)]
        let broken = RunSummary(id: UUID(), start: now.addingTimeInterval(-3 * 86_400),
                                durationSec: 0, distanceMeters: 5_200, avgHeartRate: 150)

        // 가드 전에는 페이스 0 → 5K 0초가 영구 PB가 됐다
        let fiveK = try #require(PersonalRecords.compute(runs: runs + [broken])
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
