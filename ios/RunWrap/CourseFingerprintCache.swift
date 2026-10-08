import Foundation

/// Application Support/RunWrap/course-fingerprints.json — 워크아웃 UUID → 코스 지문 (이슈 #223).
/// ReportCache·ZoneTimeCache와 같은 패턴(atomic write, 백업 제외). 과거 기록마다 경로를 다시 읽으면
/// 무거워서 세션 상세를 열 때 만든 지문을 쌓아 둔다. 워크아웃 경로는 불변이라 영구 캐시한다.
/// 저장 실패는 조용히 삼킨다: 다음에 세션을 열 때 다시 만들 뿐이다.
/// 저장 형식은 `[UUID 문자열: Fingerprint]` — JSON 객체 키는 문자열이어야 한다.
enum CourseFingerprintCache {
    static let filename = "course-fingerprints.json"
    /// 최초 1회 최근 90일 백필을 마쳤는지 (UserDefaults)
    static let backfillDoneKey = "courseFingerprintBackfillDone"
    static let backfillDays = 90.0

    static func save(_ fingerprints: [UUID: CourseMatchEngine.Fingerprint], in directory: URL? = nil) {
        let stored = Dictionary(uniqueKeysWithValues: fingerprints.map { ($0.key.uuidString, $0.value) })
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(stored) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// 파일이 없거나 깨졌으면 빈 딕셔너리 — 세션을 열 때마다 다시 쌓인다
    static func load(from directory: URL? = nil) -> [UUID: CourseMatchEngine.Fingerprint] {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode([String: CourseMatchEngine.Fingerprint].self, from: data)
        else { return [:] }
        return Dictionary(uniqueKeysWithValues: stored.compactMap { key, value in
            UUID(uuidString: key).map { ($0, value) }
        })
    }

    /// iCloud·아이튠즈 백업에서 제외 — 심사 지침 5.1.3(ii)는 건강 정보를 iCloud에
    /// 두는 것을 금지한다. 위치 지문은 기록에서 언제든 다시 만들 수 있으니 백업할 이유도 없다.
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
