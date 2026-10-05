package com.jkpark.runwrap.engine

import java.time.Instant

/// (Android 전용 — iOS 대응 없음) 기간 단위로 한 번에 읽은 표본을 세션 구간별로 나눈다.
///
/// iOS는 러닝 목록의 거리·심박·칼로리를 HKWorkout 통계에서 그대로 꺼내 추가 쿼리가 없다.
/// Health Connect에는 세션 통계가 없어 세션마다 집계(aggregate)를 부르면 기록이 수백 건일 때
/// 읽기 호출 한도에 걸린다 — 그래서 타입마다 기간 전체를 한 번 읽고 여기서 세션별로 나눈다.
object SessionSlices {
    /// 누적 표본 한 건 — 거리(m)·걸음 수·칼로리(kcal)
    data class Amount(val start: Instant, val end: Instant, val value: Double)

    /// 누적 표본 묶음 — 시작 시각 순으로 정렬해 두고, 구간마다 이분 탐색으로 시작점을 잡아 훑는다
    class Totals(amounts: List<Amount>) {
        private val sorted = amounts.sortedBy { it.start }
        /// 가장 긴 표본 길이(초) — 구간 시작보다 이만큼 앞에서 시작한 표본까지 구간에 걸칠 수 있다
        private val maxSpanSec = sorted.maxOfOrNull { seconds(it.start, it.end) } ?: 0.0

        /// [start, end]에 걸친 표본의 합 — 구간 밖으로 걸친 표본은 겹친 비율만큼만 센다.
        /// 걸친 표본이 없으면 nil (iOS 통계의 sumQuantity()가 nil인 것과 같다)
        fun sum(start: Instant, end: Instant): Double? {
            val from = instantSince1970(start.timeIntervalSince1970 - maxSpanSec)
            var i = firstIndex(sorted.size) { sorted[it].start >= from }
            var total: Double? = null
            while (i < sorted.size && sorted[i].start <= end) {
                val amount = sorted[i++]
                val span = seconds(amount.start, amount.end)
                val overlap = seconds(maxOf(amount.start, start), minOf(amount.end, end))
                if (span > 0 && overlap > 0) {
                    total = (total ?: 0.0) + amount.value * overlap / span
                } else if (span <= 0 && amount.start >= start) {
                    total = (total ?: 0.0) + amount.value
                }
            }
            return total
        }
    }

    /// [start, end] 안의 순간 표본(심박) — samples는 시각 오름차순
    fun within(samples: List<TrainingGuideEngine.HeartRateSample>,
               start: Instant, end: Instant): List<TrainingGuideEngine.HeartRateSample> {
        val from = firstIndex(samples.size) { samples[it].time >= start }
        val to = firstIndex(samples.size) { samples[it].time > end }
        return if (from < to) samples.subList(from, to) else emptyList()
    }

    /// 단조 술어가 처음 참이 되는 인덱스 — 없으면 size
    private fun firstIndex(size: Int, predicate: (Int) -> Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (predicate(mid)) high = mid else low = mid + 1
        }
        return low
    }

    private fun seconds(from: Instant, to: Instant): Double = to.timeIntervalSince1970 - from.timeIntervalSince1970
}
