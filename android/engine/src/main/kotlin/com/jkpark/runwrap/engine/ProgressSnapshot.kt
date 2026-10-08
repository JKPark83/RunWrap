package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject

/// 진행도 스냅샷 — 앱 삭제·재설치 후 성장 상태를 복원하기 위한 단일 원본 (이슈 #29).
///
/// XP 숫자는 넣지 않는다 — 복원된 `cycleStartedAt`과 HealthKit 이력으로
/// `GrowthEngine`이 결정론적으로 재계산한다(기획서 §5의 "XP 원장을 저장하지 않는다").
/// 여기 담는 것은 재계산이 불가능한 값들뿐이다: 프로필·사이클 경계·최고 단계·도감·직접 입력한 대회 기록.
/// 저장처는 사용자의 CloudKit private database(`ProgressBackupStore`)이고,
/// 이 파일은 Foundation만 알아 순수 로직으로 테스트한다.
/// (Android: 백업은 Auto Backup이라 `ProgressMergeEngine`(CloudKit 병합)은 옮기지 않는다 — 모델과 로컬 읽기/쓰기만 둔다.
/// UUID는 대문자 문자열이다)
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ProgressSnapshotSerializer::class)
data class ProgressSnapshot(
    val schemaVersion: Int,
    /// 업로드마다 1씩 오르는 단조 증가 값 — 충돌 감지·병합의 기준
    val revision: Int,
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val updatedAt: Instant,
    /// 성장 사이클 식별자 — 같은 사이클끼리만 "maxStage는 내려가지 않는다" 병합을 적용한다
    val cycleID: String,

    val levelRaw: String,
    val purposesRaw: String,
    val weeklyGoal: Int,
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val onboardedAt: Instant,

    @Serializable(with = ReferenceDateInstantSerializer::class)
    val cycleStartedAt: Instant,
    val maxStage: Int,

    val raceGoalRaw: String,
    val raceGoalSeconds: Int,
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val raceDate: Instant? = null,
    val collectedBirds: List<CollectedBird>,

    /// 심박 기준 (이슈 #56) — nil = 미설정. 이 필드가 없던 옛 스냅샷도 decodeIfPresent로
    /// 디코드되며 미설정으로 읽힌다. 옵셔널 var라 memberwise init 기본값이 nil이다
    val hrMaxManual: Int? = null,
    val restingHRManual: Int? = null,
    val hrZoneMethodRaw: String? = null,

    /// 주간 목표 변경 이력 (이슈 #108, #116) — nil = 변경 없음. 복원 후에도 "바뀐 목표는 다음 주부터"가
    /// 유지되도록 함께 백업한다. #108의 옛 두 필드(weeklyGoalChangedAt·weeklyGoalBefore)만 있는 스냅샷은
    /// 디코드 때 1건짜리 이력으로 흡수하고, 인코드는 이 필드만 쓴다
    val weeklyGoalChanges: List<WeeklyGoalChange>? = null,
    /// 사이클 시작 때 고정한 목표 (이슈 #110) — 새 종류 판정용. nil = 이 필드가 없던 옛 스냅샷이거나
    /// 로컬에 아직 사이클 목표 키가 없는 설치. 복원하면 raceGoal로 대체한다. #56 심박 필드와 같은 방식
    val cycleGoalRaw: String? = null,
    val cycleGoalSeconds: Int? = null,
    /// 직접 입력한 대회 기록 (이슈 #118) — HealthKit 건강 데이터가 아닌 사용자 입력값이라 함께 백업한다.
    /// nil = 이 필드가 없던 옛 스냅샷. readLocal은 항상 배열(빈 배열 포함)로 채운다. #56 심박 필드와 같은 방식
    val raceRecords: List<RaceRecord>? = null,
    /// 지운 대회 기록의 id(삭제 표식, 이슈 #118) — 합집합 병합은 삭제를 전파하지 못해 한 기기에서 지운
    /// 기록이 서버 본에서 되살아난다. 지운 id를 함께 백업해 `unionRaceRecords`가 걸러 낸다. nil = 옛 스냅샷
    val deletedRaceRecordIDs: List<String>? = null,
) {
    /// 내용이 같은지 — 동기화 메타(revision·updatedAt)만 다른 스냅샷은 다시 올릴 필요가 없다
    fun hasSameContent(other: ProgressSnapshot): Boolean {
        // Swift `.distantPast` 대신 고정 시각 하나로 양쪽을 맞춘다 — 같은 값이기만 하면 된다
        val lhs = copy(revision = 0, updatedAt = Instant.MIN)
        val rhs = other.copy(revision = 0, updatedAt = Instant.MIN)
        return lhs == rhs
    }

    // MARK: - 로컬 상태 읽기/쓰기 (UserDefaults + 도감·대회 기록 배열)

    /// 스냅샷을 로컬 저장값에 적용한다 — 신규 설치 복원 경로.
    /// 도감·대회 기록 파일 쓰기는 호출부(스토어) 몫이다: 이 함수는 UserDefaults만 알아 테스트가 쉽다.
    fun apply(defaults: KeyValueStore) {
        defaults.set(ProfileKey.levelV2, levelRaw)
        defaults.set(ProfileKey.purposes, purposesRaw)
        defaults.set(ProfileKey.weeklyGoal, weeklyGoal)
        defaults.set(ProfileKey.onboardedAt, onboardedAt.timeIntervalSince1970)
        defaults.set(GrowthKey.cycleStartedAt, cycleStartedAt.timeIntervalSince1970)
        defaults.set(GrowthKey.maxStage, maxStage)
        defaults.set(GrowthKey.cycleID, cycleID)
        defaults.set(ProfileKey.raceGoal, raceGoalRaw)
        defaults.set(ProfileKey.raceGoalSec, raceGoalSeconds)
        // 사이클 목표 (이슈 #110) — 옛 스냅샷(nil)은 당시 목표를 사이클 목표로 본다
        defaults.set(GrowthKey.cycleGoal, cycleGoalRaw ?: raceGoalRaw)
        defaults.set(GrowthKey.cycleGoalSec, cycleGoalSeconds ?: raceGoalSeconds)
        defaults.set(ProfileKey.raceDate, raceDate?.timeIntervalSince1970 ?: 0.0)
        // 심박 기준 (이슈 #56) — 스냅샷이 단일 원본이라 nil이면 로컬 값도 지워 미설정으로 맞춘다
        if (hrMaxManual != null) {
            defaults.set(ProfileKey.hrMaxManual, hrMaxManual)
        } else {
            defaults.remove(ProfileKey.hrMaxManual)
        }
        if (restingHRManual != null) {
            defaults.set(ProfileKey.restingHRManual, restingHRManual)
        } else {
            defaults.remove(ProfileKey.restingHRManual)
        }
        if (hrZoneMethodRaw != null) {
            defaults.set(ProfileKey.hrZoneMethod, hrZoneMethodRaw)
        } else {
            defaults.remove(ProfileKey.hrZoneMethod)
        }
        // 주간 목표 변경 이력 (이슈 #108, #116) — 심박 기준과 같이 nil이면 로컬 이력도 지운다
        WeeklyGoalChangeLog.save(weeklyGoalChanges ?: emptyList(), defaults)
        // 서버 본을 반영한 것이지 로컬 변경이 아니다 — 그 본의 변경 시각을 그대로 이어받는다 (이슈 #130)
        defaults.set(GrowthKey.localChangedAt, updatedAt.timeIntervalSince1970)
    }

    companion object {
        /// 현재 스키마 버전 — 필드가 바뀌면 올리고, 병합·복원은 이 값 이하만 받는다.
        /// 심박 기준 필드(이슈 #56)·주간 목표 변경 이력(이슈 #108, #116)·사이클 목표 필드(이슈 #110)·대회 기록(이슈 #118)은 옵셔널 추가라 1로 둔다 — 옛 디코더는 모르는 키를 무시하고,
        /// 올리면 구버전 기기가 keepServer로 백업 자체를 멈춘다
        const val currentSchemaVersion = 1

        /// 현재 로컬 상태를 스냅샷으로 접는다. 온보딩 전(레벨 없음)이면 nil —
        /// 백업할 진행도 자체가 없다. revision은 동기화 메타라 스토어가 채운다(여기서는 0).
        /// `updatedAt`은 로컬 변경 시각(`GrowthKey.localChangedAt`)이다 — 병합이 "최신 변경"을 가리게 (이슈 #130).
        /// 키가 없으면(이 키 도입 전 설치의 첫 백업) `now`로 대신한다
        fun readLocal(defaults: KeyValueStore, birds: List<CollectedBird>,
                      raceRecords: List<RaceRecord>, deletedRaceRecordIDs: List<String> = emptyList(),
                      now: Instant): ProgressSnapshot? {
            val levelRaw = defaults.string(ProfileKey.levelV2)
            if (levelRaw == null || levelRaw.isEmpty()) return null
            val raceDateRaw = defaults.double(ProfileKey.raceDate)
            val hrMaxManual = defaults.int(ProfileKey.hrMaxManual)
            val restingHRManual = defaults.int(ProfileKey.restingHRManual)
            val hrZoneMethodRaw = defaults.string(ProfileKey.hrZoneMethod) ?: ""
            val weeklyGoalChanges = WeeklyGoalChangeLog.load(defaults)
            val localChangedAt = defaults.double(GrowthKey.localChangedAt)
            return ProgressSnapshot(
                schemaVersion = currentSchemaVersion,
                revision = 0,
                updatedAt = if (localChangedAt > 0) instantSince1970(localChangedAt) else now,
                cycleID = ensureCycleID(defaults),
                levelRaw = levelRaw,
                purposesRaw = defaults.string(ProfileKey.purposes) ?: "",
                weeklyGoal = defaults.int(ProfileKey.weeklyGoal),
                onboardedAt = instantSince1970(defaults.double(ProfileKey.onboardedAt)),
                cycleStartedAt = instantSince1970(defaults.double(GrowthKey.cycleStartedAt)),
                maxStage = defaults.int(GrowthKey.maxStage),
                raceGoalRaw = defaults.string(ProfileKey.raceGoal) ?: "",
                raceGoalSeconds = defaults.int(ProfileKey.raceGoalSec),
                raceDate = if (raceDateRaw > 0) instantSince1970(raceDateRaw) else null,
                collectedBirds = birds,
                hrMaxManual = if (hrMaxManual > 0) hrMaxManual else null,
                restingHRManual = if (restingHRManual > 0) restingHRManual else null,
                hrZoneMethodRaw = hrZoneMethodRaw.ifEmpty { null },
                weeklyGoalChanges = weeklyGoalChanges.ifEmpty { null },
                // 빈 문자열은 "목표 없음"이라 유효한 값 — 키가 없을 때만 nil이다
                cycleGoalRaw = defaults.string(GrowthKey.cycleGoal),
                cycleGoalSeconds = if (!defaults.contains(GrowthKey.cycleGoalSec))
                    null else defaults.int(GrowthKey.cycleGoalSec),
                raceRecords = raceRecords,
                deletedRaceRecordIDs = deletedRaceRecordIDs)
        }

        /// 백업 대상 값(프로필·사이클·도감·대회 기록·심박 기준·주간 목표 이력)이 로컬에서 바뀌었음을 기록한다 (이슈 #130).
        /// 다음 `readLocal`의 `updatedAt`이 이 시각이 되어, 병합은 업로드 시각이 아니라 실제 변경 시각으로 최신을 가린다
        fun markLocalChanged(defaults: KeyValueStore, now: Instant) {
            defaults.set(GrowthKey.localChangedAt, now.timeIntervalSince1970)
        }

        /// 사이클 식별자를 읽고, 없으면 만들어 저장한다 — 이 기능 도입 전 사용자의
        /// 기존 사이클에 식별자를 최초 1회 부여하는 마이그레이션이기도 하다.
        fun ensureCycleID(defaults: KeyValueStore): String {
            val raw = defaults.string(GrowthKey.cycleID)
            if (raw != null && uuidPattern.matches(raw)) {
                return raw.uppercase()  // Swift `UUID(uuidString:)!.uuidString`은 대문자다
            }
            val id = UUID.randomUUID().toString().uppercase()
            defaults.set(GrowthKey.cycleID, id)
            return id
        }

        /// Swift `UUID(uuidString:)`가 받는 형식 — 8-4-4-4-12 16진수 (`java.util.UUID.fromString`은 더 관대하다)
        private val uuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

        /// 스냅샷 JSON에서 관대 디코드가 버리는 새의 수 (이슈 #128) — 0보다 크면 이 앱이 모르는 종(미래 버전)이거나
        /// 손상된 항목이 있다. 그대로 병합해 올리면 그 새가 서버에서도 사라지므로 업로드를 보류하는 판정에 쓴다.
        /// JSON 자체를 읽을 수 없으면 0 — 그 경우는 스냅샷 디코드가 먼저 실패한다
        fun undecodableBirdCount(payload: ByteArray): Int {
            val birds = try {
                (EngineJson.parseToJsonElement(payload.decodeToString()) as? JsonObject)
                    ?.get("collectedBirds") as? JsonArray
            } catch (_: IllegalArgumentException) {
                null  // SerializationException은 IllegalArgumentException의 하위 타입이다
            } ?: return 0
            return birds.count { decodeBirdOrNull(it) == null }
        }

        /// 배열 원소 하나의 디코드 실패가 배열 전체 실패로 번지지 않게 감싼다 (이슈 #128, Swift `LenientElement`).
        /// 실패한 원소는 nil로 남아 호출부가 걸러 낸다
        internal fun decodeBirdOrNull(element: JsonElement): CollectedBird? = try {
            EngineJson.decodeFromJsonElement(CollectedBird.serializer(), element)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/// 합성 디코더와 같되, `weeklyGoalChanges`가 없고 옛 두 필드가 있으면 1건짜리 이력으로 흡수한다 (이슈 #116).
/// 인코드는 합성 그대로라 옛 필드를 다시 쓰지 않는다.
/// 도감은 관대하게 읽는다 (이슈 #128) — 모르는 종·손상된 1건 때문에 스냅샷 전체가 읽히지 않으면
/// 복원이 통째로 실패한다. 버린 새가 서버에서 지워지지 않게 업로드 쪽은 `undecodableBirdCount`로 보류한다
/// (Android: Swift `init(from:)`·`LegacyCodingKeys` 대신 JSON 트리를 고친 뒤 생성된 직렬화기로 넘긴다)
object ProgressSnapshotSerializer :
    JsonTransformingSerializer<ProgressSnapshot>(ProgressSnapshot.generatedSerializer()) {

    /// #108의 옛 주간 목표 변경 필드 — 디코드 때 흡수만 한다 (이슈 #116)
    private const val legacyChangedAtKey = "weeklyGoalChangedAt"
    private const val legacyBeforeKey = "weeklyGoalBefore"

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return buildJsonObject {
            for ((key, value) in obj) put(key, value)
            val birds = obj["collectedBirds"]
            if (birds is JsonArray) {
                put("collectedBirds", JsonArray(birds.filter { ProgressSnapshot.decodeBirdOrNull(it) != null }))
            }
            val changes = obj["weeklyGoalChanges"]
            if (changes == null || changes is JsonNull) {
                val at = obj[legacyChangedAtKey]?.takeUnless { it is JsonNull }
                val before = obj[legacyBeforeKey]?.takeUnless { it is JsonNull }
                if (at != null && before != null) {
                    put("weeklyGoalChanges", JsonArray(listOf(buildJsonObject {
                        put("at", at)
                        put("before", before)
                    })))
                }
            }
        }
    }
}
