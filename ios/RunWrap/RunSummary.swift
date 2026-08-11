import Foundation

/// 러닝 1회 요약 — HKWorkout에서 화면에 필요한 값만 추린다
struct RunSummary: Identifiable, Equatable {
    let id: UUID
    let start: Date
    let durationSec: Double
    let distanceMeters: Double?
    let avgHeartRate: Double?
    /// 세션 소모 칼로리(kcal) — 다이어트 카드의 주간 합계 재료 (기획서 §4.5)
    let calories: Double?
    /// 실내(트레드밀) 여부 — 세션 표시(배지·지도 미노출)에만 쓰고 집계는 통합한다 (기획서 §4.6)
    let isIndoor: Bool
    /// 평균 케이던스(spm) — 주법 추이(계획서 M4) 재료. 걸음 수 쿼리 비용 때문에
    /// 최근 28일 워크아웃에만 채워진다 (HealthStore가 목록 조회 뒤 백필).
    var cadenceSpm: Double?

    /// 뒤에 붙은 필드들의 기본값을 위한 명시적 init — 기존 호출부(테스트 포함)를 깨지 않는다
    init(id: UUID, start: Date, durationSec: Double,
         distanceMeters: Double?, avgHeartRate: Double?,
         calories: Double? = nil, isIndoor: Bool = false,
         cadenceSpm: Double? = nil) {
        self.id = id
        self.start = start
        self.durationSec = durationSec
        self.distanceMeters = distanceMeters
        self.avgHeartRate = avgHeartRate
        self.calories = calories
        self.isIndoor = isIndoor
        self.cadenceSpm = cadenceSpm
    }

    var distanceKm: Double? { distanceMeters.map { $0 / 1000 } }

    /// 평균 페이스 (초/km) — 거리가 너무 짧으면 의미가 없어 nil
    var paceSecPerKm: Double? {
        guard let km = distanceKm, km > 0.1 else { return nil }
        return durationSec / km
    }
}
