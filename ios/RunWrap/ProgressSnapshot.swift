import Foundation

/// 진행도 스냅샷 — 앱 삭제·재설치 후 성장 상태를 복원하기 위한 단일 원본 (이슈 #29).
///
/// XP 숫자는 넣지 않는다 — 복원된 `cycleStartedAt`과 HealthKit 이력으로
/// `GrowthEngine`이 결정론적으로 재계산한다(기획서 §5의 "XP 원장을 저장하지 않는다").
/// 여기 담는 것은 재계산이 불가능한 값들뿐이다: 프로필·사이클 경계·최고 단계·도감·직접 입력한 대회 기록.
/// 저장처는 사용자의 CloudKit private database(`ProgressBackupStore`)이고,
/// 이 파일은 Foundation만 알아 순수 로직으로 테스트한다.
struct ProgressSnapshot: Codable, Equatable {
    /// 현재 스키마 버전 — 필드가 바뀌면 올리고, 병합·복원은 이 값 이하만 받는다.
    /// 심박 기준 필드(이슈 #56)·주간 목표 변경 이력(이슈 #108, #116)·사이클 목표 필드(이슈 #110)·대회 기록(이슈 #118)은 옵셔널 추가라 1로 둔다 — 옛 디코더는 모르는 키를 무시하고,
    /// 올리면 구버전 기기가 keepServer로 백업 자체를 멈춘다
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

    /// 심박 기준 (이슈 #56) — nil = 미설정. 이 필드가 없던 옛 스냅샷도 decodeIfPresent로
    /// 디코드되며 미설정으로 읽힌다. 옵셔널 var라 memberwise init 기본값이 nil이다
    var hrMaxManual: Int?
    var restingHRManual: Int?
    var hrZoneMethodRaw: String?

    /// 주간 목표 변경 이력 (이슈 #108, #116) — nil = 변경 없음. 복원 후에도 "바뀐 목표는 다음 주부터"가
    /// 유지되도록 함께 백업한다. #108의 옛 두 필드(weeklyGoalChangedAt·weeklyGoalBefore)만 있는 스냅샷은
    /// 디코드 때 1건짜리 이력으로 흡수하고, 인코드는 이 필드만 쓴다
    var weeklyGoalChanges: [WeeklyGoalChange]?
    /// 사이클 시작 때 고정한 목표 (이슈 #110) — 새 종류 판정용. nil = 이 필드가 없던 옛 스냅샷이거나
    /// 로컬에 아직 사이클 목표 키가 없는 설치. 복원하면 raceGoal로 대체한다. #56 심박 필드와 같은 방식
    var cycleGoalRaw: String?
    var cycleGoalSeconds: Int?
    /// 직접 입력한 대회 기록 (이슈 #118) — HealthKit 건강 데이터가 아닌 사용자 입력값이라 함께 백업한다.
    /// nil = 이 필드가 없던 옛 스냅샷. readLocal은 항상 배열(빈 배열 포함)로 채운다. #56 심박 필드와 같은 방식
    var raceRecords: [RaceRecord]?
    /// 지운 대회 기록의 id(삭제 표식, 이슈 #118) — 합집합 병합은 삭제를 전파하지 못해 한 기기에서 지운
    /// 기록이 서버 본에서 되살아난다. 지운 id를 함께 백업해 `unionRaceRecords`가 걸러 낸다. nil = 옛 스냅샷
    var deletedRaceRecordIDs: [UUID]?

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

    // MARK: - 로컬 상태 읽기/쓰기 (UserDefaults + 도감·대회 기록 배열)

