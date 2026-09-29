import SwiftUI

/// 앱 루트 — 온보딩 완료 여부와 연결 상태에 따라 설문 / 로딩 / 오류 / 메인 탭 분기.
/// HealthStore는 RunWrapApp이 소유하고 environmentObject로 내려온다 (계획서 M8).
///
/// v0.7에서 순서가 바뀌었다: **설문이 먼저, 권한 요청이 나중**이다 (기획서 §2).
/// 설문은 자기 신고라 HealthKit이 필요 없고, 맥락(내 레벨·내 알)을 만든 뒤 권한을 요청해야
/// 수락률이 오른다. 그래서 분기 기준이 `health.state`가 아니라 레벨 저장값이다.
struct RootView: View {
    @EnvironmentObject private var health: HealthStore
    /// 신규 설치 복원(이슈 #29) — 온보딩을 열기 전에 CloudKit 스냅샷부터 확인한다
    @EnvironmentObject private var backup: ProgressBackupStore
    /// 복원된 도감을 메모리에 반영하기 위한 통로 — 파일 쓰기는 backup이 이미 마쳤다
    @EnvironmentObject private var collection: CollectionStore
    /// 날씨는 홈이 뜨기 전에 준비돼야 해서 루트가 쥔다 — 스플래시 해제 조건이자 홈의 재료
    @StateObject private var weather = WeatherStore()
    /// 직접 입력한 대회 기록 (이슈 #35) — 설정이 입력하고 리포트가 소비해서 루트가 쥔다
    @StateObject private var raceRecords = RaceRecordStore()
    @AppStorage("didConnectHealth") private var didConnectHealth = false
    @Environment(\.scenePhase) private var scenePhase
    /// 온보딩 설문 완료 여부 — 빈 문자열이면 아직 레벨이 없다 (= 설문 미완료)
    @AppStorage(ProfileKey.levelV2) private var levelRaw = ""
    /// v0.6 이하 사용자에게 재온보딩 사유를 한 번 알려준다
    @State private var isReturningUser = false
    /// 복원 불가 안내를 탭으로 닫았는지 — 같은 세션에서 다시 띄우지 않는다
    @State private var noticeDismissed = false
    /// 스플래시 안전망 — 기동이 병적으로 늘어지면 홈부터 연다.
    /// 이때 날씨 타일은 "불러오는 중" 힌트로 남고, 로딩이 끝나는 즉시 값으로 바뀐다
    @State private var splashTimedOut = false
    /// 안전망 상한 — 위치 권한이 있으면 날씨가 홈보다 먼저다(요구사항). 정상 실패 경로는
    /// 위치 실패·네트워크 타임아웃(WeatherClient 10초)이 이보다 먼저 결론을 내므로,
    /// 이 값은 그마저 안 오는 병적 상황(권한 다이얼로그 방치 등) 전용이다
    private static let splashTimeout: Duration = .seconds(20)

