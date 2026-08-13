import SwiftUI

/// 홈 탭 — 새 성장 스테이지가 주인공 (기획서 v0.7 §6, 시안 1f/1g/1h).
///
/// 첫 화면의 주인공을 지표에서 새로 바꿨다. 글자는 브리핑 1~2문장과 칩 라벨이 전부이고,
/// 상세 지표·차트는 전부 리포트 탭의 일이다. 데이터 가공은 하지 않는다 —
/// XP·단계는 `GrowthEngine`, 브리핑 문장은 `HomeBriefingEngine`, 승급 판정은 `LevelEngine`이 낸다.
struct HomeScreen: View {
    @EnvironmentObject private var health: HealthStore

    @AppStorage(ProfileKey.levelV2) private var levelRaw = RunnerLevel.beginner.rawValue
    /// 주간 목표 — 온보딩 Q5에서 항상 먼저 쓰이므로 이 기본값은 사실상 안전망이다.
    /// 값은 `OnboardingFlowScreen`의 미응답 기본값(2)과 맞춰 둔다
    @AppStorage(ProfileKey.weeklyGoal) private var weeklyGoal = 2
    @AppStorage(ProfileKey.onboardedAt) private var onboardedAtRaw = 0.0
    @AppStorage(ProfileKey.promotionDeclinedAt) private var promotionDeclinedAtRaw = 0.0
    @AppStorage(GrowthKey.cycleStartedAt) private var cycleStartedAtRaw = 0.0
    @AppStorage(GrowthKey.maxStage) private var maxStage = GrowthStage.egg.rawValue
    /// 세러모니에서 수집될 새 종을 정하는 목표 — 사이클 시작 때 정해진 값을 그대로 쓴다
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0

    @EnvironmentObject private var collection: CollectionStore
    @State private var showsCeremony = false

