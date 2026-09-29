import CloudKit
import Foundation
import os

/// 진행도 CloudKit 백업·복원 스토어 (이슈 #29).
///
/// 로컬(UserDefaults + 도감·대회 기록 파일)은 오프라인 캐시로 그대로 두고, 복원에 필요한
/// 상태를 사용자의 CloudKit private database에 **단일 스냅샷 레코드**로 저장한다.
/// 별도 회원가입·자체 서버는 없다. 건강 데이터(운동 목록·심박·위치)는 올리지 않는다 —
/// 스냅샷에는 성장 복원 메타데이터와 도감, 직접 입력한 대회 기록만 담는다(`ProgressSnapshot` 참고).
///
/// 원칙: iCloud 미로그인·네트워크 실패·quota 오류가 **로컬 진행도를 초기화하면 안 된다.**
/// 백업 실패는 조용히 다음 기회로 미루고, 복원 실패는 상태로만 알려 온보딩을 연다.
/// 복원을 확정하지 못한 설치가 나중에 서버의 이전 진행도를 발견하면 덮지 않고 사용자에게 묻는다 (이슈 #44).
@MainActor
final class ProgressBackupStore: ObservableObject {
    /// 신규 설치 부트스트랩의 복원 시도 결과 — RootView가 온보딩을 열지 말지 이걸로 가른다
    enum RestoreState: Equatable {
        case idle          // 시도 전 (기존 사용자는 이 상태에 머문다)
        case checking      // CloudKit에서 스냅샷 확인 중 — 온보딩을 열지 않고 기다린다
        case restored      // 스냅샷을 로컬에 적용 완료 — 온보딩 없이 홈으로 간다
        case empty         // 스냅샷 없음 — 진짜 신규 사용자, 온보딩 시작
        case unavailable   // iCloud 미로그인·제한 — 복원 불가를 안내하고 온보딩 시작
        case failed        // 네트워크 등 일시 오류 — 복원 불가를 안내하고 온보딩 시작
    }

    @Published private(set) var restoreState: RestoreState = .idle
    /// 첫 업로드 직전에 발견한 서버의 이전 진행도 — nil이 아니면 RootView가
    /// "이전 기록 불러오기 / 새로 시작" 시트로 묻는다. 답을 받기 전에는 업로드하지 않는다 (이슈 #44)
    @Published private(set) var restoreCandidate: ProgressSnapshot?
    /// 백업 병합이 서버 쪽 도감·대회 기록을 살려 로컬 파일을 바꿨을 때의 결과 — nil이 아니면 RootView가
    /// 메모리의 `CollectionStore`·`RaceRecordStore`를 교체하고 `clearMergedResult()`로 비운다 (이슈 #128).
    /// 그대로 두면 다음 수집·입력 때 메모리 배열이 파일을 덮어 되살린 항목이 다시 사라진다
    @Published private(set) var mergedResult: MergedProgress?

    /// 병합으로 바뀐 도감·대회 기록 — 스토어끼리 결합하지 않고 호출부가 반영한다
    struct MergedProgress: Equatable {
        let birds: [CollectedBird]
        let raceRecords: [RaceRecord]
    }

    /// 업로드 한 번의 결과 — 보류 사유를 호출부가 가를 수 있게 돌려준다 (이슈 #128)
    enum UploadOutcome: Equatable {
        case uploaded          // 저장 완료
        case keptServer        // 병합 규칙이 서버 본을 지켰다 (merge의 keepServer)
        case heldNewerSchema   // 서버 레코드가 더 새 스키마 — 디코드 전에 보류
        case heldUndecodable   // 서버 payload를 읽을 수 없거나 모르는 새가 있다 — 덮으면 지워지니 보류
    }
    /// 마지막으로 서버와 맞춘(업로드·복원 성공) 시각 — 설정 화면 캡션용 (이슈 #129).
    /// 운영 스키마 미배포 같은 설정 오류가 있으면 이 시각이 갱신되지 않아 드러난다
    @Published private(set) var lastBackupAt: Date?

