package com.jkpark.runwrap.engine

import kotlinx.serialization.Serializable

/// 사용자 프로필 — 러너 레벨·러닝 목적 (기획서 v0.7 §3, §9)
///
/// 값은 UserDefaults(@AppStorage)에 rawValue로 저장한다. 엔진은 프로필을 모른다 —
/// 카드 포함 여부는 `ReportGate`가 정하고, 문장 난이도만 level 값 입력으로 전달한다.
/// (Android: 케이스 이름을 iOS rawValue와 같게 두어 `rawValue`/`fromRawValue`가 이름 그대로다)

/// 러너 레벨 3단계 (기획서 §3) — 온보딩 설문 결과로 판정하고, 실기록으로 승급만 한다.
///
/// 노출 라벨은 런미새 위트 톤(§4.11)의 별칭이다. 저장값(rawValue)은 영문 그대로 둬서
/// 라벨 문구를 바꿔도 기존 사용자의 저장값이 깨지지 않게 한다.
enum class RunnerLevel {
    beginner, intermediate, advanced;

    val rawValue: String get() = name

    val label: String
        get() = when (this) {
            beginner -> "런린이"
            intermediate -> "런잘알"
            advanced -> "런친놈"
        }

    /// 레벨 비교용 서열 — 승급 판정(`LevelEngine.promotionCandidate`)에서 쓴다.
    /// CaseIterable 순서에 기대지 않도록 명시값으로 둔다.
    val rank: Int
        get() = when (this) {
            beginner -> 0
            intermediate -> 1
            advanced -> 2
        }

