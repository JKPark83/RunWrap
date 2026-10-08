import Foundation
import Testing
@testable import RunWrap

/// 같은 코스 판정 (이슈 #223) — 시작·끝 150m, 거리 ±5%, 중간점 200m, 루프는 중간점으로만 방향 구분.
/// 경로는 기준점(37.5, 127.0)에서 동·북 방향 미터 오프셋을 10m 간격으로 이어 만든다.
@MainActor
struct CourseMatchEngineTests {
    private let now = ISO8601DateFormatter().date(from: "2026-10-01T06:00:00Z")!
    private static let metersPerDegree = 111_195.0   // GPXParser와 같은 상수
    private static let lonScale = metersPerDegree * cos(37.5 * .pi / 180)

    /// (동, 북) 미터 꼭짓점을 차례로 잇는 경로 — 꼭짓점 사이는 10m 간격으로 채운다
    private func route(_ corners: [(east: Double, north: Double)]) -> [TrackPoint] {
        var points: [(Double, Double)] = [corners[0]]
        for (a, b) in zip(corners, corners.dropFirst()) {
            let steps = max(Int((hypot(b.east - a.east, b.north - a.north) / 10).rounded()), 1)
            for i in 1...steps {
                let f = Double(i) / Double(steps)
                points.append((a.east + (b.east - a.east) * f, a.north + (b.north - a.north) * f))
            }
        }
        return points.enumerated().map { i, p in
            TrackPoint(lat: 37.5 + p.1 / Self.metersPerDegree, lon: 127.0 + p.0 / Self.lonScale,
                       time: now.addingTimeInterval(Double(i)), elevationM: nil,
                       horizontalAccuracyM: 5, speedMps: nil)
        }
    }

    private func fingerprint(_ corners: [(east: Double, north: Double)],
                             distanceM: Double) throws -> CourseMatchEngine.Fingerprint {
        try #require(CourseMatchEngine.fingerprint(route(corners), distanceM: distanceM))
    }

