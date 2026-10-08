import Foundation
import Testing
@testable import RunWrap

/// 페이스 색 경로 · km 마커 · 고도 프로필 (이슈 #222 §2) — 평활·미노출 가드.
struct RoutePaceEngineTests {
    private let start = ISO8601DateFormatter().date(from: "2026-10-08T06:00:00Z")!
    /// 위도 0.0001°(≈11.12m, GPXParser 기준 111,195m/°) 간격 직선 — 100m 반창에 정확히 8다리가 든다.
    /// legSeconds[i] = i번 점 → i+1번 점 소요 초
    private func line(_ legSeconds: [Double], accuracy: (Int) -> Double = { _ in 5 },
                      elevation: (Int) -> Double? = { _ in nil }) -> [TrackPoint] {
        var time = start
        return (0...legSeconds.count).map { i in
            if i > 0 { time = time.addingTimeInterval(legSeconds[i - 1]) }
            return TrackPoint(lat: 37.5 + Double(i) * 0.0001, lon: 127, time: time,
                              elevationM: elevation(i), horizontalAccuracyM: accuracy(i), speedMps: nil)
        }
    }

    @Test("표본 부족 가드 — 점 10개 미만·거리 500m 미만이면 구간을 내지 않는다")
    func sampleGuard() {
        #expect(RoutePaceEngine.segments(line(Array(repeating: 3, count: 8))) == nil)   // 9점
        #expect(RoutePaceEngine.segments(line(Array(repeating: 3, count: 40))) == nil)  // 444.8m
    }

    @Test("평활 — 1초·5초가 번갈아도(순간 90↔450초/km) 200m 창 평균은 고르게 steady 한 구간")
    func smoothsAlternatingLegs() throws {
        // 가운데 창은 16다리(두 다리 22.24m에 6초로 일정) → 비율 1.0.
        // 시작·끝은 창을 안쪽으로 밀어 17다리(0~200m) → 53/17 또는 49/17초 ÷ 3초 = 1.039·0.961 → ±5% 안 steady
        let points = line((0..<100).map { $0.isMultiple(of: 2) ? 1 : 5 })
        let segments = try #require(RoutePaceEngine.segments(points))
        #expect(segments.count == 1)
        #expect(segments[0].tone == .steady)
        #expect(segments[0].points == points)
    }

    @Test("후반 처짐 — 중앙값보다 15% 넘게 느린 끝 구간은 overload, 앞은 steady")
    func slowFinish() throws {
        // 앞 100다리 3초, 뒤 50다리 3.6초(+20%) → 중앙값은 앞쪽 페이스, 끝 비율 1.2 → overload
        let points = line(Array(repeating: 3, count: 100) + Array(repeating: 3.6, count: 50))
        let segments = try #require(RoutePaceEngine.segments(points))
        #expect(segments.first?.tone == .steady)
        #expect(segments.last?.tone == .overload)
        // 이웃 구간은 경계 점을 공유한다 — 폴리라인이 끊기지 않는다
        for (a, b) in zip(segments, segments.dropFirst()) { #expect(a.points.last == b.points.first) }
    }

    @Test("정확도 나쁜 구간 — 수평 정확도 50m 초과 점이 닿는 다리는 tone nil(회색)")
    func poorAccuracyIsGray() throws {
        // 50~59번 점 정확도 80m → 49~59번 다리가 nil
        let points = line(Array(repeating: 3, count: 100), accuracy: { (50..<60).contains($0) ? 80 : 5 })
        let segments = try #require(RoutePaceEngine.segments(points))
        #expect(segments.map(\.tone) == [.steady, nil, .steady])
        #expect(segments[1].points.first == points[49])
        #expect(segments[1].points.last == points[60])
    }

    @Test("km 마커 — 누적 1km를 처음 넘는 점, 경로가 짧으면 그만큼만")
    func kmMarkers() {
        // 11.12m 간격 250다리 = 2.78km → 1km는 90번(1000.8m)·2km는 180번 점 (스플릿이 3개라도 2개)
        let points = line(Array(repeating: 3, count: 250))
        let markers = RoutePaceEngine.kmMarkers(points, count: 3)
        #expect(markers.count == 2)
        #expect(abs(markers[0].lat - points[90].lat) < 1e-12)
        #expect(abs(markers[1].lat - points[180].lat) < 1e-12)
        #expect(RoutePaceEngine.kmMarkers(points, count: 1).count == 1)
    }

    @Test("고도 프로필 — 고도 있는 점이 10개 미만이면 nil")
    func elevationGuard() {
        let points = line(Array(repeating: 3, count: 100), elevation: { $0 < 9 ? 20 : nil })
        #expect(RoutePaceEngine.elevationProfile(points) == nil)
    }

    @Test("고도 프로필 — 누적 거리 축을 균등 표본으로 나눠 가장 가까운 점의 고도를 쓴다")
    func elevationProfile() throws {
        // 100다리 = 1.112km, 고도 = 점 번호(m). 5등분 → 0·25·50·75·100번 점 → 0·25·50·75·100m
        let points = line(Array(repeating: 3, count: 100), elevation: { Double($0) })
        let profile = try #require(RoutePaceEngine.elevationProfile(points, samples: 5))
        #expect(profile.map(\.elevationM) == [0, 25, 50, 75, 100])
        #expect(abs(profile.last!.distanceKm - 1.11195) < 1e-9)
    }
}
