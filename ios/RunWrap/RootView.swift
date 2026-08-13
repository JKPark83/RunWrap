import SwiftUI

/// 앱 루트 — 온보딩 완료 여부와 연결 상태에 따라 설문 / 로딩 / 오류 / 메인 탭 분기.
/// HealthStore는 RunWrapApp이 소유하고 environmentObject로 내려온다 (계획서 M8).
///
/// v0.7에서 순서가 바뀌었다: **설문이 먼저, 권한 요청이 나중**이다 (기획서 §2).
/// 설문은 자기 신고라 HealthKit이 필요 없고, 맥락(내 레벨·내 알)을 만든 뒤 권한을 요청해야
/// 수락률이 오른다. 그래서 분기 기준이 `health.state`가 아니라 레벨 저장값이다.
struct RootView: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage("didConnectHealth") private var didConnectHealth = false
    /// 온보딩 설문 완료 여부 — 빈 문자열이면 아직 레벨이 없다 (= 설문 미완료)
    @AppStorage(ProfileKey.levelV2) private var levelRaw = ""
    /// v0.6 이하 사용자에게 재온보딩 사유를 한 번 알려준다
    @State private var isReturningUser = false

    var body: some View {
        Group {
            if levelRaw.isEmpty {
                // 설문이 먼저다 — 설문은 자기 신고라 HealthKit 권한이 필요 없다 (기획서 §2)
                OnboardingFlowScreen()
                    // 재온보딩 안내는 기존 사용자에게만, 설문 위에 얹어서 한 번 보여준다.
                    // 설문 화면의 인터페이스를 건드리지 않으려고 여기서 덮는다.
                    .overlay(alignment: .bottom) {
                        if isReturningUser { migrationNotice }
                    }
            } else {
                switch health.state {
                case .idle, .loading:
                    ZStack {
                        RR.bg.ignoresSafeArea()
                        ProgressView()
                    }
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
                }
            }
        }
        .tint(RR.brand)
        .task {
            detectReturningUser()
            // 온보딩을 마친 사용자는 바로 조회 (권한 시트는 이미 설문 끝에서 지났다)
            if !levelRaw.isEmpty, case .idle = health.state {
                await health.load()
            }
        }
        .onChange(of: levelRaw) { _, newValue in
            // 설문을 막 마친 직후 — 권한 시트가 끝났으니 데이터를 읽는다
            guard !newValue.isEmpty else { return }
            isReturningUser = false
            Task { await health.load() }
        }
        .onChange(of: health.state) { _, newState in
            if case .loaded = newState { didConnectHealth = true }
        }
    }

    /// v0.6 이하 사용자용 안내 — 레벨 체계가 바뀌어 설문을 다시 받아야 하는 이유를 알린다
    private var migrationNotice: some View {
        Text("런미새가 새 단장을 했어요. 1분만 다시 알려주세요.")
            .font(.system(size: 12.5))
            .foregroundStyle(RR.text2)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(RR.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(RR.line))
            .padding(.bottom, 26)
            .onTapGesture { isReturningUser = false }
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

/// 5탭 구조 — 홈 / 리포트 / 오늘 / 코스 / 대회 (기획서 v0.7 §6).
/// 홈(새 성장)이 첫 탭으로 오고, 통계 탭은 리포트의 "발전상" 세그먼트로 흡수되어 사라졌다.
private struct MainTabs: View {
    var body: some View {
        TabView {
            NavigationStack { HomeScreen() }
                // 새 아이콘은 에셋이 아니라 Shape이라 Label(image:)를 못 쓴다 — 뷰로 직접 조립한다
                .tabItem {
                    Label {
                        Text("홈")
                    } icon: {
                        BirdTabIcon()
                    }
                }
            NavigationStack { ReportHomeScreen() }
                .tabItem { Label("리포트", systemImage: "figure.run") }
            NavigationStack { TodayScreen() }
                .tabItem { Label("오늘", systemImage: "sun.max") }
            NavigationStack { CourseScreen() }
                .tabItem { Label("코스", systemImage: "map") }
            NavigationStack { RaceListScreen() }
                .tabItem { Label("대회", systemImage: "flag.checkered") }
        }
    }
}
