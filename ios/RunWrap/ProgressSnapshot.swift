import Foundation

/// 진행도 스냅샷 — 앱 삭제·재설치 후 성장 상태를 복원하기 위한 단일 원본 (이슈 #29).
///
/// XP 숫자는 넣지 않는다 — 복원된 `cycleStartedAt`과 HealthKit 이력으로
/// `GrowthEngine`이 결정론적으로 재계산한다(기획서 §5의 "XP 원장을 저장하지 않는다").
/// 여기 담는 것은 재계산이 불가능한 값들뿐이다: 프로필·사이클 경계·최고 단계·도감.
/// 저장처는 사용자의 CloudKit private database(`ProgressBackupStore`)이고,
/// 이 파일은 Foundation만 알아 순수 로직으로 테스트한다.
struct ProgressSnapshot: Codable, Equatable {
    /// 현재 스키마 버전 — 필드가 바뀌면 올리고, 병합·복원은 이 값 이하만 받는다
    static let currentSchemaVersion = 1

    var schemaVersion: Int
    /// 업로드마다 1씩 오르는 단조 증가 값 — 충돌 감지·병합의 기준
    var revision: Int
    var updatedAt: Date
    /// 성장 사이클 식별자 — 같은 사이클끼리만 "maxStage는 내려가지 않는다" 병합을 적용한다
    var cycleID: UUID

    var levelRaw: String
    var purposesRaw: String
    var weeklyGoal: Int
    var onboardedAt: Date

    var cycleStartedAt: Date
    var maxStage: Int

    var raceGoalRaw: String
    var raceGoalSeconds: Int
    var raceDate: Date?
    var collectedBirds: [CollectedBird]

    /// 내용이 같은지 — 동기화 메타(revision·updatedAt)만 다른 스냅샷은 다시 올릴 필요가 없다
    func hasSameContent(as other: ProgressSnapshot) -> Bool {
        var lhs = self
        var rhs = other
        lhs.revision = 0
        rhs.revision = 0
        lhs.updatedAt = .distantPast
        rhs.updatedAt = .distantPast
        return lhs == rhs
    }

    // MARK: - 로컬 상태 읽기/쓰기 (UserDefaults + 도감 배열)

    /// 현재 로컬 상태를 스냅샷으로 접는다. 온보딩 전(레벨 없음)이면 nil —
    /// 백업할 진행도 자체가 없다. revision은 동기화 메타라 스토어가 채운다(여기서는 0).
    static func readLocal(defaults: UserDefaults, birds: [CollectedBird],
                          now: Date) -> ProgressSnapshot? {
        guard let levelRaw = defaults.string(forKey: ProfileKey.levelV2),
              !levelRaw.isEmpty else { return nil }
        let raceDateRaw = defaults.double(forKey: ProfileKey.raceDate)
        return ProgressSnapshot(
            schemaVersion: currentSchemaVersion,
            revision: 0,
            updatedAt: now,
            cycleID: ensureCycleID(defaults: defaults),
            levelRaw: levelRaw,
            purposesRaw: defaults.string(forKey: ProfileKey.purposes) ?? "",
            weeklyGoal: defaults.integer(forKey: ProfileKey.weeklyGoal),
            onboardedAt: Date(timeIntervalSince1970: defaults.double(forKey: ProfileKey.onboardedAt)),
            cycleStartedAt: Date(timeIntervalSince1970: defaults.double(forKey: GrowthKey.cycleStartedAt)),
            maxStage: defaults.integer(forKey: GrowthKey.maxStage),
            raceGoalRaw: defaults.string(forKey: ProfileKey.raceGoal) ?? "",
            raceGoalSeconds: defaults.integer(forKey: ProfileKey.raceGoalSec),
            raceDate: raceDateRaw > 0 ? Date(timeIntervalSince1970: raceDateRaw) : nil,
            collectedBirds: birds)
    }

