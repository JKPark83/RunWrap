import SwiftUI

// 일러스트 고정 팔레트 — 시안 §10. 크림 배경(#F2F0EA)과 다크 배경(#0A0A09) 양쪽에서
// 자체 윤곽선으로 대비를 확보하는 게 설계 의도라, 여기서만 RR 토큰 대신 고정색을 쓴다.
private enum BirdPalette {
    static let outline = Color(red: 0x20 / 255, green: 0x1F / 255, blue: 0x1B / 255)    // #201F1B
    static let cream = Color(red: 0xFF / 255, green: 0xF3 / 255, blue: 0xE4 / 255)      // #FFF3E4
    static let brand = Color(red: 0xFF / 255, green: 0x4D / 255, blue: 0x2E / 255)      // #FF4D2E
    static let brandDeep = Color(red: 0xD9 / 255, green: 0x3B / 255, blue: 0x1F / 255)  // #D93B1F
    static let hint = Color(red: 0xB9 / 255, green: 0xB3 / 255, blue: 0xA6 / 255)       // #B9B3A6 (속도선·바닥선)
}

/// 시안의 인라인 SVG(240×240 viewBox)를 SwiftUI Path로 옮긴 자리표시 일러스트.
/// 실제 1024×1024 에셋으로 교체할 때 레이아웃을 건드리지 않도록 정사각 바운딩 박스를 유지한다.
struct BirdView: View {
    let stage: GrowthStage
    var isSulky: Bool = false

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / 240
            ZStack {
                switch stage {
                case .egg:
                    EggShape().fill(BirdPalette.cream)
                    EggShape().stroke(BirdPalette.outline, lineWidth: 7 * s)
                    EggDots(scale: s)
                case .crackedEgg:
                    EggShape().fill(BirdPalette.cream)
                    EggShape().stroke(BirdPalette.outline, lineWidth: 7 * s)
                    CrackZigzag(scale: s)
                        .stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineCap: .round, lineJoin: .round))
                    Circle().fill(BirdPalette.brand).frame(width: 10 * s, height: 10 * s).position(x: 100 * s, y: 150 * s)
                    Circle().fill(BirdPalette.brand).frame(width: 8 * s, height: 8 * s).position(x: 134 * s, y: 160 * s)
                case .hatchling:
                    HatchingView(scale: s, isSulky: isSulky)
                case .fledgling:
                    ChickView(scale: s, isSulky: isSulky)
                case .flapping:
                    FledglingView(scale: s, isSulky: isSulky)
                case .flying:
                    FlyingView(scale: s, isSulky: isSulky)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

// MARK: - 1~2단계: 알

/// SVG 1·2: `M120 46c34 0 58 40 58 82a58 58 0 01-116 0c0-42 24-82 58-82z`
private struct EggShape: Shape {
    func path(in rect: CGRect) -> Path {
        let s = rect.width / 240
        var p = Path()
        p.move(to: CGPoint(x: 120 * s, y: 46 * s))
        p.addCurve(to: CGPoint(x: 178 * s, y: 128 * s),
                    control1: CGPoint(x: 154 * s, y: 46 * s),
                    control2: CGPoint(x: 178 * s, y: 86 * s))
        // a58 58 0 01-116 0 — 반지름 58 원호 절반을 3차 베지어로 근사
        p.addArc(center: CGPoint(x: 120 * s, y: 128 * s), radius: 58 * s,
                  startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
        p.addCurve(to: CGPoint(x: 120 * s, y: 46 * s),
                    control1: CGPoint(x: 62 * s, y: 86 * s),
                    control2: CGPoint(x: 86 * s, y: 46 * s))
        p.closeSubpath()
        return p
    }
}

/// SVG 1: 알 표면 브랜드 점 3개 (130px 결과 카드용)
private struct EggDots: View {
    let scale: CGFloat
    var body: some View {
        Circle().fill(BirdPalette.brand).frame(width: 10 * scale, height: 10 * scale).position(x: 100 * scale, y: 110 * scale)
        Circle().fill(BirdPalette.brand).frame(width: 12 * scale, height: 12 * scale).position(x: 132 * scale, y: 132 * scale)
        Circle().fill(BirdPalette.brand).frame(width: 8 * scale, height: 8 * scale).position(x: 118 * scale, y: 164 * scale)
    }
}

/// SVG 2: 지그재그 균열 `M78 112l20 12 14-16 18 14 16-12 14 10`
private struct CrackZigzag: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 78 * s, y: 112 * s))
        p.addLine(to: CGPoint(x: 98 * s, y: 124 * s))
        p.addLine(to: CGPoint(x: 112 * s, y: 108 * s))
        p.addLine(to: CGPoint(x: 130 * s, y: 122 * s))
        p.addLine(to: CGPoint(x: 146 * s, y: 110 * s))
        p.addLine(to: CGPoint(x: 160 * s, y: 120 * s))
        return p
    }
}

