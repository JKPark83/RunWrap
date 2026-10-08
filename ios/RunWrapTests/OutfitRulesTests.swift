import Foundation
import Testing
@testable import RunWrap

/// 복장 룰 — "달릴 때 기온"(실제 기온 + 10°C) 경계값 + 습도·바람·강수·자외선·계절 가산
/// + 출발 직후 안내 문구 + fixture 디코드 검증 (계획서 M6, 이슈 #219)
struct OutfitRulesTests {
    /// 고정 시각 — 계절 가산이 없는 봄(4월)을 기본으로 쓴다
    private let spring = ISO8601DateFormatter().date(from: "2026-04-15T10:00:00+09:00")!
    private let summer = ISO8601DateFormatter().date(from: "2026-07-15T10:00:00+09:00")!
    private let winter = ISO8601DateFormatter().date(from: "2027-01-15T10:00:00+09:00")!

    /// 기본값(맑음·무풍·건조하지 않은 습도 50%)으로 실제 기온만 바꿔 호출하는 헬퍼
    private func outfit(temperatureC: Double, humidityPct: Double = 50, windMs: Double = 0,
                        precipitationMm: Double = 0, weatherCode: Int? = nil,
                        uvIndex: Double? = nil, now: Date? = nil) -> [OutfitItem] {
        OutfitRules.outfit(temperatureC: temperatureC, humidityPct: humidityPct, windMs: windMs,
                           precipitationMm: precipitationMm, weatherCode: weatherCode,
                           uvIndex: uvIndex, now: now ?? spring)
    }

    @Test("기온 +10 기준 — 실제 5°C는 달릴 때 15°C 구간 복장(긴팔+타이츠)")
    func plusTenBasis() {
        // 5 + 10 = 15 → 8~16 구간
        #expect(outfit(temperatureC: 5) == [.longSleeve, .tights])
    }

    @Test("기온 경계 — 실제 13.9°C(달릴 때 23.9°C)는 반팔, 14.0°C부터 싱글렛")
    func temperatureBoundary24() {
        #expect(outfit(temperatureC: 13.9) == [.shortSleeve, .shorts])
        #expect(outfit(temperatureC: 14.0) == [.singlet, .shorts])
    }

    @Test("기온 경계 — 실제 5.9°C(달릴 때 15.9°C)는 긴팔·타이츠, 6.0°C부터 반팔·반바지")
    func temperatureBoundary16() {
        #expect(outfit(temperatureC: 5.9) == [.longSleeve, .tights])
        #expect(outfit(temperatureC: 6.0) == [.shortSleeve, .shorts])
    }

