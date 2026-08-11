import SwiftUI

/// 홈 — 주간 리포트 (시안 "홈 · 주간 리포트" / "빈 상태 · 기록 없음")
struct ReportHomeScreen: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage(ProfileKey.goal) private var goalRaw = RunGoal.training.rawValue
    @AppStorage(ProfileKey.level) private var levelRaw = RunLevel.experienced.rawValue
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                if runs.isEmpty {
                    EmptyReportScreen()
                } else {
                    let level = RunLevel(rawValue: levelRaw) ?? .experienced
                    let goal = RunGoal(rawValue: goalRaw) ?? .training
                    let battery = health.vitals.flatMap {
                        BatteryEngine.compute(vitals: $0, runs: runs)
                    }
                    ReportHomeContent(report: ReportEngine(level: level).weeklyReport(from: runs),
                                      battery: battery,
                                      goal: goal,
                                      level: level,
                                      weight: ReportEngine.weightTrend(samples: health.bodyMass,
                                                                       now: Date()),
                                      vo2Max: ReportEngine.vo2MaxTrend(samples: health.vo2Max,
                                                                       now: Date()),
                                      form: FormTrend.compute(runs: runs, now: Date()),
                                      guide: trainingGuide(runs: runs, goal: goal, level: level,
                                                           batteryTone: battery?.tone))
                        .refreshable { await health.load() }
                }
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    /// 훈련 가이드 — 훈련 모드에서 목표 레이스를 설정했을 때만 계산한다 (계획서 M7)
    private func trainingGuide(runs: [RunSummary], goal: RunGoal, level: RunLevel,
                               batteryTone: RRTone?) -> TrainingGuide? {
        guard goal == .training, let race = RaceDistance(rawValue: raceGoalRaw) else { return nil }
        return TrainingGuideEngine(now: Date(), level: level)
            .guide(runs: runs, records: PersonalRecords.compute(runs: runs), race: race,
                   goalSec: raceGoalSec > 0 ? Double(raceGoalSec) : nil,
                   batteryTone: batteryTone)
    }
}

// MARK: - 리포트 본문

struct ReportHomeContent: View {
    let report: WeeklyReport
    /// 체력 배터리 — 활력징후 기준선이 부족하면 nil (안내 카드로 대체)
    var battery: BatteryReport? = nil
    /// 프로필 — 카드 포함 여부·순서와 문장 난이도만 바꾼다 (집계는 동일, 계획서 M2)
    var goal: RunGoal = .training
    var level: RunLevel = .experienced
    /// 몸무게 추이 — 다이어트 모드에서만 노출 (표본 부족이면 엔진이 nil을 준다)
    var weight: WeightTrend? = nil
    /// 심폐 체력(VO₂max) 추이 — 목적 무관 노출 (표본 부족이면 엔진이 nil을 준다)
    var vo2Max: Vo2MaxTrend? = nil
    /// 주간 케이던스 추이 — 최근 28일 케이던스 표본이 부족하면 엔진이 nil을 준다 (계획서 M4)
    var form: FormTrend? = nil
    /// 훈련 가이드 — 훈련 모드 + 목표 레이스 설정 시에만 값이 온다 (계획서 M7)
    var guide: TrainingGuide? = nil
    /// 샘플 리포트 시트에서는 상세 이동 대신 배너를 단다
    var isSample = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header

                // 다이어트 모드: 칼로리·몸무게·streak을 앞세우고 ACWR은 맨 뒤로 (계획서 M2)
                if goal == .diet {
                    if let diet = report.diet { dietCaloriesCard(diet) }
                    if let weight { weightCard(weight) }
                    if report.streakWeeks >= 1 { streakCard }
                }

                if let battery {
                    batteryCard(battery)
                } else if !isSample {
                    batteryHintCard
                }

                if let distance = report.distance { distanceCard(distance) }
                if goal == .diet {
                    if let efficiency = report.efficiency { efficiencyCard(efficiency) }
                    if let acwr = report.acwr { acwrCard(acwr) }
                } else {
                    if let acwr = report.acwr { acwrCard(acwr) }
                    if let efficiency = report.efficiency { efficiencyCard(efficiency) }
                }

                if let vo2Max { vo2MaxCard(vo2Max) }

                if let form { formTrendCard(form) }

                if let guide { trainingGuideCard(guide) }

                if report.isEmpty { insufficientCard }

