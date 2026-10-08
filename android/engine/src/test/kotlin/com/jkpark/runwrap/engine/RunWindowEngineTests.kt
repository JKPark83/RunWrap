package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 달리기 좋은 시간 추천 (이슈 #173, #219) — 칸 점수표 경계(실제 기온 기준), 추천 구간 목록·미노출 가드,
/// 그리고 open-meteo 시간대별 응답 디코드(지금 칸부터 24개 자르기·관용 처리) 검증.
/// 기준일은 KST 2026-08-10(월) — 0시 = 2026-08-09T15:00:00Z.
class RunWindowEngineTests {
    val midnight: Instant = iso("2026-08-09T15:00:00Z")

    /// KST 기준일 h시(24 이상이면 다음 날) 시각
    private fun at(h: Double): Instant = midnight.plusMillis((h * 3_600_000).toLong())

    /// 기본값은 만점 칸 — 기온 10°C(체감 8°C)·습도 60%·강수확률 0%·강수 0mm·바람 2m/s·맑음(0) → 100
    private fun slot(h: Int, temp: Double = 10.0, humidity: Double = 60.0, probability: Int = 0,
                     mm: Double = 0.0, wind: Double = 2.0, code: Int? = 0): HourlyWeather =
        HourlyWeather(time = at(h.toDouble()), temperatureC = temp, apparentC = temp - 2,
                      humidityPct = humidity, precipitationProbabilityPct = probability,
                      precipitationMm = mm, windMs = wind, weatherCode = code)

    /// 0~23시 하루치 — 시각별 기온만 바꿔 점수를 조립한다
    private fun day(temp: (Int) -> Double): List<HourlyWeather> =
        (0 until 24).map { slot(it, temp = temp(it)) }

    private fun score(temp: Double = 10.0, humidity: Double = 60.0, probability: Int = 0, mm: Double = 0.0,
                      wind: Double = 2.0, code: Int? = 0): Int =
        RunWindowEngine.score(slot(12, temp = temp, humidity = humidity, probability = probability,
                                   mm = mm, wind = wind, code = code))

    // MARK: - 칸 점수

    @Test
    @DisplayName("점수표 경계 — 실제 기온 0·4·7·15·17·20°C에서 구간이 바뀐다 (고온 쪽이 더 가파르다)")
    fun scoreTemperatureBoundaries() {
        // 고온 쪽은 15→20°C(5°C)만에 10점, 추운 쪽은 7→0°C(7°C) — El Helou 2012 비대칭
        assertEquals(70, score(temp = 6.9))
        assertEquals(100, score(temp = 7.0))
        assertEquals(100, score(temp = 14.9))
        assertEquals(70, score(temp = 15.0))
        assertEquals(70, score(temp = 16.9))
        assertEquals(40, score(temp = 17.0))
        assertEquals(40, score(temp = 19.9))
        assertEquals(10, score(temp = 20.0))
        assertEquals(70, score(temp = 4.0))
        assertEquals(40, score(temp = 3.9))
        assertEquals(40, score(temp = 0.0))
        assertEquals(10, score(temp = -0.1))
    }

    @Test
    @DisplayName("판단 기준은 실제 기온 — 10°C·습도 보통·무강수는 만점, 22°C는 감점된다")
    fun scoreUsesActualTemperature() {
        // 체감(apparentC)은 기온 − 2로 넣는다 — 체감 기준이었다면 22°C 칸(체감 20)이 만점이었다
        assertEquals(100, score(temp = 10.0))
        assertEquals(10, score(temp = 22.0))
    }

    @Test
    @DisplayName("고온다습 — 습도 80%↑이면서 19°C↑면 −20, 19°C 아래는 감점 없음")
    fun scoreHumidHeat() {
        // 19°C(40) − 고온다습(20) = 20
        assertEquals(20, score(temp = 19.0, humidity = 80.0))
        assertEquals(40, score(temp = 19.0, humidity = 79.9))
        // 22°C(10) − 고온다습(20) → 하한 0
        assertEquals(0, score(temp = 22.0, humidity = 85.0))
        // 18°C는 고온다습 선 아래 — 기온 점수 40 그대로
        assertEquals(40, score(temp = 18.0, humidity = 90.0))
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
        // 기온 35°C(10) − 강수확률 80%(40) − 강수(20) − 비(30) → 음수는 0으로
        assertEquals(0, score(temp = 35.0, probability = 80, mm = 1.0))
    }

    // MARK: - 추천 구간

    @Test
    @DisplayName("추천 구간 — 기준 이상 구간이 둘이면 둘 다 시각 순으로 돌려준다")
    fun windowsReturnsAll() {
        // 06~08시 기온 10(100), 18~20시 기온 16(70), 나머지 25(10) → 6~9시 · 18~21시
        val hourly = day { h ->
            when (h) {
                in 6..8 -> 10.0
                in 18..20 -> 16.0
                else -> 25.0
            }
        }.toMutableList()
        hourly[19] = slot(19, temp = 16.0, probability = 10)   // 강수확률 10%는 감점 없음 — 구간의 최대 강수확률로 남는다
        val windows = RunWindowEngine.windows(hourly = hourly, now = at(5.0))
        assertEquals(2, windows.size)
        assertEquals(at(6.0), windows[0].start)
        assertEquals(at(9.0), windows[0].end)
        assertEquals(100, windows[0].avgScore)
        assertEquals(10.0, windows[0].temperatureC)
        assertEquals(8.0, windows[0].apparentC)
        assertEquals(at(18.0), windows[1].start)
        assertEquals(at(21.0), windows[1].end)
        assertEquals(70, windows[1].avgScore)
        assertEquals(10, windows[1].precipitationProbabilityPct)
        assertEquals("6~9시 · 18~21시", RunWindowEngine.rangesLabel(windows))
    }

    @Test
    @DisplayName("추천 구간 — 한 칸짜리도 구간이고, 70점 미만 칸에서 끊긴다")
    fun windowsSplitsOnLowScore() {
        // 7시 100 · 8시 10(기온 20) · 9~10시 100 → 7~8시 · 9~11시
        val hourly = day { h ->
            when (h) {
                7, 9, 10 -> 10.0
                8 -> 20.0
                else -> 25.0
            }
        }
        val windows = RunWindowEngine.windows(hourly = hourly, now = at(5.0))
        assertEquals(listOf(at(7.0), at(9.0)), windows.map { it.start })
        assertEquals("7~8시 · 9~11시", RunWindowEngine.rangesLabel(windows))
    }

    @Test
    @DisplayName("추천 구간 — 값이 빠진 칸(한 시간 넘게 벌어진 칸)에서는 구간을 끊는다")
    fun windowsSplitsOnGap() {
        // 6·7시와 9시만 있고 8시 칸이 빠짐 → 6~8시 · 9~10시
        val hourly = listOf(slot(6), slot(7), slot(9))
        val windows = RunWindowEngine.windows(hourly = hourly, now = at(5.0))
        assertEquals("6~8시 · 9~10시", RunWindowEngine.rangesLabel(windows))
    }

    @Test
    @DisplayName("미노출 가드 — 하루 종일 비면 기준 미달이라 빈 배열")
    fun windowsAllRain() {
        // 기온 10(100) − 강수확률 80%(40) − 강수 1mm(20) − 비 코드(30) = 10
        val hourly = (0 until 24).map { slot(it, probability = 80, mm = 1.0, code = 61) }
        assertTrue(RunWindowEngine.windows(hourly = hourly, now = at(5.0)).isEmpty())
        assertNull(RunWindowEngine.rangesLabel(emptyList()))
    }

    @Test
    @DisplayName("내일로 넘김 — 22시면 오늘 남은 후보(05~21시 칸)가 없어 내일 구간을 고른다")
    fun windowsFallsBackToTomorrow() {
        // 지금(22시)부터 24칸 모두 만점 → 오늘 후보 없음 → 내일 05~21시 칸 → 내일 5~22시
        val hourly = (22 until 46).map { slot(it) }
        val windows = RunWindowEngine.windows(hourly = hourly, now = at(22.0))
        assertEquals(1, windows.size)
        val window = windows[0]
        assertEquals(at(29.0), window.start)
        assertTrue(window.isTomorrow)
        assertEquals("내일 5~22시", RunWindowEngine.rangesLabel(windows))
    }

    @Test
    @DisplayName("미노출 가드 — 오늘 후보가 없고 내일 칸도 없으면 빈 배열")
    fun windowsTooLateNoTomorrow() {
        val hourly = (0 until 24).map { slot(it) }
        assertTrue(RunWindowEngine.windows(hourly = hourly, now = at(22.0)).isEmpty())
    }

    @Test
    @DisplayName("좋은 칸 — 05~21시이면서 70점 이상, 오늘 구간이 있어도 내일 좋은 칸은 그대로 좋은 칸 (시간별 띠 강조)")
    fun isGoodMarksEveryGoodHour() {
        // 오늘 18시·내일 6시 모두 만점 — windows()는 오늘 것만 돌려주지만 띠 강조는 둘 다
        val hourly = (15 until 39).map { slot(it) }
        assertTrue(RunWindowEngine.windows(hourly = hourly, now = at(15.0)).all { !it.isTomorrow })
        assertTrue(RunWindowEngine.isGood(slot(18)))
        assertTrue(RunWindowEngine.isGood(slot(30)))      // 내일 6시
        assertFalse(RunWindowEngine.isGood(slot(22)))     // 22시 칸은 추천 시간대 밖
        assertFalse(RunWindowEngine.isGood(slot(28)))     // 내일 4시
        assertFalse(RunWindowEngine.isGood(slot(18, temp = 18.0)))   // 40점
    }

    @Test
    @DisplayName("구간 라벨 줄임 — limit개 넘는 구간은 '외 N곳'으로")
    fun rangesLabelLimit() {
        // 6·8·10·12시 한 칸짜리 만점 구간 4개 (사이 칸은 기온 25 → 10점)
        val hourly = day { h -> if (h in listOf(6, 8, 10, 12)) 10.0 else 25.0 }
        val windows = RunWindowEngine.windows(hourly = hourly, now = at(5.0))
        assertEquals("6~7시 · 8~9시 · 10~11시 · 12~13시", RunWindowEngine.rangesLabel(windows))
        assertEquals("6~7시 · 8~9시 외 2곳", RunWindowEngine.rangesLabel(windows, limit = 2))
        assertEquals("6~7시 · 8~9시", RunWindowEngine.rangesLabel(windows.take(2), limit = 2))
    }

    @Test
    @DisplayName("후보 시각 — 05시 이전 칸과 지나간 칸은 구간에 들지 않는다")
    fun windowsExcludesEarlyAndPast() {
        // 03~06시 만점, 나머지 10점(기온 25) → 03·04시는 05시 이전이라 빠져 5~7시
        val hourly = day { h -> if (h in 3..6) 10.0 else 25.0 }
        assertEquals("5~7시", RunWindowEngine.rangesLabel(RunWindowEngine.windows(hourly = hourly, now = at(0.0))))

        // now 05:30 → 5시 칸은 이미 시작해 제외, 6~7시
        assertEquals("6~7시", RunWindowEngine.rangesLabel(RunWindowEngine.windows(hourly = hourly, now = at(5.5))))
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
