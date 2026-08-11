import SwiftUI

// 시안의 SVG 차트들을 SwiftUI Path로 옮긴 경량 차트 모음.
// 데이터 축·색은 전부 호출부(카드)가 결정한다.

/// 주간 막대 차트 — 마지막(현재) 주 강조 + 선택적 상한 점선.
/// 주가 많아 폭이 모자라면 슬롯 폭을 유지한 채 가로 스크롤로 넘긴다 (최신 주가
/// 오른쪽 끝, 과거는 왼쪽으로 스크롤). 각 막대 위에 값을 작게 상시 표시하고
/// (작업 지침 차트 규칙), 막대를 탭하면 주·수치 콜아웃을 띄운다.
struct WeeklyBarsChart: View {
    let weeks: [WeeklyReport.WeekBar]
    var currentColor: Color = RR.brand
    var cap: Double? = nil
    var capLabel: String? = nil
    var chartHeight: CGFloat = 76
    /// 탭 콜아웃 수치 표기 — 다이어트 카드는 kcal을 주입한다
    var valueText: (Double) -> String = { Format.km($0) + " km" }
    /// 막대 위 상시 표시용 짧은 수치 — 단위 없이 숫자만 (슬롯 폭이 좁다)
    var barValueText: (Double) -> String = { Format.km($0) }

    @State private var selected: Int? = nil   // WeekBar.index

    /// 스크롤 모드에서 주 하나가 차지하는 최소 폭 — "12월 4째주" 라벨이 들어가는 폭
    private let minSlot: CGFloat = 56
    private let labelHeight: CGFloat = 22
    /// 막대 위 값 텍스트가 차지하는 높이 — 막대·상한선 스케일은 이만큼 뺀 높이를 쓴다
    private let valueReserve: CGFloat = 13

    private var scaleMax: Double {
        let peak = max(weeks.map(\.km).max() ?? 1, cap ?? 0)
        return peak > 0 ? peak * 1.08 : 1
    }

    var body: some View {
        GeometryReader { geo in
            let count = max(weeks.count, 1)
            let scrollable = CGFloat(count) * minSlot > geo.size.width
            let slot = scrollable ? minSlot : geo.size.width / CGFloat(count)

            ScrollView(.horizontal) {
                content(slot: slot, width: slot * CGFloat(count))
            }
            .scrollDisabled(!scrollable)
            .scrollIndicators(.hidden)
            .defaultScrollAnchor(.trailing)   // 최신 주부터 보인다
            // 잘린 게 아니라 과거로 이어진다는 신호 — 스크롤 가능할 때만
            .overlay(alignment: .leading) {
                if scrollable {
                    LinearGradient(colors: [RR.surface, RR.surface.opacity(0)],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: 22)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: chartHeight + labelHeight)
    }

    private func content(slot: CGFloat, width: CGFloat) -> some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topLeading) {
                HStack(alignment: .bottom, spacing: 0) {
                    ForEach(weeks) { week in
                        VStack(spacing: 3) {
                            if week.km > 0 {
                                Text(barValueText(week.km))
                                    .font(.system(size: 8.5,
                                                  weight: week.isCurrent ? .semibold : .regular,
                                                  design: .monospaced))
                                    .foregroundStyle(week.isCurrent ? currentColor : RR.text3)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.7)
                            }
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .fill(week.isCurrent ? currentColor : RR.barFill)
                                .frame(width: max(6, slot - 10), height: barHeight(week.km))
                        }
                        .opacity(selected == nil || selected == week.index ? 1 : 0.45)
                        .frame(width: slot, height: chartHeight, alignment: .bottom)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selected = selected == week.index ? nil : week.index
                        }
                    }
                }

                if let cap, cap <= scaleMax {
                    let y = chartHeight - (chartHeight - valueReserve) * cap / scaleMax
                    Line()
                        .stroke(RR.dang.opacity(0.65),
                                style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .frame(width: width, height: 1)
                        .offset(y: y)
                    if let capLabel {
                        Text(capLabel)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(RR.dang)
                            .offset(x: 2, y: max(0, y - 15))
                    }
                }

                if let selected, let week = weeks.first(where: { $0.index == selected }) {
                    ChartCallout(text: "\(week.label) · \(valueText(week.km))")
                        .position(x: calloutX(slot: slot, width: width, index: selected), y: 13)
                }
            }
            .frame(height: chartHeight)

