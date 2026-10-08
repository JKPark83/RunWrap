import SwiftUI

/// 홈 탭 — 새 성장 스테이지와 **오늘의 판단 카드** (기획서 v0.8 §6, 시안 1f/1g/1h).
///
/// 새가 첫 화면의 주인공이고, 그 아래가 "오늘 뛸까 말까"에 답하는 판단 카드다.
/// 상세 지표·차트는 전부 리포트 탭의 일이다. 데이터 가공은 하지 않는다 —
/// XP·단계는 `GrowthEngine`, 오늘의 판단은 `TodayVerdictEngine`, 승급 판정은 `LevelEngine`이 낸다.
///
/// 날씨(위치)는 `WeatherStore`가 기동 시점에 조회를 마치고 내려준다 — 홈이 뜨기 전에
/// 스플래시가 그 완료를 기다리는 구조라, 여기서는 읽기만 하고 로딩을 시작하지 않는다.
struct HomeScreen: View {
    /// 판단 카드의 배터리·권장 세션 줄에서 리포트 탭으로 넘어가는 통로 (탭 전환은 RootView 몫)
    var onSelectReport: () -> Void = {}

    @EnvironmentObject private var health: HealthStore
    @EnvironmentObject private var weather: WeatherStore
    /// 홈 날씨 타일의 미세·초미세 등급 재료 — 좌표는 WeatherStore의 위치 결론을 같이 쓴다.
    /// '오늘' 시트의 스토어와 별개 인스턴스지만 1시간 디스크 캐시를 공유해 중복 조회는 없다
    @StateObject private var airQuality = AirQualityStore()
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    @State private var showsToday = false
    @State private var showsLastRun = false
    /// 날씨 타일 재시도 중 — refresh()는 .loading을 거치지 않아 타일에 진행을 따로 알리고 연타를 막는다
    @State private var retryingWeather = false

    @AppStorage(ProfileKey.levelV2) private var levelRaw = RunnerLevel.beginner.rawValue
    /// 주간 목표 — 온보딩 Q5에서 항상 먼저 쓰이므로 이 기본값은 사실상 안전망이다.
    /// 값은 `OnboardingFlowScreen`의 미응답 기본값(2)과 맞춰 둔다
    @AppStorage(ProfileKey.weeklyGoal) private var weeklyGoal = 2
    /// 주간 목표 변경 이력 (이슈 #108, #116) — 설정·재진단이 기록한다. 바뀌면 다시 그리도록 관찰한다
    @AppStorage(ProfileKey.weeklyGoalChanges) private var weeklyGoalChangesData: Data?
    @AppStorage(ProfileKey.onboardedAt) private var onboardedAtRaw = 0.0
    @AppStorage(ProfileKey.promotionDeclinedAt) private var promotionDeclinedAtRaw = 0.0
    @AppStorage(GrowthKey.cycleStartedAt) private var cycleStartedAtRaw = 0.0
    @AppStorage(GrowthKey.maxStage) private var maxStage = GrowthStage.egg.rawValue
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0
    /// 세러모니에서 수집될 새 종을 정하는 목표 — 사이클 시작 때 고정한 값 (이슈 #110).
    /// 옵셔널인 이유: 키가 없는(도입 전) 사용자를 가려 현재 목표로 한 번 보정하기 위해서다
    @AppStorage(GrowthKey.cycleGoal) private var cycleGoalRaw: String?
    @AppStorage(GrowthKey.cycleGoalSec) private var cycleGoalSecRaw: Int?
    @AppStorage(GrowthKey.deferredSpecies) private var deferredSpeciesRaw = ""
    @AppStorage(ProfileKey.raceDate) private var raceDateRaw = 0.0

