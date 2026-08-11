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

    private func night(daysAgo: Double, fraction: Double? = nil,
                       bedtime: Double? = nil) -> VitalsSnapshot.SleepNight {
        .init(date: now.addingTimeInterval(-daysAgo * 86_400),
              asleepHours: 7, deepRemFraction: fraction, bedtimeMinutes: bedtime)
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

    @Test("HRR — 기저 표본 4개는 최소 5개 미달이라 팩터 없음") func hrrBelowMinCountStaysSilent() throws {
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    hrr: reading(22, 30, days: 4))
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(report.factors.count == 2)
        #expect(!report.factors.contains { $0.name == "심박 회복" })
    }

    @Test("HRR — 표본 5개·−25%면 −10점 클램프") func hrrDropPenalizes() throws {
        // 21 / 28 = 0.75 → −25% → 정규화 −1(클램프) × 10 = −10
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    hrr: reading(21, 28, days: 5))
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 40)
        #expect(report.factors.contains { $0.name == "심박 회복" && $0.points == -10 })
    }

    @Test("HRR — +25%면 +10점") func hrrRiseCharges() throws {
        // 35 / 28 = 1.25 → +25% → 정규화 1(클램프) × 10 = +10
        let vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    hrr: reading(35, 28, days: 5))
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 60)
        #expect(report.factors.contains { $0.name == "심박 회복" && $0.points == 10 })
    }

    @Test("HRV 없이 안정 심박 + HRR 두 코어 신호만으로도 배터리가 노출된다") func restingHRAndHRRAloneExposeBattery() throws {
        // 24 / 30 = 0.8 → −20% → 정규화 −0.8 × 10 = −8, 안정 심박은 중립(0) → 50 − 8 = 42
        let vitals = VitalsSnapshot(restingHR: reading(52, 52),
                                    hrr: reading(24, 30, days: 5))
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 42)
        #expect(report.factors.count == 2)
        #expect(report.factors.contains { $0.name == "심박 회복" && $0.points == -8 })
    }

    @Test("수면 질 — 기저 대비 20% 이상 하락하면 −8점") func sleepQualityDropPenalizes() throws {
        // 최근 밤 22% vs 나머지 6개 밤 평균 30% → (30−22)/30 ≈ 26.7% ≥ 20% → −8, 50 − 8 = 42
        var vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        vitals.sleepNights = [night(daysAgo: 0, fraction: 0.22)] +
            (1...6).map { night(daysAgo: Double($0), fraction: 0.30) }
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 42)
        #expect(report.tone == .caution)
        #expect(report.factors.contains { $0.name == "수면 질" && $0.points == -8 })
    }

    @Test("수면 질 — 하락이 10%뿐이면 팩터 없음") func sleepQualityMinorDropStaysSilent() throws {
        // 최근 밤 27% vs 평균 30% → (30−27)/30 = 10% < 20%
        var vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        vitals.sleepNights = [night(daysAgo: 0, fraction: 0.27)] +
            (1...6).map { night(daysAgo: Double($0), fraction: 0.30) }
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(!report.factors.contains { $0.name == "수면 질" })
    }

    @Test("수면 질·리듬 — 단계/취침 데이터가 있는 밤이 6개뿐이면 둘 다 팩터 없음") func fewerThanSevenNightsStayBothSilent() throws {
        var vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        // 값 자체는 기준 초과지만(하락 33%, SD > 90분) 6개뿐이라 최소 7개 가드에 걸려 침묵한다
        let fractions: [Double] = [0.20, 0.30, 0.30, 0.30, 0.30, 0.30]
        let bedtimes: [Double] = [550, 550, 550, 700, 850, 850]
        vitals.sleepNights = (0..<6).map { night(daysAgo: Double($0), fraction: fractions[$0], bedtime: bedtimes[$0]) }
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(!report.factors.contains { $0.name == "수면 질" })
        #expect(!report.factors.contains { $0.name == "수면 리듬" })
    }

    @Test("수면 리듬 — 취침 시각 표준편차가 90분을 넘으면 −6점") func bedtimeSDOverThresholdPenalizes() throws {
        // 취침 시각(분) [550,550,550,700,850,850,850], 평균 700
        // 모집단분산 = (3·150² + 0 + 3·150²)/7 = 135000/7 ≈ 19285.7 → SD ≈ 138.9분(>90) → −6, 50 − 6 = 44
        var vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        let bedtimes: [Double] = [550, 550, 550, 700, 850, 850, 850]
        vitals.sleepNights = bedtimes.enumerated().map { night(daysAgo: Double($0.offset), bedtime: $0.element) }
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 44)
        #expect(report.factors.contains { $0.name == "수면 리듬" && $0.points == -6 })
    }

    @Test("수면 리듬 — 표준편차가 90분 미만이면 팩터 없음") func bedtimeSDUnderThresholdStaysSilent() throws {
        // 취침 시각(분) [660×6, 775] → 평균 ≈676.3, SD ≈ 40.2분(<90) → 팩터 없음
        var vitals = VitalsSnapshot(hrvMs: reading(60, 60),
                                    restingHR: reading(52, 52),
                                    sleepHours: 7)
        let bedtimes: [Double] = [660, 660, 660, 660, 660, 660, 775]
        vitals.sleepNights = bedtimes.enumerated().map { night(daysAgo: Double($0.offset), bedtime: $0.element) }
        let report = try #require(BatteryEngine.compute(vitals: vitals, runs: [], now: now))
        #expect(report.level == 50)
        #expect(!report.factors.contains { $0.name == "수면 리듬" })
    }
}
