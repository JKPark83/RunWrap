import SwiftUI
import WidgetKit

/// "오늘 컨디션" 위젯 뷰 (이슈 #181) — 패밀리 다섯 개를 한 뷰에서 가른다.
/// 값은 스냅샷에 있는 것만 그린다: 재료가 nil이면 "—"나 안내 문구로 두고 지어내지 않는다.
/// 색은 RR 토큰만 쓴다. 잠금화면(accessory) 패밀리는 시스템이 단색으로 칠하므로 색을 주지 않는다.
struct StatusWidgetView: View {
    let entry: StatusEntry
    @Environment(\.widgetFamily) private var family

    /// 24시간 넘은 스냅샷의 수치·문장 흐림 정도
    private static let staleOpacity = 0.35
    private static let staleMessage = "앱을 열어 갱신해 주세요"
    private static let emptyMessage = "런미새를 열어 첫 리포트를 만들어요"

    var body: some View {
        switch family {
        case .accessoryCircular:
            circular.containerBackground(.clear, for: .widget)
        case .accessoryRectangular:
            rectangular.containerBackground(.clear, for: .widget)
        case .accessoryInline:
            inline.containerBackground(.clear, for: .widget)
        case .systemMedium:
            home { medium($0) }.containerBackground(RR.surface, for: .widget)
        default:
            home { small($0) }.containerBackground(RR.surface, for: .widget)
        }
    }

    // MARK: - 홈 화면

    /// 스냅샷이 없으면 홈 두 패밀리 공통 빈 상태
    @ViewBuilder
    private func home<Content: View>(@ViewBuilder _ content: (WidgetSnapshot) -> Content) -> some View {
        if let snapshot = entry.snapshot {
            content(snapshot)
        } else {
            Text("런미새를 열어\n첫 리포트를 만들어요")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(RR.text2)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func small(_ snapshot: WidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            batteryBlock(snapshot)
                .opacity(entry.isStale ? Self.staleOpacity : 1)
            Spacer(minLength: 4)
            if entry.isStale {
                staleNotice
            } else {
                Text(snapshot.headline)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func medium(_ snapshot: WidgetSnapshot) -> some View {
        GeometryReader { proxy in
            HStack(spacing: 0) {
                batteryBlock(snapshot)
                    .opacity(entry.isStale ? Self.staleOpacity : 1)
                    .frame(width: proxy.size.width * 0.4, alignment: .topLeading)
                    .frame(maxHeight: .infinity, alignment: .topLeading)
                Rectangle()
                    .fill(RR.line)
                    .frame(width: 1)
                    .padding(.trailing, 14)
                VStack(alignment: .leading, spacing: 4) {
                    VStack(alignment: .leading, spacing: 4) {
                        label("오늘 판정")
                        Text(snapshot.headline)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(RR.text)
                            .lineLimit(3)
                            .minimumScaleFactor(0.8)
                    }
                    .opacity(entry.isStale ? Self.staleOpacity : 1)
                    Spacer(minLength: 4)
                    if entry.isStale {
                        staleNotice
                    } else {
                        label("이번 주")
                        Text(weekPhrase(snapshot))
                            .font(.system(size: 15, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(RR.text)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
    }

    /// 체력 배터리 블록 — small 전체, medium 왼쪽. 배터리가 없으면 숫자 대신 "—"
    private func batteryBlock(_ snapshot: WidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            label("체력 배터리")
            if let level = snapshot.batteryLevel, let tone = snapshot.batteryTone {
                HStack(alignment: .firstTextBaseline, spacing: 2) {
                    Text("\(level)")
                        .font(RR.numeral(44))
                        .foregroundStyle(tone.color)
                    Text("%")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text2)
                }
                if let statusLabel = snapshot.batteryLabel {
                    Text(statusLabel)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(tone.color)
                }
            } else {
                Text("—")
                    .font(RR.numeral(44))
                    .foregroundStyle(RR.text3)
                Text("회복 신호 없음")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(RR.text3)
    }

    private var staleNotice: some View {
        Text(Self.staleMessage)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(RR.brand)
            .lineLimit(2)
    }

    /// "12.4 km · 3회" — 런린이는 거리 수치 없이 횟수만 (ReportGate.showsNumbers(.distance))
    private func weekPhrase(_ snapshot: WidgetSnapshot) -> String {
        guard snapshot.runCount > 0 else { return "아직 안 뛰었어요" }
        return snapshot.showsDistanceNumbers
            ? "\(Format.km(snapshot.weekKm)) km · \(snapshot.runCount)회"
            : "\(snapshot.runCount)번 뛰었어요"
    }

    // MARK: - 잠금 화면

    @ViewBuilder
    private var circular: some View {
        if let level = entry.snapshot?.batteryLevel {
            Gauge(value: Double(level) / 100) {
                EmptyView()
            } currentValueLabel: {
                Text("\(level)").font(.system(size: 18, weight: .bold))
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .widgetAccentable()
            .opacity(entry.isStale ? Self.staleOpacity : 1)
        } else {
            Image(systemName: "figure.run")
                .font(.system(size: 22, weight: .semibold))
                .widgetAccentable()
        }
    }

    @ViewBuilder
    private var rectangular: some View {
        if let snapshot = entry.snapshot {
            VStack(alignment: .leading, spacing: 2) {
                Text(snapshot.headline)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(2)
                    .widgetAccentable()
                Text(entry.isStale ? Self.staleMessage : rectangularDetail(snapshot))
                    .font(.system(size: 12))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text(Self.emptyMessage)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// "배터리 72% · 이번 주 12.4 km · 3회" — 배터리가 없으면 앞 조각을 뺀다
    private func rectangularDetail(_ snapshot: WidgetSnapshot) -> String {
        let week: String
        if snapshot.runCount == 0 {
            week = "이번 주 아직 안 뛰었어요"
        } else if snapshot.showsDistanceNumbers {
            week = "이번 주 \(Format.km(snapshot.weekKm)) km · \(snapshot.runCount)회"
        } else {
            week = "이번 주 \(snapshot.runCount)회"
        }
        return [snapshot.batteryLevel.map { "배터리 \($0)%" }, week]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    @ViewBuilder
    private var inline: some View {
        if let snapshot = entry.snapshot {
            if entry.isStale {
                Label(Self.staleMessage, systemImage: "arrow.clockwise")
            } else {
                Label(snapshot.headline, systemImage: batterySymbol(snapshot.batteryLevel))
            }
        } else {
            Text(Self.emptyMessage)
        }
    }

    /// 배터리 수준에 가장 가까운 SF 심볼 (0·25·50·75·100) — 배터리가 없으면 러너 아이콘
    private func batterySymbol(_ level: Int?) -> String {
        guard let level else { return "figure.run" }
        let step = Int((Double(level) / 25).rounded()) * 25
        return "battery.\(min(max(step, 0), 100))"
    }
}
