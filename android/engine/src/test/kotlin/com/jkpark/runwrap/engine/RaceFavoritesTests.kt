package com.jkpark.runwrap.engine

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 대회 즐겨찾기 직렬화 검증 (이슈 #172) — @AppStorage 문자열 ↔ 대회 번호 배열
class RaceFavoritesTests {
    @Test
    @DisplayName("왕복 — 인코딩한 문자열을 디코딩하면 순서까지 그대로 돌아온다")
    fun roundTrip() {
        val ids = listOf(3_512, 17, 42)
        val encoded = RaceFavorites.encode(ids)
        assertEquals("[3512,17,42]", encoded)
        assertEquals(ids, RaceFavorites.decode(encoded))
        assertEquals(emptyList(), RaceFavorites.decode(RaceFavorites.encode(emptyList())))
    }

    @Test
    @DisplayName("토글 — 없으면 뒤에 붙이고, 있으면 뺀다")
    fun toggled() {
        // [1, 2] + 3 → [1, 2, 3], 다시 2를 누르면 → [1, 3]
        val added = RaceFavorites.toggled(listOf(1, 2), 3)
        assertEquals(listOf(1, 2, 3), added)
        assertEquals(listOf(1, 3), RaceFavorites.toggled(added, 2))
        assertEquals(listOf(7), RaceFavorites.toggled(emptyList(), 7))
        assertEquals(emptyList(), RaceFavorites.toggled(listOf(7), 7))
    }

    @Test
    @DisplayName("깨진 값 — 빈 문자열·JSON 아님·타입 불일치는 즐겨찾기 없음으로 읽는다")
    fun brokenInput() {
        assertEquals(emptyList(), RaceFavorites.decode(""))
        assertEquals(emptyList(), RaceFavorites.decode("1,2,3"))
        assertEquals(emptyList(), RaceFavorites.decode("[\"a\"]"))
        assertEquals(emptyList(), RaceFavorites.decode("{\"id\":1}"))
    }
}
