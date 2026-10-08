package com.jkpark.runwrap.engine

import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// GPX 내보내기 (이슈 #222) — 파서 왕복·요소 생략·UTC 시각·이스케이프·심박 병합.
class GPXWriterTests {
    private val start = iso("2026-10-08T06:00:00Z")
    /// 위도 0.0001° ≈ 11m (GPXParser 기준 111,195m/°)
    private fun point(i: Int, lat: Double = 37.5, elevation: Double? = null, speed: Double? = null): TrackPoint =
        TrackPoint(lat = lat + i * 0.0001, lon = 127.0, time = start.plusSeconds(i * 3L),
                   elevationM = elevation, horizontalAccuracyM = 5.0, speedMps = speed)

    private fun count(text: String, of: String): Int = text.split(of).size - 1

    @Test
    @DisplayName("GPX 왕복 — 내보낸 파일을 GPXParser가 같은 좌표·세그먼트로 읽는다")
    fun roundTrip() {
        // 0~4번 점 뒤 위도 0.02°(≈2.2km) 점프 → maxLegMeters(1km) 초과라 세그먼트 2개
        val first = (0 until 5).map { point(it) }
        val second = (5 until 9).map { point(it, lat = 37.52) }
        val segments = GPXWriter.segments(first + second)
        assertEquals(listOf(first, second), segments)

        val gpx = GPXWriter.gpx(name = "아침 러닝", start = start, segments = segments)
        val parsed = GPXParser.parseSegments(gpx.toByteArray())
        assertEquals(2, parsed.size)
        assertEquals(listOf(5, 4), parsed.map { it.size })
        // 좌표는 소수 7자리로 쓴다 — 1e-7° 안에서 같다
        for ((written, read) in segments.flatten().zip(parsed.flatten())) {
            assertTrue(abs(written.lat - read.lat) < 1e-7)
            assertTrue(abs(written.lon - read.lon) < 1e-7)
        }
    }

    @Test
    @DisplayName("심박 없음 — hr 요소를 0이 아니라 생략한다")
    fun omitsMissingHeartRate() {
        val gpx = GPXWriter.gpx(name = "러닝", start = start, segments = listOf(listOf(point(0), point(1))))
        assertFalse(gpx.contains("gpxtpx:hr"))
        // 고도·속도도 없으면 생략 — 확장 블록 자체가 없다
        assertFalse(gpx.contains("<ele>"))
        assertFalse(gpx.contains("<extensions>"))
    }

    @Test
    @DisplayName("심박 병합 — ±5초 안 가장 가까운 샘플을 쓰고, 벗어나면 생략한다")
    fun mergesNearestHeartRate() {
        // 점 시각 0s·3s·30s. 샘플 -2s(150)·4s(162.4)·20s(170)
        // 0s → -2s(2초) 150 / 3s → 4s(1초) 162 / 30s → 최근접 20s가 10초 → 생략
        val samples = listOf(TrainingGuideEngine.HeartRateSample(start.minusSeconds(2), 150.0),
                             TrainingGuideEngine.HeartRateSample(start.plusSeconds(4), 162.4),
                             TrainingGuideEngine.HeartRateSample(start.plusSeconds(20), 170.0))
        val gpx = GPXWriter.gpx(name = "러닝", start = start, segments = listOf(listOf(point(0), point(1), point(10))),
                                heartRates = samples)
        assertEquals(2, count(gpx, "<gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:hr>150</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:hr>162</gpxtpx:hr>"))
    }

    @Test
    @DisplayName("시각 UTC 포맷 — 모든 trkpt에 ISO8601 Z 시각, 고도·속도는 값이 있을 때만")
    fun utcTimeAndOptionalElements() {
        val gpx = GPXWriter.gpx(name = "러닝", start = start,
                                segments = listOf(listOf(point(0, elevation = 12.34, speed = 3.456), point(1))))
        assertTrue(gpx.contains("<metadata><time>2026-10-08T06:00:00Z</time></metadata>"))
        assertTrue(gpx.contains("<time>2026-10-08T06:00:03Z</time>"))
        assertEquals(3, count(gpx, "<time>"))  // metadata 1 + trkpt 2
        assertTrue(gpx.contains("<ele>12.3</ele>"))
        assertTrue(gpx.contains("<gpxtpx:speed>3.46</gpxtpx:speed>"))
    }

    @Test
    @DisplayName("운동 이름 XML 이스케이프 — & < > \" '")
    fun escapesName() {
        val gpx = GPXWriter.gpx(name = """한강 <5K> & "LSD" 'easy'""", start = start, segments = listOf(listOf(point(0))))
        assertTrue(gpx.contains("<name>한강 &lt;5K&gt; &amp; &quot;LSD&quot; &apos;easy&apos;</name>"))
        // 이스케이프한 파일은 파서가 끝까지 읽는다
        assertEquals(1, GPXParser.parse(gpx.toByteArray()).size)
    }

    @Test
    @DisplayName("파일 이름 — 러닝-연-월-일.gpx (기기 시간대)")
    fun fileName() {
        // 2026-10-08T06:00Z = 서울 15:00, 호놀룰루(-10) 전날 20:00
        assertEquals("러닝-2026-10-08.gpx", GPXWriter.fileName(start, ZoneId.of("Asia/Seoul")))
        assertEquals("러닝-2026-10-07.gpx", GPXWriter.fileName(start, ZoneId.of("Pacific/Honolulu")))
    }
}