    /// CloudKit 컨테이너 — project.yml의 icloud-container-identifiers와 짝
    private static let containerID = "iCloud.com.jkpark.runwrap"
    private static let recordType = "ProgressSnapshot"
    /// 사용자당 스냅샷 하나 — 고정 레코드 이름으로 쿼리 없이 ID 조회한다
    private static let recordName = "progress"
    /// 신규 설치 복원 대기 상한 — 이보다 늦으면 실패로 보고 온보딩을 연다.
    /// 백업 경로에는 시한이 없다 (백그라운드라 기다려도 해가 없다)
    private static let restoreTimeout: TimeInterval = 10
    /// 실패 진단 로그 (이슈 #129) — CKError 코드와 단계 이름만 남긴다.
    /// 스냅샷 내용·개인 데이터는 절대 싣지 않는다 (개인정보 처리방침)
    private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "RunWrap",
                                       category: "cloud-backup")

    private enum RecordField {
        static let payload = "payload"          // 스냅샷 전체 JSON — 스키마 변화에 유연하다
        static let revision = "revision"        // 콘솔 확인용 중복 필드 (판정은 payload 기준)
        static let schemaVersion = "schemaVersion"
        static let updatedAt = "updatedAt"
    }

    /// 동기화 메타 저장 키 — 스냅샷 내용(GrowthKey·ProfileKey)과 구분해 cloud. 접두사를 쓴다
    private enum SyncKey {
        /// 마지막으로 서버에 반영된 revision — 다음 업로드는 +1로 만든다
        static let lastSyncedRevision = "cloud.lastSyncedRevision"
        /// 마지막 업로드 성공본(JSON) — 내용이 같으면 업로드를 건너뛰는 변경 감지용
        static let lastUploaded = "cloud.lastUploadedSnapshot"
        /// 복원 선택 시트에서 "새로 시작"을 골랐는지 — 그 뒤 업로드가 실패해도 다시 묻지 않는다 (이슈 #44)
        static let restoreChoiceMade = "cloud.restoreChoiceMade"
        /// 마지막 동기화 성공 시각(timeIntervalSince1970) — 표시용이라 스냅샷에는 넣지 않는다 (이슈 #129)
        static let lastBackupAt = "cloud.lastBackupAt"
    }

    private let defaults: UserDefaults
    /// 업로드 재진입 가드 — 백그라운드 진입과 화면 트리거가 겹쳐도 한 번만 올린다
    private var isBackingUp = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let backedUpAt = defaults.double(forKey: SyncKey.lastBackupAt)
        lastBackupAt = backedUpAt > 0 ? Date(timeIntervalSince1970: backedUpAt) : nil
        #if DEBUG
        // 시뮬레이터 확인용 — CloudKit 계정이 없고 데모 가드가 백업 경로를 막아
        // 실제로는 복원 선택 시트에 닿을 수 없다. 실행 인자로 후보를 주입해 화면을 본다
        if ProcessInfo.processInfo.arguments.contains("-RWInjectRestoreCandidate") {
            restoreCandidate = Self.debugRestoreCandidate(now: Date())
        }
        #endif
    }

    private var database: CKDatabase {
        CKContainer(identifier: Self.containerID).privateCloudDatabase
    }

    private var recordID: CKRecord.ID { CKRecord.ID(recordName: Self.recordName) }

    // MARK: - 신규 설치 복원

    /// 로컬 프로필이 없을 때(신규 설치·재설치) CloudKit에서 스냅샷을 찾아 적용한다.
    ///
    /// 반환값은 적용된 스냅샷 — 도감(`CollectionStore`)·대회 기록(`RaceRecordStore`) 반영은 호출부 몫이다
    /// (스토어끼리 결합하지 않는다). 로컬 프로필이 이미 있으면 아무것도 하지 않는다 —
    /// **오래된 스냅샷이 최신 로컬 진행도를 되돌리는 일은 구조적으로 없다.**
    func restoreOnFreshInstall() async -> ProgressSnapshot? {
        guard defaults.string(forKey: ProfileKey.levelV2)?.isEmpty != false else { return nil }
        // 데모 모드(시뮬레이터 포함)는 합성 데이터 세계라 복원할 것도, 계정도 없다
        guard !DemoMode.isActive else {
            restoreState = .empty
            return nil
        }
        restoreState = .checking
        do {
            let containerID = Self.containerID
            let status = try await Self.withTimeout(Self.restoreTimeout) {
                try await CKContainer(identifier: containerID).accountStatus()
            }
            guard status == .available else {
                restoreState = .unavailable
                return nil
            }
            guard let snapshot = try await Self.withTimeout(Self.restoreTimeout, {
                Self.decode(try await CKContainer(identifier: containerID)
                    .privateCloudDatabase.record(for: CKRecord.ID(recordName: Self.recordName)))
            }) else {
                // 레코드는 있는데 payload를 읽을 수 없다 — 손상됐거나 이 앱이 모르는 미래 스키마다.
                // 새로 시작은 하되 안내는 남긴다. 읽을 수 없는 본은 복원 선택으로 물을 수도 없지만
                // 업로드도 덮어쓰지 않는다 — upload가 미래 스키마·디코드 실패를 보류한다 (이슈 #128)
                restoreState = .failed
                return nil
            }
            guard ProgressMergeEngine.canRestore(snapshot) else {
                restoreState = .failed
                return nil
            }
            snapshot.apply(to: defaults)
            try? CollectionCache.save(snapshot.collectedBirds)
            RaceRecordCache.save(snapshot.raceRecords ?? [])
            RaceRecordCache.saveDeletedIDs(snapshot.deletedRaceRecordIDs ?? [])
            rememberSynced(snapshot)
            Self.logger.debug("restore 성공")
            restoreState = .restored
            return snapshot
        } catch let error as CKError where error.code == .unknownItem {
            restoreState = .empty   // 스냅샷이 아예 없다 — 진짜 신규 사용자
            return nil
        } catch let error as CKError where error.code == .notAuthenticated {
            restoreState = .unavailable
            return nil
        } catch {
            Self.logFailure(stage: "restore", error)
            restoreState = .failed
            return nil
        }
    }

    // MARK: - 백업

    /// 로컬 상태가 마지막 업로드와 다르면 CloudKit에 올린다.
    ///
    /// 이 기능이 없던 버전에서 올라온 기존 사용자의 **최초 1회 마이그레이션**도 이 경로다 —
    /// 업로드 이력이 없으니 "변경됨"으로 판정되어 현재 로컬 상태가 첫 스냅샷이 된다.
    /// 실패는 사용자에게 알리지 않고 Logger에 코드만 남긴다(이슈 #129): 다음 트리거(백그라운드 진입·단계 상승·사이클 전환)에서 다시 시도한다.
    func backupIfChanged() async {
        // 복원 선택을 기다리는 동안은 올리지 않는다 — 답하기 전에 서버 본을 덮으면 묻는 의미가 없다
        guard !DemoMode.isActive, !isBackingUp, restoreCandidate == nil else { return }
        // 도감은 파일에서 읽는다 — 저장에 실패한 새는 사이클도 넘어가지 않으므로 의도적으로 올리지 않는다 (이슈 #67)
        guard var local = ProgressSnapshot.readLocal(defaults: defaults,
                                                     birds: CollectionCache.load(),
                                                     raceRecords: RaceRecordCache.load() ?? [],
                                                     deletedRaceRecordIDs: RaceRecordCache.loadDeletedIDs(),
                                                     now: Date()) else { return }
        if let last = lastUploaded(), last.hasSameContent(as: local) { return }
        isBackingUp = true
        defer { isBackingUp = false }

        guard (try? await CKContainer(identifier: Self.containerID).accountStatus())
            == .available else { return }
        local.revision = defaults.integer(forKey: SyncKey.lastSyncedRevision) + 1

        let serverRecord: CKRecord?
        do {
            serverRecord = try await fetchServerRecord()
        } catch {
            // 네트워크·스키마 등 — 로컬은 그대로 두고 다음 기회에 다시 올린다
            Self.logFailure(stage: "fetch", error)
            return
        }
        do {
            // 한 번도 동기화하지 않은 설치가 서버의 다른 사이클 본을 만나면 덮기 전에 묻는다 (이슈 #44).
            // 가져온 레코드를 그대로 판정에 쓴다 — 판정용으로 한 번 더 조회하지 않는다
            let server = serverRecord.flatMap(Self.decode)
            if ProgressMergeEngine.needsRestoreChoice(local: local, server: server,
                                                      hasSyncedBefore: hasSyncedBefore),
               let server {
                restoreCandidate = server
                return
            }
            _ = try await upload(local: local, onto: serverRecord)
        } catch let error as CKError where error.code == .serverRecordChanged {
            // 저장 경합 — 서버 최신본과 한 번 더 병합해 재시도, 또 실패하면 다음 기회로
            guard let latest = error.serverRecord else { return }
            do {
                _ = try await upload(local: local, onto: latest)
            } catch {
                Self.logFailure(stage: "save", error)
            }
        } catch {
            // 네트워크·quota 등 — 로컬은 그대로 두고 다음 기회에 다시 올린다
            Self.logFailure(stage: "save", error)
        }
    }

    /// 서버 레코드 조회 — 없으면(첫 업로드) nil
    private func fetchServerRecord() async throws -> CKRecord? {
        do {
            return try await database.record(for: recordID)
        } catch let error as CKError where error.code == .unknownItem {
            return nil
        }
    }

    /// 스냅샷을 레코드에 실어 저장한다. 서버 본이 있으면 병합부터 —
    /// 같은 사이클의 `maxStage`는 내려가지 않고, 도감·대회 기록은 합집합이다 (ProgressMergeEngine).
    ///
    /// 서버 본을 이해할 수 없으면 올리지 않는다 (이슈 #128): 더 새 스키마(디코드 전에 판정), payload 디코드 실패,
    /// 관대 디코드가 버린 새가 있는 경우 — 덮어쓰면 구버전 기기가 미래 스키마나 모르는 새를 지운다.
    private func upload(local: ProgressSnapshot, onto serverRecord: CKRecord?) async throws -> UploadOutcome {
        var outgoing = local
        let record: CKRecord
        if let serverRecord {
            if ProgressMergeEngine.shouldHoldUpload(
                serverSchemaVersion: serverRecord[RecordField.schemaVersion] as? Int) {
                return .heldNewerSchema
            }
            guard let server = Self.decode(serverRecord),
                  let payload = serverRecord[RecordField.payload] as? Data,
                  ProgressSnapshot.undecodableBirdCount(in: payload) == 0 else {
                return .heldUndecodable
            }
            switch ProgressMergeEngine.merge(local: local, server: server) {
            case .keepServer:
                return .keptServer   // 서버가 더 새 스키마 — 덮어쓰지 않는다
            case .upload(let merged):
                outgoing = merged
            }
            record = serverRecord   // 가져온 인스턴스에 실어야 change tag가 맞는다
        } else {
            record = CKRecord(recordType: Self.recordType, recordID: recordID)
        }

        Self.encode(outgoing, into: record)
        _ = try await database.save(record)
        rememberSynced(outgoing)
        Self.logger.debug("save 성공")

        // 병합이 서버 쪽 값을 살렸다면 로컬에도 반영해 둔다 (재설치 경합 같은 드문 경우).
        // 기다리는 사이 사용자가 바꾼 값은 되돌리지 않게, 지금 로컬을 다시 읽어 바뀐 필드는 남긴다 (이슈 #128)
        if !outgoing.hasSameContent(as: local),
           let current = ProgressSnapshot.readLocal(defaults: defaults,
                                                    birds: CollectionCache.load(),
                                                    raceRecords: RaceRecordCache.load() ?? [],
                                                    deletedRaceRecordIDs: RaceRecordCache.loadDeletedIDs(),
                                                    now: Date()) {
            let applied = ProgressMergeEngine.localApplying(merged: outgoing, start: local, current: current)
            applied.apply(to: defaults)
            try? CollectionCache.save(applied.collectedBirds)
            RaceRecordCache.save(applied.raceRecords ?? [])
            RaceRecordCache.saveDeletedIDs(applied.deletedRaceRecordIDs ?? [])
            // 메모리의 CollectionStore·RaceRecordStore는 RootView가 이 값을 받아 교체한다
            if applied.collectedBirds != current.collectedBirds
                || applied.raceRecords != current.raceRecords {
                mergedResult = MergedProgress(birds: applied.collectedBirds,
                                              raceRecords: applied.raceRecords ?? [])
            }
        }
        return .uploaded
    }

    /// RootView가 `mergedResult`를 메모리 스토어에 반영한 뒤 비운다
    func clearMergedResult() {
        mergedResult = nil
    }

    // MARK: - 복원 선택 (이슈 #44)

    /// "이전 기록 불러오기" — 서버 본을 로컬에 적용한다. 방금 온보딩에서 정한 레벨·목표는 서버 값으로 바뀐다.
    ///
    /// 도감·대회 기록은 합집합으로 붙인다 — 수집 이력·입력한 기록은 잃을 이유가 없다(merge와 같은 원칙).
    /// 반환값은 적용된 도감·대회 기록 — 메모리의 `CollectionStore`·`RaceRecordStore` 반영은 호출부 몫이다.
    func acceptRestoreCandidate() -> (birds: [CollectedBird], raceRecords: [RaceRecord])? {
        guard let server = restoreCandidate else { return nil }
        var applied = server
        applied.collectedBirds = ProgressMergeEngine.unionBirds(CollectionCache.load(),
                                                                server.collectedBirds)
        let deleted = ProgressMergeEngine.unionDeletedIDs(RaceRecordCache.loadDeletedIDs(),
                                                          server.deletedRaceRecordIDs ?? [])
        let raceRecords = ProgressMergeEngine.unionRaceRecords(RaceRecordCache.load() ?? [],
                                                               server.raceRecords ?? [], deleted: deleted)
        applied.apply(to: defaults)
        try? CollectionCache.save(applied.collectedBirds)
        RaceRecordCache.save(raceRecords)
        RaceRecordCache.saveDeletedIDs(deleted)
        // 동기화 기준은 서버에 실제로 있는 본 — 합집합으로 늘어난 새가 있으면 다음 백업이 올린다
        rememberSynced(server)
        restoreCandidate = nil
        Task { await backupIfChanged() }
        return (applied.collectedBirds, raceRecords)
    }

    /// "새로 시작" — 지금의 새 사이클을 유지하고 서버 본을 덮는다.
    ///
    /// 서버 쪽 도감·대회 기록은 먼저 로컬 파일에 합쳐 둔다 — 업로드가 실패해도 "도감의 새는 그대로 남아요"가
    /// 지켜진다. 이후 업로드는 기존 병합 규칙(다른 사이클 → 최신인 로컬이 이김, 도감·대회 기록은 합집합)을 탄다.
    /// 반환값은 합쳐진 도감·대회 기록 — 메모리의 `CollectionStore`·`RaceRecordStore` 반영은 호출부 몫이다.
    func declineRestoreCandidate() -> (birds: [CollectedBird], raceRecords: [RaceRecord]) {
        let local = CollectionCache.load()
        let localRecords = RaceRecordCache.load() ?? []
        guard let server = restoreCandidate else { return (local, localRecords) }
        let birds = ProgressMergeEngine.unionBirds(local, server.collectedBirds)
        let deleted = ProgressMergeEngine.unionDeletedIDs(RaceRecordCache.loadDeletedIDs(),
                                                          server.deletedRaceRecordIDs ?? [])
        let raceRecords = ProgressMergeEngine.unionRaceRecords(localRecords, server.raceRecords ?? [],
                                                               deleted: deleted)
        try? CollectionCache.save(birds)
        RaceRecordCache.save(raceRecords)
        RaceRecordCache.saveDeletedIDs(deleted)
        defaults.set(true, forKey: SyncKey.restoreChoiceMade)
        restoreCandidate = nil
        Task { await backupIfChanged() }
        return (birds, raceRecords)
    }

    #if DEBUG
    /// 시뮬레이터 확인용 샘플 — 런친놈, 90일 전 시작한 사이클의 날갯짓 단계, 도감 2마리
    private static func debugRestoreCandidate(now: Date) -> ProgressSnapshot {
        ProgressSnapshot(
            schemaVersion: ProgressSnapshot.currentSchemaVersion,
            revision: 7,
            updatedAt: now.addingTimeInterval(-3 * 86_400),
            cycleID: UUID(),
            levelRaw: RunnerLevel.advanced.rawValue,
            purposesRaw: RunPurpose.encode([.record]),
            weeklyGoal: 4,
            onboardedAt: now.addingTimeInterval(-200 * 86_400),
            cycleStartedAt: now.addingTimeInterval(-90 * 86_400),
            maxStage: GrowthStage.flapping.rawValue,
            raceGoalRaw: RaceDistance.full.rawValue,
            raceGoalSeconds: 4 * 3_600,
            raceDate: nil,
            collectedBirds: [
                CollectedBird(species: .sparrow, goalLabel: "주 3회 습관",
                              collectedAt: now.addingTimeInterval(-150 * 86_400), cycleDays: 40),
                CollectedBird(species: .swallow, goalLabel: "10km 완주",
                              collectedAt: now.addingTimeInterval(-95 * 86_400), cycleDays: 55),
            ])
    }
    #endif

    // MARK: - 동기화 메타

    /// 이 설치가 서버와 한 번이라도 맞춰 봤는지 — 업로드·복원 성공 이력이 있거나 복원 선택을 마쳤다
    private var hasSyncedBefore: Bool {
        lastUploaded() != nil || defaults.bool(forKey: SyncKey.restoreChoiceMade)
    }

    private func rememberSynced(_ snapshot: ProgressSnapshot) {
        defaults.set(snapshot.revision, forKey: SyncKey.lastSyncedRevision)
        defaults.set(try? JSONEncoder().encode(snapshot), forKey: SyncKey.lastUploaded)
        let now = Date()
        defaults.set(now.timeIntervalSince1970, forKey: SyncKey.lastBackupAt)
        lastBackupAt = now
    }

    /// 실패 로그 — 단계 이름과 CKError 코드 숫자만. CKError가 아니면 코드 -1 (이슈 #129).
    /// 에러 설명 문자열은 서버 응답을 담을 수 있어 남기지 않는다
    private static func logFailure(stage: String, _ error: Error) {
        let code = (error as? CKError)?.code.rawValue ?? -1
        logger.error("\(stage, privacy: .public) 실패 — CKError \(code, privacy: .public)")
    }

    private func lastUploaded() -> ProgressSnapshot? {
        guard let data = defaults.data(forKey: SyncKey.lastUploaded) else { return nil }
        return try? JSONDecoder().decode(ProgressSnapshot.self, from: data)
    }

    // MARK: - 레코드 변환

    // 레코드 변환은 순수 함수라 액터 격리가 필요 없다 — 타임아웃 경주 클로저에서도 부른다
    private nonisolated static func encode(_ snapshot: ProgressSnapshot, into record: CKRecord) {
        record[RecordField.payload] = try? JSONEncoder().encode(snapshot)
        record[RecordField.revision] = snapshot.revision
        record[RecordField.schemaVersion] = snapshot.schemaVersion
        record[RecordField.updatedAt] = snapshot.updatedAt
    }

    private nonisolated static func decode(_ record: CKRecord) -> ProgressSnapshot? {
        guard let data = record[RecordField.payload] as? Data else { return nil }
        return try? JSONDecoder().decode(ProgressSnapshot.self, from: data)
    }

    /// 복원 대기 상한 — CloudKit 자체 타임아웃이 스플래시를 오래 잡아 두지 않게 경주시킨다.
    ///
    /// 태스크 그룹은 쓰지 않는다: 그룹은 끝나기 전에 남은 자식을 모두 기다리는데, CloudKit async
    /// 호출은 협력적 취소를 지원하지 않아 결국 CloudKit 자체 타임아웃만큼 멈춘다 (이슈 #44).
    /// 대신 작업과 타이머를 따로 띄워 **먼저 끝난 쪽만** continuation을 재개한다.
    /// 시한을 넘긴 작업은 취소만 요청하고 결과는 버린다(응답이 올 때까지 살아 있어도 무해하다).
    private nonisolated static func withTimeout<T: Sendable>(
        _ seconds: TimeInterval,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        // continuation은 정확히 한 번만 재개해야 한다 — 두 경주자 중 첫 번째만 통과시키는 깃발.
        // Mutex는 iOS 18부터라 iOS 16+의 OSAllocatedUnfairLock을 쓴다
        let resumed = OSAllocatedUnfairLock(initialState: false)
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
            @Sendable func finish(_ result: Result<T, Error>) {
                let isFirst = resumed.withLock { done -> Bool in
                    guard !done else { return false }
                    done = true
                    return true
                }
                if isFirst { continuation.resume(with: result) }
            }
            let work = Task {
                do { finish(.success(try await operation())) } catch { finish(.failure(error)) }
            }
            Task {
                try? await Task.sleep(for: .seconds(seconds))
                work.cancel()
                finish(.failure(CKError(.networkFailure)))
            }
        }
    }
}
