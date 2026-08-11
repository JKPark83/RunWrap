import SwiftUI

// 시안의 SVG 차트들을 SwiftUI Path로 옮긴 경량 차트 모음.
// 데이터 축·색은 전부 호출부(카드)가 결정한다.

/// 주간 막대 차트 — 마지막(현재) 주 강조 + 선택적 상한 점선
struct WeeklyBarsChart: View {
    let weeks: [WeeklyReport.WeekBar]
    var currentColor: Color = RR.brand
    var cap: Double? = nil
    var capLabel: String? = nil
    var chartHeight: CGFloat = 76

    private var scaleMax: Double {
        let peak = max(weeks.map(\.km).max() ?? 1, cap ?? 0)
        return peak > 0 ? peak * 1.08 : 1
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topLeading) {
                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(weeks) { week in
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(week.isCurrent ? currentColor : RR.barFill)
                            .frame(height: barHeight(week.km))
                            .frame(maxWidth: .infinity, alignment: .bottom)
                    }
                }
                .frame(height: chartHeight, alignment: .bottom)

                if let cap, cap <= scaleMax {
                    let y = chartHeight * (1 - cap / scaleMax)
                    Line()
                        .stroke(RR.dang.opacity(0.65),
                                style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .frame(height: 1)
                        .offset(y: y)
                    if let capLabel {
                        Text(capLabel)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(RR.dang)
                            .offset(x: 2, y: max(0, y - 15))
                    }
                }
            }
            HStack(spacing: 10) {
                ForEach(weeks) { week in
                    Text(week.label)
                        .font(.system(size: 9.5, weight: week.isCurrent ? .bold : .regular,
                                      design: .monospaced))
                        .foregroundStyle(week.isCurrent ? currentColor : RR.text3)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.top, 8)
        }
    }

    private func barHeight(_ km: Double) -> CGFloat {
        let minimal: CGFloat = 4  // 0이어도 흔적은 보이게
        return max(minimal, chartHeight * CGFloat(km / scaleMax))
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: 0, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.width, y: rect.midY))
            return p
        }
    }
}

/// ACWR 반원 게이지 — 0.5~2.0, 안전(0.8~1.3)/주의/위험 구간 표시
struct AcwrGauge: View {
    let ratio: Double

    // 시안 좌표계 320×152 기준: 중심 (160,124), 반지름 100
    private let designSize = CGSize(width: 320, height: 152)

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / designSize.width
            let center = CGPoint(x: 160 * s, y: 124 * s)
            let radius = 100 * s

            ZStack {
                segment(0.5...0.8, color: RR.barFill, center: center, radius: radius, scale: s)
                segment(0.8...1.3, color: RR.pos, center: center, radius: radius, scale: s)
                segment(1.3...1.5, color: RR.warn, center: center, radius: radius, scale: s)
                segment(1.5...2.0, color: RR.dang, center: center, radius: radius, scale: s)

                needle(center: center, radius: radius, scale: s)

                Text(String(format: "%.2f", ratio))
                    .font(.system(size: 36 * s, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                    .position(x: center.x, y: center.y - 24 * s)

                Text("0.5")
                    .font(.system(size: 10 * s, design: .monospaced))
                    .foregroundStyle(RR.text3)
                    .position(x: 60 * s, y: 138 * s)
                Text("0.8–1.3 안전")
                    .font(.system(size: 10 * s, design: .monospaced))
                    .foregroundStyle(RR.pos)
                    .position(x: 138 * s, y: 54 * s)  // 초록 호와 겹치지 않는 오목면 안쪽
                Text("2.0")
                    .font(.system(size: 10 * s, design: .monospaced))
                    .foregroundStyle(RR.text3)
                    .position(x: 260 * s, y: 138 * s)
            }
        }
        .aspectRatio(designSize.width / designSize.height, contentMode: .fit)
    }

    /// 값 → 각도: 0.5가 왼쪽(180°), 2.0이 오른쪽(360°)
    private func angle(_ value: Double) -> Angle {
        .degrees(180 + (min(max(value, 0.5), 2.0) - 0.5) / 1.5 * 180)
    }

