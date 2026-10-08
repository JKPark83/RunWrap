package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlinx.serialization.Serializable

/// 도감에 수집되는 새 종 — 그 사이클에 **실제로 달린 기록**이 종을 정한다 (2026-10-05 결정).
///
/// 처음에는 사이클 목표가 종을 정했지만(기획서 §5 초안), 목표는 적기만 하면 되는 값이라
/// "서브3"를 입력하는 것만으로 백조가 됐다. 도감이 러너의 이력이 되려면 증거가 필요하다.
/// 경계값은 `CollectionEngine` 한 곳에만 두고 화면·저장 어디에서도 다시 판정하지 않는다.
@Serializable
enum class BirdSpecies {
    sparrow,    // 참새
    swallow,    // 제비
    falcon,     // 매
    goose,      // 기러기
    crane,      // 두루미
    swan;       // 백조

    val rawValue: String get() = name

    /// 도감 이력 시트(`.sheet(item:)`)의 식별자 — 종 자체가 곧 식별자다
    val id: BirdSpecies get() = this

    val label: String
        get() = when (this) {
            sparrow -> "참새"
            swallow -> "제비"
            falcon -> "매"
            goose -> "기러기"
            crane -> "두루미"
            swan -> "백조"
        }

    /// 이 종이 되려면 사이클 안에 무엇을 달려야 하는지 한 줄로 설명한다 (미수집 칸의 힌트로도 쓴다)
    val goalHint: String
        get() = when (this) {
            sparrow -> "5km 미만으로 꾸준히"
            swallow -> "5km 이상 한 번"
            falcon -> "하프 완주"
            goose -> "풀코스 완주"
            crane -> "풀코스 4시간 안"
            swan -> "풀코스 서브3"
        }

    /// 한 칸 위 종 — 수집을 미루면 무엇이 될 수 있는지 안내하는 데 쓴다. 백조는 nil
    val next: BirdSpecies?
        get() {
            val all = entries
            val index = all.indexOf(this)
            if (index < 0 || index + 1 >= all.size) return null
            return all[index + 1]
        }