// MARK: - 3~4단계: 부화

/// SVG 3·4: 브랜드 원 몸통 + 눈 2개 + 부리 + 크림 껍질(지그재그 상단)
private struct HatchingView: View {
    let scale: CGFloat
    let isSulky: Bool

    var body: some View {
        let s = scale
        Circle().fill(BirdPalette.brand).frame(width: 84 * s, height: 84 * s).position(x: 120 * s, y: 112 * s)
        Circle().strokeBorder(BirdPalette.outline, lineWidth: 6.5 * s).frame(width: 84 * s, height: 84 * s).position(x: 120 * s, y: 112 * s)

        if isSulky {
            SulkyEye(cx: 104, cy: 105, scale: s)
            SulkyEye(cx: 136, cy: 105, scale: s)
        } else {
            NormalEye(cx: 104, cy: 104, hcx: 106, hcy: 101.5, scale: s)
            NormalEye(cx: 136, cy: 104, hcx: 138, hcy: 101.5, scale: s)
        }

        // 부리 `M112 120l8 10 8-10z`
        BeakTriangle(points: [(112, 120), (120, 130), (128, 120)], scale: s)

        // 크림 껍질(하단) `M62 140l16-14 14 13 14-13 14 13 14-13 14 13 16-14c2 8 3 14 3 20 0 30-27 50-61 50s-61-20-61-50c0-6 1-12 3-20z`
        EggshellBottom(scale: s)
    }
}

/// 정상 눈: 검은 원 + 흰 하이라이트 작은 원
private struct NormalEye: View {
    let cx: CGFloat
    let cy: CGFloat
    let hcx: CGFloat
    let hcy: CGFloat
    let scale: CGFloat
    var body: some View {
        Circle().fill(BirdPalette.outline).frame(width: 12 * scale, height: 12 * scale).position(x: cx * scale, y: cy * scale)
        Circle().fill(BirdPalette.cream).frame(width: 4 * scale, height: 4 * scale).position(x: hcx * scale, y: hcy * scale)
    }
}

/// 시무룩 눈: 아래로 감긴 반달(`a 8 8 0 0 0 16 0z` 채움) + 그 위 수평선
private struct SulkyEye: View {
    let cx: CGFloat
    let cy: CGFloat
    let scale: CGFloat
    var body: some View {
        SulkyEyeShape(cx: cx, cy: cy, scale: scale).fill(BirdPalette.outline)
        Rectangle().fill(BirdPalette.outline)
            .frame(width: 18 * scale, height: 4 * scale)
            .position(x: cx * scale, y: cy * scale)
    }
}

/// `M(cx-8) cy a8 8 0 0 0 16 0z` — 중심 아래로 볼록한 반원(하현 눈썹형 눈)
private struct SulkyEyeShape: Shape {
    let cx: CGFloat
    let cy: CGFloat
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: (cx - 8) * s, y: cy * s))
        p.addArc(center: CGPoint(x: cx * s, y: cy * s), radius: 8 * s,
                  startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
        p.closeSubpath()
        return p
    }
}

/// 크림 삼각 부리 — outline 스트로크 포함
private struct BeakTriangle: View {
    let points: [(CGFloat, CGFloat)]
    let scale: CGFloat
    var body: some View {
        BeakShape(points: points, scale: scale).fill(BirdPalette.cream)
        BeakShape(points: points, scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 4 * scale, lineJoin: .round))
    }
}

