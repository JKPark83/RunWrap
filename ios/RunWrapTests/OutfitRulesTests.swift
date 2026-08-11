import Foundation
import Testing
@testable import RunWrap

/// 복장 룰 경계값 + Open-Meteo 응답 fixture 디코드 검증 (계획서 M6)
struct OutfitRulesTests {
    @Test("체감온도 경계 — 23.9°C는 반팔, 24.0°C부터 싱글렛")
    func temperatureBoundary24() {
        #expect(OutfitRules.outfit(apparentC: 23.9, precipitationMm: 0, windMs: 0)
            == [.shortSleeve, .shorts])
        #expect(OutfitRules.outfit(apparentC: 24.0, precipitationMm: 0, windMs: 0)
            == [.singlet, .shorts])
    }

    @Test("체감온도 경계 — 15.9°C는 긴팔·타이츠, 16.0°C부터 반팔·반바지")
    func temperatureBoundary16() {
        #expect(OutfitRules.outfit(apparentC: 15.9, precipitationMm: 0, windMs: 0)
            == [.longSleeve, .tights])
        #expect(OutfitRules.outfit(apparentC: 16.0, precipitationMm: 0, windMs: 0)
            == [.shortSleeve, .shorts])
    }

    @Test("체감온도 경계 — 0°C는 자켓·장갑, 영하부터 방한 세트")
    func temperatureBoundaryZero() {
        #expect(OutfitRules.outfit(apparentC: 0, precipitationMm: 0, windMs: 0)
            == [.jacket, .gloves])
        #expect(OutfitRules.outfit(apparentC: -0.1, precipitationMm: 0, windMs: 0)
            == [.thermalTop, .thermalBottom, .beanie, .gloves])
    }

    @Test("바람 가산 — 16~23°C 구간에서 8.0 m/s부터 바람막이가 붙는다")
    func windAddsWindbreaker() {
        #expect(OutfitRules.outfit(apparentC: 20, precipitationMm: 0, windMs: 7.9)
            == [.shortSleeve, .shorts])
        #expect(OutfitRules.outfit(apparentC: 20, precipitationMm: 0, windMs: 8.0)
            == [.shortSleeve, .shorts, .windbreaker])
    }

    @Test("강수 가산 — 더위엔 방수 캡, 쌀쌀하면 방수 자켓")
    func rainAddsWaterproof() {
        #expect(OutfitRules.outfit(apparentC: 26, precipitationMm: 0.5, windMs: 0)
            == [.singlet, .shorts, .waterproofCap])
        #expect(OutfitRules.outfit(apparentC: 10, precipitationMm: 0.5, windMs: 0)
            == [.longSleeve, .tights, .waterproofJacket])
    }

    @Test("응답 fixture 디코드 — current 필드 5개를 그대로 옮긴다")
    func decodeFixture() throws {
        // Open-Meteo v1/forecast 실제 응답 축약 — current 외 필드는 무시된다
        let fixture = """
        {
          "latitude": 37.5, "longitude": 126.94, "timezone": "Asia/Seoul",
          "current_units": { "temperature_2m": "°C", "wind_speed_10m": "m/s" },
          "current": {
            "time": "2026-08-10T18:00",
            "temperature_2m": 29.4,
            "apparent_temperature": 33.1,
            "relative_humidity_2m": 78,
            "wind_speed_10m": 3.6,
            "precipitation": 0.2
          }
        }
        """
        let weather = try WeatherClient.decode(Data(fixture.utf8))
        #expect(weather.temperatureC == 29.4)
        #expect(weather.apparentC == 33.1)
        #expect(weather.humidityPct == 78)
        #expect(weather.windMs == 3.6)
        #expect(weather.precipitationMm == 0.2)
    }
}