    private func segment(_ range: ClosedRange<Double>, color: some ShapeStyle,
                         center: CGPoint, radius: CGFloat, scale: CGFloat) -> some View {
        Path { p in
            p.addArc(center: center, radius: radius,
                     startAngle: angle(range.lowerBound), endAngle: angle(range.upperBound),
                     clockwise: false)
        }
        .stroke(color, style: StrokeStyle(lineWidth: 15 * scale, lineCap: .butt))
    }

    private func needle(center: CGPoint, radius: CGFloat, scale: CGFloat) -> some View {
        let a = angle(ratio).radians
        let tip = CGPoint(x: center.x + cos(a) * radius * 0.88,
                          y: center.y + sin(a) * radius * 0.88)
        return ZStack {
            Path { p in
                p.move(to: center)
                p.addLine(to: tip)
            }
            .stroke(RR.text, style: StrokeStyle(lineWidth: 3.4 * scale, lineCap: .round))
            Circle().fill(RR.text).frame(width: 14 * scale, height: 14 * scale)
                .position(center)
            Circle().fill(RR.surface).frame(width: 5 * scale, height: 5 * scale)
                .position(center)
        }
    }
}

/// 추세 라인 차트 — 그라디언트 채움 + 끝점 도트 (EF 카드)
struct TrendLineChart: View {
    let points: [Double]
    var tint: Color = RR.pos
    var height: CGFloat = 96
    var endLabels: (String, String)? = nil  // (왼쪽, 오른쪽) 축 라벨

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                let pts = normalized(in: geo.size)
                ZStack {
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: geo.size.height))
                        p.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height))
                    }
                    .stroke(RR.line, lineWidth: 1)

                    if pts.count >= 2 {
                        Path { p in
                            p.move(to: CGPoint(x: pts[0].x, y: geo.size.height))
                            for pt in pts { p.addLine(to: pt) }
                            p.addLine(to: CGPoint(x: pts[pts.count - 1].x, y: geo.size.height))
                            p.closeSubpath()
                        }
                        .fill(LinearGradient(colors: [tint.opacity(0.28), tint.opacity(0)],
                                             startPoint: .top, endPoint: .bottom))

                        Path { p in
                            p.move(to: pts[0])
                            for pt in pts.dropFirst() { p.addLine(to: pt) }
                        }
                        .stroke(tint, style: StrokeStyle(lineWidth: 2.8, lineCap: .round,
                                                         lineJoin: .round))

                        Circle()
                            .fill(tint)
                            .stroke(RR.surface, lineWidth: 2.5)
                            .frame(width: 10, height: 10)
                            .position(pts[pts.count - 1])
                    }
                }
            }
            .frame(height: height)

            if let (left, right) = endLabels {
                HStack {
                    Text(left)
                    Spacer()
                    Text(right)
                }
                .font(.system(size: 9.5, design: .monospaced))
                .foregroundStyle(RR.text3)
            }
        }
    }

    private func normalized(in size: CGSize) -> [CGPoint] {
        guard points.count >= 2,
              let low = points.min(), let high = points.max() else { return [] }
        let span = max(high - low, 0.0001)
        let inset: CGFloat = 10
        let usableW = size.width - inset * 2
        let top = size.height * 0.18
        let bottom = size.height * 0.88
        return points.enumerated().map { i, v in
            CGPoint(x: inset + usableW * CGFloat(i) / CGFloat(points.count - 1),
                    y: bottom - (bottom - top) * CGFloat((v - low) / span))
        }
    }
}

/// 작은 스파크라인 (통계 타일)
struct SparkLine: View {
    let points: [Double]
    var tint: Color = RR.text3
    /// true면 값이 작을수록 위(페이스처럼 낮을수록 좋은 지표)
    var invert: Bool = false