    @EnvironmentObject private var collection: CollectionStore
    /// 성장 상태가 바뀌는 지점(단계 상승·사이클 전환·승급)에서 CloudKit 스냅샷을 갱신한다 (이슈 #29)
    @EnvironmentObject private var backup: ProgressBackupStore
    @State private var showsCeremony = false
    /// 수집 확정 때 도감 저장이 실패했는지 — 세러모니 위에 알림을 띄운다 (이슈 #67)
    @State private var showsCollectFailed = false
    // PB 축하 (이슈 #21) — 홈 진입 때 베이스라인과 비교해 새 기록이면 한 번만 띄운다
    @State private var showsPBCongrats = false
    @State private var newPBs: [PersonalRecords.Entry] = []
    // 결산 리캡 (이슈 #167) — 월초·연말연초 홈 카드. 열어 보거나 X를 누른 기간은 다시 띄우지 않는다
    @AppStorage(RecapKey.dismissedMonth) private var recapDismissedMonth = ""
    @AppStorage(RecapKey.dismissedYear) private var recapDismissedYear = ""
    @State private var recapPeriod: RecapPeriod?
    // 러닝화 카드 (이슈 #171, #206) — 판단 카드 아래에 상시 노출. 교체 안내도 이 카드의 각 행이 맡는다
    @EnvironmentObject private var shoes: ShoeStore
    @State private var editingShoe: Shoe?
    @State private var editingShoeIsNew = false
    // 러닝 후 러닝화 묻기 팝업 (이슈 #206) — 기준 시각 이후의 새 러닝을 카드로 넘기며 고른다
    @AppStorage(ShoeKey.promptedThrough) private var shoePromptedThrough = 0.0
    @AppStorage(ShoeKey.promptOptOut) private var shoePromptOptOut = false
    @State private var showsShoePrompt = false
    /// 띄울 때 고정한 러닝 목록 — 배정이 바뀌어도 시트 아래에서 카드가 바뀌지 않게 한다
    @State private var shoePromptRuns: [RunSummary] = []
    // 목표 대회 카드 (이슈 #172) — 대회 상세의 '목표 대회로 지정'이 정한 대회. 목록은 루트의 RaceStore
    @EnvironmentObject private var raceStore: RaceStore
    @AppStorage(RaceKey.targetID) private var targetRaceID = 0
    @State private var openedTargetRace: RaceEngine.Entry?

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
        .onAppear { migrateCycleGoalIfNeeded() }
        .sheet(isPresented: $showsToday) { todaySheet }
        // 구독 시점의 현재 좌표부터 흘러온다 — 첫 노출과 늦은 위치 결론(스플래시 타임아웃 경로)을 한 줄로 처리
        .onReceive(weather.$coordinate) { coordinate in
            guard let coordinate else { return }
            Task {
                await airQuality.load(latitude: coordinate.latitude,
                                      longitude: coordinate.longitude)
            }
        }
        // 포그라운드 복귀 — load()는 첫 조회만 하므로 낡은 대기질은 여기서 갱신한다 (이슈 #69).
        // 좌표는 직전 위치 결론이다 — 같은 순간 루트가 날씨를 새로 받는 중이어도 측정소가
        // 바뀔 만큼 이동한 경우가 아니면 결과가 같고, 다음 복귀·당겨서 새로고침이 따라잡는다
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, let coordinate = weather.coordinate else { return }
            Task {
                await airQuality.refreshIfStale(latitude: coordinate.latitude,
                                                longitude: coordinate.longitude)
            }
        }
        // 대기질은 위젯 스냅샷($vitals 신호)보다 늦게 도착한다 — 등급이 정해지면 위젯을 다시 발행해
        // 홈 판정과 위젯 문구가 어긋나지 않게 한다 (이슈 #195). health.vitals는 대개 이미 채워져 있고,
        // 아직이면 뒤이은 $vitals 신호가 캐시 등급(cachedFreshGrade)으로 다시 쓴다
        .onChange(of: loadedAir.flatMap(AirQualityEngine.representativeGrade)) { _, grade in
            guard let grade else { return }
            RunWrapApp.publishWidgetSnapshot(health: health, vitals: health.vitals, air: grade)
        }
    }

    /// '오늘'은 탭에서 시트로 내려왔다 — 날씨 줄을 눌렀을 때만 펼친다 (기획서 v0.8 §6)
    private var todaySheet: some View {
        NavigationStack {
            TodayScreen()
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("닫기") { showsToday = false }
                    }
                }
        }
    }

    /// 날씨 타일에 얹을 대기질 — 값이 있을 때만. 로딩·실패는 자리조차 만들지 않는다 (미노출 가드)
    private var loadedAir: AirQuality? {
        if case .loaded(let air) = airQuality.state { air } else { nil }
    }

    /// 스토어의 위치·네트워크 상태를 엔진이 아는 값으로 접는다 (엔진은 둘 다 모른다)
    private var weatherInput: TodayVerdictEngine.WeatherInput {
        switch weather.state {
        case .idle, .loading: .loading
        case .loaded(let current):
            .current(current, windows: RunWindowEngine.windows(hourly: current.hourly, now: Date()))
        case .denied: .denied
        case .unavailable: .unavailable
        }
    }

    @ViewBuilder
    private func content(runs: [RunSummary]) -> some View {
        let now = Date()
        let growth = GrowthEngine.state(runs: runs,
                                        cycleStartedAt: cycleStartedAt,
                                        maxStage: maxStage,
                                        weeklyGoal: weeklyGoal,
                                        weeklyGoalChanges: weeklyGoalChanges,
                                        now: now)
        let level = RunnerLevel(rawValue: levelRaw) ?? .beginner
        let promotion = promotionOffer(runs: runs, level: level, now: now)

        VStack(spacing: 0) {
            header(showsCollection: !runs.isEmpty)
                // 고정 헤더 — 아래 스크롤 본문이 헤더·상태바 뒤로 비치지 않게 바탕을 깔고 위에 둔다 (이슈 #211)
                .background(RR.bg)
                .zIndex(1)

            if runs.isEmpty {
                firstLaunchBody(growth: growth)
            } else {
                loadedBody(runs: runs, growth: growth, level: level,
                            promotion: promotion, now: now)
            }
        }
        .onAppear {
            syncStage(growth.stage, runs: runs)
            checkNewPBs(runs: runs)
            // 세러모니·PB 다음 순서 — 둘 중 하나가 막 떴으면 그쪽이 닫힌 뒤 다시 부른다 (이슈 #206)
            checkNewRunsForShoe(runs: runs)
        }
        // 포그라운드 복귀·당겨서 새로고침으로 새 러닝이 들어오면 홈이 이미 떠 있어 onAppear가 다시 불리지 않는다 (이슈 #206)
        .onChange(of: runs.map(\.id)) { _, _ in
            syncStage(growth.stage, runs: runs)   // 세러모니가 먼저 — 같은 갱신에서 단계가 올랐으면 팝업이 양보한다
            checkNewRunsForShoe(runs: runs)
        }
        // 포그라운드 복귀·당겨서 새로고침으로 단계가 오르면 홈이 이미 떠 있어 onAppear가 다시 불리지 않는다 —
        // 단계 변화에도 같은 기록·백업·세러모니를 건다 (이슈 #60)
        .onChange(of: growth.stage) { _, newStage in
            syncStage(newStage, runs: runs)
        }
        // 베스트 에포트 백필이 끝나면(남은 개수 0) 미뤄 둔 PB 감지를 다시 건다 (이슈 #166)
        .onChange(of: health.bestEffortPending) { _, pending in
            if pending == 0 {
                checkNewPBs(runs: runs)
                checkNewRunsForShoe(runs: runs)   // 백필을 기다리던 러닝화 팝업 — PB가 떴으면 그 뒤로 미뤄진다
            }
        }
        .fullScreenCover(isPresented: $showsCeremony, onDismiss: {
            // 세러모니 → PB → 러닝화 순서 (이슈 #206)
            checkNewPBs(runs: runs)
            checkNewRunsForShoe(runs: runs)
        }) {
            let earned = pendingBird(runs: runs)
            CeremonyScreen(species: earned.species,
                            goalLabel: earned.label,
                            cycleStartedAt: cycleStartedAt,
                            cycleGoal: cycleGoal,
                            cycleGoalSeconds: cycleGoalSec,
                            currentGoal: RaceDistance(rawValue: raceGoalRaw),
                            currentGoalSeconds: raceGoalSec,
                            onLater: { deferredSpeciesRaw = earned.species.rawValue }) { newGoal, newSeconds in
                startNewCycle(runs: runs, goal: newGoal, goalSeconds: newSeconds, now: Date())
            }
            // 세러모니는 저장 실패 시 닫히지 않으므로 알림도 그 위에 건다 — 홈에 걸면 커버에 가려진다
            .alert("도감에 담지 못했어요", isPresented: $showsCollectFailed) {
                Button("확인", role: .cancel) {}
            } message: {
                Text("저장 공간을 확인한 뒤 다시 시도해 주세요. 새는 그대로 기다리고 있어요.")
            }
        }
        .sheet(isPresented: $showsPBCongrats, onDismiss: { checkNewRunsForShoe(runs: runs) }) {
            PBCongratsSheet(entries: newPBs)
        }
        // 닫히면(확인·스와이프) 마지막 카드의 러닝까지 물어본 것으로 기준을 올린다 —
        // 팝업에 밀려 미뤄 둔 PB 축하가 있으면 이어서 다시 건다 (이슈 #206)
        .sheet(isPresented: $showsShoePrompt, onDismiss: {
            if let latest = shoePromptRuns.last?.start.timeIntervalSince1970, latest > shoePromptedThrough {
                shoePromptedThrough = latest
            }
            checkNewPBs(runs: runs)
        }) {
            RunShoePromptSheet(runs: shoePromptRuns, onOptOut: { shoePromptOptOut = true })
                .environmentObject(shoes)
                .environmentObject(health)
        }
    }

    /// 지금 수집될 새 종과 근거 기록 — 세러모니 표시와 실제 수집이 같은 판정을 쓴다.
    /// 목표가 아니라 이번 사이클에 실제로 달린 기록으로 정한다
    private func pendingBird(runs: [RunSummary]) -> (species: BirdSpecies, label: String) {
        CollectionEngine.earned(runs: runs, since: cycleStartedAt)
    }

    // MARK: - 헤더

    private func header(showsCollection: Bool) -> some View {
        HStack(spacing: 8) {
            Text("런미새")
                .font(RR.display(18))
                .foregroundStyle(RR.text)
            Spacer()
            if showsCollection {
                NavigationLink {
                    CollectionScreen()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "book.closed")
                            .font(.system(size: 15, weight: .semibold))
                        Text("도감")
                            .font(.system(size: 15, weight: .semibold))
                    }
                    .foregroundStyle(RR.text)
                    .padding(.horizontal, 14)
                    .frame(height: 40)
                    .background(RR.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(RR.line))
                    .contentShape(Rectangle())
                }
            }
            // 설정 진입 — 리포트 탭까지 가지 않아도 홈에서 바로 연다 (도감과 같은 필 스타일)
            NavigationLink {
                SettingsScreen()
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .frame(width: 40, height: 40)
                    .background(RR.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(RR.line))
                    .contentShape(Rectangle())
                    .rrTapTarget()
            }
            .buttonStyle(.plain)  // 기본 스타일은 라벨 밖으로 넓힌 탭 영역을 받지 않는다 (이슈 #212)
            .accessibilityLabel("설정")
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
                            promotion: PromotionEvidence?, now: Date) -> some View {
        let battery = health.vitals.flatMap { BatteryEngine.compute(vitals: $0, runs: runs, now: now) }
        let verdict = TodayVerdictEngine.verdict(runs: runs,
                                                 battery: battery,
                                                 weather: weatherInput,
                                                 guide: trainingGuide(runs: runs, level: level,
                                                                      batteryTone: battery?.tone,
                                                                      now: now),
                                                 hasRaceGoal: RaceDistance(rawValue: raceGoalRaw) != nil,
                                                 weeklyGoal: weeklyGoal,
                                                 level: level,
                                                 air: loadedAir.flatMap(AirQualityEngine.representativeGrade),
                                                 now: now)
        // 시안(1f) 216보다 작게 — 아래 카드가 첫 화면에 더 올라오도록 새·단계 영역을 줄였다.
        // 승급 카드가 뜨면 한 번 더 줄여 카드 자리를 만든다 (시안 1h)
        let birdSize: CGFloat = promotion == nil ? 152 : 124

        ScrollView {
            VStack(spacing: 0) {
                BirdView(stage: growth.stage, isSulky: growth.isSulky)
                    .frame(width: birdSize, height: birdSize)

                stageName(growth: growth)
                XpGauge(progress: growth.progress)
                    .padding(.top, 10)
                xpText(growth: growth)
                    .padding(.top, 8)

                if let promotion {
                    PromotionCard(evidence: promotion,
                                  onAccept: { accept(promotion.target) },
                                  onDecline: { decline(now: now) })
                        .padding(.top, 14)
                }

                if let verdict {
                    VerdictCard(verdict: verdict, battery: battery, weather: weatherInput,
                                air: loadedAir, retryingWeather: retryingWeather) { kind in
                        tap(kind, runs: runs)
                    }
                    .padding(.top, 14)
                }

                // 매일·매주 바뀌는 칩을 러닝화 카드보다 먼저 — 러닝화 카드는 켤레 수만큼 길어져
                // 아래에 두면 칩이 첫 화면 밖으로 밀린다
                chipRow(runs: runs, now: now)
                    .padding(.top, 10)

                // 러닝화 (이슈 #206) — 신발이 없으면 '다시 보지 않기' 전까지만 등록 권유로 보인다
                if !shoes.shoes.isEmpty || !shoePromptOptOut {
                    HomeShoeCard(runs: runs) { shoe, isNew in
                        editingShoeIsNew = isNew
                        editingShoe = shoe
                    }
                    .padding(.top, 10)
                }

                // 목표 대회 (이슈 #172) — 대회 목록에 있고 대회일이 지나지 않았을 때만
                if let target = targetRace(now: now) {
                    TargetRaceCard(entry: target) { openedTargetRace = target }
                        .padding(.top, 10)
                }

                ForEach(recapPrompts(runs: runs, now: now)) { period in
                    RecapPromptCard(title: recapPromptTitle(period, now: now),
                                    subtitle: RecapEngine.periodLabel(period) + " 결산이 준비됐어요",
                                    onOpen: {
                                        dismissRecap(period)
                                        recapPeriod = period
                                    },
                                    onDismiss: { dismissRecap(period) })
                        .padding(.top, 10)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .refreshable {
            // 건강 데이터와, 위치부터 다시 잡은 날씨·대기질을 함께 갱신한다.
            // 대기질은 새 위치 결론이 재료라 날씨 뒤에 순서대로, 건강 데이터만 병렬로.
            //
            // 비구조 Task로 감싸는 이유: 갱신이 publish하는 상태 변화가 재렌더를 부르고,
            // SwiftUI는 그때 refreshable 액션 태스크를 취소해 버린다(수십 ms 만에) —
            // 위치 결론 대기가 잘려 새 위치가 반영되지 않았다. Task는 취소를 상속하지
            // 않으므로 작업이 끝까지 돌고, 스피너는 .value 대기가 풀릴 때 내려간다
            await Task {
                async let healthReload: Void = health.load()
                await weather.refresh()
                if let coordinate = weather.coordinate {
                    await airQuality.refresh(latitude: coordinate.latitude,
                                             longitude: coordinate.longitude)
                }
                await healthReload
            }.value
        }
        .navigationDestination(isPresented: $showsLastRun) {
            if let last = runs.max(by: { $0.start < $1.start }) {
                SessionDetailScreen(run: last)
            }
        }
        .sheet(item: $recapPeriod) { period in
            RecapScreen(period: period)
        }
        // 러닝화 등록·편집 (이슈 #206) — 설정과 같은 시트
        .sheet(item: $editingShoe) { shoe in
            ShoeEditSheet(shoe: shoe, isNew: editingShoeIsNew,
                          isDefault: editingShoeIsNew ? shoes.defaultShoeID == nil : shoes.defaultShoeID == shoe.id,
                          onSave: { shoes.save($0, isDefault: $1, runs: runs) },
                          onDelete: { shoes.remove(shoe) })
        }
        // 목표 대회 카드 재료 — 대회 탭을 열지 않았어도 목표가 있으면 목록을 불러온다 (이슈 #172)
        .task(id: targetRaceID) {
            if targetRaceID != 0 { await raceStore.load() }
        }
        .sheet(item: $openedTargetRace) { entry in
            NavigationStack {
                RaceDetailScreen(entry: entry)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("닫기") { openedTargetRace = nil }
                        }
                    }
            }
            .environmentObject(raceStore)
        }
    }

    // MARK: - 목표 대회 (이슈 #172)

    /// 목표 대회 항목 — 목록에 없거나 대회일이 지났으면(entries가 이미 뺀다) nil → 카드 미노출
    private func targetRace(now: Date) -> RaceEngine.Entry? {
        guard targetRaceID != 0, case .loaded(let file) = raceStore.state else { return nil }
        return RaceEngine.entries(from: file.races.filter { $0.id == targetRaceID }, now: now).first
    }

    // MARK: - 결산 리캡 (이슈 #167)

    /// 노출할 결산 카드 — 날짜·닫힘 판정은 엔진, 기록 3회 미만 기간은 열어 봐야 빈 화면이라 뺀다
    private func recapPrompts(runs: [RunSummary], now: Date) -> [RecapPeriod] {
        RecapEngine.promptKinds(now: now, dismissedMonth: recapDismissedMonth,
                                dismissedYear: recapDismissedYear)
            .filter { RecapEngine.hasEnoughRuns($0, runs: runs) }
    }

    /// "지난달 결산 보기" / "올해 결산 보기" / "지난해 결산 보기"(1월 1~7일)
    private func recapPromptTitle(_ period: RecapPeriod, now: Date) -> String {
        switch period {
        case .month:
            return "지난달 결산 보기"
        case .year(let date):
            let calendar = Calendar.current
            return calendar.component(.year, from: date) == calendar.component(.year, from: now)
                ? "올해 결산 보기" : "지난해 결산 보기"
        }
    }

    /// 열어 보거나 닫으면 그 기간 키를 남긴다 — 다음 진입부터 카드가 뜨지 않는다
    private func dismissRecap(_ period: RecapPeriod) {
        let key = RecapEngine.dismissKey(for: period)
        switch period {
        case .month: recapDismissedMonth = key
        case .year: recapDismissedYear = key
        }
    }

    /// 네 줄의 목적지 — 재료를 만든 화면으로 보낸다 (기획서 v0.8 §6).
    /// 배터리·권장 세션은 둘 다 리포트 탭의 카드라 같은 곳으로 간다.
    private func tap(_ kind: TodayVerdict.Line.Kind, runs: [RunSummary]) {
        switch kind {
        case .battery, .session:
            onSelectReport()
        case .weather:
            // 권한을 거부한 상태에서는 앱 안에서 다시 물을 수 없다 — 설정으로 보낸다
            if case .denied = weather.state {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            } else if case .unavailable = weather.state {
                // 조회 실패 — 열어 봐야 같은 실패 카드라 그 자리에서 다시 불러온다.
                // 대기질은 새 좌표가 오면 onReceive(weather.$coordinate)가 따라 채운다
                guard !retryingWeather else { return }
                retryingWeather = true
                Task {
                    await weather.refresh()
                    retryingWeather = false
                    if case .unavailable = weather.state {
                        AccessibilityNotification.Announcement("날씨를 다시 불러오지 못했어요").post()
                    }
                }
            } else {
                showsToday = true
            }
        case .recovery:
            showsLastRun = true
        }
    }

    /// 주간 처방 — 리포트 탭과 같은 방식으로 만든다 (목표 레이스가 없으면 nil)
    private func trainingGuide(runs: [RunSummary], level: RunnerLevel,
                               batteryTone: RRTone?, now: Date) -> TrainingGuide? {
        guard let race = RaceDistance(rawValue: raceGoalRaw) else { return nil }
        return TrainingGuideEngine(now: now, level: level)
            .guide(runs: runs, race: race,
                   goalSec: raceGoalSec > 0 ? Double(raceGoalSec) : nil,
                   raceDate: raceDateRaw > 0 ? Date(timeIntervalSince1970: raceDateRaw) : nil,
                   batteryTone: batteryTone)
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

    @ViewBuilder
    private func xpText(growth: GrowthState) -> some View {
        // 성조면 수집 버튼 — "조금 더 키우기"로 미뤄도 여기서 언제든 다시 연다.
        // 데모는 수집을 저장하지 않으므로(syncStage와 같은 가드) 문구만 둔다
        if growth.xpToNextStage == nil && !DemoMode.isActive {
            Button {
                showsCeremony = true
            } label: {
                Text("도감에 넣기")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(RR.onBrand)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(RR.brand, in: Capsule())
            }
            .buttonStyle(.plain)
            .rrTapTarget()
        } else {
            Text(growth.xpToNextStage.map { "다음 단계까지 \($0) XP" } ?? "성조 도달 — 세러모니가 기다려요")
                .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                .kerning(0.46)  // 시안 letter-spacing .04em × 11.5px
                .foregroundStyle(RR.text2)
        }
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
        // 기록 줄이 두 줄로 넘어가도 두 칩 높이를 맞춘다
        .fixedSize(horizontal: false, vertical: true)
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
                    // 반쪽 폭 칩이라 '10.0km · 6′06″/km'가 넘친다 — 줄바꿈 대신 한 줄에 맞춰 살짝 줄인다
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .foregroundStyle(RR.text)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(RR.text3)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
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
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
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

    /// 주간 목표 칩 횟수 — 성장 엔진 주간 목표 판정과 같은 1km 이상 기준
    private func weekRunCount(runs: [RunSummary], now: Date) -> Int {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        guard let week = calendar.dateInterval(of: .weekOfYear, for: now) else { return 0 }
        return runs.filter {
            $0.start >= week.start && $0.start <= now && GrowthEngine.countsAsCompletedRun($0)
        }.count
    }

    // MARK: - 승급 제안 (기획서 §3, 시안 1h)

    /// 실데이터 승급 후보. 거절한 지 4주가 안 지났으면 다시 묻지 않는다.
    private func promotionOffer(runs: [RunSummary], level: RunnerLevel, now: Date) -> PromotionEvidence? {
        if promotionDeclinedAtRaw > 0 {
            let declined = Date(timeIntervalSince1970: promotionDeclinedAtRaw)
            guard now.timeIntervalSince(declined) >= 28 * 86_400 else { return nil }
        }
        return LevelEngine.promotionCandidate(current: level, runs: runs, now: now)
    }

    private func accept(_ level: RunnerLevel) {
        levelRaw = level.rawValue
        ProgressSnapshot.markLocalChanged(defaults: .standard, now: Date())
        // 수락했으면 거절 기록은 의미가 없다 — 지워 둔다
        promotionDeclinedAtRaw = 0
        scheduleBackup()
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

    /// 주간 목표 변경 이력 — 비어 있으면 모든 주를 현재 목표로 판정한다 (이슈 #108, #116).
    /// 새 키가 아직 없으면 읽기 헬퍼가 #108의 옛 두 키를 1건짜리 이력으로 이관한다 — 첫 렌더부터
    /// 옛 기록으로 판정해야 이관 전 계산이 maxStage를 부풀리지 않는다
    private var weeklyGoalChanges: [WeeklyGoalChange] {
        if let weeklyGoalChangesData { return WeeklyGoalChangeLog.decode(weeklyGoalChangesData) }
        return WeeklyGoalChangeLog.load(defaults: .standard)
    }

    /// 이번 사이클 목표 — 키가 아직 없으면 현재 목표로 대신한다 (보정 저장 전 첫 렌더 대비)
    private var cycleGoal: RaceDistance? {
        RaceDistance(rawValue: cycleGoalRaw ?? raceGoalRaw)
    }

    private var cycleGoalSec: Int {
        cycleGoalSecRaw ?? raceGoalSec
    }

    /// 사이클 목표 키가 없는 기존 사용자(이슈 #110 이전 설치·복원)는 지금의 목표를 한 번 복사해 고정한다.
    /// 이후 설정에서 목표를 바꿔도 이번 사이클의 새 종류는 그대로다
    private func migrateCycleGoalIfNeeded() {
        if cycleGoalRaw == nil { cycleGoalRaw = raceGoalRaw }
        if cycleGoalSecRaw == nil { cycleGoalSecRaw = raceGoalSec }
    }

    /// 표시 단계를 최고 단계에 기록하고, 성조면 세러모니를 띄운다 — 홈 진입·단계 변화 두 곳에서 부른다 (이슈 #60)
    private func syncStage(_ stage: GrowthStage, runs: [RunSummary]) {
        // 데모(합성 데이터)는 표시만 한다 — 최고 단계·세러모니·사이클 전환을 저장하면
        // 데모를 꺼도 부풀려진 단계와 가짜 새가 남고 CloudKit까지 올라간다 (이슈 #44).
        // 세러모니가 뜨지 않으면 startNewCycle도 불리지 않는다
        guard !DemoMode.isActive else { return }
        syncMaxStage(stage)
        // 성조에 도달했는데 아직 수집하지 않았다면 세러모니를 띄운다.
        // 판정은 표시 단계로 한다 — XP가 흔들려도 한 번 성조가 됐으면 성조다.
        // 이미 떠 있으면 다시 세우지 않는다. "조금 더 키우기"로 미룬 종 그대로면 조용히 두고,
        // 그 뒤 기록으로 종이 올랐으면 다시 축하한다
        if !showsCeremony && CollectionEngine.hasReachedAdult(stage: stage)
            && deferredSpeciesRaw != pendingBird(runs: runs).species.rawValue { showsCeremony = true }
    }

    /// 이번 사이클 최고 단계를 올려 둔다 — 다음 실행에서 표시 단계가 내려가지 않게 하는 하한.
    private func syncMaxStage(_ stage: GrowthStage) {
        if stage.rawValue > maxStage {
            maxStage = stage.rawValue
            ProgressSnapshot.markLocalChanged(defaults: .standard, now: Date())
            scheduleBackup()
        }
    }

    /// 수집 확정 — 도감에 넣고 새 사이클을 시작한다 (기획서 §5).
    ///
    /// 사이클 전환의 **유일한 지점**이다. 순서가 중요하다: 도감에 먼저 넣고
    /// 사이클을 초기화한다 — 반대로 하면 저장에 실패했을 때 새를 잃는다.
    /// `cycleStartedAt`을 지금으로 옮기면 XP는 자동으로 0부터 다시 쌓인다
    /// (XP 원장을 저장하지 않는 설계라 리셋할 값이 따로 없다).
    /// - Returns: 도감 저장 성공 여부. 실패하면 사이클을 그대로 두고 알림만 띄운다 (이슈 #67)
    private func startNewCycle(runs: [RunSummary], goal: RaceDistance?, goalSeconds: Int, now: Date) -> Bool {
        let saved = collection.add(CollectionEngine.collect(runs: runs,
                                                             cycleStartedAt: cycleStartedAt,
                                                             now: now))
        guard saved else {
            showsCollectFailed = true
            return false
        }
        raceGoalRaw = goal?.rawValue ?? ""
        raceGoalSec = goalSeconds
        // 새 사이클의 목표를 고정한다 — 다음 세러모니의 목표 추천 기준이 된다 (이슈 #110)
        cycleGoalRaw = goal?.rawValue ?? ""
        cycleGoalSecRaw = goalSeconds
        cycleStartedAtRaw = now.timeIntervalSince1970
        maxStage = GrowthStage.egg.rawValue
        deferredSpeciesRaw = ""
        // 새 사이클 = 새 식별자 — CloudKit 스냅샷 병합의 사이클 경계 (이슈 #29)
        UserDefaults.standard.set(UUID().uuidString, forKey: GrowthKey.cycleID)
        ProgressSnapshot.markLocalChanged(defaults: .standard, now: now)
        scheduleBackup()
        return true
    }

    /// 성장 상태 변경 직후의 스냅샷 백업 — 실패해도 다음 트리거에서 다시 올라간다
    private func scheduleBackup() {
        Task { await backup.backupIfChanged() }
    }

    /// PB 갱신 감지 (이슈 #21) — 베이스라인과 비교해 새 기록이 있으면 한 번 축하한다.
    /// 첫 비교(베이스라인 없음)는 조용히 씨만 뿌린다 — 기존 기록 전부를 축하하면 소음이다.
    /// 세러모니와 겹치면 이번에는 베이스라인을 남겨 두고 미룬다 — 다음 진입 때 다시 잡힌다.
    private func checkNewPBs(runs: [RunSummary]) {
        guard !DemoMode.isActive else { return }   // 합성 데이터 기록으로는 축하하지 않는다
        // 베스트 에포트 백필 중이면 미룬다 — 일부만 계산된 기록으로 시드하면, 나중에 계산된
        // 옛 세션의 기록이 "새 PB"로 축하된다 (이슈 #166)
        guard health.bestEffortPending == 0 else { return }
        let current = PersonalRecords.compute(runs: runs, efforts: health.bestEfforts)
        guard !current.isEmpty else { return }
        let fresh = PBEngine.newRecords(current: current, baseline: PBBaselineCache.load())
        // 러닝화 팝업이 떠 있어도 미룬다 — 시트는 한 번에 하나라 겹치면 축하가 뜨지 못하고 사라진다 (이슈 #206)
        guard fresh.isEmpty || (!showsCeremony && !showsShoePrompt) else { return }
        PBBaselineCache.save(.make(from: current))
        if !fresh.isEmpty {
            newPBs = fresh
            showsPBCongrats = true
        }
    }

    /// 러닝 후 러닝화 묻기 (이슈 #206) — 기준 시각(`ShoeKey.promptedThrough`) 이후의 새 러닝을 팝업으로 묻는다.
    /// 첫 비교(기준 없음)는 조용히 기준만 심는다 — 기존 기록 전부를 묻지 않는다(PB 베이스라인과 같은 방식).
    /// 세러모니·PB 축하가 떠 있으면 기준을 남겨 두고 미룬다 — 둘의 onDismiss가 다시 부른다.
    /// 기준은 팝업이 닫힐 때 올린다
    private func checkNewRunsForShoe(runs: [RunSummary]) {
        guard !showsShoePrompt else { return }
        let now = Date()
        #if targetEnvironment(simulator)
        // 시뮬레이터는 검증용 — 기준이 없으면 3일 전으로 보고 DemoData 최근 러닝을 카드로 띄운다(저장하지 않음)
        let baseline = shoePromptedThrough > 0
            ? Date(timeIntervalSince1970: shoePromptedThrough) : now.addingTimeInterval(-3 * 86_400)
        #else
        guard !DemoMode.isActive else { return }   // 실기기 데모(합성 데이터)로는 묻지 않는다
        guard shoePromptedThrough > 0 else {
            shoePromptedThrough = now.timeIntervalSince1970
            return
        }
        let baseline = Date(timeIntervalSince1970: shoePromptedThrough)
        #endif
        // '다시 보지 않기' — 기준만 따라 올려, 나중에 다시 켜도 지난 러닝이 쏟아지지 않게 한다
        if shoePromptOptOut {
            if let latest = runs.map(\.start).max()?.timeIntervalSince1970, latest > shoePromptedThrough {
                shoePromptedThrough = latest
            }
            return
        }
        let pending = ShoeEngine.pendingRuns(runs: runs, promptedThrough: baseline, now: now)
        // PB 백필이 끝나기 전에는 띄우지 않는다(PB 축하가 먼저). 다른 시트가 떠 있으면 다음 기회로 미룬다 —
        // 시트 위에 또 띄우면 표시되지 않은 채 플래그만 남는다
        guard !pending.isEmpty, !showsCeremony, !showsPBCongrats, health.bestEffortPending == 0,
              !showsToday, editingShoe == nil, recapPeriod == nil, openedTargetRace == nil else { return }
        shoePromptRuns = pending
        showsShoePrompt = true
    }
}

// MARK: - 하위 뷰

/// PB 축하 시트 (이슈 #21) — 새 기록이 잡힌 홈 진입에서 한 번만 뜬다.
/// 메달 색은 성장기 PB 목록과 같은 매핑(RR.medalColor)을 쓴다.
private struct PBCongratsSheet: View {
    let entries: [PersonalRecords.Entry]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: "medal.fill")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(RR.medalColor(forPB: entries.first?.label ?? ""))
                .padding(.top, 34)
                .accessibilityHidden(true)
            Text("새 기록입니다!")
                .font(RR.display(26))
                .foregroundStyle(RR.text)
                .padding(.top, 14)
            Text("최고 기록을 갈아치우셨네요. 성장기에 바로 새겨 두었습니다.")
                .font(.system(size: 13.5))
                .foregroundStyle(RR.text2)
                .padding(.top, 6)

            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.element.label) { index, entry in
                    HStack(spacing: 12) {
                        Image(systemName: "medal.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(RR.medalColor(forPB: entry.label))
                            .frame(width: 24)
                            .accessibilityHidden(true)
                        Text(entry.label)
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(RR.text)
                        Spacer(minLength: 8)
                        Text(Format.duration(entry.timeSec))
                            .font(.system(size: 17, weight: .bold, design: .monospaced))
                            .foregroundStyle(RR.brand)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    if index < entries.count - 1 {
                        Divider().overlay(RR.line).padding(.leading, 52)
                    }
                }
            }
            .rrCard()
            .padding(.horizontal, 24)
            .padding(.top, 20)

            Spacer(minLength: 12)

            Button {
                dismiss()
            } label: {
                Text("계속 달리기")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(RR.onBrand)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .padding(.bottom, 18)
        }
        .presentationDetents([.medium])
        .background(RR.bg)
    }
}

/// 결산 리캡 진입 카드 (이슈 #167) — 판단 카드 아래, 칩 위. 탭하면 결산 시트, X는 이번 기간 닫기
private struct RecapPromptCard: View {
    let title: String
    let subtitle: String
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(RR.brand)
                        .frame(width: 34, height: 34)
                        .background(RR.brandSoft, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(RR.text)
                        Text(subtitle)
                            .font(.system(size: 11.5))
                            .foregroundStyle(RR.text3)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(RR.text3)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.text3)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
                    .rrTapTarget()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("결산 카드 닫기")
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }
}

