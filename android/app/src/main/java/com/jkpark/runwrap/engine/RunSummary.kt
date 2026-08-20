package com.jkpark.runwrap.engine

import java.time.Instant

/// 러닝 1회 요약 — iOS `RunSummary.swift` 이식. HC `ExerciseSessionRecord`에서
/// 화면에 필요한 값만 추린다 (id는 HC 레코드 uid — iOS UUID 대응).
data class RunSummary(
    val id: String,
    val start: Instant,
    val durationSec: Double,
    val distanceMeters: Double?,
    val avgHeartRate: Double?,
    /// 세션 소모 칼로리(kcal) — 다이어트 카드의 주간 합계 재료 (기획서 §4.5)
    val calories: Double? = null,
    /// 실내(트레드밀) 여부 — 세션 표시(배지·지도 미노출)에만 쓰고 집계는 통합한다 (기획서 §4.6)
    val isIndoor: Boolean = false,
    /// 평균 케이던스(spm) — 주법 추이 재료. 걸음 수 쿼리 비용 때문에 최근 세션에만 채워진다
    val cadenceSpm: Double? = null,
    /// 세션 당시 기온(°C)·습도(%) — 열 보정 페이스(HeatEngine)의 재료. 실내는 null
    val weatherTempC: Double? = null,
    val weatherHumidityPct: Double? = null,
) {
    val distanceKm: Double? get() = distanceMeters?.let { it / 1000 }

    /// 평균 페이스 (초/km) — 거리가 너무 짧으면 의미가 없어 null
    val paceSecPerKm: Double?
        get() {
            val km = distanceKm ?: return null
            if (km <= 0.1) return null
            return durationSec / km
        }
}
