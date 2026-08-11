import SwiftUI

/// 통계 — 월간 집계 + 러닝 기록 목록 (시안 "통계 · 월간/기록")
struct StatsScreen: View {
    @EnvironmentObject private var health: HealthStore
    @State private var monthIndex = 0   // availableMonths 기준 (0 = 이번 달)

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                let months = MonthlyStats.availableMonths(in: runs)
                let index = min(monthIndex, months.count - 1)
                let stats = MonthlyStats.compute(runs: runs, month: months[index])

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 7) {
                            Eyebrow(text: "Monthly & history")
                            Text("통계")
                                .font(.system(size: 31, weight: .bold))
                                .foregroundStyle(RR.text)
                        }
                        .padding(.bottom, 6)

                        monthSelector(months: months, index: index)
                        distanceCard(stats)
                        tileGrid(stats)
                        sessionList(stats)
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
                    .padding(.bottom, 26)
                }
                .refreshable { await health.load() }
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    // MARK: 월 선택

    private func monthSelector(months: [Date], index: Int) -> some View {
        HStack {
            Button {
                monthIndex = min(index + 1, months.count - 1)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 15, weight: .semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
            }
            .disabled(index >= months.count - 1)

            Spacer()
            Text(monthLabel(months[index]))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
                .monospacedDigit()
            Spacer()

            Button {
                monthIndex = max(index - 1, 0)
            } label: {
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
            }
            .disabled(index <= 0)
        }
        .padding(8)
        .background(RR.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(RR.line))
    }

    private func monthLabel(_ month: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "yyyy년 M월"
        return formatter.string(from: month)
    }

    // MARK: 누적 거리

    private func distanceCard(_ stats: MonthlyStats) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("누적 거리")
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text3)
                    (Text(Format.km(stats.totalKm))
                        .font(.system(size: 40, weight: .bold, design: .monospaced))
                        .foregroundStyle(RR.text)
                        + Text(" km")
                        .font(.system(size: 15))
                        .foregroundStyle(RR.text3))
                }
                Spacer()
                if let delta = stats.deltaPct {
                    VStack(alignment: .trailing, spacing: 5) {
                        deltaBadge(delta)
                        Text(stats.deltaCaption)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(RR.text3)
                    }
                }
            }

            WeeklyBarsChart(weeks: stats.weeks, chartHeight: 54)
                .padding(.top, 16)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    private func deltaBadge(_ pct: Double) -> some View {
        let up = pct >= 0
        return HStack(spacing: 5) {
            Text(up ? "▲" : "▼").font(.system(size: 10))
            Text(String(format: "%.1f%%", abs(pct)))
                .font(.system(size: 12, weight: .bold, design: .monospaced))
        }
        .foregroundStyle(up ? RR.pos : RR.text2)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(up ? RR.posSoft : RR.barFill,
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    // MARK: 2×2 지표 타일

    private func tileGrid(_ stats: MonthlyStats) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible())],
                  spacing: 10) {
            tile(label: "평균 페이스",
                 value: stats.avgPaceSec.map(Format.pace) ?? "—",
                 unit: nil,
                 delta: stats.paceDeltaSec.map { delta in
                     let sec = Int(abs(delta).rounded())
                     return (String(format: "%@%d″ /km", delta <= 0 ? "−" : "+", sec),
                             delta <= 0 ? RR.pos : RR.warn)
                 },
                 spark: stats.pacePoints.count >= 2
                     ? (stats.pacePoints, RR.pos, true) : nil)

            tile(label: "평균 심박",
                 value: stats.avgHeartRate.map { "\(Int($0.rounded()))" } ?? "—",
                 unit: stats.avgHeartRate != nil ? "bpm" : nil,
                 delta: stats.heartRateDelta.map { delta in
                     (String(format: "%@%d bpm", delta <= 0 ? "−" : "+", Int(abs(delta).rounded())),
                      RR.text3)
                 },
                 spark: stats.heartRatePoints.count >= 2
                     ? (stats.heartRatePoints, RR.text3, false) : nil)

            tile(label: "러닝 횟수",
                 value: "\(stats.count)",
                 unit: "회",
                 delta: (String(format: "주 %.1f회", stats.perWeek), RR.text3),
                 spark: nil)

            tile(label: "누적 시간",
                 value: Format.duration(stats.totalDurationSec),
                 unit: nil,
                 delta: nil,
                 spark: nil)
        }
    }

    private func tile(label: String, value: String, unit: String?,
                      delta: (String, Color)?,
                      spark: ([Double], Color, Bool)?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label)
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
            (Text(value).font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                + Text(unit.map { " \($0)" } ?? "")
                .font(.system(size: 12))
                .foregroundStyle(RR.text3))
                .padding(.top, 7)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let (text, color) = delta {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(color)
                    .padding(.top, 3)
            }
            if let (points, tint, invert) = spark {
                SparkLine(points: points, tint: tint, invert: invert)
                    .padding(.top, 10)
            } else {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 108, alignment: .topLeading)
        .padding(15)
        .rrCard(radius: 20)
    }

    // MARK: 세션 목록

    private func sessionList(_ stats: MonthlyStats) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("러닝 기록")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("\(stats.count) sessions")
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }
            .padding(.horizontal, 4)
            .padding(.top, 10)

            if stats.runs.isEmpty {
                Text("이 달에는 기록이 없어요")
                    .font(.system(size: 13.5))
                    .foregroundStyle(RR.text3)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                    .rrCard(radius: 20)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(stats.runs.enumerated()), id: \.element.id) { index, run in
                        NavigationLink {
                            SessionDetailScreen(run: run, weeklyContext: weeklyContext(for: run))
                        } label: {
                            sessionRow(run)
                        }
                        .buttonStyle(.plain)
                        if index < stats.runs.count - 1 {
                            Divider().overlay(RR.line).padding(.leading, 66)
                        }
                    }
                }
                .rrCard(radius: 20)
            }
        }
    }

    private func sessionRow(_ run: RunSummary) -> some View {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M월"
        let monthText = formatter.string(from: run.start)
        formatter.dateFormat = "dd"
        let dayText = formatter.string(from: run.start)

        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(monthText)
                    .font(.system(size: 11))
                    .foregroundStyle(RR.text3)
                Text(dayText)
                    .font(.system(size: 17, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
            }
            .frame(width: 38, alignment: .leading)

            VStack(alignment: .leading, spacing: 4) {
                Text(run.displayTitle)
                    .font(.system(size: 14.5, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .lineLimit(1)
                Text(run.metaLine)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(RR.text2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 4) {
                Text(run.distanceKm.map(Format.km) ?? "—")
                    .font(.system(size: 16, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text("km")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(RR.text3)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
    }

    /// 세션 상세의 맥락 배지용 — 이번 주 리포트가 과부하일 때만 전달
    private func weeklyContext(for run: RunSummary) -> WeeklyReport.DistanceCard? {
        guard case .loaded(let runs) = health.state,
              let card = ReportEngine().weeklyReport(from: runs).distance,
              card.tone == .overload else { return nil }
        return card
    }
}
