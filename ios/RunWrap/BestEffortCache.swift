import Foundation

/// 워크아웃 UUID → [목표 거리(m): 베스트 에포트(초)] (이슈 #166)
typealias BestEffortTable = [UUID: [Double: Double]]

/// Application Support/RunWrap/best-efforts.json — PBBaselineCache와 같은 패턴 (atomic write,
/// 백업 제외). 워크아웃 데이터는 불변이라 한 번 계산한 값은 영구 캐시한다 — 거리 샘플을
/// 워크아웃마다 다시 읽는 비용을 기동마다 치르지 않으려고. 샘플이 없거나 1K 미만인
/// 워크아웃도 빈 dict로 남겨 재시도하지 않는다. 저장 실패는 조용히 삼킨다: 다음 기동에 다시 계산할 뿐이다.
enum BestEffortCache {
    static let filename = "best-efforts.json"

    static func save(_ table: BestEffortTable, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(stored(table)) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// 파일이 없거나 깨졌으면 빈 표 — 처음부터 다시 계산할 뿐 잃는 것은 없다
    static func load(from directory: URL? = nil) -> BestEffortTable {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url),
              let raw = try? JSONDecoder().decode([String: [String: Double]].self, from: data) else { return [:] }
        return table(from: raw)
    }

    /// JSON 키는 문자열이어야 해 [String(UUID): [String(Int(meters)): 초]]로 접는다
    private static func stored(_ table: BestEffortTable) -> [String: [String: Double]] {
        Dictionary(uniqueKeysWithValues: table.map { id, efforts in
            (id.uuidString, Dictionary(uniqueKeysWithValues: efforts.map { (String(Int($0.key)), $0.value) }))
        })
    }

    /// 거리 키는 정수로 저장돼(하프 21_097.5 → "21097") 목표 거리 목록에서 원래 값을 되찾는다.
    /// 목록에 없는 키·깨진 UUID는 버린다
    private static func table(from raw: [String: [String: Double]]) -> BestEffortTable {
        var table: BestEffortTable = [:]
        for (key, efforts) in raw {
            guard let id = UUID(uuidString: key) else { continue }
            var restored: [Double: Double] = [:]
            for (metersKey, seconds) in efforts {
                guard let meters = BestEffortEngine.targets
                    .first(where: { String(Int($0.meters)) == metersKey })?.meters else { continue }
                restored[meters] = seconds
            }
            table[id] = restored
        }
        return table
    }

    /// iCloud·아이튠즈 백업에서 제외 — 심사 지침 5.1.3(ii)는 건강 정보를 iCloud에
    /// 두는 것을 금지한다. 기록에서 언제든 다시 만들 수 있으니 백업할 이유도 없다.
    private static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = url
        try? url.setResourceValues(values)
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
