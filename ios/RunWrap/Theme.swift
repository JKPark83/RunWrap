import SwiftUI
import UIKit

/// 디자인 토큰 — 디자인 시안(claude.design "Runner Report" v0.4)의 rr-theme 팔레트.
/// 라이트/다크 값을 UIColor 다이내믹 프로바이더로 묶어 시스템 모드를 따른다.
/// v0.4는 "스포티 볼드" 리디자인 — 웜 크림 배경 + 레드오렌지 브랜드가 기준이다.
enum RR {
    static let bg = adaptive(0xF2F0EA, 0x0A0A09)
    static let surface = adaptive(0xFFFFFF, 0x151513)
    static let surface2 = adaptive(0xEAE7E0, 0x1E1E1B)
    static let text = adaptive(0x12120F, 0xF5F4EF)
    static let text2 = adaptive(0x54544E, 0xA8A79E)
    static let text3 = adaptive(0x8F8F86, 0x73736C)
    static let brand = adaptive(0xFF4D2E, 0xFF5A3C)
    /// 브랜드(주황)·톤 채움 위 글자·아이콘 — 지금은 양쪽 흰색이지만,
    /// 다크에서 브랜드가 밝아져 대비를 바꿔야 할 때 한 곳에서 일괄 조정하려고 토큰으로 둔다 (이슈 #85)
    static let onBrand = adaptive(0xFFFFFF, 0xFFFFFF)
    static let pos = adaptive(0x0E9146, 0x35E077)
    /// 강수(비·눈) 심볼 전용 청색 — 웜 팔레트의 유일한 한랭 색. 날씨 아이콘 palette 렌더링에만 쓴다
    static let sky = adaptive(0x2E8BD9, 0x5AAEFF)
    static let warn = adaptive(0xC77700, 0xFFAE00)
    static let dang = adaptive(0xD91F00, 0xFF3B30)

    /// 기동 스플래시 전용 — 앱 아이콘의 파랑 그라데이션 중간 톤 (light #16A5FB / dark #101426).
    /// 런치 스크린(Info.plist UIColorName)이 같은 에셋을 참조하므로 hex가 아니라 에셋에서 온다 —
    /// 색을 바꾸려면 LaunchBackground.colorset 한 곳만 고친다
    static let launchBg = Color("LaunchBackground")

    /// 시안: light rgba(20,20,16,.13) / dark rgba(255,255,255,.11)
    static let line = alpha(black: 0.13, white: 0.11)
    /// 차트 막대 바탕 — light rgba(20,20,16,.09) / dark rgba(255,255,255,.10)
    static let barFill = alpha(black: 0.09, white: 0.10)
    /// 카드 그림자 — 라이트는 옅은 검정, 다크는 배경이 거의 검정이라 그림자가 보이지 않아 0 (이슈 #85)
    static let shadow = alpha(black: 0.04, white: 0.0)
    /// 떠 있는 미리보기(공유 카드 등)용 진한 그림자 — 다크는 같은 이유로 0 (이슈 #85)
    static let shadowStrong = alpha(black: 0.10, white: 0.0)

    static let brandSoft = soft(brand, 0.12, 0.18)
    static let posSoft = soft(pos, 0.12, 0.16)
    static let warnSoft = soft(warn, 0.13, 0.16)
    static let dangSoft = soft(dang, 0.11, 0.16)

    /// PB 메달 색 (이슈 #21) — 종목 격에 맞춰 풀=금·하프=은·10K=동, 5K는 브랜드색
    static let medalGold = adaptive(0xC9A227, 0xE3C34E)
    static let medalSilver = adaptive(0x8E9196, 0xB6BAC1)
    static let medalBronze = adaptive(0xB0703C, 0xCE8B52)

