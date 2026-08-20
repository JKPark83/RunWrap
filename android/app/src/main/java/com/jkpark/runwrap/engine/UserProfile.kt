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

/// 러닝 목적 (기획서 §2 Q9) — 복수 선택. 리포트 문장의 강조점에 쓴다.
///
/// 목적은 카드를 켜고 끄지 않는다 (그건 레벨 게이트의 몫) — 어떤 목적을 골라도
/// 보이는 카드는 같고, 문장이 무엇을 앞세우는지만 달라진다.
/// (iOS의 encode/decode 저장 헬퍼는 스토어 계층과 함께 이식한다 — M5)
enum class RunPurpose(val storageValue: String) {
    HEALTH("health"), WEIGHT("weight"), RECORD("record"), RACE("race"), MOOD("mood");

    val label: String
        get() = when (this) {
            HEALTH -> "건강 관리"
            WEIGHT -> "체중 관리"
            RECORD -> "기록 향상"
            RACE -> "대회 준비"
            MOOD -> "기분 전환"
        }
}

/// 목표 레이스 종목 — 훈련 가이드·목표 기록 프리셋의 기준.
/// (iOS의 objectParticle(조사) 같은 화면 전용 속성은 화면 계층과 함께 이식한다 — M5)
enum class RaceDistance(val storageValue: String) {
    FIVE_K("fiveK"), TEN_K("tenK"), HALF("half"), FULL("full");

    val label: String
        get() = when (this) {
            FIVE_K -> "5K"
            TEN_K -> "10K"
            HALF -> "하프"
            FULL -> "풀코스"
        }

    /// 공식 거리(km) — Riegel 예측·목표 페이스 환산에 쓴다
    val km: Double
        get() = when (this) {
            FIVE_K -> 5.0
            TEN_K -> 10.0
            HALF -> 21.0975
            FULL -> 42.195
        }

    companion object {
        fun fromStorage(value: String): RaceDistance? = entries.find { it.storageValue == value }
    }
}
