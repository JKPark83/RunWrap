package com.jkpark.runwrap.engine

import java.time.Instant

/// 거리별 최고 기록 — iOS `ProgressStats.swift`의 `PersonalRecords` 이식.
/// M3에서는 TrainingGuideEngine(레이스 예측) 입력으로 쓰는 `Entry`만 옮긴다 —
/// 기록 집계(compute)와 발전상 통계(MonthlySeries 등)는 통계 화면과 함께 이식한다 (M4·M5).
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
}
