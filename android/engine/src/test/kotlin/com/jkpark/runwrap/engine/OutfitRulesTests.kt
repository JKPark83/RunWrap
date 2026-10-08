package com.jkpark.runwrap.engine

import com.jkpark.runwrap.engine.OutfitItem.beanie
import com.jkpark.runwrap.engine.OutfitItem.gloves
import com.jkpark.runwrap.engine.OutfitItem.jacket
import com.jkpark.runwrap.engine.OutfitItem.longSleeve
import com.jkpark.runwrap.engine.OutfitItem.neckWarmer
import com.jkpark.runwrap.engine.OutfitItem.shortSleeve
import com.jkpark.runwrap.engine.OutfitItem.shorts
import com.jkpark.runwrap.engine.OutfitItem.singlet
import com.jkpark.runwrap.engine.OutfitItem.sunCap
import com.jkpark.runwrap.engine.OutfitItem.sunglasses
import com.jkpark.runwrap.engine.OutfitItem.sunscreen
import com.jkpark.runwrap.engine.OutfitItem.thermalBottom
import com.jkpark.runwrap.engine.OutfitItem.thermalTop
import com.jkpark.runwrap.engine.OutfitItem.tights
import com.jkpark.runwrap.engine.OutfitItem.waterproofCap
import com.jkpark.runwrap.engine.OutfitItem.waterproofJacket
import com.jkpark.runwrap.engine.OutfitItem.windbreaker
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 복장 룰 — "달릴 때 기온"(실제 기온 + 10°C) 경계값 + 바람·강수·자외선·계절 가산(습도는 무관)
/// + 출발 직후 안내 문구 + fixture 디코드 검증 (계획서 M6, 이슈 #219)
class OutfitRulesTests {
    /// 고정 시각 — 계절 가산이 없는 봄(4월)을 기본으로 쓴다
    private val spring = iso("2026-04-15T10:00:00+09:00")
    private val summer = iso("2026-07-15T10:00:00+09:00")
    private val winter = iso("2027-01-15T10:00:00+09:00")

    /// 기본값(맑음·무풍·건조하지 않은 습도 50%)으로 실제 기온만 바꿔 호출하는 헬퍼
    /// (Android: iOS `Calendar.current` 자리에 testZone을 주입한다)
    private fun outfit(temperatureC: Double, humidityPct: Double = 50.0, windMs: Double = 0.0,
                       precipitationMm: Double = 0.0, weatherCode: Int? = null,
                       uvIndex: Double? = null, now: Instant? = null): List<OutfitItem> =
        OutfitRules.outfit(temperatureC = temperatureC, humidityPct = humidityPct, windMs = windMs,
                           precipitationMm = precipitationMm, weatherCode = weatherCode,
                           uvIndex = uvIndex, now = now ?: spring, zone = testZone)

    @Test
    @DisplayName("기온 +10 기준 — 실제 5°C는 달릴 때 15°C 구간 복장(긴팔+타이츠)")
    fun plusTenBasis() {
        // 5 + 10 = 15 → 8~16 구간
        assertEquals(listOf(longSleeve, tights), outfit(temperatureC = 5.0))
    }

