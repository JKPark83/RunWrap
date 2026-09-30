import Foundation
import Testing
@testable import RunWrap

/// 추세 라인 차트 VoiceOver 요약 — 첫 점 → 마지막 점을 콜아웃과 같은 문자열로 읽는지 검증한다 (이슈 #160).
/// 시각에 의존하지 않는 순수 문자열 조립이라 고정 시각 주입은 필요 없다.
/// TrendLineChart가 View(MainActor 격리)라 스위트도 MainActor에서 돈다.
@MainActor
@Suite("추세 차트 VoiceOver 요약")
struct RRChartsTests {
    private let valueText: (Double) -> String = { String(format: "%.1f", $0) }

    @Test("점 2개 이상 — 첫 점과 마지막 점만 '…에서 …'로 잇고, 라벨이 없으면 수치만 읽는다")
    func summaryFirstToLast() {
        let points = [12.3, 13.0, 14.1]
        let labels = ["8월 1째주", "8월 2째주", "8월 4째주"]
        // 가운데 점(13.0)은 요약에서 빠진다
        #expect(TrendLineChart.accessibilitySummary(points: points, labels: labels,
                                                    valueText: valueText)
                == "8월 1째주 12.3에서 8월 4째주 14.1")
        // pointLabels 미지정 → 콜아웃처럼 수치만
        #expect(TrendLineChart.accessibilitySummary(points: points, labels: nil,
                                                    valueText: valueText)
                == "12.3에서 14.1")
    }

    @Test("점 1개 — 그 점 하나만 읽는다")
    func summarySinglePoint() {
        #expect(TrendLineChart.accessibilitySummary(points: [14.1], labels: ["8월 4째주"],
                                                    valueText: valueText)
                == "8월 4째주 14.1")
    }

    @Test("점 0개 — 읽을 값이 없으면 빈 문자열(VoiceOver는 라벨만 읽는다)")
    func summaryEmpty() {
        #expect(TrendLineChart.accessibilitySummary(points: [], labels: [],
                                                    valueText: valueText) == "")
    }
}
