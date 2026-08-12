import SwiftUI

/// 앱 루트 — 연결 상태에 따라 온보딩 / 로딩 / 오류 / 메인 탭 분기.
/// HealthStore는 RunWrapApp이 소유하고 environmentObject로 내려온다 (계획서 M8).
struct RootView: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage("didConnectHealth") private var didConnectHealth = false
    /// 프로필(목적·레벨) 설정 완료 여부 — M2 이전 사용자는 다음 실행에 한 번 노출된다
    @AppStorage(ProfileKey.didSetProfile) private var didSetProfile = false

    var body: some View {
        Group {
            switch health.state {
            case .idle:
                OnboardingScreen()
            case .loading:
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
                // 권한 연결 뒤 프로필 스텝을 한 번 거친다 (계획서 M2)
                if didSetProfile {
                    MainTabs()
                } else {
                    ProfileSetupScreen()
                }
            }
        }
        .tint(RR.brand)
        .task {
            // 이미 연결했던 사용자는 온보딩 없이 바로 조회
            if didConnectHealth, case .idle = health.state {
                await health.load()
            }
        }
        .onChange(of: health.state) { _, newState in
            if case .loaded = newState { didConnectHealth = true }
        }
    }
}

/// 5탭 구조 — 리포트 / 통계 / 오늘 / 코스 / 대회 (계획서 M6 '오늘', M12 '코스', M13 '대회' 추가)
private struct MainTabs: View {
    var body: some View {
        TabView {
            NavigationStack { ReportHomeScreen() }
                .tabItem { Label("리포트", systemImage: "figure.run") }
            NavigationStack { StatsScreen() }
                .tabItem { Label("통계", systemImage: "chart.bar.xaxis") }
            NavigationStack { TodayScreen() }
                .tabItem { Label("오늘", systemImage: "sun.max") }
            NavigationStack { CourseScreen() }
                .tabItem { Label("코스", systemImage: "map") }
            NavigationStack { RaceListScreen() }
                .tabItem { Label("대회", systemImage: "flag.checkered") }
        }
    }
}
