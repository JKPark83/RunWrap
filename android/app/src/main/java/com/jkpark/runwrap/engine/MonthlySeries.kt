package com.jkpark.runwrap.engine

import java.time.YearMonth

/// 월별 페이스·EF·거리 시리즈 — iOS `ProgressStats.swift`의 `MonthlySeries` 이식 (기획서 §4.7).
/// MonthlyStats.compute를 월마다 반복 호출해 구성한다.
/// 순수 집계 레이어: 판정이 아니라 집계라 RRTone을 내지 않는다 — 그리는 방법은 화면이 정한다.
data class MonthlySeries(
    val points: List<Point>,     // 오래된 → 최신
) {
    data class Point(
        val month: YearMonth,
        val label: String,       // "3월" — 축 라벨용
        val totalKm: Double,
        val avgPaceSec: Double?, // 그 달 거리 표본이 없으면 null
        val avgEF: Double?,      // 심박 있는 세션 3회 미만이면 null (efficiency 가드와 동일 기준)
    )

    companion object {
        /// 미노출 가드: 러닝이 있는 월이 2개 미만이면 추이라 부를 수 없다 → null.
        /// 구간은 최근 12개월로 자른다 (가정 — 차트 가독성).
        fun compute(runs: List<RunSummary>, now: java.time.Instant): MonthlySeries? {
            val months = MonthlyStats.availableMonths(runs, now).take(12).reversed()
            val points = months.map { month ->
                val stats = MonthlyStats.compute(runs, month, now)
                Point(
                    month = month,
                    label = "${month.monthValue}월",
                    totalKm = stats.totalKm,
                    avgPaceSec = stats.avgPaceSec,
                    avgEF = monthlyEF(runs, month),
                )
            }
            if (points.count { it.totalKm > 0 } < 2) return null
            return MonthlySeries(points)
        }

        /// 월평균 EF = 세션별 (분속 m/min ÷ 평균 심박)의 단순 평균 (TrainingPeaks EF).
        /// 페이스·심박이 모두 있는 세션이 3회 미만인 달은 잡음이 커 점을 내지 않는다.
        private fun monthlyEF(runs: List<RunSummary>, month: YearMonth): Double? {
            val start = month.atDay(1).atStartOfDay(SEOUL).toInstant()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant()
            val efs = runs.filter { it.start >= start && it.start < end }
                .mapNotNull { run ->
                    val pace = run.paceSecPerKm ?: return@mapNotNull null
                    val hr = run.avgHeartRate?.takeIf { it > 0 } ?: return@mapNotNull null
                    (60_000 / pace) / hr
                }
            if (efs.size < 3) return null
            return efs.average()
        }
    }
}
