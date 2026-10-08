package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 공유 카드 주간 요약 — 창이 '지금'이 아니라 세션 기준 달력 7일인지 검증한다 (이슈 #92).
/// 캘린더는 Asia/Seoul 고정. now = 2026-08-10T09:00:00Z (월 18:00 KST).
class ShareSummaryTests {
    private val now = iso("2026-08-10T09:00:00Z")
    /// iOS `Calendar(identifier: .gregorian)` + Asia/Seoul 고정 → KST
    private val zone = KST

    private fun run(isoText: String, km: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(), start = iso(isoText),
                   durationSec = 3_600.0, distanceMeters = km * 1_000, avgHeartRate = 150.0,
                   cadenceSpm = 170.0)

    @Test
    @DisplayName("과거 세션 — 그 세션 기준 달력 7일(6일 전 자정~다음 자정)만 세고 '그날까지 7일'로 적는다")
    fun pastSessionWindow() {
        // 세션: 7.20 07:00 KST → 창 [7.14 00:00, 7.21 00:00) KST
        val session = run("2026-07-19T22:00:00Z", km = 10.0)
        val runs = listOf(
            session,
            run("2026-07-13T14:59:00Z", km = 5.0),   // 7.13 23:59 KST — 창 직전, 제외
            run("2026-07-13T15:00:00Z", km = 4.0),   // 7.14 00:00 KST — 창 시작, 포함
            run("2026-07-20T14:00:00Z", km = 3.0),   // 7.20 23:00 KST — 같은 날 뒤 기록, 포함
            run("2026-07-20T15:00:00Z", km = 8.0),   // 7.21 00:00 KST — 창 끝, 제외
            run("2026-08-09T22:00:00Z", km = 12.0),  // 이번 주 기록 — '지금' 기준이면 섞였을 값
        )
        val line = ShareSummary.weeklyLine(runs = runs, sessionStart = session.start,
                                           now = now, zone = zone)
        assertEquals("그날까지 7일 3회 · 17.0 km", line)  // 10 + 4 + 3
    }

    @Test
    @DisplayName("오늘 세션 — 창이 최근 7일과 같아 '최근 7일'로 적는다")
    fun todaySessionWindow() {
        // 세션: 8.10 07:00 KST → 창 [8.4 00:00, 8.11 00:00) KST
        val session = run("2026-08-09T22:00:00Z", km = 10.0)
        val runs = listOf(session,
                          run("2026-08-03T15:00:00Z", km = 5.0),   // 8.4 00:00 KST — 포함
                          run("2026-08-03T14:00:00Z", km = 7.0))   // 8.3 23:00 KST — 제외
        val line = ShareSummary.weeklyLine(runs = runs, sessionStart = session.start,
                                           now = now, zone = zone)
        assertEquals("최근 7일 2회 · 15.0 km", line)
    }

    @Test
    @DisplayName("창 안 기록이 없으면 nil — 카드는 기본 문구로 대체한다")
    fun emptyWindow() {
        val session = run("2026-07-19T22:00:00Z", km = 10.0)
        assertNull(ShareSummary.weeklyLine(runs = emptyList(), sessionStart = session.start,
                                           now = now, zone = zone))
    }

    // MARK: - 구간 페이스 표 (이슈 #221)

    @Test
    @DisplayName("구간 표 — 10구간 이하는 1km마다 한 줄, 가장 빠른 구간 하나만 강조")
    fun splitRowsPerKm() {
        val rows = ShareSummary.splitRows(listOf(340.0, 330.0, 330.0, 350.0, 360.0))
        assertEquals(listOf("1km", "2km", "3km", "4km", "5km"), rows.map { it.label })
        // 2·3km가 330초로 같으면 앞 구간(2km)만 강조
        assertEquals(listOf(false, true, false, false, false), rows.map { it.isFastest })
        assertEquals(10, ShareSummary.splitRows(List(10) { 330.0 }).size)
    }

    @Test
    @DisplayName("구간 표 — 하프(21구간)는 3km씩 묶어 7줄")
    fun splitRowsHalfGrouped() {
        // 1~20km는 330초, 21km는 300초 → 3km 평균 330초 × 6줄 + 19–21km (330+330+300)/3 = 320초
        val rows = ShareSummary.splitRows(List(20) { 330.0 } + 300.0)
        assertEquals(7, rows.size)
        assertEquals(ShareSummary.SplitRow("1–3km", 330.0, false), rows.first())
        assertEquals(ShareSummary.SplitRow("19–21km", 320.0, true), rows.last())
    }

    @Test
    @DisplayName("구간 표 — 풀(42구간)은 5km씩 9줄, 마지막은 남은 2km, 묶음 페이스는 평균")
    fun splitRowsFullGrouped() {
        // 1~5km: 320·330·340·330·330 → 평균 330초
        val rows = ShareSummary.splitRows(listOf(320.0, 330.0, 340.0, 330.0, 330.0) + List(37) { 350.0 })
        assertEquals(9, rows.size)
        assertEquals(ShareSummary.SplitRow("1–5km", 330.0, true), rows[0])
        assertEquals(ShareSummary.SplitRow("41–42km", 350.0, false), rows[8])
    }

    @Test
    @DisplayName("구간 표 — 3구간 미만이면 표를 내지 않는다")
    fun splitRowsTooShort() {
        assertEquals(emptyList(), ShareSummary.splitRows(listOf(330.0, 340.0)))
        assertEquals(emptyList(), ShareSummary.splitRows(emptyList()))
    }
}
