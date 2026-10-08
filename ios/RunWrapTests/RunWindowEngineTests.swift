import Foundation
import Testing
@testable import RunWrap

/// 달리기 좋은 시간 추천 (이슈 #173, #219) — 칸 점수표 경계(실제 기온 기준), 추천 구간 목록·미노출 가드,
/// 그리고 open-meteo 시간대별 응답 디코드(지금 칸부터 24개 자르기·관용 처리) 검증.
/// 기준일은 KST 2026-08-10(월) — 0시 = 2026-08-09T15:00:00Z.
struct RunWindowEngineTests {
    let midnight = ISO8601DateFormatter().date(from: "2026-08-09T15:00:00Z")!

    /// KST 기준일 h시(24 이상이면 다음 날) 시각
    private func at(_ h: Double) -> Date { midnight.addingTimeInterval(h * 3_600) }

    /// 기본값은 만점 칸 — 기온 10°C(체감 8°C)·습도 60%·강수확률 0%·강수 0mm·바람 2m/s·맑음(0) → 100
    private func slot(_ h: Int, temp: Double = 10, humidity: Double = 60, probability: Int = 0,
                      mm: Double = 0, wind: Double = 2, code: Int? = 0) -> HourlyWeather {
        HourlyWeather(time: at(Double(h)), temperatureC: temp, apparentC: temp - 2,
                      humidityPct: humidity, precipitationProbabilityPct: probability,
                      precipitationMm: mm, windMs: wind, weatherCode: code)
    }

    /// 0~23시 하루치 — 시각별 기온만 바꿔 점수를 조립한다
    private func day(temp: (Int) -> Double) -> [HourlyWeather] {
        (0..<24).map { slot($0, temp: temp($0)) }
    }

    private func score(temp: Double = 10, humidity: Double = 60, probability: Int = 0, mm: Double = 0,
                       wind: Double = 2, code: Int? = 0) -> Int {
        RunWindowEngine.score(slot(12, temp: temp, humidity: humidity, probability: probability,
                                   mm: mm, wind: wind, code: code))
    }

    // MARK: - 칸 점수

    @Test("점수표 경계 — 실제 기온 0·4·7·15·19·23°C에서 구간이 바뀐다")
    func scoreTemperatureBoundaries() {
        #expect(score(temp: 6.9) == 70)
        #expect(score(temp: 7) == 100)
        #expect(score(temp: 14.9) == 100)
        #expect(score(temp: 15) == 70)
        #expect(score(temp: 18.9) == 70)
        #expect(score(temp: 19) == 40)
        #expect(score(temp: 22.9) == 40)
        #expect(score(temp: 23) == 10)
        #expect(score(temp: 4) == 70)
        #expect(score(temp: 3.9) == 40)
        #expect(score(temp: 0) == 40)
        #expect(score(temp: -0.1) == 10)
    }

    @Test("판단 기준은 실제 기온 — 10°C·습도 보통·무강수는 만점, 22°C는 감점된다")
    func scoreUsesActualTemperature() {
        // 체감(apparentC)은 기온 − 2로 넣는다 — 체감 기준이었다면 22°C 칸(체감 20)이 만점이었다
        #expect(score(temp: 10) == 100)
        #expect(score(temp: 22) == 40)
    }

    @Test("고온다습 — 습도 80%↑이면서 19°C↑면 −20, 19°C 아래는 감점 없음")
    func scoreHumidHeat() {
        // 22°C(40) − 고온다습(20) = 20
        #expect(score(temp: 22, humidity: 85) == 20)
        #expect(score(temp: 19, humidity: 80) == 20)
        #expect(score(temp: 19, humidity: 79.9) == 40)
        // 18°C는 고온다습 선 아래 — 기온 점수 70 그대로
        #expect(score(temp: 18, humidity: 90) == 70)
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
        // 기온 35°C(10) − 강수확률 80%(40) − 강수(20) − 비(30) → 음수는 0으로
        #expect(score(temp: 35, probability: 80, mm: 1) == 0)
    }

    // MARK: - 추천 구간