    /// PB 메달 색 매핑 — PersonalRecords.Entry.label 기준
    static func medalColor(forPB label: String) -> Color {
        switch label {
        case "풀": medalGold
        case "하프": medalSilver
        case "10K": medalBronze
        default: brand
        }
    }

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
    }

    private static func alpha(black: CGFloat, white: CGFloat) -> Color {
        Color(UIColor {
            $0.userInterfaceStyle == .dark
                ? UIColor.white.withAlphaComponent(white)
                : UIColor.black.withAlphaComponent(black)
        })
    }

    private static func soft(_ base: Color, _ light: CGFloat, _ dark: CGFloat) -> Color {
        let ui = UIColor(base)
        return Color(UIColor {
            ui.resolvedColor(with: $0).withAlphaComponent($0.userInterfaceStyle == .dark ? dark : light)
        })
    }

    /// 화면 대제목용 디스플레이 서체 — 시안 v0.4의 'Black Han Sans'(번들 .ttf, SIL OFL 1.1).
    /// 한글 전용 굵은 서체라 획이 두꺼워 시안처럼 weight를 따로 주지 않는다.
    static func display(_ size: CGFloat) -> Font {
        .custom("BlackHanSans-Regular", size: size)
    }

    /// 큰 숫자용 디스플레이 서체 — 시안 v0.4의 'Anton'(번들 .ttf, SIL OFL 1.1).
    /// 라틴 숫자 전용이라 한글이 섞이는 자리에는 쓰지 않는다 (한글 글리프가 없어 폴백된다).
    static func numeral(_ size: CGFloat) -> Font {
        .custom("Anton-Regular", size: size)
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}

/// 카드 상태 톤 4가지 — 시안의 배지/강조색 매핑 (과부하·주의·유지·개선).
/// String·Codable은 위젯 스냅샷(App Group JSON)에 톤을 싣기 위해서다 (이슈 #181)
enum RRTone: String, Codable {
    case overload, caution, steady, improving

    var color: Color {
        switch self {
        case .overload: RR.dang
        case .caution: RR.warn
        case .steady: RR.brand
        case .improving: RR.pos
        }
    }

    var softColor: Color {
        switch self {
        case .overload: RR.dangSoft
        case .caution: RR.warnSoft
        case .steady: RR.brandSoft
        case .improving: RR.posSoft
        }
    }

    var label: String {
        switch self {
        case .overload: "과부하 구간"
        case .caution: "주의 구간"
        case .steady: "유지 중"
        case .improving: "좋아지는 중"
        }
    }

    var code: String {
        switch self {
        case .overload: "OVERLOAD"
        case .caution: "CAUTION"
        case .steady: "STEADY"
        case .improving: "IMPROVING"
        }
    }
}

/// 시안 v0.4의 상태 배지: 굵은 16×4 막대 + 한글 라벨 + 영문 코드.
/// v0.3까지의 soft 배경 알약에서 배경 없는 플랫 형태로 바뀌었다 — 카드 상단에서
/// 색 면적을 줄이고 헤드라인이 주인공이 되게 하려는 의도.
struct ToneBadge: View {
    let tone: RRTone
    /// 톤 라벨을 문맥에 맞게 덮어쓴다 (예: 칼로리 카드의 "BURNING", 꾸준함 카드의 "STREAK")
    var label: String?
    var code: String?

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(tone.color).frame(width: 16, height: 4)
            Text(label ?? tone.label)
                .font(.system(size: 11, weight: .heavy))
                .kerning(0.55)
                .foregroundStyle(tone.color)
            Text(code ?? tone.code)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .kerning(1)
                .foregroundStyle(tone.color.opacity(0.7))
        }
    }
}

/// 시안 v0.4의 기본 카드: surface + 1px line + r12 + 옅은 그림자.
/// v0.3의 큰 곡률(r24)에서 스포티한 각진 인상으로 조정됐다.
struct RRCardModifier: ViewModifier {
    var radius: CGFloat = 12

    func body(content: Content) -> some View {
        content
            .background(RR.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(RR.line))
            .shadow(color: RR.shadow, radius: 1, y: 1)
    }
}

extension View {
    func rrCard(radius: CGFloat = 12) -> some View { modifier(RRCardModifier(radius: radius)) }
}

