import Foundation

/// 주간 리포트 스냅샷 캐시 (계획서 M8) — 알림 본문의 재료를 Application Support에
/// JSON으로 남긴다. 화면 전체가 아니라 알림에 필요한 최소만 담는다 —
/// 리포트 본문은 앱을 열 때마다 항상 새로 계산한다.
struct ReportSnapshot: Codable, Equatable {
    let generatedAt: Date
    let headline: String       // WeeklyReport.headline
    let suggestion: String?    // 다음 주 제안
    let weekKm: Double         // 최근 7일 거리 합
    let runCount: Int          // 최근 7일 러닝 횟수

    /// 리포트 + 원본 기록에서 스냅샷을 만든다 (순수 함수 — 테스트 대상)
    static func make(report: WeeklyReport, runs: [RunSummary], now: Date) -> ReportSnapshot {
        let weekAgo = now.addingTimeInterval(-7 * 86_400)
        let weekKm = runs.filter { $0.start >= weekAgo && $0.start < now }
            .compactMap(\.distanceKm)
            .reduce(0, +)
        return ReportSnapshot(generatedAt: now,
                              headline: report.headline,
                              suggestion: report.suggestion,
                              weekKm: weekKm,
                              runCount: report.weekRunCount)
    }
}

/// Application Support/RunWrap/weekly-report.json — atomic write.
/// 저장 실패는 조용히 삼킨다: 캐시가 없으면 알림 본문이 기본 문구로 나갈 뿐이다.
enum ReportCache {
    static let filename = "weekly-report.json"

    static func save(_ snapshot: ReportSnapshot, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// iCloud·아이튠즈 백업에서 제외 — 심사 지침 5.1.3(ii)는 건강 정보를 iCloud에
    /// 두는 것을 금지한다. 스냅샷은 리포트에서 언제든 다시 만들 수 있으니 백업할 이유도 없다.
    /// 원자적 쓰기는 파일을 새로 만들어 대체하므로 저장할 때마다 다시 지정해야 한다.
    private static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = url
        try? url.setResourceValues(values)
    }

    static func load(from directory: URL? = nil) -> ReportSnapshot? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(ReportSnapshot.self, from: data)
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
