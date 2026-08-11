import Foundation

/// 러닝 복장 아이템 — 룰(엔진)은 조합까지만 정하고 아이콘·라벨은 화면이 매핑한다
/// (RRTone과 같은 분리 — 엔진은 UI를 모른다)
enum OutfitItem: String, CaseIterable, Equatable {
    case singlet, shortSleeve, longSleeve, shorts, tights
    case jacket, gloves, windbreaker
    case waterproofCap, waterproofJacket
    case thermalTop, thermalBottom, beanie
}

/// 복장 룰 — 체감온도 구간 × 강수 × 바람 → 복장 조합.
/// 구간·가산 조건은 계획서 M6 표 그대로 (가정 — 일러스트 시안 후 미세 조정 예정).
enum OutfitRules {
    /// 바람막이를 더하는 풍속 하한 (m/s)
    static let windbreakerMs = 8.0

    static func outfit(apparentC: Double, precipitationMm: Double,
                       windMs: Double) -> [OutfitItem] {
        let raining = precipitationMm > 0
        switch apparentC {
        case 24...:
            return raining ? [.singlet, .shorts, .waterproofCap] : [.singlet, .shorts]
        case 16..<24:
            return windMs >= windbreakerMs
                ? [.shortSleeve, .shorts, .windbreaker] : [.shortSleeve, .shorts]
        case 8..<16:
            return raining ? [.longSleeve, .tights, .waterproofJacket] : [.longSleeve, .tights]
        case 0..<8:
            return [.jacket, .gloves]
        default:  // 영하
            return [.thermalTop, .thermalBottom, .beanie, .gloves]
        }
    }
}
