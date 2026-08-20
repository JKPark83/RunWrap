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
                       batteryLimited: Bool = false) -> TrainingGuide {
        TrainingGuide(prediction: nil,
                      zones: nil,
                      prescription: .init(weeklyKmLow: low, weeklyKmHigh: high,
                                          lsdKmLow: low * 0.25, lsdKmHigh: high * 0.35,
                                          tempoCount: 1, intervalCount: 1,
                                          phase: nil, daysToRace: nil,
                                          peakWeeklyKm: 40, batteryLimited: batteryLimited),
                      balance: nil)
    }

    private func verdict(runs: [RunSummary]? = nil,
                         battery: BatteryReport? = nil,
                         weather: TodayVerdictEngine.WeatherInput = .loading,
                         guide: TrainingGuide? = nil,
                         hasRaceGoal: Bool = true,
                         weeklyGoal: Int = 4) -> TodayVerdict? {
        TodayVerdictEngine.verdict(runs: runs ?? baseRuns, battery: battery, weather: weather,
                                   guide: guide, hasRaceGoal: hasRaceGoal,
                                   weeklyGoal: weeklyGoal, now: now)
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

    @Test("날씨 줄 — '체감 온도 · 상의+하의', 비가 오면 가운데에 '비'가 낀다")
    func weatherPhrase() throws {
        // 체감 22.4°C·습도 60% → 16~24 구간의 반팔 티+반바지 (소품은 제외한다)
        let mild = try #require(verdict(weather: .current(weather(apparentC: 22.4))))
        #expect(mild.weather.content == .value("체감 22°C · 반팔 티+반바지"))

        let rainy = try #require(verdict(weather: .current(
            weather(apparentC: 18, precipitationMm: 2))))
        #expect(rainy.weather.content == .value("체감 18°C · 비 · 반팔 티+반바지"))
    }

    private func weather(apparentC: Double, precipitationMm: Double = 0) -> CurrentWeather {
        CurrentWeather(temperatureC: apparentC, apparentC: apparentC, humidityPct: 60,
                       windMs: 2, precipitationMm: precipitationMm, forecastMaxC: nil,
                       weatherCode: nil, uvIndex: 1)
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
