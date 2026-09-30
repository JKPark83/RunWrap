import SwiftUI
import WidgetKit

/// "오늘 컨디션" 위젯 (이슈 #181) — 체력 배터리·오늘 판정·이번 주 거리를 홈·잠금화면에 비춘다.
/// 위젯 확장은 HealthKit·UserDefaults·네트워크를 쓰지 않는다. 재료는 앱이 App Group 컨테이너에
/// 남긴 WidgetSnapshot 하나뿐이고, 값은 여기서 새로 계산하지 않는다.
struct RunWrapStatusWidget: Widget {
    let kind = "RunWrapStatus"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
        }
        .configurationDisplayName("오늘 컨디션")
        .description("체력 배터리와 오늘 판정, 이번 주 거리를 한눈에 봐요.")
        .supportedFamilies([.systemSmall, .systemMedium,
                            .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct StatusEntry: TimelineEntry {
    let date: Date
    /// 앱이 아직 스냅샷을 남기지 않았으면 nil — 뷰가 "앱을 열어" 안내로 떨어진다
    let snapshot: WidgetSnapshot?
    /// 스냅샷이 24시간을 넘겼는지 — 수치를 흐리게 하고 갱신 안내를 얹는다
    let isStale: Bool
}

/// 타임라인은 앱이 민다 — 앱이 스냅샷을 쓸 때마다 WidgetCenter.reloadAllTimelines()를 부르므로
/// 위젯 스스로 주기 갱신하지 않는다(.never). 대신 신선한 스냅샷에는 24시간 뒤 '오래됨' 엔트리를
/// 하나 더 붙여, 앱을 열지 않아도 며칠 전 배터리가 오늘 것처럼 보이지 않게 한다.
struct StatusProvider: TimelineProvider {
    /// 위젯 갤러리·로딩용 샘플 — 실제 수치가 아니다 (placeholder는 WidgetKit이 흐림 처리한다)
    static func sample(at date: Date) -> WidgetSnapshot {
        WidgetSnapshot(generatedAt: date, batteryLevel: 72, batteryTone: .steady,
                       batteryLabel: "충전 충분", headline: "평소대로 가셔도 돼요",
                       weekKm: 12.4, runCount: 3, showsDistanceNumbers: true)
    }

    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: Date(), snapshot: Self.sample(at: Date()), isStale: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        let now = Date()
        let snapshot = WidgetSnapshotStore.load() ?? Self.sample(at: now)
        completion(StatusEntry(date: now, snapshot: snapshot, isStale: snapshot.isStale(at: now)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        let now = Date()
        let snapshot = WidgetSnapshotStore.load()
        var entries = [StatusEntry(date: now, snapshot: snapshot,
                                   isStale: snapshot?.isStale(at: now) ?? false)]
        if let snapshot, !snapshot.isStale(at: now) {
            entries.append(StatusEntry(date: snapshot.generatedAt.addingTimeInterval(WidgetSnapshot.staleAfter),
                                       snapshot: snapshot, isStale: true))
        }
        completion(Timeline(entries: entries, policy: .never))
    }
}
