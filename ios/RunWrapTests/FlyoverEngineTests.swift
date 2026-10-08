import Foundation
import Testing
@testable import RunWrap

/// 플라이오버 (이슈 #224) — 시간 비례 키프레임·heading 이동 평균·진행률 → 경로 위 보간
struct FlyoverEngineTests {
    private let start = ISO8601DateFormatter().date(from: "2026-10-01T06:00:00Z")!

    /// 북쪽으로 0.001°(≈111m)씩 가는 41점 — 앞 20구간은 20초씩(빠름), 뒤 20구간은 40초씩(느림).
    /// 총 1,200초 = 앞 400초 + 뒤 800초
    private func route() -> [TrackPoint] {
        var elapsed: Double = 0
        return (0...40).map { i in
            if i > 0 { elapsed += i <= 20 ? 20 : 40 }
            return TrackPoint(lat: 37.5 + Double(i) * 0.001, lon: 127, time: start.addingTimeInterval(elapsed),
                              elevationM: nil, horizontalAccuracyM: 5, speedMps: nil)
        }
    }

    @Test("키프레임 — duration 합이 재생 길이(18초)와 같다")
    func durationsSumToPlayback() throws {
        let track = try #require(FlyoverEngine.track(route()))
        let keyframes = FlyoverEngine.keyframes(track)
        #expect(keyframes.count == FlyoverEngine.keyframeCount + 1)
        #expect(keyframes[0].durationSec == 0)
        #expect(abs(keyframes.map(\.durationSec).reduce(0, +) - 18) < 1e-9)
    }

    @Test("키프레임 — 빨리 달린 구간이 짧은 duration을 받는다")
    func fastSegmentsAreShorter() throws {
        // 거리 4등분 → 10·20·30구간 지점의 경과 200·400·800·1,200초
        // duration = 구간 소요 / 1,200 × 18 → 0, 3, 3, 6, 6
        let track = try #require(FlyoverEngine.track(route()))
        let durations = FlyoverEngine.keyframes(track, count: 4).map(\.durationSec)
        let expected: [Double] = [0, 3, 3, 6, 6]
        for (d, e) in zip(durations, expected) { #expect(abs(d - e) < 1e-9) }
    }

    @Test("진행률 보간 — t=0은 시작점, t=1은 끝점에서 끝난다")
    func progressEndpoints() throws {
        let points = route()
        let track = try #require(FlyoverEngine.track(points))
        let first = FlyoverEngine.frame(track, progress: 0)
        #expect(first.lat == points[0].lat && first.lon == points[0].lon)
        #expect(first.distanceM == 0 && first.elapsedSec == 0)
        #expect(first.paceSecPerKm == nil)   // 100m 미만 — 표본 부족
        let last = FlyoverEngine.frame(track, progress: 1)
        #expect(abs(last.lat - points[40].lat) < 1e-12)
        #expect(last.elapsedSec == 1_200)
        #expect(last.passedCount == 41)
        #expect(abs(last.distanceM - track.totalM) < 1e-6)
    }

    @Test("진행률 보간 — 위치는 거리가 아니라 경과 시간 비례로 움직이고 페이스는 직전 500m 평균이다")
    func progressFollowsTime() throws {
        let track = try #require(FlyoverEngine.track(route()))
        // t=0.5 → 600초 = 빠른 20구간(400초) + 느린 5구간(200초) → 25번 점에 도착
        let mid = FlyoverEngine.frame(track, progress: 0.5)
        #expect(mid.passedCount == 26)
        #expect(abs(mid.distanceM - track.cumulativeM[25]) < 1e-6)
        // 끝 지점의 직전 500m는 전부 느린 구간 — 111.19m당 40초 → ≈ 359.7초/km
        let pace = try #require(FlyoverEngine.frame(track, progress: 1).paceSecPerKm)
        #expect(abs(pace - 40 / track.cumulativeM[1] * 1_000) < 0.01)
    }

    @Test("heading — 350°→10°로 꺾여도 한 바퀴 돌지 않게 연속값으로 이어진다")
    func headingUnwraps() throws {
        // 앞 20구간은 북북서(-10°), 뒤 20구간은 북북동(+10°) 방향
        var lat = 37.5, lon = 127.0
        let points = (0...40).map { i -> TrackPoint in
            if i > 0 {
                let deg = (i <= 20 ? -10.0 : 10.0) * .pi / 180
                lat += 0.001 * cos(deg)
                lon += 0.001 * sin(deg)
            }
            return TrackPoint(lat: lat, lon: lon, time: start.addingTimeInterval(Double(i) * 30),
                              elevationM: nil, horizontalAccuracyM: 5, speedMps: nil)
        }
        let headings = FlyoverEngine.keyframes(try #require(FlyoverEngine.track(points))).map(\.headingDeg)
        #expect(headings.allSatisfy { abs($0) < 30 })
        #expect(zip(headings, headings.dropFirst()).allSatisfy { abs($1 - $0) < 10 })
    }

    @Test("기록 거리 보정 — 세션 거리를 주면 누적 거리를 그 길이로 맞추고 페이스도 그 거리로 낸다")
    func scalesToRecordedDistance() throws {
        // 40구간 1,200초를 6km로 맞춘다 → 끝 거리 6,000m, 뒤 구간 150m당 40초 → 266.7초/km
        let track = try #require(FlyoverEngine.track(route(), distanceM: 6_000))
        let last = FlyoverEngine.frame(track, progress: 1)
        #expect(abs(last.distanceM - 6_000) < 1e-6)
        let pace = try #require(last.paceSecPerKm)
        #expect(abs(pace - 40.0 / 150 * 1_000) < 0.01)
    }

    @Test("표본 부족 가드 — 경로 점이 20개 미만이면 플라이오버를 내지 않는다")
    func tooFewPoints() {
        #expect(FlyoverEngine.track(Array(route().prefix(19))) == nil)
        #expect(FlyoverEngine.track(Array(route().prefix(20))) != nil)
    }
}