    @Test
    @DisplayName("기온 경계 — 실제 13.9°C(달릴 때 23.9°C)는 반팔, 14.0°C부터 싱글렛")
    fun temperatureBoundary24() {
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 13.9))
        assertEquals(listOf(singlet, shorts), outfit(temperatureC = 14.0))
    }

    @Test
    @DisplayName("기온 경계 — 실제 5.9°C(달릴 때 15.9°C)는 긴팔·타이츠, 6.0°C부터 반팔·반바지")
    fun temperatureBoundary16() {
        assertEquals(listOf(longSleeve, tights), outfit(temperatureC = 5.9))
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 6.0))
    }

    @Test
    @DisplayName("기온 경계 — 실제 −10°C(달릴 때 0°C)는 긴팔·자켓·타이츠·장갑, 그 아래는 방한 세트+넥워머")
    fun temperatureBoundaryZero() {
        assertEquals(listOf(longSleeve, jacket, tights, gloves), outfit(temperatureC = -10.0))
        assertEquals(listOf(thermalTop, thermalBottom, beanie, neckWarmer, gloves),
                     outfit(temperatureC = -10.1))
    }

    @Test
    @DisplayName("습도 무관 — 실제 10°C(달릴 때 20°C)는 습도 90%여도 반팔, 고온다습(19°C↑)은 이미 싱글렛 구간")
    fun humidityDoesNotChangeOutfit() {
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 10.0, humidityPct = 79.9))
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 10.0, humidityPct = 90.0))
        // 실제 8°C·습도 90% 흐린 아침(리뷰 지적 사례) — 싱글렛이 아니라 반팔
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 8.0, humidityPct = 90.0))
        assertEquals(listOf(singlet, shorts), outfit(temperatureC = 19.0, humidityPct = 80.0))
    }

    @Test
    @DisplayName("바람 가산 — 달릴 때 8~24°C에서 8.0 m/s부터 바람막이, 더위(달릴 때 24°C+)엔 안 붙는다")
    fun windAddsWindbreaker() {
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 10.0, windMs = 7.9))
        assertEquals(listOf(shortSleeve, shorts, windbreaker), outfit(temperatureC = 10.0, windMs = 8.0))
        // 달릴 때 8~16°C 구간에도 적용 — 실제 2°C → 12°C
        assertEquals(listOf(longSleeve, tights, windbreaker), outfit(temperatureC = 2.0, windMs = 8.0))
        // 실제 16°C → 26°C
        assertEquals(listOf(singlet, shorts), outfit(temperatureC = 16.0, windMs = 9.0))
    }

    @Test
    @DisplayName("강수 가산 — 실제 10°C(달릴 때 20°C) 이상은 방수 캡, 미만은 방수 자켓")
    fun rainAddsWaterproof() {
        assertEquals(listOf(singlet, shorts, waterproofCap), outfit(temperatureC = 16.0, precipitationMm = 0.5))
        assertEquals(listOf(shortSleeve, shorts, waterproofCap),
                     outfit(temperatureC = 10.0, precipitationMm = 0.5))
        // 젖으면 +10 보정을 다 믿지 않는다 — 반팔 구간(달릴 때 19.9°C)이어도 자켓
        assertEquals(listOf(shortSleeve, shorts, waterproofJacket),
                     outfit(temperatureC = 9.9, precipitationMm = 0.5))
        assertEquals(listOf(longSleeve, tights, waterproofJacket),
                     outfit(temperatureC = 0.0, precipitationMm = 0.5))
    }

    @Test
    @DisplayName("강수 가산 — 강수량 0mm여도 소나기 코드(WMO 80)면 비로 보고 방수 캡 (이슈 #109)")
    fun rainCodeAddsWaterproof() {
        // 코드 80(약한 소나기)·강수량 0 → 비 판정, 달릴 때 20°C ≥ 20 → 방수 캡
        assertEquals(listOf(shortSleeve, shorts, waterproofCap),
                     outfit(temperatureC = 10.0, precipitationMm = 0.0, weatherCode = 80))
    }

    @Test
    @DisplayName("자외선 가산 — UV 3(WHO Moderate)부터 캡·선글라스·선크림 세트")
    fun uvAddsSunProtection() {
        assertEquals(listOf(shortSleeve, shorts), outfit(temperatureC = 10.0, uvIndex = 2.9))
        assertEquals(listOf(shortSleeve, shorts, sunCap, sunglasses, sunscreen),
                     outfit(temperatureC = 10.0, uvIndex = 3.0))
    }

    @Test
    @DisplayName("자외선 예외 — 우천 시와 달릴 때 8°C 미만에는 보호 세트를 더하지 않는다")
    fun uvSkippedWhenRainingOrCold() {
        assertEquals(listOf(shortSleeve, shorts, waterproofCap),
                     outfit(temperatureC = 10.0, precipitationMm = 0.5, uvIndex = 8.0))
        // 실제 −5°C → 달릴 때 5°C
        assertEquals(listOf(longSleeve, jacket, tights, gloves), outfit(temperatureC = -5.0, uvIndex = 8.0))
    }

    @Test
    @DisplayName("계절 가산 — 여름에 UV 값이 없으면 보호 세트를 기본 포함, 봄엔 미포함")
    fun summerDefaultsSunProtection() {
        assertEquals(listOf(singlet, shorts, sunCap, sunglasses, sunscreen),
                     outfit(temperatureC = 20.0, uvIndex = null, now = summer))
        assertEquals(listOf(singlet, shorts), outfit(temperatureC = 20.0, uvIndex = null, now = spring))
    }

    @Test
    @DisplayName("계절 가산 — 겨울 달릴 때 0~8°C에는 비니가 붙는다")
    fun winterAddsBeanie() {
        assertEquals(listOf(longSleeve, jacket, tights, gloves, beanie),
                     outfit(temperatureC = -5.0, now = winter))
        assertEquals(listOf(longSleeve, jacket, tights, gloves), outfit(temperatureC = -5.0, now = spring))
    }

    @Test
    @DisplayName("출발 직후 안내 — 실제 20°C 미만이면 '출발 후 5~10분은 쌀쌀' 한 줄, 더위(20°C~)엔 없음")
    fun startChillNote() {
        assertEquals("출발 후 5~10분은 쌀쌀할 수 있어요 — 몸이 데워지면 딱 맞아요",
                     OutfitRules.startChillNote(temperatureC = 19.9))
        assertNotNull(OutfitRules.startChillNote(temperatureC = -5.0))
        assertNull(OutfitRules.startChillNote(temperatureC = 20.0))
    }

    @Test
    @DisplayName("응답 fixture 디코드 — current 필드 5개를 그대로 옮긴다")
    fun decodeFixture() {
        // Open-Meteo v1/forecast 실제 응답 축약 — current 외 필드는 무시된다
        val fixture = """
        {
          "latitude": 37.5, "longitude": 126.94, "timezone": "Asia/Seoul",
          "current_units": { "temperature_2m": "°C", "wind_speed_10m": "m/s" },
          "current": {
            "time": "2026-08-10T18:00",
            "temperature_2m": 29.4,
            "apparent_temperature": 33.1,
            "relative_humidity_2m": 78,
            "wind_speed_10m": 3.6,
            "precipitation": 0.2
          }
        }
        """.trimIndent()
        // (Android: iOS 기본값 `now: Date()` 대신 고정 시각을 넘긴다 — hourly가 없어 결과에 영향 없음)
        val weather = WeatherClient.decode(fixture.toByteArray(Charsets.UTF_8), now = spring)
        assertEquals(29.4, weather.temperatureC)
        assertEquals(33.1, weather.apparentC)
        assertEquals(78.0, weather.humidityPct)
        assertEquals(3.6, weather.windMs)
        assertEquals(0.2, weather.precipitationMm)
    }
}
