package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.abs

/// 주간 주법 인사이트 — 최근 2주 vs 이전 2주 평균 케이던스 비교 (계획서 M4).
/// iOS `FormEngine.swift`의 `FormTrend` 이식. 세션별 주법 3종(수직 진폭·접촉 시간·보폭)은
/// HC에 러닝 다이내믹스 레코드가 없어 v1 제외지만(계획서 §2.2), 이 추이는 케이던스
/// (StepsCadenceRecord 근사)만 쓰므로 리포트 카드와 함께 이식한다.
data class FormTrend(
    val recentSpm: Double,
    val previousSpm: Double,
    val tone: RRTone,
) {
    val deltaSpm: Double get() = recentSpm - previousSpm

    companion object {
        /// 미노출 가드: 두 창 각각 케이던스 표본 3회 미만이면 추이를 내지 않는다.
        /// ±2 spm 미만 변화는 측정 요동으로 보고 유지로 판정한다 (가정 — iOS 동일).
        fun compute(runs: List<RunSummary>, now: Instant): FormTrend? {
            val mid = now.minusSeconds(14 * 86_400L)
            val cutoff = now.minusSeconds(28 * 86_400L)
            val recent = runs.filter { it.start > mid && it.start <= now }
                .mapNotNull { it.cadenceSpm }
            val previous = runs.filter { it.start > cutoff && it.start <= mid }
                .mapNotNull { it.cadenceSpm }
            if (recent.size < 3 || previous.size < 3) return null
            val recentAvg = recent.average()
            val previousAvg = previous.average()
            val delta = recentAvg - previousAvg
            val tone = when {
                abs(delta) < 2 -> RRTone.STEADY
                delta > 0 -> RRTone.IMPROVING
                else -> RRTone.CAUTION
            }
            return FormTrend(recentSpm = recentAvg, previousSpm = previousAvg, tone = tone)
        }
    }
}
