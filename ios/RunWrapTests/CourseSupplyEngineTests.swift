import Foundation
import Testing
@testable import RunWrap

/// GPX 파서 — trkpt 우선, rtept 폴백, wpt 무시 (계획서 M12-2)
struct GPXParserTests {
    private func gpx(_ body: String) -> Data {
        Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        \(body)
        </gpx>
        """.utf8)
    }

    @Test("트랙 포인트 추출 — trkpt의 lat/lon을 순서대로 읽는다")
    func parseTrackPoints() {
        let points = GPXParser.parse(gpx("""
        <trk><name>테스트</name><trkseg>
          <trkpt lat="37.5" lon="127.0"><ele>12</ele></trkpt>
          <trkpt lat="37.501" lon="127.001"/>
        </trkseg></trk>
        """))
        #expect(points == [GeoPoint(lat: 37.5, lon: 127.0),
                           GeoPoint(lat: 37.501, lon: 127.001)])
    }

    @Test("rtept 폴백 — 트랙이 없는 경로 계획 파일도 코스로 읽는다")
    func routeFallback() {
        let points = GPXParser.parse(gpx("""
        <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.51" lon="127.0"/></rte>
        """))
        #expect(points.count == 2)
        #expect(points[1] == GeoPoint(lat: 37.51, lon: 127.0))
    }

    @Test("trkpt가 있으면 rtept는 무시 — 기록 트랙이 우선")
    func trackWinsOverRoute() {
        let points = GPXParser.parse(gpx("""
        <rte><rtept lat="1.0" lon="1.0"/></rte>
        <trk><trkseg><trkpt lat="37.5" lon="127.0"/></trkseg></trk>
        """))
        #expect(points == [GeoPoint(lat: 37.5, lon: 127.0)])
    }

    @Test("wpt만 있는 파일 — 경로가 아니므로 빈 배열 (오픈 이슈 #4)")
    func waypointOnlyIsEmpty() {
        let points = GPXParser.parse(gpx("""
        <wpt lat="37.5" lon="127.0"><name>급수대</name></wpt>
        """))
        #expect(points.isEmpty)
    }

    @Test("깨진 XML·좌표 없는 포인트 — 조용히 건너뛴다")
    func malformedInput() {
        #expect(GPXParser.parse(Data("이건 GPX가 아닙니다".utf8)).isEmpty)
        let points = GPXParser.parse(gpx("""
        <trk><trkseg><trkpt lat="abc" lon="127.0"/><trkpt lat="37.5" lon="127.0"/></trkseg></trk>
        """))
        #expect(points == [GeoPoint(lat: 37.5, lon: 127.0)])
    }

    @Test("범위 밖·비유한 좌표 — lat 120·nan, lon inf는 버리고 정상 포인트만 남긴다 (감사 M12)")
    func dropsOutOfRangeCoordinates() {
        let points = GPXParser.parse(gpx("""
        <trk><trkseg>
          <trkpt lat="120" lon="127.0"/>
          <trkpt lat="nan" lon="127.0"/>
          <trkpt lat="37.5" lon="inf"/>
          <trkpt lat="37.5" lon="127.0"/>
          <trkpt lat="-90" lon="180"/>
        </trkseg></trk>
        """))
        // 경계값(±90, ±180)은 유효 범위라 남는다
        #expect(points == [GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: -90, lon: 180)])
    }

    @Test("trk 경계 분리 — 트랙 두 개는 세그먼트 두 개, parse는 이어 붙인 결과 (#150)")
    func splitsTracks() {
        let data = gpx("""
        <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.001"/></trkseg></trk>
        <trk><trkseg><trkpt lat="37.6" lon="127.0"/></trkseg></trk>
        """)
        let segments = GPXParser.parseSegments(data)
        #expect(segments == [[GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: 37.5, lon: 127.001)],
                             [GeoPoint(lat: 37.6, lon: 127.0)]])
        #expect(GPXParser.parse(data) == segments.flatMap { $0 })
    }

    @Test("trkseg 경계 분리 — 같은 trk 안 trkseg 두 개도 나누고, 빈 trkseg는 버린다 (#150)")
    func splitsTrackSegments() {
        let segments = GPXParser.parseSegments(gpx("""
        <trk>
          <trkseg><trkpt lat="37.5" lon="127.0"/></trkseg>
          <trkseg></trkseg>
          <trkseg><trkpt lat="37.51" lon="127.0"/></trkseg>
        </trk>
        """))
        #expect(segments == [[GeoPoint(lat: 37.5, lon: 127.0)], [GeoPoint(lat: 37.51, lon: 127.0)]])
    }

    @Test("rtept는 세그먼트 1개 — 경로 계획에는 끊김 개념이 없다 (#150)")
    func routeIsSingleSegment() {
        let segments = GPXParser.parseSegments(gpx("""
        <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.51" lon="127.0"/></rte>
        """))
        #expect(segments.count == 1)
        #expect(segments.first?.count == 2)
    }
}

/// 코스 보급 매칭 엔진 — 수선 거리·누적 km·반경·정렬·미노출 가드 (계획서 M12-2)
///
/// 기대값 산출 근거: 엔진과 같은 산식으로 손 계산.
/// 위도 1도 = 111,195m (R=6,371km·π/180), 경도 1도 = 111,195 × cos(37.5°) = 88,216.9m.
/// 코스는 위도 37.5 고정, 경도 127.0 → 127.02의 직선 = 0.02° × 88,216.9 = 1,764.3m.
/// 중간점 127.01을 둔다 — 점 간격 882.2m가 끊김 판정(1km, #150) 아래여야 한 줄로 이어진다.
struct CourseSupplyEngineTests {
    /// 위도 37.5 고정 동서 직선 코스 (1,764.3m)
    private let course = [GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: 37.5, lon: 127.01),
                          GeoPoint(lat: 37.5, lon: 127.02)]

    private func poi(_ kind: CoursePOI.Kind = .water, lat: Double, lon: Double) -> CoursePOI {
        CoursePOI(kind: kind, name: "테스트", lat: lat, lon: lon)
    }

    @Test("수선 매칭 — 코스 중간 북쪽 100m POI는 0.882km 지점·이탈 100m")
    func perpendicularMatch() throws {
        // POI(37.5009, 127.01): 수선 발 x = 0.01° × 88,216.9 = 882.2m → 0.882km 지점
        // 이탈 = 0.0009° × 111,195 = 100.1m ≤ 150m → 채택
        let result = CourseSupplyEngine.analyze(course: course,
                                                pois: [poi(lat: 37.5009, lon: 127.01)])
        let match = try #require(result?.matches.first)
        #expect(abs(match.courseKm - 0.882) < 0.005)
        #expect(abs(match.detourMeters - 100.1) < 0.5)
        #expect(abs(result!.totalKm - 1.764) < 0.005)
    }

    @Test("반경 밖 제외 — 이탈 222m(> 150m) POI는 매칭하지 않는다")
    func outsideRadius() {
        // 0.002° × 111,195 = 222.4m > 150m
        let result = CourseSupplyEngine.analyze(course: course,
                                                pois: [poi(lat: 37.502, lon: 127.01)])
        #expect(result?.matches.isEmpty == true)
    }

    @Test("끝점 클램프 — 코스 연장선 위 POI는 무한 직선이 아니라 끝점 거리로 잰다")
    func clampToEndpoint() {
        // POI(37.5, 127.03)는 코스 연장선 위(수직 거리 0)지만 끝점에서 882.2m —
        // 클램프가 없으면 잘못 매칭된다
        let result = CourseSupplyEngine.analyze(course: course,
                                                pois: [poi(lat: 37.5, lon: 127.03)])
        #expect(result?.matches.isEmpty == true)
    }

    @Test("km 순 정렬 — 입력 순서와 무관하게 코스 진행 순으로 나온다")
    func sortedByCourseKm() throws {
        // x = 0.015° × 88,216.9 = 1,323.3m / 0.005° × 88,216.9 = 441.1m, 이탈은 둘 다 33.4m
        let far = poi(.convenience, lat: 37.5003, lon: 127.015)
        let near = poi(.toilet, lat: 37.5003, lon: 127.005)
        let result = CourseSupplyEngine.analyze(course: course, pois: [far, near])
        let matches = try #require(result?.matches)
        #expect(matches.map(\.poi.kind) == [.toilet, .convenience])
        #expect(abs(matches[0].courseKm - 0.441) < 0.005)
        #expect(abs(matches[1].courseKm - 1.323) < 0.005)
    }

    @Test("미노출 가드 — 포인트 1개 또는 총거리 500m 미만이면 nil")
    func insufficientCourse() {
        #expect(CourseSupplyEngine.analyze(course: [GeoPoint(lat: 37.5, lon: 127.0)],
                                           pois: []) == nil)
        // 0.0009° × 111,195 = 100.1m < 500m
        let short = [GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: 37.5009, lon: 127.0)]
        #expect(CourseSupplyEngine.analyze(course: short, pois: []) == nil)
    }

    @Test("범위 밖 위도 — lat 120→121 코스는 트랩 없이 nil (감사 M12)")
    func outOfRangeLatitudeReturnsNil() {
        // 중앙 위도 120.5°의 cos < 0 → 경도 축척 음수. 가드가 없으면 lonPad가 음수가 되어
        // 경도 고정 코스의 bbox가 127.0053...126.9947(하한 > 상한)로 만들어지며 트랩한다
        let course = [GeoPoint(lat: 120, lon: 127.0), GeoPoint(lat: 121, lon: 127.0)]
        #expect(CourseSupplyEngine.analyze(course: course,
                                           pois: [poi(lat: 37.5, lon: 127.0)]) == nil)
    }

    @Test("비유한 좌표 — inf 위도·경도가 섞인 코스는 트랩 없이 nil (감사 M12)")
    func infiniteCoordinatesReturnNil() {
        // 위도 inf → 중앙 위도 inf → cos NaN → 축척 가드에서 nil
        let infLat = [GeoPoint(lat: .infinity, lon: 127.0), GeoPoint(lat: 37.5, lon: 127.02)]
        #expect(CourseSupplyEngine.analyze(course: infLat, pois: []) == nil)
        // 경도 inf → 비유한 좌표 가드에서 nil
        let infLon = [GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: 37.5, lon: .infinity)]
        #expect(CourseSupplyEngine.analyze(course: infLon, pois: []) == nil)
    }

    @Test("bbox 1차 필터 — 코스에서 아주 먼 POI가 있어도 결과는 같다")
    func bboxPrefilter() {
        let result = CourseSupplyEngine.analyze(
            course: course,
            pois: [poi(lat: 35.1, lon: 129.0),          // 부산 — bbox 밖
                   poi(lat: 37.5009, lon: 127.01)])     // 코스 옆 100m
        #expect(result?.matches.count == 1)
    }

    // MARK: 세그먼트 분리 (#150)

    /// 위도 37.5 고정, 경도 127.0부터 0.005°(441.1m) 간격 동서 직선 n점
    private func line(from lon: Double, points n: Int) -> [GeoPoint] {
        (0..<n).map { GeoPoint(lat: 37.5, lon: lon + Double($0) * 0.005) }
    }

    @Test("트랙 사이 점프 제외 — 5km 떨어진 두 trk는 세그먼트 2개, totalKm에 점프가 안 들어간다")
    func excludesJumpBetweenTracks() throws {
        // 트랙1: 127.0→127.01 (882.2m), 5km 동쪽(0.0567° × 88,216.9 = 5,001.9m)으로 떨어져
        // 트랙2: 127.0667→127.0767 (882.2m). 유효 총거리 1,764.3m — 이으면 6,766m가 된다
        let data = Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.005"/><trkpt lat="37.5" lon="127.01"/></trkseg></trk>
        <trk><trkseg><trkpt lat="37.5" lon="127.0667"/><trkpt lat="37.5" lon="127.0717"/><trkpt lat="37.5" lon="127.0767"/></trkseg></trk>
        </gpx>
        """.utf8)
        let segments = GPXParser.parseSegments(data)
        #expect(segments.count == 2)
        let result = try #require(CourseSupplyEngine.analyze(segments: segments, pois: []))
        #expect(abs(result.totalKm - 1.764) < 0.005)
    }

    @Test("점프 구간 POI 미매칭 — 이어 붙였으면 잡혔을 점프 한가운데 POI는 빠진다")
    func poiInJumpIsNotMatched() {
        // 세그먼트1 127.0→127.01, 세그먼트2 127.02→127.03 (각 882.2m, 사이 882.2m 점프).
        // POI(37.5003, 127.015)는 점프 선분 한가운데서 33.4m — 한 배열로 넘기면 매칭된다
        let first = line(from: 127.0, points: 3)
        let second = line(from: 127.02, points: 3)
        let jumpPOI = poi(lat: 37.5003, lon: 127.015)
        let joined = CourseSupplyEngine.analyze(course: first + second, pois: [jumpPOI])
        #expect(joined?.matches.count == 1)
        let split = CourseSupplyEngine.analyze(segments: [first, second], pois: [jumpPOI])
        #expect(split?.matches.isEmpty == true)
        // 누적 km는 세그먼트를 넘어 이어진다 — 점프를 뺀 882.2 + 882.2 = 1,764.3m
        #expect(abs((split?.totalKm ?? 0) - 1.764) < 0.005)
    }

    @Test("기록 트랙 안 1km 넘는 간격 — 파서가 끊어 거리·매칭에서 뺀다")
    func longLegInsideTrackIsSplit() throws {
        // 127.0→127.01(882.2m) 다음 점이 127.03(1,764.3m 점프) → 127.04(882.2m). 유효 총거리 1,764.3m
        let segments = GPXParser.parseSegments(Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.01"/>
        <trkpt lat="37.5" lon="127.03"/><trkpt lat="37.5" lon="127.04"/></trkseg></trk>
        </gpx>
        """.utf8))
        #expect(segments.count == 2)
        // 점프 한가운데(127.02) 북쪽 33.4m POI
        let result = try #require(CourseSupplyEngine.analyze(segments: segments,
                                                             pois: [poi(lat: 37.5003, lon: 127.02)]))
        #expect(result.matches.isEmpty)
        #expect(abs(result.totalKm - 1.764) < 0.005)
    }

    @Test("경로 계획(rtept)은 1km 넘는 직선도 끊지 않는다 — 점이 드문 게 정상")
    func routeLongLegIsKept() throws {
        // 127.0→127.03 한 직선 2,646.5m. 끊었다면 nil(500m 미만)이 된다
        let segments = GPXParser.parseSegments(Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.5" lon="127.03"/></rte>
        </gpx>
        """.utf8))
        #expect(segments.count == 1)
        let result = try #require(CourseSupplyEngine.analyze(segments: segments,
                                                             pois: [poi(lat: 37.5003, lon: 127.015)]))
        #expect(result.matches.count == 1)
        #expect(abs(result.totalKm - 2.646) < 0.005)
    }

    @Test("범위 밖 좌표 — 유한해도 격자 셀 계산에서 트랩하지 않고 nil")
    func outOfRangeCoordinatesReturnNil() {
        let course = line(from: 127.0, points: 5)
        let bogus = [GeoPoint(lat: 37.5, lon: 1e20), GeoPoint(lat: 37.5, lon: 1e20)]
        #expect(CourseSupplyEngine.analyze(segments: [course, bogus], pois: []) == nil)
    }

    @Test("단일 세그먼트 — analyze(course:)와 totalKm·매칭 수가 같다")
    func singleSegmentMatchesCourseAPI() throws {
        let course = line(from: 127.0, points: 5)   // 1,764.3m
        let pois = [poi(lat: 37.5009, lon: 127.01), poi(.toilet, lat: 37.5003, lon: 127.005),
                    poi(.convenience, lat: 37.502, lon: 127.01)]   // 마지막은 222m로 반경 밖
        let byCourse = try #require(CourseSupplyEngine.analyze(course: course, pois: pois))
        let bySegments = try #require(CourseSupplyEngine.analyze(segments: [course], pois: pois))
        #expect(bySegments.totalKm == byCourse.totalKm)
        #expect(bySegments.matches.count == byCourse.matches.count)
        #expect(byCourse.matches.count == 2)
    }

    // MARK: 성능 전처리 (#147)

    @Test("격자 셀 경계 — 코스와 다른 셀에 있는 반경 안 POI도 매칭한다")
    func matchesAcrossCellBoundary() throws {
        // 셀 한 변 = max(150, 300) = 300m, 원점은 코스 첫 점. 코스는 y = 0 동서선(셀 y = 0).
        // POI(37.4991, 127.01): y = -0.0009° × 111,195 = -100.1m → 셀 y = -1(경계 0m 바로 아래),
        // x = 882.2m → 셀 x = 2(경계 900m 바로 옆). 이탈 100.1m ≤ 150m → 1건
        let result = CourseSupplyEngine.analyze(course: course, pois: [poi(lat: 37.4991, lon: 127.01)])
        let match = try #require(result?.matches.first)
        #expect(result?.matches.count == 1)
        #expect(abs(match.detourMeters - 100.1) < 0.5)
        #expect(abs(match.courseKm - 0.882) < 0.005)
    }

    @Test("격자 = 완전 탐색 — 지그재그 코스·POI 400개에서 매칭 결과가 전 선분 검사와 같다")
    func gridEqualsBruteForce() throws {
        // 코스: 동쪽으로 0.002°(176.4m)씩 가며 남북으로 0.003°(333.6m) 지그재그 40점.
        // 점 간격 ≈ 377m라 솎아내기(5m)·끊김(1km) 어느 쪽에도 안 걸려 원본 선분 = 엔진 선분이다
        let course = (0..<40).map { GeoPoint(lat: 37.5 + ($0 % 2 == 0 ? 0 : 0.003),
                                             lon: 127.0 + Double($0) * 0.002) }
        // 결정론적 의사난수(LCG)로 코스 bbox 주변 POI 400개
        var seed: UInt64 = 42
        func next() -> Double {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(seed >> 11) / Double(1 << 53)
        }
        let pois = (0..<400).map { _ in poi(lat: 37.497 + next() * 0.009, lon: 126.997 + next() * 0.084) }
        let result = try #require(CourseSupplyEngine.analyze(course: course, pois: pois))

        // 완전 탐색 기준값 — 엔진과 같은 투영(첫 점 원점, 위도 범위 중앙 cos)으로 모든 선분을 본다
        let lonScale = 111_195.0 * cos(37.5015 * .pi / 180)
        let xy = course.map { (($0.lon - 127.0) * lonScale, ($0.lat - 37.5) * 111_195.0) }
        var cumulative = [0.0]
        for i in 1..<xy.count { cumulative.append(cumulative[i - 1] + hypot(xy[i].0 - xy[i - 1].0, xy[i].1 - xy[i - 1].1)) }
        var expected: [(km: Double, dist: Double)] = []
        for p in pois {
            let (px, py) = ((p.lon - 127.0) * lonScale, (p.lat - 37.5) * 111_195.0)
            var best: (dist: Double, meters: Double)?
            for i in 1..<xy.count {
                let (ax, ay) = xy[i - 1], (bx, by) = xy[i]
                let len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                let t = max(0, min(1, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / len2))
                let dist = hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
                if best == nil || dist < best!.dist { best = (dist, cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1])) }
            }
            if let best, best.dist <= 150 { expected.append((best.meters / 1_000, best.dist)) }
        }
        expected.sort { $0.km < $1.km }

        #expect(expected.count > 10)   // 표본이 비어 동일성이 공허하게 참이 되지 않도록
        #expect(result.matches.count == expected.count)
        for (match, want) in zip(result.matches, expected) {
            #expect(abs(match.courseKm - want.km) < 1e-9)
            #expect(abs(match.detourMeters - want.dist) < 1e-6)
        }
    }

    @Test("5m 미만 점 솎아내기 — 겹친 점이 많아도 총거리·매칭은 그대로")
    func thinsDensePoints() throws {
        // 127.0 부근에 0.00001°(0.9m) 간격 점 10개를 끼워도 유효 선분은 같은 직선이다.
        // 총거리는 솎아낸 뒤에도 1,764.3m (끼운 점이 직선 위라 거리 변화 없음)
        let dense = (0..<10).map { GeoPoint(lat: 37.5, lon: 127.0 + Double($0) * 0.000_01) }
        let result = try #require(CourseSupplyEngine.analyze(
            course: dense + Array(course.dropFirst()),
            pois: [poi(lat: 37.5009, lon: 127.01)]))
        #expect(abs(result.totalKm - 1.764) < 0.005)
        #expect(result.matches.count == 1)
    }

    @Test("번들 포맷 디코드 — 1글자 키(k/n/la/lo)를 CoursePOI로 읽는다")
    func decodeBundleFormat() throws {
        let json = Data("""
        {"generatedAt":"2026-08-12","pois":[{"k":"c","n":"GS25 성수점","la":37.54321,"lo":127.04567}]}
        """.utf8)
        let file = try JSONDecoder().decode(CoursePOIFile.self, from: json)
        #expect(file.generatedAt == "2026-08-12")
        #expect(file.pois == [CoursePOI(kind: .convenience, name: "GS25 성수점",
                                        lat: 37.54321, lon: 127.04567)])
    }
}
