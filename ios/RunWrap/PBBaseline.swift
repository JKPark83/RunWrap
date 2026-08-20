import Foundation

/// PB 베이스라인 (이슈 #21) — 마지막으로 확인한 종목별 최고 기록을 저장해
/// "새 PB 갱신"을 감지한다. 홈 진입 시 1회성 축하 팝업의 재료.
///
/// PersonalRecords는 매번 전체 기록에서 재계산되고 어디에도 저장되지 않으므로,
/// "지난번보다 좋아졌는가"를 알려면 마지막 확인 시점의 기록을 남겨 둬야 한다.
struct PBBaseline: Codable, Equatable {
    /// 종목 라벨("5K"·"10K"·"하프"·"풀") → 공인 거리 환산 기록(초)
    let times: [String: Double]

    static func make(from records: [PersonalRecords.Entry]) -> PBBaseline {
        PBBaseline(times: Dictionary(uniqueKeysWithValues: records.map { ($0.label, $0.timeSec) }))
    }
}

enum PBEngine {
    /// 새로 세운 PB — 종목이 처음 생겼거나 기존 기록보다 빨라졌으면 갱신으로 본다.
    /// 베이스라인이 없으면(첫 실행) 빈 배열 — 기존 기록 전부를 "새 PB"로 축하하지 않고
    /// 조용히 seed만 하라는 뜻이다. 0.5초 여유는 부동소수 재계산 노이즈 가드.
    static func newRecords(current: [PersonalRecords.Entry],
                           baseline: PBBaseline?) -> [PersonalRecords.Entry] {
        guard let baseline else { return [] }
        return current.filter { entry in
            guard let old = baseline.times[entry.label] else { return true }
            return entry.timeSec < old - 0.5
        }
    }
}

/// Application Support/RunWrap/pb-baseline.json — ReportCache와 같은 패턴 (atomic write,
/// 백업 제외). 저장 실패는 조용히 삼킨다: 다음 진입 때 다시 감지·축하할 뿐이다.
enum PBBaselineCache {
    static let filename = "pb-baseline.json"

    static func save(_ baseline: PBBaseline, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(baseline) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    static func load(from directory: URL? = nil) -> PBBaseline? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(PBBaseline.self, from: data)
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
