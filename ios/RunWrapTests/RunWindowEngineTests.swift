import Foundation
import Testing
@testable import RunWrap

/// 달리기 좋은 시간 추천 (이슈 #173) — 칸 점수표 경계, 최고 창 선택·3시간 확장·미노출 가드,
/// 그리고 open-meteo 시간대별 응답 디코드(지금 칸부터 24개 자르기·관용 처리) 검증.
/// 기준일은 KST 2026-08-10(월) — 0시 = 2026-08-09T15:00:00Z.
struct RunWindowEngineTests {
    let midnight = ISO8601DateFormatter().date(from: "2026-08-09T15:00:00Z")!

    /// KST 기준일 h시(24 이상이면 다음 날) 시각
    private func at(_ h: Double) -> Date { midnight.addingTimeInterval(h * 3_600) }

    /// 기본값은 만점 칸 — 체감 20°C·강수확률 0%·강수 0mm·바람 2m/s·맑음(0) → 100
    private func slot(_ h: Int, apparent: Double = 20, probability: Int = 0,
                      mm: Double = 0, wind: Double = 2, code: Int? = 0) -> HourlyWeather {
        HourlyWeather(time: at(Double(h)), temperatureC: apparent, apparentC: apparent,
                      humidityPct: 60, precipitationProbabilityPct: probability,
                      precipitationMm: mm, windMs: wind, weatherCode: code)
    }

    /// 0~23시 하루치 — 시각별 체감온도만 바꿔 점수를 조립한다
    private func day(apparent: (Int) -> Double) -> [HourlyWeather] {
        (0..<24).map { slot($0, apparent: apparent($0)) }
    }

    private func score(apparent: Double = 20, probability: Int = 0, mm: Double = 0,
                       wind: Double = 2, code: Int? = 0) -> Int {
        RunWindowEngine.score(slot(12, apparent: apparent, probability: probability,
                                   mm: mm, wind: wind, code: code))
    }

    // MARK: - 칸 점수

    @Test("점수표 경계 — 체감 16·24·28·33°C에서 구간이 바뀐다")
    func scoreApparentBoundaries() {
        #expect(score(apparent: 15.9) == 70)
        #expect(score(apparent: 16) == 100)
        #expect(score(apparent: 23.9) == 100)
        #expect(score(apparent: 24) == 70)
        #expect(score(apparent: 27.9) == 70)
        #expect(score(apparent: 28) == 40)
        #expect(score(apparent: 32.9) == 40)
        #expect(score(apparent: 33) == 10)
        #expect(score(apparent: 8) == 70)
        #expect(score(apparent: 7.9) == 40)
        #expect(score(apparent: 0) == 40)
        #expect(score(apparent: -0.1) == 10)
    }

    @Test("점수표 경계 — 강수확률 30%부터 −20, 60%부터 −40")
    func scorePrecipitationProbability() {
        #expect(score(probability: 29) == 100)
        #expect(score(probability: 30) == 80)
        #expect(score(probability: 59) == 80)
        #expect(score(probability: 60) == 60)
    }

    @Test("감점 — 바람 5·8m/s, 강수량·비 코드, 하한 0")
    func scorePenalties() {
        #expect(score(wind: 4.9) == 100)
        #expect(score(wind: 5) == 90)
        #expect(score(wind: 8) == 80)
        // 이슬비 코드(WMO 51)는 강수량 0mm여도 비 판정 → −30
        #expect(score(code: 51) == 70)
        // 강수 0.1mm → 강수량 −20 + 비 판정 −30 = 50
        #expect(score(mm: 0.1) == 50)
        // 체감 35°C(10) − 강수확률 80%(40) − 강수(20) − 비(30) → 음수는 0으로
        #expect(score(apparent: 35, probability: 80, mm: 1) == 0)
    }

    // MARK: - 최고 창

    @Test("최고 창 — 저녁 18~20시(100·100)가 아침 06~08시(70·70)를 이긴다")
    func bestWindowPicksHighest() throws {
        // 06·07시 체감 26(70), 18·19시 체감 20(100), 나머지 체감 30(40).
        // 20시는 40 < 평균 100이라 확장하지 않는다
        var hourly = day { h in
            switch h {
            case 6, 7: 26
            case 18, 19: 20
            default: 30
            }
        }
        hourly[19] = slot(19, probability: 10)   // 강수확률 10%는 감점 없음 — 창의 최대 강수확률로 남는다
        let window = try #require(RunWindowEngine.bestWindow(hourly: hourly, now: at(5)))
        #expect(window.start == at(18))
        #expect(window.end == at(20))
        #expect(window.avgScore == 100)
        #expect(window.apparentC == 20)
        #expect(window.precipitationProbabilityPct == 10)
        #expect(RunWindowEngine.rangeLabel(window) == "18~20시")
    }

    @Test("3시간 확장 — 다음 칸 점수가 창 평균 이상이면 한 칸 늘린다")
    func bestWindowExtends() throws {
        // 18시 100 · 19시 70(체감 26) · 20시 100, 나머지 40.
        // 18~20(평균 85)과 19~21(평균 85) 동점 → 이른 18시 창, 20시 100 ≥ 85 → 18~21시
        // 평균 (100 + 70 + 100) / 3 = 90
        let hourly = day { h in
            switch h {
            case 18, 20: 20
            case 19: 26
            default: 30
            }
        }
        let window = try #require(RunWindowEngine.bestWindow(hourly: hourly, now: at(5)))
        #expect(window.start == at(18))
        #expect(window.end == at(21))
        #expect(window.avgScore == 90)
        #expect(RunWindowEngine.rangeLabel(window) == "18~21시")
    }