/// 러닝화 카드 (이슈 #206) — 판단 카드 아래, 목표 대회 카드 위. 은퇴하지 않은 신발마다 한 줄씩
/// (설정의 러닝화 행과 같은 모양) + 끝에 '러닝화 추가'. 교체 기준을 넘은 신발은 그 행에서 주의 문구를 낸다.
/// 신발이 하나도 없으면 등록 권유 한 줄로 바뀐다 — '다시 보지 않기' 뒤에는 홈이 카드 자체를 걸지 않는다
private struct HomeShoeCard: View {
    let runs: [RunSummary]
    /// 편집할 신발과 신규 여부 — 시트는 홈이 띄운다
    let onEdit: (Shoe, Bool) -> Void
    @EnvironmentObject private var shoes: ShoeStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "러닝화")
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 4)
            if shoes.shoes.isEmpty {
                inviteRow
            } else {
                ForEach(shoes.shoes.filter { !$0.isRetired }) { shoe in
                    shoeRow(shoe)
                    Divider().overlay(RR.line).padding(.leading, 68)
                }
                addRow
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func shoeRow(_ shoe: Shoe) -> some View {
        let mileage = shoes.mileage(of: shoe, runs: runs)
        let progress = ShoeEngine.progress(mileageKm: mileage, replaceKm: shoe.replaceKm)
        return Button {
            onEdit(shoe, false)
        } label: {
            HStack(spacing: 14) {
                ShoeImage(shoe: shoe)
                    .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Text(shoe.name)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(RR.text)
                            .lineLimit(1)
                        if shoes.defaultShoeID == shoe.id {
                            Text("기본")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(RR.brand)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2.5)
                                .background(RR.brandSoft, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
                        }
                    }
                    Text("누적 \(Int(mileage.rounded())) km / \(Int(shoe.replaceKm)) km")
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(RR.barFill)
                            Capsule().fill(ShoeEngine.tone(progress: progress).color)
                                .frame(width: geo.size.width * progress)
                        }
                        // 기준을 넘긴 몫은 막대 끝에 과부하 색으로 덧칠한다 — 꽉 찬 막대만으로는 초과가 안 보인다
                        .overlay(alignment: .trailing) {
                            Capsule().fill(RRTone.overload.color)
                                .frame(width: geo.size.width * ShoeEngine.overshoot(mileageKm: mileage,
                                                                                    replaceKm: shoe.replaceKm))
                        }
                    }
                    .frame(height: 4)
                    if ShoeEngine.needsReplacement(shoe: shoe, mileageKm: mileage) {
                        Text("교체를 생각해 볼 때예요")
                            .font(.system(size: 11.5, weight: .semibold))
                            .foregroundStyle(RRTone.caution.color)
                    }
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var addRow: some View {
        Button {
            onEdit(newShoe, true)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 16, weight: .semibold))
                Text("러닝화 추가")
                    .font(.system(size: 14, weight: .semibold))
                Spacer(minLength: 0)
            }
            .foregroundStyle(RR.brand)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var inviteRow: some View {
        HStack(spacing: 12) {
            Text("러닝화를 등록하면 누적 거리로 교체 시점을 알려드려요")
                .font(.system(size: 13.5))
                .foregroundStyle(RR.text2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button("등록") { onEdit(newShoe, true) }
                .font(.system(size: 13.5, weight: .bold))
                .foregroundStyle(RR.onBrand)
                .padding(.horizontal, 14)
                .frame(height: 34)
                .background(RR.brand, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.top, 4)
        .padding(.bottom, 12)
    }

    private var newShoe: Shoe { Shoe(name: "", createdAt: Date()) }
}

/// 러닝 후 러닝화 묻기 팝업 (이슈 #206) — 새 러닝을 한 장씩 넘기며 신은 러닝화를 고른다.
/// 각 장은 세션 상세 화면 그대로(지도·지표·구간·심박 존)이고, 러닝화 행 자리에 고르는 목록이 들어간다.
/// 자동 배정(기본 신발)이 먼저 돌아 있어 그 신발이 체크된 채로 열린다 — 그대로 닫으면 기본 신발로 남는다
private struct RunShoePromptSheet: View {
    /// 띄울 때 고정한 새 러닝 — 오래된 순
    let runs: [RunSummary]
    let onOptOut: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(Array(runs.enumerated()), id: \.element.id) { index, run in
                    SessionDetailScreen(run: run, onShoePromptOptOut: onOptOut)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: runs.count > 1 ? .always : .never))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            Button {
                if page < runs.count - 1 {
                    withAnimation { page += 1 }
                } else {
                    dismiss()
                }
            } label: {
                Text(page < runs.count - 1 ? "다음" : "확인")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(RR.onBrand)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .padding(.top, 10)
            .padding(.bottom, 18)
        }
        .background(RR.bg)
    }
}

