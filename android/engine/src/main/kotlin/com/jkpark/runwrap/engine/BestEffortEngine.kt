package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.max

/// 베스트 에포트 (이슈 #166) — 한 세션 안에서 목표 거리(1K·5K·10K·하프·풀)를
/// 가장 빨리 지난 연속 구간의 소요 시간. 거리별 최고 기록(PersonalRecords)의 재료.
///
/// 왜: 예전 PR 산식(세션 평균 페이스 × 공인 거리)은 10km 세션 안의 빠른 5km를 놓치고,
/// 완주 거리가 공인 거리 근처인 세션만 후보로 삼았다. 거리 샘플 시계열에서 직접 재면
/// 긴 세션 안의 구간 기록도 잡힌다.
///
/// 시간은 벽시계 기준(정지 포함) — 대회 기록과 같은 정의라 보수적이다.
/// 순수 로직: Foundation만 쓰고 입력만으로 결과가 정해진다.
object BestEffortEngine {
    /// iOS `(label: String, meters: Double)` 튜플
    data class Target(val label: String, val meters: Double)

    /// 목표 거리 5종 (m) — 하프·풀은 공인 거리
    val targets: List<Target> = listOf(
        Target("1K", 1_000.0), Target("5K", 5_000.0), Target("10K", 10_000.0),
        Target("하프", 21_097.5), Target("풀", 42_195.0),
    )

    /// 타당 페이스 범위(초/km) — RunSummary.paceSecPerKm과 같은 기준 (이슈 #76).
    /// 범위 밖 구간은 GPS 튐·걷기 같은 러닝이 아닌 표본으로 보고 항목째 버린다.
    val plausiblePace: ClosedFloatingPointRange<Double> = 150.0..1_200.0

    /// 누적 포인트 한 개 — iOS `(t: Double, d: Double)` 튜플
    private class Point(val t: Double, val d: Double)

    /// 거리 샘플 → 누적 (time, meters) 타임라인 → 목표 거리별 최소 소요 시간(초).
    /// 못 채운 거리·타당 범위 밖 기록은 빠진다 (key = targetMeters)
    fun bestEfforts(distanceSamples: List<DistanceSample>): Map<Double, Double> {
        val samples = distanceSamples
            .filter { it.meters > 0 }             // 음수·0 거리 샘플은 건너뛴다
            .sortedBy { it.start }
        val first = samples.firstOrNull() ?: return emptyMap()

        // 누적 포인트 — 맨 앞은 (첫 샘플 시작, 0m), 이후는 샘플 끝 시각의 누적 거리
        val cum = mutableListOf(Point(sinceReferenceDate(first.start), 0.0))
        var total = 0.0
        for (sample in samples) {
            total += sample.meters
            cum.add(Point(sinceReferenceDate(sample.end), total))
        }

        val result = LinkedHashMap<Double, Double>()
        for (target in targets) {
            if (!(total >= target.meters)) continue
            val best = minimumTime(cum, target.meters) ?: continue
            val pace = best / (target.meters / 1_000)
            if (pace !in plausiblePace) continue
            result[target.meters] = best
        }
        return result
    }

    /// 투 포인터 — 시작점 i마다 cum[j].d − cum[i].d ≥ D인 최소 j를 유지하고,
    /// 구간 (j−1, j) 안에서 정확히 D가 되는 시각을 선형 보간한다. O(n)
    private fun minimumTime(cum: List<Point>, distance: Double): Double? {
        var best: Double? = null
        var j = 1
        for (i in cum.indices) {
            j = max(j, i + 1)
            while (j < cum.size && cum[j].d - cum[i].d < distance) j += 1
            if (j >= cum.size) break   // 이 시작점부터는 D를 못 채운다 — 뒤도 마찬가지
            val prev = cum[j - 1]
            val goal = cum[i].d + distance
            val fraction = (goal - prev.d) / (cum[j].d - prev.d)
            val time = prev.t + fraction * (cum[j].t - prev.t) - cum[i].t
            if (best == null || time < best) best = time
        }
        return best
    }

    /// `Date.timeIntervalSinceReferenceDate` — 2001-01-01T00:00:00Z 기준 초
    private fun sinceReferenceDate(date: Instant): Double = (date.epochSecond - 978_307_200L) + date.nano / 1e9
}
