import Foundation
import Testing
@testable import RunWrap

struct BatteryEngineTests {
    /// 2026-08-10(월) 18:00 KST — ReportMetricsTests와 같은 고정 시각
    private let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    private func run(daysAgo: Double, km: Double,
                     minPerKm: Double = 6, hr: Double = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }

    private func reading(_ today: Double, _ baseline: Double,
                         days: Int = 28) -> VitalsSnapshot.Reading {
        .init(today: today, baseline: baseline, baselineDays: days)
    }

    @Test func neutralVitalsGiveMidBattery() throws {
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(report.tone == .steady)
        #expect(report.factors.count == 3)
        #expect(report.factors.allSatisfy { $0.points == 0 })
    }

    @Test func recoveredVitalsCharge() throws {
        // HRV +20% → +16, 안정 심박 −8% → +12, 수면 8.5시간 → +11
        let vitals = VitalsSnapshot(hrvMs: reading(72, 60),
                                    restingHR: reading(46, 50),
                                    sleepHours: 8.5)
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 89)
        #expect(report.tone == .improving)
        #expect(report.statusLabel == "충전 충분")
    }

    @Test func poorVitalsAndTrainingDrainToZero() throws {
        // HRV −30%(클램프 −20), 안정 심박 +12%(클램프 −15), 수면 5시간(−15)
        // + 오늘 10 km(−20) + ACWR 1.6(−8) → 50−78 → 0으로 클램프
        let vitals = VitalsSnapshot(hrvMs: reading(42, 60),
                                    restingHR: reading(56, 50),
                                    sleepHours: 5)
        let runs = [run(daysAgo: 0.1, km: 10),
                    run(daysAgo: 8, km: 5),
                    run(daysAgo: 15, km: 5),
                    run(daysAgo: 22, km: 5)]
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: runs, now: now))
        #expect(report.level == 0)
        #expect(report.tone == .overload)
        #expect(report.factors.contains { $0.name == "오늘 훈련" && $0.points == -20 })
        #expect(report.factors.contains { $0.name == "훈련 부하" && $0.points == -8 })
    }

    @Test func requiresTwoCoreSignals() {
        // HRV 하나만 유효 (안정 심박은 기준선 3일뿐, 수면 없음) → 계산하지 않는다
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52, days: 3))
        #expect(BatteryEngine.compute(vitals: vitals, runs: [], now: now) == nil)
    }

    @Test func outlierRespirationAndTemperaturePenalize() throws {
        // 핵심 신호는 중립, 호흡수 +18%·손목 온도 +0.5°C → 각각 −6
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    respiratoryRate: reading(16.5, 14),
                                    wristTempC: reading(36.9, 36.4, days: 21),
                                    sleepHours: 7)
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 38)
        #expect(report.tone == .caution)
        #expect(report.factors.contains { $0.name == "호흡수" && $0.points == -6 })
        #expect(report.factors.contains { $0.name == "손목 온도" && $0.points == -6 })
    }

    @Test func typicalRespirationAndTemperatureStaySilent() throws {
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    respiratoryRate: reading(14.5, 14.2),
                                    wristTempC: reading(36.5, 36.4, days: 21),
                                    sleepHours: 7)
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(!report.factors.contains { $0.name == "호흡수" })
        #expect(!report.factors.contains { $0.name == "손목 온도" })
    }
}
