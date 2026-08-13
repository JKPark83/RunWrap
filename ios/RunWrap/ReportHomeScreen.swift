import SwiftUI

/// 리포트 탭 — 상단 세그먼트로 [이번 주 | 발전상]을 가른다 (기획서 v0.7 §6).
///
/// v0.7에서 통계 탭이 사라지고 이 화면의 "발전상" 세그먼트로 흡수됐다.
/// 다이어트/훈련 목적별 배치 분기도 제거됐다 — 목적은 문장의 강조점만 바꾸고,
/// 어떤 카드를 보여줄지는 레벨 게이트(`ReportGate`, §4)가 정한다.
struct ReportHomeScreen: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage(ProfileKey.levelV2) private var levelRaw = RunnerLevel.beginner.rawValue
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0
    @AppStorage(ProfileKey.weeklyGoal) private var weeklyGoal = 2
    @AppStorage(GrowthKey.cycleStartedAt) private var cycleStartedAtRaw = 0.0
    @AppStorage(ProfileKey.onboardedAt) private var onboardedAtRaw = 0.0
    @State private var tab: ReportTab = .thisWeek

    /// 리포트 탭 세그먼트 — 통계 탭을 흡수한 자리 (§6)
    enum ReportTab: String, CaseIterable {
        case thisWeek, progress

        var label: String {
            switch self {
            case .thisWeek: "이번 주"
            case .progress: "발전상"
            }
        }
    }

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                if runs.isEmpty {
                    EmptyReportScreen()
                } else {
                    let level = RunnerLevel(rawValue: levelRaw) ?? .beginner
                    let battery = health.vitals.flatMap {
                        BatteryEngine.compute(vitals: $0, runs: runs)
                    }
                    switch tab {
                    case .thisWeek:
                        ReportHomeContent(report: ReportEngine(level: level).weeklyReport(from: runs),
                                          battery: battery,
                                          level: level,
                                          vo2Max: ReportEngine.vo2MaxTrend(samples: health.vo2Max,
                                                                           now: Date()),
                                          hrr: ReportEngine.hrrTrend(samples: health.hrrTrend,
                                                                     now: Date()),
                                          cross: CrossTrainingEngine.weekly(cross: health.crossTrainings,
                                                                            runs: runs, now: Date()),
                                          form: FormTrend.compute(runs: runs, now: Date()),
                                          guide: trainingGuide(runs: runs, level: level,
                                                               batteryTone: battery?.tone),
                                          walkRun: WalkRunEngine.plan(
                                              cycleStartedAt: cycleStartedAt,
                                              weeklyGoal: weeklyGoal,
                                              runs: runs, now: Date()),
                                          segment: segment)
                            .refreshable { await health.load() }
                    case .progress:
                        // 발전상 — 옛 통계 탭 본문을 그대로 쓴다 (§6 "리포트 탭 상단 세그먼트로 흡수")
                        StatsScreen(segment: AnyView(segment))
                    }
                }
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    /// [이번 주 | 발전상] 세그먼트 — 두 세그먼트가 같은 컨트롤을 공유한다
    private var segment: some View {
        Picker("", selection: $tab) {
            ForEach(ReportTab.allCases, id: \.self) { Text($0.label).tag($0) }
        }
        .pickerStyle(.segmented)
    }

    /// 걷뛰 사이클 시작 시각 — 없으면 nil을 줘서 카드를 아예 내지 않는다.
    /// 홈(성장)은 같은 상황에서 `.distantPast`로 떨어뜨리지만 여기서는 그러면 안 된다 —
    /// 성장은 "기록 전체를 세는" 쪽이 유리한 반면, 걷뛰는 경과 주차가 곧 처방 강도라
    /// 먼 과거를 넣으면 8주차(뛰기 5분)가 나와 입문자에게 과한 처방이 된다.
    /// 표본이 아니라 기준점이 없는 경우지만, 판단 근거가 없으면 내지 않는 원칙은 같다
    private var cycleStartedAt: Date? {
        if cycleStartedAtRaw > 0 { return Date(timeIntervalSince1970: cycleStartedAtRaw) }
        if onboardedAtRaw > 0 { return Date(timeIntervalSince1970: onboardedAtRaw) }
        return nil
    }

    /// 훈련 가이드 — 목표 레이스를 설정했을 때만 계산한다 (계획서 M7).
    /// v0.7에서 "훈련 모드" 조건이 사라졌다 — 목적과 무관하게 목표가 있으면 가이드를 낸다
    private func trainingGuide(runs: [RunSummary], level: RunnerLevel,
                               batteryTone: RRTone?) -> TrainingGuide? {
        guard let race = RaceDistance(rawValue: raceGoalRaw) else { return nil }
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
    /// 레벨 — 카드 포함 여부(ReportGate)와 문장 난이도를 정한다. 집계는 레벨과 무관하게 동일
    var level: RunnerLevel = .beginner
    /// 심폐 체력(VO₂max) 추이 — 목적 무관 노출 (표본 부족이면 엔진이 nil을 준다)
    var vo2Max: Vo2MaxTrend? = nil
    /// 심박 회복(HRR) 추이 — 심폐 체력 카드의 보조 라인 (제안 문서 B1, 표본 부족이면 nil)
    var hrr: HrrTrend? = nil
    /// 크로스 트레이닝 주간 요약 — 비러닝 운동 보조 카드 (제안 문서 A3, 20분 미만이면 nil)
    var cross: CrossTrainingEngine.Summary? = nil
    /// 주간 케이던스 추이 — 최근 28일 케이던스 표본이 부족하면 엔진이 nil을 준다 (계획서 M4)
    var form: FormTrend? = nil
    /// 훈련 가이드 — 훈련 모드 + 목표 레이스 설정 시에만 값이 온다 (계획서 M7)
    var guide: TrainingGuide? = nil
    /// 걷뛰 처방 — 런린이 전용 (§4). 사이클 시작 시각이 없으면 엔진이 nil을 준다
    var walkRun: WalkRunEngine.Plan? = nil
    /// 샘플 리포트 시트에서는 상세 이동 대신 배너를 단다
    var isSample = false
    /// [이번 주 | 발전상] 세그먼트 — 샘플 시트에서는 넘기지 않아 nil이다
    var segment: (any View)? = nil

    /// 카드 노출 = 미노출 가드(엔진 nil) AND 레벨 게이트.
    /// 순서가 중요하다 — `if let`으로 표본을 먼저 확인하고 게이트를 뒤에 건다 (ReportGate 주석)
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header

                if let segment {
                    AnyView(segment).padding(.bottom, 2)
                }

                if let battery, ReportGate.shows(.battery, level: level) {
                    batteryCard(battery)
                } else if !isSample {
                    batteryHintCard
                }

                // 걷뛰는 런린이에게서 걷어낸 지표들(ACWR·EF·VO₂max·주법)의 자리를 대신 채운다.
                // 그래서 위쪽 — 배터리 바로 다음 — 에 둔다 (§4 "더하는 차별화")
                if let walkRun, ReportGate.shows(.walkRun, level: level) {
                    walkRunCard(walkRun)
                }

                if let distance = report.distance, ReportGate.shows(.distance, level: level) {
                    distanceCard(distance)
                }
                if let acwr = report.acwr, ReportGate.shows(.acwr, level: level) {
                    acwrCard(acwr)
                }
                if let efficiency = report.efficiency, ReportGate.shows(.efficiency, level: level) {
                    efficiencyCard(efficiency)
                }

                if let cross, ReportGate.shows(.crossTraining, level: level) {
                    crossTrainingCard(cross)
                }

                if let vo2Max, ReportGate.shows(.vo2Max, level: level) { vo2MaxCard(vo2Max) }

                if let form, ReportGate.shows(.form, level: level) { formTrendCard(form) }

                if let guide, ReportGate.shows(.trainingGuide, level: level) {
                    trainingGuideCard(guide)
                }

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
                        .background(
                            LinearGradient(colors: [RR.brand, RR.brand.opacity(0.82)],
                                           startPoint: .topLeading, endPoint: .bottomTrailing),
                            in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: RR.brand.opacity(0.28), radius: 10, y: 4)
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
                Eyebrow(text: report.weekLabel)
                Text("주간 리포트")
                    .font(RR.display(33))
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

    // MARK: 심폐 체력 카드 (VO₂max)

    /// 심폐 체력 추이 카드 — 목적과 무관한 기초 체력 지표라 모든 프로필에 노출한다
    private func vo2MaxCard(_ v: Vo2MaxTrend) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(icon: "lungs.fill", title: "심폐 체력", code: "VO2MAX",
                       tint: v.tone.color, soft: v.tone.softColor, info: CardInfoText.vo2Max)

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

            // 심박 회복(HRR) 보조 라인 — 러닝 직후 1분 심박 하락 폭, 클수록 회복이 빠르다 (제안 문서 B1)
            if let hrr {
                HStack(spacing: 5) {
                    Image(systemName: "arrow.clockwise.heart")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(hrr.tone.color)
                    Text(hrrLine(hrr))
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text2)
                }
                .padding(.top, 6)
            }

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

    /// 심박 회복 한 줄 — 사실(수치) 먼저, 위트는 뒤 (제안 문서 B1)
    private func hrrLine(_ hrr: HrrTrend) -> String {
        let base = "심박 회복 \(Int(hrr.current.rounded())) bpm"
        guard let delta = hrr.delta, hrr.spanWeeks > 0 else {
            return base + " — 러닝 직후 1분에 이만큼 내려와요"
        }
        return switch hrr.tone {
        case .improving:
            base + String(format: " · %d주 전보다 +%.0f — 회복 엔진도 좋아지는 중", hrr.spanWeeks, abs(delta))
        case .caution:
            base + String(format: " · %d주 전보다 −%.0f — 회복 쪽도 챙겨 주세요", hrr.spanWeeks, abs(delta))
        default:
            base + " · \(hrr.spanWeeks)주 전과 비슷하게 유지 중"
        }
    }

    // MARK: 크로스 트레이닝 카드 — 비러닝 운동 보조 정보 (제안 문서 A3)

    /// 보조 카드 — 시간·세션 수만 보여준다. 거리 부하(ACWR)·주간 거리 집계에는 절대 섞지 않는다
    private func crossTrainingCard(_ cross: CrossTrainingEngine.Summary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(icon: "figure.cross.training", title: "크로스 트레이닝", code: "CROSS",
                       tint: RR.brand, soft: RR.brandSoft, info: CardInfoText.cross)

            Text(cross.headline)
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            HStack(spacing: 8) {
                metric(label: "이번 주", value: "\(cross.sessionCount)", unit: "회", color: RR.text)
                metric(label: "총 시간", value: crossTimeLabel(cross.totalMinutes), unit: "",
                       color: RR.text)
                if let top = cross.breakdown.first {
                    metric(label: "가장 많이", value: top.label, unit: "", color: RR.text2)
                }
            }
            .padding(.top, 13)

            Text(cross.detail)
                .font(.system(size: 12))
                .lineSpacing(3)
                .foregroundStyle(RR.text3)
                .padding(.top, 11)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 18, trailing: 18))
        .rrCard()
    }

    /// "1시간 30분"·"45분" — metric 셀에 들어가는 총 시간 라벨
    private func crossTimeLabel(_ minutes: Int) -> String {
        guard minutes >= 60 else { return "\(minutes)분" }
        let remainder = minutes % 60
        return remainder == 0 ? "\(minutes / 60)시간" : "\(minutes / 60)시간 \(remainder)분"
    }

    /// 카드 공통 헤더 — 아이콘 타일 + 제목 + 영문 코드. 판정 카드는 톤 배지를 오른쪽에 단다.
    /// 시안의 점 배지를 아이콘 타일로 확장해 카드마다 시각 정체성을 준다 (확장 요구, 2026-08-11)
    /// info를 주면 제목 옆에 작은 ⓘ가 붙는다 — 누르면 지표 설명 팝오버 (확장 요구, 2026-08-12)
    private func cardHeader(icon: String, title: String, code: String,
                            tint: Color, soft: Color, tone: RRTone? = nil,
                            info: String? = nil) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 34, height: 34)
                .background(soft, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            VStack(alignment: .leading, spacing: 2.5) {
                HStack(spacing: 5) {
                    Text(title)
                        .font(.system(size: 14.5, weight: .bold))
                        .foregroundStyle(RR.text)
                    if let info {
                        CardInfoButton(title: title, text: info)
                    }
                }
                Text(code)
                    .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                    .kerning(1.2)
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 8)
            if let tone {
                ToneBadge(tone: tone)
            }
        }
    }

    // MARK: 체력 배터리 카드

    private func batteryCard(_ battery: BatteryReport) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(icon: "bolt.heart.fill", title: "체력 배터리", code: battery.statusLabel,
                       tint: battery.tone.color, soft: battery.tone.softColor,
                       info: CardInfoText.battery)

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

            BatteryGauge(level: battery.level)
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

            disclaimer("건강 상태를 진단하거나 의학적 조언을 하지 않습니다. 통증이나 이상이 있다면 전문가와 상담하세요.")
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
            cardHeader(icon: "figure.run", title: "주간 거리", code: "DISTANCE",
                       tint: card.tone.color, soft: card.tone.softColor, tone: card.tone,
                       info: CardInfoText.distance)

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

    /// 판정은 롤링 7일 창(달력 주 아님)이라 "지난주" 표현을 쓰지 않는다 —
    /// 차트(달력 주)와 기준이 달라 주 초반엔 방향이 반대로 보일 수 있기 때문.
    /// 하단 지표 라벨("최근 7일"/"이전 7일")과 같은 말로 맞춘다.
    private func distanceHeadline(_ card: WeeklyReport.DistanceCard) -> Text {
        let pct = Text(String(format: "%.0f%%", abs(card.changePct)))
            .foregroundStyle(card.tone.color)
        let base: Text
        if card.changePct >= 0 {
            base = Text("최근 7일 거리가 그 전 7일보다 ").foregroundStyle(RR.text)
                + pct
                + Text(" 늘었어요").foregroundStyle(RR.text)
        } else {
            base = Text("최근 7일 거리가 그 전 7일보다 ").foregroundStyle(RR.text)
                + pct
                + Text(" 줄었어요").foregroundStyle(RR.text)
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
            cardHeader(icon: "speedometer", title: "훈련 부하", code: "ACWR",
                       tint: card.tone.color, soft: card.tone.softColor, tone: card.tone,
                       info: CardInfoText.acwr)

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
            cardHeader(icon: "heart.fill", title: "심박 효율", code: "EFFICIENCY",
                       tint: card.tone.color, soft: card.tone.softColor, tone: card.tone,
                       info: CardInfoText.efficiency)

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
            cardHeader(icon: "shoeprints.fill", title: "주법 리듬", code: "CADENCE",
                       tint: form.tone.color, soft: form.tone.softColor, tone: form.tone,
                       info: CardInfoText.cadence)

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

    /// 걷뛰 카드 — 런린이가 오늘 그대로 따라 할 수 있는 한 세션 처방 (§4).
    /// 수치를 감춘 다른 런린이 카드와 달리 여기서는 분·세트를 그대로 보여준다 —
    /// 해석해야 하는 지표가 아니라 실행하는 지시라서 숫자가 곧 내용이다.
    private func walkRunCard(_ plan: WalkRunEngine.Plan) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(icon: "figure.walk", title: "걷뛰 프로그램", code: "START",
                       tint: RR.brand, soft: RR.brandSoft, info: CardInfoText.walkRun)

            Text(plan.headline)
                .font(.system(size: 21, weight: .bold))
                .foregroundStyle(RR.text)
                .lineSpacing(4)
                .padding(.top, 13)

            Text("\(plan.weekBadge) · 한 번에 \(Int(plan.totalMinutes))분")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 7)

            Divider().overlay(RR.line).padding(.top, 14)

            // 걷기·뛰기 비율 막대 — 주차가 오를수록 뛰기(브랜드색)가 걷기를 밀어낸다.
            // 8주차는 걷기가 0이라 막대가 통째로 뛰기가 된다
            walkRunRatioBar(plan).padding(.top, 14)

            HStack(spacing: 8) {
                metric(label: "걷기", value: Format.walkRunMinutes(plan.walkMinutes),
                       unit: "분", color: RR.text2)
                metric(label: "뛰기", value: Format.walkRunMinutes(plan.runMinutes),
                       unit: "분", color: RR.brand)
                metric(label: "세트", value: "\(plan.sets)", unit: "회", color: RR.text)
            }
            .padding(.top, 13)

            Text(plan.progressLine)
                .font(.system(size: 12.5))
                .foregroundStyle(RR.text3)
                .padding(.top, 12)
        }
        .padding(EdgeInsets(top: 20, leading: 18, bottom: 16, trailing: 18))
        .rrCard()
    }

    /// 한 세트 안의 걷기:뛰기 시간 비율 막대
    private func walkRunRatioBar(_ plan: WalkRunEngine.Plan) -> some View {
        let total = plan.walkMinutes + plan.runMinutes
        return GeometryReader { geo in
            HStack(spacing: 3) {
                // 걷기가 0인 8주차에는 막대를 그리지 않는다 (폭 0짜리 조각을 남기지 않으려고)
                if plan.walkMinutes > 0 {
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(RR.brandSoft)
                        .frame(width: max(0, (geo.size.width - 3) * (plan.walkMinutes / total)))
                }
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(RR.brand)
            }
        }
        .frame(height: 10)
        .accessibilityElement()
        .accessibilityLabel("걷기 \(Format.walkRunMinutes(plan.walkMinutes))분, 뛰기 \(Format.walkRunMinutes(plan.runMinutes))분 비율")
    }

    private func trainingGuideCard(_ guide: TrainingGuide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            cardHeader(icon: "target", title: "훈련 가이드", code: "COACH",
                       tint: guide.prediction?.tone.color ?? RR.brand,
                       soft: guide.prediction?.tone.softColor ?? RR.brandSoft,
                       info: CardInfoText.guide)

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

            disclaimer("훈련 처방은 기록을 바탕으로 한 참고 정보이며 의학적 조언이 아닙니다.")
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
            ? "지금 흐름이면 \(prediction.race.label)\(prediction.race.objectParticle) \(time)에 들어올 수 있어요"
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

    /// 카드 하단 면책 문구 — 심사 지침 1.4.1은 건강 관련 해석에 의학적 조언이 아님을
    /// 밝히라고 요구한다. 훈련 처방·체력 배터리처럼 행동을 유도하는 카드에 붙인다.
    private func disclaimer(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .lineSpacing(3)
            .foregroundStyle(RR.text3)
            .padding(.top, 12)
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

// MARK: - 지표 설명 팝오버

/// 카드 제목 옆 ⓘ 버튼 — 이 지표가 무엇이고 어떤 값이 좋은지 짧게 설명한다.
/// 자세한 산식은 주간 요약 상세(ReportDetailScreen)에 있으니 여기서는 두세 문장으로 끝낸다.
private struct CardInfoButton: View {
    let title: String
    let text: String
    @State private var isPresented = false

    var body: some View {
        Button { isPresented = true } label: {
            Image(systemName: "info.circle")
                .font(.system(size: 12.5))
                .foregroundStyle(RR.text3)
        }
        .buttonStyle(.plain)
        .popover(isPresented: $isPresented, arrowEdge: .top) {
            VStack(alignment: .leading, spacing: 7) {
                Text(title)
                    .font(.system(size: 14.5, weight: .bold))
                    .foregroundStyle(RR.text)
                Text(text)
                    .font(.system(size: 13))
                    .lineSpacing(4)
                    .foregroundStyle(RR.text2)
            }
            .padding(16)
            .frame(width: 292, alignment: .leading)
            .presentationCompactAdaptation(.popover)
        }
    }
}

/// 카드별 설명 문구 — "무엇을 재는지 → 어떤 값이 좋은지" 순서, 두세 문장 (기획서 §4.11 톤).
/// 산식 기준: 10% 룰·ACWR(Gabbett 2016)·EF·Riegel은 각 엔진 주석과 같은 출처를 따른다.
private enum CardInfoText {
    static let battery = "밤사이 활력징후(심박 변이·안정 심박·심박 회복·수면)와 훈련 부하를 합쳐 오늘 쓸 수 있는 체력을 0~100으로 추정해요. 75 이상 충전 충분, 50~74 양호, 25~49 주의, 그 밑은 방전 임박 — 낮은 날은 훈련보다 충전이 먼저예요."
    static let distance = "최근 7일 거리를 그 전 7일과 비교해요. 한 주 증가 폭은 10% 이내가 안전하다는 경험칙(10% 룰)이 기준 — 그보다 빠르게 늘리면 몸이 적응할 시간이 부족해 부상 위험이 커져요."
    static let acwr = "최근 7일 부하 ÷ 최근 4주 주평균이에요. 지금 훈련량이 몸에 익숙한 양의 몇 배인지 보는 지표로, 0.8~1.3이 적정 구간이에요. 1.3을 넘으면 몸보다 훈련이 앞선 상태, 1.5 초과는 부상 위험 구간이에요."
    static let efficiency = "같은 심박으로 얼마나 빨리 달리는지 — 속도를 심박으로 나눈 값이에요. 최근 2주를 그 전 2주와 비교해요. 절대값보다 방향이 중요해서, 오르고 있으면 같은 힘으로 더 멀리 가는 몸이 되고 있다는 뜻이에요."
    static let vo2Max = "운동 중 몸이 쓸 수 있는 산소의 최대치(mL/kg·분)로, 워치가 야외 러닝에서 추정해요. 지구력의 대표 지표라 높을수록 좋지만 나이·성별에 따라 기준이 달라서, 절대값보다 추세가 오르는지를 봐요. 함께 나오는 심박 회복은 러닝 직후 1분간 심박이 내려간 폭 — 클수록 회복 엔진이 좋은 거예요."
    static let cross = "최근 7일의 러닝 외 운동(자전거·근력 등)을 모아 보여드려요. 러닝 거리 부하(ACWR)에는 넣지 않는 보조 정보지만, 몸의 피로는 같이 쌓이니 회복을 챙길 때는 함께 계산해 주세요."
    static let cadence = "1분에 발이 땅에 닿는 횟수(spm)예요. 최근 2주를 그 전 2주와 비교해요. 보통 170~180 언저리가 접지 충격이 적고 효율적이라고 알려져 있지만 키·보폭에 따라 달라서, 조금씩 오르는 추세면 충분해요."
    static let calories = "이번 주 러닝으로 태운 활동 칼로리의 합계예요. 지난주와 비교해 리듬이 유지되는지 봐요 — 한 번에 몰아서 태우는 것보다 매주 비슷하게 태우는 쪽이 오래갑니다."
    static let weight = "건강 앱의 몸무게 기록을 주 평균으로 묶어 4주 전과 비교해요. 하루 단위 출렁임은 대부분 수분이라 주 평균으로 봐야 진짜 방향이 보여요. 주 0.5kg 안팎의 완만한 감량이 오래가는 페이스예요."
    static let streak = "주 1회 이상 달린 주가 몇 주째 이어지는지 세요. 거리보다 리듬의 지표라, 많이 달리는 것보다 끊기지 않는 게 먼저예요."
    static let guide = "최근 기록으로 목표 레이스 기록을 예측(Riegel 공식)하고 이번 주 훈련 구성을 제안해요. 예측이 목표보다 빠르면 순항 중이고, 느리면 주간 거리부터 차근히 올리면 돼요."
    static let walkRun = "걷기와 뛰기를 번갈아 하며 8주에 걸쳐 뛰는 시간을 늘려가는 입문 프로그램이에요. 총 25분은 그대로 두고 걷는 시간을 뛰는 시간으로 바꿔가요. 이번 주에 몇 번 뛰었는지가 아니라 시작한 지 몇 주가 지났는지로 정해지니, 한 주 쉬어도 처방이 뒤로 밀리지 않아요."
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
                        .font(RR.display(33))
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
                    // 읽기 권한의 허용 여부는 앱이 조회할 수 없다(애플 정책) —
                    // 기록이 있는데도 비어 있는 경우를 대비해 확인 경로를 함께 알려준다
                    Text("러닝 기록은 애플 건강 앱에서 읽어옵니다. 기록이 있는데도 비어 있다면 설정 앱 → 개인정보 보호 및 보안 → 건강 → 런미새에서 읽기 권한을 확인해 주세요.")
                        .font(.system(size: 12.5))
                        .lineSpacing(4)
                        .foregroundStyle(RR.text3)
                        .multilineTextAlignment(.center)
                        .padding(.top, 10)
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
            .rrCard()

            Color.clear
                .frame(height: 96)
                .rrCard()
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
