import SwiftUI

/// 러닝화 기본 일러스트 — 사진이 없는 신발의 썸네일·카드에 쓴다 (이슈 #206).
/// 마스코트 새(BirdIllustrations.swift)와 한 가족으로 보이도록 같은 240×240 좌표계·굵은 윤곽선·
/// 고정 팔레트를 그대로 따른다. 자체 윤곽선으로 라이트/다크 양쪽 대비를 확보하는 설계라
/// BirdIllustrations와 같은 예외로 RR 토큰 대신 BirdPalette 고정색을 쓴다.
/// 옆면 장식은 실제 브랜드 마크(스우시·삼선 등)를 연상시키지 않도록 둥근 힐 카운터 패치 하나만 둔다.
struct ShoeView: View {
    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / 240
            ZStack {
                // 힐 풀탭 — 갑피 뒤에 깔려 윗부분만 보인다
                pullTab(s).fill(BirdPalette.brandDeep)
                pullTab(s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineJoin: .round))

                // 갑피(크림) — 아랫변은 미드솔이 덮는다
                upper(s).fill(BirdPalette.cream)
                upper(s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 7 * s, lineCap: .round, lineJoin: .round))

                // 힐 카운터 패치
                heelPatch(s).fill(BirdPalette.brand)
                heelPatch(s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineJoin: .round))

                // 끈 3줄
                laces(s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 5.5 * s, lineCap: .round))

                // 미드솔(브랜드) — 바닥이 살짝 볼록한 로커형
                midsole(s).fill(BirdPalette.brand)
                midsole(s).stroke(BirdPalette.outline, style: StrokeStyle(lineWidth: 7 * s, lineCap: .round, lineJoin: .round))

                // 바닥선
                Path { p in
                    p.move(to: CGPoint(x: 64 * s, y: 192 * s))
                    p.addCurve(to: CGPoint(x: 176 * s, y: 192 * s),
                               control1: CGPoint(x: 100 * s, y: 198 * s),
                               control2: CGPoint(x: 140 * s, y: 198 * s))
                }
                .stroke(BirdPalette.hint, style: StrokeStyle(lineWidth: 5 * s, lineCap: .round))
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    /// `M40 84C36 72 40 64 50 64c6 0 8 6 6 14z`
    private func pullTab(_ s: CGFloat) -> Path {
        Path { p in
            p.move(to: CGPoint(x: 40 * s, y: 84 * s))
            p.addCurve(to: CGPoint(x: 50 * s, y: 64 * s),
                       control1: CGPoint(x: 36 * s, y: 72 * s), control2: CGPoint(x: 40 * s, y: 64 * s))
            p.addCurve(to: CGPoint(x: 56 * s, y: 78 * s),
                       control1: CGPoint(x: 56 * s, y: 64 * s), control2: CGPoint(x: 58 * s, y: 70 * s))
            p.closeSubpath()
        }
    }

    /// 뒤꿈치 → 발목 칼라 → 텅 → 발등 → 둥근 앞코 → 아랫변
    private func upper(_ s: CGFloat) -> Path {
        Path { p in
            p.move(to: CGPoint(x: 36 * s, y: 140 * s))
            p.addCurve(to: CGPoint(x: 44 * s, y: 80 * s),
                       control1: CGPoint(x: 32 * s, y: 118 * s), control2: CGPoint(x: 34 * s, y: 94 * s))
            p.addCurve(to: CGPoint(x: 92 * s, y: 88 * s),
                       control1: CGPoint(x: 60 * s, y: 88 * s), control2: CGPoint(x: 76 * s, y: 92 * s))
            p.addLine(to: CGPoint(x: 104 * s, y: 76 * s))
            p.addCurve(to: CGPoint(x: 114 * s, y: 80 * s),
                       control1: CGPoint(x: 108 * s, y: 74 * s), control2: CGPoint(x: 112 * s, y: 76 * s))
            p.addCurve(to: CGPoint(x: 186 * s, y: 116 * s),
                       control1: CGPoint(x: 130 * s, y: 100 * s), control2: CGPoint(x: 160 * s, y: 110 * s))
            p.addCurve(to: CGPoint(x: 208 * s, y: 140 * s),
                       control1: CGPoint(x: 202 * s, y: 120 * s), control2: CGPoint(x: 210 * s, y: 128 * s))
            p.closeSubpath()
        }
    }

    /// `M42 138C40 120 44 106 56 102c14-4 26 8 28 22 1 9-4 14-12 14z`
    private func heelPatch(_ s: CGFloat) -> Path {
        Path { p in
            p.move(to: CGPoint(x: 42 * s, y: 138 * s))
            p.addCurve(to: CGPoint(x: 56 * s, y: 102 * s),
                       control1: CGPoint(x: 40 * s, y: 120 * s), control2: CGPoint(x: 44 * s, y: 106 * s))
            p.addCurve(to: CGPoint(x: 84 * s, y: 124 * s),
                       control1: CGPoint(x: 70 * s, y: 98 * s), control2: CGPoint(x: 82 * s, y: 110 * s))
            p.addCurve(to: CGPoint(x: 72 * s, y: 138 * s),
                       control1: CGPoint(x: 85 * s, y: 133 * s), control2: CGPoint(x: 80 * s, y: 138 * s))
            p.closeSubpath()
        }
    }

    /// `M116 116l10-10m8 15l10-9m8 14l10-7`
    private func laces(_ s: CGFloat) -> Path {
        Path { p in
            let strokes: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [(116, 116, 126, 106), (134, 121, 144, 112), (152, 126, 162, 119)]
            for (x0, y0, x1, y1) in strokes {
                p.move(to: CGPoint(x: x0 * s, y: y0 * s))
                p.addLine(to: CGPoint(x: x1 * s, y: y1 * s))
            }
        }
    }

    /// `M34 136H200c12 0 16 6 14 14-4 12-18 16-34 16-40 5-80 5-120 0-16 0-26-8-28-18-1-6-1-12 2-12z`
    private func midsole(_ s: CGFloat) -> Path {
        Path { p in
            p.move(to: CGPoint(x: 34 * s, y: 136 * s))
            p.addLine(to: CGPoint(x: 200 * s, y: 136 * s))
            p.addCurve(to: CGPoint(x: 214 * s, y: 150 * s),
                       control1: CGPoint(x: 212 * s, y: 136 * s), control2: CGPoint(x: 216 * s, y: 142 * s))
            p.addCurve(to: CGPoint(x: 180 * s, y: 166 * s),
                       control1: CGPoint(x: 210 * s, y: 162 * s), control2: CGPoint(x: 196 * s, y: 166 * s))
            p.addCurve(to: CGPoint(x: 60 * s, y: 166 * s),
                       control1: CGPoint(x: 140 * s, y: 171 * s), control2: CGPoint(x: 100 * s, y: 171 * s))
            p.addCurve(to: CGPoint(x: 32 * s, y: 148 * s),
                       control1: CGPoint(x: 44 * s, y: 166 * s), control2: CGPoint(x: 34 * s, y: 158 * s))
            p.addCurve(to: CGPoint(x: 34 * s, y: 136 * s),
                       control1: CGPoint(x: 31 * s, y: 142 * s), control2: CGPoint(x: 31 * s, y: 136 * s))
            p.closeSubpath()
        }
    }
}
