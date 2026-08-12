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
}

/// 코스 보급 매칭 엔진 — 수선 거리·누적 km·반경·정렬·미노출 가드 (계획서 M12-2)
///
/// 기대값 산출 근거: 엔진과 같은 산식으로 손 계산.
/// 위도 1도 = 111,195m (R=6,371km·π/180), 경도 1도 = 111,195 × cos(37.5°) = 88,216.9m.
/// 코스는 위도 37.5 고정, 경도 127.0 → 127.02의 직선 = 0.02° × 88,216.9 = 1,764.3m.
struct CourseSupplyEngineTests {
    /// 위도 37.5 고정 동서 직선 코스 (1,764.3m)
    private let course = [GeoPoint(lat: 37.5, lon: 127.0), GeoPoint(lat: 37.5, lon: 127.02)]

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

    @Test("bbox 1차 필터 — 코스에서 아주 먼 POI가 있어도 결과는 같다")
    func bboxPrefilter() {
        let result = CourseSupplyEngine.analyze(
            course: course,
            pois: [poi(lat: 35.1, lon: 129.0),          // 부산 — bbox 밖
                   poi(lat: 37.5009, lon: 127.01)])     // 코스 옆 100m
        #expect(result?.matches.count == 1)
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
