import Foundation

/// 합성 러닝 약 6개월 — 시뮬레이터 기본 데이터이자 실기기 "샘플 리포트 둘러보기"의 재료
///
/// 최근 4주는 주간 리포트 3개 지표가 서로 다른 톤으로 모두 계산되도록 고정 배열로 유지:
/// 주간 증가율 +23%(경고) · ACWR 1.4(다소 높음) · 심박 효율 +6%(체력 상승)
///
/// 그 이전 22주는 시드 고정(0xC0FFEE) 생성 — 월이 지날수록 페이스가 월 −2초/km씩
/// 완만히 향상되는 패턴을 심어 장기 추이(발전상) 화면 검증에 쓴다 (계획서 M0).
enum DemoData {
    static var runs: [RunSummary] { recentTuned + history }

    /// 최근 4주(1~26일 전) — 리포트 홈 톤 시나리오에 맞춘 고정 배열.
    /// 생성 러닝이 28일 창(증가율·ACWR·EF 계산 구간)에 섞이면 톤이 바뀌므로
    /// 이 구간만은 손으로 조정한 값을 유지한다.
    /// 케이던스는 최근 2주 평균 168 vs 이전 2주 165.4로 심어
    /// 주법 추이 카드가 개선 톤(+2 spm 이상)으로 계산되게 한다 (계획서 M4).
    private static var recentTuned: [RunSummary] {
        [
            run(daysAgo: 1, km: 10, minPerKm: 6.1, hr: 145, cadence: 171),
            run(daysAgo: 3, km: 8, minPerKm: 5.9, hr: 147, cadence: 170),
            run(daysAgo: 5, km: 6.6, minPerKm: 6.0, hr: 144, indoor: true, cadence: 169),
            run(daysAgo: 8, km: 10, minPerKm: 6.2, hr: 146, cadence: 167),
            run(daysAgo: 10, km: 6, minPerKm: 5.8, hr: 148, cadence: 166),
            run(daysAgo: 12, km: 4, minPerKm: 6.0, hr: 145, indoor: true, cadence: 165),
            run(daysAgo: 16, km: 6, minPerKm: 6.1, hr: 153, cadence: 166),
            run(daysAgo: 18, km: 5, minPerKm: 6.0, hr: 152, indoor: true, cadence: 165),
            run(daysAgo: 20, km: 5, minPerKm: 6.2, hr: 154, cadence: 166),
            run(daysAgo: 23, km: 6, minPerKm: 6.0, hr: 153, cadence: 165),
            run(daysAgo: 26, km: 5, minPerKm: 6.1, hr: 155, indoor: true, cadence: 165),
        ]
    }

    /// 4~25주 전 — 주 2~3회, 거리 6~18km 변주.
    /// daysAgo가 항상 28을 넘도록 배치해 최근 4주 지표 창을 침범하지 않는다.
    private static var history: [RunSummary] {
        var rng = SplitMix64(seed: 0xC0FFEE)
        return (4..<26).flatMap { week -> [RunSummary] in
            let count = 2 + Int(rng.next() % 2)
            return (0..<count).map { slot in
                let daysAgo = Double(week) * 7 + 0.5 + Double(slot) * 2.2 + rng.unit() * 1.4
                let monthsAgo = Double(week) / 4.33
                let basePace = 6.0 + monthsAgo * 2 / 60  // 과거일수록 느림 = 현재로 오며 향상
                return run(daysAgo: daysAgo,
                           km: 6 + rng.unit() * 12,
                           minPerKm: basePace + (rng.unit() - 0.5) * 0.15,
                           hr: 144 + rng.unit() * 10,
                           indoor: slot == 1)  // 주 1회꼴 실내(트레드밀) 세션
            }
        }
    }

    private static func run(daysAgo: Double, km: Double, minPerKm: Double,
                            hr: Double, indoor: Bool = false,
                            cadence: Double? = nil) -> RunSummary {
        RunSummary(id: UUID(),
                   start: Date().addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr,
                   calories: km * 62,  // 체중 70kg 언저리 러닝 소모 근사 (≈1.036 kcal/kg/km)
                   isIndoor: indoor,
                   cadenceSpm: cadence)
    }

    /// 합성 몸무게 — 8주에 걸친 완만한 감량(약 −1.3kg), 주 2~3회 측정 (계획서 M2)
    static var bodyMass: [(date: Date, kg: Double)] {
        var rng = SplitMix64(seed: 0xD1E7)
        return (0..<8).flatMap { week -> [(date: Date, kg: Double)] in
            let count = 2 + Int(rng.next() % 2)
            return (0..<count).map { slot in
                let daysAgo = Double(week) * 7 + Double(slot) * 2 + rng.unit()
                let kg = 70.4 + Double(week) * 0.18 + (rng.unit() - 0.5) * 0.4  // 과거일수록 무겁다
                return (date: Date().addingTimeInterval(-daysAgo * 86_400), kg: kg)
            }
        }
    }

    /// 합성 VO₂max — 12주에 걸친 완만한 상승(주 +0.3), 주 1~2회 추정 기록.
    /// 4주 전 대비 +1.2 언저리가 되도록 기울여 심폐 체력 카드가 개선 톤으로 계산되게 한다.
    static var vo2Max: [(date: Date, value: Double)] {
        var rng = SplitMix64(seed: 0xF17)
        return (0..<12).flatMap { week -> [(date: Date, value: Double)] in
            let count = 1 + Int(rng.next() % 2)
            return (0..<count).map { slot in
                let daysAgo = Double(week) * 7 + Double(slot) * 3 + rng.unit() * 2
                let value = 45.2 - Double(week) * 0.3 + (rng.unit() - 0.5) * 0.5  // 과거일수록 낮다
                return (date: Date().addingTimeInterval(-daysAgo * 86_400), value: value)
            }
        }
    }

    /// 합성 활력징후 — 과부하 주간 시나리오에 맞춘 "회복 덜 됨" 상태
    ///
    /// HRV 하락 + 안정 심박 상승 + 수면 부족 + ACWR 초과가 겹쳐
    /// 체력 배터리가 20%대(주의)로 계산되도록 맞춰 놓았다.
    static var vitals: VitalsSnapshot {
        VitalsSnapshot(hrvMs: .init(today: 55, baseline: 62, baselineDays: 28),
                       restingHR: .init(today: 54, baseline: 51, baselineDays: 28),
                       respiratoryRate: .init(today: 14.6, baseline: 14.2, baselineDays: 28),
                       wristTempC: .init(today: 36.5, baseline: 36.4, baselineDays: 21),
                       sleepHours: 6.8)
    }
}

/// 재현 가능한 경량 난수 — WorkoutDetailStore.synthetic의 것과 같은 구현.
/// 그쪽은 시뮬레이터 전용 private이고 DemoData는 실기기 샘플 리포트에서도 쓰여 별도로 둔다.
private struct SplitMix64 {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state = state &+ 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    /// 0..<1
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
}
