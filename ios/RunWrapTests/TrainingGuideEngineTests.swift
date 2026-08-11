import Foundation
import Testing
@testable import RunWrap

/// 훈련 가이드 엔진 검증 — Riegel 예측, 세션 분류, 배터리 하향 보정, 표본 가드.
/// now = 2026-08-10T09:00:00Z 고정.
struct TrainingGuideEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!
    var engine: TrainingGuideEngine { TrainingGuideEngine(now: now) }

    private func run(daysAgo: Double, km: Double, paceSecPerKm: Double = 360) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: paceSecPerKm * km, distanceMeters: km * 1_000,
                   avgHeartRate: 150)
    }

    /// 28일 동안 10km × 12회 = 총 120km → chronic(4주 주평균) = 30km.
    /// daysAgo 1~26.3 — 창(28일) 안이고 최고령이 21일을 넘어 가드를 통과한다.
    private var baseRuns: [RunSummary] {
        (0..<12).map { run(daysAgo: Double($0) * 2.3 + 1, km: 10) }
    }

    private func record(label: String, km: Double, timeSec: Double,
                        daysAgo: Double) -> PersonalRecords.Entry {
        PersonalRecords.Entry(label: label, distanceKm: km, timeSec: timeSec,
                              date: now.addingTimeInterval(-daysAgo * 86_400))
    }

    @Test("Riegel 예측 — 5K 25:00 기록이면 10K는 52:07로 예측한다")
    func riegelPrediction() throws {
        // T2 = 1500 × (10/5)^1.06 = 1500 × 2.0849 ≈ 3127초 = 52:07
        let records = [record(label: "5K", km: 5.0, timeSec: 1_500, daysAgo: 7)]
        let predicted = try #require(TrainingGuideEngine.predictedTime(
            for: .tenK, records: records, now: now))
        #expect(abs(predicted - 3_127) < 1)
    }

    @Test("Riegel 표본 창 — 8주(56일)가 지난 기록으로는 예측하지 않는다")
    func riegelWindow() {
        let records = [record(label: "5K", km: 5.0, timeSec: 1_500, daysAgo: 57)]
        #expect(TrainingGuideEngine.predictedTime(for: .tenK, records: records, now: now) == nil)
    }

    @Test("세션 분류 — 주간 최장은 LSD, 4주 평균보다 10% 빠르면 스피드, 나머지 easy")
    func sessionClassification() {
        // 주간 총 28km: 최장 14km ≥ 28×0.35 = 9.8 → LSD.
        // 4주 평균 페이스 360 기준: 320 ≤ 324(= 360×0.9) → 스피드, 365는 easy.
        let week = [run(daysAgo: 1, km: 14, paceSecPerKm: 360),
                    run(daysAgo: 3, km: 6, paceSecPerKm: 320),
                    run(daysAgo: 5, km: 8, paceSecPerKm: 365)]
        #expect(TrainingGuideEngine.classify(week: week, avg4wPaceSec: 360)
            == [.lsd, .speed, .easy])
    }

    @Test("배터리 하향 보정 — overload/caution이면 LSD 상한을 25% 하한으로 내린다")
    func batteryLowersLSD() throws {
        // chronic 30 → 주간 30~33km, LSD 정상 7.5(30×0.25)~11.55(33×0.35)
        let normal = try #require(engine.guide(runs: baseRuns, records: [], race: .tenK,
                                               goalSec: nil, batteryTone: .steady))
        #expect(abs(normal.prescription.weeklyKmLow - 30) < 0.01)
        #expect(abs(normal.prescription.weeklyKmHigh - 33) < 0.01)
        #expect(abs(normal.prescription.lsdKmLow - 7.5) < 0.01)
        #expect(abs(normal.prescription.lsdKmHigh - 11.55) < 0.01)
        #expect(normal.prescription.batteryLimited == false)

        // caution이면 상한도 30×0.25 = 7.5로 고정된다
        let limited = try #require(engine.guide(runs: baseRuns, records: [], race: .tenK,
                                                goalSec: nil, batteryTone: .caution))
        #expect(abs(limited.prescription.lsdKmHigh - 7.5) < 0.01)
        #expect(limited.prescription.batteryLimited)
    }

    @Test("표본 부족 가드 — 기록 3주 미만이거나 만성 부하가 주 3km 미만이면 침묵(nil)")
    func insufficientGuard() {
        // 기록이 11일치뿐 — 최고령이 21일보다 최근이라 가드에 걸린다
        let young = (0..<6).map { run(daysAgo: Double($0) * 2 + 1, km: 10) }
        #expect(engine.guide(runs: young, records: [], race: .tenK,
                             goalSec: nil, batteryTone: nil) == nil)

        // 3주는 넘지만 4주 총 8km → chronic 2km/주 < 3
        let tiny = [run(daysAgo: 25, km: 4), run(daysAgo: 10, km: 4)]
        #expect(engine.guide(runs: tiny, records: [], race: .tenK,
                             goalSec: nil, batteryTone: nil) == nil)
    }

    @Test("목표 대비 판정 — 예측이 목표보다 빠르면 improving, 5% 넘게 느리면 caution")
    func predictionTone() throws {
        let records = [record(label: "5K", km: 5.0, timeSec: 1_500, daysAgo: 7)]
        // 예측 3127초: 목표 3200(53:20) → 달성권 improving
        let ok = try #require(engine.guide(runs: baseRuns, records: records, race: .tenK,
                                           goalSec: 3_200, batteryTone: nil))
        #expect(ok.prediction?.tone == .improving)
        // 목표 2900(48:20) → 3127 > 2900×1.05 = 3045 → caution
        let gap = try #require(engine.guide(runs: baseRuns, records: records, race: .tenK,
                                            goalSec: 2_900, batteryTone: nil))
        #expect(gap.prediction?.tone == .caution)
    }

    @Test("스피드 세션 상한 — 초보는 주 1회, 숙련은 주 2회")
    func speedSessionCap() throws {
        let beginner = try #require(TrainingGuideEngine(now: now, level: .beginner)
            .guide(runs: baseRuns, records: [], race: .tenK, goalSec: nil, batteryTone: nil))
        #expect(beginner.prescription.speedSessionsMax == 1)

        let experienced = try #require(engine.guide(runs: baseRuns, records: [], race: .tenK,
                                                    goalSec: nil, batteryTone: nil))
        #expect(experienced.prescription.speedSessionsMax == 2)
    }
}
