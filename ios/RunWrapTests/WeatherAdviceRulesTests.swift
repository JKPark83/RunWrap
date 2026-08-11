import Foundation
import Testing
@testable import RunWrap

/// 날씨 조언 룰 — 온도·습도 구간과 부가 조건(바람·비·자외선·뇌우), 심각도 정렬 검증
struct WeatherAdviceRulesTests {
    @Test("폭염+고습 — overload 2건(폭염·고온다습)이 앞, 자외선 caution이 뒤")
    func heatAndHumidity() {
        // 34°C → 폭염 overload, 습도 85%·체감 24 이상 → 고온다습 overload, UV 8 → caution
        let items = WeatherAdviceRules.advice(apparentC: 34, humidityPct: 85, windMs: 1,
                                              precipitationMm: 0, uvIndex: 8, weatherCode: 0)
        #expect(items.count == 3)
        #expect(items[0].tone == .overload)
        #expect(items[1].tone == .overload)
        #expect(items[2].tone == .caution)
    }

    @Test("최적 조건 — 20°C·습도 50%는 improving 온도 조언 1건뿐")
    func ideal() {
        let items = WeatherAdviceRules.advice(apparentC: 20, humidityPct: 50, windMs: 2,
                                              precipitationMm: 0, uvIndex: 3, weatherCode: 0)
        #expect(items.count == 1)
        #expect(items[0].tone == .improving)
    }

    @Test("비+강풍 — 선선 조언에 바람·강수 caution이 더해지고 caution이 앞에 온다")
    func rainAndWind() {
        // 12°C → 선선 steady, 바람 9m/s ≥ 8 → caution, 강수 1.2mm → caution
        let items = WeatherAdviceRules.advice(apparentC: 12, humidityPct: 60, windMs: 9,
                                              precipitationMm: 1.2, uvIndex: 0, weatherCode: 61)
        #expect(items.count == 3)
        #expect(items[0].tone == .caution)
        #expect(items[2].tone == .steady)
    }

    @Test("뇌우 — 좋은 온도여도 중단 권고가 최우선으로 온다")
    func thunderstorm() {
        let items = WeatherAdviceRules.advice(apparentC: 20, humidityPct: 50, windMs: 0,
                                              precipitationMm: 5, uvIndex: nil, weatherCode: 95)
        #expect(items.first?.tone == .overload)
        #expect(items.first?.text.contains("뇌우") == true)
    }

    @Test("건조 — 습도 30% 이하면 수분 조언이 붙는다")
    func dry() {
        let items = WeatherAdviceRules.advice(apparentC: 20, humidityPct: 25, windMs: 0,
                                              precipitationMm: 0, uvIndex: nil, weatherCode: nil)
        #expect(items.count == 2)
        #expect(items.contains { $0.text.contains("건조") })
    }
}

/// 러닝 이름 헤드라인 — 우선순위(뇌우 > 눈 > 비 > 온도)와 온도 구간별 이름 검증
struct RunNameTests {
    @Test("뇌우 — 좋은 온도여도 트밀런이 최우선")
    func thunderstormWins() {
        let name = WeatherAdviceRules.runName(apparentC: 20, precipitationMm: 5, weatherCode: 95)
        #expect(name.kind == .treadmill)
        #expect(name.tone == .overload)
    }

    @Test("눈 — 영하여도 펭귄런이 아니라 설중런이 먼저")
    func snowBeatsFreezing() {
        let name = WeatherAdviceRules.runName(apparentC: -2, precipitationMm: 1, weatherCode: 71)
        #expect(name.kind == .snow)
        #expect(name.title == "설중런")
    }

    @Test("비 — 폭염이어도 우중런이 찜런보다 먼저")
    func rainBeatsHeat() {
        let name = WeatherAdviceRules.runName(apparentC: 34, precipitationMm: 1.2, weatherCode: 61)
        #expect(name.kind == .rain)
        #expect(name.title == "우중런")
    }

    @Test("강수량만 있어도 우중런 — 코드가 맑음(0)이어도 강수 우선")
    func precipitationOnly() {
        let name = WeatherAdviceRules.runName(apparentC: 20, precipitationMm: 0.4, weatherCode: 0)
        #expect(name.kind == .rain)
    }

    @Test("온도 구간 — 34°C 찜런, 20°C 펀런, 12°C 청량런, -3°C 펭귄런")
    func temperatureBuckets() {
        #expect(WeatherAdviceRules.runName(apparentC: 34, precipitationMm: 0, weatherCode: 0).title == "찜런")
        #expect(WeatherAdviceRules.runName(apparentC: 20, precipitationMm: 0, weatherCode: 0).title == "펀런")
        #expect(WeatherAdviceRules.runName(apparentC: 12, precipitationMm: 0, weatherCode: 0).title == "청량런")
        #expect(WeatherAdviceRules.runName(apparentC: -3, precipitationMm: 0, weatherCode: nil).title == "펭귄런")
    }

    @Test("펀런 톤 — 16~24°C는 improving으로 advice의 온도 톤과 일치")
    func funTone() {
        let name = WeatherAdviceRules.runName(apparentC: 20, precipitationMm: 0, weatherCode: 1)
        #expect(name.kind == .fun)
        #expect(name.tone == .improving)
    }
}
