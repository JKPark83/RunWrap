package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 대회 접수 상태 판정·정렬·필터 검증 — now = 2026-08-12 09:00 KST 고정 (계획서 M13-2)
class RaceEngineTests {
    private val now = iso("2026-08-12T09:00:00+09:00")

    private fun race(id: Int = 1, date: String, registerStart: String? = null, registerEnd: String? = null): Race =
        Race(id = id, name = "테스트 대회", date = date, registerStart = registerStart, registerEnd = registerEnd)

    private fun decode(json: String): RaceFile = EngineJson.decodeFromString(RaceFile.serializer(), json)

    @Test
    @DisplayName("접수중 판정 — 시작일과 마감일 사이면 open, 마감 D-day를 함께 계산한다")
    fun openStatus() {
        // 8/12 기준: 접수 8/1~8/20 → 접수중, 마감까지 8일. 대회 9/1 → D-20
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-09-01", registerStart = "2026-08-01", registerEnd = "2026-08-20")),
            now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.open(end = RaceEngine.day("2026-08-20")), entry.status)
        assertEquals(8, entry.deadlineDDay)
        assertEquals(20, entry.dDay)
    }

    @Test
    @DisplayName("마감일 당일 포함 — registerEnd가 오늘이면 아직 접수중이다")
    fun deadlineDayInclusive() {
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-09-01", registerStart = "2026-08-01", registerEnd = "2026-08-12")),
            now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.open(end = RaceEngine.day("2026-08-12")), entry.status)
        assertEquals(0, entry.deadlineDDay)
    }

    @Test
    @DisplayName("접수예정 판정 — 시작일이 미래면 notYet")
    fun notYetStatus() {
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-10-01", registerStart = "2026-09-01", registerEnd = "2026-09-20")),
            now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.notYet(start = RaceEngine.day("2026-09-01")!!), entry.status)
    }

    @Test
    @DisplayName("접수완료 판정 — 마감일이 지나면 closed (기획서 §4.14 '지나간 대회는 접수완료')")
    fun closedStatus() {
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-09-01", registerStart = "2026-07-01", registerEnd = "2026-08-11")),
            now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.closed, entry.status)
        assertNull(entry.deadlineDDay)
    }

    @Test
    @DisplayName("접수기간 미상 가드 — 시작·마감 둘 다 없으면 상태를 지어내지 않는다(nil)")
    fun unknownPeriodGuard() {
        val entries = RaceEngine.entries(listOf(race(date = "2026-09-01")), now = now)
        assertNull(assertNotNull(entries.firstOrNull()).status)
    }

    @Test
    @DisplayName("마감일 미상 접수중 — 시작일만 있고 지났으며 대회까지 30일 이상이면 open(end: nil)")
    fun openWithoutEnd() {
        // 대회 10/1 → D-50 (8월 19일 + 9월 30일 + 1) ≥ 30
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-10-01", registerStart = "2026-08-01")), now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.open(end = null), entry.status)
        assertNull(entry.deadlineDDay)
    }

    @Test
    @DisplayName("마감일 미상 가드 — 대회가 30일 안으로 다가오면 접수중으로 보지 않는다(nil)")
    fun unknownEndNearRaceGuard() {
        // 대회 9/1 → D-20 (8/12→8/31 19일 + 1) < 30. 크롤러가 "9월31일" 마감을 버린 경우 (#45)
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-09-01", registerStart = "2026-08-01")), now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertNull(entry.status)
        assertNull(entry.deadlineDDay)
    }

    @Test
    @DisplayName("마감일 미상 가드 경계 — D-30은 접수중, D-29는 상태 미상")
    fun unknownEndBoundary() {
        // 대회 9/11 → D-30 (8월 남은 19일 + 9월 11일), 9/10 → D-29
        val entries = RaceEngine.entries(
            listOf(race(id = 1, date = "2026-09-11", registerStart = "2026-08-01"),
                   race(id = 2, date = "2026-09-10", registerStart = "2026-08-01")),
            now = now)
        val d30 = assertNotNull(entries.firstOrNull { it.race.id == 1 })
        val d29 = assertNotNull(entries.firstOrNull { it.race.id == 2 })
        assertEquals(30, d30.dDay)
        assertEquals(RaceEngine.RegisterStatus.open(end = null), d30.status)
        assertEquals(29, d29.dDay)
        assertNull(d29.status)
    }

    @Test
    @DisplayName("마감일 미상이어도 시작 전이면 notYet — 가드는 접수예정 판정 뒤에 온다")
    fun notYetWithoutEndNearRace() {
        // 대회 8/30 → D-18, 접수 시작 8/20 > 오늘 8/12 → 접수예정 유지
        val entries = RaceEngine.entries(
            listOf(race(date = "2026-08-30", registerStart = "2026-08-20")), now = now)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(RaceEngine.RegisterStatus.notYet(start = RaceEngine.day("2026-08-20")!!), entry.status)
    }

    @Test
    @DisplayName("지난 대회 필터 — 대회일이 어제면 빠지고, 오늘이면 D-0으로 남는다")
    fun pastRaceFilter() {
        val entries = RaceEngine.entries(
            listOf(race(id = 1, date = "2026-08-11"), race(id = 2, date = "2026-08-12")),
            now = now)
        assertEquals(1, entries.size)
        val entry = assertNotNull(entries.firstOrNull())
        assertEquals(2, entry.race.id)
        assertEquals(0, entry.dDay)
    }

    @Test
    @DisplayName("정렬 — 대회일이 가까운 순, 같은 날은 id 순")
    fun sorting() {
        val entries = RaceEngine.entries(listOf(
            race(id = 3, date = "2026-10-01"),
            race(id = 2, date = "2026-08-20"),
            race(id = 5, date = "2026-08-20"),
            race(id = 1, date = "2026-09-01"),
        ), now = now)
        assertEquals(listOf(2, 5, 1, 3), entries.map { it.race.id })
    }

    @Test
    @DisplayName("날짜 형식 오류 가드 — 대회일을 못 읽는 대회는 목록에서 뺀다")
    fun malformedDateGuard() {
        val entries = RaceEngine.entries(listOf(race(date = "2026년 9월 1일")), now = now)
        assertTrue(entries.isEmpty())
    }

    @Test
    @DisplayName("Races.json 디코딩 — 전체 필드와 최소 필드 모두 읽힌다")
    fun decoding() {
        val json = """
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","races":[
          {"id":41504,"name":"2026 인사이더런 S","date":"2026-08-01","startTime":"09:30",
           "region":"서울","place":"일산 킨텍스","host":"러너블","categories":["10km"],
           "registerStart":"2026-03-26","registerEnd":"2026-07-30",
           "homepage":"http://insiderun.me","imageUrl":"http://insiderun.me/og.png",
           "lat":37.6646954,"lon":126.7420642,"note":"10Km 레이스"},
          {"id":1,"name":"최소 대회","date":"2026-09-01"}
        ]}
        """.trimIndent()
        val file = decode(json)
        assertEquals("roadrun.co.kr", file.source)
        assertEquals(2, file.races.size)
        val full = assertNotNull(file.races.firstOrNull())
        assertEquals(listOf("10km"), full.categories)
        assertEquals("2026-07-30", full.registerEnd)
        assertEquals("http://insiderun.me/og.png", full.imageUrl)
        val minimal = file.races[1]
        assertTrue(minimal.startTime == null && minimal.homepage == null)
        assertNull(minimal.imageUrl)
        assertEquals(1, file.schemaVersion)   // 필드가 없으면 1로 기본 (#144)
    }

    @Test
    @DisplayName("관대 디코딩 — lat이 문자열인 원소 하나만 건너뛰고 나머지는 읽는다")
    fun lenientDecodingTypeMismatch() {
        val json = """
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","races":[
          {"id":1,"name":"첫 대회","date":"2026-09-01"},
          {"id":2,"name":"깨진 대회","date":"2026-09-02","lat":"37.5"},
          {"id":3,"name":"셋째 대회","date":"2026-09-03"}
        ]}
        """.trimIndent()
        val file = decode(json)
        assertEquals(listOf(1, 3), file.races.map { it.id })
    }

    @Test
    @DisplayName("관대 디코딩 — 필수 필드(name)가 빠진 원소와 객체가 아닌 원소는 건너뛴다")
    fun lenientDecodingMissingField() {
        // null 원소는 빈 struct로 커서를 넘기는 패턴이면 무한 루프가 나는 경우 — 래퍼 방식 회귀 방지
        val json = """
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","schemaVersion":1,"races":[
          {"id":1,"date":"2026-09-01"},
          null,
          {"id":2,"name":"정상 대회","date":"2026-09-02"}
        ]}
        """.trimIndent()
        val file = decode(json)
        assertEquals(listOf(2), file.races.map { it.id })
    }

    @Test
    @DisplayName("관대 디코딩 — 최상위 필수 키(generatedAt)가 없으면 여전히 실패한다")
    fun topLevelStillRequired() {
        val json = """
        {"source":"roadrun.co.kr","races":[]}
        """.trimIndent()
        assertFailsWith<SerializationException> { decode(json) }
    }

    @Test
    @DisplayName("스키마 버전 — 파일 값을 그대로 읽는다 (지원 버전 비교는 RaceStore)")
    fun schemaVersionDecoding() {
        val json = """
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","schemaVersion":2,"races":[]}
        """.trimIndent()
        val file = decode(json)
        assertEquals(2, file.schemaVersion)
        assertTrue(file.schemaVersion > RaceStore.supportedSchemaVersion)
    }

    @Test
    @DisplayName("자동 갱신 판정 — 받은 적 없으면 받고, 6시간 넘게 지났을 때만 다시 받는다")
    fun needsRefresh() {
        assertTrue(RaceStore.needsRefresh(lastRefreshedAt = null, now = now))
        // 5시간 전 갱신 → 6시간(21_600초) 이내라 그대로
        assertFalse(RaceStore.needsRefresh(lastRefreshedAt = now.minusSeconds(5 * 3_600L), now = now))
        // 7시간 전 갱신 → 다시 받는다
        assertTrue(RaceStore.needsRefresh(lastRefreshedAt = now.minusSeconds(7 * 3_600L), now = now))
    }

    @Test
    @DisplayName("신선도 판정 — 6일 전까지는 신선, 8일 전은 오래됨, 못 읽으면 경고하지 않는다")
    fun staleness() {
        // now = 8/12 09:00 KST. maxDays 7 → 7일(604_800초) 초과면 오래됨
        assertFalse(RaceFormat.isStale(generatedAt = "2026-08-10T09:00:00+09:00", now = now))   // 2일
        assertFalse(RaceFormat.isStale(generatedAt = "2026-08-06T09:00:00+09:00", now = now))   // 6일
        assertTrue(RaceFormat.isStale(generatedAt = "2026-08-04T09:00:00+09:00", now = now))    // 8일
        assertFalse(RaceFormat.isStale(generatedAt = "8월 8일", now = now))
    }

    @Test
    @DisplayName("헤더 캡션 — 정상·새로고침 실패·오래됨·둘 다 네 가지 문구")
    fun caption() {
        val updated = "2026-08-12T05:10:00+09:00"
        assertEquals("지금 접수받는 대회 12곳 · 8월 12일 갱신",
                     RaceFormat.caption(openCount = 12, updatedAt = updated, refreshFailed = false, stale = false))
        assertEquals("지금 접수받는 대회 12곳\n방금 새로 받진 못했어요 · 8월 12일 자료",
                     RaceFormat.caption(openCount = 12, updatedAt = updated, refreshFailed = true, stale = false))
        assertEquals("지금 접수받는 대회 12곳\n자료가 조금 오래됐어요 · 8월 12일 자료",
                     RaceFormat.caption(openCount = 12, updatedAt = updated, refreshFailed = false, stale = true))
        assertEquals("지금 접수받는 대회 12곳\n새로 받지 못해 자료가 조금 오래됐어요 · 8월 12일 자료",
                     RaceFormat.caption(openCount = 12, updatedAt = updated, refreshFailed = true, stale = true))
    }

    // (Android 전용: Entry는 화면 route 인자로 EngineJson 문자열에 실린다 — 봉인 타입 RegisterStatus 세 가지가 모두 되돌아와야 한다)
    @Test
    @DisplayName("route JSON 왕복 — Entry와 접수 상태 세 가지가 그대로 되돌아온다")
    fun routeJsonRoundTrip() {
        val entries = RaceEngine.entries(listOf(
            race(id = 1, date = "2026-09-01", registerStart = "2026-08-01", registerEnd = "2026-08-20"),   // open(end)
            race(id = 2, date = "2026-10-01", registerStart = "2026-08-20"),                               // notYet
            race(id = 3, date = "2026-09-05", registerStart = "2026-07-01", registerEnd = "2026-08-01"),   // closed
        ), now = now)
        assertEquals(3, entries.size)
        for (entry in entries) {
            assertEquals(entry, EngineJson.decodeFromString<RaceEngine.Entry>(EngineJson.encodeToString(entry)))
        }
    }
}
