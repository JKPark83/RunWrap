package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/// 심박 드리프트(Pw:HR 디커플링) 엔진 — 같은 페이스인데 후반 심박이 슬금슬금
/// 오르면 유산소 기반이 아직 부족하다는 신호다 (Friel 디커플링, 기획 문서
/// "HealthKit 미활용 데이터 활용 제안 A2"). 세션을 시간 중앙으로 전·후반을
/// 나눠 효율(EF = 거리 ÷ 총 심박수)을 비교한다. 세션 상세 화면 카드의 재료.
///
/// 인터벌·빌드업처럼 구간마다 페이스가 크게 바뀌는 러닝은 디커플링 해석이
/// 성립하지 않는다 — 정속주 전용 지표라 전/후반 페이스 차이가 크면 판정하지
/// 않는다. "틀린 인사이트는 없느니만 못하다."
///
/// 전·후반 평균만 보면 워밍업·쿨다운이 대칭인 인터벌(빠름/느림 교대)은 평균이 같아
/// 통과해 버린다 — 심박 지연으로 흔들린 EF 비교가 "유산소 기반 부족"으로 나간다.
/// 그래서 1km 스플릿 페이스의 변동계수(표준편차 ÷ 평균)도 본다: 8%를 넘으면 정속주가
/// 아니다. 스플릿 시간에서는 정지 구간을 뺀다(ActiveTimeline — 전·후반 페이스와 같은 원칙).
/// 스플릿이 3개 미만이면 변동을 판단할 표본이 없어 이 가드는 건너뛴다 (감사 2026-09-29, 이슈 #100).
/// (Android: 정지 구간 `DateInterval`은 ActiveTimeline과 같이 닫힌 구간 `ClosedRange<Instant>`로 받는다)
object DriftEngine {
    data class Result(
        val decouplingPct: Double,  // (전반 EF ÷ 후반 EF − 1) × 100. 양수 = 후반 효율 저하(드리프트)
        val firstHalfEF: Double,    // meters per beat
        val secondHalfEF: Double,
        val tone: RRTone,
    )

    /// 심박 샘플 한 개 — iOS `(time: Date, bpm: Double)` 튜플
    data class HeartRateSample(val time: Instant, val bpm: Double)

    private const val minDurationSec = 1_800.0  // Friel 권장 60분 이상, 우리는 30분을 하한으로 완화
    private const val minSamplesPerHalf = 20
    private const val maxPaceDiffPct = 10.0      // 이 이상 벌어지면 정속주로 보지 않는다(인터벌·빌드업)
    private const val maxSplitCV = 0.08          // 1km 스플릿 페이스 변동계수 상한 — 넘으면 인터벌로 본다
    private const val minSplitsForCV = 3         // 이보다 적으면 변동계수 가드를 건너뛴다
    private const val minTailMeters = 500.0      // 마지막 잔여 구간은 이 이상일 때만 스플릿으로 친다
    private const val improvingThreshold = -2.0  // 이하면 후반이 더 효율적
    private const val cautionThreshold = 5.0     // 미만이면 유산소 기반 탄탄(Friel 기준)

    private const val maxTimelineGapSec = 30.0  // 벽시계 − 활동 − 정지 합이 이보다 크면 타임라인 복원 불가 (감사 M5)

