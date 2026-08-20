package com.jkpark.runwrap.engine

import java.time.Instant

/// 거리별 최고 기록 — iOS `ProgressStats.swift`의 `PersonalRecords` 이식.
/// PR 판정 (가정 — 계획서 M3): 완주 거리 ∈ [D, D×1.10]인 세션 중 (페이스 × D)의 최소값.
/// 워치 GPS는 공인 거리보다 조금 길게 찍히는 게 보통이라 10% 상단 여유를 둔다.
/// 해당 거리 기록이 없으면 항목 자체를 내지 않는다.
/// 발전상 월별 추이는 `MonthlySeries.kt` 참조 (M5에서 통계 화면과 함께 이식).
object PersonalRecords {
    /// 한 종목의 최고 기록 한 건
    data class Entry(
        val label: String,       // "5K" / "10K" / "하프" / "풀"
        val distanceKm: Double,  // 공식 거리
        val timeSec: Double,     // 공식 거리로 환산한 기록 (실측 페이스 × 공식 거리)
        val run: RunSummary,     // 기록이 나온 세션
    ) {
        val date: Instant get() = run.start
    }

    /// 공인 거리 4종 (km)
    val targets = listOf("5K" to 5.0, "10K" to 10.0, "하프" to 21.0975, "풀" to 42.195)

    fun compute(runs: List<RunSummary>): List<Entry> = targets.mapNotNull { (label, targetKm) ->
        runs.mapNotNull { run ->
            val km = run.distanceKm ?: return@mapNotNull null
            val pace = run.paceSecPerKm ?: return@mapNotNull null
            if (km < targetKm || km > targetKm * 1.10) return@mapNotNull null
            pace * targetKm to run
        }
            .minByOrNull { it.first }
            ?.let { (time, run) ->
                Entry(label = label, distanceKm = targetKm, timeSec = time, run = run)
            }
    }
}
