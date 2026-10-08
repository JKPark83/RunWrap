package com.jkpark.runwrap.engine

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 같은 코스 판정 (이슈 #223) — 시작·끝 150m, 거리 ±5%, 중간점 200m, 루프는 중간점으로만 방향 구분.
/// 경로는 기준점(37.5, 127.0)에서 동·북 방향 미터 오프셋을 10m 간격으로 이어 만든다.
class CourseMatchEngineTests {
    private val now = iso("2026-10-01T06:00:00Z")
    private val metersPerDegree = 111_195.0   // GPXParser와 같은 상수
    private val lonScale = metersPerDegree * cos(37.5 * PI / 180)

    /// (동, 북) 미터 꼭짓점을 차례로 잇는 경로 — 꼭짓점 사이는 10m 간격으로 채운다
    private fun route(corners: List<Pair<Double, Double>>): List<TrackPoint> {
        val points = mutableListOf(corners[0])
        for ((a, b) in corners.zipWithNext()) {
            val steps = maxOf((hypot(b.first - a.first, b.second - a.second) / 10).swiftRoundedInt(), 1)
            for (i in 1..steps) {
                val f = i.toDouble() / steps
                points.add((a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f))
            }
        }
        return points.mapIndexed { i, p ->
            TrackPoint(lat = 37.5 + p.second / metersPerDegree, lon = 127.0 + p.first / lonScale,
                       time = now.plusSeconds(i.toLong()), elevationM = null,
                       horizontalAccuracyM = 5.0, speedMps = null)
        }
    }

    private fun fingerprint(corners: List<Pair<Double, Double>>, distanceM: Double): CourseMatchEngine.Fingerprint =
        assertNotNull(CourseMatchEngine.fingerprint(route(corners), distanceM))

    private fun run(daysAgo: Double, paceSec: Double, km: Double = 5.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = paceSec * km, distanceMeters = km * 1_000, avgHeartRate = 150.0)

    // 직선 5km 동쪽 / 1.25km 정사각형 루프(반시계: 동→북→서→남) / 2.5km 왕복
    private val line = listOf(0.0 to 0.0, 5_000.0 to 0.0)
    private val loopCCW = listOf(0.0 to 0.0, 1_250.0 to 0.0, 1_250.0 to 1_250.0, 0.0 to 1_250.0, 0.0 to 0.0)
    private val outAndBack = listOf(0.0 to 0.0, 2_500.0 to 0.0, 0.0 to 0.0)

    @Test
    @DisplayName("지문 — 중간점은 경로 길이 25·50·75% 지점, 방위는 시작→끝")
    fun fingerprintWaypoints() {
        val fp = fingerprint(line, 5_000.0)
        // 5km 직선: 1,250·2,500·3,750m 지점. 동쪽 직진이라 방위 90°
        val east = fp.waypoints.map { (it.lon - 127.0) * lonScale }
        assertEquals(listOf(1_250, 2_500, 3_750), east.map { it.roundToInt() })
        assertTrue(abs(fp.bearingDeg - 90) < 0.01)
        assertNull(CourseMatchEngine.fingerprint(emptyList(), 5_000.0))
        assertNull(CourseMatchEngine.fingerprint(route(line), 0.0))
    }

    @Test
    @DisplayName("같은 코스 — 경로가 100m 옆으로 비껴도 같은 코스로 묶는다")
    fun sameCourseMatches() {
        val a = fingerprint(line, 5_000.0)
        // 북쪽으로 100m 평행 이동 — 시작·끝(150m)·중간점(200m) 허용 안
        val b = fingerprint(listOf(0.0 to 100.0, 5_000.0 to 100.0), 5_100.0)
        assertTrue(CourseMatchEngine.isSameCourse(a, b))
        assertTrue(CourseMatchEngine.isSameCourse(b, a))
    }

    @Test
    @DisplayName("같은 코스 — 시작점이 150m 넘게 떨어지면 다른 코스")
    fun farStartDoesNotMatch() {
        val a = fingerprint(line, 5_000.0)
        // 북쪽 160m 평행 이동 — 시작·끝이 150m 밖
        val b = fingerprint(listOf(0.0 to 160.0, 5_000.0 to 160.0), 5_000.0)
        assertFalse(CourseMatchEngine.isSameCourse(a, b))
    }

    @Test
    @DisplayName("역방향 — 같은 길을 거꾸로 달리면 같은 코스가 아니다")
    fun reverseDoesNotMatch() {
        val a = fingerprint(line, 5_000.0)
        val b = fingerprint(listOf(5_000.0 to 0.0, 0.0 to 0.0), 5_000.0)
        assertFalse(CourseMatchEngine.isSameCourse(a, b))
    }

    @Test
    @DisplayName("루프 — 같은 방향은 같은 코스, 반대 방향은 중간점으로 갈린다")
    fun loopDirection() {
        val ccw = fingerprint(loopCCW, 5_000.0)
        assertTrue(CourseMatchEngine.isSameCourse(ccw, fingerprint(loopCCW, 5_000.0)))
        // 시계 방향(북→동→남→서): 25% 지점이 (0, 1250) vs (1250, 0) — 1.77km 차이
        val cw = fingerprint(loopCCW.reversed(), 5_000.0)
        assertFalse(CourseMatchEngine.isSameCourse(ccw, cw))
    }

    @Test
    @DisplayName("왕복과 루프 — 시작·끝·거리가 같아도 중간점으로 구분한다")
    fun outAndBackVsLoop() {
        val loop = fingerprint(loopCCW, 5_000.0)
        val back = fingerprint(outAndBack, 5_000.0)
        // 왕복 50% 지점(2500, 0) vs 루프 50% 지점(1250, 1250)
        assertFalse(CourseMatchEngine.isSameCourse(loop, back))
        assertTrue(CourseMatchEngine.isSameCourse(back, fingerprint(outAndBack, 5_000.0)))
    }

    @Test
    @DisplayName("거리 경계 — 짧은 쪽 대비 5%까지 같은 코스, 넘으면 다른 코스")
    fun distanceBoundary() {
        val a = fingerprint(line, 10_000.0)
        // 10,500 − 10,000 = 500 = 10,000 × 5% → 같은 코스. 10,501은 501 > 500 → 다른 코스
        assertTrue(CourseMatchEngine.isSameCourse(a, fingerprint(line, 10_500.0)))
        assertFalse(CourseMatchEngine.isSameCourse(a, fingerprint(line, 10_501.0)))
        assertTrue(CourseMatchEngine.isSameCourse(a, fingerprint(line, 9_524.0)))
        assertFalse(CourseMatchEngine.isSameCourse(a, fingerprint(line, 9_523.0)))
    }

    @Test
    @DisplayName("표본 가드 — 같은 코스 기록이 3회 미만이면 nil, 3회부터 시작 순으로 돌려준다")
    fun minimumMatches() {
        val fp = fingerprint(line, 5_000.0)
        val other = fingerprint(line.reversed(), 5_000.0)
        val target = run(daysAgo = 0.0, paceSec = 300.0)
        val older = run(daysAgo = 10.0, paceSec = 310.0)
        val oldest = run(daysAgo = 20.0, paceSec = 290.0)
        // 대상 + 같은 코스 1 + 역방향 1 → 같은 코스 2회
        assertNull(CourseMatchEngine.matches(fp, listOf(target to fp, older to fp, oldest to other)))
        val matches = assertNotNull(CourseMatchEngine.matches(fp, listOf(target to fp, older to fp, oldest to fp)))
        assertEquals(listOf(oldest.id, older.id, target.id), matches.map { it.id })
    }

    @Test
    @DisplayName("회차·순위 — 시작 순 몇 번째인지와 페이스 순위(빠를수록 1위)")
    fun standing() {
        val first = run(daysAgo = 20.0, paceSec = 290.0)
        val second = run(daysAgo = 10.0, paceSec = 310.0)
        val third = run(daysAgo = 0.0, paceSec = 300.0)
        val matches = listOf(first, second, third)
        // 세 번째 300초: 290초 하나만 더 빠름 → 2위. 첫 번째 290초 → 1위(최고 기록)
        assertEquals(CourseMatchEngine.Standing(3, 2), CourseMatchEngine.standing(third, matches))
        assertEquals(CourseMatchEngine.Standing(1, 1), CourseMatchEngine.standing(first, matches))
        assertNull(CourseMatchEngine.standing(run(daysAgo = 1.0, paceSec = 300.0), matches))
    }

    @Test
    @DisplayName("지문 캐시 — 저장 후 다시 읽으면 같은 지문")
    fun cacheRoundTrip() {
        val dir = Files.createTempDirectory("runwrap-course-test-").toFile()
        try {
            val id = UUID.randomUUID().toString()
            val fp = fingerprint(loopCCW, 5_000.0)
            assertTrue(CourseFingerprintCache.load(dir).isEmpty())
            CourseFingerprintCache.save(mapOf(id to fp), dir)
            assertEquals(mapOf(id to fp), CourseFingerprintCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("데모 공유 코스 — 6km 세 세션은 같은 코스로 묶이고 10km 두 세션은 3회 미만이라 nil")
    fun demoSharedCourse() {
        val runs = DemoData.runs(Instant.now()).filter { !it.isIndoor }
        val history = runs.mapNotNull { run ->
            CourseMatchEngine.fingerprint(WorkoutDetailStore.syntheticRoute(run), run.distanceMeters ?: 0.0)
                ?.let { run to it }
        }
        fun matches(id: String): List<RunSummary>? {
            val target = assertNotNull(history.firstOrNull { it.first.id == id })
            return CourseMatchEngine.matches(target.second, history)
        }
        val six = assertNotNull(matches(DemoData.demoID(5)))
        assertEquals(setOf(DemoData.demoID(5), DemoData.demoID(7), DemoData.demoID(10)), six.map { it.id }.toSet())
        assertNull(matches(DemoData.pausedRunID))
        // 공유 코스가 아닌 세션(20일 전 5km)은 묶이지 않는다
        assertNull(matches(DemoData.demoID(9)))
    }
}
