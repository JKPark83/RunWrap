package com.jkpark.runwrap.engine

import java.time.Duration
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/// 앱 안 경로 플라이오버 (이슈 #224, 경로 기능 리서치 §04 B′안) — 카메라 키프레임과 재생 진행률 → 경로 위 위치.
/// 키프레임은 "그 지점에 실제로 도착한 시각"에 맞춰 놓는다 — 키프레임 사이 길이가 실제 소요 시간에 비례하므로
/// 빨리 달린 구간은 카메라도 빨리 지나간다. iOS `FlyoverEngine.swift`와 같은 산식이다.
/// 순수 로직이라 지도 SDK를 모른다 — 좌표는 위경도 Double로만 다룬다.
object FlyoverEngine {
    /// 이보다 점이 적으면 경로가 너무 성겨 플라이오버를 내지 않는다(진입 버튼 미노출)
    const val minPoints = 20
    /// 재생 길이(초) — 이슈 #224 "15~20초로 정규화"
    const val playbackSec = 18.0
    /// 카메라 키프레임 구간 수 — 이슈 #224 "30~60개"
    const val keyframeCount = 40
    /// heading 이동 평균 반폭 — 앞뒤 2개씩 5개 평균 (급커브에서 카메라가 튀지 않게)
    const val headingHalfWindow = 2
    /// 현재 페이스를 재는 뒤쪽 창(m) — 직전 500m 평균
    const val paceWindowM = 500.0
    /// 이보다 덜 달렸으면 페이스 표본이 부족해 내지 않는다(null)
    const val minPaceDistanceM = 100.0

    /// 카메라 키프레임 — `durationSec`는 직전 키프레임에서 여기까지 걸리는 재생 시간(첫 키프레임은 0)
    data class Keyframe(val lat: Double, val lon: Double, val headingDeg: Double, val durationSec: Double)

    /// 재생 진행률 t 시점의 경로 위 상태 — 지나온 경로 선은 앞 `passedCount`개 점 + 현재 위치
    data class Frame(
        val lat: Double,
        val lon: Double,
        val passedCount: Int,
        val distanceM: Double,
        val elapsedSec: Double,
        /// 직전 500m 평균 페이스(초/km) — 100m 미만이면 null
        val paceSecPerKm: Double?,
    )

    /// 누적 거리·경과 시간을 미리 쌓아 둔 경로
    class Track(val points: List<TrackPoint>, val cumulativeM: List<Double>, val elapsedSec: List<Double>) {
        val totalM: Double get() = cumulativeM.last()
        val totalSec: Double get() = elapsedSec.last()
    }

    /// 점이 `minPoints` 미만이거나 거리·시간이 0이면 null — 플라이오버를 내지 않는다.
    /// `distanceM`(세션 기록 거리)을 주면 누적 거리를 그 길이로 맞춘다 — GPS 경로 길이는 워치 기록 거리와 조금씩 다르다
    fun track(route: List<TrackPoint>, distanceM: Double? = null): Track? {
        if (route.size < minPoints) return null
        val cumulative = ArrayList<Double>(route.size).apply { add(0.0) }
        val elapsed = ArrayList<Double>(route.size).apply { add(0.0) }
        val start = route[0].time
        for (i in 1 until route.size) {
            cumulative.add(cumulative[i - 1] + haversineM(route[i - 1], route[i]))
            // 시각이 뒤집힌 점이 섞여도 경과 시간은 줄지 않게 한다 (이진 탐색 전제)
            val sec = Duration.between(start, route[i].time).toMillis() / 1_000.0
            elapsed.add(max(elapsed[i - 1], sec))
        }
        val raw = cumulative.last()
        if (raw <= 0 || elapsed.last() <= 0) return null
        val scaled = if (distanceM != null && distanceM > 0) cumulative.map { it * distanceM / raw } else cumulative
        return Track(route, scaled, elapsed)
    }

