import Foundation

/// Open-Meteo 현재 날씨 — 앱에서 유일한 네트워크 코드 (기획서 §4.10, 계획서 M6).
/// 요청에는 좌표(위도·경도)만 실린다 — 건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
/// 좌표는 소수 2자리(≈1km)로 낮춰 보낸다 — 날씨에는 충분하고 정확한 위치 노출을 줄인다.
struct CurrentWeather: Equatable {
    let temperatureC: Double
    let apparentC: Double
    let humidityPct: Double
    let windMs: Double
    let precipitationMm: Double
    /// 오늘 예보 최고기온 — 수분 알람(계획서 M9) 재료. 응답에 daily가 없으면 nil
    let forecastMaxC: Double?
    /// WMO 날씨 코드 — 상태 아이콘·라벨 매핑은 화면 몫 (응답에 없으면 nil)
    let weatherCode: Int?
    /// 현재 자외선지수 (응답에 없으면 nil)
    let uvIndex: Double?
    /// 지금 시각이 든 칸부터 24시간 예보 — 달리기 좋은 시간 추천 재료 (이슈 #173).
    /// 응답에 없거나 모양이 어긋나면 빈 배열 — 화면은 24칸 미만이면 카드를 내지 않는다
    var hourly: [HourlyWeather] = []
}

/// 한 시간 칸 예보 (open-meteo hourly) — time은 그 칸의 시작 시각
struct HourlyWeather: Equatable {
    let time: Date
    let temperatureC: Double
    let apparentC: Double
    let humidityPct: Double
    let precipitationProbabilityPct: Int
    let precipitationMm: Double
    let windMs: Double
    let weatherCode: Int?
}

