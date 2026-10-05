package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/// iOS WeatherAdviceRulesTests.swift의 세 스위트를 @Nested로 옮긴다.
class WeatherAdviceRulesTests {

    /// 날씨 조언 룰 — 온도·습도 구간과 부가 조건(바람·비·자외선·뇌우), 심각도 정렬 검증
    @Nested
    inner class WeatherAdviceRulesTests {
        @Test
        @DisplayName("폭염+고습 — overload 2건(폭염·고온다습)이 앞, 자외선 caution이 뒤")
        fun heatAndHumidity() {
            // 34°C → 폭염 overload, 습도 85%·체감 24 이상 → 고온다습 overload, UV 8 → caution
            val items = WeatherAdviceRules.advice(apparentC = 34.0, humidityPct = 85.0, windMs = 1.0,
                                                  precipitationMm = 0.0, uvIndex = 8.0, weatherCode = 0)
            assertEquals(3, items.size)
            assertEquals(RRTone.overload, items[0].tone)
            assertEquals(RRTone.overload, items[1].tone)
            assertEquals(RRTone.caution, items[2].tone)
        }

        @Test
        @DisplayName("최적 조건 — 20°C·습도 50%는 improving 온도 조언 1건뿐")
        fun ideal() {
            val items = WeatherAdviceRules.advice(apparentC = 20.0, humidityPct = 50.0, windMs = 2.0,
                                                  precipitationMm = 0.0, uvIndex = 3.0, weatherCode = 0)
            assertEquals(1, items.size)
            assertEquals(RRTone.improving, items[0].tone)
        }

        @Test
        @DisplayName("비+강풍 — 선선 조언에 바람·강수 caution이 더해지고 caution이 앞에 온다")
        fun rainAndWind() {
            // 12°C → 선선 steady, 바람 9m/s ≥ 8 → caution, 강수 1.2mm → caution
            val items = WeatherAdviceRules.advice(apparentC = 12.0, humidityPct = 60.0, windMs = 9.0,
                                                  precipitationMm = 1.2, uvIndex = 0.0, weatherCode = 61)
            assertEquals(3, items.size)
            assertEquals(RRTone.caution, items[0].tone)
            assertEquals(RRTone.steady, items[2].tone)
        }

        @Test
        @DisplayName("뇌우 — 좋은 온도여도 중단 권고가 최우선으로 온다")
        fun thunderstorm() {
            val items = WeatherAdviceRules.advice(apparentC = 20.0, humidityPct = 50.0, windMs = 0.0,
                                                  precipitationMm = 5.0, uvIndex = null, weatherCode = 95)
            assertEquals(RRTone.overload, items.firstOrNull()?.tone)
            assertEquals(true, items.firstOrNull()?.text?.contains("뇌우"))
        }

        @Test
        @DisplayName("건조 — 습도 30% 이하면 수분 조언이 붙는다")
        fun dry() {
            val items = WeatherAdviceRules.advice(apparentC = 20.0, humidityPct = 25.0, windMs = 0.0,
                                                  precipitationMm = 0.0, uvIndex = null, weatherCode = null)
            assertEquals(2, items.size)
            assertTrue(items.any { it.text.contains("건조") })
        }
    }

    /// 비 판정 단일 기준 — WMO 코드 + 강수량, 뇌우 포함 (이슈 #109)
    @Nested
    inner class IsRainingTests {
        @Test
        @DisplayName("비 코드(WMO 61)면 강수량 0mm여도 비 — 조언에 '비가 와요'가 붙는다")
        fun rainCodeWithoutPrecipitation() {
            assertTrue(WeatherAdviceRules.isRaining(code = 61, precipitationMm = 0.0))
            val items = WeatherAdviceRules.advice(apparentC = 20.0, humidityPct = 50.0, windMs = 0.0,
                                                  precipitationMm = 0.0, uvIndex = null, weatherCode = 61)
            assertTrue(items.any { it.text.contains("비가 와요") })
        }

        @Test
        @DisplayName("맑음(코드 0)·강수량 0mm면 비가 아니다")
        fun clearIsNotRain() {
            assertFalse(WeatherAdviceRules.isRaining(code = 0, precipitationMm = 0.0))
            assertFalse(WeatherAdviceRules.isRaining(code = null, precipitationMm = 0.0))
        }

        @Test
        @DisplayName("뇌우(코드 95)는 비를 동반하므로 비로 본다")
        fun thunderstormIsRain() {
            assertTrue(WeatherAdviceRules.isRaining(code = 95, precipitationMm = 0.0))
        }
    }

    /// 러닝 이름 헤드라인 — 우선순위(뇌우 > 눈 > 비 > 온도)와 온도 구간별 이름 검증
    @Nested
    inner class RunNameTests {
        @Test
        @DisplayName("뇌우 — 좋은 온도여도 트밀런이 최우선")
        fun thunderstormWins() {
            val name = WeatherAdviceRules.runName(apparentC = 20.0, precipitationMm = 5.0, weatherCode = 95)
            assertEquals(RunName.Kind.treadmill, name.kind)
            assertEquals(RRTone.overload, name.tone)
        }

        @Test
        @DisplayName("눈 — 영하여도 펭귄런이 아니라 설중런이 먼저")
        fun snowBeatsFreezing() {
            val name = WeatherAdviceRules.runName(apparentC = -2.0, precipitationMm = 1.0, weatherCode = 71)
            assertEquals(RunName.Kind.snow, name.kind)
            assertEquals("설중런", name.title)
        }

        @Test
        @DisplayName("비 — 폭염이어도 우중런이 찜런보다 먼저")
        fun rainBeatsHeat() {
            val name = WeatherAdviceRules.runName(apparentC = 34.0, precipitationMm = 1.2, weatherCode = 61)
            assertEquals(RunName.Kind.rain, name.kind)
            assertEquals("우중런", name.title)
        }

        @Test
        @DisplayName("강수량만 있어도 우중런 — 코드가 맑음(0)이어도 강수 우선")
        fun precipitationOnly() {
            val name = WeatherAdviceRules.runName(apparentC = 20.0, precipitationMm = 0.4, weatherCode = 0)
            assertEquals(RunName.Kind.rain, name.kind)
        }

        @Test
        @DisplayName("온도 구간 — 34°C 찜런, 20°C 펀런, 12°C 청량런, -3°C 펭귄런")
        fun temperatureBuckets() {
            assertEquals("찜런", WeatherAdviceRules.runName(apparentC = 34.0, precipitationMm = 0.0, weatherCode = 0).title)
            assertEquals("펀런", WeatherAdviceRules.runName(apparentC = 20.0, precipitationMm = 0.0, weatherCode = 0).title)
            assertEquals("청량런", WeatherAdviceRules.runName(apparentC = 12.0, precipitationMm = 0.0, weatherCode = 0).title)
            assertEquals("펭귄런", WeatherAdviceRules.runName(apparentC = -3.0, precipitationMm = 0.0, weatherCode = null).title)
        }

        @Test
        @DisplayName("펀런 톤 — 16~24°C는 improving으로 advice의 온도 톤과 일치")
        fun funTone() {
            val name = WeatherAdviceRules.runName(apparentC = 20.0, precipitationMm = 0.0, weatherCode = 1)
            assertEquals(RunName.Kind.`fun`, name.kind)
            assertEquals(RRTone.improving, name.tone)
        }
    }
}
