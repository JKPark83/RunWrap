package com.jkpark.runwrap

import com.jkpark.runwrap.ui.Format
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/// iOS `ReportMetricsTests`의 Format 기대값을 그대로 가져와 이식 정합성을 고정한다.
class FormatTest {

    // MARK: duration

    @Test
    fun `duration - 1시간 넘으면 h_mm_ss`() {
        // 6754s = 1h 52m 34s
        assertEquals("1:52:34", Format.duration(6_754.0))
    }

    @Test
    fun `duration - 1시간 미만은 m_ss`() {
        // 2890s = 48m 10s
        assertEquals("48:10", Format.duration(2_890.0))
    }

    @Test
    fun `duration - 반올림 후 자리올림`() {
        // 3599.6s → 반올림 3600 → 1:00:00
        assertEquals("1:00:00", Format.duration(3_599.6))
    }

    // MARK: pace

    @Test
    fun `pace - 분·초 프라임 표기`() {
        assertEquals("5′20″", Format.pace(320.0))
        assertEquals("5′20″/km", Format.paceKm(320.0))
    }

    @Test
    fun `pace - 초가 한 자리면 0 패딩`() {
        assertEquals("6′05″", Format.pace(365.0))
    }

    // MARK: km · kcal · walkRunMinutes

    @Test
    fun `km - 소수 1자리`() {
        assertEquals("12.3", Format.km(12.34))
        assertEquals("0.0", Format.km(0.0))
    }

    @Test
    fun `kcal - 천 단위 구분·소수 없음`() {
        assertEquals("4,120", Format.kcal(4_120.4))
        assertEquals("512", Format.kcal(512.0))
    }

    @Test
    fun `walkRunMinutes - 정수는 소수 없이, 반 분은 1자리`() {
        assertEquals("2", Format.walkRunMinutes(2.0))
        assertEquals("3.5", Format.walkRunMinutes(3.5))
    }

    // MARK: weekLabel — 기대값은 iOS ReportMetricsTests와 동일

    @Test
    fun `weekLabel - 월 중순 평범한 주`() {
        // 2026-08-10(월) → 목요일 8/13 → 8월 2째주
        assertEquals("8월 2째주", Format.weekLabel(LocalDate.of(2026, 8, 10)))
    }

    @Test
    fun `weekLabel - 두 달에 걸친 주는 목요일의 달`() {
        // 2026-06-29(월) → 목요일 7/2 → 7월 1째주
        assertEquals("7월 1째주", Format.weekLabel(LocalDate.of(2026, 6, 29)))
    }

    @Test
    fun `weekLabel - 연말 주`() {
        // 2026-12-28(월) → 목요일 12/31 → 12월 5째주
        assertEquals("12월 5째주", Format.weekLabel(LocalDate.of(2026, 12, 28)))
    }

    @Test
    fun `weekLabel - withYear는 목요일 기준 연도`() {
        // 2025-12-29(월) → 목요일 2026-01-01 → 2026년 1월 1째주
        assertEquals("2026년 1월 1째주", Format.weekLabel(LocalDate.of(2025, 12, 29), withYear = true))
    }
}
