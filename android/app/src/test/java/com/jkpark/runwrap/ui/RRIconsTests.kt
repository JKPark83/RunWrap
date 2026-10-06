package com.jkpark.runwrap.ui

import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 옮겨 둔 SVG 경로가 전부 파싱되는지 (iOS 대응 테스트 없음)
class RRIconsTests {
    @Test
    @DisplayName("정의된 SF 이름은 모두 ImageVector로 만들어진다")
    fun allBuild() {
        for (name in RRIcons.names) assertTrue(RRIcons.named(name).root.size > 0, name)
    }

    @Test
    @DisplayName("모르는 이름은 바로 실패한다")
    fun unknown() {
        assertFailsWith<IllegalStateException> { RRIcons.named("bird") }
    }
}
