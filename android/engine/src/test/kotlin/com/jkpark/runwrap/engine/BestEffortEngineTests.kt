package com.jkpark.runwrap.engine

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 베스트 에포트 엔진 + 캐시 왕복 검증 (이슈 #166). 샘플 시각은 고정 now에서 이어 붙인다.
class BestEffortEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")

    /// (거리 m, 소요 초) 구간을 now부터 빈틈없이 이어 붙인 거리 샘플
    private fun samples(segments: List<Pair<Double, Double>>): List<DistanceSample> {
        var t: Instant = now
        return segments.map { (meters, seconds) ->
            val end = instantSince1970(t.timeIntervalSince1970 + seconds)
            DistanceSample(start = t, end = end, meters = meters).also { t = end }
        }
    }

    private fun repeating(segment: Pair<Double, Double>, count: Int) = List(count) { segment }

    @Test
    @DisplayName("등속 6km — 1K·5K는 정확히 페이스 × D, 10K는 못 채워 없다")
    fun steadyPace() {
        // 5:00/km = 1분에 200m × 30개 = 6km · 1,800초
        val result = BestEffortEngine.bestEfforts(
            distanceSamples = samples(repeating(200.0 to 60.0, count = 30)))
        assertTrue(abs(assertNotNull(result[1_000.0]) - 300) < 0.001)    // 300초/km × 1km
        assertTrue(abs(assertNotNull(result[5_000.0]) - 1_500) < 0.001)  // 300초/km × 5km
        assertNull(result[10_000.0])
        assertEquals(2, result.size)
    }

    @Test
    @DisplayName("중간 2km가 빠른 12km — 각 거리가 빠른 구간을 포함한 창을 잡는다")
    fun fastMiddleSegment() {
        // 500m 샘플 24개: 0~5km 6:00/km(180초), 5~7km 4:00/km(120초), 7~12km 6:00/km
        val segments = repeating(500.0 to 180.0, count = 10) +
            repeating(500.0 to 120.0, count = 4) +
            repeating(500.0 to 180.0, count = 10)
        val result = BestEffortEngine.bestEfforts(distanceSamples = samples(segments))
        assertTrue(abs(assertNotNull(result[1_000.0]) - 240) < 0.001)     // 빠른 구간 1km × 240
        assertTrue(abs(assertNotNull(result[5_000.0]) - 1_560) < 0.001)   // 2km × 240 + 3km × 360
        assertTrue(abs(assertNotNull(result[10_000.0]) - 3_360) < 0.001)  // 2km × 240 + 8km × 360
        assertNull(result[21_097.5])
    }

    @Test
    @DisplayName("보간 — 샘플 경계에 D가 걸리지 않으면 구간 안에서 선형 보간한다")
    fun interpolatesInsideSample() {
        // 600m/180초 + 600m/240초. 1km 지점은 둘째 샘플 400m 지점 → 180 + 240 × 400/600 = 340초.
        // 둘째 시작점(600m)부터는 남은 거리가 600m라 1km를 못 채운다
        val result = BestEffortEngine.bestEfforts(distanceSamples = samples(listOf(600.0 to 180.0, 600.0 to 240.0)))
        assertTrue(abs(assertNotNull(result[1_000.0]) - 340) < 0.001)
    }

    @Test
    @DisplayName("정렬 — 샘플이 뒤섞여 들어와도 시작 시각 순으로 이어 계산한다")
    fun sortsByStart() {
        val ordered = samples(listOf(600.0 to 180.0, 600.0 to 240.0))
        val result = BestEffortEngine.bestEfforts(distanceSamples = ordered.reversed())
        assertTrue(abs(assertNotNull(result[1_000.0]) - 340) < 0.001)
    }

    @Test
    @DisplayName("표본 가드 — 빈 샘플·1K 미만·0 이하 거리만 있으면 빈 dict")
    fun emptyWhenInsufficient() {
        assertTrue(BestEffortEngine.bestEfforts(distanceSamples = emptyList()).isEmpty())
        // 900m — 1K 미만
        assertTrue(BestEffortEngine.bestEfforts(
            distanceSamples = samples(repeating(300.0 to 90.0, count = 3))).isEmpty())
        // 음수·0 거리 샘플은 건너뛴다 — 합산돼 1K를 채운 것처럼 보이면 안 된다
        assertTrue(BestEffortEngine.bestEfforts(
            distanceSamples = samples(listOf(0.0 to 600.0, -500.0 to 60.0, 900.0 to 270.0))).isEmpty())
    }

    @Test
    @DisplayName("0 이하 샘플은 건너뛰고 나머지로 계산한다")
    fun skipsNonPositiveSamples() {
        // 유효 샘플 500m/150초 × 2 → 1K 300초. 사이의 −100m 샘플은 버린다
        val result = BestEffortEngine.bestEfforts(
            distanceSamples = samples(listOf(500.0 to 150.0, -100.0 to 10.0, 500.0 to 150.0)))
        // 버린 샘플의 10초는 둘째 유효 샘플 시작 전 공백이 된다 → 1K = 150 + 10 + 150 = 310초
        assertTrue(abs(assertNotNull(result[1_000.0]) - 310) < 0.001)
    }

    @Test
    @DisplayName("페이스 타당성 — 150~1,200초/km 밖 기록은 버린다")
    fun plausiblePaceFilter() {
        // 1km 120초(2:00/km) — GPS 튐으로 본다
        assertNull(BestEffortEngine.bestEfforts(
            distanceSamples = samples(listOf(500.0 to 60.0, 500.0 to 60.0)))[1_000.0])
        // 1.2km 30분 = 1,500초/km(25:00/km) — 걷기보다 느리다. 1K 최소도 1,250초로 범위 밖
        assertNull(BestEffortEngine.bestEfforts(
            distanceSamples = samples(listOf(600.0 to 750.0, 600.0 to 750.0)))[1_000.0])
        // 경계값 150초/km는 낸다
        assertEquals(150.0, BestEffortEngine.bestEfforts(
            distanceSamples = samples(listOf(500.0 to 75.0, 500.0 to 75.0)))[1_000.0])
    }

    @Test
    @DisplayName("캐시 왕복 — 하프(21,097.5m) 키까지 그대로 복원하고, 파일이 없으면 빈 표")
    fun cacheRoundTrip() {
        val dir = Files.createTempDirectory("runwrap-best-effort-test-${UUID.randomUUID()}").toFile()
        try {
            assertTrue(BestEffortCache.load(directory = dir).isEmpty())
            val table: BestEffortTable = mapOf(
                UUID.randomUUID().toString() to mapOf(1_000.0 to 290.0, 5_000.0 to 1_500.5, 21_097.5 to 6_900.0),
                UUID.randomUUID().toString() to emptyMap(),   // 1K 미만 워크아웃 — 재시도하지 않도록 빈 dict도 남는다
            )
            BestEffortCache.save(table, directory = dir)
            assertEquals(table, BestEffortCache.load(directory = dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