            HStack(spacing: 0) {
                ForEach(weeks) { week in
                    Text(week.label)
                        .font(.system(size: 9.5, weight: week.isCurrent ? .bold : .regular,
                                      design: .monospaced))
                        .foregroundStyle(week.isCurrent ? currentColor : RR.text3)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(width: slot)
                }
            }
            .padding(.top, 8)
        }
        .frame(width: width)
    }

    /// 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
    private func calloutX(slot: CGFloat, width: CGFloat, index: Int) -> CGFloat {
        let margin: CGFloat = 62
        let x = slot * (CGFloat(index) + 0.5)
        return min(max(x, margin), max(width - margin, margin))
    }

    private func barHeight(_ km: Double) -> CGFloat {
        let minimal: CGFloat = 4  // 0이어도 흔적은 보이게
        return max(minimal, (chartHeight - valueReserve) * CGFloat(km / scaleMax))
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

/// 차트 탭 콜아웃 — 선택한 지점의 시기·수치를 담는 작은 말풍선
struct ChartCallout: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .foregroundStyle(RR.text)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(RR.surface2, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(RR.line))
            .fixedSize()
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

                marker(center: center, radius: radius, scale: s)

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

    /// 값 위치 마커 — 호를 가로지르는 짧은 눈금. 예전의 중심축 바늘은 가운데
    /// 큰 숫자를 관통해 겹쳐 보였다 — 바늘 대신 호 위 마커로 위치만 표시한다.
    private func marker(center: CGPoint, radius: CGFloat, scale: CGFloat) -> some View {
        let a = angle(ratio).radians
        let inner = CGPoint(x: center.x + cos(a) * (radius - 13 * scale),
                            y: center.y + sin(a) * (radius - 13 * scale))
        let outer = CGPoint(x: center.x + cos(a) * (radius + 13 * scale),
                            y: center.y + sin(a) * (radius + 13 * scale))
        return Path { p in
            p.move(to: inner)
            p.addLine(to: outer)
        }
        .stroke(RR.text, style: StrokeStyle(lineWidth: 4 * scale, lineCap: .round))
    }
}

/// 추세 라인 차트 — 그라디언트 채움 + 끝점 도트 (EF 카드).
/// 차트를 탭하면 가장 가까운 점의 시기·수치를 콜아웃으로 띄운다 (다시 탭하면 닫힘).
struct TrendLineChart: View {
    let points: [Double]
    var tint: Color = RR.pos
    var height: CGFloat = 96
    var endLabels: (String, String)? = nil  // (왼쪽, 오른쪽) 축 라벨
    /// 탭 콜아웃에 함께 보여줄 시기 라벨 (points와 병행 배열)
    var pointLabels: [String]? = nil
    /// 탭 콜아웃 수치 표기 — EF·kg·페이스 등 단위는 호출부가 정한다
    var valueText: (Double) -> String = { String(format: "%.2f", $0) }

    @State private var selected: Int? = nil

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

