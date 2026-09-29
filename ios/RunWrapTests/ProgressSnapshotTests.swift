import Foundation
import Testing
@testable import RunWrap

/// ProgressSnapshot·ProgressMergeEngine 테스트 (이슈 #29).
///
/// 검증 축: ① 오래된 스냅샷이 최신 진행도를 되돌리지 않는다
/// ② 같은 사이클에서 maxStage는 절대 낮아지지 않는다 ③ 도감은 항상 합집합
/// ④ 미래 스키마는 건드리지 않는다 ⑤ 로컬 읽기/쓰기 왕복이 무손실이다.
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
        birds: [CollectedBird] = []
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
            collectedBirds: birds)
    }

    private static func makeBird(id: UUID, collectedAt: Date) -> CollectedBird {
        CollectedBird(id: id, species: .sparrow, goalLabel: "주 3회 습관",
                      collectedAt: collectedAt, cycleDays: 27)
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
        var original = Self.makeSnapshot(birds: [bird])
        // apply는 nil 사이클 목표를 raceGoal로 채우므로, 무손실 왕복을 보려면 값을 넣어 둔다 (이슈 #110)
        original.cycleGoalRaw = "full"
        original.cycleGoalSeconds = 4 * 3_600

        original.apply(to: defaults)
        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [bird], now: Self.date("2026-08-20T00:00:00Z")))

        // revision·updatedAt은 동기화 메타라 왕복 대상이 아니다 — 내용만 비교한다
        #expect(read.hasSameContent(as: original))
        #expect(read.cycleID == original.cycleID)
        #expect(read.raceDate == original.raceDate)
    }

    @Test("readLocal — 대회 날짜 없음(0)은 nil로 읽힌다")
    func readLocalNilRaceDate() throws {
        let defaults = Self.freshDefaults("nilRaceDate")
        var snapshot = Self.makeSnapshot()
        snapshot.raceDate = nil
        snapshot.apply(to: defaults)

        let read = try #require(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.raceDate == nil)
    }

    @Test("readLocal — 온보딩 전(레벨 없음)이면 nil, 백업할 진행도가 없다")
    func readLocalNilBeforeOnboarding() throws {
        let defaults = Self.freshDefaults("beforeOnboarding")
        #expect(ProgressSnapshot.readLocal(
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")) == nil)
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(read.hrMaxManual == 185)
        #expect(read.restingHRManual == 48)
        #expect(read.hrZoneMethodRaw == "karvonen")

        // 미설정 defaults(키 없음 → integer 0, string nil)는 nil 셋으로 읽힌다
        let unset = Self.freshDefaults("heartRateUnset")
        Self.makeSnapshot().apply(to: unset)
        let readUnset = try #require(ProgressSnapshot.readLocal(
            defaults: unset, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
        #expect(readCleared.weeklyGoalChanges == nil)
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-08-20T00:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-09-29T09:00:00Z")))
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
            defaults: defaults, birds: [], now: Self.date("2026-09-29T10:00:00Z")))

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
}