    /// 현재 로컬 상태를 스냅샷으로 접는다. 온보딩 전(레벨 없음)이면 nil —
    /// 백업할 진행도 자체가 없다. revision은 동기화 메타라 스토어가 채운다(여기서는 0).
    /// `updatedAt`은 로컬 변경 시각(`GrowthKey.localChangedAt`)이다 — 병합이 "최신 변경"을 가리게 (이슈 #130).
    /// 키가 없으면(이 키 도입 전 설치의 첫 백업) `now`로 대신한다
    static func readLocal(defaults: UserDefaults, birds: [CollectedBird],
                          raceRecords: [RaceRecord], deletedRaceRecordIDs: [UUID] = [],
                          now: Date) -> ProgressSnapshot? {
        guard let levelRaw = defaults.string(forKey: ProfileKey.levelV2),
              !levelRaw.isEmpty else { return nil }
        let raceDateRaw = defaults.double(forKey: ProfileKey.raceDate)
        let hrMaxManual = defaults.integer(forKey: ProfileKey.hrMaxManual)
        let restingHRManual = defaults.integer(forKey: ProfileKey.restingHRManual)
        let hrZoneMethodRaw = defaults.string(forKey: ProfileKey.hrZoneMethod) ?? ""
        let weeklyGoalChanges = WeeklyGoalChangeLog.load(defaults: defaults)
        let localChangedAt = defaults.double(forKey: GrowthKey.localChangedAt)
        return ProgressSnapshot(
            schemaVersion: currentSchemaVersion,
            revision: 0,
            updatedAt: localChangedAt > 0 ? Date(timeIntervalSince1970: localChangedAt) : now,
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
            collectedBirds: birds,
            hrMaxManual: hrMaxManual > 0 ? hrMaxManual : nil,
            restingHRManual: restingHRManual > 0 ? restingHRManual : nil,
            hrZoneMethodRaw: hrZoneMethodRaw.isEmpty ? nil : hrZoneMethodRaw,
            weeklyGoalChanges: weeklyGoalChanges.isEmpty ? nil : weeklyGoalChanges,
            // 빈 문자열은 "목표 없음"이라 유효한 값 — 키가 없을 때만 nil이다
            cycleGoalRaw: defaults.string(forKey: GrowthKey.cycleGoal),
            cycleGoalSeconds: defaults.object(forKey: GrowthKey.cycleGoalSec) == nil
                ? nil : defaults.integer(forKey: GrowthKey.cycleGoalSec),
            raceRecords: raceRecords,
            deletedRaceRecordIDs: deletedRaceRecordIDs)
    }

