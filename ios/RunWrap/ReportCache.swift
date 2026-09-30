import Foundation

/// 주간 리포트 스냅샷 캐시 (계획서 M8) — 알림 본문의 재료를 Application Support에
/// JSON으로 남긴다. 화면 전체가 아니라 알림에 필요한 최소만 담는다 —
/// 리포트 본문은 앱을 열 때마다 항상 새로 계산한다.
struct ReportSnapshot: Codable, Equatable {
    let generatedAt: Date
    let headline: String       // WeeklyReport.headline(level:)
    let suggestion: String?    // 다음 주 제안
    let weekKm: Double         // 최근 7일 거리 합 (6일 전 자정 ~ 지금 — runCount와 같은 창, 이슈 #75)
    let runCount: Int          // 최근 7일 러닝 횟수
    /// 주간 거리 수치를 알림에 실어도 되는가 — 런린이는 문장만 (ReportGate, 기획서 §4, 이슈 #141)
    let showsDistanceNumbers: Bool

    /// 리포트 + 원본 기록에서 스냅샷을 만든다 (순수 함수 — 테스트 대상).
    /// 문장은 상세 화면과 같은 레벨 게이트로 고른다 — 숨긴 카드의 판정이 알림으로 새지 않게 (이슈 #124)
    static func make(report: WeeklyReport, runs: [RunSummary], level: RunnerLevel,
                     now: Date) -> ReportSnapshot {
        // report.weekRunCount와 같은 달력 창 — 한 문장에 나란히 실리니 창이 같아야 한다 (이슈 #75)
        let windowStart = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 86_400))
        let weekKm = runs.filter { $0.start >= windowStart && $0.start < now }
            .compactMap(\.distanceKm)
            .reduce(0, +)
        return ReportSnapshot(generatedAt: now,
                              headline: report.headline(level: level),
                              suggestion: report.suggestion(level: level),
                              weekKm: weekKm,
                              runCount: report.weekRunCount,
                              showsDistanceNumbers: ReportGate.showsNumbers(.distance, level: level))
    }
}

extension ReportSnapshot {
    /// 기존 캐시 호환 (이슈 #141) — showsDistanceNumbers 키가 없던 파일은 예전처럼 수치 노출(true)로 읽는다.
    /// 디코딩이 실패하면 캐시 전체가 nil이 돼 알림이 기본 문구로 떨어지므로 키 하나로 버리지 않는다.
    /// 확장에 두는 이유: 본체에 두면 memberwise init이 사라진다
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = try container.decode(Date.self, forKey: .generatedAt)
        headline = try container.decode(String.self, forKey: .headline)
        suggestion = try container.decodeIfPresent(String.self, forKey: .suggestion)
        weekKm = try container.decode(Double.self, forKey: .weekKm)
        runCount = try container.decode(Int.self, forKey: .runCount)
        showsDistanceNumbers = try container.decodeIfPresent(Bool.self, forKey: .showsDistanceNumbers) ?? true
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

    /// 캐시 삭제 — 데모 모드를 끌 때 합성 수치가 알림 본문에 남지 않게 한다 (이슈 #44).
    /// 파일이 없거나 지우지 못해도 조용히 넘어간다 (다음 저장이 덮어쓴다)
    static func clear(in directory: URL? = nil) {
        guard let url = fileURL(in: directory) else { return }
        try? FileManager.default.removeItem(at: url)
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