private struct BeakShape: Shape {
    let points: [(CGFloat, CGFloat)]
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard let first = points.first else { return p }
        p.move(to: CGPoint(x: first.0 * scale, y: first.1 * scale))
        for pt in points.dropFirst() {
            p.addLine(to: CGPoint(x: pt.0 * scale, y: pt.1 * scale))
        }
        p.closeSubpath()
        return p
    }
}

private struct EggshellBottom: View {
    let scale: CGFloat
    var body: some View {
        EggshellBottomShape(scale: scale).fill(BirdPalette.cream)
        EggshellBottomShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 6.5 * scale, lineJoin: .round))
    }
}

/// `M62 140l16-14 14 13 14-13 14 13 14-13 14 13 16-14c2 8 3 14 3 20 0 30-27 50-61 50s-61-20-61-50c0-6 1-12 3-20z`
private struct EggshellBottomShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 62 * s, y: 140 * s))
        p.addLine(to: CGPoint(x: 78 * s, y: 126 * s))
        p.addLine(to: CGPoint(x: 92 * s, y: 139 * s))
        p.addLine(to: CGPoint(x: 106 * s, y: 126 * s))
        p.addLine(to: CGPoint(x: 120 * s, y: 139 * s))
        p.addLine(to: CGPoint(x: 134 * s, y: 126 * s))
        p.addLine(to: CGPoint(x: 148 * s, y: 139 * s))
        p.addLine(to: CGPoint(x: 164 * s, y: 126 * s))
        // c2 8 3 14 3 20 → 오른쪽으로 완만히 내려가는 곡선
        p.addCurve(to: CGPoint(x: 167 * s, y: 146 * s),
                    control1: CGPoint(x: 166 * s, y: 134 * s),
                    control2: CGPoint(x: 167 * s, y: 140 * s))
        // 0 30-27 50-61 50 → 오른쪽 아래로 향하는 큰 원호(타원 하단부) 근사
        p.addCurve(to: CGPoint(x: 106 * s, y: 196 * s),
                    control1: CGPoint(x: 167 * s, y: 176 * s),
                    control2: CGPoint(x: 140 * s, y: 196 * s))
        // s-61-20-61-50 → 대칭으로 왼쪽 아래에서 위로
        p.addCurve(to: CGPoint(x: 45 * s, y: 146 * s),
                    control1: CGPoint(x: 72 * s, y: 196 * s),
                    control2: CGPoint(x: 45 * s, y: 176 * s))
        p.addCurve(to: CGPoint(x: 62 * s, y: 140 * s),
                    control1: CGPoint(x: 45 * s, y: 140 * s),
                    control2: CGPoint(x: 46 * s, y: 134 * s))
        p.closeSubpath()
        return p
    }
}

// MARK: - 5~6단계: 어린 새

/// SVG 5·6: 머리깃 + 브랜드 원 + 크림 배 + 날개 2개 + 눈 + 부리 + 다리
private struct ChickView: View {
    let scale: CGFloat
    let isSulky: Bool

    var body: some View {
        let s = scale
        // 머리깃 `M120 66c-4-12 4-20 10-22`
        TuftShape(scale: s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineCap: .round))

        // 몸통 원
        Circle().fill(BirdPalette.brand).frame(width: 116 * s, height: 116 * s).position(x: 120 * s, y: 130 * s)
        Circle().strokeBorder(BirdPalette.outline, lineWidth: 7 * s).frame(width: 116 * s, height: 116 * s).position(x: 120 * s, y: 130 * s)

        // 배(크림 타원)
        Ellipse().fill(BirdPalette.cream).frame(width: 60 * s, height: 46 * s).position(x: 120 * s, y: 152 * s)

        // 좌우 날개
        ChickWingLeft(scale: s)
        ChickWingRight(scale: s)

        if isSulky {
            SulkyEye(cx: 100, cy: 122, scale: s)
            SulkyEye(cx: 136, cy: 122, scale: s)
        } else {
            NormalEye(cx: 102, cy: 118, hcx: 104, hcy: 115.5, scale: s)
            NormalEye(cx: 138, cy: 118, hcx: 140, hcy: 115.5, scale: s)
        }

