package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// 레벨 판정 엔진 검증 — 결정표(기획서 §3) 5개 규칙과 실데이터 승급 후보.
/// iOS `LevelEngineTests` 이식 (12개 전부).
class LevelEngineTest {
    private val now = Instant.parse("2026-08-10T09:00:00Z")

    private fun answers(
        q1: OnboardingAnswers.Q1,
        q2: OnboardingAnswers.Q2Longest? = null,
        q3: OnboardingAnswers.Q3Record? = null,
        q4: OnboardingAnswers.Q4Monthly? = null,
    ) = OnboardingAnswers(q1Experience = q1, q2Longest = q2, q3Record = q3, q4Monthly = q4)

    private fun run(daysAgo: Double, km: Double, minPerKm: Double) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1_000,
        avgHeartRate = null,
    )

    // MARK: - 결정표

    @Test
    fun `무경험이면 다른 답이 아무리 좋아도 런린이`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.NOVICE,
            q2 = OnboardingAnswers.Q2Longest.FULL_FINISHER,
            q3 = OnboardingAnswers.Q3Record.FULL_UNDER_430,
            q4 = OnboardingAnswers.Q4Monthly.OVER_200,
        ))
        assertEquals(RunnerLevel.BEGINNER, level)
    }

    @Test
    fun `풀 완주 + 풀 430 이내 + 월 200km 이상이면 런친놈`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.FULL_FINISHER,
            q3 = OnboardingAnswers.Q3Record.FULL_UNDER_430,
            q4 = OnboardingAnswers.Q4Monthly.OVER_200,
        ))
        assertEquals(RunnerLevel.ADVANCED, level)
    }

    @Test
    fun `월간 거리가 모자라면 런친놈 조건 미충족 - 런잘알`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.FULL_FINISHER,
            q3 = OnboardingAnswers.Q3Record.FULL_UNDER_430,
            q4 = OnboardingAnswers.Q4Monthly.HUNDRED_TO_200,
        ))
        assertEquals(RunnerLevel.INTERMEDIATE, level)
    }

    @Test
    fun `하프에서 풀 사이 경험이면 런잘알`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.HALF_TO_FULL,
        ))
        assertEquals(RunnerLevel.INTERMEDIATE, level)
    }

    @Test
    fun `풀 완주지만 기록과 월간 답이 없으면 런잘알`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.FULL_FINISHER,
        ))
        assertEquals(RunnerLevel.INTERMEDIATE, level)
    }

    @Test
    fun `10km 기록이 1시간 이내면 런잘알`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.FIVE_TO_TEN,
            q3 = OnboardingAnswers.Q3Record.TEN_UNDER_60,
        ))
        assertEquals(RunnerLevel.INTERMEDIATE, level)
    }

    @Test
    fun `최장 5km 미만이면 런린이`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.UNDER_5,
        ))
        assertEquals(RunnerLevel.BEGINNER, level)
    }

    @Test
    fun `10km 기록이 1시간을 넘으면 런린이`() {
        val level = LevelEngine.decide(answers(
            q1 = OnboardingAnswers.Q1.EXPERIENCED,
            q2 = OnboardingAnswers.Q2Longest.FIVE_TO_TEN,
            q3 = OnboardingAnswers.Q3Record.TEN_OVER_60,
        ))
        assertEquals(RunnerLevel.BEGINNER, level)
    }

    // MARK: - 실데이터 승급 후보

    @Test
    fun `최근 4주 내 10km 60분 이내 기록이 있으면 런잘알 승급 후보`() {
        // 10km × 5.5분/km = 55분 ≤ 60분, 8일 전 — 4주 창 안
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 5.5))
        assertEquals(RunnerLevel.INTERMEDIATE,
            LevelEngine.promotionCandidate(RunnerLevel.BEGINNER, runs, now))
    }

    @Test
    fun `10km 기록이 60분을 넘으면 승급 후보가 아니다`() {
        // 10km × 6.5분/km = 65분 > 60분
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 6.5))
        assertNull(LevelEngine.promotionCandidate(RunnerLevel.BEGINNER, runs, now))
    }

    @Test
    fun `4주 밖 기록은 승급 근거가 아니다`() {
        val runs = listOf(run(daysAgo = 30.0, km = 10.0, minPerKm = 5.5))
        assertNull(LevelEngine.promotionCandidate(RunnerLevel.BEGINNER, runs, now))
    }

    @Test
    fun `이미 최상위 레벨이면 승급 후보가 없다`() {
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 5.5))
        assertNull(LevelEngine.promotionCandidate(RunnerLevel.ADVANCED, runs, now))
    }
}
