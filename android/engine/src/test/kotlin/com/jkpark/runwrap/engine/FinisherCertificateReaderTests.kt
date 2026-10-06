package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 완주증 OCR 해석 검증 (이슈 #192) — 줄 텍스트 → 폼 프리필(순수 로직). now = 2026-09-30T00:00:00Z 고정.
/// (Android: 합성 이미지로 Vision 인식까지 잇는 통합 1건 "Vision 합성 완주증 이미지 인식"은 iOS 전용이다)
class FinisherCertificateReaderTests {
    private val now = iso("2026-09-30T00:00:00Z")

    private fun ymd(date: Instant?): List<Int?> {
        if (date == null) return emptyList()
        val comps = date.atZone(testZone)
        return listOf(comps.year, comps.monthValue, comps.dayOfMonth)
    }

    private fun interpret(vararg lines: String): RaceResultParser.Parsed? =
        FinisherCertificateReader.interpret(lines = lines.toList(), now = now, zone = testZone)

    @Test
    @DisplayName("한국 완주증 — 대회명 속 '하프', 01:45:30, '2026년 3월 15일'을 읽는다")
    fun koreanCertificate() {
        val parsed = assertNotNull(interpret("2026 서울하프마라톤", "완 주 증", "종목: 하프", "기록 01:45:30",
                                             "2026년 3월 15일"))
        assertEquals(RaceDistance.half, parsed.race)
        assertEquals(6_330.0, parsed.timeSec)   // 1×3600 + 45×60 + 30
        assertEquals(listOf<Int?>(2026, 3, 15), ymd(parsed.date))
    }

    @Test
    @DisplayName("넷타임 우선 — 같은 줄 라벨이든 바로 위 줄 라벨이든 건타임보다 넷타임")
    fun prefersNetTime() {
        val sameLine = assertNotNull(interpret("42.195km", "Gun Time 03:52:10", "Net Time 03:48:45"))
        assertEquals(RaceDistance.full, sameLine.race)
        assertEquals(13_725.0, sameLine.timeSec)   // 3×3600 + 48×60 + 45
        val labelAbove = assertNotNull(interpret("Net Time", "03:48:45", "Gun Time", "03:52:10"))
        assertEquals(13_725.0, labelAbove.timeSec)
    }

    @Test
    @DisplayName("10K MM:SS — H:MM:SS가 없으면 52:31을 52분 31초로, 2026.05.03은 날짜로")
    fun tenKMinutesSeconds() {
        val parsed = assertNotNull(interpret("10km", "52:31", "2026.05.03"))
        assertEquals(RaceDistance.tenK, parsed.race)
        assertEquals(3_151.0, parsed.timeSec)   // 52×60 + 31
        assertEquals(listOf<Int?>(2026, 5, 3), ymd(parsed.date))
    }

    @Test
    @DisplayName("종목 우선순위 — '하프'+'마라톤' 동시 표기는 하프, 15km는 종목 없음")
    fun distancePriority() {
        val half = assertNotNull(interpret("제10회 춘천마라톤", "Half Marathon", "01:50:00"))
        assertEquals(RaceDistance.half, half.race)
        // 15km: 5K 패턴은 앞이 숫자라 제외 — 종목은 비우고 기록만 살린다 (1:20:00 = 4,800초)
        val fifteen = assertNotNull(interpret("15km", "01:20:00"))
        assertNull(fifteen.race)
        assertEquals(4_800.0, fifteen.timeSec)
    }

    @Test
    @DisplayName("구간 기록표 — 풀코스 완주증의 Half·10K·5K 구간 행은 종목이 아니라 대회명(마라톤)을 따른다")
    fun splitTableDoesNotOverrideDistance() {
        // 구간 행에 기록이 붙어 있고 거리가 여럿 → 기록 없는 줄('서울마라톤')만 종목 후보. 넷타임 3:30:00
        val full = assertNotNull(interpret("2026 서울마라톤", "Half 01:42:10", "Net Time 03:30:00"))
        assertEquals(RaceDistance.full, full.race)
        assertEquals(12_600.0, full.timeSec)
        // 5K·10K 구간만 있고 넷 라벨이 없으면 첫 기록(25:12)이 뽑히지만 풀코스 페이스로는 비현실 → 기록 nil
        val splits = assertNotNull(interpret("2026 서울마라톤", "5K 00:25:12", "10K 00:50:30"))
        assertEquals(RaceDistance.full, splits.race)
        assertNull(splits.timeSec)
        // 거리가 하나뿐이면 기록과 같은 줄이어도 그대로 종목으로 쓴다
        val single = assertNotNull(interpret("하프 01:45:30"))
        assertEquals(RaceDistance.half, single.race)
        assertEquals(6_330.0, single.timeSec)
    }

    @Test
    @DisplayName("비현실 기록 방어 — 하프 20분(57초/km)은 구간·페이스 오독으로 보고 기록만 버린다")
    fun dropsImplausibleTime() {
        val parsed = assertNotNull(interpret("하프", "00:20:00"))
        assertEquals(RaceDistance.half, parsed.race)
        assertNull(parsed.timeSec)
    }

    @Test
    @DisplayName("아무것도 못 읽으면 nil, 출발 시각(07:30 AM)은 기록으로 오인하지 않는다")
    fun nothingAndClockTime() {
        assertNull(interpret("감사합니다"))
        val parsed = assertNotNull(interpret("10K", "출발 07:30 AM"))
        assertEquals(RaceDistance.tenK, parsed.race)
        assertNull(parsed.timeSec)
    }

    @Test
    @DisplayName("미래 날짜는 오독 — RaceResultParser가 날짜만 버리고 종목·기록은 살린다")
    fun dropsFutureDate() {
        val parsed = assertNotNull(interpret("하프", "01:45:30", "2027.01.01"))
        assertNull(parsed.date)
        assertEquals(RaceDistance.half, parsed.race)
        assertEquals(6_330.0, parsed.timeSec)
    }
}
