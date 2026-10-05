package com.jkpark.runwrap.engine

import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 포그라운드 복귀 갱신 판정 (이슈 #69) — 30분 경과·날짜 변경·결론 없음이면 다시 받는다
/// (Android: iOS 스위트 이름은 WeatherRefreshTests)
class WeatherStoreTests {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    @DisplayName("29분 전 결론은 아직 신선하다")
    fun fresh29Minutes() {
        // 13:00 → 13:29, 같은 날 29분 < 30분
        assertFalse(WeatherStore.needsRefresh(fetchedAt = iso("2026-09-30T13:00:00+09:00"),
                                              now = iso("2026-09-30T13:29:00+09:00"),
                                              zone = seoul))
    }

    @Test
    @DisplayName("31분 지난 결론은 다시 받는다")
    fun stale31Minutes() {
        // 13:00 → 13:31, 31분 ≥ 30분
        assertTrue(WeatherStore.needsRefresh(fetchedAt = iso("2026-09-30T13:00:00+09:00"),
                                             now = iso("2026-09-30T13:31:00+09:00"),
                                             zone = seoul))
    }

    @Test
    @DisplayName("자정을 넘기면 20분만 지나도 다시 받는다 — 당일 수분 알람 재예약")
    fun staleAcrossMidnight() {
        // 9/29 23:50 → 9/30 00:10 (서울), 20분이지만 날짜가 바뀌었다
        assertTrue(WeatherStore.needsRefresh(fetchedAt = iso("2026-09-29T23:50:00+09:00"),
                                             now = iso("2026-09-30T00:10:00+09:00"),
                                             zone = seoul))
    }

    @Test
    @DisplayName("결론이 한 번도 없으면 다시 받는다")
    fun staleWhenNil() {
        assertTrue(WeatherStore.needsRefresh(fetchedAt = null,
                                             now = iso("2026-09-30T13:00:00+09:00"),
                                             zone = seoul))
    }

    @Test
    @DisplayName("시계가 뒤로 가 결론 시각이 미래면 낡은 것으로 본다")
    fun staleWhenFuture() {
        // 13:10에 받았는데 지금이 13:00 — 나이가 음수라 신선하다고 볼 근거가 없다
        assertTrue(WeatherStore.needsRefresh(fetchedAt = iso("2026-09-30T13:10:00+09:00"),
                                             now = iso("2026-09-30T13:00:00+09:00"),
                                             zone = seoul))
    }
}