        // 부리 `M111 132l9 11 9-11z`
        BeakTriangle(points: [(111, 132), (120, 143), (129, 132)], scale: s)

        // 다리 `M106 186v16m28-16v16` + `M96 202h18m12 0h18`
        ChickLegs(scale: s)
    }
}

/// `M120 66c-4-12 4-20 10-22`
private struct TuftShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 120 * s, y: 66 * s))
        p.addCurve(to: CGPoint(x: 130 * s, y: 44 * s),
                    control1: CGPoint(x: 116 * s, y: 54 * s),
                    control2: CGPoint(x: 126 * s, y: 46 * s))
        return p
    }
}

/// `M66 124c-12 8-16 24-8 36 12-2 22-14 25-27z`
private struct ChickWingLeft: View {
    let scale: CGFloat
    var body: some View {
        ChickWingLeftShape(scale: scale).fill(BirdPalette.brandDeep)
        ChickWingLeftShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct ChickWingLeftShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 66 * s, y: 124 * s))
        p.addCurve(to: CGPoint(x: 58 * s, y: 160 * s),
                    control1: CGPoint(x: 54 * s, y: 132 * s),
                    control2: CGPoint(x: 50 * s, y: 148 * s))
        p.addCurve(to: CGPoint(x: 83 * s, y: 133 * s),
                    control1: CGPoint(x: 70 * s, y: 158 * s),
                    control2: CGPoint(x: 80 * s, y: 146 * s))
        p.closeSubpath()
        return p
    }
}

/// `M174 124c12 8 16 24 8 36-12-2-22-14-25-27z`
private struct ChickWingRight: View {
    let scale: CGFloat
    var body: some View {
        ChickWingRightShape(scale: scale).fill(BirdPalette.brandDeep)
        ChickWingRightShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct ChickWingRightShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 174 * s, y: 124 * s))
        p.addCurve(to: CGPoint(x: 182 * s, y: 160 * s),
                    control1: CGPoint(x: 186 * s, y: 132 * s),
                    control2: CGPoint(x: 190 * s, y: 148 * s))
        p.addCurve(to: CGPoint(x: 157 * s, y: 133 * s),
                    control1: CGPoint(x: 170 * s, y: 158 * s),
                    control2: CGPoint(x: 160 * s, y: 146 * s))
        p.closeSubpath()
        return p
    }
}

/// `M106 186v16m28-16v16` + `M96 202h18m12 0h18`
private struct ChickLegs: View {
    let scale: CGFloat
    var body: some View {
        let s = scale
        Path { p in
            p.move(to: CGPoint(x: 106 * s, y: 186 * s))
            p.addLine(to: CGPoint(x: 106 * s, y: 202 * s))
            p.move(to: CGPoint(x: 134 * s, y: 186 * s))
            p.addLine(to: CGPoint(x: 134 * s, y: 202 * s))
        }
        .stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 6 * s, lineCap: .round))

        Path { p in
            p.move(to: CGPoint(x: 96 * s, y: 202 * s))
            p.addLine(to: CGPoint(x: 114 * s, y: 202 * s))
            p.move(to: CGPoint(x: 126 * s, y: 202 * s))
            p.addLine(to: CGPoint(x: 144 * s, y: 202 * s))
        }
        .stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 6 * s, lineCap: .round))
    }
}

// MARK: - 7~8단계: 날갯짓

/// SVG 7·8: 펼친 날개 + 다리 + 바닥선
private struct FledglingView: View {
    let scale: CGFloat
    let isSulky: Bool

    var body: some View {
        let s = scale
        // 머리깃 `M120 78c-4-12 4-20 10-22`
        TuftShapeAt(startY: 78, scale: s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineCap: .round))

        Circle().fill(BirdPalette.brand).frame(width: 104 * s, height: 104 * s).position(x: 120 * s, y: 138 * s)
        Circle().strokeBorder(BirdPalette.outline, lineWidth: 7 * s).frame(width: 104 * s, height: 104 * s).position(x: 120 * s, y: 138 * s)

