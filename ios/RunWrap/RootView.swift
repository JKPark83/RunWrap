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
    @AppStorage("didConnectHealth") private var didConnectHealth = false
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
        .tint(RR.brand)
        // 스플래시 → 홈은 교차 페이드로 잇는다 — 런치 스크린류 화면의 관례
        .animation(.easeOut(duration: 0.35), value: isBooting)
        .task {
            detectReturningUser()
            if levelRaw.isEmpty {
                // 신규 설치 — 온보딩보다 CloudKit 복원이 먼저다 (이슈 #29).
                // 성공하면 레벨 저장값이 채워져 onChange(levelRaw)가 데이터 로딩을 이어받는다
                if let snapshot = await backup.restoreOnFreshInstall() {
                    collection.replace(with: snapshot.collectedBirds)
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
        case .unavailable: return "iCloud에 로그인되어 있지 않아 이전 기록을 확인할 수 없었어요. 새로 시작할게요."
        case .failed: return "iCloud에서 이전 기록을 확인하지 못했어요. 일단 새로 시작할게요."
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
