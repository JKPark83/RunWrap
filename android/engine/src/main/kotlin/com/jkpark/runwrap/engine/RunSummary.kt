package com.jkpark.runwrap.engine

import java.time.Instant
import kotlinx.serialization.Serializable

/// 러닝 1회 요약 — HKWorkout에서 화면에 필요한 값만 추린다
/// (Android: Health Connect ExerciseSessionRecord에서 추린다. id는 iOS UUID 대신 String.
/// @Serializable은 화면 route 인자(JSON 문자열)용)
@Serializable
data class RunSummary(
    val id: String,
    @Serializable(with = ReferenceDateInstantSerializer::class) val start: Instant,
    val durationSec: Double,
    val distanceMeters: Double?,
    val avgHeartRate: Double?,
    /// 세션 최고 심박(bpm) — HRmax 관찰 추정(TrainingGuideEngine.hrMaxEstimate)의 재료.
    /// 워크아웃 통계에서 바로 읽어 추가 쿼리 비용이 없다 (이슈 #34)
    val maxHeartRate: Double? = null,
    /// 세션 소모 칼로리(kcal) — 다이어트 카드의 주간 합계 재료 (기획서 §4.5)
    val calories: Double? = null,
    /// 실내(트레드밀) 여부 — 세션 표시(배지·지도 미노출)에만 쓰고 집계는 통합한다 (기획서 §4.6)
    val isIndoor: Boolean = false,
    /// 평균 케이던스(spm) — 주법 추이(계획서 M4) 재료. 걸음 수 쿼리 비용 때문에
    /// 최근 28일 워크아웃에만 채워진다 (HealthStore가 목록 조회 뒤 백필).
    var cadenceSpm: Double? = null,
    /// 세션 당시 기온(°C)·습도(%) — 워치가 야외 세션에 자동으로 붙이는 날씨 메타데이터.
    /// 열 보정 페이스(HeatEngine)의 재료라 추가 쿼리 비용이 없다 (제안 문서 A1). 실내는 nil.
    var weatherTempC: Double? = null,
    var weatherHumidityPct: Double? = null,
) {
    val distanceKm: Double? get() = distanceMeters?.let { it / 1000 }

    /// 평균 페이스 (초/km) — 러닝으로 볼 수 없는 표본이면 nil (이슈 #76).
    /// - 거리 0.1km 이하: 너무 짧아 페이스가 의미 없다.
    /// - 시간 0초: 외부 앱 가져오기 등에서 duration이 비면 페이스 0 → 영구 PB·EF inf가 된다.
    /// - 150...1200초/km(2:30~20:00) 밖: 세계기록 페이스가 약 2:50/km라 2:30은 러닝으로
    ///   불가능하고, 20:00/km는 걷기보다 느리다. 범위 밖은 사이클 오태깅·GPS 튐 같은
    ///   러닝이 아닌 표본으로 보고 버린다 — "틀린 인사이트는 없느니만 못하다".
    val paceSecPerKm: Double?
        get() {
            val km = distanceKm ?: return null
            if (!(km > 0.1 && durationSec > 0)) return null
            val pace = durationSec / km
            if (pace !in 150.0..1_200.0) return null
            return pace
        }
}