        Ellipse().fill(BirdPalette.cream).frame(width: 54 * s, height: 40 * s).position(x: 120 * s, y: 158 * s)

        // 활짝 펼친 날개 좌우
        SpreadWingLeft(scale: s)
        SpreadWingRight(scale: s)

        if isSulky {
            SulkyEye(cx: 104, cy: 132, scale: s)
            SulkyEye(cx: 136, cy: 132, scale: s)
        } else {
            NormalEye(cx: 104, cy: 128, hcx: 106, hcy: 125.5, scale: s)
            NormalEye(cx: 136, cy: 128, hcx: 138, hcy: 125.5, scale: s)
        }

        // 부리 `M112 142l8 10 8-10z`
        BeakTriangle(points: [(112, 142), (120, 152), (128, 142)], scale: s)

        // 다리 `M104 190l4 12m28-12l-4 12`
        Path { p in
            p.move(to: CGPoint(x: 104 * s, y: 190 * s))
            p.addLine(to: CGPoint(x: 108 * s, y: 202 * s))
            p.move(to: CGPoint(x: 132 * s, y: 190 * s))
            p.addLine(to: CGPoint(x: 128 * s, y: 202 * s))
        }
        .stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 6 * s, lineCap: .round))

        // 바닥선 `M84 218c22 10 50 10 72 0`
        Path { p in
            p.move(to: CGPoint(x: 84 * s, y: 218 * s))
            p.addCurve(to: CGPoint(x: 156 * s, y: 218 * s),
                        control1: CGPoint(x: 106 * s, y: 228 * s),
                        control2: CGPoint(x: 134 * s, y: 228 * s))
        }
        .stroke(BirdPalette.hint, style: StrokeStyle(lineWidth: 5 * s, lineCap: .round))
    }
}

/// `M120 (startY)c-4-12 4-20 10-22` — 시작 y좌표만 다른 머리깃 곡선 재사용
private struct TuftShapeAt: Shape {
    let startY: CGFloat
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 120 * s, y: startY * s))
        p.addCurve(to: CGPoint(x: 130 * s, y: (startY - 22) * s),
                    control1: CGPoint(x: 116 * s, y: (startY - 12) * s),
                    control2: CGPoint(x: 126 * s, y: (startY - 20) * s))
        return p
    }
}

/// `M76 120C58 96 42 88 26 92c8 18 24 34 48 40z`
private struct SpreadWingLeft: View {
    let scale: CGFloat
    var body: some View {
        SpreadWingLeftShape(scale: scale).fill(BirdPalette.brandDeep)
        SpreadWingLeftShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct SpreadWingLeftShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 76 * s, y: 120 * s))
        p.addCurve(to: CGPoint(x: 26 * s, y: 92 * s),
                    control1: CGPoint(x: 58 * s, y: 96 * s),
                    control2: CGPoint(x: 42 * s, y: 88 * s))
        p.addCurve(to: CGPoint(x: 74 * s, y: 132 * s),
                    control1: CGPoint(x: 34 * s, y: 110 * s),
                    control2: CGPoint(x: 50 * s, y: 126 * s))
        p.closeSubpath()
        return p
    }
}

/// `M164 120c18-24 34-32 50-28-8 18-24 34-48 40z`
private struct SpreadWingRight: View {
    let scale: CGFloat
    var body: some View {
        SpreadWingRightShape(scale: scale).fill(BirdPalette.brandDeep)
        SpreadWingRightShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct SpreadWingRightShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 164 * s, y: 120 * s))
        p.addCurve(to: CGPoint(x: 214 * s, y: 92 * s),
                    control1: CGPoint(x: 182 * s, y: 96 * s),
                    control2: CGPoint(x: 198 * s, y: 88 * s))
        p.addCurve(to: CGPoint(x: 166 * s, y: 132 * s),
                    control1: CGPoint(x: 206 * s, y: 110 * s),
                    control2: CGPoint(x: 190 * s, y: 126 * s))
        p.closeSubpath()
        return p
    }
}

// MARK: - 9~10단계: 나는 새

