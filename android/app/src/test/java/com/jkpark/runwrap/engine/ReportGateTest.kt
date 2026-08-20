package com.jkpark.runwrap.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// 레벨 게이트 매트릭스 검증 — 기획서 §4 표 그대로인지 확인한다.
class ReportGateTest {
    @Test
    fun `공통 카드 - 거리·배터리·크로스·가이드는 전 레벨 노출`() {
        for (level in RunnerLevel.entries) {
            for (card in listOf(ReportCard.DISTANCE, ReportCard.BATTERY,
                                ReportCard.CROSS_TRAINING, ReportCard.TRAINING_GUIDE)) {
                assertTrue("$card @ $level", ReportGate.shows(card, level))
            }
        }
    }

    @Test
    fun `런린이 게이트 - ACWR·EF·VO2max·주법은 숨기고 걷뛰만 연다`() {
        for (card in listOf(ReportCard.ACWR, ReportCard.EFFICIENCY,
                            ReportCard.VO2_MAX, ReportCard.FORM)) {
            assertFalse("$card", ReportGate.shows(card, RunnerLevel.BEGINNER))
            assertTrue("$card", ReportGate.shows(card, RunnerLevel.INTERMEDIATE))
            assertTrue("$card", ReportGate.shows(card, RunnerLevel.ADVANCED))
        }
        assertTrue(ReportGate.shows(ReportCard.WALK_RUN, RunnerLevel.BEGINNER))
        assertFalse(ReportGate.shows(ReportCard.WALK_RUN, RunnerLevel.INTERMEDIATE))
        assertFalse(ReportGate.shows(ReportCard.WALK_RUN, RunnerLevel.ADVANCED))
    }

    @Test
    fun `수치 게이트 - 런린이 주간 거리는 카드는 나오되 숫자는 감춘다`() {
        assertTrue(ReportGate.shows(ReportCard.DISTANCE, RunnerLevel.BEGINNER))
        assertFalse(ReportGate.showsNumbers(ReportCard.DISTANCE, RunnerLevel.BEGINNER))
        assertTrue(ReportGate.showsNumbers(ReportCard.DISTANCE, RunnerLevel.INTERMEDIATE))
        // 노출 자체가 막힌 카드는 수치도 당연히 막힌다
        assertFalse(ReportGate.showsNumbers(ReportCard.ACWR, RunnerLevel.BEGINNER))
    }
}
