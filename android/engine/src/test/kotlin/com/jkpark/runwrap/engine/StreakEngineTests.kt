package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 스트릭 소형 카드 엔진 — 2주 미만 미노출 가드, 이정표(4·8·12·26·52) 톤 분기,
/// 일반 주의 헤드라인·캡션을 검증한다. 입력이 주 수·이번 주 러닝 여부뿐이라 날짜 주입이 필요 없다.
class StreakEngineTests {
    @Test
    @DisplayName("미노출 가드 — 0주·1주는 카드를 내지 않는다")
    fun underMinWeeksHidden() {
        assertNull(StreakEngine.card(streakWeeks = 0, ranThisWeek = false))
        assertNull(StreakEngine.card(streakWeeks = 1, ranThisWeek = true))
    }

    @Test
    @DisplayName("2주 연속 — 유지 톤 + 헤드라인·이어가기 캡션")
    fun twoWeeksSteady() {
        val card = assertNotNull(StreakEngine.card(streakWeeks = 2, ranThisWeek = false))
        assertEquals(2, card.weeks)
        assertEquals(RRTone.steady, card.tone)
        assertEquals("2주 연속 달리는 중", card.headline)
        assertEquals("이번 주도 한 번만 나가면 이어집니다", card.caption)
    }

    @Test
    @DisplayName("이정표 4·8·12·26·52주에 정확히 닿으면 좋아지는 중 톤")
    fun milestonesImproving() {
        for (weeks in listOf(4, 8, 12, 26, 52)) {
            val card = assertNotNull(StreakEngine.card(streakWeeks = weeks, ranThisWeek = false))
            assertEquals(RRTone.improving, card.tone)
            assertEquals("${weeks}주 연속 달리는 중", card.headline)
            assertTrue(card.caption.startsWith("${weeks}주 이정표!"))
        }
    }

    @Test
    @DisplayName("4주 이정표 캡션 — 한 달을 꽉 채웠다는 문구")
    fun fourWeeksCaption() {
        val card = assertNotNull(StreakEngine.card(streakWeeks = 4, ranThisWeek = false))
        assertEquals("4주 이정표! 한 달을 꽉 채우셨어요", card.caption)
    }

    @Test
    @DisplayName("이정표가 아닌 5주는 유지 톤")
    fun fiveWeeksSteady() {
        val card = assertNotNull(StreakEngine.card(streakWeeks = 5, ranThisWeek = false))
        assertEquals(RRTone.steady, card.tone)
        assertEquals("이번 주도 한 번만 나가면 이어집니다", card.caption)
    }

    @Test
    @DisplayName("이번 주에 이미 달렸으면 캡션이 다음 주로 넘어간다 (이슈 #195)")
    fun ranThisWeekCaption() {
        val ran = assertNotNull(StreakEngine.card(streakWeeks = 3, ranThisWeek = true))
        assertEquals("이번 주 몫은 채우셨어요, 다음 주에 이어 가요", ran.caption)
        assertEquals(RRTone.steady, ran.tone)

        val notYet = assertNotNull(StreakEngine.card(streakWeeks = 3, ranThisWeek = false))
        assertEquals("이번 주도 한 번만 나가면 이어집니다", notYet.caption)
    }

    @Test
    @DisplayName("이정표 캡션은 이번 주 러닝 여부와 무관하다 (이슈 #195)")
    fun milestoneIgnoresRanThisWeek() {
        val ran = assertNotNull(StreakEngine.card(streakWeeks = 8, ranThisWeek = true))
        val notYet = assertNotNull(StreakEngine.card(streakWeeks = 8, ranThisWeek = false))
        assertEquals(notYet, ran)
        assertEquals("8주 이정표! 두 달째 꾸준하시네요", ran.caption)
    }
}
