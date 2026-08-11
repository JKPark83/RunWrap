import SwiftUI

/// 앱 루트 — 연결 상태에 따라 온보딩 / 로딩 / 오류 / 메인 탭 분기
struct RootView: View {
    @StateObject private var health = HealthStore()
    @AppStorage("didConnectHealth") private var didConnectHealth = false

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
                MainTabs()
            }
        }
        .environmentObject(health)
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

/// 시안의 2탭 구조 — 리포트 / 통계
private struct MainTabs: View {
    var body: some View {
        TabView {
            NavigationStack { ReportHomeScreen() }
                .tabItem { Label("리포트", systemImage: "figure.run") }
            NavigationStack { StatsScreen() }
                .tabItem { Label("통계", systemImage: "chart.bar.xaxis") }
        }
    }
}
