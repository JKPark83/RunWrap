import Foundation

/// 러닝 1회 요약 — HKWorkout에서 화면에 필요한 값만 추린다
struct RunSummary: Identifiable, Equatable {
    let id: UUID
    let start: Date
    let durationSec: Double
    let distanceMeters: Double?
    let avgHeartRate: Double?

    var distanceKm: Double? { distanceMeters.map { $0 / 1000 } }

    /// 평균 페이스 (초/km) — 거리가 너무 짧으면 의미가 없어 nil
    var paceSecPerKm: Double? {
        guard let km = distanceKm, km > 0.1 else { return nil }
        return durationSec / km
    }
}