/// SVG 9·10: 기울어진 타원 몸통 + 큰 날개/꼬리 + 속도선
private struct FlyingView: View {
    let scale: CGFloat
    let isSulky: Bool

    var body: some View {
        let s = scale
        // 속도선 `M40 92h26m-38 22h22`
        Path { p in
            p.move(to: CGPoint(x: 40 * s, y: 92 * s))
            p.addLine(to: CGPoint(x: 66 * s, y: 92 * s))
            p.move(to: CGPoint(x: 2 * s, y: 114 * s))
            p.addLine(to: CGPoint(x: 24 * s, y: 114 * s))
        }
        .stroke(BirdPalette.hint, style: StrokeStyle(lineWidth: 5 * s, lineCap: .round))

        // 기울어진 몸통 타원 `rotate(-10 128 132)` rx54 ry40
        Ellipse().fill(BirdPalette.brand)
            .frame(width: 108 * s, height: 80 * s)
            .position(x: 128 * s, y: 132 * s)
            .rotationEffect(.degrees(-10), anchor: UnitPoint(x: 0.5, y: 0.5))
        Ellipse().strokeBorder(BirdPalette.outline, lineWidth: 7 * s)
            .frame(width: 108 * s, height: 80 * s)
            .position(x: 128 * s, y: 132 * s)
            .rotationEffect(.degrees(-10), anchor: UnitPoint(x: 0.5, y: 0.5))

        // 위쪽 날개(꼬리깃) `M118 104C108 68 120 44 148 36c6 26-2 56-24 74z`
        FlyingWingTop(scale: s)
        // 아래쪽 날개 `M124 162c-6 20 0 36 16 44 8-16 4-36-6-48z`
        FlyingWingBottom(scale: s)
        // 꼬리 깃털 `M82 142L44 130l12 16-16 10 38 6z`
        FlyingTail(scale: s)

        // 배(크림 타원, 회전) `rotate(-10 136 146)` rx24 ry15
        Ellipse().fill(BirdPalette.cream)
            .frame(width: 48 * s, height: 30 * s)
            .position(x: 136 * s, y: 146 * s)
            .rotationEffect(.degrees(-10), anchor: UnitPoint(x: 0.5, y: 0.5))

        if isSulky {
            SulkyEye(cx: 158, cy: 116, scale: s)
        } else {
            NormalEye(cx: 158, cy: 112, hcx: 160, hcy: 109.5, scale: s)
        }

        // 부리 `M182 116l24 8-22 9z`
        BeakTriangle(points: [(182, 116), (206, 124), (184, 133)], scale: s)
    }
}

/// `M118 104C108 68 120 44 148 36c6 26-2 56-24 74z`
private struct FlyingWingTop: View {
    let scale: CGFloat
    var body: some View {
        FlyingWingTopShape(scale: scale).fill(BirdPalette.brandDeep)
        FlyingWingTopShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct FlyingWingTopShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 118 * s, y: 104 * s))
        p.addCurve(to: CGPoint(x: 148 * s, y: 36 * s),
                    control1: CGPoint(x: 108 * s, y: 68 * s),
                    control2: CGPoint(x: 120 * s, y: 44 * s))
        p.addCurve(to: CGPoint(x: 124 * s, y: 110 * s),
                    control1: CGPoint(x: 154 * s, y: 62 * s),
                    control2: CGPoint(x: 146 * s, y: 92 * s))
        p.closeSubpath()
        return p
    }
}

/// `M124 162c-6 20 0 36 16 44 8-16 4-36-6-48z`
private struct FlyingWingBottom: View {
    let scale: CGFloat
    var body: some View {
        FlyingWingBottomShape(scale: scale).fill(BirdPalette.brandDeep)
        FlyingWingBottomShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct FlyingWingBottomShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 124 * s, y: 162 * s))
        p.addCurve(to: CGPoint(x: 140 * s, y: 206 * s),
                    control1: CGPoint(x: 118 * s, y: 182 * s),
                    control2: CGPoint(x: 124 * s, y: 198 * s))
        p.addCurve(to: CGPoint(x: 134 * s, y: 158 * s),
                    control1: CGPoint(x: 148 * s, y: 190 * s),
                    control2: CGPoint(x: 144 * s, y: 170 * s))
        p.closeSubpath()
        return p
    }
}

