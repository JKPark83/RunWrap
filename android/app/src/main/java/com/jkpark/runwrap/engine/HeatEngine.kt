package com.jkpark.runwrap.engine

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/// 열 보정 페이스 엔진 — 야외 러닝에 붙는 기온·습도 메타데이터로 더위가 페이스에 미친
/// 영향을 "보정 페이스"로 환산한다 (기획 문서 "HealthKit 미활용 데이터 활용 제안 A1").
/// "오늘 6′10″는 열 보정하면 5′52″ 수준이에요" 같은 문장의 재료. iOS `HeatEngine.swift` 이식.
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
/// 0 이하가 되면 null을 반환한다 — "틀린 인사이트는 없느니만 못하다."
object HeatEngine {
    data class Adjustment(
        val adjustedPaceSecPerKm: Double, // 열 보정 페이스 (실제 페이스 − 보정량)
        val deltaSecPerKm: Double,        // 보정량 (양수, 초/km)
        val heatScore: Double,            // 열 점수 = 기온 + 이슬점 (°C 합)
        val tempC: Double,
        val humidityPct: Double,
    )

    private const val NO_ADJUSTMENT_THRESHOLD = 38.0  // 이 이하면 열 보정 없음
    private const val STEEP_BAND_START = 46.0         // 이 초과부터 가중치가 커진다
    private const val MID_BAND_SEC_PER_POINT = 1.5
    private const val HIGH_BAND_SEC_PER_POINT = 3.0
    private const val DELTA_CAP_SEC_PER_KM = 90.0
    private const val NOISE_FLOOR_SEC_PER_KM = 3.0    // 이 미만은 보여줄 가치 없는 노이즈

    fun adjustment(paceSecPerKm: Double, tempC: Double?, humidityPct: Double?): Adjustment? {
        if (paceSecPerKm <= 0 || tempC == null || humidityPct == null) return null
        if (tempC !in -30.0..55.0 || humidityPct !in 1.0..100.0) return null

        val dewPointC = dewPoint(tempC, humidityPct)
        val heatScore = tempC + dewPointC
        val delta = min(DELTA_CAP_SEC_PER_KM, rawDelta(heatScore))
        if (delta < NOISE_FLOOR_SEC_PER_KM) return null

        val adjustedPace = paceSecPerKm - delta
        if (adjustedPace <= 0) return null

        return Adjustment(adjustedPaceSecPerKm = adjustedPace, deltaSecPerKm = delta,
                          heatScore = heatScore, tempC = tempC, humidityPct = humidityPct)
    }

    // MARK: - 내부

    /// Magnus 공식 — 기상학에서 흔히 쓰는 이슬점 근사 (α=17.62, β=243.12°C)
    private fun dewPoint(tempC: Double, humidityPct: Double): Double {
        val alpha = 17.62
        val beta = 243.12
        val gamma = ln(humidityPct / 100) + alpha * tempC / (beta + tempC)
        return beta * gamma / (alpha - gamma)
    }

    /// 38~46 구간은 1점당 1.5초, 46 초과는 1점당 3.0초 — 상한 적용 전 값
    private fun rawDelta(heatScore: Double): Double {
        if (heatScore <= NO_ADJUSTMENT_THRESHOLD) return 0.0
        val midBand = min(heatScore, STEEP_BAND_START) - NO_ADJUSTMENT_THRESHOLD
        val highBand = max(0.0, heatScore - STEEP_BAND_START)
        return midBand * MID_BAND_SEC_PER_POINT + highBand * HIGH_BAND_SEC_PER_POINT
    }
}