    /// - durationSec: 활동 시간(일시정지 제외, HKWorkout.duration)
    /// - pauses: 정지 구간(벽시계). 중앙 시각을 활동 기준으로 옮기고 정지 중 심박을 뺀다 (이슈 #47)
    /// - end: 워크아웃 종료 벽시계 시각. 주어지면 정지 구간으로 벽시계와 활동 시간이
    ///   맞춰지는지 확인하고, 안 맞으면 판정하지 않는다(표본 부족 원칙)
    fun compute(
        hrSamples: List<HeartRateSample>,
        distanceSamples: List<DistanceSample>,
        start: Instant,
        durationSec: Double,
        pauses: List<ClosedRange<Instant>> = emptyList(),
        end: Instant? = null,
    ): Result? {
        if (!(durationSec >= minDurationSec)) return null

        if (end != null) {
            val pausedSec = pauses.fold(0.0) { sum, pause -> sum + seconds(pause.start, pause.endInclusive) }
            val unaccounted = seconds(start, end) - durationSec - pausedSec
            if (!(abs(unaccounted) <= maxTimelineGapSec)) return null
        }

        // 중앙 시각은 활동 시간의 절반 지점 — 앞선 정지 구간만큼 벽시계로 밀린다
        val midpoint = ActiveTimeline.wallTime(afterActive = durationSec / 2, from = start, pauses = pauses)

        // 정지 중 심박(신호 대기의 낮은 심박)은 효율 비교에서 뺀다
        val activeHR = hrSamples.filter { !ActiveTimeline.isPaused(it.time, pauses) }
        val firstHR = activeHR.filter { it.time < midpoint }
        val secondHR = activeHR.filter { it.time >= midpoint }
        if (!(firstHR.size >= minSamplesPerHalf && secondHR.size >= minSamplesPerHalf)) return null

        val (firstMeters, secondMeters) = splitDistance(distanceSamples, midpoint)
        if (!(firstMeters > 0 && secondMeters > 0)) return null

        // 전·후반 모두 활동 시간의 절반 (정지 구간은 중앙 시각 계산에서 이미 건너뛰었다)
        val firstMinutes = durationSec / 2 / 60
        val secondMinutes = firstMinutes

        // 전/후반 페이스(분/km) 차이가 크면 정속주가 아니다 — 디커플링 해석 불가
        val firstPace = firstMinutes * 1_000 / firstMeters
        val secondPace = secondMinutes * 1_000 / secondMeters
        val paceDiffPct = abs(secondPace / firstPace - 1) * 100
        if (!(paceDiffPct <= maxPaceDiffPct)) return null

        // 대칭 인터벌은 전/후반 평균이 같아 위 가드를 통과한다 — 스플릿 변동으로 한 번 더 거른다
        val splits = splitPaces(distanceSamples, pauses)
        if (splits.size >= minSplitsForCV) {
            val mean = average(splits)
            val variance = average(splits.map { (it - mean) * (it - mean) })
            if (!(sqrt(variance) / mean <= maxSplitCV)) return null
        }

        val firstBPM = average(firstHR.map { it.bpm })
        val secondBPM = average(secondHR.map { it.bpm })

        // EF = 거리(m) ÷ 총 심박수. 총 심박수 = 평균 bpm × 구간 길이(분)
        val firstEF = firstMeters / (firstBPM * firstMinutes)
        val secondEF = secondMeters / (secondBPM * secondMinutes)
        if (!(firstEF.isFinite() && secondEF.isFinite() && firstEF > 0 && secondEF > 0)) return null

        val decouplingPct = (firstEF / secondEF - 1) * 100

        val tone = if (decouplingPct <= improvingThreshold) {
            RRTone.improving
        } else if (decouplingPct < cautionThreshold) {
            RRTone.steady
        } else {
            RRTone.caution  // overload는 쓰지 않는다 — 드리프트 단독으로 과부하 판정은 과함
        }

        return Result(decouplingPct = decouplingPct, firstHalfEF = firstEF, secondHalfEF = secondEF, tone = tone)
    }

    /// 거리 샘플을 중앙 시각 기준 전·후반에 배분한다. 한 샘플이 중앙을 걸치면
    /// 구간 길이 비례로 나눈다.
    private fun splitDistance(samples: List<DistanceSample>, midpoint: Instant): Pair<Double, Double> {
        var first = 0.0
        var second = 0.0
        for (sample in samples) {
            val duration = seconds(sample.start, sample.end)
            if (!(duration > 0)) {
                if (sample.start < midpoint) first += sample.meters else second += sample.meters
                continue
            }
            if (sample.end <= midpoint) {
                first += sample.meters
            } else if (sample.start >= midpoint) {
                second += sample.meters
            } else {
                val firstPortion = seconds(sample.start, midpoint) / duration
                first += sample.meters * firstPortion
                second += sample.meters * (1 - firstPortion)
            }
        }
        return first to second
    }

    /// 1km 스플릿 페이스(초/km) — 누적 거리 기준, km 경계는 샘플 안에서 선형 보간한다
    /// (ActiveTimeline.splits와 같은 방식). 마지막 잔여 구간은 0.5km 이상일 때만
    /// km당 페이스로 환산해 넣는다. 스플릿 시간에서 정지 구간을 뺀다.
    /// 60초/km 미만은 데이터 오류로 보고 버린다(ActiveTimeline.splits와 같은 기준).
    private fun splitPaces(samples: List<DistanceSample>, pauses: List<ClosedRange<Instant>>): List<Double> {
        val sorted = samples.sortedBy { it.start }
        var boundaryTime = sorted.firstOrNull()?.start ?: return emptyList()
        val lastEnd = sorted.last().end
        val paces = mutableListOf<Double>()
        var cumulative = 0.0
        var nextBoundary = 1_000.0

        for (sample in sorted) {
            if (!(sample.meters > 0)) continue
            val before = cumulative
            cumulative += sample.meters
            while (cumulative >= nextBoundary) {
                val fraction = (nextBoundary - before) / sample.meters
                val crossing = adding(sample.start, seconds(sample.start, sample.end) * fraction)
                paces.add(ActiveTimeline.activeSeconds(from = boundaryTime, to = crossing, pauses = pauses))
                boundaryTime = crossing
                nextBoundary += 1_000
            }
        }

        val tailMeters = cumulative - (nextBoundary - 1_000)
        if (tailMeters >= minTailMeters) {
            val sec = ActiveTimeline.activeSeconds(from = boundaryTime, to = lastEnd, pauses = pauses)
            paces.add(sec * 1_000 / tailMeters)
        }
        return paces.filter { it > 60 }
    }

    private fun average(values: List<Double>): Double =
        values.fold(0.0) { sum, v -> sum + v } / values.size

    /// `to.timeIntervalSince(from)` — Swift `Date`처럼 Double 초로 뺀다
    private fun seconds(from: Instant, to: Instant): Double = to.timeIntervalSince1970 - from.timeIntervalSince1970

    /// `date.addingTimeInterval(seconds)`
    private fun adding(date: Instant, seconds: Double): Instant = instantSince1970(date.timeIntervalSince1970 + seconds)
}
