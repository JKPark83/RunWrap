import Foundation
import Testing
@testable import RunWrap

/// PB 갱신 감지 엔진 + 베이스라인 캐시 왕복 검증 (이슈 #21). now 고정.
struct PBEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    private func entry(_ label: String, km: Double, timeSec: Double) -> PersonalRecords.Entry {
        let run = RunSummary(id: UUID(), start: now, durationSec: timeSec,
                             distanceMeters: km * 1_000, avgHeartRate: 160)
        return PersonalRecords.Entry(label: label, distanceKm: km, timeSec: timeSec, run: run)
    }

    @Test("첫 비교 — 베이스라인이 없으면 기존 기록을 축하하지 않고 조용히 지나간다")
    func firstRunSeedsSilently() {
        let current = [entry("5K", km: 5, timeSec: 1_320)]
        #expect(PBEngine.newRecords(current: current, baseline: nil).isEmpty)
    }

    @Test("갱신 감지 — 새 종목과 0.5초 넘게 빨라진 기록만 PB로 본다")
    func detectsNewRecords() {
        let baseline = PBBaseline(times: ["5K": 1_320, "10K": 2_760])
        // 5K −0.3초는 부동소수 재계산 노이즈 가드에 걸리고, 10K −5초와 첫 하프는 PB다
        let current = [entry("5K", km: 5, timeSec: 1_319.7),
                       entry("10K", km: 10, timeSec: 2_755),
                       entry("하프", km: 21.0975, timeSec: 6_000)]
        let fresh = PBEngine.newRecords(current: current, baseline: baseline)
        #expect(fresh.map(\.label) == ["10K", "하프"])
    }

    @Test("베이스라인 생성 — 종목 라벨 → 기록 초 딕셔너리로 접는다")
    func makeBaseline() {
        let baseline = PBBaseline.make(from: [entry("5K", km: 5, timeSec: 1_320),
                                              entry("풀", km: 42.195, timeSec: 14_400)])
        #expect(baseline.times == ["5K": 1_320, "풀": 14_400])
    }

    @Test("캐시 왕복 — 저장한 베이스라인을 그대로 복원하고, 파일이 없으면 nil")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-pb-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(PBBaselineCache.load(from: dir) == nil)
        let baseline = PBBaseline(times: ["10K": 2_755])
        PBBaselineCache.save(baseline, in: dir)
        #expect(PBBaselineCache.load(from: dir) == baseline)
    }
}
