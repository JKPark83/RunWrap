package com.jkpark.runwrap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// 열 보정 페이스 엔진 검증 — 산식 경계·상한·미노출 가드. iOS `HeatEngineTests` 이식 (6개 전부).
class HeatEngineTest {
    @Test
    fun `열 점수 38 이하는 보정하지 않는다 - 선선한 날`() {
        // 15°C·습도 50% → 이슬점 4.7°C, 열 점수 19.7 — 보정 구간 밖
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 15.0, humidityPct = 50.0))
    }

    @Test
    fun `한여름 예시 - 28도 70퍼센트면 열 점수 약 50, 보정 약 24초`() {
        // 이슬점 ≈ 22.0°C → 열 점수 ≈ 50.0.
        // 보정 = (46−38)×1.5 + (50−46)×3.0 ≈ 24초/km → 370 − 24 = 346
        val adj = HeatEngine.adjustment(paceSecPerKm = 370.0, tempC = 28.0, humidityPct = 70.0)
        assertNotNull(adj)
        assertEquals(50.0, adj!!.heatScore, 0.5)
        assertEquals(24.0, adj.deltaSecPerKm, 0.5)
        assertEquals(346.0, adj.adjustedPaceSecPerKm, 0.5)
    }

    @Test
    fun `폭염 상한 - 보정량은 90초를 넘지 않는다`() {
        // 38°C·습도 95% → 열 점수가 70을 훌쩍 넘어 원보정이 90 초과 → 상한 90 고정
        val adj = HeatEngine.adjustment(paceSecPerKm = 300.0, tempC = 38.0, humidityPct = 95.0)
        assertNotNull(adj)
        assertEquals(90.0, adj!!.deltaSecPerKm, 1e-9)
        assertEquals(210.0, adj.adjustedPaceSecPerKm, 0.001)
    }

    @Test
    fun `센서 이상치·결측 가드 - 습도 범위 밖이거나 값이 없으면 null`() {
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = 0.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = 110.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = null, humidityPct = 70.0))
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 25.0, humidityPct = null))
    }

    @Test
    fun `노이즈 플로어 - 보정량 3초 미만은 보여주지 않는다`() {
        // 24°C·습도 60% → 열 점수 ≈ 39.75, 보정 ≈ 2.63초 < 3초
        assertNull(HeatEngine.adjustment(paceSecPerKm = 360.0, tempC = 24.0, humidityPct = 60.0))
    }

    @Test
    fun `일관성 - 보정 페이스는 항상 실제 페이스에서 보정량을 뺀 값이다`() {
        val pace = 400.0
        val adj = HeatEngine.adjustment(paceSecPerKm = pace, tempC = 30.0, humidityPct = 75.0)
        assertNotNull(adj)
        assertEquals(pace - adj!!.deltaSecPerKm, adj.adjustedPaceSecPerKm, 0.0001)
    }
}