/// `M82 142L44 130l12 16-16 10 38 6z`
private struct FlyingTail: View {
    let scale: CGFloat
    var body: some View {
        FlyingTailShape(scale: scale).fill(BirdPalette.brandDeep)
        FlyingTailShape(scale: scale).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * scale, lineJoin: .round))
    }
}
private struct FlyingTailShape: Shape {
    let scale: CGFloat
    func path(in rect: CGRect) -> Path {
        let s = scale
        var p = Path()
        p.move(to: CGPoint(x: 82 * s, y: 142 * s))
        p.addLine(to: CGPoint(x: 44 * s, y: 130 * s))
        p.addLine(to: CGPoint(x: 56 * s, y: 146 * s))
        p.addLine(to: CGPoint(x: 40 * s, y: 156 * s))
        p.addLine(to: CGPoint(x: 78 * s, y: 162 * s))
        p.closeSubpath()
        return p
    }
}

// MARK: - 탭바 아이콘

/// SVG 12(양날개 펼친 새): `M120 62l-22 26c-34-8-70 2-86 24 30-4 56 2 76 18l14 34 18-22 18 22 14-34c20-16 46-22 76-18-16-22-52-32-86-24z`
///
/// `tabItem`의 라벨에서는 Text와 Image만 인식된다 — Shape 뷰를 그대로 넘기면 실루엣이
/// 뭉개지고 "홈" 글자까지 사라진다. 그래서 한 번 래스터라이즈해 템플릿 Image로 캐시하고,
/// 색(선택/비선택)은 탭바가 정하게 둔다.
@MainActor
enum BirdTabIcon {
    /// SF Symbol 탭 아이콘과 눈높이를 맞춘 한 변(pt). 원본이 240 정사각이라 정사각으로 그린다
    private static let side: CGFloat = 28

    static let image: Image = {
        let renderer = ImageRenderer(
            content: BirdTabIconShape()
                .frame(width: side, height: side)
                .foregroundStyle(.black)   // 템플릿이라 이 색은 버려진다
        )
        renderer.scale = 3
        guard let rendered = renderer.uiImage else { return Image(systemName: "bird") }
        return Image(uiImage: rendered).renderingMode(.template)
    }()
}

private struct BirdTabIconShape: Shape {
    func path(in rect: CGRect) -> Path {
        let s = rect.width / 240
        var p = Path()
        p.move(to: CGPoint(x: 120 * s, y: 62 * s))
        p.addLine(to: CGPoint(x: 98 * s, y: 88 * s))
        // c-34-8-70 2-86 24 (상대 3차 베지어 근사)
        p.addCurve(to: CGPoint(x: 12 * s, y: 112 * s),
                    control1: CGPoint(x: 64 * s, y: 80 * s),
                    control2: CGPoint(x: 28 * s, y: 90 * s))
        // 30-4 56 2 76 18 (상대 좌표 → 절대 변환)
        p.addCurve(to: CGPoint(x: 88 * s, y: 130 * s),
                    control1: CGPoint(x: 42 * s, y: 108 * s),
                    control2: CGPoint(x: 68 * s, y: 114 * s))
        p.addLine(to: CGPoint(x: 102 * s, y: 164 * s))
        p.addLine(to: CGPoint(x: 120 * s, y: 142 * s))
        p.addLine(to: CGPoint(x: 138 * s, y: 164 * s))
        p.addLine(to: CGPoint(x: 152 * s, y: 130 * s))
        // 20-16 46-22 76-18
        p.addCurve(to: CGPoint(x: 228 * s, y: 112 * s),
                    control1: CGPoint(x: 172 * s, y: 114 * s),
                    control2: CGPoint(x: 198 * s, y: 108 * s))
        // -16-22-52-32-86-24
        p.addCurve(to: CGPoint(x: 142 * s, y: 88 * s),
                    control1: CGPoint(x: 212 * s, y: 90 * s),
                    control2: CGPoint(x: 176 * s, y: 80 * s))
        p.closeSubpath()
        return p
    }
}