    @Test("미노출 가드 — 하루 종일 비면 평균 50 미만이라 nil")
    func bestWindowAllRain() {
        // 체감 20(100) − 강수확률 80%(40) − 강수 1mm(20) − 비 코드(30) = 10
        let hourly = (0..<24).map { slot($0, probability: 80, mm: 1, code: 61) }
        #expect(RunWindowEngine.bestWindow(hourly: hourly, now: at(5)) == nil)
    }

    @Test("내일로 넘김 — 21시면 오늘 남은 시작 후보(≤ 19시)가 없어 내일 창을 고른다")
    func bestWindowFallsBackToTomorrow() throws {
        // 오늘·내일 48칸 모두 만점, now 21시 → 오늘 후보 없음 → 내일 05시 창(동점 중 가장 이른 창),
        // 07시도 100 ≥ 평균 100이라 확장 → 내일 5~8시
        let hourly = (0..<48).map { slot($0) }
        let window = try #require(RunWindowEngine.bestWindow(hourly: hourly, now: at(21)))
        #expect(window.start == at(29))
        #expect(window.isTomorrow)
        #expect(RunWindowEngine.rangeLabel(window) == "내일 5~8시")
    }

    @Test("미노출 가드 — 오늘 후보가 없고 내일 칸도 없으면 nil")
    func bestWindowTooLateNoTomorrow() {
        let hourly = (0..<24).map { slot($0) }
        #expect(RunWindowEngine.bestWindow(hourly: hourly, now: at(21)) == nil)
    }

    @Test("후보 시각 — 05시 이전 칸과 지나간 칸은 창에 들지 않는다")
    func bestWindowExcludesEarlyAndPast() throws {
        // 03·04시 만점, 나머지 전부 70(체감 26) → 05시 창(동점 중 가장 이른 창),
        // 07시도 70 ≥ 평균 70이라 확장 → 05~08시
        let hourly = day { h in (3...4).contains(h) ? 20 : 26 }
        let window = try #require(RunWindowEngine.bestWindow(hourly: hourly, now: at(0)))
        #expect(window.start == at(5))
        #expect(RunWindowEngine.rangeLabel(window) == "5~8시")

        // now 10:30 → 10시 칸은 이미 시작해 제외, 11시 창부터
        let late = try #require(RunWindowEngine.bestWindow(hourly: hourly, now: at(10.5)))
        #expect(late.start == at(11))
    }

    // MARK: - 응답 디코드 (WeatherClient)

    /// open-meteo 응답 축약 — hourly는 KST 08-10 0시부터 count시간.
    /// 체감온도 = 칸 번호(i)로 넣어 어느 칸이 남았는지 확인한다
    private func fixture(count: Int = 48, apparentOverride: String? = nil,
                         includeHourly: Bool = true) -> Data {
        let times = (0..<count).map { i -> String in
            let day = 10 + i / 24, hour = i % 24
            return String(format: "\"2026-08-%02dT%02d:00\"", day, hour)
        }
        let numbers = (0..<count).map { "\($0)" }.joined(separator: ",")
        let hourly = """
          , "hourly": {
            "time": [\(times.joined(separator: ","))],
            "temperature_2m": [\(numbers)],
            "apparent_temperature": [\(apparentOverride ?? numbers)],
            "relative_humidity_2m": [\(numbers)],
            "precipitation_probability": [\(numbers)],
            "precipitation": [\(numbers)],
            "wind_speed_10m": [\(numbers)],
            "weather_code": [\(numbers)]
          }
        """
        return Data("""
        {
          "utc_offset_seconds": 32400,
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 }
          \(includeHourly ? hourly : "")
        }
        """.utf8)
    }

    @Test("시간대별 디코드 — 48칸 중 지금 칸(15시)부터 24개만 남는다")
    func decodeHourlyTrims() throws {
        // now = KST 15:30 → 15시 칸(끝 16:00 > now)부터 다음 날 14시 칸까지 24개
        let weather = try WeatherClient.decode(fixture(), now: at(15.5))
        #expect(weather.hourly.count == 24)
        #expect(weather.hourly.first?.time == at(15))
        #expect(weather.hourly.first?.apparentC == 15)
        #expect(weather.hourly.first?.precipitationProbabilityPct == 15)
        #expect(weather.hourly.last?.time == at(38))
        // 현재 날씨는 그대로
        #expect(weather.apparentC == 33.1)
    }

    @Test("시간대별 디코드 관용 — hourly가 없거나 배열 길이가 다르면 빈 배열, null 칸은 건너뛴다")
    func decodeHourlyTolerant() throws {
        #expect(try WeatherClient.decode(fixture(includeHourly: false), now: at(15.5)).hourly.isEmpty)

        // 체감온도 배열만 47개 → 길이 불일치
        let short = (0..<47).map { "\($0)" }.joined(separator: ",")
        let mismatched = try WeatherClient.decode(fixture(apparentOverride: short), now: at(15.5))
        #expect(mismatched.hourly.isEmpty)
        #expect(mismatched.apparentC == 33.1)

        // 16시 칸 체감온도가 null → 그 칸만 빠지고 15시, 17~39시로 24개
        let withNull = (0..<48).map { $0 == 16 ? "null" : "\($0)" }.joined(separator: ",")
        let skipped = try WeatherClient.decode(fixture(apparentOverride: withNull), now: at(15.5))
        #expect(skipped.hourly.count == 24)
        #expect(skipped.hourly.map(\.time).contains(at(16)) == false)
        #expect(skipped.hourly.last?.time == at(39))
    }
}