    var body: some View {
        Group {
            if case .loaded(let runs) = health.state {
                content(runs: runs)
            } else {
                // 로딩·실패는 RootView가 이미 분기한다 — 여기서는 배경만 유지한다
                Color.clear
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder
    private func content(runs: [RunSummary]) -> some View {
        let now = Date()
        let growth = GrowthEngine.state(runs: runs,
                                        cycleStartedAt: cycleStartedAt,
                                        maxStage: maxStage,
                                        weeklyGoal: weeklyGoal,
                                        now: now)
        let level = RunnerLevel(rawValue: levelRaw) ?? .beginner
        let promotion = promotionOffer(runs: runs, level: level, now: now)

        VStack(spacing: 0) {
            header(showsCollection: !runs.isEmpty)

            if runs.isEmpty {
                firstLaunchBody(growth: growth)
            } else {
                loadedBody(runs: runs, growth: growth, level: level,
                            promotion: promotion, now: now)
            }
        }
        .onAppear {
            syncMaxStage(growth.stage)
            // 성조에 도달했는데 아직 수집하지 않았다면 세러모니를 띄운다.
            // 판정은 표시 단계로 한다 — XP가 흔들려도 한 번 성조가 됐으면 성조다
            if CollectionEngine.hasReachedAdult(stage: growth.stage) { showsCeremony = true }
        }
        .fullScreenCover(isPresented: $showsCeremony) {
            CeremonyScreen(species: pendingSpecies,
                            goalLabel: pendingGoalLabel,
                            cycleStartedAt: cycleStartedAt) { newGoal, newSeconds in
                startNewCycle(goal: newGoal, goalSeconds: newSeconds, now: Date())
            }
        }
    }

    /// 지금 수집될 새 종 — 세러모니 표시와 실제 수집이 같은 값을 쓰도록 한 곳에서 낸다
    private var pendingSpecies: BirdSpecies {
        CollectionEngine.species(for: RaceDistance(rawValue: raceGoalRaw),
                                  goalSeconds: raceGoalSec)
    }

    private var pendingGoalLabel: String {
        CollectionEngine.goalLabel(for: RaceDistance(rawValue: raceGoalRaw),
                                    goalSeconds: raceGoalSec)
    }

    // MARK: - 헤더

    private func header(showsCollection: Bool) -> some View {
        HStack {
            Text("런미새")
                .font(RR.display(18))
                .foregroundStyle(RR.text)
            Spacer()
            if showsCollection {
                NavigationLink {
                    CollectionScreen()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "book.closed")
                            .font(.system(size: 11, weight: .semibold))
                        Text("도감")
                            .font(.system(size: 12, weight: .semibold))
                    }
                    .foregroundStyle(RR.text)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 7)
                    .background(RR.surface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(RR.line))
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    // MARK: - 첫 실행 (시안 1g)

    /// 기록이 없을 때 — 알과 안내 문장만. 브리핑·칩은 통째로 감춘다 (미노출 가드).
    private func firstLaunchBody(growth: GrowthState) -> some View {
        VStack(spacing: 0) {
            Spacer(minLength: 12)

            BirdView(stage: growth.stage, isSulky: false)
                .frame(width: 212, height: 212)

            stageName(growth: growth)
                .padding(.top, 6)
            XpGauge(progress: growth.progress)
                .padding(.top, 14)
            xpText(growth: growth)
                .padding(.top, 10)

            Text("첫 러닝을 기다리고 있어요. 알도 기다리고 있습니다.")
                .font(.system(size: 15))
                .lineSpacing(15 * 0.6)
                .multilineTextAlignment(.center)
                .foregroundStyle(RR.text2)
                .padding(16)
                .frame(maxWidth: 300)
                .rrCard()
                .padding(.top, 26)

            Spacer(minLength: 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 20)
    }

    // MARK: - 기록 있음 (시안 1f / 1h)

    @ViewBuilder
    private func loadedBody(runs: [RunSummary], growth: GrowthState, level: RunnerLevel,
                            promotion: RunnerLevel?, now: Date) -> some View {
        let report = ReportEngine(now: now, level: level).weeklyReport(from: runs)
        let battery = health.vitals.flatMap { BatteryEngine.compute(vitals: $0, runs: runs, now: now) }
        let briefing = HomeBriefingEngine.briefing(runs: runs,
                                                   growth: growth,
                                                   report: report,
                                                   battery: battery,
                                                   weeklyGoal: weeklyGoal,
                                                   now: now)
        // 승급 카드가 뜨면 새를 216 → 172로 줄여 카드 자리를 만든다 (시안 1h)
        let birdSize: CGFloat = promotion == nil ? 216 : 172

        ScrollView {
            VStack(spacing: 0) {
                BirdView(stage: growth.stage, isSulky: growth.isSulky)
                    .frame(width: birdSize, height: birdSize)

                stageName(growth: growth)
                    .padding(.top, 6)
                XpGauge(progress: growth.progress)
                    .padding(.top, 14)
                xpText(growth: growth)
                    .padding(.top, 10)

                if let promotion {
                    PromotionCard(target: promotion,
                                  evidence: promotionEvidence(runs: runs, now: now),
                                  onAccept: { accept(promotion) },
                                  onDecline: { decline(now: now) })
                        .padding(.top, 20)
                }

                if let briefing {
                    Text(briefing)
                        .font(.system(size: 14.5))
                        .lineSpacing(14.5 * 0.55)
                        .foregroundStyle(RR.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(15)
                        .rrCard()
                        .padding(.top, 18)
                }

                chipRow(runs: runs, now: now)
                    .padding(.top, 10)
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 24)
        }
        .refreshable { await health.load() }
    }

    // MARK: - 스테이지 텍스트

    /// "{이름} · {N}단계[ · 시무룩]" — 이름만 디스플레이 서체, 나머지는 본문 서체 한 줄.
    /// 여기서 쓰는 이름은 지금 단계 라벨이다 (종 이름 — 참새·제비 — 은 §5 도감 범위).
    private func stageName(growth: GrowthState) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(growth.stage.label)
                .font(RR.display(23))
                .foregroundStyle(RR.text)
            Text(" · \(growth.stage.rawValue)단계" + (growth.isSulky ? " · 시무룩" : ""))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.text3)
        }
    }

    private func xpText(growth: GrowthState) -> some View {
        Text(growth.xpToNextStage.map { "다음 단계까지 \($0) XP" } ?? "성조 도달 — 세러모니가 기다려요")
            .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
            .kerning(0.46)  // 시안 letter-spacing .04em × 11.5px
            .foregroundStyle(RR.text2)
    }

    // MARK: - 칩 2개

    private func chipRow(runs: [RunSummary], now: Date) -> some View {
        HStack(spacing: 10) {
            if let last = runs.max(by: { $0.start < $1.start }) {
                NavigationLink {
                    SessionDetailScreen(run: last)
                } label: {
                    lastRunChip(run: last, now: now)
                }
                .buttonStyle(.plain)
            }
            weeklyGoalChip(runs: runs, now: now)
        }
    }

    private func lastRunChip(run: RunSummary, now: Date) -> some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 5) {
                Text(relativeRunLabel(run: run, now: now))
                    .font(.system(size: 11))
                    .foregroundStyle(RR.text3)
                Text(runValueLine(run: run))
                    .font(.system(size: 14.5, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(RR.text)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(RR.text3)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func weeklyGoalChip(runs: [RunSummary], now: Date) -> some View {
        let done = weekRunCount(runs: runs, now: now)
        return VStack(alignment: .leading, spacing: 5) {
            Text("이번 주 목표")
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            HStack(spacing: 8) {
                Text("\(done) / \(weeklyGoal)회")
                    .font(.system(size: 14.5, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(RR.text)
                GoalDots(total: weeklyGoal, done: done)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    /// "어제 러닝" / "7일 전 러닝" — 시안 1f는 상대 표기를 쓴다
    private func relativeRunLabel(run: RunSummary, now: Date) -> String {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let days = calendar.dateComponents([.day],
                                           from: calendar.startOfDay(for: run.start),
                                           to: calendar.startOfDay(for: now)).day ?? 0
        switch days {
        case ..<1: return "오늘 러닝"
        case 1: return "어제 러닝"
        default: return "\(days)일 전 러닝"
        }
    }

    /// "5.2km · 5′41″/km" — 페이스를 낼 수 없을 만큼 짧으면 거리만 (미노출 가드)
    private func runValueLine(run: RunSummary) -> String {
        guard let km = run.distanceKm else { return Format.duration(run.durationSec) }
        guard let pace = run.paceSecPerKm else { return "\(Format.km(km))km" }
        return "\(Format.km(km))km · \(Format.paceKm(pace))"
    }

    private func weekRunCount(runs: [RunSummary], now: Date) -> Int {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        guard let week = calendar.dateInterval(of: .weekOfYear, for: now) else { return 0 }
        return runs.filter { $0.start >= week.start && $0.start <= now }.count
    }

    // MARK: - 승급 제안 (기획서 §3, 시안 1h)

    /// 실데이터 승급 후보. 거절한 지 4주가 안 지났으면 다시 묻지 않는다.
    private func promotionOffer(runs: [RunSummary], level: RunnerLevel, now: Date) -> RunnerLevel? {
        if promotionDeclinedAtRaw > 0 {
            let declined = Date(timeIntervalSince1970: promotionDeclinedAtRaw)
            guard now.timeIntervalSince(declined) >= 28 * 86_400 else { return nil }
        }
        return LevelEngine.promotionCandidate(current: level, runs: runs, now: now)
    }

    /// 승급 근거가 된 세션 — 카드 본문의 "지난주 10km를 59분에" 자리를 실제 값으로 채운다.
    /// LevelEngine의 판정 조건(최근 4주 · 10km 이상 · 60분 이내)과 같은 창을 본다.
    private func promotionEvidence(runs: [RunSummary], now: Date) -> RunSummary? {
        let fourWeeksAgo = now.addingTimeInterval(-28 * 86_400)
        return runs
            .filter { $0.start >= fourWeeksAgo && $0.start <= now }
            .filter { ($0.distanceKm ?? 0) >= 10 && $0.durationSec <= 3_600 }
            .max { $0.start < $1.start }
    }

    private func accept(_ level: RunnerLevel) {
        levelRaw = level.rawValue
        // 수락했으면 거절 기록은 의미가 없다 — 지워 둔다
        promotionDeclinedAtRaw = 0
    }

    private func decline(now: Date) {
        promotionDeclinedAtRaw = now.timeIntervalSince1970
    }

    // MARK: - 저장값 보정

    /// 사이클 시작 시각 — 미설정(0)이면 온보딩 시각, 그마저 없으면 먼 과거로 둔다.
    /// 먼 과거를 쓰는 이유: 기록 전체를 XP로 세는 편이, 사이클을 오늘로 잡아 XP를 0으로
    /// 리셋해 버리는 것보다 "성장은 되돌리지 않는다" 원칙에 맞다.
    private var cycleStartedAt: Date {
        if cycleStartedAtRaw > 0 { return Date(timeIntervalSince1970: cycleStartedAtRaw) }
        if onboardedAtRaw > 0 { return Date(timeIntervalSince1970: onboardedAtRaw) }
        return .distantPast
    }

    /// 이번 사이클 최고 단계를 올려 둔다 — 다음 실행에서 표시 단계가 내려가지 않게 하는 하한.
    private func syncMaxStage(_ stage: GrowthStage) {
        if stage.rawValue > maxStage { maxStage = stage.rawValue }
    }

    /// 수집 확정 — 도감에 넣고 새 사이클을 시작한다 (기획서 §5).
    ///
    /// 사이클 전환의 **유일한 지점**이다. 순서가 중요하다: 도감에 먼저 넣고
    /// 사이클을 초기화한다 — 반대로 하면 저장에 실패했을 때 새를 잃는다.
    /// `cycleStartedAt`을 지금으로 옮기면 XP는 자동으로 0부터 다시 쌓인다
    /// (XP 원장을 저장하지 않는 설계라 리셋할 값이 따로 없다).
    private func startNewCycle(goal: RaceDistance?, goalSeconds: Int, now: Date) {
        collection.add(CollectionEngine.collect(distance: RaceDistance(rawValue: raceGoalRaw),
                                                 goalSeconds: raceGoalSec,
                                                 cycleStartedAt: cycleStartedAt,
                                                 now: now))
        raceGoalRaw = goal?.rawValue ?? ""
        raceGoalSec = goalSeconds
        cycleStartedAtRaw = now.timeIntervalSince1970
        maxStage = GrowthStage.egg.rawValue
    }
}

// MARK: - 하위 뷰

/// XP 게이지 — 시안 210×8, radius 4, surface2 바탕 + line 테두리, 안쪽 brand 채움
private struct XpGauge: View {
    let progress: Double

    var body: some View {
        let clamped = min(1, max(0, progress))
        RoundedRectangle(cornerRadius: 4, style: .continuous)
            .fill(RR.surface2)
            .frame(width: 210, height: 8)
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(RR.brand)
                    .frame(width: 210 * clamped)
            }
            .overlay(RoundedRectangle(cornerRadius: 4, style: .continuous).strokeBorder(RR.line))
            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

/// 주간 목표 점 — 달성 brand 채움 / 미달 surface2 + line 테두리 (시안 7×7 radius 4)
private struct GoalDots: View {
    let total: Int
    let done: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<max(0, total), id: \.self) { index in
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(index < done ? RR.brand : RR.surface2)
                    .frame(width: 7, height: 7)
                    .overlay {
                        if index >= done {
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .strokeBorder(RR.line)
                        }
                    }
            }
        }
    }
}

/// 승급 제안 카드 (시안 1h) — 1회 노출, 거절하면 4주 뒤에 다시 묻는다.
/// 승급만 있고 강등은 없다 ("성장은 되돌리지 않는다", 기획서 §3).
private struct PromotionCard: View {
    let target: RunnerLevel
    /// 승급 근거 세션 — 없으면 거리·기록 문장을 생략한 짧은 본문으로 대체한다
    let evidence: RunSummary?
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("승급 제안")
                .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                .kerning(1.26)  // 시안 letter-spacing .12em × 10.5px
                .foregroundStyle(RR.brand)

            Text(body(for: evidence))
                .font(.system(size: 14.5))
                .lineSpacing(14.5 * 0.55)
                .foregroundStyle(RR.text)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                Button(action: onAccept) {
                    Text("좋아요, 승급할게요")
                        .font(.system(size: 13.5, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(RR.brand, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                }
                .buttonStyle(.plain)

                Button(action: onDecline) {
                    Text("지금은 괜찮아요")
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(RR.text)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(RR.surface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(RR.line))
                }
                .buttonStyle(.plain)
            }

            Text("괜찮다고 하시면 4주 동안 다시 묻지 않아요")
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
        }
        .padding(15)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RR.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(RR.brand, lineWidth: 1.5))
    }

    /// 시안 문구를 실제 값으로 채운 템플릿 — 레벨명만 볼드로 강조한다 (AttributedString 합성)
    private func body(for run: RunSummary?) -> AttributedString {
        var level = AttributedString(target.label)
        level.font = .system(size: 14.5, weight: .bold)

        guard let run, let km = run.distanceKm else {
            return AttributedString("최근 기록을 보니 한 단계 올려도 되겠어요. 리포트를 ")
                + level + AttributedString(" 수준으로 올려드릴까요?")
        }
        let period = relativePeriod(of: run)
        let lead = "\(period) \(Format.km(km))km를 \(Format.duration(run.durationSec))에 달리셨더라고요. 리포트를 "
        return AttributedString(lead) + level + AttributedString(" 수준으로 올려드릴까요?")
    }

    /// "지난주" / "이번 주" / "3주 전" — 근거 세션이 언제였는지 앞머리
    private func relativePeriod(of run: RunSummary) -> String {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let weeks = calendar.dateComponents([.weekOfYear],
                                            from: calendar.startOfDay(for: run.start),
                                            to: calendar.startOfDay(for: .now)).weekOfYear ?? 0
        switch weeks {
        case ..<1: return "이번 주"
        case 1: return "지난주"
        default: return "\(weeks)주 전"
        }
    }
}
