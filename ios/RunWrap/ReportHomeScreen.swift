import SwiftUI

/// 홈 — 주간 리포트 (시안 "홈 · 주간 리포트" / "빈 상태 · 기록 없음")
struct ReportHomeScreen: View {
    @EnvironmentObject private var health: HealthStore

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                if runs.isEmpty {
                    EmptyReportScreen()
                } else {
                    ReportHomeContent(report: ReportEngine().weeklyReport(from: runs),
                                      battery: health.vitals.flatMap {
                                          BatteryEngine.compute(vitals: $0, runs: runs)
                                      })
                        .refreshable { await health.load() }
                }
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

// MARK: - 리포트 본문

struct ReportHomeContent: View {
    let report: WeeklyReport
    /// 체력 배터리 — 활력징후 기준선이 부족하면 nil (안내 카드로 대체)
    var battery: BatteryReport? = nil
    /// 샘플 리포트 시트에서는 상세 이동 대신 배너를 단다
    var isSample = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header

                if let battery {
                    batteryCard(battery)
                } else if !isSample {
                    batteryHintCard
                }

                if let distance = report.distance { distanceCard(distance) }
                if let acwr = report.acwr { acwrCard(acwr) }
                if let efficiency = report.efficiency { efficiencyCard(efficiency) }

                if report.isEmpty { insufficientCard }

