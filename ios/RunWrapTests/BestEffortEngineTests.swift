import Foundation
import Testing
@testable import RunWrap

/// 베스트 에포트 엔진 + 캐시 왕복 검증 (이슈 #166). 샘플 시각은 고정 now에서 이어 붙인다.
struct BestEffortEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    /// (거리 m, 소요 초) 구간을 now부터 빈틈없이 이어 붙인 거리 샘플
    private func samples(_ segments: [(meters: Double, seconds: Double)])
        -> [(start: Date, end: Date, meters: Double)] {
        var t = now
        return segments.map { segment in
            let end = t.addingTimeInterval(segment.seconds)
            defer { t = end }
            return (start: t, end: end, meters: segment.meters)
        }
    }

    @Test("등속 6km — 1K·5K는 정확히 페이스 × D, 10K는 못 채워 없다")
    func steadyPace() throws {
        // 5:00/km = 1분에 200m × 30개 = 6km · 1,800초
        let result = BestEffortEngine.bestEfforts(
            distanceSamples: samples(Array(repeating: (200, 60), count: 30)))
        #expect(abs(try #require(result[1_000]) - 300) < 0.001)    // 300초/km × 1km
        #expect(abs(try #require(result[5_000]) - 1_500) < 0.001)  // 300초/km × 5km
        #expect(result[10_000] == nil)
        #expect(result.count == 2)
    }

    @Test("중간 2km가 빠른 12km — 각 거리가 빠른 구간을 포함한 창을 잡는다")
    func fastMiddleSegment() throws {
        // 500m 샘플 24개: 0~5km 6:00/km(180초), 5~7km 4:00/km(120초), 7~12km 6:00/km
        let segments: [(meters: Double, seconds: Double)] =
            Array(repeating: (500, 180), count: 10)
            + Array(repeating: (500, 120), count: 4)
            + Array(repeating: (500, 180), count: 10)
        let result = BestEffortEngine.bestEfforts(distanceSamples: samples(segments))
        #expect(abs(try #require(result[1_000]) - 240) < 0.001)     // 빠른 구간 1km × 240
        #expect(abs(try #require(result[5_000]) - 1_560) < 0.001)   // 2km × 240 + 3km × 360
        #expect(abs(try #require(result[10_000]) - 3_360) < 0.001)  // 2km × 240 + 8km × 360
        #expect(result[21_097.5] == nil)
    }

    @Test("보간 — 샘플 경계에 D가 걸리지 않으면 구간 안에서 선형 보간한다")
    func interpolatesInsideSample() throws {
        // 600m/180초 + 600m/240초. 1km 지점은 둘째 샘플 400m 지점 → 180 + 240 × 400/600 = 340초.
        // 둘째 시작점(600m)부터는 남은 거리가 600m라 1km를 못 채운다
        let result = BestEffortEngine.bestEfforts(distanceSamples: samples([(600, 180), (600, 240)]))
        #expect(abs(try #require(result[1_000]) - 340) < 0.001)
    }

    @Test("정렬 — 샘플이 뒤섞여 들어와도 시작 시각 순으로 이어 계산한다")
    func sortsByStart() throws {
        let ordered = samples([(600, 180), (600, 240)])
        let result = BestEffortEngine.bestEfforts(distanceSamples: ordered.reversed())
        #expect(abs(try #require(result[1_000]) - 340) < 0.001)
    }

    @Test("표본 가드 — 빈 샘플·1K 미만·0 이하 거리만 있으면 빈 dict")
    func emptyWhenInsufficient() {
        #expect(BestEffortEngine.bestEfforts(distanceSamples: []).isEmpty)
        // 900m — 1K 미만
        #expect(BestEffortEngine.bestEfforts(
            distanceSamples: samples(Array(repeating: (300, 90), count: 3))).isEmpty)
        // 음수·0 거리 샘플은 건너뛴다 — 합산돼 1K를 채운 것처럼 보이면 안 된다
        #expect(BestEffortEngine.bestEfforts(
            distanceSamples: samples([(0, 600), (-500, 60), (900, 270)])).isEmpty)
    }

    @Test("0 이하 샘플은 건너뛰고 나머지로 계산한다")
    func skipsNonPositiveSamples() throws {
        // 유효 샘플 500m/150초 × 2 → 1K 300초. 사이의 −100m 샘플은 버린다
        let result = BestEffortEngine.bestEfforts(
            distanceSamples: samples([(500, 150), (-100, 10), (500, 150)]))
        // 버린 샘플의 10초는 둘째 유효 샘플 시작 전 공백이 된다 → 1K = 150 + 10 + 150 = 310초
        #expect(abs(try #require(result[1_000]) - 310) < 0.001)
    }

    @Test("페이스 타당성 — 150~1,200초/km 밖 기록은 버린다")
    func plausiblePaceFilter() {
        // 1km 120초(2:00/km) — GPS 튐으로 본다
        #expect(BestEffortEngine.bestEfforts(
            distanceSamples: samples([(500, 60), (500, 60)]))[1_000] == nil)
        // 1.2km 30분 = 1,500초/km(25:00/km) — 걷기보다 느리다. 1K 최소도 1,250초로 범위 밖
        #expect(BestEffortEngine.bestEfforts(
            distanceSamples: samples([(600, 750), (600, 750)]))[1_000] == nil)
        // 경계값 150초/km는 낸다
        #expect(BestEffortEngine.bestEfforts(
            distanceSamples: samples([(500, 75), (500, 75)]))[1_000] == 150)
    }

    @Test("캐시 왕복 — 하프(21,097.5m) 키까지 그대로 복원하고, 파일이 없으면 빈 표")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-best-effort-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(BestEffortCache.load(from: dir).isEmpty)
        let table: BestEffortTable = [
            UUID(): [1_000: 290, 5_000: 1_500.5, 21_097.5: 6_900],
            UUID(): [:],   // 1K 미만 워크아웃 — 재시도하지 않도록 빈 dict도 남는다
        ]
        BestEffortCache.save(table, in: dir)
        #expect(BestEffortCache.load(from: dir) == table)
    }
}
