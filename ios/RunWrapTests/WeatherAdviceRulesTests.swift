import Foundation
import Testing
@testable import RunWrap

/// 날씨 조언 룰 — 실제 기온·습도 구간과 부가 조건(바람·비·자외선·뇌우), 심각도 정렬 검증
struct WeatherAdviceRulesTests {
    @Test("폭염+고습 — overload 2건(폭염·고온다습)이 앞, 자외선 caution이 뒤")
    func heatAndHumidity() {
        // 34°C ≥ 28 → 찜통 overload, 습도 85%·19°C 이상 → 고온다습 overload, UV 8 → caution
        let items = WeatherAdviceRules.advice(temperatureC: 34, humidityPct: 85, windMs: 1,
                                              precipitationMm: 0, uvIndex: 8, weatherCode: 0)
        #expect(items.count == 3)
        #expect(items[0].tone == .overload)
        #expect(items[1].tone == .overload)
        #expect(items[2].tone == .caution)
    }

    @Test("최적 조건 — 10°C·습도 50%는 improving 온도 조언 1건뿐")
    func ideal() {
        let items = WeatherAdviceRules.advice(temperatureC: 10, humidityPct: 50, windMs: 2,
                                              precipitationMm: 0, uvIndex: 3, weatherCode: 0)
        #expect(items.count == 1)
        #expect(items[0].tone == .improving)
    }

    @Test("비+강풍 — 포근 조언에 바람·강수 caution이 더해지고 caution이 앞에 온다")
    func rainAndWind() {
        // 16°C → 포근 steady, 바람 9m/s ≥ 8 → caution, 강수 1.2mm → caution
        let items = WeatherAdviceRules.advice(temperatureC: 16, humidityPct: 60, windMs: 9,
                                              precipitationMm: 1.2, uvIndex: 0, weatherCode: 61)
        #expect(items.count == 3)
        #expect(items[0].tone == .caution)
        #expect(items[2].tone == .steady)
    }

    @Test("뇌우 — 좋은 온도여도 중단 권고가 최우선으로 온다")
    func thunderstorm() {
        let items = WeatherAdviceRules.advice(temperatureC: 20, humidityPct: 50, windMs: 0,
                                              precipitationMm: 5, uvIndex: nil, weatherCode: 95)
        #expect(items.first?.tone == .overload)
        #expect(items.first?.text.contains("뇌우") == true)
    }

    @Test("건조 — 습도 30% 이하면 수분 조언이 붙는다")
    func dry() {
        let items = WeatherAdviceRules.advice(temperatureC: 10, humidityPct: 25, windMs: 0,
                                              precipitationMm: 0, uvIndex: nil, weatherCode: nil)
        #expect(items.count == 2)
        #expect(items.contains { $0.text.contains("건조") })
    }

    @Test("고온다습 — 19°C 이상·습도 80%면 overload, 18°C면 '습도 높음' caution (시간대 점수 감점과 같은 선)")
    func humidHeatLine() {
        // 19°C → 덥다 caution + 고온다습 overload → overload가 앞
        let hot = WeatherAdviceRules.advice(temperatureC: 19, humidityPct: 80, windMs: 0,
                                            precipitationMm: 0, uvIndex: nil, weatherCode: nil)
        #expect(hot.first?.text.contains("고온다습") == true)
        // 18°C → 덥다 caution + 습도 높음 caution (고온다습 overload 아님)
        let mild = WeatherAdviceRules.advice(temperatureC: 18, humidityPct: 80, windMs: 0,
                                             precipitationMm: 0, uvIndex: nil, weatherCode: nil)
        #expect(mild.contains { $0.text.contains("습도가 높아요") })
        #expect(mild.allSatisfy { !$0.text.contains("고온다습") })
    }
}

/// 비 판정 단일 기준 — WMO 코드 + 강수량, 뇌우 포함 (이슈 #109)
struct IsRainingTests {
    @Test("비 코드(WMO 61)면 강수량 0mm여도 비 — 조언에 '비가 와요'가 붙는다")
    func rainCodeWithoutPrecipitation() {
        #expect(WeatherAdviceRules.isRaining(code: 61, precipitationMm: 0))
        let items = WeatherAdviceRules.advice(temperatureC: 20, humidityPct: 50, windMs: 0,
                                              precipitationMm: 0, uvIndex: nil, weatherCode: 61)
        #expect(items.contains { $0.text.contains("비가 와요") })
    }

    @Test("맑음(코드 0)·강수량 0mm면 비가 아니다")
    func clearIsNotRain() {
        #expect(!WeatherAdviceRules.isRaining(code: 0, precipitationMm: 0))
        #expect(!WeatherAdviceRules.isRaining(code: nil, precipitationMm: 0))
    }

