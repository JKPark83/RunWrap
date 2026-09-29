import Foundation
import Testing
@testable import RunWrap

/// ProgressSnapshot·ProgressMergeEngine 테스트 (이슈 #29).
///
/// 검증 축: ① 오래된 스냅샷이 최신 진행도를 되돌리지 않는다
/// ② 같은 사이클에서 maxStage는 절대 낮아지지 않는다 ③ 도감은 항상 합집합
/// ④ 미래 스키마는 건드리지 않는다 ⑤ 로컬 읽기/쓰기 왕복이 무손실이다
/// ⑥ 직접 입력한 대회 기록도 도감처럼 합집합으로 백업된다 (이슈 #118).
@Suite("진행도 스냅샷 병합·복원")
struct ProgressSnapshotTests {

    // MARK: - 헬퍼

    private static func date(_ iso: String) -> Date {
        ISO8601DateFormatter().date(from: iso)!
    }

    /// 병합 테스트용 기준 스냅샷 — 필요한 필드만 바꿔 쓴다
    private static func makeSnapshot(
        schemaVersion: Int = ProgressSnapshot.currentSchemaVersion,
        revision: Int = 1,
        updatedAt: Date = date("2026-08-01T09:00:00Z"),
        cycleID: UUID = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!,
        levelRaw: String = "intermediate",
        weeklyGoal: Int = 3,
        maxStage: Int = 2,
        birds: [CollectedBird] = [],
        raceRecords: [RaceRecord]? = nil,
        deletedRaceRecordIDs: [UUID]? = nil
    ) -> ProgressSnapshot {
        ProgressSnapshot(
            schemaVersion: schemaVersion,
            revision: revision,
            updatedAt: updatedAt,
            cycleID: cycleID,
            levelRaw: levelRaw,
            purposesRaw: "habit",
            weeklyGoal: weeklyGoal,
            onboardedAt: date("2026-07-01T00:00:00Z"),
            cycleStartedAt: date("2026-07-01T00:00:00Z"),
            maxStage: maxStage,
            raceGoalRaw: "full",
            raceGoalSeconds: 4 * 3_600,
            raceDate: date("2026-11-01T00:00:00Z"),
            collectedBirds: birds,
            raceRecords: raceRecords,
            deletedRaceRecordIDs: deletedRaceRecordIDs)
    }

    private static func makeBird(id: UUID, collectedAt: Date) -> CollectedBird {
        CollectedBird(id: id, species: .sparrow, goalLabel: "주 3회 습관",
                      collectedAt: collectedAt, cycleDays: 27)
    }

    private static func makeRecord(id: UUID, race: RaceDistance = .half, date: Date) -> RaceRecord {
        RaceRecord(id: id, race: race, timeSec: 6_300, date: date)
    }