    @Test("추천 구간 — 기준 이상 구간이 둘이면 둘 다 시각 순으로 돌려준다")
    func windowsReturnsAll() throws {
        // 06~08시 기온 10(100), 18~20시 기온 16(70), 나머지 25(10) → 6~9시 · 18~21시
        var hourly = day { h in
            switch h {
            case 6...8: 10
            case 18...20: 16
            default: 25
            }
        }
        hourly[19] = slot(19, temp: 16, probability: 10)   // 강수확률 10%는 감점 없음 — 구간의 최대 강수확률로 남는다
        let windows = RunWindowEngine.windows(hourly: hourly, now: at(5))
        try #require(windows.count == 2)
        #expect(windows[0].start == at(6))
        #expect(windows[0].end == at(9))
        #expect(windows[0].avgScore == 100)
        #expect(windows[0].temperatureC == 10)
        #expect(windows[0].apparentC == 8)
        #expect(windows[1].start == at(18))
        #expect(windows[1].end == at(21))
        #expect(windows[1].avgScore == 70)
        #expect(windows[1].precipitationProbabilityPct == 10)
        #expect(RunWindowEngine.rangesLabel(windows) == "6~9시 · 18~21시")
    }

    @Test("추천 구간 — 한 칸짜리도 구간이고, 70점 미만 칸에서 끊긴다")
    func windowsSplitsOnLowScore() throws {
        // 7시 100 · 8시 40(기온 20) · 9~10시 100 → 7~8시 · 9~11시
        let hourly = day { h in
            switch h {
            case 7, 9, 10: 10
            case 8: 20
            default: 25
            }
        }
        let windows = RunWindowEngine.windows(hourly: hourly, now: at(5))
        #expect(windows.map(\.start) == [at(7), at(9)])
        #expect(RunWindowEngine.rangesLabel(windows) == "7~8시 · 9~11시")
    }

    @Test("추천 구간 — 값이 빠진 칸(한 시간 넘게 벌어진 칸)에서는 구간을 끊는다")
    func windowsSplitsOnGap() {
        // 6·7시와 9시만 있고 8시 칸이 빠짐 → 6~8시 · 9~10시
        let hourly = [slot(6), slot(7), slot(9)]
        let windows = RunWindowEngine.windows(hourly: hourly, now: at(5))
        #expect(RunWindowEngine.rangesLabel(windows) == "6~8시 · 9~10시")
    }

    @Test("미노출 가드 — 하루 종일 비면 기준 미달이라 빈 배열")
    func windowsAllRain() {
        // 기온 10(100) − 강수확률 80%(40) − 강수 1mm(20) − 비 코드(30) = 10
        let hourly = (0..<24).map { slot($0, probability: 80, mm: 1, code: 61) }
        #expect(RunWindowEngine.windows(hourly: hourly, now: at(5)).isEmpty)
        #expect(RunWindowEngine.rangesLabel([]) == nil)
    }

    @Test("내일로 넘김 — 22시면 오늘 남은 후보(05~21시 칸)가 없어 내일 구간을 고른다")
    func windowsFallsBackToTomorrow() throws {
        // 지금(22시)부터 24칸 모두 만점 → 오늘 후보 없음 → 내일 05~21시 칸 → 내일 5~22시
        let hourly = (22..<46).map { slot($0) }
        let windows = RunWindowEngine.windows(hourly: hourly, now: at(22))
        let window = try #require(windows.first)
        #expect(windows.count == 1)
        #expect(window.start == at(29))
        #expect(window.isTomorrow)
        #expect(RunWindowEngine.rangesLabel(windows) == "내일 5~22시")
    }

    @Test("미노출 가드 — 오늘 후보가 없고 내일 칸도 없으면 빈 배열")
    func windowsTooLateNoTomorrow() {
        let hourly = (0..<24).map { slot($0) }
        #expect(RunWindowEngine.windows(hourly: hourly, now: at(22)).isEmpty)
    }

    @Test("후보 시각 — 05시 이전 칸과 지나간 칸은 구간에 들지 않는다")
    func windowsExcludesEarlyAndPast() throws {
        // 03~06시 만점, 나머지 10점(기온 25) → 03·04시는 05시 이전이라 빠져 5~7시
        let hourly = day { h in (3...6).contains(h) ? 10 : 25 }
        #expect(RunWindowEngine.rangesLabel(RunWindowEngine.windows(hourly: hourly, now: at(0))) == "5~7시")

        // now 05:30 → 5시 칸은 이미 시작해 제외, 6~7시
        #expect(RunWindowEngine.rangesLabel(RunWindowEngine.windows(hourly: hourly, now: at(5.5))) == "6~7시")
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
