import SwiftUI

/// 리포트 요약 — 주간 지표를 문장 + 근거 수치 + 용어 설명으로 풀어낸다 (시안 "리포트 요약")
struct ReportDetailScreen: View {
    let report: WeeklyReport

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Week \(report.weekNumber) · \(report.dateRange)")
                    Text(report.headline)
                        .font(.system(size: 26, weight: .bold))
                        .lineSpacing(5)
                        .foregroundStyle(RR.text)
                }
                .padding(.bottom, 10)

                if let distance = report.distance { distanceSection(distance) }
                if let acwr = report.acwr { acwrSection(acwr) }
                if let efficiency = report.efficiency { efficiencySection(efficiency) }

                if let suggestion = report.suggestion {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("다음 주 제안")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(RR.text)
                        Text(suggestion)
                            .font(.system(size: 13.5))
                            .lineSpacing(4)
                            .foregroundStyle(RR.text2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(RR.surface2, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(RR.line))
                }

                Text("본 리포트는 참고용이며 의학적 조언이 아닙니다. 통증이나 이상이 있다면 전문가와 상담하세요.")
                    .font(.system(size: 11.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.horizontal, 4)
                    .padding(.top, 2)
            }
            .padding(.horizontal, 18)
            .padding(.top, 12)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .navigationTitle("주간 요약")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: 섹션

    private func distanceSection(_ card: WeeklyReport.DistanceCard) -> some View {
        let sentence = card.overKm > 0
            ? String(format: "권장 상한 %.1f km를 %.1f km 넘겼습니다.", card.capKm, card.overKm)
            : String(format: "권장 상한 %.1f km 안에서 달렸습니다.", card.capKm)
        let changeText = String(format: "%@%.1f%%", card.changePct >= 0 ? "+" : "−", abs(card.changePct))
        return section(color: card.tone == .steady ? RR.brand : card.tone.color,
                       title: "주간 거리 · 10% 규칙",
                       sentence: sentence,
                       metrics: [(Format.km(card.recent7Km) + " km", RR.text2),
                                 (changeText, RR.text2),
                                 (String(format: "상한 %.1f km", card.capKm), RR.text2)],
                       explainTitle: "10% 규칙이란",
                       explainBody: "주간 거리를 직전 주 대비 10% 이내로 늘려야 몸이 적응할 시간이 생긴다는 경험칙.")
    }

    private func acwrSection(_ card: WeeklyReport.AcwrCard) -> some View {
        let sentence: String
        switch card.tone {
        case .overload, .caution:
            sentence = card.ratio > 1.3
                ? "안전 구간 0.8–1.3을 벗어났습니다. 1.5를 넘으면 부상 위험이 커집니다."
                : "훈련량이 평소의 8할 밑으로 내려갔습니다. 급감도 리듬을 흔들 수 있어요."
        default:
            sentence = "안전 구간 0.8–1.3 안에 있습니다. 몸이 감당할 수 있는 부하예요."
        }
        return section(color: card.tone == .steady ? RR.pos : card.tone.color,
                       title: "부하 비율 · ACWR",
                       sentence: sentence,
                       metrics: [(String(format: "급성 %.1f", card.acute), RR.text2),
                                 (String(format: "만성 %.1f", card.chronic), RR.text2),
                                 (String(format: "ACWR %.2f", card.ratio),
                                  card.tone == .steady ? RR.text2 : card.tone.color)],
                       explainTitle: "ACWR이란",
                       explainBody: "최근 7일 훈련량 ÷ 최근 4주 주간 평균. 1에 가까울수록 평소만큼 달렸다는 뜻입니다.")
    }

    private func efficiencySection(_ card: WeeklyReport.EfficiencyCard) -> some View {
        let delta = Int(abs(card.paceDeltaSec).rounded())
        let sentence: String
        switch card.tone {
        case .improving:
            sentence = "같은 심박에서 페이스가 \(delta)초 빨라졌습니다. 체력은 오르는 중입니다."
        case .caution:
            sentence = "같은 심박에서 페이스가 \(delta)초 느려졌습니다. 피로가 쌓였다는 신호일 수 있어요."
        default:
            sentence = "같은 심박에서 페이스가 비슷하게 유지되고 있습니다."
        }
        let changeText = String(format: "%@%.1f%%", card.changePct >= 0 ? "+" : "−", abs(card.changePct))
        return section(color: card.tone == .steady ? RR.brand : card.tone.color,
                       title: "심박 효율 · Efficiency Factor",
                       sentence: sentence,
                       metrics: [(String(format: "EF %.2f → %.2f", card.previousEF, card.recentEF), RR.text2),
                                 (changeText, card.tone == .improving ? RR.pos : RR.text2)],
                       explainTitle: "효율 지수란",
                       explainBody: "속도 ÷ 평균 심박. 값이 커지면 같은 노력으로 더 빨리 달린다는 뜻입니다.")
    }

    private func section(color: Color, title: String, sentence: String,
                         metrics: [(String, Color)],
                         explainTitle: String, explainBody: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 7) {
                Circle().fill(color).frame(width: 7, height: 7)
                Text(title)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(color)
            }

            Text(sentence)
                .font(.system(size: 16, weight: .semibold))
                .lineSpacing(5)
                .foregroundStyle(RR.text)
                .padding(.top, 11)

            HStack(spacing: 8) {
                ForEach(Array(metrics.enumerated()), id: \.offset) { index, metric in
                    if index > 0 {
                        Text("·").foregroundStyle(RR.text3)
                    }
                    Text(metric.0)
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(metric.1)
                }
            }
            .padding(.top, 13)

            VStack(alignment: .leading, spacing: 7) {
                Text(explainTitle)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(RR.text2)
                Text(explainBody)
                    .font(.system(size: 13))
                    .lineSpacing(4)
                    .foregroundStyle(RR.text2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(13)
            .background(RR.surface2, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).strokeBorder(RR.line))
            .padding(.top, 14)
        }
        .padding(18)
        .rrCard(radius: 20)
    }
}
