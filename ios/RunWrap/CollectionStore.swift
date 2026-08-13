import Foundation

/// 도감 파일 입출력 — Application Support/RunWrap/collection.json (atomic write).
///
/// `ReportCache`와 같은 방식이다. 다만 성격이 다르다: 리포트 스냅샷은 언제든
/// 다시 계산할 수 있는 캐시지만, **도감은 재계산이 불가능한 유일 원본**이다 —
/// XP는 HealthKit 이력에서 되살릴 수 있어도 "언제 어떤 목표로 수집했는지"는 여기에만 있다.
/// 그래서 저장 실패를 조용히 삼키지 않고 호출부에 알린다.
enum CollectionCache {
    static let filename = "collection.json"

    /// - Throws: 인코딩·쓰기 실패. 도감은 유일 원본이라 실패를 숨기지 않는다
    static func save(_ birds: [CollectedBird], in directory: URL? = nil) throws {
        guard let url = fileURL(in: directory) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let data = try JSONEncoder().encode(birds)
        try data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// iCloud·아이튠즈 백업에서 제외 — `ReportCache`와 같은 이유(심사 지침 5.1.3(ii)).
    /// 도감 자체는 건강 정보가 아니지만 수집일·목표가 훈련 이력을 드러내므로 같은 선에 둔다.
    /// 원자적 쓰기는 파일을 새로 만들어 대체하므로 저장할 때마다 다시 지정해야 한다.
    private static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = url
        try? url.setResourceValues(values)
    }

    /// 파일이 없으면 빈 배열 — "아직 한 마리도 수집 안 함"과 "읽기 실패"를 구분하지 않는다.
    /// 도감은 없어도 앱이 도는 부가 화면이라 여기서는 빈 도감으로 시작하는 편이 낫다.
    static func load(from directory: URL? = nil) -> [CollectedBird] {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([CollectedBird].self, from: data)) ?? []
    }

    /// directory 주입은 테스트용 — 기본은 Application Support/RunWrap (없으면 만든다)
    private static func fileURL(in directory: URL?) -> URL? {
        if let directory { return directory.appendingPathComponent(filename) }
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                                  in: .userDomainMask).first else { return nil }
        let dir = base.appendingPathComponent("RunWrap", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(filename)
    }
}

/// 도감 상태 보유 — 수집한 새 목록과 사이클 전환을 담당한다 (기획서 §5).
///
/// 스토어 계층 규칙대로 `@MainActor final class` + `ObservableObject`다.
/// 다른 스토어(HealthStore 등)와 달리 HealthKit을 만지지 않는다 — 파일과
/// `@AppStorage` 값만 다루는 얇은 계층이라 `enum State` 없이 배열 하나를 노출한다.
@MainActor
final class CollectionStore: ObservableObject {
    /// 수집한 새 — 오래된 순. 화면은 최신순이 필요하면 뒤집어 쓴다
    @Published private(set) var birds: [CollectedBird] = []
    /// 마지막 저장이 실패했는지 — 도감은 유일 원본이라 화면에서 알려야 한다
    @Published private(set) var saveFailed = false

    /// 테스트에서 임시 디렉터리를 주입한다. 기본은 Application Support
    private let directory: URL?

    init(directory: URL? = nil) {
        self.directory = directory
        birds = CollectionCache.load(from: directory)
    }

    /// 이미 그 종을 수집했는지 — 도감 그리드에서 실루엣/성조를 가른다
    func hasCollected(_ species: BirdSpecies) -> Bool {
        birds.contains { $0.species == species }
    }

    /// 같은 종을 여러 번 수집할 수 있다 (사이클마다 목표가 같을 수 있으므로).
    /// 도감 그리드는 종별로 묶어 보여주고, 여기서는 이력을 그대로 쌓는다.
    func add(_ bird: CollectedBird) {
        birds.append(bird)
        persist()
    }

    private func persist() {
        do {
            try CollectionCache.save(birds, in: directory)
            saveFailed = false
        } catch {
            saveFailed = true
        }
    }
}
