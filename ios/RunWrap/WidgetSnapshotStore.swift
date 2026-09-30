import Foundation

/// App Group 컨테이너의 widget-snapshot.json — ReportCache와 같은 패턴(atomic write, 백업 제외,
/// 저장 실패는 조용히 삼킨다: 위젯이 안내 문구로 떨어질 뿐이다).
/// App Group을 쓰는 이유: 위젯 확장은 앱 샌드박스(Application Support)를 읽을 수 없다.
///
/// 앱·위젯 두 타깃에 함께 컴파일된다. 파일 수정 시각 같은 Required Reason API는 쓰지 않는다 —
/// 생성 시각은 스냅샷 JSON의 generatedAt에 담는다.
enum WidgetSnapshotStore {
    static let appGroup = "group.com.jkpark.runwrap"
    static let filename = "widget-snapshot.json"

    static func save(_ snapshot: WidgetSnapshot, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// iCloud·아이튠즈 백업에서 제외 — 심사 지침 5.1.3(ii)는 건강 정보를 iCloud에 두는 것을 금지한다.
    /// 스냅샷은 앱이 언제든 다시 만들 수 있다. 원자적 쓰기는 파일을 새로 만들므로 저장할 때마다 다시 지정한다
    private static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = url
        try? url.setResourceValues(values)
    }

    static func load(from directory: URL? = nil) -> WidgetSnapshot? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// 스냅샷 삭제 — 기록이 비면(삭제·권한 회수) 옛 수치가 위젯에 남지 않게 한다 (ReportCache #61과 같은 이유).
    /// 파일이 없거나 지우지 못해도 조용히 넘어간다
    static func clear(in directory: URL? = nil) {
        guard let url = fileURL(in: directory) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// directory 주입은 테스트용 — 기본은 App Group 컨테이너.
    /// 컨테이너가 nil이면(엔타이틀먼트 누락) 저장·조회 모두 조용히 실패한다
    private static func fileURL(in directory: URL?) -> URL? {
        if let directory { return directory.appendingPathComponent(filename) }
        return FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent(filename)
    }
}
