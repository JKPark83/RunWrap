import Foundation

/// 러닝화 한 켤레 (이슈 #171) — 누적 거리로 교체 시점을 알려 주기 위한 사용자 입력 모델.
/// 러닝화 쿠션은 대략 500~800km에서 수명이 다한다는 게 업계 통념이라 교체 기준 기본값을 600km로 둔다.
///
/// 스키마 규칙 (이슈 #66): 저장된 JSON을 옛 버전 앱·새 버전 앱이 모두 읽어야 한다.
/// 새 필드는 반드시 옵셔널이거나 decodeIfPresent + 기본값으로 추가한다.
struct Shoe: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    /// 등록 전에 이미 달린 거리(km) — 앱을 쓰기 전 기록은 세션 배정 대신 이 값이 맡는다
    var startKm: Double
    /// 교체 기준(km) — 누적이 이 값 이상이면 홈에 교체 안내 카드를 띄운다
    var replaceKm: Double
    /// 은퇴한 신발 — 목록 끝에 흐리게 남고, 세션 배정·자동 배정 후보에서 빠진다
    var isRetired: Bool
    /// 등록 시각 — 자동 배정은 이 시각 이후 세션만 대상으로 한다(이전 거리는 startKm 몫)
    let createdAt: Date

    static let defaultReplaceKm: Double = 600

    init(id: UUID = UUID(), name: String, startKm: Double = 0,
         replaceKm: Double = Shoe.defaultReplaceKm, isRetired: Bool = false, createdAt: Date) {
        self.id = id
        self.name = name
        self.startKm = startKm
        self.replaceKm = replaceKm
        self.isRetired = isRetired
        self.createdAt = createdAt
    }
}

/// shoes.json의 최상위 모양 — 신발 목록 + 기본 신발 + 세션별 배정.
/// assignments 키는 세션 `RunSummary.id.uuidString`, 값은 신발 id(또는 `ShoeEngine.noShoeID`)
struct ShoeFile: Codable, Equatable {
    var schemaVersion: Int
    var shoes: [Shoe]
    var defaultShoeID: UUID?
    var assignments: [String: UUID]

    static let currentSchemaVersion = 1
}

/// 러닝화 마일리지 순수 로직 (이슈 #171) — Foundation만 쓴다. 화면은 여기서 낸 값을 그리기만 한다
enum ShoeEngine {
    /// "없음"으로 명시한 세션의 배정 값 — 키를 지우면 다음 자동 배정이 기본 신발로 다시 채우므로
    /// 사용자가 고른 "없음"을 기억하려고 모두 0인 UUID를 표식으로 남긴다. 어떤 신발 id와도 같지 않다
    static let noShoeID = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!

    /// 교체 기준 대비 이 비율 이상이면 진행 바를 주의 톤으로 바꾼다 (이슈 #171 — 90%)
    static let cautionRatio = 0.9

    /// 누적 거리(km) = 등록 전 거리 + 이 신발에 배정된 세션 거리 합. 거리 없는 세션은 0
    static func mileageKm(shoe: Shoe, runs: [RunSummary], assignments: [String: UUID]) -> Double {
        shoe.startKm + runs.reduce(0) { sum, run in
            assignments[run.id.uuidString] == shoe.id ? sum + (run.distanceKm ?? 0) : sum
        }
    }

    /// 교체 시점인지 — 누적이 기준 이상(경계 포함)
    static func needsReplacement(shoe: Shoe, mileageKm: Double) -> Bool {
        mileageKm >= shoe.replaceKm
    }

    /// 배정이 없는 세션만 기본 신발로 채운 배정표를 돌려준다. 기존 배정("없음" 표식 포함)은 덮어쓰지 않는다.
    /// 기본 신발이 없으면 그대로. since가 있으면 그 시각 이후 시작한 세션만 채운다 —
    /// 등록 전 거리는 startKm가 이미 담고 있어 지난 세션까지 채우면 이중으로 센다
    static func autoAssign(runs: [RunSummary], defaultShoeID: UUID?,
                           assignments: [String: UUID], since: Date? = nil) -> [String: UUID] {
        guard let defaultShoeID else { return assignments }
        var result = assignments
        for run in runs where result[run.id.uuidString] == nil {
            if let since, run.start < since { continue }
            result[run.id.uuidString] = defaultShoeID
        }
        return result
    }

    /// 진행 바 비율 — 0…1로 자른다. 기준이 0 이하면 0
    static func progress(mileageKm: Double, replaceKm: Double) -> Double {
        guard replaceKm > 0 else { return 0 }
        return min(max(mileageKm / replaceKm, 0), 1)
    }

    /// 진행 바 톤 — 기준의 90% 이상이면 주의, 아니면 유지
    static func tone(progress: Double) -> RRTone {
        progress >= cautionRatio ? .caution : .steady
    }

    /// 교체 안내 카드의 닫기 상태 키 — 같은 신발·같은 기준이면 다시 띄우지 않는다.
    /// 기준을 바꾸면 키가 달라져 새 기준에서 다시 안내한다
    static func alertKey(for shoe: Shoe) -> String {
        "\(shoe.id.uuidString)@\(Int(shoe.replaceKm))"
    }

    struct ReplacementAlert: Equatable {
        let shoe: Shoe
        let mileageKm: Double
    }

    /// 홈 교체 안내 카드 재료 — 기본 신발(은퇴 제외)이 기준을 넘었고 이번 기준에서 닫은 적이 없을 때만
    static func replacementAlert(shoes: [Shoe], defaultShoeID: UUID?, runs: [RunSummary],
                                 assignments: [String: UUID], dismissedKey: String) -> ReplacementAlert? {
        guard let shoe = shoes.first(where: { $0.id == defaultShoeID }), !shoe.isRetired,
              alertKey(for: shoe) != dismissedKey else { return nil }
        let mileage = mileageKm(shoe: shoe, runs: runs, assignments: assignments)
        guard needsReplacement(shoe: shoe, mileageKm: mileage) else { return nil }
        return ReplacementAlert(shoe: shoe, mileageKm: mileage)
    }
}

/// Application Support/RunWrap/shoes.json — RaceRecordCache와 같은 패턴 (atomic write, 기기 백업 제외,
/// 깨진 파일 격리). 대회 기록과 달리 iCloud 진행도 스냅샷에는 넣지 않는다 (이슈 #171 결정)
enum ShoeCache {
    static let filename = "shoes.json"

    static func save(_ file: ShoeFile, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// 파일이 없으면 nil(첫 실행). 깨졌으면 원본을 corrupt 파일로 옮겨 둔 뒤 nil —
    /// 옮겨 두지 않으면 빈 상태로 시작한 스토어의 다음 저장이 원본을 덮어쓴다 (이슈 #66).
    /// now 주입은 테스트용 — 격리 파일 이름의 시각
    static func load(from directory: URL? = nil, now: Date = Date()) -> ShoeFile? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        if let file = try? JSONDecoder().decode(ShoeFile.self, from: data) { return file }
        quarantine(url, now: now)
        return nil
    }

    /// 같은 폴더의 shoes.corrupt-<ISO8601>.json으로 옮긴다
    private static func quarantine(_ url: URL, now: Date) {
        let stamp = ISO8601DateFormatter().string(from: now)
        let corrupt = url.deletingLastPathComponent()
            .appendingPathComponent("shoes.corrupt-\(stamp).json")
        try? FileManager.default.moveItem(at: url, to: corrupt)
    }

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
