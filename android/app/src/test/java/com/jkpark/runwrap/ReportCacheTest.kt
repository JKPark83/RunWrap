package com.jkpark.runwrap

import com.jkpark.runwrap.store.ReportCache
import com.jkpark.runwrap.store.ReportSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val snapshot = ReportSnapshot(
        generatedAtEpochMs = 1_755_600_000_000, // 2025-08-19T…Z 고정값
        headline = "이번 주 훈련량이 적당했습니다",
        suggestion = "회복 러닝 30분을 권합니다",
        weekKm = 32.4,
        runCount = 4,
    )

    @Test
    fun `저장 후 로드하면 같은 스냅샷`() {
        val dir = tmp.newFolder()
        ReportCache.save(snapshot, dir)
        assertEquals(snapshot, ReportCache.load(dir))
    }

    @Test
    fun `suggestion null도 왕복 보존`() {
        val dir = tmp.newFolder()
        ReportCache.save(snapshot.copy(suggestion = null), dir)
        assertNull(ReportCache.load(dir)?.suggestion)
    }

    @Test
    fun `파일 없으면 null - 오류 없이`() {
        assertNull(ReportCache.load(tmp.newFolder()))
    }

    @Test
    fun `손상된 JSON이면 null - 오류 없이`() {
        val dir = tmp.newFolder()
        File(dir, ReportCache.FILENAME).writeText("{ not json !!")
        assertNull(ReportCache.load(dir))
    }

    @Test
    fun `모르는 키는 무시하고 로드`() {
        // 앞으로 필드가 늘어난 스냅샷도 구버전 코드가 읽을 수 있어야 한다
        val dir = tmp.newFolder()
        File(dir, ReportCache.FILENAME).writeText(
            """{"generatedAtEpochMs":1,"headline":"h","suggestion":null,"weekKm":1.0,"runCount":1,"futureField":true}"""
        )
        assertEquals(1, ReportCache.load(dir)?.runCount)
    }

    @Test
    fun `덮어쓰면 최신 스냅샷`() {
        val dir = tmp.newFolder()
        ReportCache.save(snapshot, dir)
        ReportCache.save(snapshot.copy(runCount = 5), dir)
        assertEquals(5, ReportCache.load(dir)?.runCount)
    }
}
