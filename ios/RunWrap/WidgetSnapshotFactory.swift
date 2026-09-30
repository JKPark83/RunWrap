import Foundation

/// 위젯 스냅샷 생성 (이슈 #181) — 앱 타깃 전용.
/// WidgetSnapshot 본체는 위젯 확장에도 컴파일되는데, 위젯 타깃에는 BatteryReport·RunSummary·
/// TodayVerdictEngine 같은 엔진 타입이 없다. 앱 코드를 위젯으로 끌고 가지 않으려고 만드는 쪽만 여기 둔다.
extension WidgetSnapshot {
    /// 배터리 + 원본 기록에서 스냅샷을 만든다 (순수 함수 — 테스트 대상).
    /// 헤드라인은 홈 판단 카드와 같은 판정(TodayVerdictEngine.headline)이라 위젯이 홈의 거울이 된다
    static func make(battery: BatteryReport?, runs: [RunSummary], level: RunnerLevel,
                     now: Date) -> WidgetSnapshot {
        // ReportSnapshot.make와 같은 달력 창 — 알림과 위젯의 "이번 주"가 어긋나지 않게 (이슈 #75)
        let windowStart = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 86_400))
        let weekRuns = runs.filter { $0.start >= windowStart && $0.start < now }
        let (_, headline) = TodayVerdictEngine.headline(battery: battery)
        return WidgetSnapshot(generatedAt: now,
                              batteryLevel: battery?.level,
                              batteryTone: battery?.tone,
                              batteryLabel: battery?.statusLabel,
                              headline: headline,
                              weekKm: weekRuns.compactMap(\.distanceKm).reduce(0, +),
                              runCount: weekRuns.count,
                              showsDistanceNumbers: ReportGate.showsNumbers(.distance, level: level))
    }
}