    var body: some View {
        Group {
            if levelRaw.isEmpty {
                if isCheckingRestore {
                    // 신규 설치(재설치 포함) — 온보딩을 열기 전에 CloudKit 복원 결과를 기다린다.
                    // 여기서 온보딩이 먼저 열리면 cycleStartedAt이 지금으로 덮여 이전 XP를 잃는다 (이슈 #29)
                    SplashScreen()
                        .transition(.opacity)
                } else {
                    // 설문이 먼저다 — 설문은 자기 신고라 HealthKit 권한이 필요 없다 (기획서 §2)
                    OnboardingFlowScreen()
                        // 재온보딩·복원 불가 안내는 설문 위에 얹어서 한 번 보여준다.
                        // 설문 화면의 인터페이스를 건드리지 않으려고 여기서 덮는다.
                        .overlay(alignment: .bottom) {
                            if isReturningUser {
                                notice("런미새가 새 단장을 했어요. 1분만 다시 알려주세요.")
                            } else if let text = restoreNoticeText {
                                notice(text)
                            }
                        }
                }
            } else if isBooting {
                // 기동 로딩(건강 데이터 + 현재 위치 날씨)이 끝날 때까지 홈 노출을 미룬다
                SplashScreen()
                    .transition(.opacity)
            } else {
                switch health.state {
                case .unavailable:
                    ContentUnavailableView("이 기기에서는 쓸 수 없어요",
                                           systemImage: "heart.slash",
                                           description: Text("HealthKit을 지원하는 iPhone이 필요합니다."))
                case .failed(let message):
                    ContentUnavailableView {
                        Label("불러오지 못했어요", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(message)
                    } actions: {
                        Button("다시 시도") { Task { await health.load() } }
                            .buttonStyle(.borderedProminent)
                    }
                case .loaded:
                    MainTabs()
                case .idle, .loading:
                    // isBooting이 이미 걸러서 오지 않는 가지 — switch 완전성용
                    SplashScreen()
                }
            }
        }
        .environmentObject(weather)
        .environmentObject(raceRecords)
        .tint(RR.brand)
        // 복원 선택 (이슈 #44) — 첫 업로드 직전에 서버의 이전 진행도를 발견했을 때 묻는다.
        // 메인 탭이 아니라 루트에 붙인다: 건강 데이터가 실패·미지원이어도 답할 수 있어야
        // 백업이 막힌 채 남지 않는다. 스플래시(권한 시트·기동 로딩) 중에는 미룬다
        .sheet(isPresented: showsRestoreChoice) {
            if let candidate = backup.restoreCandidate {
                RestoreChoiceSheet(candidate: candidate,
                                   onAccept: {
                                       if let applied = backup.acceptRestoreCandidate() {
                                           collection.replace(with: applied.birds)
                                           raceRecords.replace(with: applied.raceRecords)
                                       }
                                   },
                                   onDecline: {
                                       let merged = backup.declineRestoreCandidate()
                                       collection.replace(with: merged.birds)
                                       raceRecords.replace(with: merged.raceRecords)
                                   })
                    // 둘 중 하나를 골라야 백업이 풀린다 — 스와이프로 닫아 미정 상태로 두지 않는다
                    .interactiveDismissDisabled()
            }
        }
        // 스플래시 → 홈은 교차 페이드로 잇는다 — 런치 스크린류 화면의 관례
        .animation(.easeOut(duration: 0.35), value: isBooting)
        .task {
            detectReturningUser()
            if levelRaw.isEmpty {
                // 신규 설치 — 온보딩보다 CloudKit 복원이 먼저다 (이슈 #29).
                // 성공하면 레벨 저장값이 채워져 onChange(levelRaw)가 데이터 로딩을 이어받는다
                if let snapshot = await backup.restoreOnFreshInstall() {
                    collection.replace(with: snapshot.collectedBirds)
                    raceRecords.replace(with: snapshot.raceRecords ?? [])
                }
            } else {
                // 온보딩을 마친 사용자는 바로 조회 (권한 시트는 이미 설문 끝에서 지났다)
                if case .idle = health.state {
                    await health.load()
                }
                // 기존 사용자의 로컬 상태를 스냅샷으로 올린다 — 최초 1회 마이그레이션 포함 (이슈 #29)
                await backup.backupIfChanged()
            }
        }
        .task {
            // 건강 데이터와 별개 트랙 — 위치 권한이 남아 있으면 다이얼로그가 스플래시 위에 뜬다
            if !levelRaw.isEmpty { await weather.load() }
        }
        .task(id: levelRaw.isEmpty) {
            // 안전망 시계는 온보딩이 끝난 시점부터 잰다
            guard !levelRaw.isEmpty else { return }
            // 취소는 타임아웃이 아니다 — sleep이 끝까지 잤을 때만 안전망을 발동한다
            guard (try? await Task.sleep(for: Self.splashTimeout)) != nil else { return }
            splashTimedOut = true
        }
        .onChange(of: levelRaw) { _, newValue in
            // 설문을 막 마친 직후 — 권한 시트가 끝났으니 데이터를 읽는다
            guard !newValue.isEmpty else { return }
            isReturningUser = false
            Task { await health.load() }
            Task { await weather.load() }
        }
        .onChange(of: health.state) { _, newState in
            if case .loaded = newState { didConnectHealth = true }
        }
        .onChange(of: scenePhase) { _, phase in
            // 포그라운드 복귀 — 날씨가 낡았으면 다시 받아 수분 알람까지 재예약한다 (이슈 #69).
            // WeatherStore를 쥔 곳이 여기라 RunWrapApp이 아니라 루트에서 건다.
            // 기동 로딩 전·중이면 refresh()가 스스로 건너뛴다
            guard phase == .active, !levelRaw.isEmpty else { return }
            Task { await weather.refreshIfStale() }
        }
    }

    /// 스플래시를 유지할 조건 — 건강 데이터가 로딩 중이거나, 날씨가 아직 결론이 없을 때.
    /// 건강 쪽 실패·미지원은 스플래시가 아니라 전용 안내 화면으로 보낸다.
    private var isBooting: Bool {
        switch health.state {
        case .idle, .loading: true
        case .loaded: !(weather.isSettled || splashTimedOut)
        case .unavailable, .failed: false
        }
    }

    /// 복원 선택 시트 표시 조건 — 후보가 있고, 온보딩을 마쳤고, 스플래시가 끝났을 때.
    /// 닫기는 선택 버튼으로만 한다(후보가 비면 저절로 닫힌다)
    private var showsRestoreChoice: Binding<Bool> {
        Binding(get: { backup.restoreCandidate != nil && !levelRaw.isEmpty && !isBooting },
                set: { _ in })
    }

    /// 신규 설치 복원을 기다리는 중인지 — 이 동안은 온보딩 대신 스플래시를 유지한다.
    /// idle도 포함한다: 첫 body 평가는 .task보다 먼저라, 시도 전에 온보딩이 새면 안 된다
    private var isCheckingRestore: Bool {
        switch backup.restoreState {
        case .idle, .checking: true
        case .restored, .empty, .unavailable, .failed: false
        }
    }

    /// 복원 불가 안내 문구 — 스냅샷이 없는 진짜 신규(empty)에게는 아무 말도 하지 않는다 (이슈 #29)
    private var restoreNoticeText: String? {
        guard !noticeDismissed else { return nil }
        switch backup.restoreState {
        // 확인하지 못한 기록은 나중에 연결되면 다시 찾아 묻는다 (이슈 #44 복원 선택 시트)
        case .unavailable: return "iCloud에 로그인되어 있지 않아 이전 기록을 확인하지 못했어요. 일단 시작하고, 연결되면 다시 확인할게요."
        case .failed: return "iCloud에서 이전 기록을 확인하지 못했어요. 일단 시작하고, 연결되면 다시 확인할게요."
        case .idle, .checking, .restored, .empty: return nil
        }
    }

    /// 설문 위에 얹는 한 줄 안내 캡슐 — 재온보딩·복원 불가 공용. 탭하면 닫힌다
    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12.5))
            .foregroundStyle(RR.text2)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(RR.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(RR.line))
            .padding(.horizontal, 24)
            .padding(.bottom, 26)
            .onTapGesture {
                isReturningUser = false
                noticeDismissed = true
            }
    }

    /// v1 프로필을 갖고 있던 기존 사용자인지 판정한다.
    /// v1 키는 한 번 읽고 지운다 — 다음 실행부터는 신규 사용자와 같은 경로를 탄다.
    private func detectReturningUser() {
        let legacyKey = "profile.didSet"
        guard levelRaw.isEmpty, UserDefaults.standard.bool(forKey: legacyKey) else { return }
        isReturningUser = true
        UserDefaults.standard.removeObject(forKey: legacyKey)
    }
}