                if !isSample && !report.isEmpty {
                    NavigationLink {
                        ReportDetailScreen(report: report)
                    } label: {
                        HStack(spacing: 7) {
                            Text("리포트 자세히 보기")
                                .font(.system(size: 15.5, weight: .bold))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .bold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(RR.brand, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .padding(.top, 2)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 7) {
                Eyebrow(text: "Week \(report.weekNumber) · This week")
                Text("주간 리포트")
                    .font(.system(size: 31, weight: .bold))
                    .foregroundStyle(RR.text)
            }
            Spacer()
            Text(report.dateRange)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundStyle(RR.text2)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(RR.surface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(RR.line))
        }
        .padding(.bottom, 6)
    }

    // MARK: 체력 배터리 카드

    private func batteryCard(_ battery: BatteryReport) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Circle().fill(battery.tone.color).frame(width: 6, height: 6)
                Text("체력 배터리")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(battery.tone.color)
                Text(battery.statusLabel)
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .kerning(1)
                    .foregroundStyle(battery.tone.color.opacity(0.65))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(battery.tone.softColor,
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            Text(battery.headline)
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text("\(battery.level)")
                    .font(.system(size: 42, weight: .bold, design: .monospaced))
                    .foregroundStyle(battery.tone.color)
                Text("%")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(RR.text3)
                Spacer()
                Text("남은 체력")
                    .font(.system(size: 11))
                    .foregroundStyle(RR.text3)
            }
            .padding(.top, 8)

            BatteryGauge(level: battery.level, tint: battery.tone.color)
                .padding(.top, 6)

            Divider().overlay(RR.line).padding(.top, 14)

            VStack(spacing: 10) {
                ForEach(battery.factors, id: \.name) { factor in
                    factorRow(factor)
                }
            }
            .padding(.top, 13)

            Text("Apple Watch가 잰 지난밤 활력징후를 최근 4주의 내 기준선과 비교한 추정치예요")
                .font(.system(size: 11.5))
                .lineSpacing(3)
                .foregroundStyle(RR.text3)
                .padding(.top, 13)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    private func factorRow(_ factor: BatteryReport.Factor) -> some View {
        HStack(spacing: 10) {
            Image(systemName: factor.systemImage)
                .font(.system(size: 12))
                .foregroundStyle(RR.text3)
                .frame(width: 18)
            Text(factor.name)
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(RR.text)
            Spacer()
            Text(factor.detail)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(RR.text2)
            Text(factor.points > 0 ? "+\(factor.points)"
                 : factor.points < 0 ? "−\(-factor.points)" : "±0")
                .font(.system(size: 12.5, weight: .bold, design: .monospaced))
                .foregroundStyle(factor.points > 0 ? RR.pos
                                 : factor.points < 0 ? RR.dang : RR.text3)
                .frame(width: 34, alignment: .trailing)
        }
    }

    /// 활력징후가 아직 부족할 때 — 무엇이 쌓이면 보이는지 알려준다
    private var batteryHintCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "battery.50percent")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(RR.text3)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 5) {
                Text("체력 배터리를 준비하고 있어요")
                    .font(.system(size: 14.5, weight: .bold))
                    .foregroundStyle(RR.text)
                Text("Apple Watch를 차고 자면 심박 변이·안정 심박·수면이 쌓여요. 내 기준선(7일)이 모이면 남은 체력을 배터리로 보여드립니다.")
                    .font(.system(size: 12.5))
                    .lineSpacing(4)
                    .foregroundStyle(RR.text2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .rrCard()
    }

    // MARK: 주간 거리 카드

    private func distanceCard(_ card: WeeklyReport.DistanceCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ToneBadge(tone: card.tone)

            distanceHeadline(card)
                .font(.system(size: 23, weight: .bold))
                .lineSpacing(4)
                .padding(.top, 13)

            WeeklyBarsChart(weeks: card.weeks,
                            currentColor: card.tone == .overload ? RR.dang : RR.brand,
                            cap: card.tone == .overload ? card.capKm : nil,
                            capLabel: card.tone == .overload
                                ? String(format: "+10%% 상한 %.1f km", card.capKm) : nil)
                .padding(.top, 16)

            Divider().overlay(RR.line).padding(.top, 12)

            HStack(spacing: 8) {
                metric(label: "최근 7일", value: Format.km(card.recent7Km), unit: "km", color: RR.text)
                metric(label: "이전 7일", value: Format.km(card.previous7Km), unit: "km", color: RR.text2)
                if card.overKm > 0 {
                    metric(label: "초과분", value: "+" + Format.km(card.overKm), unit: "km", color: RR.dang)
                } else {
                    metric(label: "상한 여유", value: Format.km(-card.overKm), unit: "km", color: RR.pos)
                }
            }
            .padding(.top, 13)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    private func distanceHeadline(_ card: WeeklyReport.DistanceCard) -> Text {
        let pct = Text(String(format: "%.0f%%", abs(card.changePct)))
            .foregroundStyle(card.tone.color)
        let base: Text
        if card.changePct >= 0 {
            base = Text("주간 거리를 지난주보다 ").foregroundStyle(RR.text)
                + pct
                + Text(" 늘렸어요").foregroundStyle(RR.text)
        } else {
            base = Text("주간 거리를 지난주보다 ").foregroundStyle(RR.text)
                + pct
                + Text(" 줄였어요").foregroundStyle(RR.text)
        }
        return base
    }

    private func metric(label: String, value: String, unit: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            (Text(value).font(.system(size: 17, weight: .bold, design: .monospaced))
                + Text(" \(unit)").font(.system(size: 11)).foregroundStyle(RR.text3))
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: ACWR 카드

    private func acwrCard(_ card: WeeklyReport.AcwrCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ToneBadge(tone: card.tone)

            Text(acwrHeadline(card))
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            AcwrGauge(ratio: card.ratio)
                .padding(.top, 6)

            Text(String(format: "최근 7일 부하가 4주 평균의 %.2f배 · 1.5 초과는 위험", card.ratio))
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 2)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    private func acwrHeadline(_ card: WeeklyReport.AcwrCard) -> String {
        switch card.tone {
        case .overload: "훈련량이 회복 범위를 넘었어요"
        case .caution: card.ratio >= 1.3 ? "회복보다 훈련량이 앞서 있어요"
                                         : "훈련량이 평소보다 크게 줄었어요"
        default: "훈련과 회복이 균형을 이루고 있어요"
        }
    }

    // MARK: 심박 효율 카드

    private func efficiencyCard(_ card: WeeklyReport.EfficiencyCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ToneBadge(tone: card.tone)

            efficiencyHeadline(card)
                .font(.system(size: 21, weight: .bold))
                .lineSpacing(4)
                .padding(.top, 13)

            Text("\(Int(card.referenceHR.rounded())) bpm 기준 · \(Format.pace(card.previousPaceSec)) → \(Format.pace(card.recentPaceSec))")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)

            TrendLineChart(points: card.points,
                           tint: card.tone.color,
                           endLabels: ("8주 전", "이번 주"))
                .padding(.top, 12)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    private func efficiencyHeadline(_ card: WeeklyReport.EfficiencyCard) -> Text {
        let delta = Int(abs(card.paceDeltaSec).rounded())
        if delta < 2 {
            return Text("같은 심박에서 페이스를 유지하고 있어요").foregroundStyle(RR.text)
        }
        let seconds = Text("\(delta)초").foregroundStyle(card.tone.color)
        if card.paceDeltaSec > 0 {
            return Text("같은 심박에서 페이스가 ").foregroundStyle(RR.text)
                + seconds
                + Text(" 빨라졌어요").foregroundStyle(RR.text)
        }
        return Text("같은 심박에서 페이스가 ").foregroundStyle(RR.text)
            + seconds
            + Text(" 느려졌어요").foregroundStyle(RR.text)
    }

    // MARK: 표본 부족 안내

    private var insufficientCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("아직 해석할 만큼 기록이 쌓이지 않았어요")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(RR.text)
            Text("지표마다 필요한 최소 기록이 다릅니다. 주간 거리 비교는 2주, 부하 지표(ACWR)는 4주치가 쌓이면 계산돼요. 틀린 해석을 보여드리지 않기 위해서예요.")
                .font(.system(size: 13.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .rrCard()
    }
}

// MARK: - 빈 상태

struct EmptyReportScreen: View {
    @EnvironmentObject private var health: HealthStore
    @State private var showSample = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 7) {
                    Eyebrow(text: "This week")
                    Text("주간 리포트")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(RR.text)
                }
                .padding(.bottom, 6)

                VStack(spacing: 0) {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(RR.surface2)
                        .frame(width: 54, height: 54)
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(RR.line))
                        .overlay {
                            Image(systemName: "figure.run")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(RR.text3)
                        }
                    Text("아직 분석할 러닝이 없어요")
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(RR.text)
                        .padding(.top, 16)
                    Text("Apple Watch로 러닝을 한 번 기록하면 바로 첫 해석이 도착합니다. 부하 지표(ACWR)는 4주치가 모인 뒤 계산돼요.")
                        .font(.system(size: 14))
                        .lineSpacing(4)
                        .foregroundStyle(RR.text2)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                    Button {
                        showSample = true
                    } label: {
                        Text("샘플 리포트 둘러보기")
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 22)
                            .padding(.vertical, 13)
                            .background(RR.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .padding(.top, 20)
                }
                .frame(maxWidth: .infinity)
                .padding(EdgeInsets(top: 30, leading: 24, bottom: 30, trailing: 24))
                .rrCard()

                skeletonCards
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
        }
        .background(RR.bg.ignoresSafeArea())
        .refreshable { await health.load() }
        .sheet(isPresented: $showSample) { SampleReportSheet() }
    }

    private var skeletonCards: some View {
        VStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                SkeletonBlock(width: 88, height: 11)
                SkeletonBlock(height: 19, delay: 0.2)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)
                HStack(alignment: .bottom, spacing: 9) {
                    ForEach(Array([0.38, 0.56, 0.44, 0.72, 0.6, 0.9].enumerated()),
                            id: \.offset) { _, h in
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(RR.barFill)
                            .frame(height: 62 * h)
                            .frame(maxWidth: .infinity, alignment: .bottom)
                    }
                }
                .frame(height: 62, alignment: .bottom)
                .padding(.top, 16)
            }
            .padding(18)
            .rrCard(radius: 22)

