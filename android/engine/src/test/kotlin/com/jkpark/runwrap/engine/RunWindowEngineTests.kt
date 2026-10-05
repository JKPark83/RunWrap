package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 달리기 좋은 시간 추천 (이슈 #173) — 칸 점수표 경계, 최고 창 선택·3시간 확장·미노출 가드,
/// 그리고 open-meteo 시간대별 응답 디코드(지금 칸부터 24개 자르기·관용 처리) 검증.
/// 기준일은 KST 2026-08-10(월) — 0시 = 2026-08-09T15:00:00Z.
class RunWindowEngineTests {
    val midnight: Instant = iso("2026-08-09T15:00:00Z")

    /// KST 기준일 h시(24 이상이면 다음 날) 시각
    private fun at(h: Double): Instant = midnight.plusMillis((h * 3_600_000).toLong())

    /// 기본값은 만점 칸 — 체감 20°C·강수확률 0%·강수 0mm·바람 2m/s·맑음(0) → 100
    private fun slot(h: Int, apparent: Double = 20.0, probability: Int = 0,
                     mm: Double = 0.0, wind: Double = 2.0, code: Int? = 0): HourlyWeather =
        HourlyWeather(time = at(h.toDouble()), temperatureC = apparent, apparentC = apparent,
                      humidityPct = 60.0, precipitationProbabilityPct = probability,
                      precipitationMm = mm, windMs = wind, weatherCode = code)

    /// 0~23시 하루치 — 시각별 체감온도만 바꿔 점수를 조립한다
    private fun day(apparent: (Int) -> Double): List<HourlyWeather> =
        (0 until 24).map { slot(it, apparent = apparent(it)) }

    private fun score(apparent: Double = 20.0, probability: Int = 0, mm: Double = 0.0,
                      wind: Double = 2.0, code: Int? = 0): Int =
        RunWindowEngine.score(slot(12, apparent = apparent, probability = probability,
                                   mm = mm, wind = wind, code = code))

    // MARK: - 칸 점수

    @Test
    @DisplayName("점수표 경계 — 체감 16·24·28·33°C에서 구간이 바뀐다")
    fun scoreApparentBoundaries() {
        assertEquals(70, score(apparent = 15.9))
        assertEquals(100, score(apparent = 16.0))
        assertEquals(100, score(apparent = 23.9))
        assertEquals(70, score(apparent = 24.0))
        assertEquals(70, score(apparent = 27.9))
        assertEquals(40, score(apparent = 28.0))
        assertEquals(40, score(apparent = 32.9))
        assertEquals(10, score(apparent = 33.0))
        assertEquals(70, score(apparent = 8.0))
        assertEquals(40, score(apparent = 7.9))
        assertEquals(40, score(apparent = 0.0))
        assertEquals(10, score(apparent = -0.1))
    }

    @Test
    @DisplayName("점수표 경계 — 강수확률 30%부터 −20, 60%부터 −40")
    fun scorePrecipitationProbability() {
        assertEquals(100, score(probability = 29))
        assertEquals(80, score(probability = 30))
        assertEquals(80, score(probability = 59))
        assertEquals(60, score(probability = 60))
    }

    @Test
    @DisplayName("감점 — 바람 5·8m/s, 강수량·비 코드, 하한 0")
    fun scorePenalties() {
        assertEquals(100, score(wind = 4.9))
        assertEquals(90, score(wind = 5.0))
        assertEquals(80, score(wind = 8.0))
        // 이슬비 코드(WMO 51)는 강수량 0mm여도 비 판정 → −30
        assertEquals(70, score(code = 51))
        // 강수 0.1mm → 강수량 −20 + 비 판정 −30 = 50
        assertEquals(50, score(mm = 0.1))
        // 체감 35°C(10) − 강수확률 80%(40) − 강수(20) − 비(30) → 음수는 0으로
        assertEquals(0, score(apparent = 35.0, probability = 80, mm = 1.0))
    }

    // MARK: - 최고 창

    @Test
    @DisplayName("최고 창 — 저녁 18~20시(100·100)가 아침 06~08시(70·70)를 이긴다")
    fun bestWindowPicksHighest() {
        // 06·07시 체감 26(70), 18·19시 체감 20(100), 나머지 체감 30(40).
        // 20시는 40 < 평균 100이라 확장하지 않는다
        val hourly = day { h ->
            when (h) {
                6, 7 -> 26.0
                18, 19 -> 20.0
                else -> 30.0
            }
        }.toMutableList()
        hourly[19] = slot(19, probability = 10)   // 강수확률 10%는 감점 없음 — 창의 최대 강수확률로 남는다
        val window = assertNotNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(5.0)))
        assertEquals(at(18.0), window.start)
        assertEquals(at(20.0), window.end)
        assertEquals(100, window.avgScore)
        assertEquals(20.0, window.apparentC)
        assertEquals(10, window.precipitationProbabilityPct)
        assertEquals("18~20시", RunWindowEngine.rangeLabel(window))
    }

    @Test
    @DisplayName("3시간 확장 — 다음 칸 점수가 창 평균 이상이면 한 칸 늘린다")
    fun bestWindowExtends() {
        // 18시 100 · 19시 70(체감 26) · 20시 100, 나머지 40.
        // 18~20(평균 85)과 19~21(평균 85) 동점 → 이른 18시 창, 20시 100 ≥ 85 → 18~21시
        // 평균 (100 + 70 + 100) / 3 = 90
        val hourly = day { h ->
            when (h) {
                18, 20 -> 20.0
                19 -> 26.0
                else -> 30.0
            }
        }
        val window = assertNotNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(5.0)))
        assertEquals(at(18.0), window.start)
        assertEquals(at(21.0), window.end)
        assertEquals(90, window.avgScore)
        assertEquals("18~21시", RunWindowEngine.rangeLabel(window))
    }

    @Test
    @DisplayName("미노출 가드 — 하루 종일 비면 평균 50 미만이라 nil")
    fun bestWindowAllRain() {
        // 체감 20(100) − 강수확률 80%(40) − 강수 1mm(20) − 비 코드(30) = 10
        val hourly = (0 until 24).map { slot(it, probability = 80, mm = 1.0, code = 61) }
        assertNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(5.0)))
    }

    @Test
    @DisplayName("내일로 넘김 — 21시면 오늘 남은 시작 후보(≤ 19시)가 없어 내일 창을 고른다")
    fun bestWindowFallsBackToTomorrow() {
        // 오늘·내일 48칸 모두 만점, now 21시 → 오늘 후보 없음 → 내일 05시 창(동점 중 가장 이른 창),
        // 07시도 100 ≥ 평균 100이라 확장 → 내일 5~8시
        val hourly = (0 until 48).map { slot(it) }
        val window = assertNotNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(21.0)))
        assertEquals(at(29.0), window.start)
        assertTrue(window.isTomorrow)
        assertEquals("내일 5~8시", RunWindowEngine.rangeLabel(window))
    }

    @Test
    @DisplayName("미노출 가드 — 오늘 후보가 없고 내일 칸도 없으면 nil")
    fun bestWindowTooLateNoTomorrow() {
        val hourly = (0 until 24).map { slot(it) }
        assertNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(21.0)))
    }

    @Test
    @DisplayName("후보 시각 — 05시 이전 칸과 지나간 칸은 창에 들지 않는다")
    fun bestWindowExcludesEarlyAndPast() {
        // 03·04시 만점, 나머지 전부 70(체감 26) → 05시 창(동점 중 가장 이른 창),
        // 07시도 70 ≥ 평균 70이라 확장 → 05~08시
        val hourly = day { h -> if (h in 3..4) 20.0 else 26.0 }
        val window = assertNotNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(0.0)))
        assertEquals(at(5.0), window.start)
        assertEquals("5~8시", RunWindowEngine.rangeLabel(window))

        // now 10:30 → 10시 칸은 이미 시작해 제외, 11시 창부터
        val late = assertNotNull(RunWindowEngine.bestWindow(hourly = hourly, now = at(10.5)))
        assertEquals(at(11.0), late.start)
    }

    // MARK: - 응답 디코드 (WeatherClient)

    /// open-meteo 응답 축약 — hourly는 KST 08-10 0시부터 count시간.
    /// 체감온도 = 칸 번호(i)로 넣어 어느 칸이 남았는지 확인한다
    private fun fixture(count: Int = 48, apparentOverride: String? = null,
                        includeHourly: Boolean = true): ByteArray {
        val times = (0 until count).map { i ->
            val day = 10 + i / 24
            val hour = i % 24
            "\"2026-08-${day.toString().padStart(2, '0')}T${hour.toString().padStart(2, '0')}:00\""
        }
        val numbers = (0 until count).joinToString(",") { "$it" }
        val hourly = """
          , "hourly": {
            "time": [${times.joinToString(",")}],
            "temperature_2m": [$numbers],
            "apparent_temperature": [${apparentOverride ?: numbers}],
            "relative_humidity_2m": [$numbers],
            "precipitation_probability": [$numbers],
            "precipitation": [$numbers],
            "wind_speed_10m": [$numbers],
            "weather_code": [$numbers]
          }
        """
        return """
        {
          "utc_offset_seconds": 32400,
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 }
          ${if (includeHourly) hourly else ""}
        }
        """.toByteArray(Charsets.UTF_8)
    }

    @Test
    @DisplayName("시간대별 디코드 — 48칸 중 지금 칸(15시)부터 24개만 남는다")
    fun decodeHourlyTrims() {
        // now = KST 15:30 → 15시 칸(끝 16:00 > now)부터 다음 날 14시 칸까지 24개
        val weather = WeatherClient.decode(fixture(), now = at(15.5))
        assertEquals(24, weather.hourly.size)
        assertEquals(at(15.0), weather.hourly.firstOrNull()?.time)
        assertEquals(15.0, weather.hourly.firstOrNull()?.apparentC)
        assertEquals(15, weather.hourly.firstOrNull()?.precipitationProbabilityPct)
        assertEquals(at(38.0), weather.hourly.lastOrNull()?.time)
        // 현재 날씨는 그대로
        assertEquals(33.1, weather.apparentC)
    }

    @Test
    @DisplayName("시간대별 디코드 관용 — hourly가 없거나 배열 길이가 다르면 빈 배열, null 칸은 건너뛴다")
    fun decodeHourlyTolerant() {
        assertTrue(WeatherClient.decode(fixture(includeHourly = false), now = at(15.5)).hourly.isEmpty())

        // 체감온도 배열만 47개 → 길이 불일치
        val short = (0 until 47).joinToString(",") { "$it" }
        val mismatched = WeatherClient.decode(fixture(apparentOverride = short), now = at(15.5))
        assertTrue(mismatched.hourly.isEmpty())
        assertEquals(33.1, mismatched.apparentC)

        // 16시 칸 체감온도가 null → 그 칸만 빠지고 15시, 17~39시로 24개
        val withNull = (0 until 48).joinToString(",") { if (it == 16) "null" else "$it" }
        val skipped = WeatherClient.decode(fixture(apparentOverride = withNull), now = at(15.5))
        assertEquals(24, skipped.hourly.size)
        assertFalse(skipped.hourly.map { it.time }.contains(at(16.0)))
        assertEquals(at(39.0), skipped.hourly.lastOrNull()?.time)
    }
}