    @Test("기온 경계 — 실제 −10°C(달릴 때 0°C)는 긴팔·자켓·타이츠·장갑, 그 아래는 방한 세트+넥워머")
    func temperatureBoundaryZero() {
        #expect(outfit(temperatureC: -10) == [.longSleeve, .jacket, .tights, .gloves])
        #expect(outfit(temperatureC: -10.1)
            == [.thermalTop, .thermalBottom, .beanie, .neckWarmer, .gloves])
    }

    @Test("습도 가산 — 달릴 때 16~24°C에서 습도 80%부터 반팔 대신 싱글렛")
    func humidityLightensTop() {
        #expect(outfit(temperatureC: 10, humidityPct: 79.9) == [.shortSleeve, .shorts])
        #expect(outfit(temperatureC: 10, humidityPct: 80) == [.singlet, .shorts])
    }

    @Test("바람 가산 — 달릴 때 8~24°C에서 8.0 m/s부터 바람막이, 더위(달릴 때 24°C+)엔 안 붙는다")
    func windAddsWindbreaker() {
        #expect(outfit(temperatureC: 10, windMs: 7.9) == [.shortSleeve, .shorts])
        #expect(outfit(temperatureC: 10, windMs: 8.0) == [.shortSleeve, .shorts, .windbreaker])
        // 달릴 때 8~16°C 구간에도 적용 — 실제 2°C → 12°C
        #expect(outfit(temperatureC: 2, windMs: 8.0) == [.longSleeve, .tights, .windbreaker])
        // 실제 16°C → 26°C
        #expect(outfit(temperatureC: 16, windMs: 9.0) == [.singlet, .shorts])
    }

    @Test("강수 가산 — 실제 10°C(달릴 때 20°C) 이상은 방수 캡, 미만은 방수 자켓")
    func rainAddsWaterproof() {
        #expect(outfit(temperatureC: 16, precipitationMm: 0.5) == [.singlet, .shorts, .waterproofCap])
        #expect(outfit(temperatureC: 10, precipitationMm: 0.5)
            == [.shortSleeve, .shorts, .waterproofCap])
        // 젖으면 +10 보정을 다 믿지 않는다 — 반팔 구간(달릴 때 19.9°C)이어도 자켓
        #expect(outfit(temperatureC: 9.9, precipitationMm: 0.5)
            == [.shortSleeve, .shorts, .waterproofJacket])
        #expect(outfit(temperatureC: 0, precipitationMm: 0.5)
            == [.longSleeve, .tights, .waterproofJacket])
    }

    @Test("강수 가산 — 강수량 0mm여도 소나기 코드(WMO 80)면 비로 보고 방수 캡 (이슈 #109)")
    func rainCodeAddsWaterproof() {
        // 코드 80(약한 소나기)·강수량 0 → 비 판정, 달릴 때 20°C ≥ 20 → 방수 캡
        #expect(outfit(temperatureC: 10, precipitationMm: 0, weatherCode: 80)
            == [.shortSleeve, .shorts, .waterproofCap])
    }

    @Test("자외선 가산 — UV 3(WHO Moderate)부터 캡·선글라스·선크림 세트")
    func uvAddsSunProtection() {
        #expect(outfit(temperatureC: 10, uvIndex: 2.9) == [.shortSleeve, .shorts])
        #expect(outfit(temperatureC: 10, uvIndex: 3.0)
            == [.shortSleeve, .shorts, .sunCap, .sunglasses, .sunscreen])
    }

    @Test("자외선 예외 — 우천 시와 달릴 때 8°C 미만에는 보호 세트를 더하지 않는다")
    func uvSkippedWhenRainingOrCold() {
        #expect(outfit(temperatureC: 10, precipitationMm: 0.5, uvIndex: 8)
            == [.shortSleeve, .shorts, .waterproofCap])
        // 실제 −5°C → 달릴 때 5°C
        #expect(outfit(temperatureC: -5, uvIndex: 8) == [.longSleeve, .jacket, .tights, .gloves])
    }

    @Test("계절 가산 — 여름에 UV 값이 없으면 보호 세트를 기본 포함, 봄엔 미포함")
    func summerDefaultsSunProtection() {
        #expect(outfit(temperatureC: 20, uvIndex: nil, now: summer)
            == [.singlet, .shorts, .sunCap, .sunglasses, .sunscreen])
        #expect(outfit(temperatureC: 20, uvIndex: nil, now: spring) == [.singlet, .shorts])
    }

    @Test("계절 가산 — 겨울 달릴 때 0~8°C에는 비니가 붙는다")
    func winterAddsBeanie() {
        #expect(outfit(temperatureC: -5, now: winter)
            == [.longSleeve, .jacket, .tights, .gloves, .beanie])
        #expect(outfit(temperatureC: -5, now: spring) == [.longSleeve, .jacket, .tights, .gloves])
    }

    @Test("출발 직후 안내 — 실제 16°C 미만이면 '출발 후 5~10분은 쌀쌀' 한 줄, 그 위는 없음")
    func startChillNote() {
        #expect(OutfitRules.startChillNote(temperatureC: 15.9)
            == "출발 후 5~10분은 쌀쌀해야 정답이에요 — 몸이 데워지면 딱 맞아요")
        #expect(OutfitRules.startChillNote(temperatureC: 16) == nil)
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