    companion object {
        fun fromRawValue(rawValue: String): BirdSpecies? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// 도감에 수록된 새 한 마리 — 성조 이미지 + 종을 정한 기록 + 수집일 (기획서 §5)
/// (Android: id는 UUID 대신 대문자 UUID 문자열이고, 기본값 없이 호출부가 주입한다)
@Serializable
data class CollectedBird(
    val id: String,
    val species: BirdSpecies,
    /// 종을 정한 기록을 사람이 읽는 문장으로 굳혀 둔다 ("하프 1:52:10").
    /// 기록 기준으로 바뀌기 전(~2026-10)에 수집한 새는 당시 목표 표기가 들어 있다 —
    /// 저장 파일 호환을 위해 필드 이름은 그대로 둔다.
    val goalLabel: String,
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val collectedAt: Instant,
    /// 이 사이클이 걸린 일수 — 도감에서 "27일 만에" 같은 이력 표기에 쓴다
    val cycleDays: Int,
)

/// 성조 도달 → 수집 → 새 사이클 시작을 계산하는 순수 로직 (기획서 §5).
///
/// 엔진 계층 규칙 그대로 Foundation만 import하고 `now`를 주입받는다.
/// 저장·화면 전환은 `CollectionStore`와 화면이 맡고, 여기서는 **무엇을 수집하고
/// 다음 사이클을 어떤 값으로 시작할지**만 정한다.
object CollectionEngine {
    /// 서브3 기준 — 풀코스 3시간. "서브3 = 백조"가 §5의 확정 축이다
    private const val sub3Seconds = 3 * 3_600
    /// sub-4 기준 — 풀코스 4시간. 두루미와 기러기의 경계
    private const val sub4Seconds = 4 * 3_600

    /// `earned`의 결과 (Swift 튜플 `(species:label:)`)
    data class Earned(val species: BirdSpecies, val label: String)

    /// `recommendedGoal`의 결과 (Swift 튜플 `(distance:seconds:)`)
    data class RecommendedGoal(val distance: RaceDistance, val seconds: Int)

    /// `initialNextGoal`의 결과 (Swift 튜플 `(distance:seconds:)`)
    data class NextGoal(val distance: RaceDistance?, val seconds: Int)

    /// 사이클에 실제로 달린 기록 → 새 종과 그 근거 표기.
    ///
    /// 거리 기준은 PB 판정(`BestEffortEngine`)과 같은 공식 거리를 오차 허용 없이 쓴다 —
    /// 같은 러닝이 PB에는 하프로 잡히는데 도감에서는 아닌 일이 없게 한다.
    /// 풀코스 기록은 그 러닝의 평균 페이스를 42.195km로 환산한다 (43km를 달린 날이 손해 보지 않게).
    ///
    /// - Parameters:
    ///   - runs: 러닝 전체. `since` 이전과 거리 없는 기록은 세지 않는다
    ///   - since: 사이클 시작 시각
    fun earned(runs: List<RunSummary>, since: Instant): Earned {
        val distances = runs.mapNotNull { run ->
            val meters = run.distanceMeters
            if (run.start < since || meters == null || !(meters > 0)) return@mapNotNull null
            meters to run.durationSec
        }
        /// 공식 거리 이상을 달린 러닝 중 가장 빠른 환산 기록(초)
        fun best(official: Double): Double? =
            distances.filter { it.first >= official }.map { it.second * official / it.first }.minOrNull()

        val full = best(RaceDistance.full.km * 1_000)
        if (full != null) {
            val species = if (full < sub3Seconds.toDouble()) BirdSpecies.swan
                else if (full < sub4Seconds.toDouble()) BirdSpecies.crane else BirdSpecies.goose
            return Earned(species, "풀코스 ${Format.duration(full)}")
        }
        val half = best(RaceDistance.half.km * 1_000)
        if (half != null) {
            return Earned(BirdSpecies.falcon, "하프 ${Format.duration(half)}")
        }
        val longest = distances.map { it.first }.maxOrNull()
        if (longest != null && longest >= 5_000) {
            return Earned(BirdSpecies.swallow, "최장 ${Format.km(longest / 1_000)}km")
        }
        return Earned(BirdSpecies.sparrow, "꾸준히 달린 습관")
    }

    /// 목표 → 그 목표를 **달성하면** 될 새 종 (세러모니의 다음 목표 미리보기용).
    /// 실제 수집 종은 `earned(runs:since:)`가 기록으로 정한다.
    ///
    /// - Parameters:
    ///   - distance: 목표 종목. nil이면 목표 없음 → 참새
    ///   - goalSeconds: 목표 기록(초). 0 이하는 미입력으로 본다 — 풀코스에서만 종을 가른다
    fun species(distance: RaceDistance?, goalSeconds: Int): BirdSpecies {
        if (distance == null) return BirdSpecies.sparrow
        return when (distance) {
            RaceDistance.fiveK, RaceDistance.tenK -> BirdSpecies.swallow
            RaceDistance.half -> BirdSpecies.falcon
            RaceDistance.full -> {
                // 목표 기록 미입력이면 "완주"로 본다 — 기러기.
                // 기록을 적어 낸 경우에만 sub-4 / 서브3로 승격한다
                if (!(goalSeconds > 0)) return BirdSpecies.goose
                if (goalSeconds < sub3Seconds) return BirdSpecies.swan
                if (goalSeconds < sub4Seconds) return BirdSpecies.crane
                BirdSpecies.goose
            }
        }
    }

    /// 목표를 한 줄 문장으로 — 종목 + (있으면) 목표 기록. 세러모니의 목표 후보 설명에 쓴다.
    /// 예: "풀코스 3:30:00" · "하프" · "목표 없이 완주 습관"
    fun goalLabel(distance: RaceDistance?, goalSeconds: Int): String {
        if (distance == null) return "목표 없이 완주 습관"
        if (!(goalSeconds > 0)) return distance.label
        return "${distance.label} ${Format.duration(goalSeconds.toDouble())}"
    }

    /// 성조에 도달했는지 — 수집 세러모니를 띄울 조건.
    ///
    /// 미노출 가드와 같은 결의 판정이다: 단계가 실제로 `flying`일 때만 true다.
    fun hasReachedAdult(stage: GrowthStage): Boolean = stage.next == null

    /// 수집할 새를 만든다. 종은 사이클 기록으로 정하고, 소요 일수를 함께 굳힌다.
    /// (Android: iOS의 `Calendar(identifier: .iso8601)` + `.current` 대신 zone을 주입받고, id는 호출부가 만든다)
    fun collect(runs: List<RunSummary>,
                cycleStartedAt: Instant, now: Instant, zone: ZoneId,
                id: String): CollectedBird {
        val days = ChronoUnit.DAYS.between(cycleStartedAt.atZone(zone).toLocalDate(),
                                           now.atZone(zone).toLocalDate()).toInt()
        val earned = earned(runs, since = cycleStartedAt)
        return CollectedBird(id = id,
                             species = earned.species,
                             goalLabel = earned.label,
                             collectedAt = now,
                             cycleDays = max(0, days))
    }

    /// 다음 목표 추천 — 직전 목표를 한 칸 올린다 (기획서 §5 "직전 목표·최근 기록 기반").
    ///
    /// 풀코스에 이미 도달한 뒤에는 종목을 더 올릴 수 없으므로 **기록 단축**으로 방향을 튼다.
    /// 추천일 뿐이라 화면에서 직접 설정으로 바꿀 수 있다.
    ///
    /// - Returns: 추천 종목과 추천 목표 기록(초, 0이면 기록 목표 없이 완주). 더 올릴 곳이
    ///   없으면 nil — 그 경우 화면은 추천 대신 직접 설정만 안내한다
    fun recommendedGoal(after: RaceDistance?, goalSeconds: Int): RecommendedGoal? =
        when (after) {
            null -> RecommendedGoal(RaceDistance.fiveK, 0)
            RaceDistance.fiveK -> RecommendedGoal(RaceDistance.tenK, 0)
            RaceDistance.tenK -> RecommendedGoal(RaceDistance.half, 0)
            RaceDistance.half -> RecommendedGoal(RaceDistance.full, 0)
            RaceDistance.full -> run {
                // 종목은 끝 — 기록을 당긴다. 미입력이면 우선 sub-4를 제안하고,
                // 이미 목표가 있으면 30분씩 당기되 서브3 바로 아래에서 멈춘다.
                // species()는 경계를 배타(<)로 가르므로 정확히 4:00:00·3:00:00은 한 칸 위 종이 아니다 —
                // 추천값을 경계 1분 아래(3:59:00·2:59:00)로 둬야 추천대로 달성했을 때 종이 오른다.
                if (!(goalSeconds > 0)) return@run RecommendedGoal(RaceDistance.full, sub4Seconds - 60)
                // 이미 서브3(백조) 목표면 더 올릴 종이 없다
                if (!(goalSeconds >= sub3Seconds)) return@run null
                val tightened = goalSeconds - 30 * 60
                // 서브3 경계 이하로 당겨지면 nil 대신 서브3 바로 아래로 맞춘다
                if (!(tightened > sub3Seconds)) return@run RecommendedGoal(RaceDistance.full, sub3Seconds - 60)
                RecommendedGoal(RaceDistance.full, tightened)
            }
        }

    /// 세러모니 "다음 목표"의 초기 선택 — 이번 사이클 목표(`cycleGoal`) 기준 추천을 깐다 (이슈 #127).
    ///
    /// 사이클 시작 때 고정한 목표(이슈 #110)에서 한 칸 올려 추천한다.
    /// 다만 사용자가 사이클 도중 설정에서 이미 더 높은 목표를 골라 뒀다면 그 의도를 되묻지 않고
    /// 그대로 초기 선택으로 둔다. "더 높다"는 더 먼 종목, 또는 같은 종목에 더 빠른 기록이다.
    /// 종목 순서는 `recommendedGoal`의 사다리(없음 → 5K → 10K → 하프 → 풀)와 같다 — 거리(km)로 비교한다.
    ///
    /// - Returns: 초기 선택 종목과 목표 기록(초, 0이면 기록 목표 없이 완주).
    ///   추천할 곳이 없으면(이미 서브3) 사이클 목표를 그대로 유지한다
    fun initialNextGoal(cycleGoal: RaceDistance?, cycleGoalSeconds: Int,
                        currentGoal: RaceDistance?,
                        currentSeconds: Int): NextGoal {
        val cycleKm = cycleGoal?.km ?: 0.0
        val currentKm = currentGoal?.km ?: 0.0
        val fasterOnSameDistance = currentGoal == cycleGoal && currentSeconds > 0
            && (cycleGoalSeconds == 0 || currentSeconds < cycleGoalSeconds)
        if (currentKm > cycleKm || fasterOnSameDistance) {
            return NextGoal(currentGoal, currentSeconds)
        }
        val recommended = recommendedGoal(after = cycleGoal, goalSeconds = cycleGoalSeconds)
            // 더 올릴 곳이 없다 — 직전 목표를 그대로 유지한 채 시작한다
            ?: return NextGoal(cycleGoal, cycleGoalSeconds)
        return NextGoal(recommended.distance, recommended.seconds)
    }
}
