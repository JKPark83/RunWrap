package com.jkpark.runwrap.engine

import com.jkpark.runwrap.engine.SleepBlocks.Interval
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 밤 수면·낮잠 분리, 기상일 기준 밤 묶기 검증 (이슈 #99, #107)
class SleepBlocksTests {
    /// 2026-08-09 23:00 KST — 밤 수면 시작 기준 시각
    private val bedtime = iso("2026-08-09T14:00:00Z")

    private fun adding(date: Instant, sec: Double): Instant = instantSince1970(date.timeIntervalSince1970 + sec)

    /// 기준 시각으로부터 시(hour) 단위 오프셋 구간
    private fun interval(fromHour: Double, toHour: Double): Interval =
        Interval(start = adding(bedtime, fromHour * 3_600), end = adding(bedtime, toHour * 3_600))

    @Test
    @DisplayName("낮잠 분리 — 밤 6시간 + 같은 날 오후 낮잠 90분이면 밤 6시간만 인정")
    fun napIsExcluded() {
        // 밤 23:00–05:00(6시간), 낮잠 14:00–15:30(+15h–+16.5h) → 간격 9시간 > 2시간이라 별도 블록
        // 가장 긴 블록 = 밤 6시간
        val main = SleepBlocks.mainBlock(listOf(interval(15.0, 16.5), interval(0.0, 6.0)))
        assertEquals(1, main.size)
        assertEquals(bedtime, main.firstOrNull()?.start)
        assertEquals(6 * 3_600.0, SleepBlocks.asleepSec(main))
    }

    @Test
    @DisplayName("새벽 각성 — 20분 깼다 다시 잔 밤은 한 블록, 깬 시간은 합계에서 빠진다")
    fun shortWakeStaysInOneBlock() {
        // 23:00–03:00(4시간) + 03:20–06:00(2시간 40분) → 간격 20분 ≤ 2시간이라 한 블록
        // 합계 4h + 2h40m = 6h40m = 24_000초
        val resumed = Interval(start = adding(bedtime, 15_600.0), end = adding(bedtime, 25_200.0))
        val main = SleepBlocks.mainBlock(listOf(interval(0.0, 4.0), resumed))
        assertEquals(2, main.size)
        assertEquals(24_000.0, SleepBlocks.asleepSec(main))
    }

    @Test
    @DisplayName("이중 기록 — 워치·아이폰이 겹쳐 기록한 구간은 한 번만 센다")
    fun overlappingSourcesMerge() {
        // 워치 23:00–05:00, 아이폰 22:30–04:00(−0.5h–+5h) → 병합 22:30–05:00 = 6.5시간
        val main = SleepBlocks.mainBlock(listOf(interval(0.0, 6.0), interval(-0.5, 5.0)))
        assertEquals(1, main.size)
        assertEquals(6.5 * 3_600, SleepBlocks.asleepSec(main))
    }

    @Test
    @DisplayName("빈 입력 — 구간이 없으면 빈 블록")
    fun emptyInput() {
        assertTrue(SleepBlocks.mainBlock(emptyList()).isEmpty())
        assertTrue(SleepBlocks.nightBlocks(emptyList(), zone = seoul).isEmpty())
    }

    // MARK: - 기상일 기준 밤 묶기 (이슈 #107)

    /// 기상일 계산용 달력 — 기기 시간대와 무관하게 KST 고정
    private val seoul = KST

    /// KST 벽시계 문자열("2026-08-09T22:00") → Date
    private fun kst(text: String): Instant = iso("$text:00+09:00")

    private fun span(from: String, to: String): Interval = Interval(start = kst(from), end = kst(to))

    @Test
    @DisplayName("자정 넘김 — 22:00–06:00을 15분 표본 32개로 쪼갠 밤은 한 블록으로 06:00의 기상일에 들어간다")
    fun stageSamplesAcrossMidnightStayTogether() {
        // 워치 스테이지 표본처럼 15분씩 32개 → 자정 전에 끝난 8개(22:00–24:00)도 전날로 떨어지지 않는다
        val start = kst("2026-08-09T22:00")
        val samples = (0 until 32).map { index ->
            Interval(start = adding(start, index * 900.0), end = adding(start, (index + 1) * 900.0))
        }
        val nights = SleepBlocks.nightBlocks(samples, zone = seoul)
        assertEquals(1, nights.size)
        val night = assertNotNull(nights.firstOrNull())
        assertEquals(kst("2026-08-10T00:00"), night.wakeDay)
        assertEquals(kst("2026-08-09T22:00"), night.block.firstOrNull()?.start)  // 취침 시각이 00:00으로 밀리지 않는다
        assertEquals(8 * 3_600.0, SleepBlocks.asleepSec(night.block))  // 32 × 15분 = 8시간
    }

    @Test
    @DisplayName("같은 기상일 낮잠 — 밤 7시간 + 오후 낮잠 1시간(간격 2시간 초과)이면 밤만 남는다")
    fun napOnSameWakeDayIsDropped() {
        // 밤 23:00–06:00(7h), 낮잠 14:00–15:00(1h) → 간격 8시간이라 별도 블록, 둘 다 기상일 8/10
        val nights = SleepBlocks.nightBlocks(listOf(span("2026-08-10T14:00", "2026-08-10T15:00"),
                                                    span("2026-08-09T23:00", "2026-08-10T06:00")),
                                             zone = seoul)
        assertEquals(1, nights.size)
        assertEquals(kst("2026-08-10T00:00"), nights.firstOrNull()?.wakeDay)
        assertEquals(7 * 3_600.0, SleepBlocks.asleepSec(nights.firstOrNull()?.block ?: emptyList()))
    }

    @Test
    @DisplayName("이틀 연속 밤 — 기상일 2개가 오름차순으로 나온다")
    fun consecutiveNightsAreSortedByWakeDay() {
        // 입력은 일부러 역순 — 8/10 23:00–8/11 06:00, 8/9 23:00–8/10 06:00
        val nights = SleepBlocks.nightBlocks(listOf(span("2026-08-10T23:00", "2026-08-11T06:00"),
                                                    span("2026-08-09T23:00", "2026-08-10T06:00")),
                                             zone = seoul)
        assertEquals(listOf(kst("2026-08-10T00:00"), kst("2026-08-11T00:00")), nights.map { it.wakeDay })
        assertEquals(listOf(7 * 3_600.0, 7 * 3_600.0), nights.map { SleepBlocks.asleepSec(it.block) })
    }

    @Test
    @DisplayName("초저녁 잠 분리 — 자정 전에 끝난 잠은 자정 넘긴 밤과 다른 블록, 기상일이 같으면 긴 쪽만")
    fun eveningSleepSplitsFromNight() {
        // 8/9 19:00–21:00(2h, 기상일 8/9) → 간격 2.5시간 > 2시간이라 8/9 23:30–8/10 06:30 밤(7h)과 분리
        // 8/10 20:00–22:00(2h, 기상일 8/10) → 같은 기상일의 밤(7h)보다 짧아 제외
        val nights = SleepBlocks.nightBlocks(listOf(span("2026-08-09T19:00", "2026-08-09T21:00"),
                                                    span("2026-08-09T23:30", "2026-08-10T06:30"),
                                                    span("2026-08-10T20:00", "2026-08-10T22:00")),
                                             zone = seoul)
        assertEquals(listOf(kst("2026-08-09T00:00"), kst("2026-08-10T00:00")), nights.map { it.wakeDay })
        assertEquals(listOf(2 * 3_600.0, 7 * 3_600.0), nights.map { SleepBlocks.asleepSec(it.block) })
        // 밤 블록은 초저녁 잠과 합쳐지지 않아 취침 시각이 23:30으로 남는다
        assertEquals(kst("2026-08-09T23:30"), nights.lastOrNull()?.block?.firstOrNull()?.start)
    }
}