    private func run(daysAgo: Double, paceSec: Double, km: Double = 5) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: paceSec * km, distanceMeters: km * 1_000, avgHeartRate: 150)
    }

    // 직선 5km 동쪽 / 1.25km 정사각형 루프(반시계: 동→북→서→남) / 2.5km 왕복
    private let line: [(east: Double, north: Double)] = [(0, 0), (5_000, 0)]
    private let loopCCW: [(east: Double, north: Double)] = [(0, 0), (1_250, 0), (1_250, 1_250), (0, 1_250), (0, 0)]
    private let outAndBack: [(east: Double, north: Double)] = [(0, 0), (2_500, 0), (0, 0)]

    @Test("지문 — 중간점은 경로 길이 25·50·75% 지점, 방위는 시작→끝")
    func fingerprintWaypoints() throws {
        let fp = try fingerprint(line, distanceM: 5_000)
        // 5km 직선: 1,250·2,500·3,750m 지점. 동쪽 직진이라 방위 90°
        let east = fp.waypoints.map { ($0.lon - 127.0) * Self.lonScale }
        #expect(east.map { Int($0.rounded()) } == [1_250, 2_500, 3_750])
        #expect(abs(fp.bearingDeg - 90) < 0.01)
        #expect(CourseMatchEngine.fingerprint([], distanceM: 5_000) == nil)
        #expect(CourseMatchEngine.fingerprint(route(line), distanceM: 0) == nil)
    }

    @Test("같은 코스 — 경로가 100m 옆으로 비껴도 같은 코스로 묶는다")
    func sameCourseMatches() throws {
        let a = try fingerprint(line, distanceM: 5_000)
        // 북쪽으로 100m 평행 이동 — 시작·끝(150m)·중간점(200m) 허용 안
        let b = try fingerprint([(0, 100), (5_000, 100)], distanceM: 5_100)
        #expect(CourseMatchEngine.isSameCourse(a, b))
        #expect(CourseMatchEngine.isSameCourse(b, a))
    }

    @Test("같은 코스 — 시작점이 150m 넘게 떨어지면 다른 코스")
    func farStartDoesNotMatch() throws {
        let a = try fingerprint(line, distanceM: 5_000)
        // 북쪽 160m 평행 이동 — 시작·끝이 150m 밖
        let b = try fingerprint([(0, 160), (5_000, 160)], distanceM: 5_000)
        #expect(!CourseMatchEngine.isSameCourse(a, b))
    }

    @Test("역방향 — 같은 길을 거꾸로 달리면 같은 코스가 아니다")
    func reverseDoesNotMatch() throws {
        let a = try fingerprint(line, distanceM: 5_000)
        let b = try fingerprint([(5_000, 0), (0, 0)], distanceM: 5_000)
        #expect(!CourseMatchEngine.isSameCourse(a, b))
    }

    @Test("루프 — 같은 방향은 같은 코스, 반대 방향은 중간점으로 갈린다")
    func loopDirection() throws {
        let ccw = try fingerprint(loopCCW, distanceM: 5_000)
        #expect(CourseMatchEngine.isSameCourse(ccw, try fingerprint(loopCCW, distanceM: 5_000)))
        // 시계 방향(북→동→남→서): 25% 지점이 (0, 1250) vs (1250, 0) — 1.77km 차이
        let cw = try fingerprint(Array(loopCCW.reversed()), distanceM: 5_000)
        #expect(!CourseMatchEngine.isSameCourse(ccw, cw))
    }

    @Test("왕복과 루프 — 시작·끝·거리가 같아도 중간점으로 구분한다")
    func outAndBackVsLoop() throws {
        let loop = try fingerprint(loopCCW, distanceM: 5_000)
        let back = try fingerprint(outAndBack, distanceM: 5_000)
        // 왕복 50% 지점(2500, 0) vs 루프 50% 지점(1250, 1250)
        #expect(!CourseMatchEngine.isSameCourse(loop, back))
        #expect(CourseMatchEngine.isSameCourse(back, try fingerprint(outAndBack, distanceM: 5_000)))
    }

    @Test("거리 경계 — 짧은 쪽 대비 5%까지 같은 코스, 넘으면 다른 코스")
    func distanceBoundary() throws {
        let a = try fingerprint(line, distanceM: 10_000)
        // 10,500 − 10,000 = 500 = 10,000 × 5% → 같은 코스. 10,501은 501 > 500 → 다른 코스
        #expect(CourseMatchEngine.isSameCourse(a, try fingerprint(line, distanceM: 10_500)))
        #expect(!CourseMatchEngine.isSameCourse(a, try fingerprint(line, distanceM: 10_501)))
        #expect(CourseMatchEngine.isSameCourse(a, try fingerprint(line, distanceM: 9_524)))
        #expect(!CourseMatchEngine.isSameCourse(a, try fingerprint(line, distanceM: 9_523)))
    }

    @Test("표본 가드 — 같은 코스 기록이 3회 미만이면 nil, 3회부터 시작 순으로 돌려준다")
    func minimumMatches() throws {
        let fp = try fingerprint(line, distanceM: 5_000)
        let other = try fingerprint(Array(line.reversed()), distanceM: 5_000)
        let target = run(daysAgo: 0, paceSec: 300)
        let older = run(daysAgo: 10, paceSec: 310)
        let oldest = run(daysAgo: 20, paceSec: 290)
        // 대상 + 같은 코스 1 + 역방향 1 → 같은 코스 2회
        #expect(CourseMatchEngine.matches(of: fp, in: [(target, fp), (older, fp), (oldest, other)]) == nil)
        let matches = try #require(CourseMatchEngine.matches(of: fp, in: [(target, fp), (older, fp), (oldest, fp)]))
        #expect(matches.map(\.id) == [oldest.id, older.id, target.id])
    }

    @Test("회차·순위 — 시작 순 몇 번째인지와 페이스 순위(빠를수록 1위)")
    func standing() throws {
        let first = run(daysAgo: 20, paceSec: 290)
        let second = run(daysAgo: 10, paceSec: 310)
        let third = run(daysAgo: 0, paceSec: 300)
        let matches = [first, second, third]
        // 세 번째 300초: 290초 하나만 더 빠름 → 2위. 첫 번째 290초 → 1위(최고 기록)
        let t = try #require(CourseMatchEngine.standing(of: third, in: matches))
        #expect(t.ordinal == 3 && t.rank == 2)
        let f = try #require(CourseMatchEngine.standing(of: first, in: matches))
        #expect(f.ordinal == 1 && f.rank == 1)
        #expect(CourseMatchEngine.standing(of: run(daysAgo: 1, paceSec: 300), in: matches) == nil)
    }

    @Test("지문 캐시 — 저장 후 다시 읽으면 같은 지문")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        let fp = try fingerprint(loopCCW, distanceM: 5_000)
        #expect(CourseFingerprintCache.load(from: dir).isEmpty)
        CourseFingerprintCache.save([id: fp], in: dir)
        #expect(CourseFingerprintCache.load(from: dir) == [id: fp])
    }

    @Test("지문 캐시 — 파일이 백업에서 제외된다")
    func cacheExcludedFromBackup() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        CourseFingerprintCache.save([UUID(): try fingerprint(line, distanceM: 5_000)], in: dir)
        let url = dir.appendingPathComponent(CourseFingerprintCache.filename)
        #expect(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test("데모 공유 코스 — 6km 세 세션은 같은 코스로 묶이고 10km 두 세션은 3회 미만이라 nil")
    func demoSharedCourse() throws {
        let runs = DemoData.runs.filter { !$0.isIndoor }
        let history = runs.compactMap { run in
            CourseMatchEngine.fingerprint(WorkoutDetailStore.syntheticRoute(for: run),
                                          distanceM: run.distanceMeters ?? 0).map { (run, $0) }
        }
        func matches(_ id: UUID) throws -> [RunSummary]? {
            let target = try #require(history.first { $0.0.id == id })
            return CourseMatchEngine.matches(of: target.1, in: history)
        }
        let six = try #require(try matches(DemoData.demoID(5)))
        #expect(Set(six.map(\.id)) == [DemoData.demoID(5), DemoData.demoID(7), DemoData.demoID(10)])
        #expect(try matches(DemoData.pausedRunID) == nil)
        // 공유 코스가 아닌 세션(20일 전 5km)은 묶이지 않는다
        #expect(try matches(DemoData.demoID(9)) == nil)
    }
}