                if !isSample && !report.isEmpty {
                    NavigationLink {
                        ReportDetailScreen(report: report, guide: guide)
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
                Eyebrow(text: "\(report.weekLabel) · 이번 주")
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

            // 설정 진입점 — 샘플 시트에서는 프로필을 바꿀 이유가 없어 숨긴다 (계획서 M2)
            if !isSample {
                NavigationLink {
                    SettingsScreen()
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(RR.text2)
                        .frame(width: 38, height: 38)
                        .background(RR.surface, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous).strokeBorder(RR.line))
                }
            }
        }
        .padding(.bottom, 6)
    }

    // MARK: 다이어트 모드 카드 (칼로리 · 몸무게 · streak) — 기획서 §4.5, 계획서 M2

    private func dietCaloriesCard(_ card: WeeklyReport.DietCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            dietBadge(label: "칼로리", code: "BURNING", color: RR.brand, soft: RR.brandSoft)

            Text("이번 주 \(Format.kcal(card.weekKcal)) kcal를 태웠어요")
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            if let change = card.changePct {
                Text(level == .beginner
                     ? String(format: "지난주보다 %.0f%% %@ 태웠어요",
                              abs(change), change >= 0 ? "더" : "덜")
                     : String(format: "지난주 대비 %+.0f%% · 러닝 세션 소모 기준", change))
                    .font(.system(size: 13))
                    .foregroundStyle(RR.text2)
                    .padding(.top, 7)
            }

            // WeekBar.km 필드에 kcal를 담았다 — 차트는 단위를 모른다 (계획서 M2)
            WeeklyBarsChart(weeks: card.weeks, currentColor: RR.brand,
                            valueText: { Format.kcal($0) + " kcal" },
                            barValueText: { Format.kcal($0) })
                .padding(.top, 16)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    private func weightCard(_ w: WeightTrend) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            dietBadge(label: "몸무게", code: "WEIGHT", color: w.tone.color, soft: w.tone.softColor)

            Text(weightHeadline(w))
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            Text(String(format: "이번 주 평균 %.1f kg · 최근 8주 추이 · 건강 앱 기록 기준", w.currentKg))
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)

            TrendLineChart(points: w.points,
                           tint: w.tone.color,
                           endLabels: w.pointLabels.count >= 2
                               ? (w.pointLabels.first!, w.pointLabels.last!) : nil,
                           pointLabels: w.pointLabels,
                           valueText: { String(format: "%.1f kg", $0) })
                .padding(.top, 12)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    private func weightHeadline(_ w: WeightTrend) -> String {
        guard let delta = w.deltaKg else {
            return level == .beginner ? "몸무게 기록이 쌓이는 중이에요"
                                      : String(format: "이번 주 평균 %.1f kg", w.currentKg)
        }
        if level == .beginner {
            return delta <= -0.2 ? "몸무게가 천천히 내려가고 있어요"
                 : delta >= 0.2 ? "몸무게가 조금 올라왔어요"
                                : "몸무게가 그대로 유지되고 있어요"
        }
        return String(format: "%d주 전보다 %.1f kg %@",
                      w.spanWeeks, abs(delta), delta <= 0 ? "가벼워졌어요" : "무거워졌어요")
    }

    private var streakCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            dietBadge(label: "연속 기록", code: "STREAK", color: RR.brand, soft: RR.brandSoft)

            Text("\(report.streakWeeks)주 연속 달리고 있어요")
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            Text("주 1회 기준 · 이번 주 \(report.weekRunCount)회 완료")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    // MARK: 심폐 체력 카드 (VO₂max)

    /// 심폐 체력 추이 카드 — 목적과 무관한 기초 체력 지표라 모든 프로필에 노출한다
    private func vo2MaxCard(_ v: Vo2MaxTrend) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            dietBadge(label: "심폐 체력", code: "VO2MAX", color: v.tone.color, soft: v.tone.softColor)

            Text(vo2MaxHeadline(v))
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            Text(String(format: "이번 주 평균 %.1f ml/kg/min · 최근 12주 추이 · 워치 추정 기준",
                        v.current))
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)

            TrendLineChart(points: v.points,
                           tint: v.tone.color,
                           endLabels: v.pointLabels.count >= 2
                               ? (v.pointLabels.first!, v.pointLabels.last!) : nil,
                           pointLabels: v.pointLabels,
                           valueText: { String(format: "%.1f", $0) })
                .padding(.top, 12)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    private func vo2MaxHeadline(_ v: Vo2MaxTrend) -> String {
        guard let delta = v.delta else {
            return level == .beginner ? "심폐 체력 기록이 쌓이는 중이에요"
                                      : String(format: "VO₂max %.1f", v.current)
        }
        if level == .beginner {
            // 문장 분기는 엔진이 정한 톤을 그대로 따른다 — 화면에서 임계값을 재판정하지 않는다
            return switch v.tone {
            case .improving: "심폐 체력이 좋아지고 있어요"
            case .caution: "심폐 체력이 살짝 내려왔어요"
            default: "심폐 체력이 잘 유지되고 있어요"
            }
        }
        return switch v.tone {
        case .improving: String(format: "VO₂max가 %d주 전보다 %.1f 올랐어요", v.spanWeeks, abs(delta))
        case .caution: String(format: "VO₂max가 %d주 전보다 %.1f 내려왔어요", v.spanWeeks, abs(delta))
        default: String(format: "VO₂max %.1f — 안정적으로 유지 중이에요", v.current)
        }
    }

    /// 카드 종류 배지 (다이어트·심폐 체력 카드 공용) — 판정 톤이 아니라 카드 종류 표시라 ToneBadge를 쓰지 않는다
    private func dietBadge(label: String, code: String, color: Color, soft: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(color)
            Text(code)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .kerning(1)
                .foregroundStyle(color.opacity(0.65))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(soft, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
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
                           endLabels: card.pointLabels.count >= 2
                               ? (card.pointLabels.first!, card.pointLabels.last!) : nil,
                           pointLabels: card.pointLabels,
                           valueText: { String(format: "EF %.2f", $0) })
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

    // MARK: 주법 추이 카드 (케이던스 2주 비교) — 기획서 §4.8, 계획서 M4

    private func formTrendCard(_ form: FormTrend) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ToneBadge(tone: form.tone)

            Text(formHeadline(form))
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            Text(String(format: "케이던스 최근 2주 평균 %.0f spm · 이전 2주 %.0f spm",
                        form.recentSpm, form.previousSpm))
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    private func formHeadline(_ form: FormTrend) -> String {
        let delta = Int(abs(form.deltaSpm).rounded())
        switch form.tone {
        case .improving:
            return level == .beginner ? "발걸음이 조금 더 잦고 가벼워졌어요"
                                      : "케이던스가 2주 전보다 \(delta) spm 올랐어요"
        case .caution:
            return level == .beginner ? "발걸음 수가 줄었어요 — 보폭이 커졌을 수 있어요"
                                      : "케이던스가 2주 전보다 \(delta) spm 내렸어요"
        default:
            return "케이던스가 평소 리듬을 유지하고 있어요"
        }
    }

    // MARK: 훈련 가이드 카드 (Riegel 진단 + 주간 처방) — 기획서 §4.9, 계획서 M7

    private func trainingGuideCard(_ guide: TrainingGuide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            dietBadge(label: "훈련 가이드", code: "COACH",
                      color: guide.prediction?.tone.color ?? RR.brand,
                      soft: guide.prediction?.tone.softColor ?? RR.brandSoft)

            Text(guideHeadline(guide))
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            if let prediction = guide.prediction {
                Text(predictionCaption(prediction))
                    .font(.system(size: 13))
                    .foregroundStyle(RR.text2)
                    .padding(.top, 7)
            }

            Divider().overlay(RR.line).padding(.top, 14)

            HStack(spacing: 8) {
                metric(label: "권장 주간",
                       value: kmRange(guide.prescription.weeklyKmLow,
                                      guide.prescription.weeklyKmHigh),
                       unit: "km", color: RR.text)
                metric(label: "LSD 목표",
                       value: kmRange(guide.prescription.lsdKmLow,
                                      guide.prescription.lsdKmHigh),
                       unit: "km", color: RR.text)
                metric(label: "스피드",
                       value: "≤\(guide.prescription.speedSessionsMax)",
                       unit: "회/주", color: RR.text)
            }
            .padding(.top, 13)

            if guide.prescription.batteryLimited {
                Text("체력 배터리가 낮아 이번 주 LSD 목표를 하한으로 줄였어요")
                    .font(.system(size: 11.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.top, 10)
            }
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    private func guideHeadline(_ guide: TrainingGuide) -> String {
        guard let prediction = guide.prediction else {
            // 8주 내 PR이 없어 예측은 못 해도 처방은 나간다
            return "이번 주 훈련 처방이 준비됐어요"
        }
        let time = Format.duration(prediction.predictedSec)
        return level == .beginner
            ? "지금 흐름이면 \(prediction.race.label)를 \(time)에 들어올 수 있어요"
            : "\(prediction.race.label) 예상 완주 \(time)"
    }

    private func predictionCaption(_ prediction: TrainingGuide.Prediction) -> String {
        var caption = "\(prediction.baseLabel) \(Format.duration(prediction.baseTimeSec)) 기록 기준 Riegel 예측"
        if let goal = prediction.goalSec {
            caption += " · 목표 \(Format.duration(goal))"
        }
        return caption
    }

    /// "30~33" / 상·하한이 같으면 "7.5" 하나만
    private func kmRange(_ low: Double, _ high: Double) -> String {
        high - low < 0.05 ? String(format: "%.1f", low)
                          : String(format: "%.1f~%.1f", low, high)
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
