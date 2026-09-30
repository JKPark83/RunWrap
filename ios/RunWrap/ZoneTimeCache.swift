import Foundation

/// 세션별 심박 히스토그램 (이슈 #165) — 기간별 심박존 분포(80/20 강도 배분) 카드의 재료.
///
/// **왜 존 시간이 아니라 bpm 히스토그램인가.** 존 경계(0.6/0.7/0.8/0.9)는 HeartRateProfile
/// (HRmax·안정 심박·%HRmax/Karvonen)에 달려 있고, 사용자가 설정에서 언제든 바꾼다.
/// 정수 bpm → 초로 남겨 두면 경계는 표시 시점에 적용되므로 심박 기준을 바꿔도 캐시가 유효하다.
struct ZoneHistogram: Codable, Equatable {
    /// 반올림한 정수 bpm → 그 심박으로 보낸 시간(초)
    var secondsByBpm: [Int: Double]

    /// 심박 샘플(시각 오름차순) → 히스토그램. 가중은 TrainingGuideEngine.heartRateZones와 같다 —
    /// 다음 샘플까지 간격(≤15초 캡), 마지막 샘플은 5초. 230 초과(plausiblePeakBpm 상한)는
    /// 착용 불량 스파이크로 보고 버린다 (sessionPeakBpm과 같은 기준)
    static func make(samples: [(time: Date, bpm: Double)]) -> ZoneHistogram {
        var seconds: [Int: Double] = [:]
        for (i, sample) in samples.enumerated()
        where sample.bpm <= TrainingGuideEngine.plausiblePeakBpm.upperBound {
            let weight = i + 1 < samples.count
                ? min(samples[i + 1].time.timeIntervalSince(sample.time), 15)
                : 5
            guard weight > 0 else { continue }
            seconds[Int(sample.bpm.rounded()), default: 0] += weight
        }
        return ZoneHistogram(secondsByBpm: seconds)
    }
}

/// Application Support/RunWrap/zone-histograms.json — PBBaselineCache·ReportCache와 같은 패턴
/// (atomic write, 백업 제외). 워크아웃마다 심박 샘플 쿼리가 필요해 한 번 만든 값을 남겨 둔다.
/// 저장 실패는 조용히 삼킨다: 다음 조회 때 다시 만들 뿐이다.
/// 저장 형식은 `[UUID 문자열: ZoneHistogram]` — JSON 객체 키는 문자열이어야 한다.
enum ZoneTimeCache {
    static let filename = "zone-histograms.json"

    static func save(_ histograms: [UUID: ZoneHistogram], in directory: URL? = nil) {
        let stored = Dictionary(uniqueKeysWithValues: histograms.map { ($0.key.uuidString, $0.value) })
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(stored) else { return }
        try? data.write(to: url, options: .atomic)
        excludeFromBackup(url)
    }

    /// 파일이 없거나 깨졌으면 빈 딕셔너리 — 백필이 처음부터 다시 채운다
    static func load(from directory: URL? = nil) -> [UUID: ZoneHistogram] {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode([String: ZoneHistogram].self, from: data)
        else { return [:] }
        return Dictionary(uniqueKeysWithValues: stored.compactMap { key, value in
            UUID(uuidString: key).map { ($0, value) }
        })
    }

    /// 28일 창 밖(또는 삭제된) 워크아웃 항목을 버린다 — 캐시가 기록 전체로 불어나지 않게
    static func prune(_ dict: [UUID: ZoneHistogram], keepingIDs: Set<UUID>) -> [UUID: ZoneHistogram] {
        dict.filter { keepingIDs.contains($0.key) }
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
