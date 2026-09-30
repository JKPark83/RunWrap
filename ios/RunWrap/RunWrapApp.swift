import SwiftUI
import WidgetKit

/// 앱 진입점 + 알림 수명주기 (계획서 M8).
/// HealthStore를 앱이 소유해 scenePhase 훅에서 재계산→캐시→주간 알림 재예약을 돌린다 —
/// 포그라운드 재계산이 1차 경로, HealthKit 옵저버(러닝 종료 즉시 알림)는 보조 경로.
@main
struct RunWrapApp: App {
    @StateObject private var health: HealthStore
    /// 도감 — 파일에서 한 번 읽어 앱 수명 동안 들고 간다 (기획서 §5)
    @StateObject private var collection = CollectionStore()
    /// 진행도 CloudKit 백업·복원 (이슈 #29) — 신규 설치 복원은 RootView가,
    /// 주기적 백업은 백그라운드 진입 훅이 굴린다
    @StateObject private var backup = ProgressBackupStore()
    @Environment(\.scenePhase) private var scenePhase

    /// 워크아웃 옵저버는 기동 직후 등록해야 백그라운드 전달로 깨어났을 때 콜백을 받는다
    /// (애플 문서: HKObserverQuery·enableBackgroundDelivery는 didFinishLaunching에서 설정).
    /// App의 init은 프로세스 기동 직후 메인 스레드에서 돌아 그 시점과 같다 — 뷰의 .task는
    /// 백그라운드 기동에서 늦거나 돌지 않을 수 있어 여기로 옮겼다 (이슈 #155).
    /// UIKit 앱 델리게이트 어댑터는 쓰지 않는다 — SwiftUI 수명주기만으로 같은 시점을 잡을 수 있다
    init() {
        let health = HealthStore()
        _health = StateObject(wrappedValue: health)
        health.startObservingWorkouts { [weak health] in
            guard let health else { return }
            await Self.handleWorkoutUpdate(health: health)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(health)
                .environmentObject(collection)
                .environmentObject(backup)
                // 위젯 스냅샷 쓰기 (이슈 #181). vitals를 신호로 삼는 이유: HealthStore.load()는
                // state = .loaded 다음에 vitals를 채우므로(데모 경로도 state 직후 vitals) $vitals가
                // 배터리 재료까지 갖춰졌다는 신호다. @Published는 willSet에서 방출하므로
                // health.vitals(아직 이전 값)가 아니라 클로저 인자 vitals를 쓴다.
                // 첫 기동·포그라운드 진입·옵저버 콜백이 전부 load()를 거치므로 쓰는 곳은 여기 한 곳뿐이다
                .onReceive(health.$vitals.dropFirst()) { vitals in
                    Self.publishWidgetSnapshot(health: health, vitals: vitals)
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
    /// 첫 로드가 실패(.failed)로 끝났으면 포그라운드 복귀 때 다시 시도한다 (이슈 #58).
    /// 설정 앱에서 알림을 꺼 두고 돌아왔으면 켜진 알림 토글부터 끈다 (이슈 #94)
    private func refreshCacheAndReschedule() async {
        await NotificationScheduler.disableTogglesIfDenied()
        switch health.state {
        case .loaded, .failed: await health.load()
        default: break
        }
        // 데모 수치는 캐시하지 않는다 — 주간 알림 본문으로 나가면 안 된다 (이슈 #44)
        if case .loaded(let runs) = health.state, !runs.isEmpty, !DemoMode.isActive {
            let report = ReportEngine().weeklyReport(from: runs)
            ReportCache.save(ReportSnapshot.make(report: report, runs: runs,
                                                 level: Self.currentLevel, now: Date()))
        } else if case .loaded(let runs) = health.state, runs.isEmpty, !DemoMode.isActive {
            // 기록이 비었으면(삭제·권한 회수) 옛 스냅샷이 알림으로 나가지 않게 지운다 (이슈 #61)
            ReportCache.clear()
        }
        await NotificationScheduler.rescheduleWeekly()
    }

    /// 옵저버 콜백(보조 경로) — 최신 세션을 요약해 즉시 알림.
    /// 등록 직후 최초 콜백·과거 기록 동기화로 중복 알림이 가지 않게
    /// 마지막으로 알린 세션 시각 + 최근 6시간 창으로 거른다.
    /// 잠금 후 ~10분이 지나면 기기 데이터 보호로 HK 읽기가 오류를 던진다 — 보호 데이터에
    /// 접근할 수 없으면 읽기 자체를 건너뛴다. 그래도 실패하면 기존 목록은 유지되고
    /// 사유만 lastError에 남는다 (이슈 #58).
    @MainActor
    private static func handleWorkoutUpdate(health: HealthStore) async {
        let defaults = UserDefaults.standard
        guard defaults.bool(forKey: NotifyKey.workoutEnabled),
              UIApplication.shared.isProtectedDataAvailable else { return }
        await health.load()
        guard case .loaded(let runs) = health.state, let latest = runs.first else { return }

        let startStamp = latest.start.timeIntervalSince1970
        guard startStamp > defaults.double(forKey: NotifyKey.lastWorkoutStart),
              Date().timeIntervalSince(latest.start) < 6 * 3_600 else { return }
        defaults.set(startStamp, forKey: NotifyKey.lastWorkoutStart)

        await NotificationScheduler.sendWorkoutInsight(
            body: NotificationScheduler.workoutBody(run: latest))
        // 주간 알림 본문도 최신 데이터로 — 데모 수치는 캐시하지 않는다 (이슈 #44)
        if !DemoMode.isActive {
            let report = ReportEngine().weeklyReport(from: runs)
            ReportCache.save(ReportSnapshot.make(report: report, runs: runs,
                                                 level: Self.currentLevel, now: Date()))
        }
        await NotificationScheduler.rescheduleWeekly()
    }

    /// 위젯이 그릴 스냅샷을 App Group에 남기고 타임라인을 다시 불러오게 한다 (이슈 #181).
    /// 데모 모드도 쓴다 — 위젯은 홈의 거울이라 홈에 보이는 수치를 그대로 비춘다. ReportCache(#44)가
    /// 데모를 막는 이유는 합성 수치가 나중에 예약 발송되는 주간 알림 본문에 실데이터처럼 실리기
    /// 때문인데, 위젯은 데모를 끄고 앱이 다시 load()하는 즉시 실데이터로 덮인다
    @MainActor
    private static func publishWidgetSnapshot(health: HealthStore, vitals: VitalsSnapshot?) {
        guard case .loaded(let runs) = health.state else { return }
        if runs.isEmpty {
            // 기록이 비었으면(삭제·권한 회수) 옛 수치가 위젯에 남지 않게 지운다 (ReportCache #61과 같은 이유)
            WidgetSnapshotStore.clear()
        } else {
            let now = Date()
            let battery = vitals.flatMap { BatteryEngine.compute(vitals: $0, runs: runs, now: now) }
            WidgetSnapshotStore.save(WidgetSnapshot.make(battery: battery, runs: runs,
                                                         level: Self.currentLevel,
                                                         air: AirQualityStore.cachedFreshGrade(now: now),
                                                         now: now))
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// 저장된 러너 레벨 — 화면의 @AppStorage 기본값과 같이 미설정이면 런린이 (이슈 #124)
    private static var currentLevel: RunnerLevel {
        RunnerLevel(rawValue: UserDefaults.standard.string(forKey: ProfileKey.levelV2) ?? "") ?? .beginner
    }
}
