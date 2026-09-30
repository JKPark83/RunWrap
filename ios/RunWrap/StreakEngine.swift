import Foundation

/// 리포트 홈 스트릭 소형 카드 재료 — 연속 달린 주 수(`WeeklyReport.streakWeeks`)를
/// 헤드라인·캡션·톤으로 바꾼다 (이슈 #184). 레벨 게이트와 무관한 소형 카드라
/// `ReportGate.ReportCard`에는 넣지 않는다. 순수 로직: Foundation만 쓴다.
///
/// 왜 2주 미만은 미노출인가: 1주 연속은 "이번(지난) 주에 한 번 달렸다"와 같은 말이라
/// 연속이라 부를 근거가 없다. 표본이 부족하면 지표를 내지 않는 원칙대로 nil을 준다.

struct StreakCard: Equatable {
    let weeks: Int
    let headline: String
    let caption: String
    let tone: RRTone
}

enum StreakEngine {
    /// 카드를 내는 최소 연속 주 수 — 1주는 연속이 아니다
    static let minWeeks = 2
    /// 정확히 닿았을 때 축하하는 이정표 (4주=한 달, 8주=두 달, 12주=한 분기, 26주=반년, 52주=1년)
    static let milestones: Set<Int> = [4, 8, 12, 26, 52]

    /// 연속 주 수가 2 미만이면 nil — 카드를 아예 그리지 않는다
    static func card(streakWeeks: Int) -> StreakCard? {
        guard streakWeeks >= minWeeks else { return nil }
        let headline = "\(streakWeeks)주 연속 달리는 중"
        if milestones.contains(streakWeeks) {
            // 존댓말 위트 톤 (기획서 §4.11)
            return StreakCard(weeks: streakWeeks, headline: headline,
                              caption: "\(streakWeeks)주 이정표! \(milestonePhrase(streakWeeks))",
                              tone: .improving)
        }
        // 진행 중인 이번 주는 끊김으로 세지 않는다(ReportEngine.streakWeeks) — 이번 주 한 번이면 이어진다
        return StreakCard(weeks: streakWeeks, headline: headline,
                          caption: "이번 주도 한 번만 나가면 이어집니다",
                          tone: .steady)
    }

    private static func milestonePhrase(_ weeks: Int) -> String {
        switch weeks {
        case 4: return "한 달을 꽉 채우셨어요"
        case 8: return "두 달째 꾸준하시네요"
        case 12: return "한 분기를 통째로 달리셨어요"
        case 26: return "반년 개근, 이 정도면 습관이십니다"
        default: return "1년 내내 달리셨어요. 존경합니다"
        }
    }
}