    /// 테스트 격리용 UserDefaults — 도메인을 비우고 시작한다
    private static func freshDefaults(_ name: String) -> UserDefaults {
        let suite = "ProgressSnapshotTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    // MARK: - 병합: maxStage 보존

    @Test("같은 사이클 병합 — 서버가 오래됐어도 maxStage는 낮아지지 않는다")
    func sameCycleKeepsHigherMaxStage() throws {
        // 로컬이 최신(8/10)이지만 단계는 2 — 서버(8/5)의 단계 4가 살아남아야 한다
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"), maxStage: 2)
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"), maxStage: 4)

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        #expect(merged.maxStage == 4)
        // 스칼라는 최신인 로컬 쪽 — updatedAt 비교로 로컬이 이긴다
        #expect(merged.weeklyGoal == local.weeklyGoal)
    }

    @Test("같은 사이클 병합 — 최신 updatedAt 쪽의 스칼라 값이 이긴다")
    func newerSideWinsScalars() throws {
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"), weeklyGoal: 3)
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"), weeklyGoal: 5)

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 서버가 최신 → weeklyGoal 5. updatedAt은 둘 중 최댓값으로 남는다
        #expect(merged.weeklyGoal == 5)
        #expect(merged.updatedAt == Self.date("2026-08-10T09:00:00Z"))
    }

    @Test("다른 사이클 병합 — 사이클 전환은 원자적이라 최신 쪽이 통째로 이긴다")
    func differentCycleNewerWinsWholesale() throws {
        let oldCycle = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
        let newCycle = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!
        // 서버: 이전 사이클에서 단계 4까지 갔던 본. 로컬: 새 사이클을 막 시작해 단계 0
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"),
                                       cycleID: oldCycle, maxStage: 4)
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"),
                                      cycleID: newCycle, maxStage: 0)

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 다른 사이클이므로 maxStage max 규칙을 적용하지 않는다 — 새 사이클의 0이 맞다
        #expect(merged.cycleID == newCycle)
        #expect(merged.maxStage == 0)
    }

    // MARK: - 병합: 도감 합집합

    @Test("도감 병합 — id 기준 중복 제거 후 수집일 오름차순 합집합")
    func birdsUnionDedupesAndSorts() throws {
        let sharedID = UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000001")!
        let shared = Self.makeBird(id: sharedID, collectedAt: Self.date("2026-07-10T00:00:00Z"))
        let localOnly = Self.makeBird(id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000002")!,
                                      collectedAt: Self.date("2026-08-09T00:00:00Z"))
        let serverOnly = Self.makeBird(id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000003")!,
                                       collectedAt: Self.date("2026-07-20T00:00:00Z"))

        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"),
                                      birds: [shared, localOnly])
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"),
                                       birds: [shared, serverOnly])

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 공통 1 + 로컬 1 + 서버 1 = 3마리, 수집일 오래된 순 (7/10 → 7/20 → 8/9)
        #expect(merged.collectedBirds.map(\.id) == [shared.id, serverOnly.id, localOnly.id])
    }

    // MARK: - 대회 기록 (이슈 #118)

    @Test("대회 기록 합집합 — id 기준 중복 제거 후 대회 날짜 최신순")
    func raceRecordsUnionDedupesAndSorts() {
        let shared = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000001")!,
                                     date: Self.date("2026-04-12T00:00:00Z"))
        let lhsOnly = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000002")!,
                                      race: .tenK, date: Self.date("2025-10-19T00:00:00Z"))
        let rhsOnly = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000003")!,
                                      race: .full, date: Self.date("2026-06-07T00:00:00Z"))

        let union = ProgressMergeEngine.unionRaceRecords([shared, lhsOnly], [rhsOnly, shared])
        // 공통 1 + 왼쪽 1 + 오른쪽 1 = 3건, 최신순 (2026-06-07 → 2026-04-12 → 2025-10-19)
        #expect(union.map(\.id) == [rhsOnly.id, shared.id, lhsOnly.id])
    }

    @Test("대회 기록 병합 — 어느 쪽이 최신이든 양쪽 기록이 모두 남고, 옛 서버 본(nil)도 로컬 기록을 지우지 않는다")
    func mergeKeepsRaceRecordsFromBothSides() throws {
        let localRecord = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000004")!,
                                          date: Self.date("2026-05-10T00:00:00Z"))
        let serverRecord = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000005")!,
                                           race: .tenK, date: Self.date("2026-03-01T00:00:00Z"))
        // 서버가 최신(8/10)이라 스칼라는 서버 쪽이 이기지만 대회 기록은 합집합이다
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"),
                                      raceRecords: [localRecord])
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"),
                                       raceRecords: [serverRecord])
        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 최신순: 로컬 5/10 → 서버 3/1
        #expect(merged.raceRecords == [localRecord, serverRecord])

        // 이 필드가 없던 옛 서버 본(nil)이 최신이어도 로컬 기록은 살아남는다
        let legacyServer = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"))
        guard case .upload(let mergedLegacy) = ProgressMergeEngine.merge(local: local,
                                                                         server: legacyServer) else {
            Issue.record("upload여야 한다")
            return
        }
        #expect(mergedLegacy.raceRecords == [localRecord])
    }

    @Test("대회 기록 인코딩 왕복 — JSON으로 무손실 보존되고, 필드가 없는 옛 JSON은 nil로 디코드된다")
    func raceRecordsCodableRoundTrip() throws {
        let record = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000006")!,
                                     date: Self.date("2026-05-10T00:00:00Z"))
        let snapshot = Self.makeSnapshot(raceRecords: [record])
        let decoded = try JSONDecoder().decode(ProgressSnapshot.self,
                                               from: JSONEncoder().encode(snapshot))
        #expect(decoded == snapshot)
        #expect(decoded.raceRecords == [record])

        // nil 옵셔널은 synthesized 인코딩에서 키가 빠진다 — 이슈 #118 이전 본과 같은 JSON
        let legacyData = try JSONEncoder().encode(Self.makeSnapshot())
        let object = try #require(try JSONSerialization.jsonObject(with: legacyData) as? [String: Any])
        #expect(object["raceRecords"] == nil)
        let legacy = try JSONDecoder().decode(ProgressSnapshot.self, from: legacyData)
        #expect(legacy.raceRecords == nil)
    }

    @Test("대회 기록 내용 비교 — 기록이 추가되면 다른 내용으로 보고 다시 올린다")
    func raceRecordsAffectSameContent() {
        let record = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000007")!,
                                     date: Self.date("2026-05-10T00:00:00Z"))
        let before = Self.makeSnapshot(raceRecords: [])
        let after = Self.makeSnapshot(revision: 2, raceRecords: [record])
        #expect(!before.hasSameContent(as: after))
    }

    @Test("대회 기록 삭제 표식 — 한 기기에서 지운 기록은 서버 본에 남아 있어도 병합에서 되살아나지 않는다")
    func deletedRaceRecordStaysDeletedAfterMerge() throws {
        let deletedRecord = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000008")!,
                                            date: Self.date("2026-05-10T00:00:00Z"))
        let kept = Self.makeRecord(id: UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000009")!,
                                   race: .tenK, date: Self.date("2026-03-01T00:00:00Z"))
        // 로컬: 지운 뒤(기록 없음, 표식 있음). 서버: 지우기 전 본(기록 둘, 표식 없음)이 더 최신
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"),
                                      raceRecords: [kept], deletedRaceRecordIDs: [deletedRecord.id])
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"),
                                       raceRecords: [deletedRecord, kept])
        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        #expect(merged.raceRecords == [kept])
        // 표식은 병합본에도 남아 다른 기기의 합집합에서도 걸러진다
        #expect(merged.deletedRaceRecordIDs == [deletedRecord.id])

        // 반대 방향: 서버 표식이 로컬 기록을 지운다 (다른 기기에서 지운 경우)
        let localStale = Self.makeSnapshot(raceRecords: [deletedRecord, kept])
        let serverDeleted = Self.makeSnapshot(raceRecords: [kept], deletedRaceRecordIDs: [deletedRecord.id])
        guard case .upload(let merged2) = ProgressMergeEngine.merge(local: localStale, server: serverDeleted) else {
            Issue.record("upload여야 한다")
            return
        }
        #expect(merged2.raceRecords == [kept])
    }

    @Test("대회 기록 삭제 표식 파일 — remove가 남긴 id를 저장·로드하고, 파일이 없으면 빈 목록")
    func raceRecordTombstoneFileRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tombstones-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(RaceRecordCache.loadDeletedIDs(from: dir) == [])
        let id = UUID(uuidString: "DDDDDDDD-0000-0000-0000-000000000010")!
        RaceRecordCache.saveDeletedIDs([id], in: dir)
        #expect(RaceRecordCache.loadDeletedIDs(from: dir) == [id])
    }

    // MARK: - 병합: 스키마·revision

    @Test("미래 스키마 서버 본 — 덮어쓰지 않고 keepServer로 보류한다")
    func futureServerSchemaIsKept() throws {
        let local = Self.makeSnapshot()
        let server = Self.makeSnapshot(schemaVersion: ProgressSnapshot.currentSchemaVersion + 1)
        #expect(ProgressMergeEngine.merge(local: local, server: server) == .keepServer)
    }

    @Test("revision 단조 증가 — 병합본은 양쪽 최댓값보다 크다")
    func revisionIsMonotonic() throws {
        let local = Self.makeSnapshot(revision: 3)
        let server = Self.makeSnapshot(revision: 7)
        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // max(3, 7) + 1 = 8 — 서버 최신본으로 남으려면 서버 revision을 넘어야 한다
        #expect(merged.revision == 8)
    }

    // MARK: - 복원 가능 판정

    @Test("복원 판정 — 미래 스키마·빈 레벨은 거부, 현재 스키마는 허용")
    func canRestoreGuards() throws {
        #expect(ProgressMergeEngine.canRestore(Self.makeSnapshot()))
        #expect(!ProgressMergeEngine.canRestore(
            Self.makeSnapshot(schemaVersion: ProgressSnapshot.currentSchemaVersion + 1)))
        #expect(!ProgressMergeEngine.canRestore(Self.makeSnapshot(levelRaw: "")))
    }

    // MARK: - 내용 비교

    @Test("내용 비교 — 동기화 메타(revision·updatedAt)만 다르면 같은 내용으로 본다")
    func sameContentIgnoresSyncMeta() throws {
        let a = Self.makeSnapshot(revision: 1, updatedAt: Self.date("2026-08-01T09:00:00Z"))
        let b = Self.makeSnapshot(revision: 9, updatedAt: Self.date("2026-08-15T09:00:00Z"))
        #expect(a.hasSameContent(as: b))

        // 실제 내용(weeklyGoal)이 다르면 당연히 다르다
        let c = Self.makeSnapshot(weeklyGoal: 5)
        #expect(!a.hasSameContent(as: c))
    }

    // MARK: - 로컬 읽기/쓰기

    @Test("apply → readLocal 왕복 — 스냅샷 내용이 무손실로 보존된다")
    func applyReadLocalRoundTrip() throws {
        let defaults = Self.freshDefaults("roundTrip")
        let bird = Self.makeBird(id: UUID(), collectedAt: Self.date("2026-07-10T00:00:00Z"))
        let record = Self.makeRecord(id: UUID(), date: Self.date("2026-05-10T00:00:00Z"))
        // readLocal은 대회 기록·삭제 표식을 항상 배열로 채우므로 기준본도 빈 표식 배열로 만든다 (이슈 #118)
        var original = Self.makeSnapshot(birds: [bird], raceRecords: [record], deletedRaceRecordIDs: [])
        // apply는 nil 사이클 목표를 raceGoal로 채우므로, 무손실 왕복을 보려면 값을 넣어 둔다 (이슈 #110)
        original.cycleGoalRaw = "full"
        original.cycleGoalSeconds = 4 * 3_600

        original.apply(to: defaults)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [bird], raceRecords: [record], now: Self.date("2026-08-20T00:00:00Z")))

        // revision·updatedAt은 동기화 메타라 왕복 대상이 아니다 — 내용만 비교한다
        #expect(read.hasSameContent(as: original))
        #expect(read.cycleID == original.cycleID)
        #expect(read.raceDate == original.raceDate)
        #expect(read.raceRecords == [record])
    }

    @Test("readLocal — 대회 날짜 없음(0)은 nil로 읽힌다")
    func readLocalNilRaceDate() throws {
        let defaults = Self.freshDefaults("nilRaceDate")
        var snapshot = Self.makeSnapshot()
        snapshot.raceDate = nil
        snapshot.apply(to: defaults)

        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.raceDate == nil)
    }

    @Test("readLocal — 온보딩 전(레벨 없음)이면 nil, 백업할 진행도가 없다")
    func readLocalNilBeforeOnboarding() throws {
        let defaults = Self.freshDefaults("beforeOnboarding")
        #expect(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")) == nil)
    }

    // MARK: - 로컬 변경 시각 (이슈 #130)

    @Test("readLocal updatedAt — 로컬 변경 시각 키가 있으면 그 시각, 없으면(도입 전 설치) now")
    func readLocalUpdatedAtUsesLocalChangedAt() throws {
        let defaults = Self.freshDefaults("localChangedAt")
        defaults.set("intermediate", forKey: ProfileKey.levelV2)
        let now = Self.date("2026-09-30T09:00:00Z")

        // 키 없음 → 업로드 시각(now)으로 폴백
        let fallback = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: now))
        #expect(fallback.updatedAt == now)

        // 키 있음 → now가 아니라 기록된 변경 시각(9/1)
        let changedAt = Self.date("2026-09-01T09:00:00Z")
        defaults.set(changedAt.timeIntervalSince1970, forKey: GrowthKey.localChangedAt)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: now))
        #expect(read.updatedAt == changedAt)
    }

    @Test("markLocalChanged — 기록한 시각이 다음 readLocal의 updatedAt이 된다")
    func markLocalChangedReflectsInReadLocal() throws {
        let defaults = Self.freshDefaults("markLocalChanged")
        defaults.set("intermediate", forKey: ProfileKey.levelV2)
        let changedAt = Self.date("2026-09-10T12:00:00Z")

        ProgressSnapshot.markLocalChanged(defaults: defaults, now: changedAt)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-09-30T09:00:00Z")))
        #expect(read.updatedAt == changedAt)
    }

    @Test("apply — 서버 본 반영은 로컬 변경이 아니라 그 본의 updatedAt을 변경 시각으로 이어받는다")
    func applyKeepsServerUpdatedAt() throws {
        let defaults = Self.freshDefaults("applyUpdatedAt")
        // 이전 로컬 변경(9/25)이 있어도 적용한 서버 본의 시각(8/1)으로 덮인다
        ProgressSnapshot.markLocalChanged(defaults: defaults, now: Self.date("2026-09-25T00:00:00Z"))
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-01T09:00:00Z"))

        server.apply(to: defaults)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-09-30T09:00:00Z")))
        #expect(read.updatedAt == server.updatedAt)
    }

    @Test("오래된 백업 복원 기기의 뒤늦은 업로드 — 로컬 변경이 9/1이면 9/20 서버 본(다른 사이클)을 이기지 못한다")
    func staleRestoredDeviceLosesToNewerServer() throws {
        let defaults = Self.freshDefaults("staleUpload")
        let oldCycle = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
        let newCycle = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!
        // 로컬: 옛 사이클 본, 마지막 로컬 변경 9/1. 업로드는 9/30에야 일어난다
        Self.makeSnapshot(updatedAt: Self.date("2026-09-01T09:00:00Z"), cycleID: oldCycle, maxStage: 4)
            .apply(to: defaults)
        let local = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-09-30T09:00:00Z")))
        // 서버: 다른 기기가 9/20에 새 사이클로 올린 본
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-09-20T09:00:00Z"),
                                       cycleID: newCycle, maxStage: 1)

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 9/1 < 9/20 → 서버가 통째로 이긴다. 예전(updatedAt = 업로드 시각 9/30)이면 로컬이 이겼다
        #expect(merged.cycleID == newCycle)
        #expect(merged.maxStage == 1)
        #expect(merged.updatedAt == server.updatedAt)
    }

    @Test("심박 기준 왕복 — 수동 최대·안정 심박·존 방식이 apply→readLocal로 보존되고, 0/빈 값은 nil로 읽힌다")
    func heartRateRoundTrip() throws {
        let defaults = Self.freshDefaults("heartRate")
        var snapshot = Self.makeSnapshot()
        snapshot.hrMaxManual = 185
        snapshot.restingHRManual = 48
        snapshot.hrZoneMethodRaw = "karvonen"
        snapshot.apply(to: defaults)

        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.hrMaxManual == 185)
        #expect(read.restingHRManual == 48)
        #expect(read.hrZoneMethodRaw == "karvonen")

        // 미설정 defaults(키 없음 → integer 0, string nil)는 nil 셋으로 읽힌다
        let unset = Self.freshDefaults("heartRateUnset")
        Self.makeSnapshot().apply(to: unset)
        let readUnset = try #require(ProgressSnapshot.readLocal(
            defaults: unset, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(readUnset.hrMaxManual == nil)
        #expect(readUnset.restingHRManual == nil)
        #expect(readUnset.hrZoneMethodRaw == nil)
    }

    @Test("옛 스냅샷 호환 — 심박 필드가 없는 JSON도 디코드되고, 적용하면 로컬 수동값을 미설정으로 되돌린다")
    func legacySnapshotWithoutHeartRate() throws {
        // nil 옵셔널은 synthesized 인코딩에서 키 자체가 빠진다 — 이슈 #56 이전 본과 같은 JSON
        let data = try JSONEncoder().encode(Self.makeSnapshot())
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["hrMaxManual"] == nil)
        #expect(object["restingHRManual"] == nil)
        #expect(object["hrZoneMethodRaw"] == nil)

        let decoded = try JSONDecoder().decode(ProgressSnapshot.self, from: data)
        #expect(decoded.hrMaxManual == nil)
        #expect(decoded.restingHRManual == nil)
        #expect(decoded.hrZoneMethodRaw == nil)

        // 스냅샷이 단일 원본 — 로컬에 남은 수동값 180은 지워져 추정값으로 돌아간다
        let defaults = Self.freshDefaults("legacyHeartRate")
        defaults.set(180, forKey: ProfileKey.hrMaxManual)
        decoded.apply(to: defaults)
        #expect(defaults.integer(forKey: ProfileKey.hrMaxManual) == 0)
    }

    @Test("주간 목표 변경 이력 왕복 — apply→readLocal로 전부 보존되고, 이력 없음은 nil·#108 이전 JSON도 디코드된다")
    func weeklyGoalChangeRoundTrip() throws {
        let defaults = Self.freshDefaults("weeklyGoalChange")
        let history = [WeeklyGoalChange(at: Self.date("2026-07-16T00:00:00Z"), before: 3),
                       WeeklyGoalChange(at: Self.date("2026-08-12T00:00:00Z"), before: 1)]
        var snapshot = Self.makeSnapshot()
        snapshot.weeklyGoalChanges = history
        snapshot.apply(to: defaults)

        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.weeklyGoalChanges == history)
        let reencoded = try JSONDecoder().decode(ProgressSnapshot.self, from: JSONEncoder().encode(snapshot))
        #expect(reencoded.weeklyGoalChanges == history)

        // nil 옵셔널은 키 자체가 빠진다 — 이슈 #108 이전 본과 같은 JSON이 nil로 디코드된다
        let data = try JSONEncoder().encode(Self.makeSnapshot())
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["weeklyGoalChanges"] == nil)
        let decoded = try JSONDecoder().decode(ProgressSnapshot.self, from: data)
        #expect(decoded.weeklyGoalChanges == nil)

        // 스냅샷이 단일 원본 — 이력 없는 본을 적용하면 로컬 이력도 지워져 readLocal이 nil로 읽는다
        decoded.apply(to: defaults)
        let readCleared = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(readCleared.weeklyGoalChanges == nil)
    }

    // MARK: - 병합: 주간 목표 변경 이력 합집합 (이슈 #126)
    // 시각은 모두 주 중간(화~목) 12:00Z — 어느 시간대에서 돌려도 ISO 주 경계(월 00:00)를 넘지 않는다

    @Test("목표 변경 이력 합집합 — 서로 다른 주의 이력은 모두 남고 시각 오름차순, 완전히 같은 항목은 하나로")
    func weeklyGoalChangesUnionAcrossWeeks() throws {
        let july = WeeklyGoalChange(at: Self.date("2026-07-15T12:00:00Z"), before: 3)   // 수요일
        let shared = WeeklyGoalChange(at: Self.date("2026-07-29T12:00:00Z"), before: 4) // 양쪽에 동기화돼 있던 항목
        let august = WeeklyGoalChange(at: Self.date("2026-08-12T12:00:00Z"), before: 5)

        let union = ProgressMergeEngine.unionWeeklyGoalChanges([august, shared], [shared, july])
        // 세 주가 모두 다르다 → 3건, 7/15 → 7/29 → 8/12 순. shared는 한 번만
        #expect(union == [july, shared, august])
    }

    @Test("목표 변경 이력 합집합 — 같은 ISO 주에 두 기기가 각각 바꿨으면 먼저 바꾼 1건만 남긴다")
    func weeklyGoalChangesUnionKeepsEarliestInWeek() throws {
        // 8/4(화)·8/6(목)은 같은 주(8/3 월요일 시작) — 그 주 판정은 먼저 바꾸기 직전 목표(3)로 해야 한다
        let earlier = WeeklyGoalChange(at: Self.date("2026-08-04T12:00:00Z"), before: 3)
        let later = WeeklyGoalChange(at: Self.date("2026-08-06T12:00:00Z"), before: 4)

        #expect(ProgressMergeEngine.unionWeeklyGoalChanges([later], [earlier]) == [earlier])
        #expect(ProgressMergeEngine.unionWeeklyGoalChanges([earlier], [later]) == [earlier])
    }

    @Test("목표 변경 이력 병합 — 다른 사이클의 서버 본이 통째로 이겨도 로컬 이력이 합쳐져 남고, 양쪽 모두 없으면 nil")
    func mergeUnionsWeeklyGoalChangesEvenWhenServerWins() throws {
        let localCycle = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
        let serverCycle = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!
        let localChange = WeeklyGoalChange(at: Self.date("2026-07-15T12:00:00Z"), before: 3)
        let serverChange = WeeklyGoalChange(at: Self.date("2026-08-12T12:00:00Z"), before: 5)
        var local = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"), cycleID: localCycle)
        local.weeklyGoalChanges = [localChange]
        var server = Self.makeSnapshot(updatedAt: Self.date("2026-08-10T09:00:00Z"), cycleID: serverCycle)
        server.weeklyGoalChanges = [serverChange]

        guard case .upload(let merged) = ProgressMergeEngine.merge(local: local, server: server) else {
            Issue.record("upload여야 한다")
            return
        }
        // 서버(8/10)가 최신 → 사이클은 서버 것. 이력은 로컬 7/15 + 서버 8/12 합집합
        #expect(merged.cycleID == serverCycle)
        #expect(merged.weeklyGoalChanges == [localChange, serverChange])

        // 양쪽 모두 이력 없음 → nil 유지 (빈 배열을 쓰지 않는다)
        guard case .upload(let mergedEmpty) = ProgressMergeEngine.merge(local: Self.makeSnapshot(),
                                                                        server: Self.makeSnapshot()) else {
            Issue.record("upload여야 한다")
            return
        }
        #expect(mergedEmpty.weeklyGoalChanges == nil)
    }

    @Test("목표 변경 이력 병합 반영 — 기다리는 사이 로컬에서 바꾼 이력과 병합본 이력이 합집합으로 남는다")
    func localApplyingUnionsWeeklyGoalChanges() throws {
        let serverChange = WeeklyGoalChange(at: Self.date("2026-07-15T12:00:00Z"), before: 3)
        let localChange = WeeklyGoalChange(at: Self.date("2026-08-12T12:00:00Z"), before: 4)
        let start = Self.makeSnapshot()
        var merged = Self.makeSnapshot()
        merged.weeklyGoalChanges = [serverChange]
        var current = Self.makeSnapshot()
        current.weeklyGoalChanges = [localChange]

        let applied = ProgressMergeEngine.localApplying(merged: merged, start: start, current: current)
        // 필드 단위 채택이면 현재 로컬([8/12])만 남았다 — 합집합이라 7/15도 남는다
        #expect(applied.weeklyGoalChanges == [serverChange, localChange])
    }

    @Test("#108 스냅샷 흡수 — weeklyGoalChangedAt·weeklyGoalBefore만 있는 옛 JSON은 1건짜리 이력으로 디코드되고, 다시 인코드하면 새 필드만 쓴다 (이슈 #116)")
    func legacyWeeklyGoalChangeSnapshotDecodes() throws {
        // #108 인코더와 같은 모양 — Date는 JSONEncoder 기본 전략(2001 기준 초)으로 적힌다
        let changedAt = Self.date("2026-08-12T00:00:00Z")
        var object = try #require(try JSONSerialization.jsonObject(
            with: JSONEncoder().encode(Self.makeSnapshot())) as? [String: Any])
        object["weeklyGoalChangedAt"] = changedAt.timeIntervalSinceReferenceDate
        object["weeklyGoalBefore"] = 3
        let legacyData = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(ProgressSnapshot.self, from: legacyData)
        #expect(decoded.weeklyGoalChanges == [WeeklyGoalChange(at: changedAt, before: 3)])

        let reencoded = try #require(try JSONSerialization.jsonObject(
            with: JSONEncoder().encode(decoded)) as? [String: Any])
        #expect(reencoded["weeklyGoalChangedAt"] == nil)
        #expect(reencoded["weeklyGoalBefore"] == nil)
        #expect(reencoded["weeklyGoalChanges"] != nil)
    }

    @Test("옛 두 키 이관 — #108의 변경 시각·이전 목표 키는 첫 읽기 때 1건짜리 이력으로 옮겨지고 지워진다 (이슈 #116)")
    func legacyWeeklyGoalKeysMigrate() {
        let defaults = Self.freshDefaults("legacyWeeklyGoalKeys")
        let changedAt = Self.date("2026-08-12T00:00:00Z")
        defaults.set(changedAt.timeIntervalSince1970, forKey: WeeklyGoalChangeLog.legacyChangedAtKey)
        defaults.set(3, forKey: WeeklyGoalChangeLog.legacyBeforeKey)

        #expect(WeeklyGoalChangeLog.load(defaults: defaults) == [WeeklyGoalChange(at: changedAt, before: 3)])
        // 옛 키는 지워지고 새 키 하나에 남는다 — 다시 읽어도 같은 이력
        #expect(defaults.object(forKey: WeeklyGoalChangeLog.legacyChangedAtKey) == nil)
        #expect(defaults.object(forKey: WeeklyGoalChangeLog.legacyBeforeKey) == nil)
        #expect(defaults.data(forKey: ProfileKey.weeklyGoalChanges) != nil)
        #expect(WeeklyGoalChangeLog.load(defaults: defaults) == [WeeklyGoalChange(at: changedAt, before: 3)])

        // 아무 기록도 없으면 빈 이력이고 새 키를 만들지 않는다
        let empty = Self.freshDefaults("noWeeklyGoalKeys")
        #expect(WeeklyGoalChangeLog.load(defaults: empty).isEmpty)
        #expect(empty.data(forKey: ProfileKey.weeklyGoalChanges) == nil)
    }

    @Test("사이클 목표 왕복 — 설정 목표와 다른 사이클 목표가 인코딩·디코딩과 apply→readLocal로 보존된다 (이슈 #110)")
    func cycleGoalRoundTrip() throws {
        // 설정 목표는 풀 4:00:00(makeSnapshot), 사이클 목표는 하프 1:45:00(6_300초) — 중간에 목표를 바꾼 상황
        var snapshot = Self.makeSnapshot()
        snapshot.cycleGoalRaw = "half"
        snapshot.cycleGoalSeconds = 6_300

        let decoded = try JSONDecoder().decode(ProgressSnapshot.self,
                                               from: JSONEncoder().encode(snapshot))
        #expect(decoded == snapshot)
        #expect(decoded.cycleGoalRaw == "half")
        #expect(decoded.cycleGoalSeconds == 6_300)

        let defaults = Self.freshDefaults("cycleGoal")
        decoded.apply(to: defaults)
        #expect(defaults.string(forKey: GrowthKey.cycleGoal) == "half")
        #expect(defaults.integer(forKey: GrowthKey.cycleGoalSec) == 6_300)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.cycleGoalRaw == "half")
        #expect(read.cycleGoalSeconds == 6_300)
        #expect(read.raceGoalRaw == "full")
    }

    @Test("사이클 목표 — 목표 없음(빈 문자열·0초)은 nil이 아니라 유효한 값으로 왕복한다")
    func cycleGoalNoneRoundTrip() throws {
        var snapshot = Self.makeSnapshot()
        snapshot.cycleGoalRaw = ""
        snapshot.cycleGoalSeconds = 0

        let defaults = Self.freshDefaults("cycleGoalNone")
        snapshot.apply(to: defaults)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.cycleGoalRaw == "")
        #expect(read.cycleGoalSeconds == 0)
    }

    @Test("옛 스냅샷 호환 — 사이클 목표 필드가 없는 JSON은 nil로 디코드되고, 적용하면 raceGoal로 대체한다 (이슈 #110)")
    func legacySnapshotWithoutCycleGoal() throws {
        // makeSnapshot의 사이클 목표는 nil → synthesized 인코딩에서 키가 빠진다 — 이슈 #110 이전 본과 같은 JSON
        let data = try JSONEncoder().encode(Self.makeSnapshot())
        let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["cycleGoalRaw"] == nil)
        #expect(object["cycleGoalSeconds"] == nil)

        let decoded = try JSONDecoder().decode(ProgressSnapshot.self, from: data)
        #expect(decoded.cycleGoalRaw == nil)
        #expect(decoded.cycleGoalSeconds == nil)

        // 복원: 로컬에 남은 다른 사이클 목표(10K) 대신 스냅샷의 raceGoal(풀 4:00:00)로 채운다
        let defaults = Self.freshDefaults("legacyCycleGoal")
        defaults.set("tenK", forKey: GrowthKey.cycleGoal)
        defaults.set(3_000, forKey: GrowthKey.cycleGoalSec)
        decoded.apply(to: defaults)
        #expect(defaults.string(forKey: GrowthKey.cycleGoal) == "full")
        #expect(defaults.integer(forKey: GrowthKey.cycleGoalSec) == 4 * 3_600)
    }

    @Test("readLocal — 사이클 목표 키가 없는 설치(도입 전)는 nil로 읽힌다")
    func readLocalNilCycleGoalWithoutKey() throws {
        let defaults = Self.freshDefaults("cycleGoalMissing")
        defaults.set("intermediate", forKey: ProfileKey.levelV2)
        defaults.set("full", forKey: ProfileKey.raceGoal)

        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.cycleGoalRaw == nil)
        #expect(read.cycleGoalSeconds == nil)
    }

    // MARK: - 복원 선택 판정 (이슈 #44)

    @Test("복원 선택 — 동기화 이력 없는 설치가 서버의 다른 사이클 본을 만나면 묻는다")
    func differentCycleNeedsChoice() throws {
        // 복원이 일시 실패한 뒤 새 온보딩으로 사이클 B를 연 설치. readLocal은 updatedAt을 now(9/29)로 채운다
        let defaults = Self.freshDefaults("choiceDifferentCycle")
        Self.makeSnapshot(cycleID: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!,
                          maxStage: 1).apply(to: defaults)
        let local = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-09-29T09:00:00Z")))
        // 서버: 이전 설치의 사이클 A, 4단계, 8/5 백업
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"), maxStage: 4)

        // 이 판정이 없으면 merge는 "다른 사이클은 최신이 통째로 이긴다" 규칙으로
        // 로컬 B(9/29)를 택해 서버의 4단계를 조용히 덮는다 — 그래서 첫 업로드 전에 묻는다
        #expect(ProgressMergeEngine.needsRestoreChoice(local: local, server: server,
                                                       hasSyncedBefore: false))
    }

    @Test("복원 선택 — 서버 본이 없으면 묻지 않는다 (진짜 신규)")
    func noServerNoChoice() {
        let local = Self.makeSnapshot()
        #expect(!ProgressMergeEngine.needsRestoreChoice(local: local, server: nil,
                                                        hasSyncedBefore: false))
    }

    @Test("복원 선택 — 같은 사이클이면 묻지 않는다 (maxStage 최댓값 병합이 지켜 준다)")
    func sameCycleNoChoice() {
        let local = Self.makeSnapshot(updatedAt: Self.date("2026-09-29T09:00:00Z"), maxStage: 2)
        let server = Self.makeSnapshot(updatedAt: Self.date("2026-08-05T09:00:00Z"), maxStage: 4)
        #expect(!ProgressMergeEngine.needsRestoreChoice(local: local, server: server,
                                                        hasSyncedBefore: false))
    }

    @Test("복원 선택 — 미래 스키마 서버 본은 불러올 수 없으니 묻지 않고, 병합이 keepServer로 보호한다")
    func futureSchemaNoChoice() {
        let local = Self.makeSnapshot(cycleID: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!)
        let server = Self.makeSnapshot(schemaVersion: ProgressSnapshot.currentSchemaVersion + 1)
        #expect(!ProgressMergeEngine.needsRestoreChoice(local: local, server: server,
                                                        hasSyncedBefore: false))
        #expect(ProgressMergeEngine.merge(local: local, server: server) == .keepServer)
    }

    @Test("복원 선택 — 이미 서버와 동기화한 설치는 묻지 않는다 (사이클 전환은 기존 병합 규칙)")
    func syncedInstallNoChoice() {
        let local = Self.makeSnapshot(cycleID: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!)
        let server = Self.makeSnapshot()
        #expect(!ProgressMergeEngine.needsRestoreChoice(local: local, server: server,
                                                        hasSyncedBefore: true))
    }

    @Test("불러오기 — 온보딩 값 위에 서버 본을 적용하면 서버의 사이클 식별자·단계·시작 시각이 남는다")
    func acceptAppliesServerCycle() throws {
        let defaults = Self.freshDefaults("acceptServer")
        // 방금 마친 온보딩: 사이클 B, 알 단계, 9/29 시작
        var onboarding = Self.makeSnapshot(cycleID: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!,
                                           levelRaw: "beginner", maxStage: 1)
        onboarding.cycleStartedAt = Self.date("2026-09-29T09:00:00Z")
        onboarding.apply(to: defaults)
        // 서버: 사이클 A, 4단계, 7/1 시작 (makeSnapshot 기본값)
        let server = Self.makeSnapshot(maxStage: 4)

        server.apply(to: defaults)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], raceRecords: [], now: Self.date("2026-09-29T10:00:00Z")))

        #expect(read.cycleID == server.cycleID)
        #expect(read.maxStage == 4)
        #expect(read.cycleStartedAt == Self.date("2026-07-01T00:00:00Z"))
        #expect(read.levelRaw == "intermediate")
    }

    @Test("ensureCycleID — 없으면 만들어 저장하고, 있으면 같은 값을 돌려준다")
    func ensureCycleIDIsStable() throws {
        let defaults = Self.freshDefaults("cycleID")
        // 최초 호출: 생성 + 저장 (기존 사용자 마이그레이션 경로)
        let first = ProgressSnapshot.ensureCycleID(defaults: defaults)
        // 두 번째 호출: 저장된 값을 그대로 — 부를 때마다 바뀌면 사이클 병합이 깨진다
        let second = ProgressSnapshot.ensureCycleID(defaults: defaults)
        #expect(first == second)
        #expect(defaults.string(forKey: GrowthKey.cycleID) == first.uuidString)
    }

    // MARK: - 업로드 안전성 (이슈 #128)

    @Test("업로드 보류 판정 — 서버 스키마가 더 크면 디코드 전에 보류하고, 같거나 작거나 필드가 없으면 보류하지 않는다")
    func holdUploadForNewerSchema() throws {
        let current = ProgressSnapshot.currentSchemaVersion
        #expect(ProgressMergeEngine.shouldHoldUpload(serverSchemaVersion: current + 1))
        #expect(!ProgressMergeEngine.shouldHoldUpload(serverSchemaVersion: current))
        #expect(!ProgressMergeEngine.shouldHoldUpload(serverSchemaVersion: current - 1))
        // 필드 없음은 보류 사유가 아니다 — payload 디코드 성패가 가른다
        #expect(!ProgressMergeEngine.shouldHoldUpload(serverSchemaVersion: nil))
    }

    @Test("도감 관대 디코드 — 모르는 종이 섞인 JSON도 스냅샷은 디코드되고 나머지 새는 살아남으며, 버린 수를 셀 수 있다")
    func lenientBirdDecoding() throws {
        let known = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!
        let snapshot = Self.makeSnapshot(birds: [
            Self.makeBird(id: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000001")!,
                          collectedAt: Self.date("2026-05-01T00:00:00Z")),
            Self.makeBird(id: known, collectedAt: Self.date("2026-06-01T00:00:00Z")),
        ])
        let original = try JSONEncoder().encode(snapshot)
        // 정상 JSON은 버리는 새가 없다
        #expect(ProgressSnapshot.undecodableBirdCount(in: original) == 0)

        // 첫 번째 새를 미래 버전의 종("phoenix")으로 바꾼다
        var object = try #require(try JSONSerialization.jsonObject(with: original) as? [String: Any])
        var birds = try #require(object["collectedBirds"] as? [[String: Any]])
        birds[0]["species"] = "phoenix"
        object["collectedBirds"] = birds
        let tampered = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(ProgressSnapshot.self, from: tampered)
        #expect(decoded.collectedBirds.map(\.id) == [known])
        #expect(decoded.levelRaw == snapshot.levelRaw)
        // 업로드는 이 수가 0보다 크면 보류한다 — 버린 새가 서버에서 지워지지 않게
        #expect(ProgressSnapshot.undecodableBirdCount(in: tampered) == 1)
    }

    @Test("병합 반영 — 기다리는 사이 바뀐 로컬 필드는 유지하고, 안 바뀐 필드·도감·maxStage는 병합 결과를 쓴다")
    func localApplyingKeepsChangesMadeDuringUpload() throws {
        let birdA = Self.makeBird(id: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000001")!,
                                  collectedAt: Self.date("2026-05-01T00:00:00Z"))
        let birdB = Self.makeBird(id: UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!,
                                  collectedAt: Self.date("2026-06-01T00:00:00Z"))
        let recordServer = Self.makeRecord(id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000001")!,
                                           date: Self.date("2026-04-01T00:00:00Z"))
        let recordNew = Self.makeRecord(id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000002")!,
                                        date: Self.date("2026-09-01T00:00:00Z"))
        // 업로드 시작 시점 로컬: 주 3회, 단계 2, 도감 A
        let start = Self.makeSnapshot(weeklyGoal: 3, maxStage: 2, birds: [birdA], raceRecords: [])
        // 병합본: 서버가 이겨 레벨 advanced·주 4회, 단계 4, 도감 A+B, 서버 대회 기록
        let merged = Self.makeSnapshot(levelRaw: "advanced", weeklyGoal: 4, maxStage: 4,
                                       birds: [birdA, birdB], raceRecords: [recordServer])
        // 기다리는 사이: 주간 목표를 5로 바꾸고 새 대회 기록을 입력했다
        let current = Self.makeSnapshot(weeklyGoal: 5, maxStage: 2, birds: [birdA],
                                        raceRecords: [recordNew])

        let applied = ProgressMergeEngine.localApplying(merged: merged, start: start, current: current)
        #expect(applied.weeklyGoal == 5)               // 로컬에서 바뀐 필드 → 현재 로컬
        #expect(applied.levelRaw == "advanced")        // 로컬에서 안 바뀐 필드 → 병합본
        #expect(applied.maxStage == 4)                 // 같은 사이클 → max(4, 2)
        #expect(applied.collectedBirds.map(\.id) == [birdA.id, birdB.id])
        // 대회 기록은 병합본 ∪ 현재 로컬, 최신순 — 기다리는 사이 입력한 기록도 남는다
        #expect(applied.raceRecords?.map(\.id) == [recordNew.id, recordServer.id])
    }

    @Test("병합 반영 — 기다리는 사이 사이클이 바뀌면 새 사이클과 그 단계를 지키고, 지운 대회 기록은 되살리지 않는다")
    func localApplyingKeepsNewCycleAndDeletions() throws {
        let recordServer = Self.makeRecord(id: UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000001")!,
                                           date: Self.date("2026-04-01T00:00:00Z"))
        let newCycle = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000002")!
        let start = Self.makeSnapshot(maxStage: 2, raceRecords: [recordServer])
        let merged = Self.makeSnapshot(maxStage: 4, raceRecords: [recordServer])
        // 기다리는 사이 새를 수집해 새 사이클(단계 0)로 넘어갔고, 대회 기록을 지웠다
        let current = Self.makeSnapshot(cycleID: newCycle, maxStage: 0, raceRecords: [],
                                        deletedRaceRecordIDs: [recordServer.id])

        let applied = ProgressMergeEngine.localApplying(merged: merged, start: start, current: current)
        #expect(applied.cycleID == newCycle)
        // 옛 사이클의 단계 4를 새 사이클에 얹지 않는다 — 새 사이클의 현재 값 0
        #expect(applied.maxStage == 0)
        #expect(applied.raceRecords == [])
        #expect(applied.deletedRaceRecordIDs == [recordServer.id])
    }
}
