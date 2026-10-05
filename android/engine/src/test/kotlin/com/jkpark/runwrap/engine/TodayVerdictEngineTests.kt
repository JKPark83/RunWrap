package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 홈 판단 카드 엔진 검증 — 카드/줄 단위 미노출 가드와 오늘 권장 거리 산식.
/// now = 2026-08-13T09:00:00Z = KST 2026-08-13(목) 18:00 고정.
/// 이번 주(ISO 8601, 월요일 시작) 창은 08-10 00:00 ~ 08-17 00:00 KST,
/// 오늘 포함 남은 날은 목·금·토·일 = 4일이다.
class TodayVerdictEngineTests {
    private val now = iso("2026-08-13T09:00:00Z")

    private fun run(daysAgo: Double, km: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = 360 * km, distanceMeters = km * 1_000, avgHeartRate = 150.0)

    /// 이번 주 6km 1회(08-11 화) + 지난주 10km 1회(08-04) —
    /// 상한 가드의 기준(최근 4주 최장)이 10km가 된다
    private val baseRuns: List<RunSummary> get() = listOf(run(daysAgo = 2.0, km = 6.0), run(daysAgo = 9.0, km = 10.0))

    private fun battery(tone: RRTone, level: Int = 62,
                        statusLabel: String = "양호"): BatteryReport =
        BatteryReport(level = level, tone = tone, statusLabel = statusLabel,
                      headline = "", factors = emptyList())

    /// 주간 20~22km 처방 — 중앙값 21km가 기준 주간량이 된다.
    /// zones는 nil로 둔다 — 페이스 존이 없으면 퀄리티 처방을 건너뛰므로(엔진 규칙)
    /// 이 픽스처의 기대값은 이지런·LSD 계열만 나온다.
    private fun guide(low: Double = 20.0, high: Double = 22.0,
                      batteryLimited: Boolean = false,
                      zones: TrainingGuide.PaceZones? = null,
                      tempoCount: Int = 1): TrainingGuide =
        TrainingGuide(prediction = null,
                      zones = zones,
                      prescription = TrainingGuide.Prescription(weeklyKmLow = low, weeklyKmHigh = high,
                                                                lsdKmLow = low * 0.25, lsdKmHigh = high * 0.35,
                                                                tempoCount = tempoCount, intervalCount = 1,
                                                                phase = null, daysToRace = null,
                                                                peakWeeklyKm = 40.0, batteryLimited = batteryLimited),
                      balance = null)

    private fun verdict(runs: List<RunSummary>? = null,
                        battery: BatteryReport? = null,
                        weather: TodayVerdictEngine.WeatherInput = TodayVerdictEngine.WeatherInput.loading,
                        guide: TrainingGuide? = null,
                        hasRaceGoal: Boolean = true,
                        weeklyGoal: Int = 4,
                        air: AirGrade? = null): TodayVerdict? =
        TodayVerdictEngine.verdict(runs = runs ?: baseRuns, battery = battery, weather = weather,
                                   guide = guide, hasRaceGoal = hasRaceGoal,
                                   weeklyGoal = weeklyGoal, air = air, now = now, zone = testZone)

    private fun value(text: String) = TodayVerdict.Line.Content.value(text)
    private fun hint(text: String) = TodayVerdict.Line.Content.hint(text)

    // MARK: - 카드 전체 가드

    @Test
    @DisplayName("미노출 가드 — 러닝 기록이 하나도 없으면 카드를 내지 않는다")
    fun noRunsHidesCard() {
        assertNull(verdict(runs = emptyList()))
    }

    // MARK: - 제목줄

