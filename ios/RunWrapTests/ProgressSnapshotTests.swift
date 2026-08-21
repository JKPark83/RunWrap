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
        let original = Self.makeSnapshot(birds: [bird])

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