            Color.clear
                .frame(height: 96)
                .rrCard(radius: 22)
        }
        .opacity(0.5)
    }
}

/// 빈 상태의 스켈레톤 블록 — 은은한 펄스
private struct SkeletonBlock: View {
    var width: CGFloat? = nil
    let height: CGFloat
    var delay: Double = 0
    @State private var dim = false

    var body: some View {
        RoundedRectangle(cornerRadius: 6, style: .continuous)
            .fill(RR.barFill)
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? 240 : nil, alignment: .leading)
            .opacity(dim ? 0.35 : 0.9)
            .animation(.easeInOut(duration: 1.2).repeatForever().delay(delay), value: dim)
            .onAppear { dim = true }
    }
}

/// 샘플 리포트 시트 — 합성 데이터로 만든 실제 리포트 + 안내 배너
private struct SampleReportSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 12, weight: .semibold))
                    Text("합성 데이터로 만든 샘플이에요. 내 기록이 쌓이면 이렇게 해석해 드립니다.")
                        .font(.system(size: 12.5, weight: .medium))
                }
                .foregroundStyle(RR.brand)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(RR.brandSoft)

                ReportHomeContent(report: ReportEngine().weeklyReport(from: DemoData.runs),
                                  battery: BatteryEngine.compute(vitals: DemoData.vitals,
                                                                 runs: DemoData.runs),
                                  isSample: true)
            }
            .background(RR.bg.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .tint(RR.brand)
    }
}
