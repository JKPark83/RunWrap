import SwiftUI

/// 종별 성조 일러스트 — 도감·수집 세러모니에서 종마다 다른 새를 보여준다 (기획서 §5·§10).
///
/// 실제 에셋(`bird-adult-sparrow` …)이 오기 전까지의 자리표시지만, 도감의 재미는
/// "참새와 백조가 다르게 생겼다"에서 오므로 실루엣·색·무늬를 종마다 따로 그린다.
/// `BirdView`와 같은 240×240 좌표계·두꺼운 윤곽선 문법을 쓰고, 오른쪽으로 나는 옆모습이다.
/// 종의 크기 차이(작은 목표 = 작은 새)는 그림 안에서 배율로 표현한다.
struct SpeciesBirdView: View {
    let species: BirdSpecies

    var body: some View {
        Canvas { ctx, size in
            let s = size.width / 240
            let k = Self.bodyScale(species)
            // 240 좌표계로 맞춘 뒤, 종별 배율은 칸 중심(120,125)을 기준으로 건다
            ctx.scaleBy(x: s, y: s)
            ctx.translateBy(x: 120, y: 125)
            ctx.scaleBy(x: k, y: k)
            ctx.translateBy(x: -120, y: -125)
            // 배율이 작은 새도 윤곽선 굵기는 같게 보이도록 되돌린다
            let pen = BirdPen(ctx: ctx, width: 5.5 / k)
            switch species {
            case .sparrow: Self.sparrow(pen)
            case .swallow: Self.swallow(pen)
            case .falcon: Self.falcon(pen)
            case .goose: Self.goose(pen)
            case .crane: Self.crane(pen)
            case .swan: Self.swan(pen)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }

    /// 큰 목표일수록 큰 새 — 기러기·두루미·백조는 그림 자체가 칸을 가득 채운다
    private static func bodyScale(_ species: BirdSpecies) -> CGFloat {
        switch species {
        case .sparrow: 0.8
        case .swallow: 0.9
        case .falcon: 0.96
        case .goose, .crane, .swan: 1
        }
    }

    // MARK: - 참새: 통통한 몸 · 밤색 정수리 · 뺨의 검은 점 · 날개 흰 띠

    private static func sparrow(_ pen: BirdPen) {
        pen.speedLines()
        pen.part("M 80 148 L 40 136 L 50 154 L 38 166 L 82 166 Z", SpeciesPalette.sparrowDark)
        pen.part("M 118 170 C 112 190 118 206 134 212 C 142 196 138 180 130 166 Z", SpeciesPalette.sparrowDark)
        pen.part("E 124 138 56 44 -10", SpeciesPalette.sparrow, marks: [
            ("E 134 164 42 22 -10", BirdPalette.cream),          // 배
            ("E 156 95 40 16 14", SpeciesPalette.chestnut),      // 정수리
            ("E 152 133 15 11 0", BirdPalette.cream),            // 흰 뺨
            ("E 151 134 6 5 0", BirdPalette.outline),            // 뺨의 검은 점
            ("E 174 147 10 13 -25", BirdPalette.outline),        // 턱받이
        ])
        pen.part("M 106 122 C 94 88 102 60 128 44 C 140 72 134 104 122 128 Z", SpeciesPalette.sparrowDark, marks: [
            ("M 96 98 L 140 84 L 140 93 L 96 107 Z", BirdPalette.cream),   // 날개 띠
        ])
        pen.part("M 176 116 L 198 126 L 177 135 Z", SpeciesPalette.beakDark)
        pen.eye(160, 117)
    }

    // MARK: - 제비: 가는 몸 · 낫 모양 날개 · 깊게 갈라진 꼬리 · 붉은 멱

    private static func swallow(_ pen: BirdPen) {
        pen.speedLines()
        pen.part("M 88 124 L 18 96 L 60 134 L 14 168 L 90 146 Z", SpeciesPalette.navyDark)
        pen.part("M 126 152 C 124 182 110 206 84 222 C 92 196 100 174 108 150 Z", SpeciesPalette.navyDark)
        pen.part("E 128 134 54 30 -8", SpeciesPalette.navy, marks: [
            ("E 134 155 52 20 -8", BirdPalette.cream),           // 배
            ("E 172 139 15 12 0", BirdPalette.brandDeep),        // 붉은 멱
            ("E 179 115 9 7 0", BirdPalette.brandDeep),          // 이마
        ])
        pen.part("M 124 118 C 118 84 92 50 44 28 C 62 60 82 96 104 128 Z", SpeciesPalette.navyDark)
        pen.line("M 112 110 C 104 88 90 66 72 50", width: 0.55)
        pen.part("M 180 121 L 198 127 L 180 133 Z", SpeciesPalette.beakDark)
        pen.eye(162, 123)
    }

    // MARK: - 매: 뾰족한 날개 · 갈고리 부리 · 눈 밑 수염 무늬 · 가슴 가로줄

    private static func falcon(_ pen: BirdPen) {
        pen.part("M 84 136 L 30 118 L 26 150 L 82 158 Z", SpeciesPalette.slate, marks: [
            ("M 22 112 L 42 118 L 38 158 L 18 152 Z", SpeciesPalette.slateDark),   // 꼬리 끝 띠
            ("M 54 122 L 63 125 L 60 158 L 51 156 Z", SpeciesPalette.slateDark),
        ])
        pen.part("M 120 164 C 112 188 116 208 130 222 C 140 202 140 182 132 162 Z", SpeciesPalette.slateDark)
        pen.part("E 126 134 56 38 -10", SpeciesPalette.slate, marks: [
            ("E 140 157 42 22 -10", BirdPalette.cream),          // 가슴·배
            ("E 156 100 40 17 14", SpeciesPalette.slateDark),    // 머리 두건
            ("M 156 118 C 154 136 160 148 170 152 C 172 138 170 126 168 118 Z", SpeciesPalette.slateDark),   // 수염 무늬
        ])
        for bar in ["M 118 153 L 130 155", "M 136 161 L 148 161", "M 122 167 L 134 169", "M 146 151 L 155 150"] {
            pen.line(bar, SpeciesPalette.slateDark, width: 0.65)
        }
        pen.part("M 108 116 C 100 76 114 40 150 16 C 154 54 146 94 130 124 Z", SpeciesPalette.slateDark)
        pen.line("M 136 50 C 140 70 136 92 126 110", SpeciesPalette.slate, width: 0.55)
        pen.part("M 178 113 L 192 115 L 192 129 L 178 131 Z", SpeciesPalette.yellow)   // 납막
        pen.part("M 192 115 C 204 115 209 124 204 138 C 200 131 196 129 192 129 Z", SpeciesPalette.beakDark)
        pen.ctx.fill(Path(svg: "E 164 114 9.5 9.5 0"), with: .color(SpeciesPalette.yellow))   // 노란 눈테
        pen.eye(164, 114)
        pen.line("M 152 102 L 177 108")   // 눈썹뼈 — 매의 날카로운 인상
    }

    // MARK: - 기러기: 앞으로 뻗은 긴 목 · 넓은 날개 · 주황 부리 · 흰 허리

    private static func goose(_ pen: BirdPen) {
        pen.part("M 58 142 L 22 130 L 32 148 L 20 160 L 60 162 Z", SpeciesPalette.gooseDark)
        pen.part("M 102 176 C 96 198 102 214 118 222 C 126 206 122 190 112 174 Z", SpeciesPalette.gooseDark)
        pen.part("M 214 82 L 235 92 L 212 98 Z", SpeciesPalette.orange)
        // 목·머리와 몸통을 한 도형으로 합쳐 이음매 선을 없앤다
        pen.part("E 104 148 56 36 -6 "
                 + "M 142 122 C 162 114 178 100 186 86 C 190 72 206 70 214 80 C 220 90 216 102 206 106 C 194 124 172 144 150 154 Z",
                 SpeciesPalette.goose, marks: [
            ("M 150 110 L 230 60 L 230 120 L 160 160 Z", SpeciesPalette.gooseDark),   // 목·머리는 짙게
            ("E 216 88 6 9 0", BirdPalette.cream),               // 부리 뿌리의 흰 이마
            ("E 110 171 46 18 -6", SpeciesPalette.gooseLight),   // 배
            ("E 52 152 14 26 0", BirdPalette.cream),             // 흰 허리
        ])
        pen.part("M 92 128 C 80 92 90 58 122 32 C 134 64 128 102 114 134 Z", SpeciesPalette.gooseDark)
        pen.line("M 112 62 C 116 82 114 104 107 120", SpeciesPalette.goose, width: 0.55)
        pen.line("M 99 82 C 101 96 101 110 99 122", SpeciesPalette.goose, width: 0.55)
        pen.eye(203, 88, r: 4.5)
    }

    // MARK: - 두루미: 붉은 정수리 · 검은 목 · 뒤로 뻗은 긴 다리 · 검은 날개 끝

    private static func crane(_ pen: BirdPen) {
        pen.line("M 66 150 L 14 172")
        pen.line("M 66 158 L 18 186")
        pen.part("M 74 124 C 52 120 34 132 26 152 C 44 150 60 150 78 152 Z", SpeciesPalette.ink)   // 검은 꽁지깃
        pen.part("M 102 164 C 94 190 100 212 118 226 C 126 206 122 184 112 162 Z", SpeciesPalette.white)
        pen.part("M 217 77 L 236 91 L 213 95 Z", SpeciesPalette.beakOlive)
        pen.part("E 104 142 52 30 -6 "
                 + "M 140 126 C 162 120 182 102 192 84 C 196 70 212 68 220 78 C 226 88 220 100 210 102 C 198 122 174 142 148 152 Z",
                 SpeciesPalette.white, marks: [
            ("E 108 163 44 12 -6", SpeciesPalette.shade),        // 배 그늘
            ("M 152 106 L 232 56 L 232 116 L 166 156 Z", SpeciesPalette.ink),   // 검은 목
            ("E 198 98 9 13 35", SpeciesPalette.white),          // 눈 뒤로 흐르는 흰 줄
            ("E 211 72 13 7 15", SpeciesPalette.red),            // 붉은 정수리
        ])
        pen.part("M 92 124 C 76 86 84 46 118 14 C 134 50 128 96 114 130 Z", SpeciesPalette.white, marks: [
            ("M 118 14 C 134 50 128 96 114 130 L 103 124 C 116 92 120 54 108 22 Z", SpeciesPalette.ink),   // 검은 둘째날개깃
        ])
        pen.ctx.fill(Path(svg: "E 209 84 6 6 0"), with: .color(SpeciesPalette.white))
        pen.eye(209, 84, r: 3.5)
    }

    // MARK: - 백조: S자 목 · 물결 진 큰 날개 · 주황 부리와 검은 혹

    private static func swan(_ pen: BirdPen) {
        pen.part("M 52 144 L 20 126 L 32 150 L 50 162 Z", SpeciesPalette.white)
        pen.part("M 203 68 L 228 84 L 198 88 Z", SpeciesPalette.orange)
        pen.part("E 100 152 56 34 -4 "
                 + "M 134 134 C 150 136 168 128 170 112 C 172 98 164 90 168 76 C 172 60 192 56 202 66 "
                 + "C 210 76 206 88 196 90 C 188 92 190 102 192 114 C 194 138 170 160 144 162 Z",
                 SpeciesPalette.white, marks: [
            ("E 104 177 48 14 -4", SpeciesPalette.shade),        // 배 그늘
            ("E 205 71 6 6 0", SpeciesPalette.ink),              // 부리 뿌리의 검은 혹
        ])
        // 깃 끝이 물결 진 큰 날개
        pen.part("M 86 132 C 66 96 72 54 104 22 C 110 32 112 42 110 52 C 120 46 127 50 126 62 "
                 + "C 135 59 139 68 133 80 C 133 100 125 120 112 138 Z", SpeciesPalette.white)
        pen.line("M 104 52 C 104 76 100 100 94 118", SpeciesPalette.shadeDeep, width: 0.55)
        pen.line("M 120 66 C 120 86 114 106 106 124", SpeciesPalette.shadeDeep, width: 0.55)
        pen.eye(192, 72, r: 4)
    }
}

/// 종별 일러스트 고정색 — `BirdPalette`와 같은 이유로 RR 토큰 대신 고정색을 쓴다
/// (크림·다크 배경 양쪽에서 윤곽선으로 대비를 잡는 그림이라 테마를 따라 바뀌면 안 된다).
private enum SpeciesPalette {
    static let sparrow = hex(0xC98F54)
    static let sparrowDark = hex(0x8C5B31)
    static let chestnut = hex(0x7A4325)
    static let navy = hex(0x2F4A8F)
    static let navyDark = hex(0x1E2F5E)
    static let slate = hex(0x748494)
    static let slateDark = hex(0x3F4A56)
    static let yellow = hex(0xF4B93A)
    static let goose = hex(0xA88B6A)
    static let gooseDark = hex(0x6E5642)
    static let gooseLight = hex(0xDCCBB2)
    static let orange = hex(0xF28C28)
    static let white = hex(0xFDFCF8)
    static let shade = hex(0xE3E7EE)
    static let shadeDeep = hex(0xB9C0CC)
    static let ink = hex(0x2A2824)
    static let red = hex(0xE0362B)
    static let beakDark = hex(0x4E4840)
    static let beakOlive = hex(0xB9A873)

    private static func hex(_ v: Int) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

/// 240 좌표계에 도형을 찍는 펜 — 채움·무늬·윤곽선 순서를 한 곳에 묶는다
private struct BirdPen {
    let ctx: GraphicsContext
    /// 윤곽선 굵기 (도형 바깥으로 보이는 두께)
    let width: CGFloat

    /// 윤곽선(2배 굵기) → 채움 → 무늬 순으로 그린다. 채움이 선의 안쪽 절반을 덮어
    /// 바깥 절반만 남으므로, 여러 하위 경로를 한 도형으로 합쳐도 이음매 선이 생기지 않는다.
    /// 무늬는 도형 안으로 잘라 윤곽선을 침범하지 않게 한다
    func part(_ d: String, _ color: Color, marks: [(String, Color)] = []) {
        let path = Path(svg: d)
        ctx.stroke(path, with: .color(BirdPalette.outline),
                   style: StrokeStyle(lineWidth: width * 2, lineCap: .round, lineJoin: .round))
        ctx.fill(path, with: .color(color))
        var inner = ctx
        inner.clip(to: path)
        for (mark, markColor) in marks {
            inner.fill(Path(svg: mark), with: .color(markColor))
        }
    }

    /// 깃 결·다리 같은 선. width는 윤곽선 굵기에 대한 배수
    func line(_ d: String, _ color: Color = BirdPalette.outline, width factor: CGFloat = 1) {
        ctx.stroke(Path(svg: d), with: .color(color),
                   style: StrokeStyle(lineWidth: width * factor, lineCap: .round, lineJoin: .round))
    }

    /// 눈 + 반짝임 — `BirdView`의 NormalEye와 같은 비율
    func eye(_ x: CGFloat, _ y: CGFloat, r: CGFloat = 6.5) {
        ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
                 with: .color(BirdPalette.outline))
        let h = r * 0.34
        ctx.fill(Path(ellipseIn: CGRect(x: x + r * 0.3 - h, y: y - r * 0.38 - h, width: h * 2, height: h * 2)),
                 with: .color(.white))
    }

    /// `BirdView` 나는 새와 같은 속도선 — 작은 새에만 쓴다 (큰 새는 칸을 꽉 채운다)
    func speedLines() {
        line("M 22 92 L 46 92", BirdPalette.hint)
        line("M 4 114 L 24 114", BirdPalette.hint)
    }
}

private extension Path {
    /// 절대좌표 SVG 부분집합(M L Q C Z)에 타원 `E cx cy rx ry 회전°`를 더한 최소 파서.
    /// 토큰은 공백으로만 나눈다 — 그림 좌표를 시안 path처럼 한 줄로 적기 위한 것
    init(svg d: String) {
        self.init()
        var command: Substring = "M"
        var n: [CGFloat] = []
        for token in d.split(separator: " ") {
            guard let value = Double(token) else {
                command = token
                n = []
                if command == "Z" { closeSubpath() }
                continue
            }
            n.append(CGFloat(value))
            switch (command, n.count) {
            case ("M", 2): move(to: CGPoint(x: n[0], y: n[1]))
            case ("L", 2): addLine(to: CGPoint(x: n[0], y: n[1]))
            case ("Q", 4): addQuadCurve(to: CGPoint(x: n[2], y: n[3]), control: CGPoint(x: n[0], y: n[1]))
            case ("C", 6): addCurve(to: CGPoint(x: n[4], y: n[5]),
                                    control1: CGPoint(x: n[0], y: n[1]), control2: CGPoint(x: n[2], y: n[3]))
            case ("E", 5): addEllipse(in: CGRect(x: -n[2], y: -n[3], width: n[2] * 2, height: n[3] * 2),
                                      transform: CGAffineTransform(translationX: n[0], y: n[1])
                                          .rotated(by: n[4] * .pi / 180))
            default: continue
            }
            n = []
        }
    }
}