    /// 경로를 거리로 `count` 등분한 `count + 1`개 키프레임. duration 합 = `playbackSec`
    fun keyframes(track: Track, count: Int = keyframeCount, playbackSec: Double = FlyoverEngine.playbackSec): List<Keyframe> {
        val stops = (0..count).map { sample(track, track.cumulativeM, track.totalM * it / count) }
        // 방위: 다음 지점을 향한다(마지막은 직전 방향 유지)
        val raw = stops.indices.map { k ->
            if (k < count) bearingDeg(stops[k], stops[k + 1]) else bearingDeg(stops[k - 1], stops[k])
        }
        // 원형 이동 평균 — 방위는 각도라 sin·cos 평균으로 낸다
        val headings = ArrayList<Double>(raw.size)
        for (k in raw.indices) {
            val window = raw.subList(max(0, k - headingHalfWindow), min(count, k + headingHalfWindow) + 1)
            val s = window.fold(0.0) { acc, d -> acc + sin(d * PI / 180) }
            val c = window.fold(0.0) { acc, d -> acc + cos(d * PI / 180) }
            val mean = atan2(s, c) * 180 / PI
            val previous = headings.lastOrNull()
            if (previous == null) { headings.add(mean); continue }
            // 직전 값과의 차이를 (-180, 180]로 접어 연속값으로 펼친다
            var delta = (mean - previous) % 360
            if (delta > 180) delta -= 360
            if (delta <= -180) delta += 360
            headings.add(previous + delta)
        }
        return stops.indices.map { k ->
            val duration = if (k == 0) 0.0
                else (stops[k].elapsedSec - stops[k - 1].elapsedSec) / track.totalSec * playbackSec
            Keyframe(stops[k].lat, stops[k].lon, headings[k], duration)
        }
    }

    /// 진행률 t(0~1) → 경로 위 위치. 카메라 키프레임과 같은 시간축(실제 경과 시간 비례)이다
    fun frame(track: Track, progress: Double): Frame {
        val current = sample(track, track.elapsedSec, progress.coerceIn(0.0, 1.0) * track.totalSec)
        var pace: Double? = null
        if (current.distanceM >= minPaceDistanceM) {
            val from = sample(track, track.cumulativeM, max(0.0, current.distanceM - paceWindowM))
            pace = (current.elapsedSec - from.elapsedSec) / (current.distanceM - from.distanceM) * 1_000
        }
        return Frame(current.lat, current.lon, current.passedCount, current.distanceM, current.elapsedSec, pace)
    }

    // 보간

    private class Sample(val lat: Double, val lon: Double, val distanceM: Double, val elapsedSec: Double, val passedCount: Int)

    /// 오름차순 `keys`(누적 거리 또는 경과 시간)에서 `value`가 놓인 구간을 찾아 선형 보간한다
    private fun sample(track: Track, keys: List<Double>, value: Double): Sample {
        // value 이상인 첫 키 — 이진 탐색
        var lo = 0
        var hi = keys.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (keys[mid] < value) lo = mid + 1 else hi = mid
        }
        val i = lo
        val j = max(i - 1, 0)
        val a = track.points[j]
        val b = track.points[i]
        val span = if (i > 0) keys[i] - keys[i - 1] else 0.0
        val f = if (span > 0) ((value - keys[i - 1]) / span).coerceIn(0.0, 1.0) else 1.0
        return Sample(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            distanceM = track.cumulativeM[j] + (track.cumulativeM[i] - track.cumulativeM[j]) * f,
            elapsedSec = track.elapsedSec[j] + (track.elapsedSec[i] - track.elapsedSec[j]) * f,
            passedCount = if (f >= 1) i + 1 else i,
        )
    }

    /// 두 좌표의 대권 거리(m) — 하버사인, 지구 반지름 6,371km (`RoutePrivacy`와 같은 식)
    private fun haversineM(a: TrackPoint, b: TrackPoint): Double {
        val toRad = PI / 180
        val dLat = (b.lat - a.lat) * toRad
        val dLon = (b.lon - a.lon) * toRad
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * toRad) * cos(b.lat * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(min(1.0, sqrt(h)))
    }

    /// a → b 초기 방위(도, 0~360)
    private fun bearingDeg(a: Sample, b: Sample): Double {
        val lat1 = a.lat * PI / 180
        val lat2 = b.lat * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x) * 180 / PI
        return if (deg < 0) deg + 360 else deg
    }
}
