import Foundation
import Testing
@testable import RunWrap

/// 복장 룰 — 온도 경계값 + 습도·바람·강수·자외선·계절 가산 + fixture 디코드 검증 (계획서 M6)
struct OutfitRulesTests {
    /// 고정 시각 — 계절 가산이 없는 봄(4월)을 기본으로 쓴다
    private let spring = ISO8601DateFormatter().date(from: "2026-04-15T10:00:00+09:00")!
    private let summer = ISO8601DateFormatter().date(from: "2026-07-15T10:00:00+09:00")!
    private let winter = ISO8601DateFormatter().date(from: "2027-01-15T10:00:00+09:00")!

    /// 기본값(맑음·무풍·건조하지 않은 습도 50%)으로 온도만 바꿔 호출하는 헬퍼
    private func outfit(apparentC: Double, humidityPct: Double = 50, windMs: Double = 0,
                        precipitationMm: Double = 0, uvIndex: Double? = nil,
                        now: Date? = nil) -> [OutfitItem] {
        OutfitRules.outfit(apparentC: apparentC, humidityPct: humidityPct, windMs: windMs,
                           precipitationMm: precipitationMm, uvIndex: uvIndex,
                           now: now ?? spring)
    }

    @Test("체감온도 경계 — 23.9°C는 반팔, 24.0°C부터 싱글렛")
    func temperatureBoundary24() {
        #expect(outfit(apparentC: 23.9) == [.shortSleeve, .shorts])
        #expect(outfit(apparentC: 24.0) == [.singlet, .shorts])
    }

    @Test("체감온도 경계 — 15.9°C는 긴팔·타이츠, 16.0°C부터 반팔·반바지")
    func temperatureBoundary16() {
        #expect(outfit(apparentC: 15.9) == [.longSleeve, .tights])
        #expect(outfit(apparentC: 16.0) == [.shortSleeve, .shorts])
    }

    @Test("체감온도 경계 — 0°C는 긴팔·자켓·타이츠·장갑, 영하부터 방한 세트+넥워머")
    func temperatureBoundaryZero() {
        #expect(outfit(apparentC: 0) == [.longSleeve, .jacket, .tights, .gloves])
        #expect(outfit(apparentC: -0.1)
            == [.thermalTop, .thermalBottom, .beanie, .neckWarmer, .gloves])
    }

    @Test("습도 가산 — 16~24°C에서 습도 80%부터 반팔 대신 싱글렛")
    func humidityLightensTop() {
        #expect(outfit(apparentC: 20, humidityPct: 79.9) == [.shortSleeve, .shorts])
        #expect(outfit(apparentC: 20, humidityPct: 80) == [.singlet, .shorts])
    }

    @Test("바람 가산 — 8~24°C에서 8.0 m/s부터 바람막이, 더위(24°C+)엔 안 붙는다")
    func windAddsWindbreaker() {
        #expect(outfit(apparentC: 20, windMs: 7.9) == [.shortSleeve, .shorts])
        #expect(outfit(apparentC: 20, windMs: 8.0) == [.shortSleeve, .shorts, .windbreaker])
        // 8~16°C 구간에도 확장 적용 (기존 16~24°C 한정에서 확대)
        #expect(outfit(apparentC: 12, windMs: 8.0) == [.longSleeve, .tights, .windbreaker])
        #expect(outfit(apparentC: 26, windMs: 9.0) == [.singlet, .shorts])
    }

    @Test("강수 가산 — 16°C 이상은 방수 캡, 미만은 방수 자켓")
    func rainAddsWaterproof() {
        #expect(outfit(apparentC: 26, precipitationMm: 0.5) == [.singlet, .shorts, .waterproofCap])
        #expect(outfit(apparentC: 20, precipitationMm: 0.5)
            == [.shortSleeve, .shorts, .waterproofCap])
        #expect(outfit(apparentC: 10, precipitationMm: 0.5)
            == [.longSleeve, .tights, .waterproofJacket])
    }

    @Test("자외선 가산 — UV 3(WHO Moderate)부터 캡·선글라스·선크림 세트")
    func uvAddsSunProtection() {
        #expect(outfit(apparentC: 20, uvIndex: 2.9) == [.shortSleeve, .shorts])
        #expect(outfit(apparentC: 20, uvIndex: 3.0)
            == [.shortSleeve, .shorts, .sunCap, .sunglasses, .sunscreen])
    }

    @Test("자외선 예외 — 우천 시와 8°C 미만에는 보호 세트를 더하지 않는다")
    func uvSkippedWhenRainingOrCold() {
        #expect(outfit(apparentC: 20, precipitationMm: 0.5, uvIndex: 8)
            == [.shortSleeve, .shorts, .waterproofCap])
        #expect(outfit(apparentC: 5, uvIndex: 8) == [.longSleeve, .jacket, .tights, .gloves])
    }

    @Test("계절 가산 — 여름에 UV 값이 없으면 보호 세트를 기본 포함, 봄엔 미포함")
    func summerDefaultsSunProtection() {
        #expect(outfit(apparentC: 30, uvIndex: nil, now: summer)
            == [.singlet, .shorts, .sunCap, .sunglasses, .sunscreen])
        #expect(outfit(apparentC: 30, uvIndex: nil, now: spring) == [.singlet, .shorts])
    }

    @Test("계절 가산 — 겨울 0~8°C에는 비니가 붙는다")
    func winterAddsBeanie() {
        #expect(outfit(apparentC: 5, now: winter)
            == [.longSleeve, .jacket, .tights, .gloves, .beanie])
        #expect(outfit(apparentC: 5, now: spring) == [.longSleeve, .jacket, .tights, .gloves])
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