    var body: some View {
        GeometryReader { geo in
            if points.count >= 2, let low = points.min(), let high = points.max() {
                let span = max(high - low, 0.0001)
                Path { p in
                    for (i, v) in points.enumerated() {
                        var t = CGFloat((v - low) / span)
                        if !invert { t = 1 - t }
                        let pt = CGPoint(x: geo.size.width * CGFloat(i) / CGFloat(points.count - 1),
                                         y: geo.size.height * (0.12 + 0.76 * t))
                        i == 0 ? p.move(to: pt) : p.addLine(to: pt)
                    }
                }
                .stroke(tint, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(height: 26)
    }
}

/// 구간별(km) 페이스 막대 — 평균 대비 느린 구간은 경고색
struct SplitBarsChart: View {
    let splits: [WorkoutDetail.Split]
    var slowThresholdSec: Double = 8
    var height: CGFloat = 64

    private var avgPace: Double {
        splits.map(\.paceSecPerKm).reduce(0, +) / Double(max(splits.count, 1))
    }

    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                let speeds = splits.map { 1 / $0.paceSecPerKm }
                let maxSpeed = speeds.max() ?? 1
                let minSpeed = speeds.min() ?? 1
                let span = max(maxSpeed - minSpeed, 0.0001)
                let barW = max(3, (geo.size.width - CGFloat(splits.count - 1) * 5) / CGFloat(splits.count))
                HStack(alignment: .bottom, spacing: 5) {
                    ForEach(Array(splits.enumerated()), id: \.offset) { _, split in
                        let t = CGFloat((1 / split.paceSecPerKm - minSpeed) / span)
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(split.paceSecPerKm > avgPace + slowThresholdSec
                                  ? RR.warn : RR.brand.opacity(0.8))
                            .frame(width: barW, height: geo.size.height * (0.45 + 0.55 * t))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            .frame(height: height)

            HStack {
                Text("1")
                Spacer()
                if splits.count > 4 { Text("\((splits.count + 1) / 2)"); Spacer() }
                Text("\(splits.count)")
            }
            .font(.system(size: 9, design: .monospaced))
            .foregroundStyle(RR.text3)
        }
    }
}

/// 심박 존 분포 바 + 존별 퍼센트
struct ZoneBarView: View {
    /// Z1~Z5 비율 (합 1.0)
    let fractions: [Double]

    private let colors: [Color] = [RR.barFill, RR.pos.opacity(0.55), RR.pos, RR.warn, RR.dang]

    var body: some View {
        VStack(spacing: 12) {
            GeometryReader { geo in
                let gaps = CGFloat(fractions.count - 1) * 2
                HStack(spacing: 2) {
                    ForEach(Array(fractions.enumerated()), id: \.offset) { i, f in
                        Rectangle()
                            .fill(colors[min(i, colors.count - 1)])
                            .frame(width: max(0, (geo.size.width - gaps) * CGFloat(f)))
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .frame(height: 16)

            HStack(spacing: 6) {
                ForEach(Array(fractions.enumerated()), id: \.offset) { i, f in
                    VStack(spacing: 4) {
                        Text("Z\(i + 1)")
                            .font(.system(size: 10))
                            .foregroundStyle(RR.text3)
                        Text("\(Int((f * 100).rounded()))%")
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundStyle(f == fractions.max() ? RR.text : RR.text2)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

// MARK: - 체력 배터리 게이지

/// 가로 배터리 모양 잔량 게이지 — 몸통(잔량 채움) + 오른쪽 단자 + 25/50/75 눈금
struct BatteryGauge: View {
    let level: Int          // 0–100
    let tint: Color

    var body: some View {
        HStack(spacing: 3) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(RR.barFill.opacity(0.55))
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(tint)
                        .frame(width: max(10, (geo.size.width - 8) * CGFloat(level) / 100))
                        .padding(4)
                    ForEach([0.25, 0.5, 0.75], id: \.self) { tick in
                        Rectangle()
                            .fill(RR.bg.opacity(0.5))
                            .frame(width: 1.5)
                            .padding(.vertical, 4)
                            .offset(x: geo.size.width * tick)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(RR.line))
            }
            RoundedRectangle(cornerRadius: 2.5, style: .continuous)
                .fill(RR.line)
                .frame(width: 5, height: 18)
        }
        .frame(height: 44)
    }
}
