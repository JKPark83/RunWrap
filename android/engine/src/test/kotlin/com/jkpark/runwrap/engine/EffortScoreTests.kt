package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// Apple 운동 노력도 (이슈 #178) — 척도 구간 라벨, 출처 라벨, 권한 묶음 포함, 데모 합성 범위를 검증한다.
/// (Android: 권한 묶음 테스트 1건은 HealthKit 전용이라 ios-only)
@DisplayName("Apple 운동 노력도")
class EffortScoreTests {
    /// 고정 시각 — 합성 시드가 시작 시각 비트를 섞으므로 now를 주입해 결정론적으로 만든다
    private val now = iso("2026-09-30T07:00:00+09:00")

    @Test
    @DisplayName("구간 라벨 — Apple 척도 1~3 편안 · 4~6 보통 · 7~8 힘듦 · 9~10 전력")
    fun labelBoundaries() {
        // 경계는 3.5·6.5·8.5 — 정수 척도는 각자 Apple 구간에 떨어진다
        assertEquals("편안", EffortScore.label(1.0))
        assertEquals("편안", EffortScore.label(3.0))
        assertEquals("보통", EffortScore.label(4.0))
        assertEquals("보통", EffortScore.label(6.0))
        assertEquals("힘듦", EffortScore.label(7.0))
        assertEquals("힘듦", EffortScore.label(8.0))
        assertEquals("전력", EffortScore.label(9.0))
        assertEquals("전력", EffortScore.label(10.0))
    }

    @Test
    @DisplayName("출처 라벨 — 직접 입력과 Apple 추정을 구분하고, label은 score를 따른다")
    fun sourceLabel() {
        val manual = EffortScore(score = 7.0, isEstimated = false)
        val estimated = EffortScore(score = 5.0, isEstimated = true)
        assertEquals("직접 입력", manual.sourceLabel)
        assertEquals("Apple 추정", estimated.sourceLabel)
        assertEquals("힘듦", manual.label)
        assertEquals("보통", estimated.label)
    }

    @Test
    @DisplayName("데모 합성 — 노력도는 4~8 정수의 Apple 추정이고 같은 세션이면 같은 값")
    fun syntheticEffortInRange() {
        val profile = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.fallback,
                                       restingHR = null, zoneMethod = HeartRateZoneMethod.percentMax)
        // 시드만 바꿔 여러 세션을 만든다 — 4 + Int(unit × 5)라 4...8 밖으로 나가지 않는다
        for (index in 1..20) {
            val run = RunSummary(id = DemoData.demoID(index), start = now, durationSec = 3_000.0,
                                 distanceMeters = 8_000.0, avgHeartRate = 150.0)
            val effort = assertNotNull(WorkoutDetailStore.synthetic(run, heartRate = profile).effort)
            assertTrue(effort.isEstimated)
            assertTrue(effort.score in 4.0..8.0)
            assertEquals(effort.score.swiftRounded(), effort.score)
            assertEquals(effort, WorkoutDetailStore.synthetic(run, heartRate = profile).effort)
        }
    }
}