/// 복원 선택 시트 (이슈 #44) — "이전 기록 불러오기 / 새로 시작".
///
/// 복원이 일시 실패한 뒤 새로 시작한 설치가, 첫 업로드 직전에 서버의 다른 사이클 본을 발견했을 때 뜬다.
/// 조용히 덮어쓰지 않고 묻는 것은 사용자 결정이다. 불러오면 방금 정한 레벨·목표가 이전 값으로 바뀌므로
/// 그 점을 문구로 밝힌다. 새로 시작해도 도감은 합집합으로 남는다(ProgressBackupStore).
private struct RestoreChoiceSheet: View {
    let candidate: ProgressSnapshot
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "iCloud")

            Text("이전 기록을 찾았어요")
                .font(RR.display(26))
                .foregroundStyle(RR.text)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 10) {
                summaryRow(label: "레벨", value: levelLabel)
                summaryRow(label: "성장 단계", value: stageLabel)
                summaryRow(label: "도감", value: "\(candidate.collectedBirds.count)마리")
                summaryRow(label: "마지막 백업", value: Self.dateText(candidate.updatedAt))
            }
            .padding(16)
            .background(RR.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(RR.line))
            .padding(.top, 20)

            Text("불러오면 방금 진단한 레벨과 목표 대신 이전 기록으로 이어가요.")
                .font(.system(size: 13))
                .lineSpacing(3)
                .foregroundStyle(RR.text2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 14)

            Spacer(minLength: 20)

            PrimaryButton(title: "이전 기록 불러오기", action: onAccept)

            Button(action: onDecline) {
                Text("새로 시작")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.plain)
            .padding(.top, 4)

            Text("새로 시작해도 도감의 새는 그대로 남아요")
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(RR.bg.ignoresSafeArea())
    }

    private var levelLabel: String {
        RunnerLevel(rawValue: candidate.levelRaw)?.label ?? candidate.levelRaw
    }

    private var stageLabel: String {
        (GrowthStage(rawValue: candidate.maxStage) ?? .egg).label
    }

    private func summaryRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(RR.text3)
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(RR.text)
        }
    }

    private static func dateText(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "yyyy년 M월 d일"
        return formatter.string(from: date)
    }
}

/// 4탭 구조 — 홈 / 리포트 / 코스 / 대회 (기획서 v0.8 §6).
/// '오늘'은 탭에서 빠져 홈 판단 카드의 날씨 줄에서 여는 시트가 됐다 —
/// 날씨 한 줄이 홈으로 올라온 뒤로는 매일 탭을 하나 차지할 만큼 자주 볼 화면이 아니다.
private struct MainTabs: View {
    /// 판단 카드의 배터리·권장 세션 줄이 리포트 탭으로 넘기려면 선택 탭을 여기서 쥐어야 한다
    @State private var selection = Tab.home

    private enum Tab { case home, report, course, race }

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { HomeScreen(onSelectReport: { selection = .report }) }
                // 새 아이콘은 에셋이 아니라 Shape을 래스터라이즈한 이미지다 (BirdTabIcon 주석 참고)
                .tabItem { Label { Text("홈") } icon: { BirdTabIcon.image } }
                .tag(Tab.home)
            NavigationStack { ReportHomeScreen() }
                .tabItem { Label("리포트", systemImage: "figure.run") }
                .tag(Tab.report)
            NavigationStack { CourseScreen() }
                .tabItem { Label("코스", systemImage: "map") }
                .tag(Tab.course)
            NavigationStack { RaceListScreen() }
                .tabItem { Label("대회", systemImage: "flag.checkered") }
                .tag(Tab.race)
        }
    }
}
