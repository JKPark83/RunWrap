package com.jkpark.runwrap.store

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// SharedPreferences 원시값 → UserDefaults식 타입 변환 (iOS 대응 테스트 없음 — Android 저장 방식 전용)
class SettingsStoreTests {
    @Test
    @DisplayName("Double은 Long 비트로 저장돼 그대로 돌아오고, Int로 읽으면 소수점을 버린다")
    fun doubleBits() {
        val raw = 2.7.toRawBits()
        assertEquals(2.7, prefDouble(raw))
        assertEquals(2, prefInt(raw))
        assertEquals(true, prefBool(raw))
        assertEquals(false, prefBool(0.0.toRawBits()))
    }

    @Test
    @DisplayName("Int로 넣은 키를 double로 읽으면 변환된다 — UserDefaults와 같은 동작")
    fun intToDouble() {
        assertEquals(5.0, prefDouble(5))
        assertEquals(true, prefBool(5))
        assertEquals(1, prefInt(true))
        assertEquals(1.0, prefDouble(true))
    }

    @Test
    @DisplayName("없는 키·문자열은 0/false")
    fun absent() {
        assertEquals(0, prefInt(null))
        assertEquals(0.0, prefDouble(null))
        assertEquals(false, prefBool(null))
        assertEquals(0, prefInt("12"))
    }
}
