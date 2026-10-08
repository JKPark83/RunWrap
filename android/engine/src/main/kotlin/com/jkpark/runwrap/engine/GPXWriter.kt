package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/// 러닝 기록 → GPX 1.1 문자열 (이슈 #222). Apple 건강 앱은 운동을 GPX로 내보내지 못해서
/// 사용자가 요청할 때만 Strava·Garmin 등으로 옮길 파일을 만든다 — 앱이 직접 전송하지 않는다.
/// 심박은 Garmin TrackPointExtension v2(`gpxtpx:hr`·`gpxtpx:speed`)에 싣는다.
/// 규칙(이슈 #222 §1):
/// - 모든 `<trkpt>`에 `<time>`(ISO8601 UTC) — Strava는 시각이 없으면 "Time information is missing"으로 거부한다
/// - 값이 없으면 0을 쓰지 않고 요소를 생략한다 (hr 1~255만, 고도·속도는 nil이면 생략)
/// - 세그먼트는 `GPXParser.maxLegMeters`와 같은 기준으로 나눠, 내보낸 파일을 다시 읽어도 같은 세그먼트가 된다
/// ponytail: 케이던스(`gpxtpx:cad`)는 점별 걸음 샘플 쿼리가 없고 단위(spm·spm/2)도 Strava 확인 전이라 생략 —
/// 실기기 업로드로 단위를 정하면 걸음 샘플을 심박처럼 병합해 붙인다
object GPXWriter {
    /// 심박 병합 허용 오차 — 점 시각에서 이보다 먼 샘플은 붙이지 않는다 (이슈 #222)
    const val heartRateToleranceSec = 5.0

    /// 인접 점이 `GPXParser.maxLegMeters`보다 멀면 끊는다 — 일시정지·GPS 끊김 뒤 재개 (파서와 같은 기준)
    fun segments(points: List<TrackPoint>): List<List<TrackPoint>> {
        val result = mutableListOf<List<TrackPoint>>()
        var current = mutableListOf<TrackPoint>()
        for (point in points) {
            val last = current.lastOrNull()
            if (last != null &&
                GPXParser.roughMeters(GeoPoint(last.lat, last.lon), GeoPoint(point.lat, point.lon)) > GPXParser.maxLegMeters) {
                result.add(current)
                current = mutableListOf()
            }
            current.add(point)
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /// heartRates: 시각 오름차순 심박 샘플 — 존 계산에 쓴 샘플을 그대로 받는다(추가 쿼리 없음)
    fun gpx(name: String, start: Instant, segments: List<List<TrackPoint>>,
            heartRates: List<TrainingGuideEngine.HeartRateSample> = emptyList()): String {
        val lines = mutableListOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<gpx version="1.1" creator="런미새" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2">""",
            "  <metadata><time>${iso(start)}</time></metadata>",
            "  <trk>",
            "    <name>${escaped(name)}</name>",
            "    <type>running</type>",
        )
        for (segment in segments) {
            if (segment.isEmpty()) continue
            lines.add("    <trkseg>")
            for (point in segment) {
                lines.add("""      <trkpt lat="${fmt(point.lat, 7)}" lon="${fmt(point.lon, 7)}">""")
                point.elevationM?.let { lines.add("        <ele>${fmt(it, 1)}</ele>") }
                lines.add("        <time>${iso(point.time)}</time>")
                val extensions = mutableListOf<String>()
                nearestBpm(point.time, heartRates)?.let { extensions.add("<gpxtpx:hr>$it</gpxtpx:hr>") }
                point.speedMps?.let { extensions.add("<gpxtpx:speed>${fmt(it, 2)}</gpxtpx:speed>") }
                if (extensions.isNotEmpty()) {
                    lines.add("        <extensions><gpxtpx:TrackPointExtension>" +
                              extensions.joinToString("") + "</gpxtpx:TrackPointExtension></extensions>")
                }
                lines.add("      </trkpt>")
            }
            lines.add("    </trkseg>")
        }
        lines += listOf("  </trk>", "</gpx>", "")
        return lines.joinToString("\n")
    }

    /// "러닝-2026-10-08.gpx" — 날짜는 기기 시간대, 서기 연도
    fun fileName(start: Instant, zone: ZoneId): String {
        val d = start.atZone(zone)
        return "러닝-" + d.year.toString().padStart(4, '0') + "-" +
            d.monthValue.toString().padStart(2, '0') + "-" + d.dayOfMonth.toString().padStart(2, '0') + ".gpx"
    }

    /// iOS `ISO8601DateFormatter` 기본 옵션 = UTC "2026-10-08T06:00:00Z" (소수 초 없음)
    private fun iso(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

    /// ±5초 안의 가장 가까운 샘플(이진 탐색). 1~255 bpm 밖이면 생략
    private fun nearestBpm(time: Instant, samples: List<TrainingGuideEngine.HeartRateSample>): Int? {
        if (samples.isEmpty()) return null
        var low = 0
        var high = samples.size
        while (low < high) {
            val mid = (low + high) / 2
            if (samples[mid].time < time) low = mid + 1 else high = mid
        }
        fun gap(i: Int) = abs((samples[i].time.toEpochMilli() - time.toEpochMilli()) / 1_000.0)
        val nearest = listOf(low - 1, low).filter { it in samples.indices }.minByOrNull { gap(it) } ?: return null
        if (gap(nearest) > heartRateToleranceSec) return null
        val bpm = samples[nearest].bpm.swiftRoundedInt()
        return if (bpm in 1..255) bpm else null
    }

    private fun escaped(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
