import Foundation

/// 직접 입력한 대회 기록 (이슈 #35) — 다른 앱·다른 워치·기록증에만 있어 HealthKit에 없는
/// 지난 대회 완주 기록을 예측 표본으로 쓰기 위한 모델. 대회 기록은 정의상 전력 노력이라
/// 훈련 표본에 없는 "힘든 노력의 증거"이고, 예측이 실제보다 느리게 나오는 문제의
/// 가장 직접적인 해법이다 (6개월 전 하프 기록이 이번 주 이지런보다 좋은 재료다).
struct RaceRecord: Codable, Equatable, Identifiable {
    let id: UUID
    /// 종목 — 공인 거리(km)는 race.km로 얻는다. 수동 입력이 종목 선택이라 임의 거리는 없다
    let race: RaceDistance
    /// 완주 기록(초)
    let timeSec: Double
    /// 대회 날짜 — 오래된 기록의 시점 차이는 엔진의 VO₂max 추세 배율이 보정한다
    let date: Date
}

/// Application Support/RunWrap/race-records.json — PBBaselineCache와 같은 패턴 (atomic write,
/// 백업 제외). 사용자가 직접 입력한 소량 데이터라 잃어도 다시 입력할 수 있고,
/// 운동 기록이라 iCloud에 두지 않는 쪽이 심사 지침 5.1.3(ii)에 안전하다.
enum RaceRecordCache {
    static let filename = "race-records.json"

    static func save(_ records: [RaceRecord], in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    static func load(from directory: URL? = nil) -> [RaceRecord]? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode([RaceRecord].self, from: data)
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

/// 대회 기록 소유 스토어 — 설정 화면이 입력하고 리포트 화면이 소비한다.
/// 두 화면이 공유해야 해서 RootView가 쥐고 environmentObject로 내린다 (이슈 #35)
@MainActor
final class RaceRecordStore: ObservableObject {
    @Published private(set) var records: [RaceRecord]

    init() {
        records = RaceRecordCache.load() ?? []
    }

    func add(_ record: RaceRecord) {
        records.append(record)
        records.sort { $0.date > $1.date }   // 최신이 위 — 설정 목록 표시 순서
        RaceRecordCache.save(records)
    }

    func remove(_ record: RaceRecord) {
        records.removeAll { $0.id == record.id }
        RaceRecordCache.save(records)
    }
}
