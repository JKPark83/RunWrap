import Foundation

/// 날씨 조언 한 줄 — 엔진은 톤과 문장까지만 정하고 색·아이콘은 화면이 매핑한다
struct WeatherAdvice: Equatable {
    let tone: RRTone
    let text: String
}

/// 오늘의 러닝 이름 — 날씨를 러너 은어 헤드라인으로 번역한다 (펀런·우중런·찜런 …).
/// 심볼 매핑은 화면 몫이라 kind만 넘긴다. quip은 재미용 한 줄 — 주의 문구는 advice()가 담당.
struct RunName: Equatable {
    enum Kind {
        case treadmill, snow, rain, sauna, dawn, shade, fun, crisp, hotpack, penguin
    }

    let kind: Kind
    let tone: RRTone
    let title: String
    let quip: String
}

/// 날씨 조언 룰 — 러닝 이름 헤드라인(runName)과 주의 문구 목록(advice)을 만들어
/// 오늘 탭 제목 "오늘, 달리기 좋을까"에 대한 답을 만든다 (확장 요구, 2026-08-12).
/// 온도 구간·풍속 하한은 복장 룰(OutfitRules)의 24/16/8/0°C·8m/s와 맞춰
/// 두 카드가 모순된 말을 하지 않게 한다. 구간별 문장은 가정 — 사용 피드백으로 조정.
enum WeatherAdviceRules {
    /// 우선순위: 뇌우 > 눈 > 비 > 체감온도 구간 — 강수는 온도보다 그날의 러닝 성격을 더 크게 바꾼다.
    /// 톤은 advice()의 같은 조건 항목과 맞춰 카드 안에서 색이 모순되지 않게 한다.
    static func runName(apparentC: Double, precipitationMm: Double,
                        weatherCode: Int?) -> RunName {
        // 강수 판정은 현재 관측(WMO 코드·강수량)만 사용 — 예보 강수는 아직 조회하지 않는다
        let code = weatherCode ?? 0
        if (95...99).contains(code) {
            return .init(kind: .treadmill, tone: .overload, title: "트밀런",
                         quip: "뇌우엔 밖은 금물 — 오늘은 러닝머신과 데이트")
        }
        if (71...77).contains(code) || (85...86).contains(code) {
            return .init(kind: .snow, tone: .caution, title: "설중런",
                         quip: "뽀드득뽀드득, 설원을 달리는 날")
        }
        if precipitationMm > 0 || (51...67).contains(code) || (80...82).contains(code) {
            return .init(kind: .rain, tone: .caution, title: "우중런",
                         quip: "빗소리를 BGM 삼아 달리는 낭만")
        }
        return switch apparentC {
        case 33...:
            .init(kind: .sauna, tone: .overload, title: "찜런",
                  quip: "도로가 통째로 찜질방인 날")
        case 28..<33:
            .init(kind: .dawn, tone: .caution, title: "새벽런",
                  quip: "한낮은 양보, 해 뜨기 전이 골든타임")
        case 24..<28:
            .init(kind: .shade, tone: .caution, title: "그늘런",
                  quip: "그늘만 골라 밟는 여름 코스")
        case 16..<24:
            .init(kind: .fun, tone: .improving, title: "펀런",
                  quip: "핑계 없는 날씨 — 오늘 안 뛰면 손해")
        case 8..<16:
            .init(kind: .crisp, tone: .steady, title: "청량런",
                  quip: "청량한 공기가 페이스를 끌어줘요")
        case 0..<8:
            .init(kind: .hotpack, tone: .caution, title: "핫팩런",
                  quip: "주머니엔 핫팩, 워밍업은 두 배")
        default:  // 영하
            .init(kind: .penguin, tone: .overload, title: "펭귄런",
                  quip: "펭귄도 실내를 찾는 혹한")
        }
    }

    static func advice(apparentC: Double, humidityPct: Double, windMs: Double,
                       precipitationMm: Double, uvIndex: Double?,
                       weatherCode: Int?) -> [WeatherAdvice] {
        var items: [WeatherAdvice] = []

        // 뇌우는 다른 모든 조언에 앞서는 중단 권고 (WMO 95~99)
        if let code = weatherCode, (95...99).contains(code) {
            items.append(.init(tone: .overload, text: "뇌우가 있어요 — 야외 러닝은 미루는 게 안전해요"))
        }

        // 체감온도 구간별 기본 조언 — 항상 1건은 나온다
        switch apparentC {
        case 33...:
            items.append(.init(tone: .overload, text: "폭염 수준이에요 — 한낮은 피하고 이른 아침이나 밤에 짧게 달리세요"))
        case 28..<33:
            items.append(.init(tone: .caution, text: "많이 더워요 — 페이스를 평소보다 늦추고 거리를 줄이세요"))
        case 24..<28:
            items.append(.init(tone: .caution, text: "더운 편이에요 — 물을 자주 마시고 그늘이 있는 코스를 고르세요"))
        case 16..<24:
            items.append(.init(tone: .improving, text: "달리기 좋은 온도예요 — 기록을 노려볼 만한 날이에요"))
        case 8..<16:
            items.append(.init(tone: .steady, text: "선선해요 — 가볍게 몸을 풀고 나가면 딱 좋아요"))
        case 0..<8:
            items.append(.init(tone: .caution, text: "쌀쌀해요 — 부상 예방을 위해 워밍업을 평소보다 길게 하세요"))
        default:  // 영하
            items.append(.init(tone: .overload, text: "영하 추위예요 — 빙판을 조심하고 숨이 차면 강도를 낮추세요"))
        }

        // 습도 조언 — 더위와 겹치면 땀이 증발하지 못해 위험도가 한 단계 올라간다
        if humidityPct >= 80, apparentC >= 24 {
            items.append(.init(tone: .overload, text: "고온다습이에요 — 땀이 마르지 않아 체온이 계속 올라요. 오늘은 무리하지 마세요"))
        } else if humidityPct >= 80 {
            items.append(.init(tone: .caution, text: "습도가 높아요 — 평소보다 빨리 지칠 수 있어요"))
        } else if humidityPct <= 30 {
            items.append(.init(tone: .caution, text: "건조해요 — 목마르기 전에 미리 수분을 챙기세요"))
        }

        if windMs >= OutfitRules.windbreakerMs {
            items.append(.init(tone: .caution, text: "바람이 강해요 — 맞바람 구간에서는 페이스 욕심을 버리세요"))
        }
        if precipitationMm > 0 {
            items.append(.init(tone: .caution, text: "비가 와요 — 노면이 미끄러우니 보폭을 줄이세요"))
        }
        if let uv = uvIndex, uv >= 6 {
            items.append(.init(tone: .caution, text: "자외선이 강해요 — 선크림과 모자를 챙기세요"))
        }

        // 심한 것부터: overload → caution → steady → improving. 같은 톤은 추가된 순서 유지
        return items.enumerated()
            .sorted { (severity($0.element.tone), $0.offset) < (severity($1.element.tone), $1.offset) }
            .map(\.element)
    }

    private static func severity(_ tone: RRTone) -> Int {
        switch tone {
        case .overload: 0
        case .caution: 1
        case .steady: 2
        case .improving: 3
        }
    }
}
