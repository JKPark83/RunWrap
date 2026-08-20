package com.jkpark.runwrap.engine

/// 러너 레벨 3단계 (기획서 §3) — iOS `UserProfile.swift` 이식.
/// 온보딩 설문 결과로 판정하고, 실기록으로 승급만 한다.
///
/// 노출 라벨은 런미새 위트 톤(§4.11)의 별칭이다. 저장값(storageValue)은 영문 그대로 둬서
/// 라벨 문구를 바꿔도 기존 사용자의 저장값이 깨지지 않게 한다 (iOS rawValue 대응).
enum class RunnerLevel(val storageValue: String) {
    BEGINNER("beginner"), INTERMEDIATE("intermediate"), ADVANCED("advanced");

    val label: String
        get() = when (this) {
            BEGINNER -> "런린이"
            INTERMEDIATE -> "런잘알"
            ADVANCED -> "런친놈"
        }

    /// 레벨 비교용 서열 — 승급 판정(LevelEngine)에서 쓴다. 선언 순서에 기대지 않는 명시값
    val rank: Int
        get() = when (this) {
            BEGINNER -> 0
            INTERMEDIATE -> 1
            ADVANCED -> 2
        }

    companion object {
        fun fromStorage(value: String): RunnerLevel? = entries.find { it.storageValue == value }
    }
}
