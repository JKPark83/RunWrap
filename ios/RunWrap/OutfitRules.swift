import Foundation

/// 러닝 복장 아이템 — 룰(엔진)은 조합까지만 정하고 아이콘·라벨은 화면이 매핑한다
/// (RRTone과 같은 분리 — 엔진은 UI를 모른다)
enum OutfitItem: String, CaseIterable, Equatable {
    case singlet, shortSleeve, longSleeve, shorts, tights
    case jacket, gloves, windbreaker
    case waterproofCap, waterproofJacket
    case thermalTop, thermalBottom, beanie, neckWarmer
    case sunCap, sunglasses, sunscreen

    /// 노출 라벨 — 오늘 화면의 타일과 홈 판단 카드의 한 줄이 같은 표기를 쓰도록 한곳에 둔다.
    /// 아이콘 매핑은 여전히 화면 몫이다 (엔진은 UI를 모른다)
    var label: String {
        switch self {
        case .singlet: "싱글렛"
        case .shortSleeve: "반팔 티"
        case .longSleeve: "긴팔 티"
        case .shorts: "반바지"
        case .tights: "타이츠"
        case .jacket: "자켓"
        case .gloves: "장갑"
        case .windbreaker: "바람막이"
        case .waterproofCap: "방수 캡"
        case .waterproofJacket: "방수 자켓"
        case .thermalTop: "방한 상의"
        case .thermalBottom: "방한 하의"
        case .beanie: "비니"
        case .neckWarmer: "넥워머"
        case .sunCap: "러닝 캡"
        case .sunglasses: "선글라스"
        case .sunscreen: "선크림"
        }
    }
}

/// 복장 룰 — 체감온도 구간을 기본으로 습도·바람·강수·자외선·계절을 가산한다
/// (확장 요구, 2026-08-12). 구간은 계획서 M6 표, 가산 조건 출처:
/// - 자외선 보호 하한 UV 3: WHO Global Solar UV Index (Moderate부터 보호 권고)
/// - 고온다습·추위 레이어링: Nike 기온별 러닝 복장 가이드, 러닝 커뮤니티 겨울 복장 관례
/// 계절은 주입받은 now의 월로 판정한다 (기상학적 구분 — 여름 6~8월, 겨울 12~2월).
enum OutfitRules {
    /// 바람막이를 더하는 풍속 하한 (m/s)
    static let windbreakerMs = 8.0
    /// 자외선 보호 세트를 더하는 UV 지수 하한 (WHO Moderate)
    static let sunProtectionUV = 3.0

    static func outfit(apparentC: Double, humidityPct: Double, windMs: Double,
                       precipitationMm: Double, uvIndex: Double?, now: Date) -> [OutfitItem] {
        let raining = precipitationMm > 0
        var items: [OutfitItem]

        switch apparentC {
        case 24...:
            items = [.singlet, .shorts]
        case 16..<24:
            // 고온다습(≥80%)이면 땀이 증발하지 못해 한 단계 가볍게 — 반팔 대신 싱글렛
            items = humidityPct >= 80 ? [.singlet, .shorts] : [.shortSleeve, .shorts]
        case 8..<16:
            items = [.longSleeve, .tights]
        case 0..<8:
            items = [.longSleeve, .jacket, .tights, .gloves]
            // 겨울에는 같은 온도라도 귀 시림이 커 비니를 더한다
            if isWinter(now) { items.append(.beanie) }
        default:  // 영하
            items = [.thermalTop, .thermalBottom, .beanie, .neckWarmer, .gloves]
        }

        // 강수 가산 — 따뜻하면 챙으로 비만 막고(방수 캡), 서늘하면 체온 유지까지(방수 자켓)
        if raining {
            items.append(apparentC >= 16 ? .waterproofCap : .waterproofJacket)
        }

        // 바람 가산 — 8~24°C에서 바람막이. 24°C 이상 더위엔 겹옷이 역효과, 8°C 미만은 자켓이 겸한다
        if windMs >= windbreakerMs, (8..<24).contains(apparentC) {
            items.append(.windbreaker)
        }

        // 자외선 가산 — UV 3 이상이면 캡·선글라스·선크림 세트.
        // 여름에 UV 값이 없으면(야간 제외 API 누락) 보호 세트를 기본 포함한다.
        // 8°C 미만은 비니 영역이라 제외, 우천 시에는 이미 해가 없어 제외.
        let uv = uvIndex ?? (isSummer(now) ? sunProtectionUV : 0)
        if uv >= sunProtectionUV, apparentC >= 8, !raining {
            items.append(contentsOf: [.sunCap, .sunglasses, .sunscreen])
        }

        return items
    }

    private static func month(_ now: Date) -> Int {
        Calendar.current.component(.month, from: now)
    }

    private static func isSummer(_ now: Date) -> Bool { (6...8).contains(month(now)) }
    private static func isWinter(_ now: Date) -> Bool { month(now) == 12 || month(now) <= 2 }
}
