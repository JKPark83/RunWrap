package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 대회 → 캘린더 이벤트 시각 검증 (이슈 #172) — now = 2026-08-12 09:00 KST 고정
class RaceCalendarTests {
    private val now = iso("2026-08-12T09:00:00+09:00")

    private fun entry(startTime: String?): RaceEngine.Entry {
        val race = Race(id = 1, name = "테스트 대회", date = "2026-10-04", startTime = startTime)
        return assertNotNull(RaceEngine.entries(listOf(race), now = now).firstOrNull())
    }

    @Test
    @DisplayName("출발 시각이 있으면 — 그 시각(KST)부터 4시간짜리 이벤트")
    fun timedEvent() {
        // 10/4 09:30 KST 출발 → 09:30~13:30 KST
        val schedule = RaceCalendar.schedule(entry(startTime = "09:30"))
        assertFalse(schedule.isAllDay)
        assertEquals(iso("2026-10-04T09:30:00+09:00"), schedule.start)
        assertEquals(iso("2026-10-04T13:30:00+09:00"), schedule.end)
    }

    @Test
    @DisplayName("출발 시각이 없거나 형식이 어긋나면 — 대회일 종일 이벤트")
    fun allDayEvent() {
        val midnight = iso("2026-10-04T00:00:00+09:00")
        for (startTime in listOf(null, "9시", "25:00", "08:3")) {
            val schedule = RaceCalendar.schedule(entry(startTime = startTime))
            assertTrue(schedule.isAllDay)
            assertEquals(midnight, schedule.start)
            assertEquals(midnight, schedule.end)
        }
    }
}
