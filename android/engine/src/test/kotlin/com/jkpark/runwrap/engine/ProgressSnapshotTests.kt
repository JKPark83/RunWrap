package com.jkpark.runwrap.engine

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// ProgressSnapshot 테스트 (이슈 #29).
///
/// 검증 축: ⑤ 로컬 읽기/쓰기 왕복이 무손실이다
/// ⑥ 직접 입력한 대회 기록도 도감처럼 백업된다 (이슈 #118).
/// (Android: ProgressMergeEngine(CloudKit 병합)은 옮기지 않아 그 테스트는 ios-only다 — docs/parity.md)
@DisplayName("진행도 스냅샷 병합·복원")
class ProgressSnapshotTests {

    // MARK: - 헬퍼

    private fun date(text: String): Instant = iso(text)

    /// 병합 테스트용 기준 스냅샷 — 필요한 필드만 바꿔 쓴다
    private fun makeSnapshot(
        schemaVersion: Int = ProgressSnapshot.currentSchemaVersion,
        revision: Int = 1,
        updatedAt: Instant = date("2026-08-01T09:00:00Z"),
        cycleID: String = "AAAAAAAA-0000-0000-0000-000000000001",
        levelRaw: String = "intermediate",
        weeklyGoal: Int = 3,
        maxStage: Int = 2,
        birds: List<CollectedBird> = emptyList(),
        raceRecords: List<RaceRecord>? = null,
        deletedRaceRecordIDs: List<String>? = null,
    ): ProgressSnapshot =
        ProgressSnapshot(
            schemaVersion = schemaVersion,
            revision = revision,
            updatedAt = updatedAt,
            cycleID = cycleID,
            levelRaw = levelRaw,
            purposesRaw = "habit",
            weeklyGoal = weeklyGoal,
            onboardedAt = date("2026-07-01T00:00:00Z"),
            cycleStartedAt = date("2026-07-01T00:00:00Z"),
            maxStage = maxStage,
            raceGoalRaw = "full",
            raceGoalSeconds = 4 * 3_600,
            raceDate = date("2026-11-01T00:00:00Z"),
            collectedBirds = birds,
            raceRecords = raceRecords,
            deletedRaceRecordIDs = deletedRaceRecordIDs)

    private fun makeBird(id: String, collectedAt: Instant): CollectedBird =
        CollectedBird(id = id, species = BirdSpecies.sparrow, goalLabel = "주 3회 습관",
                      collectedAt = collectedAt, cycleDays = 27)

    private fun makeRecord(id: String, race: RaceDistance = RaceDistance.half, date: Instant): RaceRecord =
        RaceRecord(id = id, race = race, timeSec = 6_300.0, date = date)

    private fun newID(): String = UUID.randomUUID().toString().uppercase()

    /// 테스트 격리용 저장소 — 비어 있는 상태로 시작한다
    private fun freshDefaults(): KeyValueStore = InMemoryKeyValueStore()

    private fun encode(snapshot: ProgressSnapshot): String =
        EngineJson.encodeToString(ProgressSnapshot.serializer(), snapshot)

    private fun decode(text: String): ProgressSnapshot =
        EngineJson.decodeFromString(ProgressSnapshot.serializer(), text)

    private fun jsonObject(text: String): JsonObject = EngineJson.parseToJsonElement(text).jsonObject

    // MARK: - 대회 기록 (이슈 #118)

