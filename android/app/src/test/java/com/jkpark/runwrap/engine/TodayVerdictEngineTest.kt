package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// 오늘의 판단 카드 엔진 검증 — 카드 가드·제목줄 판정·재료 네 줄(배터리/날씨/세션/회복).
/// now = 2026-08-13T09:00:00Z = KST 목 18:00 — 이번 주 남은 날 4(목·금·토·일).
/// iOS `TodayVerdictEngineTests` 이식 (15개 전부).
class TodayVerdictEngineTest {
    private val now = Instant.parse("2026-08-13T09:00:00Z")

    private fun run(daysAgo: Double, km: Double) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = 360 * km,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    /// 이번 주 1회(화 6km) + 지난주 1회(10km) — 세션 줄 계산의 기준 이력
    private val baseRuns = listOf(run(2.0, 6.0), run(9.0, 10.0))

    private fun battery(tone: RRTone, level: Int = 62, statusLabel: String = "양호") =
        BatteryReport(level = level, tone = tone, statusLabel = statusLabel,
                      headline = "", factors = emptyList())

    /// 처방 주간 20~22km — 중앙값 21km 기준으로 잔여 거리를 나눈다
    private fun guide(low: Double = 20.0, high: Double = 22.0, batteryLimited: Boolean = false) =
        TrainingGuide(
            prediction = null,
            zones = null,
            prescription = TrainingGuide.Prescription(
                weeklyKmLow = low, weeklyKmHigh = high,
                lsdKmLow = low * 0.25, lsdKmHigh = high * 0.35,
                tempoCount = 1, intervalCount = 1,
                phase = null, daysToRace = null,
                peakWeeklyKm = 40.0, batteryLimited = batteryLimited,
            ),
            balance = null,
        )

    private fun verdict(
        runs: List<RunSummary> = baseRuns,
        battery: BatteryReport? = null,
        weather: TodayVerdictEngine.WeatherInput = TodayVerdictEngine.WeatherInput.Loading,
        guide: TrainingGuide? = null,
        hasRaceGoal: Boolean = true,
        weeklyGoal: Int = 4,
    ) = TodayVerdictEngine.verdict(runs, battery, weather, guide, hasRaceGoal, weeklyGoal, now = now)

    private fun weather(apparentC: Double, precip: Double = 0.0) =
        TodayVerdictEngine.WeatherInput.Current(
            CurrentWeather(
                temperatureC = apparentC, apparentC = apparentC, humidityPct = 60.0,
                windMs = 2.0, precipitationMm = precip,
                forecastMaxC = null, weatherCode = null, uvIndex = 1.0,
            )
        )

    private fun value(text: String): TodayVerdict.Line.Content =
        TodayVerdict.Line.Content.Value(text)

    private fun hint(text: String): TodayVerdict.Line.Content =
        TodayVerdict.Line.Content.Hint(text)

    // MARK: - 카드 가드·제목줄

    @Test
    fun `미노출 가드 - 기록이 하나도 없으면 카드 자체를 내지 않는다`() {
        assertNull(verdict(runs = emptyList()))
    }

    @Test
    fun `제목줄 - 배터리 톤 네 단계가 각자의 판정 문구를 낸다`() {
        assertEquals("오늘은 쉬시는 게 이깁니다", verdict(battery = battery(RRTone.OVERLOAD))!!.headline)
        assertEquals("가볍게만 다녀오세요", verdict(battery = battery(RRTone.CAUTION))!!.headline)
        assertEquals("평소대로 가셔도 돼요", verdict(battery = battery(RRTone.STEADY))!!.headline)
        assertEquals("몸이 좋습니다, 밀어붙여도 돼요", verdict(battery = battery(RRTone.IMPROVING))!!.headline)
        assertEquals(RRTone.OVERLOAD, verdict(battery = battery(RRTone.OVERLOAD))!!.tone)
    }

    @Test
    fun `제목줄 - 배터리가 없으면 판정하지 않고 중립 문구만 둔다`() {
        val v = verdict()!!
        assertNull(v.tone)
        assertEquals("오늘은 어떻게 가실까요", v.headline)
    }

    // MARK: - 배터리 줄

    @Test
    fun `배터리 줄 - 있으면 레벨과 상태, 없으면 유도 문구`() {
        val with = verdict(battery = battery(RRTone.IMPROVING, level = 84, statusLabel = "충전 충분"))!!
        assertEquals(value("84 · 충전 충분"), with.battery.content)
        assertEquals(RRTone.IMPROVING, with.battery.tone)

        val without = verdict()!!
        assertEquals(hint("워치를 차고 주무시면 켜져요"), without.battery.content)
        assertNull(without.battery.tone)
    }

    // MARK: - 날씨 줄