    @Test("뇌우(코드 95)는 비를 동반하므로 비로 본다")
    func thunderstormIsRain() {
        #expect(WeatherAdviceRules.isRaining(code: 95, precipitationMm: 0))
    }
}

/// 러닝 이름 헤드라인 — 우선순위(뇌우 > 눈 > 비 > 온도)와 온도 구간별 이름 검증
struct RunNameTests {
    @Test("뇌우 — 좋은 온도여도 트밀런이 최우선")
    func thunderstormWins() {
        let name = WeatherAdviceRules.runName(temperatureC: 20, precipitationMm: 5, weatherCode: 95)
        #expect(name.kind == .treadmill)
        #expect(name.tone == .overload)
    }

    @Test("눈 — 영하여도 펭귄런이 아니라 설중런이 먼저")
    func snowBeatsFreezing() {
        let name = WeatherAdviceRules.runName(temperatureC: -2, precipitationMm: 1, weatherCode: 71)
        #expect(name.kind == .snow)
        #expect(name.title == "설중런")
    }

    @Test("비 — 폭염이어도 우중런이 찜런보다 먼저")
    func rainBeatsHeat() {
        let name = WeatherAdviceRules.runName(temperatureC: 34, precipitationMm: 1.2, weatherCode: 61)
        #expect(name.kind == .rain)
        #expect(name.title == "우중런")
    }

    @Test("강수량만 있어도 우중런 — 코드가 맑음(0)이어도 강수 우선")
    func precipitationOnly() {
        let name = WeatherAdviceRules.runName(temperatureC: 20, precipitationMm: 0.4, weatherCode: 0)
        #expect(name.kind == .rain)
    }

    @Test("온도 구간 — 34°C 찜런, 10°C 펀런, 16°C 청량런, -3°C 펭귄런")
    func temperatureBuckets() {
        #expect(WeatherAdviceRules.runName(temperatureC: 34, precipitationMm: 0, weatherCode: 0).title == "찜런")
        #expect(WeatherAdviceRules.runName(temperatureC: 10, precipitationMm: 0, weatherCode: 0).title == "펀런")
        #expect(WeatherAdviceRules.runName(temperatureC: 16, precipitationMm: 0, weatherCode: 0).title == "청량런")
        #expect(WeatherAdviceRules.runName(temperatureC: -3, precipitationMm: 0, weatherCode: nil).title == "펭귄런")
    }

    @Test("온도 경계 — 시간대 점수 경계(0·4·7·15·17°C)와 23·28°C에서 이름이 바뀐다")
    func temperatureBoundaries() {
        func title(_ t: Double) -> String {
            WeatherAdviceRules.runName(temperatureC: t, precipitationMm: 0, weatherCode: 0).title
        }
        #expect(title(-0.1) == "펭귄런")
        #expect(title(0) == "핫팩런")
        #expect(title(3.9) == "핫팩런")
        #expect(title(4) == "청량런")
        #expect(title(6.9) == "청량런")
        #expect(title(7) == "펀런")
        #expect(title(14.9) == "펀런")
        #expect(title(15) == "청량런")
        #expect(title(16.9) == "청량런")
        #expect(title(17) == "그늘런")
        #expect(title(23) == "새벽런")
        #expect(title(28) == "찜런")
    }

    @Test("추천 구간 톤 일치 — 70점 이상인 4~17°C는 이름·조언 모두 긍정 톤, 밖은 caution")
    func toneMatchesGoodScore() {
        // 시간대 점수: 5°C·16°C → 70, 10°C → 100 (추천) / 2°C·18°C → 40 (미추천)
        for t in [5.0, 10, 16] {
            let name = WeatherAdviceRules.runName(temperatureC: t, precipitationMm: 0, weatherCode: 0)
            let advice = WeatherAdviceRules.advice(temperatureC: t, humidityPct: 50, windMs: 0,
                                                   precipitationMm: 0, uvIndex: nil, weatherCode: 0)
            #expect([.steady, .improving].contains(name.tone))
            #expect(advice.allSatisfy { [.steady, .improving].contains($0.tone) })
        }
        for t in [2.0, 18] {
            #expect(WeatherAdviceRules.runName(temperatureC: t, precipitationMm: 0, weatherCode: 0).tone == .caution)
        }
    }

    @Test("펀런 톤 — 7~15°C는 improving으로 advice의 온도 톤과 일치")
    func funTone() {
        let name = WeatherAdviceRules.runName(temperatureC: 10, precipitationMm: 0, weatherCode: 1)
        #expect(name.kind == .fun)
        #expect(name.tone == .improving)
    }
}
