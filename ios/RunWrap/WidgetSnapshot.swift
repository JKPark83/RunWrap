import Foundation

/// 위젯 스냅샷 (이슈 #181) — 위젯 확장은 HealthKit을 읽지 못하므로 앱이 계산한 결과 중
/// 위젯에 필요한 최소만 App Group 컨테이너에 JSON으로 남긴다. ReportCache와 같은 원칙:
/// 화면 전체가 아니라 위젯 다섯 패밀리가 그리는 재료만. 원본은 항상 앱이 다시 계산한다.
///
/// 앱·위젯 두 타깃에 함께 컴파일된다 — 위젯 타깃에는 엔진 타입(BatteryReport·RunSummary 등)이
/// 없으므로 스냅샷을 만드는 `make`는 앱 전용 파일(WidgetSnapshotFactory.swift)에 둔다.
struct WidgetSnapshot: Codable, Equatable {
    let generatedAt: Date
    let batteryLevel: Int?        // 0–100. 회복 신호가 없어 배터리를 못 내면 nil
    let batteryTone: RRTone?      // 배터리 톤 (숫자 색). 헤드라인 톤은 대기질 상한(#183)으로 달라질 수 있다
    let batteryLabel: String?     // BatteryReport.statusLabel
    let headline: String          // TodayVerdictEngine.headline(battery:air:).1 — 배터리 없으면 중립 문구
    let weekKm: Double            // 6일 전 자정 ~ now (ReportSnapshot과 같은 창, 이슈 #75)
    let runCount: Int             // 같은 창의 러닝 횟수
    let showsDistanceNumbers: Bool // 런린이는 거리 수치 대신 횟수만 (ReportGate.showsNumbers(.distance))

    static let staleAfter: TimeInterval = 24 * 3_600
    /// 24시간이 지나면 수치를 흐리게 하고 갱신 안내 — 며칠 전 배터리가 오늘 것처럼 보이지 않게
    func isStale(at now: Date) -> Bool { now.timeIntervalSince(generatedAt) > Self.staleAfter }
}
