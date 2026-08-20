package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.Format
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max

/// 도감 새 종류 (기획서 §5 도감) — 사이클의 목표 종목·기록이 어떤 새인지 결정한다
enum class BirdSpecies(val storageValue: String) {
    SPARROW("sparrow"), SWALLOW("swallow"), FALCON("falcon"),
    GOOSE("goose"), CRANE("crane"), SWAN("swan");

    val label: String
        get() = when (this) {
            SPARROW -> "참새"
            SWALLOW -> "제비"
            FALCON -> "매"
            GOOSE -> "기러기"
            CRANE -> "두루미"
            SWAN -> "백조"
        }

    /// 이 새를 얻는 목표 조건 안내 — 도감 카드·다음 목표 추천 문구
    val goalHint: String
        get() = when (this) {
            SPARROW -> "목표 없이 완주 습관"
            SWALLOW -> "5K · 10K"
            FALCON -> "하프"
            GOOSE -> "풀코스 완주"
            CRANE -> "풀코스 sub-4"
            SWAN -> "풀코스 서브3"
        }

    companion object {
        fun fromStorage(value: String): BirdSpecies? = entries.find { it.storageValue == value }
    }
}

/// 완성한 사이클 한 장 — 도감에 영구히 남는 기록.
/// 영속화(CollectionCache)는 스토어 계층 몫이다 (M5)
data class CollectedBird(
    val id: UUID = UUID.randomUUID(),
    val species: BirdSpecies,
    val goalLabel: String,     // "하프", "풀코스 3:59:00" 같은 그 사이클의 목표 표기
    val collectedAt: Instant,
    val cycleDays: Int,        // 사이클 시작부터 완성까지 걸린 일수
)

/// 도감 엔진 (기획서 §5) — 목표 종목·기록 → 새 종류 배정, 사이클 완성 판정,
/// 다음 목표 추천을 결정론적으로 계산한다. iOS `CollectionEngine.swift` 이식.
object CollectionEngine {
    /// 풀코스 기록 경계 (초) — 새 배정·목표 조이기 기준
    private const val SUB3 = 3 * 3_600
    private const val SUB4 = 4 * 3_600

    /// 다음 사이클 목표 추천 한 건
    data class GoalRecommendation(val distance: RaceDistance, val seconds: Int)

    /// 목표 종목·기록으로 이번 사이클의 새를 정한다.
    /// 목표 없음 → 참새, 5K·10K → 제비, 하프 → 매,
    /// 풀코스는 기록 없으면 기러기 · sub-4 두루미 · sub-3 백조
    fun species(distance: RaceDistance?, goalSeconds: Int): BirdSpecies {
        if (distance == null) return BirdSpecies.SPARROW
        return when (distance) {
            RaceDistance.FIVE_K, RaceDistance.TEN_K -> BirdSpecies.SWALLOW
            RaceDistance.HALF -> BirdSpecies.FALCON
            RaceDistance.FULL -> when {
                goalSeconds <= 0 -> BirdSpecies.GOOSE
                goalSeconds < SUB3 -> BirdSpecies.SWAN
                goalSeconds < SUB4 -> BirdSpecies.CRANE
                else -> BirdSpecies.GOOSE
            }
        }
    }

    /// 도감 카드에 적을 목표 표기 — 목표 없으면 참새 힌트, 기록 없으면 종목만
    fun goalLabel(distance: RaceDistance?, goalSeconds: Int): String {
        if (distance == null) return BirdSpecies.SPARROW.goalHint
        if (goalSeconds <= 0) return distance.label
        return "${distance.label} ${Format.duration(goalSeconds.toDouble())}"
    }

    /// 성체(최종 단계) 도달 = 사이클 완성 조건
    fun hasReachedAdult(stage: GrowthStage): Boolean = stage.next == null

    /// 사이클 완성 — 도감 카드 한 장을 만든다. 경과일은 자정 경계 기준
    fun collect(
        distance: RaceDistance?,
        goalSeconds: Int,
        cycleStartedAt: Instant,
        now: Instant,
        id: UUID = UUID.randomUUID(),
    ): CollectedBird {
        val days = ChronoUnit.DAYS.between(dayOf(cycleStartedAt), dayOf(now)).toInt()
        return CollectedBird(
            id = id,
            species = species(distance, goalSeconds),
            goalLabel = goalLabel(distance, goalSeconds),
            collectedAt = now,
            cycleDays = max(0, days),
        )
    }

    /// 다음 사이클 목표 추천 — 5K → 10K → 하프 → 풀 → sub-4 → 30분씩 조이기.
    /// 서브3 아래로는 더 조일 목표를 추천하지 않는다 (null)
    fun recommendedGoal(distance: RaceDistance?, goalSeconds: Int): GoalRecommendation? {
        if (distance == null) return GoalRecommendation(RaceDistance.FIVE_K, 0)
        return when (distance) {
            RaceDistance.FIVE_K -> GoalRecommendation(RaceDistance.TEN_K, 0)
            RaceDistance.TEN_K -> GoalRecommendation(RaceDistance.HALF, 0)
            RaceDistance.HALF -> GoalRecommendation(RaceDistance.FULL, 0)
            RaceDistance.FULL -> {
                if (goalSeconds <= 0) return GoalRecommendation(RaceDistance.FULL, SUB4)
                val tightened = goalSeconds - 30 * 60
                if (tightened < SUB3) null else GoalRecommendation(RaceDistance.FULL, tightened)
            }
        }
    }
}
