import SwiftUI
import WidgetKit

/// 위젯 확장 진입점 (이슈 #181) — 지금은 "오늘 컨디션" 위젯 하나.
/// 위젯 확장은 HealthKit·UserDefaults·네트워크를 쓰지 않는다 — 앱이 App Group에 남긴
/// 스냅샷(WidgetSnapshotStore)만 읽는다.
@main
struct RunWrapWidgetBundle: WidgetBundle {
    var body: some Widget {
        RunWrapStatusWidget()
    }
}
