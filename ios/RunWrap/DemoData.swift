import Foundation

/// 합성 러닝 4주치 — 시뮬레이터 기본 데이터이자 실기기 "샘플 리포트 둘러보기"의 재료
///
/// 주간 리포트 3개 지표가 서로 다른 톤으로 모두 계산되도록 구성:
/// 주간 증가율 +23%(경고) · ACWR 1.4(다소 높음) · 심박 효율 +6%(체력 상승)
enum DemoData {
    static var runs: [RunSummary] {
        func run(daysAgo: Double, km: Double, minPerKm: Double, hr: Double) -> RunSummary {
            RunSummary(id: UUID(),
                       start: Date().addingTimeInterval(-daysAgo * 86_400),
                       durationSec: km * minPerKm * 60,
                       distanceMeters: km * 1000,
                       avgHeartRate: hr)
        }
        return [
            run(daysAgo: 1, km: 10, minPerKm: 6.1, hr: 145),
            run(daysAgo: 3, km: 8, minPerKm: 5.9, hr: 147),
            run(daysAgo: 5, km: 6.6, minPerKm: 6.0, hr: 144),
            run(daysAgo: 8, km: 10, minPerKm: 6.2, hr: 146),
            run(daysAgo: 10, km: 6, minPerKm: 5.8, hr: 148),
            run(daysAgo: 12, km: 4, minPerKm: 6.0, hr: 145),
            run(daysAgo: 16, km: 6, minPerKm: 6.1, hr: 153),
            run(daysAgo: 18, km: 5, minPerKm: 6.0, hr: 152),
            run(daysAgo: 20, km: 5, minPerKm: 6.2, hr: 154),
            run(daysAgo: 23, km: 6, minPerKm: 6.0, hr: 153),
            run(daysAgo: 26, km: 5, minPerKm: 6.1, hr: 155),
        ]
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