    companion object {
        fun fromRawValue(rawValue: String): RunnerLevel? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// 러닝 목적 (기획서 §2 Q9) — 복수 선택. 리포트 문장의 강조점에 쓴다.
///
/// 목적은 카드를 켜고 끄지 않는다 (그건 레벨 게이트의 몫) — 어떤 목적을 골라도
/// 보이는 카드는 같고, 문장이 무엇을 앞세우는지만 달라진다.
@Serializable
enum class RunPurpose {
    health, weight, record, race, mood;

    val rawValue: String get() = name

    val label: String
        get() = when (this) {
            health -> "건강 관리"
            weight -> "체중 관리"
            record -> "기록 향상"
            race -> "대회 준비"
            mood -> "기분 전환"
        }

    companion object {
        fun fromRawValue(rawValue: String): RunPurpose? = entries.firstOrNull { it.rawValue == rawValue }

        /// 복수 선택이라 @AppStorage에 넣으려면 한 문자열로 접어야 한다.
        /// 쉼표 구분 — rawValue에 쉼표가 없으므로 안전하다.
        fun encode(purposes: List<RunPurpose>): String = purposes.joinToString(",") { it.rawValue }

        // Swift split은 빈 조각을 버린다 — 빈 조각은 어차피 fromRawValue가 nil이라 결과가 같다
        fun decode(raw: String): List<RunPurpose> = raw.split(',').mapNotNull { fromRawValue(it) }
    }
}

/// 목표 레이스 종목 — 훈련 가이드·목표 기록 프리셋의 기준
@Serializable
enum class RaceDistance {
    fiveK, tenK, half, full;

    val rawValue: String get() = name

    val label: String
        get() = when (this) {
            fiveK -> "5K"
            tenK -> "10K"
            half -> "하프"
            full -> "풀코스"
        }

    /// label 뒤에 붙는 목적격 조사 — "하프를", "5K를"처럼 문장을 자연스럽게 잇는다.
    /// 종결 음절의 받침 유무로 갈린다: 5K·10K는 "케이"(받침 없음), 하프도 받침 없음,
    /// 풀코스는 "스"로 끝나 받침이 없다 — 현재 4종은 모두 "를"이지만,
    /// 종목이 늘어날 때 호출부가 아니라 이곳만 고치도록 케이스로 남겨 둔다.
    val objectParticle: String
        get() = when (this) {
            fiveK, tenK, half, full -> "를"
        }

    /// 공식 거리(km) — Riegel 예측·목표 페이스 환산에 쓴다
    val km: Double
        get() = when (this) {
            fiveK -> 5.0
            tenK -> 10.0
            half -> 21.0975
            full -> 42.195
        }

    companion object {
        fun fromRawValue(rawValue: String): RaceDistance? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// @AppStorage 키 모음 — 화면마다 문자열을 다시 치지 않도록 한곳에 둔다.
///
/// v0.7에서 레벨 체계가 바뀌어 `levelV2`를 새로 쓴다. v1 키(`profile.level`·`profile.goal`)는
/// 더 쓰지 않지만, `profile.didSet`은 RootView가 "기존 사용자 재온보딩" 판정에 한 번 읽고 지운다.
object ProfileKey {
    /// 러너 레벨 (RunnerLevel rawValue) — 빈 문자열이면 온보딩 미완료
    const val levelV2 = "profile.levelV2"
    /// 러닝 목적 복수 선택 (쉼표 구분 rawValue)
    const val purposes = "profile.purposes"
    /// 주간 러닝 목표 횟수 — 성장 XP의 주간 보너스 분모이자 홈 목표 칩의 기준
    const val weeklyGoal = "profile.weeklyGoal"
    /// 주간 목표 변경 이력 (`[WeeklyGoalChange]` JSON Data) — 없으면 변경 없음.
    /// 바뀐 목표는 다음 주부터 보너스 판정에 적용한다 (이슈 #108, #116). 읽기·쓰기는 `WeeklyGoalChangeLog`로만 한다
    const val weeklyGoalChanges = "profile.weeklyGoalChanges"
    /// 온보딩 완료 시각 (timeIntervalSince1970)
    const val onboardedAt = "profile.onboardedAt"
    /// 승급 제안을 거절한 시각 — 4주간 다시 묻지 않는다 (§3)
    const val promotionDeclinedAt = "profile.promotionDeclinedAt"
    /// 목표 레이스 종목 (RaceDistance rawValue) — 빈 문자열이면 미설정 → 훈련 가이드 카드 미노출
    /// v1과 같은 키를 그대로 쓴다 — 재온보딩해도 기존 목표가 보존된다
    const val raceGoal = "profile.raceGoal"
    /// 목표 기록 (초) — 0이면 미입력으로 취급해 예측만 보여준다
    const val raceGoalSec = "profile.raceGoalSec"
    /// 대회 날짜 (timeIntervalSince1970) — 0이면 미설정. 있으면 훈련 가이드가
    /// D-day 주기화(기초→강화→피크→테이퍼)로 주간 처방을 조절한다
    const val raceDate = "profile.raceDate"
    /// 최대 심박 수동 입력(bpm) — 0이면 미설정 → TrainingGuideEngine 추정(관찰 최대·Tanaka·190) 사용 (이슈 #56)
    const val hrMaxManual = "profile.hrMaxManual"
    /// 안정 심박 수동 입력(bpm) — 0이면 미설정 → HealthKit 최근값 사용 (이슈 #56)
    const val restingHRManual = "profile.restingHRManual"
    /// 심박 존 방식 (HeartRateZoneMethod rawValue) — 빈 문자열이면 %HRmax (이슈 #56)
    const val hrZoneMethod = "profile.hrZoneMethod"
}

/// 주간 목표 변경 이력 읽기·쓰기 — 저장 형식과 옛 키 이관을 한곳에 둔다 (이슈 #116).
///
/// #108은 (시각, 이전 목표) 1건을 두 키에 따로 저장했다. 첫 읽기 때 그 두 키를 1건짜리 이력으로
/// 옮기고 지운다 — 홈·설정·재진단·백업 중 어디서 먼저 읽어도 같은 결과가 되도록 `load`가 처리한다.
/// (Android: iOS가 UserDefaults에 넣던 JSON Data는 KeyValueStore에 JSON 문자열로 넣는다)
object WeeklyGoalChangeLog {
    /// #108의 옛 키 — 마지막 변경 시각(timeIntervalSince1970, 0이면 없음)과 변경 직전 목표. 이관용으로만 읽는다
    const val legacyChangedAtKey = "profile.weeklyGoalChangedAt"
    const val legacyBeforeKey = "profile.weeklyGoalBefore"

    /// 저장된 이력(시각 오름차순). 새 키가 없고 옛 두 키가 있으면 1건짜리 이력으로 이관해 돌려준다
    fun load(defaults: KeyValueStore): List<WeeklyGoalChange> {
        defaults.string(ProfileKey.weeklyGoalChanges)?.let { return decode(it) }
        val legacyAt = defaults.double(legacyChangedAtKey)
        if (!(legacyAt > 0)) return emptyList()
        val history = listOf(WeeklyGoalChange(at = instantSince1970(legacyAt),
                                              before = defaults.int(legacyBeforeKey)))
        save(history, defaults)
        return history
    }

    /// 이력을 저장한다 — 비어 있으면 키를 지운다. 옛 키는 항상 지워 이관이 되살아나지 않게 한다
    fun save(history: List<WeeklyGoalChange>, defaults: KeyValueStore) {
        if (history.isEmpty()) {
            defaults.remove(ProfileKey.weeklyGoalChanges)
        } else {
            defaults.set(ProfileKey.weeklyGoalChanges, EngineJson.encodeToString(history))
        }
        defaults.remove(legacyChangedAtKey)
        defaults.remove(legacyBeforeKey)
    }

    /// 저장 Data → 이력. 깨진 값은 변경 없음으로 읽는다
    fun decode(data: String?): List<WeeklyGoalChange> {
        if (data == null) return emptyList()
        return runCatching { EngineJson.decodeFromString<List<WeeklyGoalChange>>(data) }.getOrDefault(emptyList())
    }
}

/// 성장 시스템 저장 키 (기획서 §5).
///
/// XP 원장은 저장하지 않는다 — 사이클 시작 이후 HealthKit 이력에서 매번 재계산한다.
/// 저장할 값은 사이클 시작 시각과 이번 사이클 최고 단계뿐이다.
object GrowthKey {
    /// 현재 성장 사이클 시작 시각 (timeIntervalSince1970). 첫 사이클 = 온보딩 시각
    const val cycleStartedAt = "growth.cycleStartedAt"
    /// 이번 사이클에서 도달한 최고 단계 — "성장은 되돌리지 않는다"의 구현 장치
    const val maxStage = "growth.maxStage"
    /// 사이클 식별자 (UUID 문자열) — CloudKit 스냅샷 병합에서 같은 사이클인지 판정한다 (이슈 #29).
    /// 온보딩·사이클 전환 때 새로 발급하고, 없으면 백업 시점에 최초 1회 만든다
    const val cycleID = "growth.cycleID"
    /// 이번 사이클의 목표 종목 (RaceDistance rawValue, 빈 문자열 = 목표 없음) — 사이클 시작 때 고정해
    /// 세러모니의 새 종류 판정에 쓴다 (기획서 v0.7 §5 "새 종류는 그 사이클의 목표 수준이 정한다", 이슈 #110).
    /// 설정·재진단으로 바뀌는 `ProfileKey.raceGoal`과 분리해, 성조 직전 목표 변경으로 종을 바꾸지 못하게 한다.
    /// 키가 없으면(도입 전 사용자) 홈이 현재 raceGoal을 한 번 복사해 저장한다
    const val cycleGoal = "growth.cycleGoal"
    /// 이번 사이클의 목표 기록 (초) — `cycleGoal`과 함께 고정한다. 0이면 기록 미입력
    const val cycleGoalSec = "growth.cycleGoalSec"
    /// 성조 세러모니에서 "조금 더 키우기"로 수집을 미룬 종 (BirdSpecies rawValue, 빈 문자열 = 미룬 적 없음).
    /// 미룬 뒤 기록으로 종이 오르면 값이 달라져 세러모니가 다시 뜬다. 기기별 값이라 백업하지 않는다
    const val deferredSpecies = "growth.deferredSpecies"
    /// 백업 대상 값이 로컬에서 마지막으로 바뀐 시각 (timeIntervalSince1970) — 스냅샷 `updatedAt`의 원천 (이슈 #130).
    /// 업로드 시각을 쓰면 오래된 백업을 복원한 기기가 뒤늦게 올릴 때 서버의 더 새 진행도를 이긴다.
    /// 쓰기는 `ProgressSnapshot.markLocalChanged`로만 한다. 서버 본을 적용할 때는 그 본의 `updatedAt`을 기록한다
    const val localChangedAt = "progress.localChangedAt"
}

/// 결산 리캡 홈 카드 닫힘 기록 (이슈 #167) — 열어 보거나 X를 누른 기간을 남겨 다시 띄우지 않는다.
/// 기기 로컬 표시 상태라 백업(ProgressSnapshot) 대상이 아니다
object RecapKey {
    /// 마지막으로 닫은 월간 결산 ("yyyy-MM", RecapEngine.dismissKey) — 빈 문자열이면 없음
    const val dismissedMonth = "recap.dismissedMonth"
    /// 마지막으로 닫은 연간 결산 ("yyyy") — 빈 문자열이면 없음
    const val dismissedYear = "recap.dismissedYear"
}

/// 대회 탭 즐겨찾기·목표 대회 (이슈 #172) — 기기 로컬 표시 상태라 백업(ProgressSnapshot) 대상이 아니다
object RaceKey {
    /// 즐겨찾기한 대회 번호 (`[Int]` JSON 문자열) — 읽기·쓰기는 `RaceFavorites`로만 한다
    const val favorites = "race.favorites"
    /// 목표 대회 번호 (Race.id) — 0이면 미지정. 홈 목표 대회 카드의 기준
    const val targetID = "race.targetId"
}

/// 즐겨찾기 목록 직렬화 (이슈 #172) — @AppStorage에 배열을 둘 수 없어 JSON 문자열로 접는다
object RaceFavorites {
    /// 저장 문자열 → 대회 번호. 비었거나 깨진 값은 즐겨찾기 없음으로 읽는다
    fun decode(s: String): List<Int> =
        runCatching { EngineJson.decodeFromString<List<Int>>(s) }.getOrDefault(emptyList())

    fun encode(ids: List<Int>): String = EngineJson.encodeToString(ids)

    /// 있으면 빼고 없으면 뒤에 붙인다 — 별 버튼 한 번의 동작
    fun toggled(ids: List<Int>, id: Int): List<Int> = if (id in ids) ids.filter { it != id } else ids + id
}
