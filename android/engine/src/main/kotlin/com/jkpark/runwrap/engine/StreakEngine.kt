package com.jkpark.runwrap.engine

/// 리포트 홈 스트릭 소형 카드 재료 — 연속 달린 주 수(`WeeklyReport.streakWeeks`)를
/// 헤드라인·캡션·톤으로 바꾼다 (이슈 #184). 레벨 게이트와 무관한 소형 카드라
/// `ReportGate.ReportCard`에는 넣지 않는다. 순수 로직: Foundation만 쓴다.
///
/// 왜 2주 미만은 미노출인가: 1주 연속은 "이번(지난) 주에 한 번 달렸다"와 같은 말이라
/// 연속이라 부를 근거가 없다. 표본이 부족하면 지표를 내지 않는 원칙대로 nil을 준다.
///
/// 캡션은 이번 주에 이미 달렸는지(`WeeklyReport.ranThisWeek`)로 가른다 (이슈 #195) — 연속 주 수는
/// 진행 중인 이번 주를 끊김으로 세지 않아, 주 수만으로는 "이번 주 몫을 채웠는지"를 알 수 없다.

data class StreakCard(
    val weeks: Int,
    val headline: String,
    val caption: String,
    val tone: RRTone,
)

object StreakEngine {
    /// 카드를 내는 최소 연속 주 수 — 1주는 연속이 아니다
    const val minWeeks = 2
    /// 정확히 닿았을 때 축하하는 이정표 (4주=한 달, 8주=두 달, 12주=한 분기, 26주=반년, 52주=1년)
    val milestones: Set<Int> = setOf(4, 8, 12, 26, 52)

    /// 연속 주 수가 2 미만이면 nil — 카드를 아예 그리지 않는다
    fun card(streakWeeks: Int, ranThisWeek: Boolean): StreakCard? {
        if (streakWeeks < minWeeks) return null
        val headline = "${streakWeeks}주 연속 달리는 중"
        if (streakWeeks in milestones) {
            // 존댓말 위트 톤 (기획서 §4.11)
            return StreakCard(weeks = streakWeeks, headline = headline,
                              caption = "${streakWeeks}주 이정표! ${milestonePhrase(streakWeeks)}",
                              tone = RRTone.improving)
        }
        // 진행 중인 이번 주는 끊김으로 세지 않는다(ReportEngine.streakWeeks) — 이번 주 한 번이면 이어진다.
        // 이미 달린 주에 "한 번만 나가면"은 틀린 말이라 다음 주로 넘긴다 (이슈 #195)
        return StreakCard(weeks = streakWeeks, headline = headline,
                          caption = if (ranThisWeek) "이번 주 몫은 채우셨어요, 다음 주에 이어 가요"
                                    else "이번 주도 한 번만 나가면 이어집니다",
                          tone = RRTone.steady)
    }

    private fun milestonePhrase(weeks: Int): String = when (weeks) {
        4 -> "한 달을 꽉 채우셨어요"
        8 -> "두 달째 꾸준하시네요"
        12 -> "한 분기를 통째로 달리셨어요"
        26 -> "반년 개근, 이 정도면 습관이십니다"
        else -> "1년 내내 달리셨어요. 존경합니다"
    }
}