    @Test
    @DisplayName("대회 기록 인코딩 왕복 — JSON으로 무손실 보존되고, 필드가 없는 옛 JSON은 nil로 디코드된다")
    fun raceRecordsCodableRoundTrip() {
        val record = makeRecord(id = "DDDDDDDD-0000-0000-0000-000000000006",
                                date = date("2026-05-10T00:00:00Z"))
        val snapshot = makeSnapshot(raceRecords = listOf(record))
        val decoded = decode(encode(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals(listOf(record), decoded.raceRecords)

        // nil 옵셔널은 synthesized 인코딩에서 키가 빠진다 — 이슈 #118 이전 본과 같은 JSON
        val legacyData = encode(makeSnapshot())
        val obj = jsonObject(legacyData)
        assertNull(obj["raceRecords"])
        val legacy = decode(legacyData)
        assertNull(legacy.raceRecords)
    }

    @Test
    @DisplayName("대회 기록 내용 비교 — 기록이 추가되면 다른 내용으로 보고 다시 올린다")
    fun raceRecordsAffectSameContent() {
        val record = makeRecord(id = "DDDDDDDD-0000-0000-0000-000000000007",
                                date = date("2026-05-10T00:00:00Z"))
        val before = makeSnapshot(raceRecords = emptyList())
        val after = makeSnapshot(revision = 2, raceRecords = listOf(record))
        assertFalse(before.hasSameContent(after))
    }

    @Test
    @DisplayName("대회 기록 삭제 표식 파일 — remove가 남긴 id를 저장·로드하고, 파일이 없으면 빈 목록")
    fun raceRecordTombstoneFileRoundTrip() {
        val dir = Files.createTempDirectory("tombstones-${UUID.randomUUID()}").toFile()
        try {
            assertEquals(emptyList(), RaceRecordCache.loadDeletedIDs(dir))
            val id = "DDDDDDDD-0000-0000-0000-000000000010"
            RaceRecordCache.saveDeletedIDs(listOf(id), dir)
            assertEquals(listOf(id), RaceRecordCache.loadDeletedIDs(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // MARK: - 내용 비교

    @Test
    @DisplayName("내용 비교 — 동기화 메타(revision·updatedAt)만 다르면 같은 내용으로 본다")
    fun sameContentIgnoresSyncMeta() {
        val a = makeSnapshot(revision = 1, updatedAt = date("2026-08-01T09:00:00Z"))
        val b = makeSnapshot(revision = 9, updatedAt = date("2026-08-15T09:00:00Z"))
        assertTrue(a.hasSameContent(b))

        // 실제 내용(weeklyGoal)이 다르면 당연히 다르다
        val c = makeSnapshot(weeklyGoal = 5)
        assertFalse(a.hasSameContent(c))
    }

    // MARK: - 로컬 읽기/쓰기

    @Test
    @DisplayName("apply → readLocal 왕복 — 스냅샷 내용이 무손실로 보존된다")
    fun applyReadLocalRoundTrip() {
        val defaults = freshDefaults()
        val bird = makeBird(id = newID(), collectedAt = date("2026-07-10T00:00:00Z"))
        val record = makeRecord(id = newID(), date = date("2026-05-10T00:00:00Z"))
        // readLocal은 대회 기록·삭제 표식을 항상 배열로 채우므로 기준본도 빈 표식 배열로 만든다 (이슈 #118)
        // apply는 nil 사이클 목표를 raceGoal로 채우므로, 무손실 왕복을 보려면 값을 넣어 둔다 (이슈 #110)
        val original = makeSnapshot(birds = listOf(bird), raceRecords = listOf(record),
                                    deletedRaceRecordIDs = emptyList())
            .copy(cycleGoalRaw = "full", cycleGoalSeconds = 4 * 3_600)

        original.apply(defaults)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = listOf(bird), raceRecords = listOf(record), now = date("2026-08-20T00:00:00Z")))

        // revision·updatedAt은 동기화 메타라 왕복 대상이 아니다 — 내용만 비교한다
        assertTrue(read.hasSameContent(original))
        assertEquals(original.cycleID, read.cycleID)
        assertEquals(original.raceDate, read.raceDate)
        assertEquals(listOf(record), read.raceRecords)
    }

    @Test
    @DisplayName("readLocal — 대회 날짜 없음(0)은 nil로 읽힌다")
    fun readLocalNilRaceDate() {
        val defaults = freshDefaults()
        val snapshot = makeSnapshot().copy(raceDate = null)
        snapshot.apply(defaults)

        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertNull(read.raceDate)
    }

    @Test
    @DisplayName("readLocal — 온보딩 전(레벨 없음)이면 nil, 백업할 진행도가 없다")
    fun readLocalNilBeforeOnboarding() {
        val defaults = freshDefaults()
        assertNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
    }

    // MARK: - 로컬 변경 시각 (이슈 #130)

    @Test
    @DisplayName("readLocal updatedAt — 로컬 변경 시각 키가 있으면 그 시각, 없으면(도입 전 설치) now")
    fun readLocalUpdatedAtUsesLocalChangedAt() {
        val defaults = freshDefaults()
        defaults.set(ProfileKey.levelV2, "intermediate")
        val now = date("2026-09-30T09:00:00Z")

        // 키 없음 → 업로드 시각(now)으로 폴백
        val fallback = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = now))
        assertEquals(now, fallback.updatedAt)

        // 키 있음 → now가 아니라 기록된 변경 시각(9/1)
        val changedAt = date("2026-09-01T09:00:00Z")
        defaults.set(GrowthKey.localChangedAt, changedAt.timeIntervalSince1970)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = now))
        assertEquals(changedAt, read.updatedAt)
    }