    /// 스냅샷을 로컬 저장값에 적용한다 — 신규 설치 복원 경로.
    /// 도감 파일 쓰기는 호출부(스토어) 몫이다: 이 함수는 UserDefaults만 알아 테스트가 쉽다.
    func apply(to defaults: UserDefaults) {
        defaults.set(levelRaw, forKey: ProfileKey.levelV2)
        defaults.set(purposesRaw, forKey: ProfileKey.purposes)
        defaults.set(weeklyGoal, forKey: ProfileKey.weeklyGoal)
        defaults.set(onboardedAt.timeIntervalSince1970, forKey: ProfileKey.onboardedAt)
        defaults.set(cycleStartedAt.timeIntervalSince1970, forKey: GrowthKey.cycleStartedAt)
        defaults.set(maxStage, forKey: GrowthKey.maxStage)
        defaults.set(cycleID.uuidString, forKey: GrowthKey.cycleID)
        defaults.set(raceGoalRaw, forKey: ProfileKey.raceGoal)
        defaults.set(raceGoalSeconds, forKey: ProfileKey.raceGoalSec)
        defaults.set(raceDate?.timeIntervalSince1970 ?? 0, forKey: ProfileKey.raceDate)
    }

    /// 사이클 식별자를 읽고, 없으면 만들어 저장한다 — 이 기능 도입 전 사용자의
    /// 기존 사이클에 식별자를 최초 1회 부여하는 마이그레이션이기도 하다.
    static func ensureCycleID(defaults: UserDefaults) -> UUID {
        if let raw = defaults.string(forKey: GrowthKey.cycleID),
           let id = UUID(uuidString: raw) {
            return id
        }
        let id = UUID()
        defaults.set(id.uuidString, forKey: GrowthKey.cycleID)
        return id
    }
}

/// 업로드 직전 서버 최신본과의 병합 결과
enum ProgressMergeResult: Equatable {
    /// 병합된 스냅샷을 업로드한다 (내용이 로컬과 다르면 로컬에도 반영해야 한다)
    case upload(ProgressSnapshot)
    /// 서버 본이 더 새 스키마다 — 이해할 수 없는 것을 덮어쓰지 않고 보류한다
    case keepServer
}

/// 스냅샷 병합·복원 판정 — 순수 로직 (이슈 #29 동기화/충돌 원칙).
///
/// 원칙: 오래된 스냅샷이 최신 진행도를 되돌리면 안 된다. 같은 사이클에서는
/// `maxStage`가 낮아지는 병합을 허용하지 않고, 도감은 이력이라 항상 합집합이다.
enum ProgressMergeEngine {
    /// 이 스냅샷을 현재 앱이 적용할 수 있는지 — 미래 스키마는 필드 의미를 모르니 거부한다
    static func canRestore(_ snapshot: ProgressSnapshot) -> Bool {
        snapshot.schemaVersion <= ProgressSnapshot.currentSchemaVersion
            && !snapshot.levelRaw.isEmpty
    }

    /// 로컬 스냅샷을 올리려는데 서버에 다른 본이 있을 때의 병합.
    ///
    /// - 같은 cycleID: `updatedAt`이 최신인 쪽의 값을 쓰되 `maxStage`는 둘 중 최댓값 —
    ///   "성장은 되돌리지 않는다"(§5)를 기기 간에도 지킨다.
    /// - 다른 cycleID: 사이클 전환은 원자적 사건이라 최신 `updatedAt` 쪽이 통째로 이긴다.
    /// - 도감: 어느 경우든 합집합 — 수집 이력은 잃을 이유가 없다.
    static func merge(local: ProgressSnapshot, server: ProgressSnapshot) -> ProgressMergeResult {
        guard server.schemaVersion <= ProgressSnapshot.currentSchemaVersion else {
            return .keepServer
        }
        var merged = local.updatedAt >= server.updatedAt ? local : server
        if local.cycleID == server.cycleID {
            merged.maxStage = max(local.maxStage, server.maxStage)
        }
        merged.collectedBirds = unionBirds(local.collectedBirds, server.collectedBirds)
        merged.schemaVersion = ProgressSnapshot.currentSchemaVersion
        // 서버 revision보다 커야 이 병합이 최신본으로 남는다 — 단조 증가 보장
        merged.revision = max(local.revision, server.revision) + 1
        merged.updatedAt = max(local.updatedAt, server.updatedAt)
        return .upload(merged)
    }

    /// 도감 합집합 — id 기준 중복 제거, 수집일 오래된 순(CollectionStore의 저장 순서와 동일)
    static func unionBirds(_ lhs: [CollectedBird], _ rhs: [CollectedBird]) -> [CollectedBird] {
        var seen = Set<UUID>()
        return (lhs + rhs)
            .filter { seen.insert($0.id).inserted }
            .sorted { $0.collectedAt < $1.collectedAt }
    }
}