/// 실내(트레드밀) 세션 표시용 소형 텍스트 배지 — 상태(톤)가 아니라 종류 표시라
/// RRTone 매핑을 쓰지 않는다 (계획서 M1). 시안: 10px/650, brandSoft 배경
struct IndoorBadge: View {
    var body: some View {
        Text("실내")
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(RR.brand)
            .padding(.horizontal, 6)
            .padding(.vertical, 2.5)
            .background(RR.brandSoft, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

/// 화면 상단 모노스페이스 아이브로 라벨 (예: "2026년 8월 2째주")
struct Eyebrow: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
            .kerning(1.4)
            .foregroundStyle(RR.text3)
    }
}

/// 수치 포맷 공통 헬퍼
enum Format {
    /// "1:52:34" 또는 "48:10"
    static func duration(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }

    /// "5′20″"
    static func pace(_ secPerKm: Double) -> String {
        let s = Int(secPerKm.rounded())
        return "\(s / 60)′\(String(format: "%02d", s % 60))″"
    }

    /// "5′20″/km"
    static func paceKm(_ secPerKm: Double) -> String { pace(secPerKm) + "/km" }

    static func km(_ value: Double) -> String { String(format: "%.1f", value) }

    /// 걷뛰 분 — 정수는 "2", 반 분은 "3.5". 사다리에 0.5분 단위가 있어 나눠 찍는다.
    /// 엔진의 `headline`과 카드의 metric이 같은 표기를 쓰도록 여기 둔다 (WalkRunEngine)
    static func walkRunMinutes(_ value: Double) -> String {
        value == value.rounded() ? String(format: "%.0f", value)
                                 : String(format: "%.1f", value)
    }

    /// "4,120" — 천 단위 구분 정수 (칼로리 카드)
    static func kcal(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        return formatter.string(from: NSNumber(value: value)) ?? "0"
    }

    /// "8월 2째주" — 차트 주 라벨. 두 달에 걸친 주는 그 주의 목요일이 속한 달로
    /// 정하고(ISO 8601 관행), 째주 순번도 목요일 날짜 기준(1–7일 → 1째주)이다.
    /// withYear를 켜면 "2026년 8월 2째주" — 연도도 목요일 기준(연말·연초에 걸친 주 대응).
    static func weekLabel(weekStart: Date, withYear: Bool = false) -> String {
        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작
        calendar.timeZone = .current
        let thursday = calendar.date(byAdding: .day, value: 3, to: weekStart) ?? weekStart
        let month = calendar.component(.month, from: thursday)
        let ordinal = (calendar.component(.day, from: thursday) + 6) / 7
        let label = "\(month)월 \(ordinal)째주"
        return withYear ? "\(calendar.component(.year, from: thursday))년 \(label)" : label
    }

    /// "9월 30일 오후 3:12" — 날짜·시각 한 줄 표기 (설정의 마지막 iCloud 백업 시각, 이슈 #129).
    /// 기기 로케일과 무관하게 한국어 고정, 시간대는 기기 설정을 따른다
    static func monthDayTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = .current
        formatter.dateFormat = "M월 d일 a h:mm"
        return formatter.string(from: date)
    }

    /// "이번 주" / "지난주" / "3주 전" — 달력 주(ISO 8601, 월요일 시작) 기준 상대 표기.
    /// 두 날짜 사이의 7일 묶음 수가 아니라 **각자 속한 주의 시작일끼리** 비교한다 —
    /// 그래야 월요일에 본 지난주 일요일 세션이 "이번 주"가 아니라 "지난주"가 된다.
    static func relativeWeek(of date: Date, now: Date) -> String {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        guard let dateWeek = calendar.dateInterval(of: .weekOfYear, for: date)?.start,
              let nowWeek = calendar.dateInterval(of: .weekOfYear, for: now)?.start
        else { return "이번 주" }
        let weeks = calendar.dateComponents([.weekOfYear], from: dateWeek, to: nowWeek).weekOfYear ?? 0
        switch weeks {
        case ..<1: return "이번 주"
        case 1: return "지난주"
        default: return "\(weeks)주 전"
        }
    }
}
