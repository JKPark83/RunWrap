import SwiftUI

/// 앱 진입점 + 알림 수명주기 (계획서 M8).
/// HealthStore를 앱이 소유해 scenePhase 훅에서 재계산→캐시→주간 알림 재예약을 돌린다 —
/// 포그라운드 재계산이 1차 경로, HealthKit 옵저버(러닝 종료 즉시 알림)는 보조 경로.
@main
struct RunWrapApp: App {
    @StateObject private var health = HealthStore()
    /// 도감 — 파일에서 한 번 읽어 앱 수명 동안 들고 간다 (기획서 §5)
    @StateObject private var collection = CollectionStore()
    /// 진행도 CloudKit 백업·복원 (이슈 #29) — 신규 설치 복원은 RootView가,
    /// 주기적 백업은 백그라운드 진입 훅이 굴린다
    @StateObject private var backup = ProgressBackupStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(health)
                .environmentObject(collection)
                .environmentObject(backup)
                .task {
                    // 기동 시 옵저버 재등록 — 워치 러닝이 끝나면 백그라운드에서 깨워준다
                    let health = health
                    health.startObservingWorkouts { [weak health] in
                        guard let health else { return }
                        await Self.handleWorkoutUpdate(health: health)
                    }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await refreshCacheAndReschedule() }
            } else if phase == .background {
                // 설정 변경(주간 목표·대회 목표 등)까지 훑어 담는 안전망 백업 —
                // 내용이 마지막 업로드와 같으면 아무것도 하지 않는다 (이슈 #29)
                Task { await backup.backupIfChanged() }
            }
        }
    }

    /// 포그라운드 진입: 이미 로딩된 목록이 있으면 새로 고침 → 캐시 갱신 → 주간 알림 재예약.
    /// 최초 기동은 RootView의 connect/load가 담당하므로 여기서는 건드리지 않는다.
    private func refreshCacheAndReschedule() async {
        if case .loaded = health.state { await health.load() }
        if case .loaded(let runs) = health.state, !runs.isEmpty {
            let report = ReportEngine().weeklyReport(from: runs)
            ReportCache.save(ReportSnapshot.make(report: report, runs: runs, now: Date()))
        }
        await NotificationScheduler.rescheduleWeekly()
    }

    /// 옵저버 콜백(보조 경로) — 최신 세션을 요약해 즉시 알림.
    /// 등록 직후 최초 콜백·과거 기록 동기화로 중복 알림이 가지 않게
    /// 마지막으로 알린 세션 시각 + 최근 6시간 창으로 거른다.
    /// 잠금 후 ~10분이 지나면 HK 읽기가 실패한다 — 빈 결과로 조용히 끝난다.
    @MainActor
    private static func handleWorkoutUpdate(health: HealthStore) async {
        let defaults = UserDefaults.standard
        guard defaults.bool(forKey: NotifyKey.workoutEnabled) else { return }
        await health.load()
        guard case .loaded(let runs) = health.state, let latest = runs.first else { return }

        let startStamp = latest.start.timeIntervalSince1970
        guard startStamp > defaults.double(forKey: NotifyKey.lastWorkoutStart),
              Date().timeIntervalSince(latest.start) < 6 * 3_600 else { return }
        defaults.set(startStamp, forKey: NotifyKey.lastWorkoutStart)

        await NotificationScheduler.sendWorkoutInsight(
            body: NotificationScheduler.workoutBody(run: latest))
        // 주간 알림 본문도 최신 데이터로
        let report = ReportEngine().weeklyReport(from: runs)
        ReportCache.save(ReportSnapshot.make(report: report, runs: runs, now: Date()))
        await NotificationScheduler.rescheduleWeekly()
    }
}
