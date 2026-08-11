import SwiftUI
import UIKit

/// 디자인 토큰 — 디자인 시안(claude.design "Runner Report")의 rr-theme 팔레트.
/// 라이트/다크 값을 UIColor 다이내믹 프로바이더로 묶어 시스템 모드를 따른다.
enum RR {
    static let bg = adaptive(0xF1F0ED, 0x08090C)
    static let surface = adaptive(0xFFFFFF, 0x14161C)
    static let surface2 = adaptive(0xF7F6F3, 0x1B1E26)
    static let text = adaptive(0x12131A, 0xF3F4F8)
    static let text2 = adaptive(0x5F6270, 0x9DA1B0)
    static let text3 = adaptive(0x9A9DA8, 0x6C7080)
    static let brand = adaptive(0x6E5BFF, 0x8B7BFF)
    static let pos = adaptive(0x1B9E57, 0x34D97C)
    static let warn = adaptive(0xC87A00, 0xFFB020)
    static let dang = adaptive(0xD93A2B, 0xFF5C4D)

    static let line = alpha(black: 0.08, white: 0.10)
    static let barFill = alpha(black: 0.07, white: 0.09)

    static let brandSoft = soft(brand, 0.12, 0.18)
    static let posSoft = soft(pos, 0.12, 0.16)
    static let warnSoft = soft(warn, 0.13, 0.16)
    static let dangSoft = soft(dang, 0.11, 0.16)

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
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}

/// 카드 상태 톤 4가지 — 시안의 배지/강조색 매핑 (과부하·주의·유지·개선)
enum RRTone {
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

/// 시안의 상태 배지: 점 + 한글 라벨 + 영문 코드
struct ToneBadge: View {
    let tone: RRTone

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(tone.color).frame(width: 6, height: 6)
            Text(tone.label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(tone.color)
            Text(tone.code)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .kerning(1)
                .foregroundStyle(tone.color.opacity(0.65))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(tone.softColor, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

/// 시안의 기본 카드: surface + 1px line + r24 + 옅은 그림자
struct RRCardModifier: ViewModifier {
    var radius: CGFloat = 24

    func body(content: Content) -> some View {
        content
            .background(RR.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(RR.line))
            .shadow(color: .black.opacity(0.04), radius: 1, y: 1)
    }
}

extension View {
    func rrCard(radius: CGFloat = 24) -> some View { modifier(RRCardModifier(radius: radius)) }
}

/// 화면 상단 모노스페이스 아이브로 라벨 (예: "WEEK 32 · THIS WEEK")
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
}
