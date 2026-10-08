package com.jkpark.runwrap.engine

import com.jkpark.runwrap.engine.TrainingGuideEngine.HeartRateSample
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// (Android 전용) 기간 단위로 읽은 표본을 세션 구간으로 나누는 규칙 — 겹친 비율 배분과 경계를 검증한다.
class SessionSlicesTests {
    private val start = iso("2026-08-10T07:00:00+09:00")

    private fun at(sec: Double): Instant = instantSince1970(start.timeIntervalSince1970 + sec)

    @Test
    @DisplayName("구간에 걸친 누적 표본은 겹친 비율만큼만 더한다")
    fun prorates() {
        val totals = SessionSlices.Totals(listOf(
            SessionSlices.Amount(at(1_000.0), at(1_100.0), 300.0),  // 구간 밖 (다른 세션)
            SessionSlices.Amount(at(0.0), at(60.0), 200.0),         // 통째로 안
            SessionSlices.Amount(at(-60.0), at(60.0), 100.0),       // 절반만 안 → 50
            SessionSlices.Amount(at(540.0), at(660.0), 40.0),       // 절반만 안 → 20
        ))
        // 200 + 50 + 20 = 270
        assertEquals(270.0, totals.sum(at(0.0), at(600.0)))
        assertEquals(300.0, totals.sum(at(1_000.0), at(1_100.0)))
    }

    @Test
    @DisplayName("걸친 표본이 없으면 합은 nil이다")
    fun emptyIsNil() {
        val totals = SessionSlices.Totals(listOf(SessionSlices.Amount(at(0.0), at(60.0), 10.0)))
        assertNull(totals.sum(at(60.0), at(600.0)))   // 끝이 구간 시작에 닿기만 한 표본은 겹침 0
        assertNull(SessionSlices.Totals(emptyList()).sum(at(0.0), at(600.0)))
    }

    @Test
    @DisplayName("순간 표본은 구간 양 끝을 포함해 고른다")
    fun withinIsClosed() {
        val samples = listOf(-1.0, 0.0, 300.0, 600.0, 601.0).map { HeartRateSample(at(it), it) }
        assertEquals(listOf(0.0, 300.0, 600.0), SessionSlices.within(samples, at(0.0), at(600.0)).map { it.bpm })
        assertEquals(emptyList(), SessionSlices.within(samples, at(700.0), at(800.0)))
    }
}
