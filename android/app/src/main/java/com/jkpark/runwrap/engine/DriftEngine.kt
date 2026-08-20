package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.abs

/// 심박 드리프트(Pw:HR 디커플링) 엔진 — 같은 페이스인데 후반 심박이 슬금슬금
/// 오르면 유산소 기반이 아직 부족하다는 신호다 (Friel 디커플링, 기획 문서
/// "HealthKit 미활용 데이터 활용 제안 A2"). 세션을 시간 중앙으로 전·후반을
/// 나눠 효율(EF = 거리 ÷ 총 심박수)을 비교한다. 세션 상세 화면 카드의 재료.
/// iOS `DriftEngine.swift` 이식 — HC에서는 심박 시계열(HeartRateRecord.samples)과
/// 거리 구간(DistanceRecord)이 재료가 된다.
///
/// 인터벌·빌드업처럼 구간마다 페이스가 크게 바뀌는 러닝은 디커플링 해석이
/// 성립하지 않는다 — 정속주 전용 지표라 전/후반 페이스 차이가 크면 판정하지
/// 않는다. "틀린 인사이트는 없느니만 못하다."
object DriftEngine {
    /// 심박 표본 하나 — iOS `(time: Date, bpm: Double)` 튜플 대응
    data class HrSample(val time: Instant, val bpm: Double)

    /// 거리 구간 표본 — iOS `(start: Date, end: Date, meters: Double)` 튜플 대응
    data class DistanceSample(val start: Instant, val end: Instant, val meters: Double)

    data class Result(
        val decouplingPct: Double,  // (전반 EF ÷ 후반 EF − 1) × 100. 양수 = 후반 효율 저하(드리프트)
        val firstHalfEF: Double,    // meters per beat
        val secondHalfEF: Double,
        val tone: RRTone,
    )

    private const val MIN_DURATION_SEC = 1_800.0  // Friel 권장 60분 이상, 우리는 30분을 하한으로 완화
    private const val MIN_SAMPLES_PER_HALF = 20
    private const val MAX_PACE_DIFF_PCT = 10.0    // 이 이상 벌어지면 정속주로 보지 않는다(인터벌·빌드업)
    private const val IMPROVING_THRESHOLD = -2.0  // 이하면 후반이 더 효율적
    private const val CAUTION_THRESHOLD = 5.0     // 미만이면 유산소 기반 탄탄(Friel 기준)

    fun compute(hrSamples: List<HrSample>,
                distanceSamples: List<DistanceSample>,
                start: Instant, durationSec: Double): Result? {
        if (durationSec < MIN_DURATION_SEC) return null

        val midpoint = start.plusMillis((durationSec / 2 * 1_000).toLong())
        val end = start.plusMillis((durationSec * 1_000).toLong())

        val firstHR = hrSamples.filter { it.time < midpoint }
        val secondHR = hrSamples.filter { it.time >= midpoint }
        if (firstHR.size < MIN_SAMPLES_PER_HALF || secondHR.size < MIN_SAMPLES_PER_HALF) return null

        val (firstMeters, secondMeters) = splitDistance(distanceSamples, midpoint)
        if (firstMeters <= 0 || secondMeters <= 0) return null

        val firstMinutes = secondsBetween(start, midpoint) / 60
        val secondMinutes = secondsBetween(midpoint, end) / 60

        // 전/후반 페이스(분/km) 차이가 크면 정속주가 아니다 — 디커플링 해석 불가
        val firstPace = firstMinutes * 1_000 / firstMeters
        val secondPace = secondMinutes * 1_000 / secondMeters
        val paceDiffPct = abs(secondPace / firstPace - 1) * 100
        if (paceDiffPct > MAX_PACE_DIFF_PCT) return null

        val firstBPM = firstHR.map { it.bpm }.average()
        val secondBPM = secondHR.map { it.bpm }.average()

        // EF = 거리(m) ÷ 총 심박수. 총 심박수 = 평균 bpm × 구간 길이(분)
        val firstEF = firstMeters / (firstBPM * firstMinutes)
        val secondEF = secondMeters / (secondBPM * secondMinutes)
        if (!firstEF.isFinite() || !secondEF.isFinite() || firstEF <= 0 || secondEF <= 0) return null

        val decouplingPct = (firstEF / secondEF - 1) * 100

        val tone = when {
            decouplingPct <= IMPROVING_THRESHOLD -> RRTone.IMPROVING
            decouplingPct < CAUTION_THRESHOLD -> RRTone.STEADY
            else -> RRTone.CAUTION  // overload는 쓰지 않는다 — 드리프트 단독으로 과부하 판정은 과함
        }

        return Result(decouplingPct = decouplingPct, firstHalfEF = firstEF,
                      secondHalfEF = secondEF, tone = tone)
    }

    /// 거리 샘플을 중앙 시각 기준 전·후반에 배분한다. 한 샘플이 중앙을 걸치면
    /// 구간 길이 비례로 나눈다.
    private fun splitDistance(samples: List<DistanceSample>,
                              midpoint: Instant): Pair<Double, Double> {
        var first = 0.0
        var second = 0.0
        for (sample in samples) {
            val duration = secondsBetween(sample.start, sample.end)
            if (duration <= 0) {
                if (sample.start < midpoint) first += sample.meters else second += sample.meters
                continue
            }
            when {
                sample.end <= midpoint -> first += sample.meters
                sample.start >= midpoint -> second += sample.meters
                else -> {
                    val firstPortion = secondsBetween(sample.start, midpoint) / duration
                    first += sample.meters * firstPortion
                    second += sample.meters * (1 - firstPortion)
                }
            }
        }
        return Pair(first, second)
    }
}