    @Test
    @DisplayName("markLocalChanged — 기록한 시각이 다음 readLocal의 updatedAt이 된다")
    fun markLocalChangedReflectsInReadLocal() {
        val defaults = freshDefaults()
        defaults.set(ProfileKey.levelV2, "intermediate")
        val changedAt = date("2026-09-10T12:00:00Z")

        ProgressSnapshot.markLocalChanged(defaults = defaults, now = changedAt)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-09-30T09:00:00Z")))
        assertEquals(changedAt, read.updatedAt)
    }

    @Test
    @DisplayName("apply — 서버 본 반영은 로컬 변경이 아니라 그 본의 updatedAt을 변경 시각으로 이어받는다")
    fun applyKeepsServerUpdatedAt() {
        val defaults = freshDefaults()
        // 이전 로컬 변경(9/25)이 있어도 적용한 서버 본의 시각(8/1)으로 덮인다
        ProgressSnapshot.markLocalChanged(defaults = defaults, now = date("2026-09-25T00:00:00Z"))
        val server = makeSnapshot(updatedAt = date("2026-08-01T09:00:00Z"))

        server.apply(defaults)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-09-30T09:00:00Z")))
        assertEquals(server.updatedAt, read.updatedAt)
    }

    @Test
    @DisplayName("심박 기준 왕복 — 수동 최대·안정 심박·존 방식이 apply→readLocal로 보존되고, 0/빈 값은 nil로 읽힌다")
    fun heartRateRoundTrip() {
        val defaults = freshDefaults()
        val snapshot = makeSnapshot().copy(hrMaxManual = 185, restingHRManual = 48, hrZoneMethodRaw = "karvonen")
        snapshot.apply(defaults)

        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertEquals(185, read.hrMaxManual)
        assertEquals(48, read.restingHRManual)
        assertEquals("karvonen", read.hrZoneMethodRaw)

        // 미설정 defaults(키 없음 → integer 0, string nil)는 nil 셋으로 읽힌다
        val unset = freshDefaults()
        makeSnapshot().apply(unset)
        val readUnset = assertNotNull(ProgressSnapshot.readLocal(
            defaults = unset, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertNull(readUnset.hrMaxManual)
        assertNull(readUnset.restingHRManual)
        assertNull(readUnset.hrZoneMethodRaw)
    }

    @Test
    @DisplayName("옛 스냅샷 호환 — 심박 필드가 없는 JSON도 디코드되고, 적용하면 로컬 수동값을 미설정으로 되돌린다")
    fun legacySnapshotWithoutHeartRate() {
        // nil 옵셔널은 synthesized 인코딩에서 키 자체가 빠진다 — 이슈 #56 이전 본과 같은 JSON
        val data = encode(makeSnapshot())
        val obj = jsonObject(data)
        assertNull(obj["hrMaxManual"])
        assertNull(obj["restingHRManual"])
        assertNull(obj["hrZoneMethodRaw"])

        val decoded = decode(data)
        assertNull(decoded.hrMaxManual)
        assertNull(decoded.restingHRManual)
        assertNull(decoded.hrZoneMethodRaw)

        // 스냅샷이 단일 원본 — 로컬에 남은 수동값 180은 지워져 추정값으로 돌아간다
        val defaults = freshDefaults()
        defaults.set(ProfileKey.hrMaxManual, 180)
        decoded.apply(defaults)
        assertEquals(0, defaults.int(ProfileKey.hrMaxManual))
    }

    @Test
    @DisplayName("주간 목표 변경 이력 왕복 — apply→readLocal로 전부 보존되고, 이력 없음은 nil·#108 이전 JSON도 디코드된다")
    fun weeklyGoalChangeRoundTrip() {
        val defaults = freshDefaults()
        val history = listOf(WeeklyGoalChange(at = date("2026-07-16T00:00:00Z"), before = 3),
                             WeeklyGoalChange(at = date("2026-08-12T00:00:00Z"), before = 1))
        val snapshot = makeSnapshot().copy(weeklyGoalChanges = history)
        snapshot.apply(defaults)

        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertEquals(history, read.weeklyGoalChanges)
        val reencoded = decode(encode(snapshot))
        assertEquals(history, reencoded.weeklyGoalChanges)

        // nil 옵셔널은 키 자체가 빠진다 — 이슈 #108 이전 본과 같은 JSON이 nil로 디코드된다
        val data = encode(makeSnapshot())
        val obj = jsonObject(data)
        assertNull(obj["weeklyGoalChanges"])
        val decoded = decode(data)
        assertNull(decoded.weeklyGoalChanges)

        // 스냅샷이 단일 원본 — 이력 없는 본을 적용하면 로컬 이력도 지워져 readLocal이 nil로 읽는다
        decoded.apply(defaults)
        val readCleared = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertNull(readCleared.weeklyGoalChanges)
    }

    @Test
    @DisplayName("#108 스냅샷 흡수 — weeklyGoalChangedAt·weeklyGoalBefore만 있는 옛 JSON은 1건짜리 이력으로 디코드되고, 다시 인코드하면 새 필드만 쓴다 (이슈 #116)")
    fun legacyWeeklyGoalChangeSnapshotDecodes() {
        // #108 인코더와 같은 모양 — Date는 JSONEncoder 기본 전략(2001 기준 초)으로 적힌다
        val changedAt = date("2026-08-12T00:00:00Z")
        val obj = jsonObject(encode(makeSnapshot())).toMutableMap()
        obj["weeklyGoalChangedAt"] = JsonPrimitive((changedAt.epochSecond - 978_307_200).toDouble())
        obj["weeklyGoalBefore"] = JsonPrimitive(3)
        val legacyData = JsonObject(obj).toString()

        val decoded = decode(legacyData)
        assertEquals(listOf(WeeklyGoalChange(at = changedAt, before = 3)), decoded.weeklyGoalChanges)

        val reencoded = jsonObject(encode(decoded))
        assertNull(reencoded["weeklyGoalChangedAt"])
        assertNull(reencoded["weeklyGoalBefore"])
        assertNotNull(reencoded["weeklyGoalChanges"])
    }

    @Test
    @DisplayName("옛 두 키 이관 — #108의 변경 시각·이전 목표 키는 첫 읽기 때 1건짜리 이력으로 옮겨지고 지워진다 (이슈 #116)")
    fun legacyWeeklyGoalKeysMigrate() {
        val defaults = freshDefaults()
        val changedAt = date("2026-08-12T00:00:00Z")
        defaults.set(WeeklyGoalChangeLog.legacyChangedAtKey, changedAt.timeIntervalSince1970)
        defaults.set(WeeklyGoalChangeLog.legacyBeforeKey, 3)

        assertEquals(listOf(WeeklyGoalChange(at = changedAt, before = 3)), WeeklyGoalChangeLog.load(defaults))
        // 옛 키는 지워지고 새 키 하나에 남는다 — 다시 읽어도 같은 이력
        assertFalse(defaults.contains(WeeklyGoalChangeLog.legacyChangedAtKey))
        assertFalse(defaults.contains(WeeklyGoalChangeLog.legacyBeforeKey))
        assertNotNull(defaults.string(ProfileKey.weeklyGoalChanges))
        assertEquals(listOf(WeeklyGoalChange(at = changedAt, before = 3)), WeeklyGoalChangeLog.load(defaults))

        // 아무 기록도 없으면 빈 이력이고 새 키를 만들지 않는다
        val empty = freshDefaults()
        assertTrue(WeeklyGoalChangeLog.load(empty).isEmpty())
        assertNull(empty.string(ProfileKey.weeklyGoalChanges))
    }

    @Test
    @DisplayName("사이클 목표 왕복 — 설정 목표와 다른 사이클 목표가 인코딩·디코딩과 apply→readLocal로 보존된다 (이슈 #110)")
    fun cycleGoalRoundTrip() {
        // 설정 목표는 풀 4:00:00(makeSnapshot), 사이클 목표는 하프 1:45:00(6_300초) — 중간에 목표를 바꾼 상황
        val snapshot = makeSnapshot().copy(cycleGoalRaw = "half", cycleGoalSeconds = 6_300)

        val decoded = decode(encode(snapshot))
        assertEquals(snapshot, decoded)
        assertEquals("half", decoded.cycleGoalRaw)
        assertEquals(6_300, decoded.cycleGoalSeconds)

        val defaults = freshDefaults()
        decoded.apply(defaults)
        assertEquals("half", defaults.string(GrowthKey.cycleGoal))
        assertEquals(6_300, defaults.int(GrowthKey.cycleGoalSec))
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertEquals("half", read.cycleGoalRaw)
        assertEquals(6_300, read.cycleGoalSeconds)
        assertEquals("full", read.raceGoalRaw)
    }

    @Test
    @DisplayName("사이클 목표 — 목표 없음(빈 문자열·0초)은 nil이 아니라 유효한 값으로 왕복한다")
    fun cycleGoalNoneRoundTrip() {
        val snapshot = makeSnapshot().copy(cycleGoalRaw = "", cycleGoalSeconds = 0)

        val defaults = freshDefaults()
        snapshot.apply(defaults)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertEquals("", read.cycleGoalRaw)
        assertEquals(0, read.cycleGoalSeconds)
    }

    @Test
    @DisplayName("옛 스냅샷 호환 — 사이클 목표 필드가 없는 JSON은 nil로 디코드되고, 적용하면 raceGoal로 대체한다 (이슈 #110)")
    fun legacySnapshotWithoutCycleGoal() {
        // makeSnapshot의 사이클 목표는 nil → synthesized 인코딩에서 키가 빠진다 — 이슈 #110 이전 본과 같은 JSON
        val data = encode(makeSnapshot())
        val obj = jsonObject(data)
        assertNull(obj["cycleGoalRaw"])
        assertNull(obj["cycleGoalSeconds"])

        val decoded = decode(data)
        assertNull(decoded.cycleGoalRaw)
        assertNull(decoded.cycleGoalSeconds)

        // 복원: 로컬에 남은 다른 사이클 목표(10K) 대신 스냅샷의 raceGoal(풀 4:00:00)로 채운다
        val defaults = freshDefaults()
        defaults.set(GrowthKey.cycleGoal, "tenK")
        defaults.set(GrowthKey.cycleGoalSec, 3_000)
        decoded.apply(defaults)
        assertEquals("full", defaults.string(GrowthKey.cycleGoal))
        assertEquals(4 * 3_600, defaults.int(GrowthKey.cycleGoalSec))
    }

    @Test
    @DisplayName("readLocal — 사이클 목표 키가 없는 설치(도입 전)는 nil로 읽힌다")
    fun readLocalNilCycleGoalWithoutKey() {
        val defaults = freshDefaults()
        defaults.set(ProfileKey.levelV2, "intermediate")
        defaults.set(ProfileKey.raceGoal, "full")

        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-08-20T00:00:00Z")))
        assertNull(read.cycleGoalRaw)
        assertNull(read.cycleGoalSeconds)
    }

    // MARK: - 복원 선택 판정 (이슈 #44)

    @Test
    @DisplayName("불러오기 — 온보딩 값 위에 서버 본을 적용하면 서버의 사이클 식별자·단계·시작 시각이 남는다")
    fun acceptAppliesServerCycle() {
        val defaults = freshDefaults()
        // 방금 마친 온보딩: 사이클 B, 알 단계, 9/29 시작
        val onboarding = makeSnapshot(cycleID = "BBBBBBBB-0000-0000-0000-000000000002",
                                      levelRaw = "beginner", maxStage = 1)
            .copy(cycleStartedAt = date("2026-09-29T09:00:00Z"))
        onboarding.apply(defaults)
        // 서버: 사이클 A, 4단계, 7/1 시작 (makeSnapshot 기본값)
        val server = makeSnapshot(maxStage = 4)

        server.apply(defaults)
        val read = assertNotNull(ProgressSnapshot.readLocal(
            defaults = defaults, birds = emptyList(), raceRecords = emptyList(), now = date("2026-09-29T10:00:00Z")))

        assertEquals(server.cycleID, read.cycleID)
        assertEquals(4, read.maxStage)
        assertEquals(date("2026-07-01T00:00:00Z"), read.cycleStartedAt)
        assertEquals("intermediate", read.levelRaw)
    }

    @Test
    @DisplayName("ensureCycleID — 없으면 만들어 저장하고, 있으면 같은 값을 돌려준다")
    fun ensureCycleIDIsStable() {
        val defaults = freshDefaults()
        // 최초 호출: 생성 + 저장 (기존 사용자 마이그레이션 경로)
        val first = ProgressSnapshot.ensureCycleID(defaults = defaults)
        // 두 번째 호출: 저장된 값을 그대로 — 부를 때마다 바뀌면 사이클 병합이 깨진다
        val second = ProgressSnapshot.ensureCycleID(defaults = defaults)
        assertEquals(first, second)
        assertEquals(first, defaults.string(GrowthKey.cycleID))
    }

    // MARK: - 업로드 안전성 (이슈 #128)

    @Test
    @DisplayName("도감 관대 디코드 — 모르는 종이 섞인 JSON도 스냅샷은 디코드되고 나머지 새는 살아남으며, 버린 수를 셀 수 있다")
    fun lenientBirdDecoding() {
        val known = "BBBBBBBB-0000-0000-0000-000000000002"
        val snapshot = makeSnapshot(birds = listOf(
            makeBird(id = "BBBBBBBB-0000-0000-0000-000000000001",
                     collectedAt = date("2026-05-01T00:00:00Z")),
            makeBird(id = known, collectedAt = date("2026-06-01T00:00:00Z")),
        ))
        val original = encode(snapshot)
        // 정상 JSON은 버리는 새가 없다
        assertEquals(0, ProgressSnapshot.undecodableBirdCount(original.encodeToByteArray()))

        // 첫 번째 새를 미래 버전의 종("phoenix")으로 바꾼다
        val obj = jsonObject(original).toMutableMap()
        val birds = obj.getValue("collectedBirds").jsonArray.toMutableList()
        birds[0] = JsonObject(birds[0].jsonObject + ("species" to JsonPrimitive("phoenix")))
        obj["collectedBirds"] = JsonArray(birds)
        val tampered = JsonObject(obj).toString()

        val decoded = decode(tampered)
        assertEquals(listOf(known), decoded.collectedBirds.map { it.id })
        assertEquals(snapshot.levelRaw, decoded.levelRaw)
        // 업로드는 이 수가 0보다 크면 보류한다 — 버린 새가 서버에서 지워지지 않게
        assertEquals(1, ProgressSnapshot.undecodableBirdCount(tampered.encodeToByteArray()))
    }
}
