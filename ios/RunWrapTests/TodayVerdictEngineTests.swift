import Foundation
import Testing
@testable import RunWrap

/// 홈 판단 카드 엔진 검증 — 카드/줄 단위 미노출 가드와 오늘 권장 거리 산식.
/// now = 2026-08-13T09:00:00Z = KST 2026-08-13(목) 18:00 고정.
/// 이번 주(ISO 8601, 월요일 시작) 창은 08-10 00:00 ~ 08-17 00:00 KST,
/// 오늘 포함 남은 날은 목·금·토·일 = 4일이다.
struct TodayVerdictEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func run(daysAgo: Double, km: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: 360 * km, distanceMeters: km * 1_000, avgHeartRate: 150)
    }

    /// 이번 주 6km 1회(08-11 화) + 지난주 10km 1회(08-04) —
    /// 상한 가드의 기준(최근 4주 최장)이 10km가 된다
    private var baseRuns: [RunSummary] { [run(daysAgo: 2, km: 6), run(daysAgo: 9, km: 10)] }

    private func battery(_ tone: RRTone, level: Int = 62,
                         statusLabel: String = "양호") -> BatteryReport {
        BatteryReport(level: level, tone: tone, statusLabel: statusLabel,
                      headline: "", factors: [])
    }

    /// 주간 20~22km 처방 — 중앙값 21km가 기준 주간량이 된다.
    /// zones는 nil로 둔다 — 페이스 존이 없으면 퀄리티 처방을 건너뛰므로(엔진 규칙)
    /// 이 픽스처의 기대값은 이지런·LSD 계열만 나온다.
    private func guide(low: Double = 20, high: Double = 22,
                       batteryLimited: Bool = false,
                       zones: TrainingGuide.PaceZones? = nil,
                       tempoCount: Int = 1) -> TrainingGuide {
        TrainingGuide(prediction: nil,
                      zones: zones,
                      prescription: .init(weeklyKmLow: low, weeklyKmHigh: high,
                                          lsdKmLow: low * 0.25, lsdKmHigh: high * 0.35,
                                          tempoCount: tempoCount, intervalCount: 1,
                                          phase: nil, daysToRace: nil,
                                          peakWeeklyKm: 40, batteryLimited: batteryLimited),
                      balance: nil)
    }

    private func verdict(runs: [RunSummary]? = nil,
                         battery: BatteryReport? = nil,
                         weather: TodayVerdictEngine.WeatherInput = .loading,
                         guide: TrainingGuide? = nil,
                         hasRaceGoal: Bool = true,
                         weeklyGoal: Int = 4,
                         air: AirGrade? = nil) -> TodayVerdict? {
        TodayVerdictEngine.verdict(runs: runs ?? baseRuns, battery: battery, weather: weather,
                                   guide: guide, hasRaceGoal: hasRaceGoal,
                                   weeklyGoal: weeklyGoal, air: air, now: now)
    }

    // MARK: - 카드 전체 가드

    @Test("미노출 가드 — 러닝 기록이 하나도 없으면 카드를 내지 않는다")
    func noRunsHidesCard() {
        #expect(verdict(runs: []) == nil)
    }

    // MARK: - 제목줄

    @Test("제목줄 — 배터리 톤 4단계가 각각의 판정 문장으로 이어진다")
    func headlineByTone() throws {
        let expected: [(RRTone, String)] = [
            (.overload, "오늘은 쉬시는 게 이깁니다"),
            (.caution, "가볍게만 다녀오세요"),
            (.steady, "평소대로 가셔도 돼요"),
            (.improving, "몸이 좋습니다, 밀어붙여도 돼요"),
        ]
        for (tone, headline) in expected {
            let result = try #require(verdict(battery: battery(tone)))
            #expect(result.tone == tone)
            #expect(result.headline == headline)
        }
    }

    @Test("제목줄 가드 — 배터리가 없으면 판정하지 않고(tone nil) 중립 문구만 둔다")
    func headlineWithoutBattery() throws {
        let result = try #require(verdict(battery: nil))
        #expect(result.tone == nil)
        #expect(result.headline == "오늘은 어떻게 가실까요")
    }

    // MARK: - 제목줄 대기질 상한 (이슈 #183)

    @Test("대기질 상한 — 매우나쁨이면 배터리가 좋아도 overload·'오늘은 실내가 이깁니다'")
    func headlineVeryBadAir() throws {
        let result = try #require(verdict(battery: battery(.improving), air: .veryBad))
        #expect(result.tone == .overload)
        #expect(result.headline == "오늘은 실내가 이깁니다")

        // 배터리가 없어도 같은 판정 — 공기는 배터리와 무관한 조건이다
        let noBattery = try #require(verdict(battery: nil, air: .veryBad))
        #expect(noBattery.tone == .overload)
        #expect(noBattery.headline == "오늘은 실내가 이깁니다")
    }

    @Test("대기질 상한 — 나쁨이면 배터리 steady·improving·없음을 caution으로 낮춘다")
    func headlineBadAirCapsTone() throws {
        for tone: RRTone? in [.steady, .improving, nil] {
            let result = try #require(verdict(battery: tone.map { battery($0) }, air: .bad))
            #expect(result.tone == .caution)
            #expect(result.headline == "공기가 나빠요, 가볍게만 다녀오세요")
        }
    }

    @Test("대기질 상한 — 나쁨이어도 배터리가 이미 overload·caution이면 배터리 판정을 유지한다")
    func headlineBadAirKeepsConservativeBattery() throws {
        let overload = try #require(verdict(battery: battery(.overload), air: .bad))
        #expect(overload.tone == .overload)
        #expect(overload.headline == "오늘은 쉬시는 게 이깁니다")

        let caution = try #require(verdict(battery: battery(.caution), air: .bad))
        #expect(caution.tone == .caution)
        #expect(caution.headline == "가볍게만 다녀오세요")
    }

    @Test("대기질 상한 — 좋음·보통·nil이면 배터리 판정 그대로")
    func headlineMildAirUnchanged() throws {
        for air: AirGrade? in [.good, .moderate, nil] {
            let result = try #require(verdict(battery: battery(.improving), air: air))
            #expect(result.tone == .improving)
            #expect(result.headline == "몸이 좋습니다, 밀어붙여도 돼요")
        }
        let noBattery = try #require(verdict(battery: nil, air: .moderate))
        #expect(noBattery.tone == nil)
        #expect(noBattery.headline == "오늘은 어떻게 가실까요")
    }

    // MARK: - 체력 배터리 줄

    @Test("배터리 줄 — 값이 있으면 '레벨 · 상태', 없으면 유도 문구")
    func batteryLine() throws {
        let charged = try #require(verdict(battery: battery(.improving, level: 84,
                                                            statusLabel: "충전 충분")))
        #expect(charged.battery.content == .value("84 · 충전 충분"))
        #expect(charged.battery.tone == .improving)

        let empty = try #require(verdict(battery: nil))
        #expect(empty.battery.content == .hint("워치를 차고 주무시면 켜져요"))
        #expect(empty.battery.tone == nil)
    }

    // MARK: - 날씨 줄

    @Test("날씨 줄 — 로딩·권한 거부·조회 실패는 각각 다른 유도 문구를 낸다")
    func weatherHints() throws {
        let cases: [(TodayVerdictEngine.WeatherInput, String)] = [
            (.loading, "날씨를 불러오는 중"),
            (.denied, "설정에서 위치 허용하기"),
            (.unavailable, "날씨를 불러오지 못했어요"),
        ]
        for (input, hint) in cases {
            let result = try #require(verdict(weather: input))
            #expect(result.weather.content == .hint(hint))
        }
    }

    @Test("날씨 줄 — '기온 체감 N° · 상의+하의', 비가 오면 가운데에 '비'가 낀다")
    func weatherPhrase() throws {
        // 기온 12.4°C(체감 11)·습도 60% → 달릴 때 22.4°C → 16~24 구간의 반팔 티+반바지 (소품은 제외한다)
        let mild = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11))))
        #expect(mild.weather.content == .value("12°C 체감 11° · 반팔 티+반바지"))

        // 기온 8°C → 달릴 때 18°C → 반팔 티+반바지 (방수 자켓은 소품이라 문구에서 빠진다)
        let rainy = try #require(verdict(weather: .current(
            weather(temperatureC: 8, apparentC: 6, precipitationMm: 2))))
        #expect(rainy.weather.content == .value("8°C 체감 6° · 비 · 반팔 티+반바지"))
    }

    @Test("날씨 조각 — 실제 기온이 주 숫자, 체감은 뒤에 붙는다 (이슈 #220)")
    func weatherPartsTemperature() {
        let parts = TodayVerdictEngine.weatherParts(
            weather(temperatureC: 29.4, apparentC: 33.1), now: now)
        #expect(parts.temperature == "29°C 체감 33°")
    }

    @Test("날씨 캡션 — 달리기 좋은 구간을 모두 나열해 '6~9시 · 18~21시가 좋아요', 없으면 nil (이슈 #173, #219)")
    func weatherWindowsCaption() throws {
        // now = KST 18:00 → 구간 06:00~09:00(now − 12h)와 18:00~21:00. 판정문(값)은 추천과 무관하게 그대로
        let morning = RunWindow(start: now.addingTimeInterval(-43_200), end: now.addingTimeInterval(-32_400),
                                avgScore: 100, temperatureC: 10, apparentC: 8,
                                precipitationProbabilityPct: 0)
        let evening = RunWindow(start: now, end: now.addingTimeInterval(10_800),
                                avgScore: 70, temperatureC: 16, apparentC: 15,
                                precipitationProbabilityPct: 10)
        let withWindows = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11),
                                                                 windows: [morning, evening])))
        #expect(withWindows.weather.caption == "6~9시 · 18~21시가 좋아요")
        #expect(withWindows.weather.content == .value("12°C 체감 11° · 반팔 티+반바지"))

        let withoutWindow = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11))))
        #expect(withoutWindow.weather.caption == nil)
    }

    @Test("날씨 줄 대기질 — 나쁨 이상이면 문구 뒤에 ' · 대기질 <공식 등급>'을 붙인다 (이슈 #183)")
    func weatherAirSuffix() throws {
        let bad = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11)), air: .bad))
        #expect(bad.weather.content == .value("12°C 체감 11° · 반팔 티+반바지 · 대기질 나쁨"))

        let veryBad = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11)), air: .veryBad))
        #expect(veryBad.weather.content == .value("12°C 체감 11° · 반팔 티+반바지 · 대기질 매우나쁨"))

        // 보통 이하는 붙이지 않는다
        let moderate = try #require(verdict(weather: .current(weather(temperatureC: 12.4, apparentC: 11)), air: .moderate))
        #expect(moderate.weather.content == .value("12°C 체감 11° · 반팔 티+반바지"))
    }

    @Test("날씨 줄 대기질 — 날씨 값이 없는 유도 문구 줄에는 붙이지 않는다")
    func weatherAirSuffixSkipsHints() throws {
        let cases: [(TodayVerdictEngine.WeatherInput, String)] = [
            (.loading, "날씨를 불러오는 중"),
            (.denied, "설정에서 위치 허용하기"),
            (.unavailable, "날씨를 불러오지 못했어요"),
        ]
        for (input, hint) in cases {
            let result = try #require(verdict(weather: input, air: .bad))
            #expect(result.weather.content == .hint(hint))
        }
    }

    @Test("날씨 조각 — 강수량 0mm여도 이슬비 코드(WMO 51)면 raining (이슈 #109)")
    func weatherPartsRainCode() {
        let parts = TodayVerdictEngine.weatherParts(
            weather(temperatureC: 18, apparentC: 18, weatherCode: 51), now: now)
        #expect(parts.raining)
    }

    private func weather(temperatureC: Double, apparentC: Double, precipitationMm: Double = 0,
                         weatherCode: Int? = nil) -> CurrentWeather {
        CurrentWeather(temperatureC: temperatureC, apparentC: apparentC, humidityPct: 60,
                       windMs: 2, precipitationMm: precipitationMm, forecastMaxC: nil,
                       weatherCode: weatherCode, uvIndex: 1)
    }

    // MARK: - 오늘 권장 세션

    @Test("권장 세션 가드 — 처방이 없으면 목표 설정 여부에 따라 유도 문구가 갈린다")
    func sessionWithoutGuide() throws {
        let noSample = try #require(verdict(guide: nil, hasRaceGoal: true))
        #expect(noSample.session.content == .hint("3주치 기록이 쌓이면 알려드려요"))

        let noGoal = try #require(verdict(guide: nil, hasRaceGoal: false))
        #expect(noGoal.session.content == .hint("목표 대회를 정해 보세요"))
    }

    @Test("권장 세션 — 잔여량을 남은 횟수로 나눈다 (21 − 6 = 15km ÷ 3회 = 5.0km)")
    func sessionSplitsRemainder() throws {
        // 기준 주간량 = (20 + 22) / 2 = 21km, 이번 주 소화 6km → 잔여 15km.
        // 남은횟수 = min(4회 − 1회, 남은 4일) = 3 → 5.0km. 상한 11km(10×1.1)에 걸리지 않는다
        let result = try #require(verdict(battery: battery(.steady), guide: guide()))
        #expect(result.session.content == .value("이지런 5.0km"))
        #expect(result.session.tone == .steady)
    }

    @Test("권장 세션 상한 — 최근 4주 최장 거리의 +10%를 넘기지 않는다")
    func sessionCapsAtLongRun() throws {
        // 남은횟수 1회(주 2회 목표 − 이번 주 1회) → 잔여 15km가 통째로 걸리지만,
        // 4주 최장이 6km라 상한 6.6km(6 × 1.1)로 깎인다
        let runs = [run(daysAgo: 2, km: 6), run(daysAgo: 9, km: 6)]
        let result = try #require(verdict(runs: runs, battery: battery(.steady),
                                          guide: guide(), weeklyGoal: 2))
        #expect(result.session.content == .value("이지런 6.6km"))
    }

    @Test("권장 세션 — 배터리가 하향 보정을 건 주에는 기준을 하한(20km)으로 내린다")
    func sessionUsesLowBoundWhenBatteryLimited() throws {
        // 기준 20km − 소화 6km = 14km ÷ 3회 = 4.666… → 4.7km. 강도는 caution → "가볍게"
        let result = try #require(verdict(battery: battery(.caution),
                                          guide: guide(batteryLimited: true)))
        #expect(result.session.content == .value("가볍게 4.7km"))
        #expect(result.session.tone == .caution)
    }

    @Test("권장 세션 — 배터리가 좋으면 '빌드업', 배터리가 없으면 '이지런'")
    func sessionIntensityLabels() throws {
        let good = try #require(verdict(battery: battery(.improving), guide: guide()))
        #expect(good.session.content == .value("빌드업 5.0km"))

        let unknown = try #require(verdict(battery: nil, guide: guide()))
        #expect(unknown.session.content == .value("이지런 5.0km"))
        #expect(unknown.session.tone == .steady)
    }

    @Test("권장 세션 — 방전 임박이면 거리를 내지 않고 휴식을 처방한다")
    func sessionRestsOnOverload() throws {
        let result = try #require(verdict(battery: battery(.overload), guide: guide()))
        #expect(result.session.content == .value("오늘은 휴식"))
        #expect(result.session.tone == .overload)
    }

    @Test("권장 세션 — 잔여량이 1km 미만이면 거리 대신 완료를 알린다")
    func sessionStopsWhenWeeklyKmDone() throws {
        // 이번 주 25km 소화 → 기준 21km를 이미 넘겼다 (남은 횟수는 아직 3회 남아 있다)
        let runs = [run(daysAgo: 2, km: 25), run(daysAgo: 9, km: 10)]
        let result = try #require(verdict(runs: runs, battery: battery(.steady), guide: guide()))
        #expect(result.session.content == .value("이번 주 목표를 채우셨어요"))
        #expect(result.session.tone == .improving)
    }

    @Test("권장 세션 — 남은 횟수가 0이면 거리(잔여 15km)가 남아도 처방하지 않는다")
    func sessionStopsWhenWeeklyCountDone() throws {
        let result = try #require(verdict(battery: battery(.steady), guide: guide(),
                                          weeklyGoal: 1))
        #expect(result.session.content == .value("이번 주 횟수를 다 채우셨어요"))
    }

    // MARK: - 권장 세션·배지 대기질 상한 (이슈 #195)

    /// 인터벌이 처방되는 guide — 페이스 존이 있고 템포 0·인터벌 1회.
    /// baseRuns 기준: 이번 주 6km ≥ LSD 하한 5km라 롱런은 끝났고, 두 러닝 페이스가 같아
    /// 스피드 세션 0회 < 퀄리티 1회 → 배터리가 좋으면 인터벌 차례다
    private var intervalGuide: TrainingGuide {
        guide(zones: .init(vdot: 50, easySecPerKm: 294...338, tempoSecPerKm: 255,
                           intervalSecPerKm: 235, goalSecPerKm: nil),
              tempoCount: 0)
    }

    @Test("대기질 상한 — 매우나쁨이면 권장 세션은 '오늘은 실내에서'(overload), 배지는 '실외 자제'")
    func sessionVeryBadAir() throws {
        let result = try #require(verdict(battery: battery(.improving), guide: intervalGuide,
                                          air: .veryBad))
        #expect(result.session.content == .value("오늘은 실내에서"))
        #expect(result.session.tone == .overload)
        #expect(result.badgeLabel == "실외 자제")

        // 처방이 없어 유도 문구를 낼 자리여도 이 줄이 이긴다
        let noGuide = try #require(verdict(battery: nil, guide: nil, air: .veryBad))
        #expect(noGuide.session.content == .value("오늘은 실내에서"))
        #expect(noGuide.badgeLabel == "실외 자제")
    }

    @Test("대기질 상한 — 나쁨 + 배터리 좋음이면 인터벌 대신 '가볍게' 이지런(caution), 배지는 '공기 나쁨'")
    func sessionBadAirCapsIntensity() throws {
        // 대조군: 공기가 좋으면 같은 픽스처에서 인터벌(중급 5×800m)이 나온다
        let clean = try #require(verdict(battery: battery(.improving), guide: intervalGuide))
        guard case .value(let cleanText) = clean.session.content else {
            Issue.record("권장 세션이 값이 아니다"); return
        }
        #expect(cleanText.hasPrefix("인터벌"))

        // 나쁨 → 유효 톤 caution → easy(.battery): 잔여 15km ÷ 3회 = 5.0km,
        // 이지 페이스 중앙값 (294 + 338) / 2 = 316초 = 5′16″
        let result = try #require(verdict(battery: battery(.improving), guide: intervalGuide,
                                          air: .bad))
        guard case .value(let text) = result.session.content else {
            Issue.record("권장 세션이 값이 아니다"); return
        }
        #expect(text.hasPrefix("가볍게"))
        #expect(result.session.tone == .caution)
        #expect(result.badgeLabel == "공기 나쁨")
    }

    @Test("대기질 상한 — 나쁨이어도 배터리가 overload면 휴식 처방 그대로, 배지는 톤 기본 라벨(nil)")
    func sessionBadAirKeepsOverload() throws {
        let result = try #require(verdict(battery: battery(.overload), guide: intervalGuide,
                                          air: .bad))
        #expect(result.session.content == .value("오늘은 휴식"))
        #expect(result.session.tone == .overload)
        #expect(result.badgeLabel == nil)
    }

    @Test("대기질 상한 — 좋음이면 권장 세션은 기존 그대로, 배지 라벨 nil")
    func sessionGoodAirUnchanged() throws {
        let result = try #require(verdict(battery: battery(.steady), guide: guide(), air: .good))
        #expect(result.session.content == .value("이지런 5.0km"))
        #expect(result.session.tone == .steady)
        #expect(result.badgeLabel == nil)
    }

    @Test("배지 라벨 — 대기질이 판정을 정했을 때만 값이 있다 (헤드라인 상한과 같은 조건)")
    func badgeLabelRules() {
        #expect(TodayVerdictEngine.badgeLabel(battery: nil, air: .veryBad) == "실외 자제")
        #expect(TodayVerdictEngine.badgeLabel(battery: battery(.overload), air: .veryBad) == "실외 자제")
        for tone: RRTone? in [.steady, .improving, nil] {
            #expect(TodayVerdictEngine.badgeLabel(battery: tone.map { battery($0) }, air: .bad) == "공기 나쁨")
        }
        for tone: RRTone in [.overload, .caution] {
            #expect(TodayVerdictEngine.badgeLabel(battery: battery(tone), air: .bad) == nil)
        }
        for air: AirGrade? in [.good, .moderate, nil] {
            #expect(TodayVerdictEngine.badgeLabel(battery: battery(.improving), air: air) == nil)
        }
    }

    // MARK: - 회복 경과

    @Test("회복 경과 — 마지막 러닝까지의 날짜 차이를 오늘/어제/N일 전으로 읽는다")
    func recoveryElapsed() throws {
        // 날짜 경계 기준이라 시각이 아니라 일수로 센다 (daysAgo 0.2 = 오늘 새벽)
        let cases: [(Double, String)] = [(0.2, "오늘 다녀오셨어요"), (1, "어제"), (5, "5일 전")]
        for (daysAgo, text) in cases {
            let result = try #require(verdict(runs: [run(daysAgo: daysAgo, km: 5)]))
            #expect(result.recovery.content == .value(text))
            #expect(result.recovery.tone == nil)
        }
    }
}
