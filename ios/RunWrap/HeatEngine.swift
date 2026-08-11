import Foundation

/// 열 보정 페이스 엔진 — 워치가 야외 러닝에 자동으로 붙이는 기온·습도 메타데이터로
/// 더위가 페이스에 미친 영향을 "보정 페이스"로 환산한다 (기획 문서 "HealthKit 미활용
/// 데이터 활용 제안 A1"). "오늘 6′10″는 열 보정하면 5′52″ 수준이에요" 같은 문장의 재료.
///
/// 산식:
/// - 이슬점: Magnus 공식 (α=17.62, β=243.12°C).
///   γ = ln(RH/100) + α·T/(β+T), Td = β·γ/(α−γ)
/// - 열 점수 = 기온(°C) + 이슬점(°C) — 러닝 커뮤니티에서 쓰는
///   "temp + dew point" 더위 체감 보정표 관행
/// - 보정량(초/km): 열 점수 38 이하는 보정 없음. 38~46 구간은 1점당 +1.5초/km,
///   46 초과 구간은 1점당 +3.0초/km 누진. 상한 +90초/km.
///
/// 가드: 기온·습도가 없거나 센서 이상치(습도 1~100% 밖, 기온 −30~55°C 밖)면,
/// 페이스가 0 이하면, 보정량이 3초/km 미만(38점 언저리 노이즈)이면, 보정 후 페이스가
/// 0 이하가 되면 nil을 반환한다 — "틀린 인사이트는 없느니만 못하다."
enum HeatEngine {
    struct Adjustment: Equatable {
        let adjustedPaceSecPerKm: Double  // 열 보정 페이스 (실제 페이스 − 보정량)
        let deltaSecPerKm: Double         // 보정량 (양수, 초/km)
        let heatScore: Double             // 열 점수 = 기온 + 이슬점 (°C 합)
        let tempC: Double
        let humidityPct: Double
    }

    private static let noAdjustmentThreshold = 38.0   // 이 이하면 열 보정 없음
    private static let steepBandStart = 46.0          // 이 초과부터 가중치가 커진다
    private static let midBandSecPerPoint = 1.5
    private static let highBandSecPerPoint = 3.0
    private static let deltaCapSecPerKm = 90.0
    private static let noiseFloorSecPerKm = 3.0       // 이 미만은 보여줄 가치 없는 노이즈

    static func adjustment(paceSecPerKm: Double, tempC: Double?, humidityPct: Double?) -> Adjustment? {
        guard paceSecPerKm > 0,
              let tempC, let humidityPct,
              (-30...55).contains(tempC),
              (1...100).contains(humidityPct) else { return nil }

        let dewPointC = dewPoint(tempC: tempC, humidityPct: humidityPct)
        let heatScore = tempC + dewPointC
        let delta = min(deltaCapSecPerKm, rawDelta(heatScore: heatScore))
        guard delta >= noiseFloorSecPerKm else { return nil }

        let adjustedPace = paceSecPerKm - delta
        guard adjustedPace > 0 else { return nil }

        return Adjustment(adjustedPaceSecPerKm: adjustedPace, deltaSecPerKm: delta,
                          heatScore: heatScore, tempC: tempC, humidityPct: humidityPct)
    }

    // MARK: - 내부

    /// Magnus 공식 — 기상학에서 흔히 쓰는 이슬점 근사 (α=17.62, β=243.12°C)
    private static func dewPoint(tempC: Double, humidityPct: Double) -> Double {
        let alpha = 17.62
        let beta = 243.12
        let gamma = log(humidityPct / 100) + alpha * tempC / (beta + tempC)
        return beta * gamma / (alpha - gamma)
    }

    /// 38~46 구간은 1점당 1.5초, 46 초과는 1점당 3.0초 — 상한 적용 전 값
    private static func rawDelta(heatScore: Double) -> Double {
        guard heatScore > noAdjustmentThreshold else { return 0 }
        let midBand = min(heatScore, steepBandStart) - noAdjustmentThreshold
        let highBand = max(0, heatScore - steepBandStart)
        return midBand * midBandSecPerPoint + highBand * highBandSecPerPoint
    }
}
