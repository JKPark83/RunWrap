package com.jkpark.runwrap.engine

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// 픽스처는 android/tools/gen_swiftcompat_fixture.swift가 실제 Swift로 만든 정답이다 (약 3,300개 값).
class SwiftCompatTests {
    private val rows: List<List<String>> =
        javaClass.getResource("/swiftcompat_fixture.tsv")!!.readText()
            .lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }

    private fun bits(hex: String) = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, 16))

    @Test
    @DisplayName("fmt는 Swift String(format:) %.0f·%.1f·%.2f·%.3f·%+.0f·%+.1f와 전 항목 일치한다")
    fun fmtMatchesSwift() {
        assertTrue(rows.size > 3_000)
        val failures = rows.flatMap { r ->
            val v = bits(r[0])
            listOf(
                fmt(v, 0) to r[1], fmt(v, 1) to r[2], fmt(v, 2) to r[3], fmt(v, 3) to r[4],
                fmt(v, 0, plus = true) to r[5], fmt(v, 1, plus = true) to r[6],
            ).filter { (got, want) -> got != want }.map { (got, want) -> "$v: got $got, want $want" }
        }
        assertEquals(emptyList(), failures.take(20), "불일치 ${failures.size}건")
    }

    @Test
    @DisplayName("swiftRounded·swiftRoundedInt는 Swift rounded()·Int(rounded())와 전 항목 일치한다")
    fun roundedMatchesSwift() {
        val failures = rows.mapNotNull { r ->
            val v = bits(r[0])
            val got = v.swiftRounded()
            when {
                got.toRawBits() != bits(r[7]).toRawBits() -> "$v: rounded got $got, want ${bits(r[7])}"
                r[8] != "-" && v.swiftRoundedInt().toString() != r[8] -> "$v: int got ${v.swiftRoundedInt()}, want ${r[8]}"
                else -> null
            }
        }
        assertEquals(emptyList(), failures, "불일치 ${failures.size}건")
    }
}