struct WeatherClient {
    /// 기동 스플래시가 이 요청의 결론을 기다린다 — 기본 60초 타임아웃은 기동을 볼모로
    /// 잡으므로 10초로 묶는다. 실패도 "결론"이라 스플래시가 홈을 열 수 있다 (RootView 참조).
    /// timeoutIntervalForRequest는 바이트가 올 때마다 다시 시작되는 무응답 간격이라 전체 상한이 아니다 —
    /// 느리게 조금씩 오는 응답이 10초를 넘기지 않도록 Resource 상한도 같이 건다 (이슈 #157)
    private static let bounded: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 10
        return URLSession(configuration: configuration)
    }()

    var session: URLSession = WeatherClient.bounded

    func current(latitude: Double, longitude: Double) async throws -> CurrentWeather {
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            .init(name: "latitude", value: String(format: "%.2f", latitude)),
            .init(name: "longitude", value: String(format: "%.2f", longitude)),
            .init(name: "current", value: "temperature_2m,apparent_temperature,"
                + "relative_humidity_2m,wind_speed_10m,precipitation,weather_code,uv_index"),
            .init(name: "wind_speed_unit", value: "ms"),  // 복장 룰이 m/s 기준 (계획서 M6)
            .init(name: "daily", value: "temperature_2m_max"),  // 수분 알람: 오늘 최고기온 (계획서 M9)
            // 달리기 좋은 시간 추천 (이슈 #173) — 같은 요청에 시간대별 예보만 더 받는다.
            // 보내는 값은 그대로 좌표뿐. 저녁에도 24시간이 이어지도록 이틀치를 받는다
            .init(name: "hourly", value: "temperature_2m,apparent_temperature,relative_humidity_2m,"
                + "precipitation_probability,precipitation,wind_speed_10m,weather_code"),
            .init(name: "forecast_days", value: "2"),
            .init(name: "timezone", value: "auto"),
        ]
        let (data, _) = try await session.data(from: components.url!)
        return try Self.decode(data)
    }

    /// 응답 디코드 — fixture 테스트를 위해 네트워크와 분리 (계획서 M6).
    /// `now`는 시간대별 예보를 지금 칸부터 24개로 자르는 기준 (테스트에서 고정 시각 주입)
    static func decode(_ data: Data, now: Date = Date()) throws -> CurrentWeather {
        let response = try JSONDecoder().decode(Response.self, from: data)
        return CurrentWeather(temperatureC: response.current.temperature_2m,
                              apparentC: response.current.apparent_temperature,
                              humidityPct: response.current.relative_humidity_2m,
                              windMs: response.current.wind_speed_10m,
                              precipitationMm: response.current.precipitation,
                              forecastMaxC: response.daily?.temperature_2m_max.first,
                              weatherCode: response.current.weather_code,
                              uvIndex: response.current.uv_index,
                              hourly: decodeHourly(data, now: now))
    }

    /// 시간대별 예보는 부가 정보라 관용 처리한다 — 따로 디코드해서 실패해도 현재 날씨는 살린다.
    /// hourly가 없거나 배열 길이가 서로 다르면 빈 배열, 값이 빠진(null) 칸은 건너뛴다.
    /// time은 timezone=auto라 오프셋 없는 현지 시각 문자열("2026-08-10T15:00")이어서
    /// utc_offset_seconds로 시간대를 붙여 Date로 만든다
    private static func decodeHourly(_ data: Data, now: Date) -> [HourlyWeather] {
        guard let response = try? JSONDecoder().decode(HourlyResponse.self, from: data),
              let zone = TimeZone(secondsFromGMT: response.utc_offset_seconds) else { return [] }
        let h = response.hourly
        let count = h.time.count
        guard [h.temperature_2m.count, h.apparent_temperature.count, h.relative_humidity_2m.count,
               h.precipitation_probability.count, h.precipitation.count, h.wind_speed_10m.count,
               h.weather_code.count].allSatisfy({ $0 == count }) else { return [] }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"

        let slots: [HourlyWeather] = (0..<count).compactMap { i in
            guard let time = formatter.date(from: h.time[i]),
                  let temperature = h.temperature_2m[i],
                  let apparent = h.apparent_temperature[i],
                  let humidity = h.relative_humidity_2m[i],
                  let probability = h.precipitation_probability[i],
                  let precipitation = h.precipitation[i],
                  let wind = h.wind_speed_10m[i] else { return nil }
            return HourlyWeather(time: time, temperatureC: temperature, apparentC: apparent,
                                 humidityPct: humidity,
                                 precipitationProbabilityPct: Int(probability.rounded()),
                                 precipitationMm: precipitation, windMs: wind,
                                 weatherCode: h.weather_code[i])
        }
        // 지금 시각이 든 칸(끝이 now 이후인 첫 칸)부터 24시간
        return Array(slots.drop { $0.time.addingTimeInterval(3_600) <= now }.prefix(24))
    }

    /// Open-Meteo 응답 스키마 그대로 — 필드명은 API의 snake_case를 따른다
    private struct Response: Codable {
        struct Current: Codable {
            let temperature_2m: Double
            let apparent_temperature: Double
            let relative_humidity_2m: Double
            let wind_speed_10m: Double
            let precipitation: Double
            let weather_code: Int?     // 구 응답·필드 미지원 대비 옵셔널
            let uv_index: Double?
        }
        struct Daily: Codable {
            let temperature_2m_max: [Double]   // 첫 값이 오늘 (forecast_days=2 → 오늘·내일)
        }
        let current: Current
        let daily: Daily?
    }

    /// 시간대별 예보 스키마 — 값이 빠진 칸(null)이 올 수 있어 원소를 옵셔널로 받는다
    private struct HourlyResponse: Codable {
        struct Hourly: Codable {
            let time: [String]
            let temperature_2m: [Double?]
            let apparent_temperature: [Double?]
            let relative_humidity_2m: [Double?]
            let precipitation_probability: [Double?]
            let precipitation: [Double?]
            let wind_speed_10m: [Double?]
            let weather_code: [Int?]
        }
        let utc_offset_seconds: Int
        let hourly: Hourly
    }
}