    /// 스냅샷을 로컬 저장값에 적용한다 — 신규 설치 복원 경로.
    /// 도감·대회 기록 파일 쓰기는 호출부(스토어) 몫이다: 이 함수는 UserDefaults만 알아 테스트가 쉽다.
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
        // 사이클 목표 (이슈 #110) — 옛 스냅샷(nil)은 당시 목표를 사이클 목표로 본다
        defaults.set(cycleGoalRaw ?? raceGoalRaw, forKey: GrowthKey.cycleGoal)
        defaults.set(cycleGoalSeconds ?? raceGoalSeconds, forKey: GrowthKey.cycleGoalSec)
        defaults.set(raceDate?.timeIntervalSince1970 ?? 0, forKey: ProfileKey.raceDate)
        // 심박 기준 (이슈 #56) — 스냅샷이 단일 원본이라 nil이면 로컬 값도 지워 미설정으로 맞춘다
        if let hrMaxManual {
            defaults.set(hrMaxManual, forKey: ProfileKey.hrMaxManual)
        } else {
            defaults.removeObject(forKey: ProfileKey.hrMaxManual)
        }
        if let restingHRManual {
            defaults.set(restingHRManual, forKey: ProfileKey.restingHRManual)
        } else {
            defaults.removeObject(forKey: ProfileKey.restingHRManual)
        }
        if let hrZoneMethodRaw {
            defaults.set(hrZoneMethodRaw, forKey: ProfileKey.hrZoneMethod)
        } else {
            defaults.removeObject(forKey: ProfileKey.hrZoneMethod)
        }
        // 주간 목표 변경 이력 (이슈 #108, #116) — 심박 기준과 같이 nil이면 로컬 이력도 지운다
        WeeklyGoalChangeLog.save(weeklyGoalChanges ?? [], defaults: defaults)
        // 서버 본을 반영한 것이지 로컬 변경이 아니다 — 그 본의 변경 시각을 그대로 이어받는다 (이슈 #130)
        defaults.set(updatedAt.timeIntervalSince1970, forKey: GrowthKey.localChangedAt)
    }

    /// 백업 대상 값(프로필·사이클·도감·대회 기록·심박 기준·주간 목표 이력)이 로컬에서 바뀌었음을 기록한다 (이슈 #130).
    /// 다음 `readLocal`의 `updatedAt`이 이 시각이 되어, 병합은 업로드 시각이 아니라 실제 변경 시각으로 최신을 가린다
    static func markLocalChanged(defaults: UserDefaults, now: Date) {
        defaults.set(now.timeIntervalSince1970, forKey: GrowthKey.localChangedAt)
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

extension ProgressSnapshot {
    /// #108의 옛 주간 목표 변경 필드 — 디코드 때 흡수만 한다 (이슈 #116)
    private enum LegacyCodingKeys: String, CodingKey {
        case weeklyGoalChangedAt, weeklyGoalBefore
    }

    /// 합성 디코더와 같되, `weeklyGoalChanges`가 없고 옛 두 필드가 있으면 1건짜리 이력으로 흡수한다 (이슈 #116).
    /// 인코드는 합성 그대로라 옛 필드를 다시 쓰지 않는다
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        revision = try container.decode(Int.self, forKey: .revision)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        cycleID = try container.decode(UUID.self, forKey: .cycleID)
        levelRaw = try container.decode(String.self, forKey: .levelRaw)
        purposesRaw = try container.decode(String.self, forKey: .purposesRaw)
        weeklyGoal = try container.decode(Int.self, forKey: .weeklyGoal)
        onboardedAt = try container.decode(Date.self, forKey: .onboardedAt)
        cycleStartedAt = try container.decode(Date.self, forKey: .cycleStartedAt)
        maxStage = try container.decode(Int.self, forKey: .maxStage)
        raceGoalRaw = try container.decode(String.self, forKey: .raceGoalRaw)
        raceGoalSeconds = try container.decode(Int.self, forKey: .raceGoalSeconds)
        raceDate = try container.decodeIfPresent(Date.self, forKey: .raceDate)
        // 도감은 관대하게 읽는다 (이슈 #128) — 모르는 종·손상된 1건 때문에 스냅샷 전체가 읽히지 않으면
        // 복원이 통째로 실패한다. 버린 새가 서버에서 지워지지 않게 업로드 쪽은 `undecodableBirdCount`로 보류한다
        collectedBirds = try container.decode([LenientElement<CollectedBird>].self, forKey: .collectedBirds)
            .compactMap(\.value)
        hrMaxManual = try container.decodeIfPresent(Int.self, forKey: .hrMaxManual)
        restingHRManual = try container.decodeIfPresent(Int.self, forKey: .restingHRManual)
        hrZoneMethodRaw = try container.decodeIfPresent(String.self, forKey: .hrZoneMethodRaw)
        cycleGoalRaw = try container.decodeIfPresent(String.self, forKey: .cycleGoalRaw)
        cycleGoalSeconds = try container.decodeIfPresent(Int.self, forKey: .cycleGoalSeconds)
        raceRecords = try container.decodeIfPresent([RaceRecord].self, forKey: .raceRecords)
        deletedRaceRecordIDs = try container.decodeIfPresent([UUID].self, forKey: .deletedRaceRecordIDs)

        weeklyGoalChanges = try container.decodeIfPresent([WeeklyGoalChange].self, forKey: .weeklyGoalChanges)
        if weeklyGoalChanges == nil {
            let legacy = try decoder.container(keyedBy: LegacyCodingKeys.self)
            if let at = try legacy.decodeIfPresent(Date.self, forKey: .weeklyGoalChangedAt),
               let before = try legacy.decodeIfPresent(Int.self, forKey: .weeklyGoalBefore) {
                weeklyGoalChanges = [WeeklyGoalChange(at: at, before: before)]
            }
        }
    }

    /// 스냅샷 JSON에서 관대 디코드가 버리는 새의 수 (이슈 #128) — 0보다 크면 이 앱이 모르는 종(미래 버전)이거나
    /// 손상된 항목이 있다. 그대로 병합해 올리면 그 새가 서버에서도 사라지므로 업로드를 보류하는 판정에 쓴다.
    /// JSON 자체를 읽을 수 없으면 0 — 그 경우는 스냅샷 디코드가 먼저 실패한다
    static func undecodableBirdCount(in payload: Data) -> Int {
        struct BirdsOnly: Decodable {
            let collectedBirds: [LenientElement<CollectedBird>]
        }
        guard let probe = try? JSONDecoder().decode(BirdsOnly.self, from: payload) else { return 0 }
        return probe.collectedBirds.filter { $0.value == nil }.count
    }
}

