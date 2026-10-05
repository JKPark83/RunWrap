package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.max

/// 누적 거리 샘플 한 개 — iOS `(start: Date, end: Date, meters: Double)` 튜플.
/// ActiveTimeline·BestEffortEngine이 같이 쓴다.
data class DistanceSample(val start: Instant, val end: Instant, val meters: Double)

/// 활동 타임라인 엔진 — 오토포즈·신호 대기로 멈춘 시간을 km 스플릿과 심박 드리프트에서
/// 빼기 위한 순수 로직 (감사 리포트 M5·M6, 이슈 #47).
///
/// 왜 필요한가: `HKWorkout.duration`은 일시정지를 뺀 활동 시간인데, 샘플 시각은
/// 벽시계라 둘을 그대로 섞으면 정지 시간이 스플릿에 더해지고(“페이스 유지 실패” 오판)
/// 드리프트의 중앙 시각이 어긋나 거짓 improving이 나온다.
/// HealthKit 타입은 받지 않는다 — 스토어가 이벤트·샘플을 값 타입으로 바꿔 넘긴다.
/// (Android: `DateInterval`은 닫힌 구간 `ClosedRange<Instant>`(`start..end`)로 옮긴다)
object ActiveTimeline {
    /// 워크아웃 이벤트를 엔진용으로 옮긴 표식. 사용자 정지(pause/resume)와
    /// 모션 정지(motionPaused/motionResumed)는 서로 섞여 올 수 있어 따로 추적한다.
    enum class Marker {
        pause, resume, motionPause, motionResume
    }

    /// iOS `(date: Date, kind: Marker)` 튜플
    data class MarkerEvent(val date: Instant, val kind: Marker)

    /// iOS `(index: Int, paceSecPerKm: Double)` 튜플
    data class Split(val index: Int, val paceSecPerKm: Double)

    /// 표식 → 정지 구간. 사용자 정지와 모션 정지 중 하나라도 걸려 있으면 정지 상태로 본다
    /// (둘을 한 줄로 합치면 pause→motionPause→motionResume→resume 순서에서 정지를 덜 뺀다).
    /// 짝 없는 재개·중복 정지는 무시하고, 끝까지 닫히지 않은 정지는 end에서 닫는다.
    /// 결과는 [start, end]로 자르고 길이 0인 구간은 버린다.
    fun pauses(markers: List<MarkerEvent>, start: Instant, end: Instant): List<ClosedRange<Instant>> {
        var userPaused = false
        var motionPaused = false
        var pauseStart: Instant? = null
        val result = mutableListOf<ClosedRange<Instant>>()

        fun close(date: Instant) {
            val from = pauseStart ?: return
            val clippedStart = maxOf(from, start)
            val clippedEnd = minOf(date, end)
            if (clippedEnd > clippedStart) {
                result.add(clippedStart..clippedEnd)
            }
            pauseStart = null
        }

        for (marker in markers.sortedBy { it.date }) {
            val wasPaused = userPaused || motionPaused
            when (marker.kind) {
                Marker.pause -> userPaused = true
                Marker.resume -> userPaused = false
                Marker.motionPause -> motionPaused = true
                Marker.motionResume -> motionPaused = false
            }
            val isPaused = userPaused || motionPaused
            if (!wasPaused && isPaused) {
                pauseStart = marker.date
            } else if (wasPaused && !isPaused) {
                close(marker.date)
            }
        }
        close(end)
        return result
    }

    /// 시각이 정지 구간 안인지 — 재개 시각의 샘플은 활동으로 친다(반열린 구간)
    fun isPaused(date: Instant, pauses: List<ClosedRange<Instant>>): Boolean =
        pauses.any { it.start <= date && date < it.endInclusive }

    /// from~to 벽시계 구간에서 정지 구간과 겹친 만큼을 뺀 활동 초
    fun activeSeconds(from: Instant, to: Instant, pauses: List<ClosedRange<Instant>>): Double {
        val wall = max(seconds(from, to), 0.0)
        val paused = pauses.fold(0.0) { sum, pause ->
            val overlap = seconds(maxOf(from, pause.start), minOf(to, pause.endInclusive))
            sum + max(overlap, 0.0)
        }
        return max(wall - paused, 0.0)
    }

    /// start부터 활동 seconds초가 흐른 벽시계 시각 — 앞선 정지 구간 길이를 차례로 더한다
    fun wallTime(afterActive: Double, from: Instant, pauses: List<ClosedRange<Instant>>): Instant {
        var cursor = from
        var remaining = afterActive
        for (pause in pauses.sortedBy { it.start }) {
            if (!(pause.endInclusive > cursor)) continue
            val runUntilPause = max(seconds(cursor, pause.start), 0.0)
            if (remaining <= runUntilPause) return adding(cursor, remaining)
            remaining -= runUntilPause
            cursor = pause.endInclusive
        }
        return adding(cursor, remaining)
    }

    /// 이벤트가 없는 기록의 폴백 — 거리 샘플 사이가 minGap초 이상 벌어진 곳을 정지로 본다
    /// (감사 리포트 M6 제안값 10초). 정지가 없는 세션의 GPS 공백까지 빼지 않도록
    /// 호출부가 “벽시계 − 활동 시간 > 30초”일 때만 부른다.
    fun gapPauses(samples: List<DistanceSample>, minGap: Double = 10.0): List<ClosedRange<Instant>> {
        val sorted = samples.sortedBy { it.start }
        var lastEnd = sorted.firstOrNull()?.end ?: return emptyList()
        val result = mutableListOf<ClosedRange<Instant>>()
        for (sample in sorted.drop(1)) {
            if (seconds(lastEnd, sample.start) >= minGap) {
                result.add(lastEnd..sample.start)
            }
            lastEnd = maxOf(lastEnd, sample.end)
        }
        return result
    }

    /// 누적 거리 샘플 → km 스플릿. km 경계는 샘플 사이를 선형 보간한다.
    /// index는 실제 km 번호 — 데이터 오류로 건너뛴 구간이 있어도 눈금이 밀리지 않는다.
    /// 경계 사이 시간에서 정지 구간을 뺀다 — pauses가 비면 벽시계 차이 그대로다.
    fun splits(distanceSamples: List<DistanceSample>, pauses: List<ClosedRange<Instant>>): List<Split> {
        val result = mutableListOf<Split>()
        var cumulative = 0.0        // m
        var boundaryTime: Instant? = distanceSamples.firstOrNull()?.start
        var nextBoundary = 1000.0

        for (sample in distanceSamples) {
            val meters = sample.meters
            val before = cumulative
            cumulative += meters
            while (cumulative >= nextBoundary && meters > 0) {
                val fraction = (nextBoundary - before) / meters
                val duration = seconds(sample.start, sample.end)
                val crossing = adding(sample.start, duration * fraction)
                val start = boundaryTime
                if (start != null) {
                    val sec = activeSeconds(start, crossing, pauses)
                    if (sec > 60) {  // 60초/km 미만은 데이터 오류로 본다
                        result.add(Split(index = (nextBoundary / 1000).toInt(), paceSecPerKm = sec))
                    }
                }
                boundaryTime = crossing
                nextBoundary += 1000
            }
        }
        return result
    }

    /// `to.timeIntervalSince(from)` — Swift `Date`처럼 Double 초로 뺀다
    private fun seconds(from: Instant, to: Instant): Double = to.timeIntervalSince1970 - from.timeIntervalSince1970

    /// `date.addingTimeInterval(seconds)`
    private fun adding(date: Instant, seconds: Double): Instant = instantSince1970(date.timeIntervalSince1970 + seconds)
}
