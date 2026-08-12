import Foundation

/// 데모 모드 게이트 — 합성 데이터(DemoData)를 실기기에서도 켤 수 있게 하는 스위치
///
/// 왜 필요한가: 심사자 아이폰에는 애플워치 러닝 기록이 없다. 표본이 없으면 이 앱은
/// 설계상 지표를 아예 내지 않으므로(미노출 가드) 화면이 텅 비고, App Store 심사
/// 지침 2.1(App Completeness)에 걸린다. 애플은 이런 경우 앱에 내장된 데모 모드를
/// 허용하되 심사 노트에 켜는 방법을 밝히라고 요구한다 — 숨긴 기능이 아니어야
/// 지침 2.3.1에도 걸리지 않으므로 설정 화면에 그대로 노출한다.
///
/// 시뮬레이터는 워치 기록이 있을 수 없어 토글과 무관하게 항상 켜진 것으로 다룬다.
enum DemoMode {
    static let key = "demoModeEnabled"

    /// 설정 화면 토글의 저장값 — 시뮬레이터에서는 의미가 없다(항상 활성)
    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    /// 합성 데이터를 쓸지 여부. HealthKit 조회 경로를 타기 전에 이것부터 확인한다.
    static var isActive: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        isEnabled
        #endif
    }
}

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
    /// 날씨는 야외 세션 일부에만 심는다 — 한여름(열 점수 46 초과, 보정 큼)·
    /// 초여름(38~46 구간)·선선한 날(38 이하 → 보정 카드 미노출 가드 확인)을 섞는다.
    private static var recentTuned: [RunSummary] {
        [
            run(daysAgo: 1, km: 10, minPerKm: 6.1, hr: 145, cadence: 171, tempC: 28, humidityPct: 72),
            run(daysAgo: 3, km: 8, minPerKm: 5.9, hr: 147, cadence: 170, tempC: 26, humidityPct: 65),
            run(daysAgo: 5, km: 6.6, minPerKm: 6.0, hr: 144, indoor: true, cadence: 169),
            run(daysAgo: 8, km: 10, minPerKm: 6.2, hr: 146, cadence: 167, tempC: 30, humidityPct: 78),
            run(daysAgo: 10, km: 6, minPerKm: 5.8, hr: 148, cadence: 166, tempC: 22, humidityPct: 55),
            run(daysAgo: 12, km: 4, minPerKm: 6.0, hr: 145, indoor: true, cadence: 165),
            run(daysAgo: 16, km: 6, minPerKm: 6.1, hr: 153, cadence: 166, tempC: 27, humidityPct: 70),
            run(daysAgo: 18, km: 5, minPerKm: 6.0, hr: 152, indoor: true, cadence: 165),
            run(daysAgo: 20, km: 5, minPerKm: 6.2, hr: 154, cadence: 166),
            run(daysAgo: 23, km: 6, minPerKm: 6.0, hr: 153, cadence: 165, tempC: 25, humidityPct: 68),
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
                            cadence: Double? = nil,
                            tempC: Double? = nil, humidityPct: Double? = nil) -> RunSummary {
        RunSummary(id: UUID(),
                   start: Date().addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr,
                   calories: km * 62,  // 체중 70kg 언저리 러닝 소모 근사 (≈1.036 kcal/kg/km)
                   isIndoor: indoor,
                   cadenceSpm: cadence,
                   weatherTempC: tempC,
                   weatherHumidityPct: humidityPct)
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
    /// HRV 하락 + 안정 심박 상승 + 심박 회복 소폭 하락 + 수면 질 저하 + ACWR 초과가
    /// 겹쳐 체력 배터리가 20%대(주의)로 계산되도록 맞춰 놓았다.
    /// 포인트 합: HRV −6, 안정 심박 −6, HRR −1, 수면 +1, 수면 질 −8, ACWR −2 → 50−22 = 28.
    /// 수면 시간은 7.1h로 충분한데 깊은잠+렘이 뚝 떨어진 시나리오 — "잤는데 얕게 잔 날".
    static var vitals: VitalsSnapshot {
        VitalsSnapshot(hrvMs: .init(today: 57, baseline: 62, baselineDays: 28),
                       restingHR: .init(today: 53, baseline: 51, baselineDays: 28),
                       hrr: .init(today: 30, baseline: 31, baselineDays: 9),  // baselineDays 자리는 표본 수
                       respiratoryRate: .init(today: 14.6, baseline: 14.2, baselineDays: 28),
                       wristTempC: .init(today: 36.5, baseline: 36.4, baselineDays: 21),
                       sleepHours: 7.1,
                       sleepNights: sleepNights)
    }

    /// 합성 밤별 수면 — 최근 14일. 마지막 밤만 깊은잠+렘 비율을 0.27로 떨어뜨려
    /// (평소 0.34~0.38, 상대 하락 20% 이상) 수면 질 감점(−8pt)이 데모에서 보이게 한다.
    /// 취침 시각은 23:30 전후 ±40분(정오 기준 650~730분)으로 규칙적이라
    /// 수면 리듬 감점(SD > 90분)은 트리거하지 않는다.
    private static var sleepNights: [VitalsSnapshot.SleepNight] {
        var rng = SplitMix64(seed: 0x5EE9)
        return (0..<14).map { i in
            let daysAgo = Double(13 - i)
            let isLatest = i == 13
            return VitalsSnapshot.SleepNight(
                date: Date().addingTimeInterval(-daysAgo * 86_400),
                asleepHours: 6.5 + rng.unit() * 1.2,
                deepRemFraction: isLatest ? 0.27 : 0.34 + rng.unit() * 0.04,
                bedtimeMinutes: 690 + (rng.unit() - 0.5) * 80)
        }
    }

    /// 합성 심박 회복(HRR) — 12주에 걸친 완만한 상승(주 +0.45bpm), 주 1~2회 야외 러닝 후 기록.
    /// 84일 창 추세(ReportEngine.hrrTrend)가 개선 톤(+2bpm 초과)으로 계산되게 한다.
    /// 배터리의 오늘 값(30, 기준선 31 대비 소폭 하락)과는 창이 다르다 —
    /// 12주 추세는 오르는 중인데 오늘 하루만 살짝 낮은, 흔한 과부하 주간 그림.
    static var hrrTrend: [(date: Date, value: Double)] {
        var rng = SplitMix64(seed: 0x48EA)
        return (0..<12).flatMap { week -> [(date: Date, value: Double)] in
            let count = 1 + Int(rng.next() % 2)
            return (0..<count).map { slot in
                let daysAgo = Double(week) * 7 + Double(slot) * 3 + rng.unit() * 2
                let value = 31.2 - Double(week) * 0.45 + (rng.unit() - 0.5) * 1.5  // 과거일수록 낮다
                return (date: Date().addingTimeInterval(-daysAgo * 86_400), value: value)
            }
        }
    }

    /// 합성 크로스 트레이닝 — 이번 주 자전거 90분 + 근력 45분 (계 2시간 15분).
    /// 걷기 25분 세션은 CrossTrainingEngine의 30분 미만 걷기 가드에 걸러지는 걸 확인하는 재료.
    static var crossTrainings: [CrossTraining] {
        [
            CrossTraining(start: Date().addingTimeInterval(-2 * 86_400),
                          durationSec: 90 * 60, kind: .cycling, kcal: 520),
            CrossTraining(start: Date().addingTimeInterval(-4 * 86_400),
                          durationSec: 45 * 60, kind: .strength, kcal: 210),
            CrossTraining(start: Date().addingTimeInterval(-5 * 86_400),
                          durationSec: 25 * 60, kind: .walking, kcal: 90),
        ]
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
