import Foundation

/// 직접 입력한 대회 기록 (이슈 #35) — 다른 앱·다른 워치·기록증에만 있어 HealthKit에 없는
/// 지난 대회 완주 기록을 예측 표본으로 쓰기 위한 모델. 대회 기록은 정의상 전력 노력이라
/// 훈련 표본에 없는 "힘든 노력의 증거"이고, 예측이 실제보다 느리게 나오는 문제의
/// 가장 직접적인 해법이다 (6개월 전 하프 기록이 이번 주 이지런보다 좋은 재료다).
///
/// 스키마 규칙 (이슈 #66): 저장된 JSON을 옛 버전 앱·새 버전 앱이 모두 읽어야 한다.
/// 새 필드는 반드시 옵셔널이거나 decodeIfPresent + 기본값으로 추가하고,
/// RaceDistance의 raw value(fiveK·tenK·half·full)는 절대 바꾸지 않는다 —
/// 어긋난 원소는 로드 때 건너뛰어지고(원본은 corrupt 파일로 격리) 목록에서 빠진다.
struct RaceRecord: Codable, Equatable, Identifiable {
    let id: UUID
    /// 종목 — 공인 거리(km)는 race.km로 얻는다. 수동 입력이 종목 선택이라 임의 거리는 없다
    let race: RaceDistance
    /// 완주 기록(초)
    let timeSec: Double
    /// 대회 날짜 — 오래된 기록의 시점 차이는 엔진의 VO₂max 추세 배율이 보정한다
    let date: Date

    /// 기록 타당성 (이슈 #93) — 페이스가 세계기록 수준(2′30″/km)보다 빠르거나 20′00″/km보다
    /// 느리면 오독('1시간 45분' → 1분 45초)이나 휠 실수다. 이런 기록 하나가 Riegel 최솟값으로
    /// 2년간 예측을 지배하지 않도록, 엔진 후보 필터와 입력 시트 저장 버튼이 같은 판정을 쓴다
    static func isPlausible(timeSec: Double, km: Double) -> Bool {
        guard timeSec > 0, km > 0 else { return false }
        return (TrainingGuideEngine.minGoalPaceSecPerKm...TrainingGuideEngine.maxRacePaceSecPerKm)
            .contains(timeSec / km)
    }
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

    /// 파일이 없으면 nil(첫 실행). 디코딩은 원소 단위로 관대하게 한다 (이슈 #66) —
    /// 예전엔 원소 하나만 어긋나도 통째로 nil이 되고, 스토어가 빈 목록으로 시작해
    /// 다음 저장이 전체 기록을 덮어썼다. 이제 깨진 원소만 건너뛰고 나머지를 살리며,
    /// 하나라도 실패하면 원본을 corrupt 파일로 옮겨 둔 뒤 살린 기록을 다시 저장한다.
    /// now 주입은 테스트용 — 격리 파일 이름의 시각
    static func load(from directory: URL? = nil, now: Date = Date()) -> [RaceRecord]? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        let decoded = try? JSONDecoder().decode([LenientRecord].self, from: data)
        let records = decoded?.compactMap(\.record) ?? []
        if let decoded, records.count == decoded.count { return records }
        // 원본을 먼저 옮겨야 다음 save가 덮어쓰지 않는다. 옮기지 못하면 원본을 건드리지 않는다
        if quarantine(url, now: now) { save(records, in: directory) }
        return records
    }

    /// 원소 하나의 디코딩 실패를 삼키는 래퍼 — 배열 디코딩이 통째로 실패하지 않게 한다
    private struct LenientRecord: Decodable {
        let record: RaceRecord?
        init(from decoder: any Decoder) throws {
            record = try? RaceRecord(from: decoder)
        }
    }

    /// 같은 폴더의 race-records.corrupt-<ISO8601>.json으로 옮긴다 — 성공 여부 반환
    private static func quarantine(_ url: URL, now: Date) -> Bool {
        let stamp = ISO8601DateFormatter().string(from: now)
        let corrupt = url.deletingLastPathComponent()
            .appendingPathComponent("race-records.corrupt-\(stamp).json")
        return (try? FileManager.default.moveItem(at: url, to: corrupt)) != nil
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