/// 목표 대회 카드 (이슈 #172) — 판단 카드 아래, 결산 카드 위. 탭하면 대회 상세 시트
private struct TargetRaceCard: View {
    let entry: RaceEngine.Entry
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                Image(systemName: "flag.checkered")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(RR.brand)
                    .frame(width: 34, height: 34)
                    .background(RR.brandSoft, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text("목표 대회 \(RaceFormat.dDay(entry.dDay)) · \(entry.race.name)")
                        .font(.system(size: 14.5, weight: .bold))
                        .foregroundStyle(RR.text)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    HStack(spacing: 6) {
                        Text(RaceFormat.fullDate.string(from: entry.raceDate))
                            .font(.system(size: 11.5))
                            .foregroundStyle(RR.text3)
                        RegisterBadge(status: entry.status)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .rrCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 오늘의 판단 카드 (기획서 v0.8 §6) — 판정 한 줄 + 그 판정의 재료 네 줄.
///
/// 위트는 제목줄이 맡는다 (브리핑 카드가 하던 몫). 재료 줄은 값이 있으면 그대로 보여주고,
/// 없으면 숫자를 지어내지 않고 무엇을 하면 켜지는지만 말한다 — 판정·문구는 전부
/// `TodayVerdictEngine`이 정하고 여기서는 색과 목적지만 붙인다.
private struct VerdictCard: View {
    let verdict: TodayVerdict
    /// 타일 그림의 재료 — 문구는 전부 엔진이 낸 것을 쓰고, 여기서는 같은 값을 그림으로 한 번 더 보여준다
    let battery: BatteryReport?
    let weather: TodayVerdictEngine.WeatherInput
    /// 날씨 타일에 얹는 미세·초미세 등급 요약 — 상세 수치는 '오늘' 시트 몫
    let air: AirQuality?
    /// 조회 실패 타일을 눌러 다시 불러오는 중
    let retryingWeather: Bool
    let onTap: (TodayVerdict.Line.Kind) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 배터리가 없으면 판정 자체가 없다 — 배지를 감추고 중립 문구만 남긴다
            if let tone = verdict.tone {
                ToneBadge(tone: tone, label: verdict.badgeLabel)
                    .padding(.bottom, 9)
            }

            Text(verdict.headline)
                .font(RR.display(19))
                .foregroundStyle(RR.text)
                .frame(maxWidth: .infinity, alignment: .leading)

            // 배터리·날씨는 눈으로 먼저 읽히는 값이라 글자 대신 타일 두 장으로 낸다.
            // 남은 두 줄(권장 세션·회복 경과)은 문장이 곧 값이라 그대로 한 줄씩 둔다
            HStack(spacing: 9) {
                tile(verdict.battery) { batteryArt }
                tile(verdict.weather) { weatherArt }
            }
            .padding(.top, 13)

            Divider().overlay(RR.line).padding(.top, 13)
            row(verdict.session)
            Divider().overlay(RR.line)
            row(verdict.recovery)
        }
        .padding(EdgeInsets(top: 15, leading: 15, bottom: 5, trailing: 15))
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    // MARK: 타일 두 장

    private func tile(_ line: TodayVerdict.Line, @ViewBuilder art: () -> some View) -> some View {
        Button { onTap(line.kind) } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 4) {
                    Text(line.label)
                        .font(.system(size: 11.5))
                        .foregroundStyle(RR.text3)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9.5, weight: .semibold))
                        .foregroundStyle(RR.text3)
                }
                art()
            }
            .padding(EdgeInsets(top: 11, leading: 12, bottom: 12, trailing: 12))
            // maxHeight를 열어 둬야 내용이 짧은 쪽(날씨)이 긴 쪽(배터리) 높이에 맞춰 늘어난다
            .frame(maxWidth: .infinity, minHeight: 104, maxHeight: .infinity, alignment: .topLeading)
            .background(RR.surface2, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 잔량 막대 + 큰 숫자 — 배터리는 "얼마나 남았나"가 한눈에 들어와야 하는 값이다
    @ViewBuilder
    private var batteryArt: some View {
        if let battery {
            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text("\(battery.level)")
                        .font(RR.numeral(25))
                        .foregroundStyle(battery.tone.color)
                    Text(battery.statusLabel)
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(RR.text2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                // 리포트 탭과 같은 게이지를 그대로 쓴다 — 칸 수로 잔량이 먼저 읽힌다
                BatteryGauge(level: battery.level)
            }
        } else {
            hintArt(symbol: "applewatch", line: verdict.battery)
        }
    }

    /// 하늘 상태 아이콘 + 기온(체감은 작게, 이슈 #220) + 복장 — '오늘' 시트의 세 카드를 한 장으로 줄인 요약
    @ViewBuilder
    private var weatherArt: some View {
        switch weather {
        case .current(let current, _):
            let parts = TodayVerdictEngine.weatherParts(current, now: Date())
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    if let condition = WeatherCondition.of(current.weatherCode) {
                        Image(systemName: condition.symbol)
                            .font(.system(size: 21))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(condition.tint, condition.tint2)
                    }
                    Text("\(Int(current.temperatureC.rounded()))")
                        .font(RR.numeral(25))
                        .foregroundStyle(RR.text)
                    Text("°C · 체감 \(Int(current.apparentC.rounded()))°")
                        .font(.system(size: 10.5))
                        .foregroundStyle(RR.text3)
                }
                if let outfit = parts.outfit {
                    Text(parts.raining ? "비 · \(outfit)" : outfit)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(RR.text2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                }
                // 달리기 좋은 시간 (이슈 #173) — 추천이 없으면 줄을 내지 않는다
                if let caption = verdict.weather.caption {
                    Text(caption)
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(RRTone.improving.color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                }
                // 미세·초미세는 등급 문구를 등급 색으로 — 값이 있는 항목만 (미노출 가드)
                if let air, air.pm10Grade != nil || air.pm25Grade != nil {
                    HStack(spacing: 8) {
                        airBadge("미세", grade: air.pm10Grade)
                        airBadge("초미세", grade: air.pm25Grade)
                    }
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                }
            }
        case .loading:
            hintArt(symbol: "arrow.triangle.2.circlepath", line: verdict.weather)
        case .denied:
            hintArt(symbol: "location.slash", line: verdict.weather)
        case .unavailable:
            VStack(alignment: .leading, spacing: 4) {
                hintArt(symbol: "icloud.slash", line: verdict.weather)
                // 탭이 곧 재시도다 (HomeScreen.tap) — 그 사실을 타일에서 말해 준다
                Text(retryingWeather ? "다시 불러오는 중…" : "눌러서 다시 불러오기")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.brand)
            }
        }
    }

    /// "미세 좋음" — 항목명은 보조색, 등급 문구는 등급 톤 색. 등급이 없으면 그리지 않는다
    @ViewBuilder
    private func airBadge(_ name: String, grade: AirGrade?) -> some View {
        if let grade {
            HStack(spacing: 3) {
                Text(name)
                    .font(.system(size: 10.5))
                    .foregroundStyle(RR.text3)
                Text(grade.label)
                    .font(.system(size: 10.5, weight: .bold))
                    .foregroundStyle(grade.tone.color)
            }
        }
    }

    /// 값이 없는 타일 — 숫자 자리를 비워 두고 무엇을 하면 켜지는지만 말한다
    private func hintArt(symbol: String, line: TodayVerdict.Line) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 21))
                .foregroundStyle(RR.text3)
            Text(text(line))
                .font(.system(size: 12))
                .lineSpacing(2)
                .foregroundStyle(RR.text3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func row(_ line: TodayVerdict.Line) -> some View {
        Button { onTap(line.kind) } label: {
            HStack(spacing: 10) {
                Text(line.label)
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
                    .frame(width: 66, alignment: .leading)

                Text(text(line))
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(color(line))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func text(_ line: TodayVerdict.Line) -> String {
        switch line.content {
        case .value(let text), .hint(let text): text
        }
    }

    /// 유도 문구는 값이 아니므로 한 단계 흐리게 — 값 줄만 톤 색을 입는다
    private func color(_ line: TodayVerdict.Line) -> Color {
        if case .hint = line.content { return RR.text3 }
        return line.tone?.color ?? RR.text
    }
}

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
    /// 승급 근거 (LevelEngine 판정 결과) — 제안 레벨과 근거별 본문 문장을 정한다
    let evidence: PromotionEvidence
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
                        .foregroundStyle(RR.onBrand)
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

    /// 시안 문구를 근거별 실제 값으로 채운 템플릿 — 레벨명만 볼드로 강조한다 (AttributedString 합성)
    private func body(for evidence: PromotionEvidence) -> AttributedString {
        var level = AttributedString(evidence.target.label)
        level.font = .system(size: 14.5, weight: .bold)

        let lead: String
        switch evidence {
        case .tenKmPace(let run):
            let km = run.distanceKm ?? 10
            // 10km 환산 기록(분) — 엔진 판정식 durationSec / km × 10과 같은 값
            let tenKmMin = Int((run.durationSec / km * 10 / 60).rounded())
            lead = "\(Format.relativeWeek(of: run.start, now: .now)) \(Format.km(km))km를 "
                + "10km 환산 \(tenKmMin)분 페이스로 달리셨더라고요. "
        case .halfFinish(let run):
            lead = "\(Format.relativeWeek(of: run.start, now: .now)) 하프 거리를 완주하셨더라고요. "
        case .fullUnder430(let run, let monthlyKm):
            lead = "\(Format.relativeWeek(of: run.start, now: .now)) 풀 거리를 \(Format.duration(run.durationSec))에 "
                + "달리고 4주 월환산 \(Format.km(monthlyKm))km — 런친놈 기준이에요. "
        }
        return AttributedString(lead + "리포트를 ") + level + AttributedString(" 수준으로 올려드릴까요?")
    }
}
