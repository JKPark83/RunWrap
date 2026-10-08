package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// PB 갱신 감지 엔진 + 베이스라인 캐시 왕복 검증 (이슈 #21). now 고정.
class PBEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")

    private fun entry(label: String, km: Double, timeSec: Double): PersonalRecords.Entry {
        val run = RunSummary(id = UUID.randomUUID().toString(), start = now, durationSec = timeSec,
                             distanceMeters = km * 1_000, avgHeartRate = 160.0)
        return PersonalRecords.Entry(label = label, distanceKm = km, timeSec = timeSec, run = run)
    }

    @Test
    @DisplayName("첫 비교 — 베이스라인이 없으면 기존 기록을 축하하지 않고 조용히 지나간다")
    fun firstRunSeedsSilently() {
        val current = listOf(entry("5K", km = 5.0, timeSec = 1_320.0))
        assertTrue(PBEngine.newRecords(current = current, baseline = null).isEmpty())
    }

    @Test
    @DisplayName("갱신 감지 — 새 종목과 0.5초 넘게 빨라진 기록만 PB로 본다")
    fun detectsNewRecords() {
        val baseline = PBBaseline(times = mapOf("5K" to 1_320.0, "10K" to 2_760.0))
        // 5K −0.3초는 부동소수 재계산 노이즈 가드에 걸리고, 10K −5초와 첫 하프는 PB다
        val current = listOf(entry("5K", km = 5.0, timeSec = 1_319.7),
                             entry("10K", km = 10.0, timeSec = 2_755.0),
                             entry("하프", km = 21.0975, timeSec = 6_000.0))
        val fresh = PBEngine.newRecords(current = current, baseline = baseline)
        assertEquals(listOf("10K", "하프"), fresh.map { it.label })
    }

    @Test
    @DisplayName("베이스라인 생성 — 종목 라벨 → 기록 초 딕셔너리로 접는다")
    fun makeBaseline() {
        val baseline = PBBaseline.make(from = listOf(entry("5K", km = 5.0, timeSec = 1_320.0),
                                                     entry("풀", km = 42.195, timeSec = 14_400.0)))
        assertEquals(mapOf("5K" to 1_320.0, "풀" to 14_400.0), baseline.times)
    }

    @Test
    @DisplayName("캐시 왕복 — 저장한 베이스라인을 그대로 복원하고, 파일이 없으면 nil")
    fun cacheRoundTrip() {
        val dir = Files.createTempDirectory("runwrap-pb-test-").toFile()
        try {
            assertNull(PBBaselineCache.load(dir))
            val baseline = PBBaseline(times = mapOf("10K" to 2_755.0))
            PBBaselineCache.save(baseline, dir)
            assertEquals(baseline, PBBaselineCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("산식 버전 — version < 2(평균 페이스 산식) 베이스라인은 nil로 읽어 조용히 재시드한다 (이슈 #166)")
    fun oldVersionBaselineLoadsAsNil() {
        val dir = Files.createTempDirectory("runwrap-pb-test-").toFile()
        try {
            // 버전 필드가 없는 옛 파일 → version 1로 디코딩 → nil
            File(dir, PBBaselineCache.filename).writeText("""{"times":{"5K":1320}}""")
            assertNull(PBBaselineCache.load(dir))

            // 명시적 version 1도 nil, 새로 만든 베이스라인은 현재 버전(2)이라 그대로 읽힌다
            PBBaselineCache.save(PBBaseline(times = mapOf("5K" to 1_320.0), version = 1), dir)
            assertNull(PBBaselineCache.load(dir))
            val fresh = PBBaseline.make(from = listOf(entry("5K", km = 5.0, timeSec = 1_320.0)))
            assertEquals(PBBaseline.currentVersion, fresh.version)
            PBBaselineCache.save(fresh, dir)
            assertEquals(fresh, PBBaselineCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
