package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId

/// 발전상 (기획서 §4.7, 계획서 M3) — 통계 탭의 장기 추이·최고 기록 재료.
/// 순수 집계 레이어: Foundation만 쓰고 now를 주입받아 결정론적이다.
/// 판정이 아니라 집계라 RRTone을 내지 않는다 — 그리는 방법은 화면이 정한다.

/// 월별 페이스·EF·거리 시리즈 — MonthlyStats.compute를 월마다 반복 호출해 구성
data class MonthlySeries(
    val points: List<Point>,          // 오래된 → 최신
) {
    data class Point(
        val month: Instant,           // 월 시작일
        val label: String,            // "3월" — 축 라벨용
        val totalKm: Double,
        val avgPaceSec: Double?,      // 그 달 거리 표본이 없으면 nil
        val avgEF: Double?,           // 심박 있는 세션 3회 미만이면 nil (efficiency 가드와 동일 기준)
    )

    companion object {
        /// 미노출 가드: 러닝이 있는 월이 2개 미만이면 추이라 부를 수 없다 → nil.
        /// 구간은 최근 12개월로 자른다 (가정 — 차트 가독성).
        fun compute(runs: List<RunSummary>, now: Instant, zone: ZoneId): MonthlySeries? {
            val months = MonthlyStats.availableMonths(runs, now, zone).take(12).reversed()

            val points = months.map { month ->
                val stats = MonthlyStats.compute(runs, month, now, zone)
                // (Android: DateFormatter ko_KR "M월" — 로케일 데이터 차이를 피하려고 직접 조립한다)
                Point(month = month,
                      label = "${month.atZone(zone).monthValue}월",
                      totalKm = stats.totalKm,
                      avgPaceSec = stats.avgPaceSec,
                      avgEF = monthlyEF(runs, month, zone))
            }
            if (points.count { it.totalKm > 0 } < 2) return null
            return MonthlySeries(points)
        }

        /// 월평균 EF = 세션별 (분속 m/min ÷ 평균 심박)의 단순 평균 (TrainingPeaks EF).
        /// 페이스·심박이 모두 있는 세션이 3회 미만인 달은 잡음이 커 점을 내지 않는다.
        private fun monthlyEF(runs: List<RunSummary>, month: Instant, zone: ZoneId): Double? {
            // (Android: DateInterval.contains는 끝을 포함한다 — 닫힌 구간 [월 시작, 다음 달 시작])
            val start = month.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            val interval = start.toInstant()..start.plusMonths(1).toInstant()
            val efs = runs.filter { it.start in interval }
                .mapNotNull { run ->
                    val pace = run.paceSecPerKm ?: return@mapNotNull null
                    val hr = run.avgHeartRate
                    if (hr == null || !(hr > 0)) return@mapNotNull null
                    (60_000 / pace) / hr
                }
            if (efs.size < 3) return null
            return efs.sum() / efs.size
        }
    }
}

/// 거리별 최고 기록 — 5K/10K/하프/풀 (기획서 §4.7, 이슈 #166).
/// PR = 세션마다 거리 샘플에서 잰 베스트 에포트(목표 거리를 가장 빨리 지난 연속 구간의
/// 벽시계 시간, BestEffortEngine)의 최소값. 10km 세션 안의 빠른 5km도 5K 기록이 된다.
/// 아직 계산되지 않은(백필 대기) 세션은 후보에서 빠진다.
/// 해당 거리 기록이 없으면 항목 자체를 내지 않는다.
object PersonalRecords {
    data class Entry(
        val label: String,        // "5K" · "10K" · "하프" · "풀"
        val distanceKm: Double,   // 공인 거리
        val timeSec: Double,      // 베스트 에포트 — 공인 거리 구간의 소요 시간
        val run: RunSummary,      // 기록을 세운 세션 — 목록에서 탭하면 이 세션 상세로 간다
    ) {
        /// 달성일 — 세션 시작 시각과 같다
        val date: Instant get() = run.start
    }

    /// 공인 거리 4종 (m) — 베스트 에포트 목표 거리에서 1K를 뺀 것.
    /// 1K는 대회 종목이 아니고 거의 모든 러닝이 후보라 PB로서 의미가 옅다 (엔진·캐시는 그대로 잰다)
    val targets: List<BestEffortEngine.Target> = BestEffortEngine.targets.filter { it.meters >= 5_000 }

    fun compute(runs: List<RunSummary>, efforts: BestEffortTable): List<Entry> =
        targets.mapNotNull { target ->
            val candidates = runs.mapNotNull { run ->
                val time = efforts[run.id]?.get(target.meters) ?: return@mapNotNull null
                time to run
            }
            // minByOrNull은 동률에서 앞 원소를 돌려준다 — Swift min(by:)와 같다
            val best = candidates.minByOrNull { it.first } ?: return@mapNotNull null
            Entry(label = target.label, distanceKm = target.meters / 1_000,
                  timeSec = best.first, run = best.second)
        }
}
