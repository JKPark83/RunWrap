import SwiftUI

/// 훈련 계획 캘린더 (이슈 #189) — 목표 대회에서 역산한 주차별 처방.
/// 리포트 홈 race 카드에서 push로 들어온다. 계산은 전부 TrainingPlanEngine이 하고
/// 여기서는 그리기만 한다. 지난 주·이번 주는 실제 거리를 계획 옆에 나란히 둔다 —
/// "계획표"가 아니라 "지금 어디쯤인지"가 보여야 계획을 따라갈 수 있어서다.
struct TrainingPlanScreen: View {
    let plan: TrainingPlan

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "training plan")
                    Text("훈련 계획")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(RR.text)
                    Text("\(plan.daysToRace == 0 ? "D-day" : "D-\(plan.daysToRace)") · \(plan.race.label) · \(RaceFormat.fullDate.string(from: plan.raceDate))")
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                }
                .padding(.bottom, 10)

                summaryCard

                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(plan.weeks.enumerated()), id: \.element.id) { index, week in
                        if index > 0 { Divider().overlay(RR.line) }
                        WeekRow(week: week)
                    }
                }
                .padding(.horizontal, 18)
                .rrCard()

                Text("만성 부하(최근 4주 평균)에서 10% 룰로 늘려 피크 주간 거리에서 멈춥니다. 오늘 컨디션·대기질은 홈 판정을 따르세요.")
                    .font(.system(size: 12))
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
        .navigationTitle("훈련 계획")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: 요약 — 단계 타임라인

    /// 이번 주~대회 주간을 연속된 단계 구간으로 묶는다 (구간 = 단계 + 주 수 + 이번 주 포함 여부)
    private var segments: [(phase: TrainingGuide.Phase, weeks: Int, isCurrent: Bool)] {
        var result: [(phase: TrainingGuide.Phase, weeks: Int, isCurrent: Bool)] = []
        for week in plan.weeks {
            guard let phase = week.phase else { continue }
            if let last = result.last, last.phase == phase {
                result[result.count - 1].weeks += 1
                result[result.count - 1].isCurrent = last.isCurrent || week.isCurrent
            } else {
                result.append((phase, 1, week.isCurrent))
            }
        }
        return result
    }

    private var summaryCard: some View {
        let segments = segments
        let taperWeeks = plan.weeks.filter { $0.phase == .taper }.count
        let taperText = taperWeeks > 0 ? ", \(taperWeeks)주 테이퍼" : ""
        return VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "phases")
            PhaseTimeline(segments: segments)
                .frame(height: 30)
                .padding(.top, 14)
            Text("피크 주간 \(String(format: "%.0f", plan.peakWeeklyKm))km 기준 · 10% 룰 점증\(taperText)")
                .font(.system(size: 11.5))
                .lineSpacing(3)
                .foregroundStyle(RR.text3)
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .rrCard()
    }
}

/// 단계 구간을 주 수 비례 너비의 캡슐로 — 짧은 구간(대회 주간 1주)도 라벨이 읽히게
/// 구간마다 최소 너비를 먼저 주고 남는 폭을 주 수 비례로 나눈다
private struct PhaseTimeline: View {
    let segments: [(phase: TrainingGuide.Phase, weeks: Int, isCurrent: Bool)]
    private let spacing: CGFloat = 4
    private let minWidth: CGFloat = 40

    var body: some View {
        GeometryReader { geo in
            let total = max(1, segments.reduce(0) { $0 + $1.weeks })
            let usable = geo.size.width - spacing * CGFloat(max(0, segments.count - 1))
            let spare = max(0, usable - minWidth * CGFloat(segments.count))
            HStack(alignment: .top, spacing: spacing) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    VStack(spacing: 5) {
                        Capsule()
                            .fill(segment.isCurrent ? RR.brand : RR.brandSoft)
                            .frame(height: 8)
                        Text(segment.phase.label)
                            .font(.system(size: 10.5, weight: segment.isCurrent ? .semibold : .regular))
                            .foregroundStyle(segment.isCurrent ? RR.brand : RR.text2)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .frame(width: minWidth + spare * CGFloat(segment.weeks) / CGFloat(total))
                }
            }
        }
    }
}

// MARK: - 주차 행

private struct WeekRow: View {
    let week: TrainingPlan.Week

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 7) {
                Text(week.label)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(week.phase == nil ? RR.text3 : RR.text)
                if week.isCurrent {
                    capsule("이번 주", foreground: RR.brand, background: RR.brandSoft)
                }
                Spacer(minLength: 0)
                if let phase = week.phase {
                    capsule(phase.label, foreground: RR.text2, background: RR.surface2)
                }
            }

            if week.phase == nil {
                Text("실제 \(Format.km(week.actualKm ?? 0))km")
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text3)
            } else {
                Text(planText)
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text2)
                if week.phase != .raceWeek {
                    Text(qualityText)
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text3)
                }
            }

            if week.isCurrent, let actual = week.actualKm, let high = week.weeklyKmHigh {
                HStack(spacing: 10) {
                    Text("지금까지 \(Format.km(actual))km")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(RR.brand)
                        .fixedSize()
                    ProgressCapsule(ratio: high > 0 ? min(1, actual / high) : 0)
                }
            }
        }
        .padding(.vertical, 13)
    }

    /// "주 30.0~33.0km · 롱런 7.5~11.6km" / 대회 주간은 "대회 주간 — 가볍게 12.0~15.0km"
    private var planText: String {
        let weekly = kmRangeText(week.weeklyKmLow ?? 0, week.weeklyKmHigh ?? 0)
        if week.phase == .raceWeek { return "대회 주간 — 가볍게 \(weekly)km" }
        return "주 \(weekly)km · 롱런 \(kmRangeText(week.lsdKmLow ?? 0, week.lsdKmHigh ?? 0))km"
    }

    /// "템포 1 · 인터벌 1" — 0회는 생략, 둘 다 0이면 가볍게
    private var qualityText: String {
        var parts: [String] = []
        if week.tempoCount > 0 { parts.append("템포 \(week.tempoCount)") }
        if week.intervalCount > 0 { parts.append("인터벌 \(week.intervalCount)") }
        return parts.isEmpty ? "퀄리티 없이 가볍게" : parts.joined(separator: " · ")
    }

    /// "30.0~33.0" / 상·하한이 같으면 "65.0" 하나만
    private func kmRangeText(_ low: Double, _ high: Double) -> String {
        high - low < 0.05 ? Format.km(low) : "\(Format.km(low))~\(Format.km(high))"
    }

    private func capsule(_ text: String, foreground: Color, background: Color) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(background, in: Capsule())
    }
}

/// 이번 주 계획 상한 대비 진행 막대 — 1을 넘으면 꽉 찬 채로 둔다
private struct ProgressCapsule: View {
    let ratio: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(RR.brandSoft)
                Capsule().fill(RR.brand)
                    .frame(width: geo.size.width * ratio)
            }
        }
        .frame(height: 5)
    }
}