    @Test
    @DisplayName("제목줄 — 배터리 톤 4단계가 각각의 판정 문장으로 이어진다")
    fun headlineByTone() {
        val expected: List<Pair<RRTone, String>> = listOf(
            RRTone.overload to "오늘은 쉬시는 게 이깁니다",
            RRTone.caution to "가볍게만 다녀오세요",
            RRTone.steady to "평소대로 가셔도 돼요",
            RRTone.improving to "몸이 좋습니다, 밀어붙여도 돼요",
        )
        for ((tone, headline) in expected) {
            val result = assertNotNull(verdict(battery = battery(tone)))
            assertEquals(tone, result.tone)
            assertEquals(headline, result.headline)
        }
    }

    @Test
    @DisplayName("제목줄 가드 — 배터리가 없으면 판정하지 않고(tone nil) 중립 문구만 둔다")
    fun headlineWithoutBattery() {
        val result = assertNotNull(verdict(battery = null))
        assertNull(result.tone)
        assertEquals("오늘은 어떻게 가실까요", result.headline)
    }

    // MARK: - 제목줄 대기질 상한 (이슈 #183)

    @Test
    @DisplayName("대기질 상한 — 매우나쁨이면 배터리가 좋아도 overload·'오늘은 실내가 이깁니다'")
    fun headlineVeryBadAir() {
        val result = assertNotNull(verdict(battery = battery(RRTone.improving), air = AirGrade.veryBad))
        assertEquals(RRTone.overload, result.tone)
        assertEquals("오늘은 실내가 이깁니다", result.headline)

        // 배터리가 없어도 같은 판정 — 공기는 배터리와 무관한 조건이다
        val noBattery = assertNotNull(verdict(battery = null, air = AirGrade.veryBad))
        assertEquals(RRTone.overload, noBattery.tone)
        assertEquals("오늘은 실내가 이깁니다", noBattery.headline)
    }

    @Test
    @DisplayName("대기질 상한 — 나쁨이면 배터리 steady·improving·없음을 caution으로 낮춘다")
    fun headlineBadAirCapsTone() {
        for (tone in listOf(RRTone.steady, RRTone.improving, null)) {
            val result = assertNotNull(verdict(battery = tone?.let { battery(it) }, air = AirGrade.bad))
            assertEquals(RRTone.caution, result.tone)
            assertEquals("공기가 나빠요, 가볍게만 다녀오세요", result.headline)
        }
    }

    @Test
    @DisplayName("대기질 상한 — 나쁨이어도 배터리가 이미 overload·caution이면 배터리 판정을 유지한다")
    fun headlineBadAirKeepsConservativeBattery() {
        val overload = assertNotNull(verdict(battery = battery(RRTone.overload), air = AirGrade.bad))
        assertEquals(RRTone.overload, overload.tone)
        assertEquals("오늘은 쉬시는 게 이깁니다", overload.headline)

        val caution = assertNotNull(verdict(battery = battery(RRTone.caution), air = AirGrade.bad))
        assertEquals(RRTone.caution, caution.tone)
        assertEquals("가볍게만 다녀오세요", caution.headline)
    }

    @Test
    @DisplayName("대기질 상한 — 좋음·보통·nil이면 배터리 판정 그대로")
    fun headlineMildAirUnchanged() {
        for (air in listOf(AirGrade.good, AirGrade.moderate, null)) {
            val result = assertNotNull(verdict(battery = battery(RRTone.improving), air = air))
            assertEquals(RRTone.improving, result.tone)
            assertEquals("몸이 좋습니다, 밀어붙여도 돼요", result.headline)
        }
        val noBattery = assertNotNull(verdict(battery = null, air = AirGrade.moderate))
        assertNull(noBattery.tone)
        assertEquals("오늘은 어떻게 가실까요", noBattery.headline)
    }

    // MARK: - 체력 배터리 줄

    @Test
    @DisplayName("배터리 줄 — 값이 있으면 '레벨 · 상태', 없으면 유도 문구")
    fun batteryLine() {
        val charged = assertNotNull(verdict(battery = battery(RRTone.improving, level = 84,
                                                              statusLabel = "충전 충분")))
        assertEquals(value("84 · 충전 충분"), charged.battery.content)
        assertEquals(RRTone.improving, charged.battery.tone)

        val empty = assertNotNull(verdict(battery = null))
        assertEquals(hint("워치를 차고 주무시면 켜져요"), empty.battery.content)
        assertNull(empty.battery.tone)
    }