/// 배열 원소 하나의 디코드 실패가 배열 전체 실패로 번지지 않게 감싼다 (이슈 #128).
/// 실패한 원소는 nil로 남아 호출부가 걸러 낸다 — 원소 디코더 안에서 삼켜야 배열 커서가 다음으로 넘어간다
private struct LenientElement<Element: Decodable>: Decodable {
    let value: Element?

    init(from decoder: Decoder) throws {
        value = try? Element(from: decoder)
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
    /// - 대회 기록(이슈 #118): 어느 경우든 합집합이되 양쪽의 삭제 표식에 걸린 기록은 뺀다 — `unionRaceRecords` 참고.
    /// - 주간 목표 변경 이력(이슈 #126): 어느 경우든 합집합 — 한쪽만 남기면 그 주의 보너스 판정이 달라진다.
    static func merge(local: ProgressSnapshot, server: ProgressSnapshot) -> ProgressMergeResult {
        guard server.schemaVersion <= ProgressSnapshot.currentSchemaVersion else {
            return .keepServer
        }
        var merged = local.updatedAt >= server.updatedAt ? local : server
        if local.cycleID == server.cycleID {
            merged.maxStage = max(local.maxStage, server.maxStage)
        }
        merged.collectedBirds = unionBirds(local.collectedBirds, server.collectedBirds)
        let deleted = unionDeletedIDs(local.deletedRaceRecordIDs ?? [], server.deletedRaceRecordIDs ?? [])
        merged.raceRecords = unionRaceRecords(local.raceRecords ?? [], server.raceRecords ?? [],
                                              deleted: deleted)
        merged.deletedRaceRecordIDs = deleted
        let goalChanges = unionWeeklyGoalChanges(local.weeklyGoalChanges ?? [], server.weeklyGoalChanges ?? [])
        merged.weeklyGoalChanges = goalChanges.isEmpty ? nil : goalChanges  // 이력 없음은 nil (readLocal과 같은 규칙)
        merged.schemaVersion = ProgressSnapshot.currentSchemaVersion
        // 서버 revision보다 커야 이 병합이 최신본으로 남는다 — 단조 증가 보장
        merged.revision = max(local.revision, server.revision) + 1
        merged.updatedAt = max(local.updatedAt, server.updatedAt)
        return .upload(merged)
    }

    /// 서버 레코드의 스키마 버전만 보고 업로드를 보류할지 (이슈 #128) — payload 디코드 **전에** 판정한다.
    /// 디코드되지 않는 미래 스키마는 `merge`의 keepServer까지 닿지 못해 덮어쓰이던 구멍을 막는다.
    /// nil(필드 없음)은 보류 사유가 아니다 — 디코드 성패가 가른다
    static func shouldHoldUpload(serverSchemaVersion: Int?) -> Bool {
        guard let serverSchemaVersion else { return false }
        return serverSchemaVersion > ProgressSnapshot.currentSchemaVersion
    }

    /// 업로드한 병합본을 로컬에 반영할 값 (이슈 #128).
    ///
    /// 조회·저장을 기다리는 동안 사용자가 설정을 바꿀 수 있다. 병합본을 통째로 적용하면 그 변경이
    /// 업로드 시작 시점 값으로 되돌아가므로, 다시 읽은 현재 로컬(`current`)과 시작 시점 로컬(`start`)을 비교한다.
    /// - 프로필·사이클 필드: 현재 로컬이 시작 시점과 **같을 때만** 병합본 값을 쓴다 — 바뀐 필드는 현재 로컬 유지
    /// - `maxStage`: 병합본 값. 남은 사이클이 현재 로컬과 같으면 현재 값과의 최댓값(§5 "성장은 되돌리지 않는다")
    /// - 도감·대회 기록·삭제 표식·주간 목표 변경 이력: 병합본에 현재 로컬을 합집합 — 기다리는 사이 수집·입력·삭제·변경한 것도 잃지 않는다
    static func localApplying(merged: ProgressSnapshot, start: ProgressSnapshot,
                              current: ProgressSnapshot) -> ProgressSnapshot {
        var result = merged
        func keepLocalChange<Value: Equatable>(_ field: WritableKeyPath<ProgressSnapshot, Value>) {
            if current[keyPath: field] != start[keyPath: field] {
                result[keyPath: field] = current[keyPath: field]
            }
        }
        keepLocalChange(\.cycleID)
        keepLocalChange(\.levelRaw)
        keepLocalChange(\.purposesRaw)
        keepLocalChange(\.weeklyGoal)
        keepLocalChange(\.onboardedAt)
        keepLocalChange(\.cycleStartedAt)
        keepLocalChange(\.raceGoalRaw)
        keepLocalChange(\.raceGoalSeconds)
        keepLocalChange(\.raceDate)
        keepLocalChange(\.hrMaxManual)
        keepLocalChange(\.restingHRManual)
        keepLocalChange(\.hrZoneMethodRaw)
        keepLocalChange(\.cycleGoalRaw)
        keepLocalChange(\.cycleGoalSeconds)
        if result.cycleID == current.cycleID {
            let mergedStage = merged.cycleID == current.cycleID ? merged.maxStage : 0
            result.maxStage = max(mergedStage, current.maxStage)
        }
        result.collectedBirds = unionBirds(merged.collectedBirds, current.collectedBirds)
        let deleted = unionDeletedIDs(merged.deletedRaceRecordIDs ?? [], current.deletedRaceRecordIDs ?? [])
        result.raceRecords = unionRaceRecords(merged.raceRecords ?? [], current.raceRecords ?? [],
                                              deleted: deleted)
        result.deletedRaceRecordIDs = deleted
        let goalChanges = unionWeeklyGoalChanges(merged.weeklyGoalChanges ?? [], current.weeklyGoalChanges ?? [])
        result.weeklyGoalChanges = goalChanges.isEmpty ? nil : goalChanges
        return result
    }

    /// 첫 업로드 전에 사용자에게 "이전 기록 불러오기 / 새로 시작"을 물어야 하는지 (이슈 #44).
    ///
    /// 복원이 일시 실패(네트워크·iCloud 미로그인·타임아웃)한 설치는 새 온보딩으로 새 사이클을
    /// 만들고, `readLocal`의 `updatedAt`이 방금 한 온보딩 시각(로컬 변경 시각)이므로 `merge`의 "다른 사이클은 최신이
    /// 통째로 이긴다" 규칙에서 항상 이긴다 — 서버의 이전 진행도가 조용히 사라진다.
    /// 그래서 이 설치가 **한 번도 동기화한 적 없고**(업로드·복원 이력 없음) 서버에
    /// **다른 사이클의 복원 가능한 본**이 있으면 덮어쓰기 전에 묻는다.
    ///
    /// - 동기화 이력 있음: 이미 서버와 맞춰 본 설치 — 기존 병합 규칙을 따른다
    /// - 서버 본 없음·같은 사이클: 잃을 것이 없다 (같은 사이클은 maxStage 최댓값 병합)
    /// - 적용 불가(미래 스키마·빈 레벨): 불러올 수 없으니 묻지 않는다 — 미래 스키마는
    ///   `merge`의 keepServer가 덮어쓰기를 막는다
    ///
    /// 동기화 이력으로 판정하므로 이 수정 이전에 복원이 실패한 채 업로드하지 못한 설치,
    /// 다른 기기에서 처음 올리는 기존 사용자(#29 마이그레이션)도 같은 보호를 받는다.
    static func needsRestoreChoice(local: ProgressSnapshot, server: ProgressSnapshot?,
                                   hasSyncedBefore: Bool) -> Bool {
        guard !hasSyncedBefore, let server, canRestore(server) else { return false }
        return server.cycleID != local.cycleID
    }

    /// 도감 합집합 — id 기준 중복 제거, 수집일 오래된 순(CollectionStore의 저장 순서와 동일)
    static func unionBirds(_ lhs: [CollectedBird], _ rhs: [CollectedBird]) -> [CollectedBird] {
        var seen = Set<UUID>()
        return (lhs + rhs)
            .filter { seen.insert($0.id).inserted }
            .sorted { $0.collectedAt < $1.collectedAt }
    }

    /// 대회 기록 합집합 (이슈 #118) — id 기준 중복 제거, 대회 날짜 최신순(RaceRecordStore의 저장 순서와 동일).
    ///
    /// `deleted`에 든 id는 뺀다. 합집합만으로는 삭제가 전파되지 않아, 한 기기(iCloud 사용 중인 단일 기기
    /// 포함)에서 지운 기록이 서버 본과 합쳐질 때마다 되살아났다 — 지운 id를 삭제 표식으로 함께 들고
    /// 다닌다. 표식은 사용자가 지운 횟수만큼만 늘어 크기 걱정은 없다
    static func unionRaceRecords(_ lhs: [RaceRecord], _ rhs: [RaceRecord],
                                 deleted: [UUID] = []) -> [RaceRecord] {
        var seen = Set<UUID>(deleted)
        return (lhs + rhs)
            .filter { seen.insert($0.id).inserted }
            .sorted { $0.date > $1.date }
    }

    /// 주간 목표 변경 이력 합집합 (이슈 #126) — 시각 오름차순, 같은 ISO 주에는 먼저 기록된 1건만 남긴다.
    ///
    /// `GrowthEngine.recordWeeklyGoalChange`가 한 주에 첫 변경만 남기는 규칙(그 주 판정은 변경 직전 목표로)을
    /// 기기 간에도 지킨다 — 두 기기가 같은 주에 각각 바꿨다면 먼저 바꾼 쪽의 `before`가 그 주의 원래 목표다.
    /// 주 경계도 그 함수와 같다(ISO 8601, 월요일 시작, 현재 시간대). 완전히 같은 항목은 같은 주라 하나로 합쳐진다
    static func unionWeeklyGoalChanges(_ lhs: [WeeklyGoalChange],
                                       _ rhs: [WeeklyGoalChange]) -> [WeeklyGoalChange] {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        var seenWeeks = Set<Date>()
        return (lhs + rhs)
            .sorted { $0.at < $1.at }
            .filter { change in
                guard let weekStart = calendar.dateInterval(of: .weekOfYear, for: change.at)?.start else {
                    return true
                }
                return seenWeeks.insert(weekStart).inserted
            }
    }

    /// 삭제 표식 합집합 (이슈 #118) — 순서는 의미 없지만 `hasSameContent` 비교가 안정되게 정렬한다
    static func unionDeletedIDs(_ lhs: [UUID], _ rhs: [UUID]) -> [UUID] {
        Array(Set(lhs).union(rhs)).sorted { $0.uuidString < $1.uuidString }
    }
}
