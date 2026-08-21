import CloudKit
import Foundation

/// 진행도 CloudKit 백업·복원 스토어 (이슈 #29).
///
/// 로컬(UserDefaults + 도감 파일)은 오프라인 캐시로 그대로 두고, 복원에 필요한
/// 상태를 사용자의 CloudKit private database에 **단일 스냅샷 레코드**로 저장한다.
/// 별도 회원가입·자체 서버는 없다. 건강 데이터(운동 목록·심박·위치)는 올리지 않는다 —
/// 스냅샷에는 성장 복원 메타데이터와 도감만 담는다(`ProgressSnapshot` 참고).
///
/// 원칙: iCloud 미로그인·네트워크 실패·quota 오류가 **로컬 진행도를 초기화하면 안 된다.**
/// 백업 실패는 조용히 다음 기회로 미루고, 복원 실패는 상태로만 알려 온보딩을 연다.
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

    /// CloudKit 컨테이너 — project.yml의 icloud-container-identifiers와 짝
    private static let containerID = "iCloud.com.jkpark.runwrap"
    private static let recordType = "ProgressSnapshot"
    /// 사용자당 스냅샷 하나 — 고정 레코드 이름으로 쿼리 없이 ID 조회한다
    private static let recordName = "progress"
    /// 신규 설치 복원 대기 상한 — 이보다 늦으면 실패로 보고 온보딩을 연다.
    /// 백업 경로에는 시한이 없다 (백그라운드라 기다려도 해가 없다)
    private static let restoreTimeout: TimeInterval = 10

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
    }

    private let defaults: UserDefaults
    /// 업로드 재진입 가드 — 백그라운드 진입과 화면 트리거가 겹쳐도 한 번만 올린다
    private var isBackingUp = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private var database: CKDatabase {
        CKContainer(identifier: Self.containerID).privateCloudDatabase
    }

    private var recordID: CKRecord.ID { CKRecord.ID(recordName: Self.recordName) }

    // MARK: - 신규 설치 복원

    /// 로컬 프로필이 없을 때(신규 설치·재설치) CloudKit에서 스냅샷을 찾아 적용한다.
    ///
    /// 반환값은 적용된 스냅샷 — 도감(`CollectionStore`) 반영은 호출부 몫이다
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
                // 레코드는 있는데 읽을 수 없다 — 손상이거나 지금 앱보다 새 스키마.
                // 새로 시작은 하되 안내는 남긴다 (덮어쓰기는 merge가 keepServer로 막는다)
                restoreState = .failed
                return nil
            }
            guard ProgressMergeEngine.canRestore(snapshot) else {
                restoreState = .failed
                return nil
            }
            snapshot.apply(to: defaults)
            try? CollectionCache.save(snapshot.collectedBirds)
            rememberSynced(snapshot)
            restoreState = .restored
            return snapshot
        } catch let error as CKError where error.code == .unknownItem {
            restoreState = .empty   // 스냅샷이 아예 없다 — 진짜 신규 사용자
            return nil
        } catch let error as CKError where error.code == .notAuthenticated {
            restoreState = .unavailable
            return nil
        } catch {
            restoreState = .failed
            return nil
        }
    }

    // MARK: - 백업

    /// 로컬 상태가 마지막 업로드와 다르면 CloudKit에 올린다.
    ///
    /// 이 기능이 없던 버전에서 올라온 기존 사용자의 **최초 1회 마이그레이션**도 이 경로다 —
    /// 업로드 이력이 없으니 "변경됨"으로 판정되어 현재 로컬 상태가 첫 스냅샷이 된다.
    /// 실패는 조용히 삼킨다: 다음 트리거(백그라운드 진입·단계 상승·사이클 전환)에서 다시 시도한다.
    func backupIfChanged() async {
        guard !DemoMode.isActive, !isBackingUp else { return }
        guard var local = ProgressSnapshot.readLocal(defaults: defaults,
                                                     birds: CollectionCache.load(),
                                                     now: Date()) else { return }
        if let last = lastUploaded(), last.hasSameContent(as: local) { return }
        isBackingUp = true
        defer { isBackingUp = false }

        guard (try? await CKContainer(identifier: Self.containerID).accountStatus())
            == .available else { return }
        local.revision = defaults.integer(forKey: SyncKey.lastSyncedRevision) + 1

        do {
            let serverRecord = try await fetchServerRecord()
            try await upload(local: local, onto: serverRecord)
        } catch let error as CKError where error.code == .serverRecordChanged {
            // 저장 경합 — 서버 최신본과 한 번 더 병합해 재시도, 또 실패하면 다음 기회로
            guard let latest = error.serverRecord else { return }
            try? await upload(local: local, onto: latest)
        } catch {
            // 네트워크·quota 등 — 로컬은 그대로 두고 다음 기회에 다시 올린다
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
    /// 같은 사이클의 `maxStage`는 내려가지 않고, 도감은 합집합이다 (ProgressMergeEngine).
    private func upload(local: ProgressSnapshot, onto serverRecord: CKRecord?) async throws {
        var outgoing = local
        let record: CKRecord
        if let serverRecord, let server = Self.decode(serverRecord) {
            switch ProgressMergeEngine.merge(local: local, server: server) {
            case .keepServer:
                return   // 서버가 더 새 스키마 — 덮어쓰지 않는다
            case .upload(let merged):
                outgoing = merged
            }
            record = serverRecord   // 가져온 인스턴스에 실어야 change tag가 맞는다
        } else {
            record = serverRecord ?? CKRecord(recordType: Self.recordType, recordID: recordID)
        }

        Self.encode(outgoing, into: record)
        _ = try await database.save(record)
        rememberSynced(outgoing)

        // 병합이 서버 쪽 값을 살렸다면 로컬에도 반영해 둔다 (재설치 경합 같은 드문 경우).
        // 메모리의 CollectionStore까지는 갱신하지 않는다 — 다음 실행에서 파일로 읽는다
        if !outgoing.hasSameContent(as: local) {
            outgoing.apply(to: defaults)
            try? CollectionCache.save(outgoing.collectedBirds)
        }
    }

    // MARK: - 동기화 메타

    private func rememberSynced(_ snapshot: ProgressSnapshot) {
        defaults.set(snapshot.revision, forKey: SyncKey.lastSyncedRevision)
        defaults.set(try? JSONEncoder().encode(snapshot), forKey: SyncKey.lastUploaded)
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

    /// 복원 대기 상한 — CloudKit 자체 타임아웃이 스플래시를 오래 잡아 두지 않게 경주시킨다
    private static func withTimeout<T: Sendable>(
        _ seconds: TimeInterval,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw CKError(.networkFailure)
            }
            guard let first = try await group.next() else {
                throw CKError(.internalError)
            }
            group.cancelAll()
            return first
        }
    }
}
