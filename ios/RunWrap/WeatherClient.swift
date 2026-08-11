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
}

struct WeatherClient {
    var session: URLSession = .shared

    func current(latitude: Double, longitude: Double) async throws -> CurrentWeather {
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            .init(name: "latitude", value: String(format: "%.2f", latitude)),
            .init(name: "longitude", value: String(format: "%.2f", longitude)),
            .init(name: "current", value: "temperature_2m,apparent_temperature,"
                + "relative_humidity_2m,wind_speed_10m,precipitation,weather_code,uv_index"),
            .init(name: "wind_speed_unit", value: "ms"),  // 복장 룰이 m/s 기준 (계획서 M6)
            .init(name: "daily", value: "temperature_2m_max"),  // 수분 알람: 오늘 최고기온 (계획서 M9)
            .init(name: "forecast_days", value: "1"),
            .init(name: "timezone", value: "auto"),
        ]
        let (data, _) = try await session.data(from: components.url!)
        return try Self.decode(data)
    }

    /// 응답 디코드 — fixture 테스트를 위해 네트워크와 분리 (계획서 M6)
    static func decode(_ data: Data) throws -> CurrentWeather {
        let response = try JSONDecoder().decode(Response.self, from: data)
        return CurrentWeather(temperatureC: response.current.temperature_2m,
                              apparentC: response.current.apparent_temperature,
                              humidityPct: response.current.relative_humidity_2m,
                              windMs: response.current.wind_speed_10m,
                              precipitationMm: response.current.precipitation,
                              forecastMaxC: response.daily?.temperature_2m_max.first,
                              weatherCode: response.current.weather_code,
                              uvIndex: response.current.uv_index)
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
            let temperature_2m_max: [Double]   // forecast_days=1 → 오늘 하루치
        }
        let current: Current
        let daily: Daily?
    }
}