    // MARK: - 날씨 줄

    @Test
    @DisplayName("날씨 줄 — 로딩·권한 거부·조회 실패는 각각 다른 유도 문구를 낸다")
    fun weatherHints() {
        val cases: List<Pair<TodayVerdictEngine.WeatherInput, String>> = listOf(
            TodayVerdictEngine.WeatherInput.loading to "날씨를 불러오는 중",
            TodayVerdictEngine.WeatherInput.denied to "설정에서 위치 허용하기",
            TodayVerdictEngine.WeatherInput.unavailable to "날씨를 불러오지 못했어요",
        )
        for ((input, expected) in cases) {
            val result = assertNotNull(verdict(weather = input))
            assertEquals(hint(expected), result.weather.content)
        }
    }

    @Test
    @DisplayName("날씨 줄 — '체감 온도 · 상의+하의', 비가 오면 가운데에 '비'가 낀다")
    fun weatherPhrase() {
        // 체감 22.4°C·습도 60% → 16~24 구간의 반팔 티+반바지 (소품은 제외한다)
        val mild = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4))))
        assertEquals(value("체감 22°C · 반팔 티+반바지"), mild.weather.content)

        val rainy = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(
            weather(apparentC = 18.0, precipitationMm = 2.0))))
        assertEquals(value("체감 18°C · 비 · 반팔 티+반바지"), rainy.weather.content)
    }

    @Test
    @DisplayName("날씨 캡션 — 달리기 좋은 시간이 있으면 '18~20시가 좋아요', 없으면 nil (이슈 #173)")
    fun weatherBestWindowCaption() {
        // now = KST 18:00 → 창 18:00~20:00, 판정문(값)은 추천과 무관하게 그대로
        val window = RunWindow(start = now, end = now.plusSeconds(7_200),
                               avgScore = 100, apparentC = 20.0, precipitationProbabilityPct = 10)
        val withWindow = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4),
                                                                                                bestWindow = window)))
        assertEquals("18~20시가 좋아요", withWindow.weather.caption)
        assertEquals(value("체감 22°C · 반팔 티+반바지"), withWindow.weather.content)

        val withoutWindow = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4))))
        assertNull(withoutWindow.weather.caption)
    }

    @Test
    @DisplayName("날씨 줄 대기질 — 나쁨 이상이면 문구 뒤에 ' · 대기질 <공식 등급>'을 붙인다 (이슈 #183)")
    fun weatherAirSuffix() {
        val bad = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4)), air = AirGrade.bad))
        assertEquals(value("체감 22°C · 반팔 티+반바지 · 대기질 나쁨"), bad.weather.content)

        val veryBad = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4)), air = AirGrade.veryBad))
        assertEquals(value("체감 22°C · 반팔 티+반바지 · 대기질 매우나쁨"), veryBad.weather.content)

        // 보통 이하는 붙이지 않는다
        val moderate = assertNotNull(verdict(weather = TodayVerdictEngine.WeatherInput.current(weather(apparentC = 22.4)), air = AirGrade.moderate))
        assertEquals(value("체감 22°C · 반팔 티+반바지"), moderate.weather.content)
    }

    @Test
    @DisplayName("날씨 줄 대기질 — 날씨 값이 없는 유도 문구 줄에는 붙이지 않는다")
    fun weatherAirSuffixSkipsHints() {
        val cases: List<Pair<TodayVerdictEngine.WeatherInput, String>> = listOf(
            TodayVerdictEngine.WeatherInput.loading to "날씨를 불러오는 중",
            TodayVerdictEngine.WeatherInput.denied to "설정에서 위치 허용하기",
            TodayVerdictEngine.WeatherInput.unavailable to "날씨를 불러오지 못했어요",
        )
        for ((input, expected) in cases) {
            val result = assertNotNull(verdict(weather = input, air = AirGrade.bad))
            assertEquals(hint(expected), result.weather.content)
        }
    }

    @Test
    @DisplayName("날씨 조각 — 강수량 0mm여도 이슬비 코드(WMO 51)면 raining (이슈 #109)")
    fun weatherPartsRainCode() {
        val parts = TodayVerdictEngine.weatherParts(
            weather(apparentC = 18.0, weatherCode = 51), now = now, zone = testZone)
        assertTrue(parts.raining)
    }

    private fun weather(apparentC: Double, precipitationMm: Double = 0.0,
                        weatherCode: Int? = null): CurrentWeather =
        CurrentWeather(temperatureC = apparentC, apparentC = apparentC, humidityPct = 60.0,
                       windMs = 2.0, precipitationMm = precipitationMm, forecastMaxC = null,
                       weatherCode = weatherCode, uvIndex = 1.0)

    // MARK: - 오늘 권장 세션

    @Test
    @DisplayName("권장 세션 가드 — 처방이 없으면 목표 설정 여부에 따라 유도 문구가 갈린다")
    fun sessionWithoutGuide() {
        val noSample = assertNotNull(verdict(guide = null, hasRaceGoal = true))
        assertEquals(hint("3주치 기록이 쌓이면 알려드려요"), noSample.session.content)

        val noGoal = assertNotNull(verdict(guide = null, hasRaceGoal = false))
        assertEquals(hint("목표 대회를 정해 보세요"), noGoal.session.content)
    }

    @Test
    @DisplayName("권장 세션 — 잔여량을 남은 횟수로 나눈다 (21 − 6 = 15km ÷ 3회 = 5.0km)")
    fun sessionSplitsRemainder() {
        // 기준 주간량 = (20 + 22) / 2 = 21km, 이번 주 소화 6km → 잔여 15km.
        // 남은횟수 = min(4회 − 1회, 남은 4일) = 3 → 5.0km. 상한 11km(10×1.1)에 걸리지 않는다
        val result = assertNotNull(verdict(battery = battery(RRTone.steady), guide = guide()))
        assertEquals(value("이지런 5.0km"), result.session.content)
        assertEquals(RRTone.steady, result.session.tone)
    }

    @Test
    @DisplayName("권장 세션 상한 — 최근 4주 최장 거리의 +10%를 넘기지 않는다")
    fun sessionCapsAtLongRun() {
        // 남은횟수 1회(주 2회 목표 − 이번 주 1회) → 잔여 15km가 통째로 걸리지만,
        // 4주 최장이 6km라 상한 6.6km(6 × 1.1)로 깎인다
        val runs = listOf(run(daysAgo = 2.0, km = 6.0), run(daysAgo = 9.0, km = 6.0))
        val result = assertNotNull(verdict(runs = runs, battery = battery(RRTone.steady),
                                           guide = guide(), weeklyGoal = 2))
        assertEquals(value("이지런 6.6km"), result.session.content)
    }

    @Test
    @DisplayName("권장 세션 — 배터리가 하향 보정을 건 주에는 기준을 하한(20km)으로 내린다")
    fun sessionUsesLowBoundWhenBatteryLimited() {
        // 기준 20km − 소화 6km = 14km ÷ 3회 = 4.666… → 4.7km. 강도는 caution → "가볍게"
        val result = assertNotNull(verdict(battery = battery(RRTone.caution),
                                           guide = guide(batteryLimited = true)))
        assertEquals(value("가볍게 4.7km"), result.session.content)
        assertEquals(RRTone.caution, result.session.tone)
    }

    @Test
    @DisplayName("권장 세션 — 배터리가 좋으면 '빌드업', 배터리가 없으면 '이지런'")
    fun sessionIntensityLabels() {
        val good = assertNotNull(verdict(battery = battery(RRTone.improving), guide = guide()))
        assertEquals(value("빌드업 5.0km"), good.session.content)

        val unknown = assertNotNull(verdict(battery = null, guide = guide()))
        assertEquals(value("이지런 5.0km"), unknown.session.content)
        assertEquals(RRTone.steady, unknown.session.tone)
    }

    @Test
    @DisplayName("권장 세션 — 방전 임박이면 거리를 내지 않고 휴식을 처방한다")
    fun sessionRestsOnOverload() {
        val result = assertNotNull(verdict(battery = battery(RRTone.overload), guide = guide()))
        assertEquals(value("오늘은 휴식"), result.session.content)
        assertEquals(RRTone.overload, result.session.tone)
    }

    @Test
    @DisplayName("권장 세션 — 잔여량이 1km 미만이면 거리 대신 완료를 알린다")
    fun sessionStopsWhenWeeklyKmDone() {
        // 이번 주 25km 소화 → 기준 21km를 이미 넘겼다 (남은 횟수는 아직 3회 남아 있다)
        val runs = listOf(run(daysAgo = 2.0, km = 25.0), run(daysAgo = 9.0, km = 10.0))
        val result = assertNotNull(verdict(runs = runs, battery = battery(RRTone.steady), guide = guide()))
        assertEquals(value("이번 주 목표를 채우셨어요"), result.session.content)
        assertEquals(RRTone.improving, result.session.tone)
    }

    @Test
    @DisplayName("권장 세션 — 남은 횟수가 0이면 거리(잔여 15km)가 남아도 처방하지 않는다")
    fun sessionStopsWhenWeeklyCountDone() {
        val result = assertNotNull(verdict(battery = battery(RRTone.steady), guide = guide(),
                                           weeklyGoal = 1))
        assertEquals(value("이번 주 횟수를 다 채우셨어요"), result.session.content)
    }

    // MARK: - 권장 세션·배지 대기질 상한 (이슈 #195)

    /// 인터벌이 처방되는 guide — 페이스 존이 있고 템포 0·인터벌 1회.
    /// baseRuns 기준: 이번 주 6km ≥ LSD 하한 5km라 롱런은 끝났고, 두 러닝 페이스가 같아
    /// 스피드 세션 0회 < 퀄리티 1회 → 배터리가 좋으면 인터벌 차례다
    private val intervalGuide: TrainingGuide
        get() = guide(zones = TrainingGuide.PaceZones(vdot = 50.0, easySecPerKm = 294.0..338.0, tempoSecPerKm = 255.0,
                                                      intervalSecPerKm = 235.0, goalSecPerKm = null),
                      tempoCount = 0)

    @Test
    @DisplayName("대기질 상한 — 매우나쁨이면 권장 세션은 '오늘은 실내에서'(overload), 배지는 '실외 자제'")
    fun sessionVeryBadAir() {
        val result = assertNotNull(verdict(battery = battery(RRTone.improving), guide = intervalGuide,
                                           air = AirGrade.veryBad))
        assertEquals(value("오늘은 실내에서"), result.session.content)
        assertEquals(RRTone.overload, result.session.tone)
        assertEquals("실외 자제", result.badgeLabel)

        // 처방이 없어 유도 문구를 낼 자리여도 이 줄이 이긴다
        val noGuide = assertNotNull(verdict(battery = null, guide = null, air = AirGrade.veryBad))
        assertEquals(value("오늘은 실내에서"), noGuide.session.content)
        assertEquals("실외 자제", noGuide.badgeLabel)
    }

    @Test
    @DisplayName("대기질 상한 — 나쁨 + 배터리 좋음이면 인터벌 대신 '가볍게' 이지런(caution), 배지는 '공기 나쁨'")
    fun sessionBadAirCapsIntensity() {
        // 대조군: 공기가 좋으면 같은 픽스처에서 인터벌(중급 5×800m)이 나온다
        val clean = assertNotNull(verdict(battery = battery(RRTone.improving), guide = intervalGuide))
        val cleanText = (clean.session.content as? TodayVerdict.Line.Content.value)?.text
            ?: fail("권장 세션이 값이 아니다")
        assertTrue(cleanText.startsWith("인터벌"))

        // 나쁨 → 유효 톤 caution → easy(.battery): 잔여 15km ÷ 3회 = 5.0km,
        // 이지 페이스 중앙값 (294 + 338) / 2 = 316초 = 5′16″
        val result = assertNotNull(verdict(battery = battery(RRTone.improving), guide = intervalGuide,
                                           air = AirGrade.bad))
        val text = (result.session.content as? TodayVerdict.Line.Content.value)?.text
            ?: fail("권장 세션이 값이 아니다")
        assertTrue(text.startsWith("가볍게"))
        assertEquals(RRTone.caution, result.session.tone)
        assertEquals("공기 나쁨", result.badgeLabel)
    }

    @Test
    @DisplayName("대기질 상한 — 나쁨이어도 배터리가 overload면 휴식 처방 그대로, 배지는 톤 기본 라벨(nil)")
    fun sessionBadAirKeepsOverload() {
        val result = assertNotNull(verdict(battery = battery(RRTone.overload), guide = intervalGuide,
                                           air = AirGrade.bad))
        assertEquals(value("오늘은 휴식"), result.session.content)
        assertEquals(RRTone.overload, result.session.tone)
        assertNull(result.badgeLabel)
    }

    @Test
    @DisplayName("대기질 상한 — 좋음이면 권장 세션은 기존 그대로, 배지 라벨 nil")
    fun sessionGoodAirUnchanged() {
        val result = assertNotNull(verdict(battery = battery(RRTone.steady), guide = guide(), air = AirGrade.good))
        assertEquals(value("이지런 5.0km"), result.session.content)
        assertEquals(RRTone.steady, result.session.tone)
        assertNull(result.badgeLabel)
    }

    @Test
    @DisplayName("배지 라벨 — 대기질이 판정을 정했을 때만 값이 있다 (헤드라인 상한과 같은 조건)")
    fun badgeLabelRules() {
        assertEquals("실외 자제", TodayVerdictEngine.badgeLabel(battery = null, air = AirGrade.veryBad))
        assertEquals("실외 자제", TodayVerdictEngine.badgeLabel(battery = battery(RRTone.overload), air = AirGrade.veryBad))
        for (tone in listOf(RRTone.steady, RRTone.improving, null)) {
            assertEquals("공기 나쁨", TodayVerdictEngine.badgeLabel(battery = tone?.let { battery(it) }, air = AirGrade.bad))
        }
        for (tone in listOf(RRTone.overload, RRTone.caution)) {
            assertNull(TodayVerdictEngine.badgeLabel(battery = battery(tone), air = AirGrade.bad))
        }
        for (air in listOf(AirGrade.good, AirGrade.moderate, null)) {
            assertNull(TodayVerdictEngine.badgeLabel(battery = battery(RRTone.improving), air = air))
        }
    }

    // MARK: - 회복 경과

    @Test
    @DisplayName("회복 경과 — 마지막 러닝까지의 날짜 차이를 오늘/어제/N일 전으로 읽는다")
    fun recoveryElapsed() {
        // 날짜 경계 기준이라 시각이 아니라 일수로 센다 (daysAgo 0.2 = 오늘 새벽)
        val cases: List<Pair<Double, String>> = listOf(0.2 to "오늘 다녀오셨어요", 1.0 to "어제", 5.0 to "5일 전")
        for ((daysAgo, text) in cases) {
            val result = assertNotNull(verdict(runs = listOf(run(daysAgo = daysAgo, km = 5.0))))
            assertEquals(value(text), result.recovery.content)
            assertNull(result.recovery.tone)
        }
    }
}
