package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// iOS에 대응 테스트가 없는 Android 전용 검증 — `Format`이 iOS와 글자 단위로 같은지 본다.
/// 픽스처는 android/tools/gen_format_fixture.swift가 iOS `Format` 원문을 실제 Swift로 돌려 만든 정답이다.
/// 시각 의존 행은 생성 당시 TZ(UTC·Asia/Seoul)가 열에 적혀 있어 testZone과 무관하게 그 zone으로 돌린다.
class FormatTests {
    private val rows: List<List<String>> =
        javaClass.getResource("/format_fixture.tsv")!!.readText()
            .lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }

    private fun bits(hex: String) = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, 16))

    private fun format(fn: String, zone: ZoneId?, input: String): String = when (fn) {
        "duration" -> Format.duration(bits(input))
        "pace" -> Format.pace(bits(input))
        "paceKm" -> Format.paceKm(bits(input))
        "km" -> Format.km(bits(input))
        "walkRunMinutes" -> Format.walkRunMinutes(bits(input))
        "kcal" -> Format.kcal(bits(input))
        "weekLabel" -> Format.weekLabel(Instant.parse(input), zone!!)
        "weekLabelYear" -> Format.weekLabel(Instant.parse(input), zone!!, withYear = true)
        "monthDayTime" -> Format.monthDayTime(Instant.parse(input), zone!!)
        "relativeWeek" -> {
            val (date, now) = input.split('|')
            Format.relativeWeek(of = Instant.parse(date), now = Instant.parse(now), zone = zone!!)
        }
        else -> error("모르는 fn: $fn")
    }

    @Test
    @DisplayName("Format 전 함수가 Swift 픽스처와 글자 단위로 일치한다")
    fun formatMatchesSwift() {
        assertTrue(rows.size > 2_000)
        // 모든 함수·두 zone이 실제로 픽스처에 있는지 — 생성기가 일부를 빠뜨려도 통과하지 않게
        assertEquals(setOf("duration", "pace", "paceKm", "km", "walkRunMinutes", "kcal",
                           "weekLabel", "weekLabelYear", "monthDayTime", "relativeWeek"),
                     rows.map { it[0] }.toSet())
        assertEquals(setOf("-", "UTC", "Asia/Seoul"), rows.map { it[1] }.toSet())
        val failures = rows.mapNotNull { (fn, zone, input, expected) ->
            val got = format(fn, if (zone == "-") null else ZoneId.of(zone), input)
            if (got == expected) null else "$fn[$zone] $input: got $got, want $expected"
        }
        assertEquals(emptyList(), failures.take(20), "불일치 ${failures.size}건")
    }

    @Test
    @DisplayName("kcal은 픽스처에 없는 NaN·무한대를 macOS NumberFormatter 실측값으로 적는다")
    fun kcalNonFinite() {
        // macOS ko_KR NumberFormatter(.decimal, 소수 0자리) 실측 — 생성기에는 넣지 않았다
        assertEquals("NaN", Format.kcal(Double.NaN))
        assertEquals("+∞", Format.kcal(Double.POSITIVE_INFINITY))
        assertEquals("-∞", Format.kcal(Double.NEGATIVE_INFINITY))
    }
}