                        if let selected, pts.indices.contains(selected) {
                            selectionMarks(at: pts[selected], index: selected, in: geo.size)
                        }
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture(coordinateSpace: .local) { location in
                    guard !pts.isEmpty,
                          let nearest = pts.indices.min(by: {
                              abs(pts[$0].x - location.x) < abs(pts[$1].x - location.x)
                          }) else { return }
                    selected = selected == nearest ? nil : nearest
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

    /// 선택 표식 — 세로 가이드선 + 링 도트 + 콜아웃
    @ViewBuilder
    private func selectionMarks(at pt: CGPoint, index: Int, in size: CGSize) -> some View {
        Path { p in
            p.move(to: CGPoint(x: pt.x, y: size.height))
            p.addLine(to: CGPoint(x: pt.x, y: pt.y))
        }
        .stroke(RR.line, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))

        Circle()
            .fill(tint)
            .stroke(RR.surface, lineWidth: 2.5)
            .frame(width: 10, height: 10)
            .position(pt)

        let label = pointLabels.flatMap { $0.indices.contains(index) ? $0[index] : nil }
        ChartCallout(text: label.map { "\($0) · \(valueText(points[index]))" }
                        ?? valueText(points[index]))
            .position(x: min(max(pt.x, 56), max(size.width - 56, 56)),
                      y: max(pt.y - 24, 12))
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

/// 구간별(km) 페이스 막대 — 평균 대비 느린 구간은 경고색.
/// 구간이 많아 폭이 모자라면 막대 폭을 유지한 채 가로 스크롤로 넘긴다
/// (예전에는 폭에 억지로 밀어넣어 롱런 후반 구간이 카드 밖으로 잘렸다).
/// 막대가 좁아 값 상시 표시가 불가능한 차트 — 탭 콜아웃으로 대신한다 (작업 지침 차트 규칙).
struct SplitBarsChart: View {
    let splits: [WorkoutDetail.Split]
    var slowThresholdSec: Double = 8
    var height: CGFloat = 64

    @State private var selected: Int? = nil   // Split.index

    /// 스크롤 모드에서 막대 하나가 차지하는 최소 폭 (막대 + 간격)
    private let slot: CGFloat = 10
    private let gap: CGFloat = 3
    private let axisHeight: CGFloat = 13

    private var avgPace: Double {
        splits.map(\.paceSecPerKm).reduce(0, +) / Double(max(splits.count, 1))
    }

    var body: some View {
        GeometryReader { geo in
            let needed = CGFloat(splits.count) * slot
            let width = max(geo.size.width, needed)
            let scrollable = needed > geo.size.width

            ScrollView(.horizontal) {
                VStack(spacing: 4) {
                    bars(width: width)
                    axis(width: width)
                }
                .frame(width: width)
            }
            .scrollDisabled(!scrollable)
            // 잘린 게 아니라 이어진다는 신호 — 스크롤 가능할 때만
            .overlay(alignment: .trailing) {
                if scrollable {
                    LinearGradient(colors: [RR.surface.opacity(0), RR.surface],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: 22)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: height + axisHeight + 4)
    }

    private func barWidth(in width: CGFloat) -> CGFloat {
        max(3, (width - CGFloat(splits.count - 1) * gap) / CGFloat(splits.count))
    }

    private func bars(width: CGFloat) -> some View {
        let speeds = splits.map { 1 / $0.paceSecPerKm }
        let minSpeed = speeds.min() ?? 1
        let span = max((speeds.max() ?? 1) - minSpeed, 0.0001)
        let barW = barWidth(in: width)

        return ZStack(alignment: .topLeading) {
            HStack(alignment: .bottom, spacing: gap) {
                ForEach(splits) { split in
                    let t = CGFloat((1 / split.paceSecPerKm - minSpeed) / span)
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(split.paceSecPerKm > avgPace + slowThresholdSec
                              ? RR.warn : RR.brand.opacity(0.8))
                        .opacity(selected == nil || selected == split.index ? 1 : 0.55)
                        .frame(width: barW, height: height * (0.45 + 0.55 * t))
                        .frame(height: height, alignment: .bottom)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selected = selected == split.index ? nil : split.index
                        }
                }
            }

            if let selected,
               let offset = splits.firstIndex(where: { $0.index == selected }) {
                ChartCallout(text: "\(splits[offset].index)km · "
                             + Format.pace(splits[offset].paceSecPerKm))
                    .position(x: calloutX(barW: barW, width: width, offset: offset), y: 13)
            }
        }
        .frame(height: height, alignment: .bottom)
    }

    /// 콜아웃이 차트 밖으로 잘리지 않게 중심 x를 안쪽으로 조인다
    private func calloutX(barW: CGFloat, width: CGFloat, offset: Int) -> CGFloat {
        let margin: CGFloat = 56
        let x = CGFloat(offset) * (barW + gap) + barW / 2
        return min(max(x, margin), max(width - margin, margin))
    }

    /// km 눈금 — 막대와 같은 슬롯에 얹어 스크롤해도 어긋나지 않는다
    private func axis(width: CGFloat) -> some View {
        let barW = barWidth(in: width)
        let step = labelStep(slotWidth: barW + gap)

        return HStack(spacing: gap) {
            ForEach(splits) { split in
                Color.clear
                    .frame(width: barW, height: axisHeight)
                    .overlay {
                        if split.index == 1 || split.index % step == 0 {
                            Text("\(split.index)")
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundStyle(RR.text3)
                                .fixedSize()
                        }
                    }
            }
        }
    }

    /// 라벨이 서로 붙지 않는 최소 간격 (30pt 확보)
    private func labelStep(slotWidth: CGFloat) -> Int {
        [1, 2, 5, 10, 20, 50].first { CGFloat($0) * slotWidth >= 30 } ?? 100
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
