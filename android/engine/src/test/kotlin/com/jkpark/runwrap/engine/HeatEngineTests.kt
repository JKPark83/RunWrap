package com.jkpark.runwrap.engine

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 열 보정 페이스 엔진 — Magnus 이슬점 → 열 점수 → 보정량 산식과 미노출 가드 검증
class HeatEngineTests {
    @Test
    @DisplayName("서늘한 날 — 15°C/50%는 열 점수 20 안팎이라 보정 없이 nil")
    fun coolDayReturnsNil() {
        // 15°C/50% → 이슬점 ≈4.65 → 열 점수 ≈19.65 (38 이하) → 보정 없음
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 15.0, humidityPct = 50.0))
    }

    @Test
    @DisplayName("전형적 여름 — 28°C/70%는 이슬점 22.0·열 점수 50.0으로 24초/km 보정")
    fun typicalSummerDay() {
        // 28°C/70% → 이슬점 ≈22.0 → 열 점수 ≈50.0
        // → (46−38)×1.5 + (50.0−46)×3.0 = 12 + 12.0 = 24.0초/km
        val result = assertNotNull(HeatEngine.adjustment(paceSecPerKm = 370.0, tempC = 28.0, humidityPct = 70.0))
        assertTrue(abs(result.heatScore - 50.0) < 0.5)
        assertTrue(abs(result.deltaSecPerKm - 24.0) < 0.5)
        assertTrue(abs(result.adjustedPaceSecPerKm - 346.0) < 0.5)
    }

    @Test
    @DisplayName("폭염 — 38°C/95%는 보정량이 상한 90초/km에 클램프된다")
    fun extremeHeatClamps() {
        // 38°C/95% → 이슬점 ≈37.0 → 열 점수 ≈75.0 → 원 보정량 ≈99초 → 90으로 클램프
        val result = assertNotNull(HeatEngine.adjustment(paceSecPerKm = 300.0, tempC = 38.0, humidityPct = 95.0))
        assertEquals(90.0, result.deltaSecPerKm)
        assertTrue(abs(result.adjustedPaceSecPerKm - 210.0) < 0.001)
    }

    @Test
    @DisplayName("습도 이상치·결측 — 0%·110%, 기온·습도 nil은 모두 nil")
    fun invalidOrMissingInputsReturnNil() {
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = 0.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = 110.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = null, humidityPct = 70.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = null))
    }

    @Test
    @DisplayName("경계 노이즈 — 24°C/60%는 열 점수 38 언저리라 보정량 3초 미만으로 nil")
    fun boundaryNoiseReturnsNil() {
        // 24°C/60% → 이슬점 ≈15.75 → 열 점수 ≈39.75 → 보정량 ≈2.63초/km (< 3) → nil
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 24.0, humidityPct = 60.0))
    }

    @Test
    @DisplayName("보정 페이스 관계 — adjustedPace는 항상 pace − delta")
    fun adjustedPaceEqualsRawMinusDelta() {
        val pace = 400.0
        val result = assertNotNull(HeatEngine.adjustment(paceSecPerKm = pace, tempC = 30.0, humidityPct = 75.0))
        assertTrue(abs(result.adjustedPaceSecPerKm - (pace - result.deltaSecPerKm)) < 0.0001)
    }
}
