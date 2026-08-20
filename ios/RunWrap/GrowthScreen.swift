import SwiftUI

/// 나의 성장기 — 장기 추이 3종(페이스·EF·거리) + PB 목록 (이슈 #21).
///
/// StatsScreen("발전상" 섹션)에 있던 추이·PB를 세그먼트 화면으로 분리했다.
/// 지표 전환 세그먼트 대신 카드 3장으로 펼쳐 한 화면에서 흐름을 훑게 한다.
/// PB에는 종목별 메달(풀=금·하프=은·10K=동·5K=브랜드색)을 단다.
struct GrowthScreen: View {
    /// [내 상태 | 이번달 | 나의 성장기] 세그먼트 — 리포트 탭이 넘긴다
    var segment: AnyView? = nil

    @EnvironmentObject private var health: HealthStore

    /// 추이 카드 3장 — 카드마다 지표가 고정이다 (전환 세그먼트 없음)
    private enum Metric: String, CaseIterable {
        case pace = "페이스", ef = "EF", distance = "거리"

        var caption: String {
            switch self {
            case .pace: "월 평균 페이스 · 내려갈수록 빨라진 것"
            case .ef: "심박당 속도(EF) 월 평균 · 올라갈수록 좋아진 것"
            case .distance: "월 누적 거리"
            }
        }

        /// 표본 가드에 걸려 점이 모자랄 때의 안내 — 지표별로 쌓아야 할 게 다르다
        var emptyText: String {
            switch self {
            case .pace: "월 평균 페이스를 그리기엔 아직 기록이 부족해요. 거리가 찍힌 러닝이 달마다 쌓이면 그려집니다."
            case .ef: "이 지표는 아직 월별 기록이 부족해요. 심박이 함께 찍힌 러닝이 달마다 3회쯤 쌓이면 그려집니다."
            case .distance: "월별 기록이 쌓이면 거리 추이가 그려집니다."
            }
        }
    }

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                let series = MonthlySeries.compute(runs: runs, now: Date())
                let records = PersonalRecords.compute(runs: runs)

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 7) {
                            Eyebrow(text: "Growth & records")
                            Text("런미새 리포트")
                                .font(RR.display(33))
                                .foregroundStyle(RR.text)
                        }
                        .padding(.bottom, 6)

                        if let segment { segment.padding(.bottom, 2) }

                        if let series {
                            ForEach(Metric.allCases, id: \.self) { metric in
                                chartCard(metric, series: series)
                            }
                        } else {
                            // MonthlySeries 가드(두 달 이상 기록) 미달 — 지표 대신 안내만 낸다
                            Text("아직 성장기를 그리기엔 기록이 부족해요. 두 달 이상 러닝이 쌓이면 추이가 나타납니다.")
                                .font(.system(size: 13.5))
                                .lineSpacing(4)
                                .foregroundStyle(RR.text3)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(EdgeInsets(top: 20, leading: 18, bottom: 20, trailing: 18))
                                .rrCard()
                        }

                        if !records.isEmpty { recordsCard(records) }
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

    // MARK: 추이 카드

    private func chartCard(_ metric: Metric, series: MonthlySeries) -> some View {
        let points = metricPoints(metric, in: series)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(metric.rawValue)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("최근 12개월")
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            if points.count >= 2 {
                TrendLineChart(points: points.map(\.value),
                               tint: RR.brand,
                               endLabels: (points.first!.label, points.last!.label),
                               pointLabels: points.map(\.label),
                               valueText: metricValueText(metric))
                    .padding(.top, 14)
            } else {
                Text(metric.emptyText)
                    .font(.system(size: 12.5))
                    .lineSpacing(4)
                    .foregroundStyle(RR.text3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 20)
            }

            Text(metric.caption)
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
                .padding(.top, 8)
        }
        .padding(EdgeInsets(top: 16, leading: 18, bottom: 14, trailing: 18))
        .rrCard()
    }

    /// 지표별 (축 라벨, 값) 시리즈 — 가드로 점이 없는 달은 건너뛴다
    private func metricPoints(_ metric: Metric,
                              in series: MonthlySeries) -> [(label: String, value: Double)] {
        switch metric {
        case .pace: series.points.compactMap { p in p.avgPaceSec.map { (p.label, $0) } }
        case .ef: series.points.compactMap { p in p.avgEF.map { (p.label, $0) } }
        case .distance: series.points.map { ($0.label, $0.totalKm) }
        }
    }

    /// 지표별 탭 콜아웃 수치 표기
    private func metricValueText(_ metric: Metric) -> (Double) -> String {
        switch metric {
        case .pace: { Format.paceKm($0) }
        case .ef: { String(format: "EF %.2f", $0) }
        case .distance: { Format.km($0) + " km" }
        }
    }

    // MARK: PB 목록

    private func recordsCard(_ records: [PersonalRecords.Entry]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "내 PB 목록")
                .padding(.horizontal, 16)
                .padding(.top, 14)
            ForEach(Array(records.enumerated()), id: \.element.label) { index, entry in
                NavigationLink {
                    SessionDetailScreen(run: entry.run,
                                        weeklyContext: weeklyContext(for: entry.run))
                } label: {
                    recordRow(entry)
                }
                .buttonStyle(.plain)
                if index < records.count - 1 {
                    Divider().overlay(RR.line).padding(.leading, 66)
                }
            }
        }
        .rrCard()
    }

    /// PB 한 줄 — 종목 메달 + 라벨 + 기록 + 날짜 + 셰브런 (이슈 #21에서 메달 추가)
    private func recordRow(_ entry: PersonalRecords.Entry) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "medal.fill")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(RR.medalColor(forPB: entry.label))
                .frame(width: 26)
            Text(entry.label)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .frame(width: 38, alignment: .leading)
            Text(Format.duration(entry.timeSec))
                .font(.system(size: 17, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
            Spacer(minLength: 8)
            Text(recordDate(entry.date))
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(RR.text3)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(RR.text3)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    private func recordDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "yyyy.M.d"
        return formatter.string(from: date)
    }

    /// 세션 상세의 맥락 배지용 — 이번 주 리포트가 과부하일 때만 전달 (StatsScreen과 동일)
    private func weeklyContext(for run: RunSummary) -> WeeklyReport.DistanceCard? {
        guard case .loaded(let runs) = health.state,
              let card = ReportEngine().weeklyReport(from: runs).distance,
              card.tone == .overload else { return nil }
        return card
    }
}