    @Test
    fun `날씨 줄 - 로딩과 권한 거부와 실패는 각자의 유도 문구를 낸다`() {
        assertEquals(hint("날씨를 불러오는 중"),
            verdict(weather = TodayVerdictEngine.WeatherInput.Loading)!!.weather.content)
        assertEquals(hint("설정에서 위치 허용하기"),
            verdict(weather = TodayVerdictEngine.WeatherInput.Denied)!!.weather.content)
        assertEquals(hint("날씨를 불러오지 못했어요"),
            verdict(weather = TodayVerdictEngine.WeatherInput.Unavailable)!!.weather.content)
    }

    @Test
    fun `날씨 줄 - 체감온도 반올림과 복장, 비가 오면 가운데에 비를 끼운다`() {
        assertEquals(value("체감 22°C · 반팔 티+반바지"),
            verdict(weather = weather(22.4))!!.weather.content)
        assertEquals(value("체감 18°C · 비 · 반팔 티+반바지"),
            verdict(weather = weather(18.0, precip = 2.0))!!.weather.content)
    }

    // MARK: - 세션 줄

    @Test
    fun `세션 줄 - 처방이 없는 이유에 따라 유도 문구를 가른다`() {
        assertEquals(hint("3주치 기록이 쌓이면 알려드려요"),
            verdict(hasRaceGoal = true)!!.session.content)
        assertEquals(hint("목표 대회를 정해 보세요"),
            verdict(hasRaceGoal = false)!!.session.content)
    }

    @Test
    fun `세션 줄 - steady면 이지런, 잔여량을 남은 횟수로 나눈다`() {
        // 기준 21km − 이번 주 6km = 15km, 남은 횟수 min(4−1, 남은 날 4) = 3 → 5.0km
        val v = verdict(battery = battery(RRTone.STEADY), guide = guide())!!
        assertEquals(value("이지런 5.0km"), v.session.content)
        assertEquals(RRTone.STEADY, v.session.tone)
    }

    @Test
    fun `세션 줄 - 최근 4주 최장 거리의 110퍼센트 상한에 걸린다`() {
        // 잔여 15km를 1회에 다 낼 수 없다 — 최장 6km × 1.1 = 6.6km 상한
        val runs = listOf(run(2.0, 6.0), run(9.0, 6.0))
        val v = verdict(runs = runs, battery = battery(RRTone.STEADY),
                        guide = guide(), weeklyGoal = 2)!!
        assertEquals(value("이지런 6.6km"), v.session.content)
    }

    @Test
    fun `세션 줄 - caution이면 가볍게, 하한 기준으로 거리를 줄인다`() {
        // 하한 20km − 6km = 14km ÷ 3회 ≈ 4.7km
        val v = verdict(battery = battery(RRTone.CAUTION), guide = guide(batteryLimited = true))!!
        assertEquals(value("가볍게 4.7km"), v.session.content)
        assertEquals(RRTone.CAUTION, v.session.tone)
    }

    @Test
    fun `세션 줄 - improving이면 빌드업, 배터리 없으면 이지런에 steady 톤`() {
        assertEquals(value("빌드업 5.0km"),
            verdict(battery = battery(RRTone.IMPROVING), guide = guide())!!.session.content)

        val v = verdict(guide = guide())!!
        assertEquals(value("이지런 5.0km"), v.session.content)
        assertEquals(RRTone.STEADY, v.session.tone)
    }

    @Test
    fun `세션 줄 - overload면 거리 없이 휴식`() {
        val v = verdict(battery = battery(RRTone.OVERLOAD), guide = guide())!!
        assertEquals(value("오늘은 휴식"), v.session.content)
        assertEquals(RRTone.OVERLOAD, v.session.tone)
    }

    @Test
    fun `세션 줄 - 주간 목표 거리를 채웠으면 완료`() {
        // 이번 주 25km ≥ 기준 21km → 거리 완료
        val runs = listOf(run(2.0, 25.0), run(9.0, 10.0))
        val v = verdict(runs = runs, battery = battery(RRTone.STEADY), guide = guide())!!
        assertEquals(value("이번 주 목표를 채우셨어요"), v.session.content)
        assertEquals(RRTone.IMPROVING, v.session.tone)
    }

    @Test
    fun `세션 줄 - 주간 목표 횟수를 채웠으면 완료`() {
        val v = verdict(battery = battery(RRTone.STEADY), guide = guide(), weeklyGoal = 1)!!
        assertEquals(value("이번 주 횟수를 다 채우셨어요"), v.session.content)
    }

    // MARK: - 회복 줄

    @Test
    fun `회복 줄 - 오늘과 어제와 N일 전을 자정 경계로 가른다`() {
        assertEquals(value("오늘 다녀오셨어요"),
            verdict(runs = listOf(run(0.2, 5.0)))!!.recovery.content)
        assertEquals(value("어제"),
            verdict(runs = listOf(run(1.0, 5.0)))!!.recovery.content)
        val v = verdict(runs = listOf(run(5.0, 5.0)))!!
        assertEquals(value("5일 전"), v.recovery.content)
        assertNull(v.recovery.tone)
    }
}
