import Foundation
import Testing
@testable import RunWrap

/// 최근접 측정소 탐색 — 등장방형 투영 거리와 커버리지 밖 가드 검증
struct AirQualityNearestStationTests {
    // 서울 도심 언저리의 합성 측정소 2곳 — 좌표는 테스트용 임의값
    private let stations = [
        AirStation(name: "가까운곳", lat: 37.57, lon: 126.98),
        AirStation(name: "먼곳", lat: 37.50, lon: 127.10),
    ]

    @Test("직선거리가 가장 짧은 측정소를 고른다")
    func nearest() {
        // (37.565, 126.975) → 가까운곳 약 0.7km, 먼곳 약 13km
        let station = AirQualityEngine.nearestStation(to: GeoPoint(lat: 37.565, lon: 126.975),
                                                      stations: stations)
        #expect(station?.name == "가까운곳")
    }

    @Test("커버리지 밖 가드 — 최근접이 30km 밖(도쿄)이면 nil")
    func outOfCoverage() {
        let station = AirQualityEngine.nearestStation(to: GeoPoint(lat: 35.68, lon: 139.69),
                                                      stations: stations)
        #expect(station == nil)
    }

    @Test("빈 목록 — nil (번들이 아직 채워지지 않은 상태의 미노출 가드)")
    func emptyStations() {
        let station = AirQualityEngine.nearestStation(to: GeoPoint(lat: 37.5, lon: 127.0),
                                                      stations: [])
        #expect(station == nil)
    }
}

/// 공식 등급 구간표 폴백 — 에어코리아 등급 기준 경계값 검증 (응답에 등급이 없을 때만 쓰인다)
struct AirGradeMappingTests {
    @Test("PM2.5 — 15/16, 35/36, 75/76 경계에서 등급이 바뀐다")
    func pm25Boundaries() {
        #expect(AirQualityEngine.pm25Grade(15) == .good)
        #expect(AirQualityEngine.pm25Grade(16) == .moderate)
        #expect(AirQualityEngine.pm25Grade(35) == .moderate)
        #expect(AirQualityEngine.pm25Grade(36) == .bad)
        #expect(AirQualityEngine.pm25Grade(75) == .bad)
        #expect(AirQualityEngine.pm25Grade(76) == .veryBad)
    }

    @Test("PM10 — 30/31, 80/81, 150/151 경계에서 등급이 바뀐다")
    func pm10Boundaries() {
        #expect(AirQualityEngine.pm10Grade(30) == .good)
        #expect(AirQualityEngine.pm10Grade(31) == .moderate)
        #expect(AirQualityEngine.pm10Grade(80) == .moderate)
        #expect(AirQualityEngine.pm10Grade(81) == .bad)
        #expect(AirQualityEngine.pm10Grade(150) == .bad)
        #expect(AirQualityEngine.pm10Grade(151) == .veryBad)
    }

    @Test("오존 — 0.030/0.031, 0.090/0.091, 0.150/0.151 경계에서 등급이 바뀐다")
    func o3Boundaries() {
        #expect(AirQualityEngine.o3Grade(0.030) == .good)
        #expect(AirQualityEngine.o3Grade(0.031) == .moderate)
        #expect(AirQualityEngine.o3Grade(0.090) == .moderate)
        #expect(AirQualityEngine.o3Grade(0.091) == .bad)
        #expect(AirQualityEngine.o3Grade(0.150) == .bad)
        #expect(AirQualityEngine.o3Grade(0.151) == .veryBad)
    }

    @Test("통합지수 — 50/51, 100/101, 250/251 경계에서 등급이 바뀐다")
    func khaiBoundaries() {
        #expect(AirQualityEngine.khaiGrade(50) == .good)
        #expect(AirQualityEngine.khaiGrade(51) == .moderate)
        #expect(AirQualityEngine.khaiGrade(100) == .moderate)
        #expect(AirQualityEngine.khaiGrade(101) == .bad)
        #expect(AirQualityEngine.khaiGrade(250) == .bad)
        #expect(AirQualityEngine.khaiGrade(251) == .veryBad)
    }

    @Test("등급 → 톤 — 좋음 improving부터 매우나쁨 overload까지 순서대로")
    func tones() {
        #expect(AirGrade.good.tone == .improving)
        #expect(AirGrade.moderate.tone == .steady)
        #expect(AirGrade.bad.tone == .caution)
        #expect(AirGrade.veryBad.tone == .overload)
    }
}

/// 대표 등급·미노출 가드·캐시 신선도
struct AirQualityGuardTests {
    private func quality(pm10: Double? = nil, pm25: Double? = nil,
                         pm10Grade: AirGrade? = nil, pm25Grade: AirGrade? = nil,
                         khaiGrade: AirGrade? = nil) -> AirQuality {
        AirQuality(stationName: "측정소", dataTime: nil,
                   pm10: pm10, pm25: pm25, o3: nil, khai: nil,
                   pm10Grade: pm10Grade, pm25Grade: pm25Grade,
                   o3Grade: nil, khaiGrade: khaiGrade)
    }

    @Test("대표 등급 — 통합지수 등급이 있으면 PM보다 우선한다")
    func representativePrefersKhai() {
        let grade = AirQualityEngine.representativeGrade(
            quality(pm10Grade: .veryBad, pm25Grade: .veryBad, khaiGrade: .moderate))
        #expect(grade == .moderate)
    }

    @Test("대표 등급 — 통합지수가 없으면 PM 두 등급 중 나쁜 쪽")
    func representativeWorstPM() {
        let grade = AirQualityEngine.representativeGrade(
            quality(pm10Grade: .good, pm25Grade: .bad))
        #expect(grade == .bad)
    }

    @Test("대표 등급 — 등급이 하나도 없으면 nil (배지 미노출)")
    func representativeNone() {
        #expect(AirQualityEngine.representativeGrade(quality()) == nil)
    }

    @Test("표본 부족 가드 — PM 수치가 하나도 없으면 지표를 내지 않는다")
    func hasReading() {
        #expect(!AirQualityEngine.hasReading(quality()))
        #expect(AirQualityEngine.hasReading(quality(pm10: 34)))
        #expect(AirQualityEngine.hasReading(quality(pm25: 19)))
    }

    @Test("캐시 1시간 — 59분 전은 신선, 61분 전·미래 시각은 아니다")
    func freshness() {
        let now = ISO8601DateFormatter().date(from: "2026-08-20T10:00:00+09:00")!
        #expect(AirQualityEngine.isFresh(fetchedAt: now.addingTimeInterval(-59 * 60), now: now))
        #expect(!AirQualityEngine.isFresh(fetchedAt: now.addingTimeInterval(-61 * 60), now: now))
        // 기기 시계 역행(미래 fetchedAt)은 신선으로 치지 않는다 — 캐시를 다시 받는 쪽이 안전
        #expect(!AirQualityEngine.isFresh(fetchedAt: now.addingTimeInterval(600), now: now))
    }
}

/// 응답 디코드 — 측정소별 실시간 측정정보(ver 1.3) 응답 축약 fixture 검증
struct AirQualityClientDecodeTests {
    private func data(_ json: String) -> Data { Data(json.utf8) }

    @Test("정상 응답 — 문자열 수치·공식 등급을 그대로 파싱한다")
    func normal() throws {
        let json = """
        {"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",\
        "pm10Value":"34","pm25Value":"19","o3Value":"0.031","khaiValue":"68",\
        "pm10Grade1h":"2","pm25Grade1h":"2","o3Grade":"2","khaiGrade":"2"}]},\
        "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """
        let quality = try AirQualityClient.decode(data(json), stationName: "중구")
        #expect(quality.stationName == "중구")
        #expect(quality.dataTime == "2026-08-20 14:00")
        #expect(quality.pm10 == 34)
        #expect(quality.pm25 == 19)
        #expect(quality.o3 == 0.031)
        #expect(quality.khai == 68)
        #expect(quality.pm25Grade == .moderate)
        #expect(quality.khaiGrade == .moderate)
    }

    @Test("결측 \"-\" — 수치는 nil로 접고, 등급이 빠진 항목만 수치로 보완한다")
    func missingValues() throws {
        // pm25는 수치만 있고 등급이 "-" → 공식 구간표 폴백(80 → 매우나쁨).
        // o3·khai는 통신장애로 수치·등급 모두 "-" → 전부 nil
        let json = """
        {"response":{"body":{"totalCount":1,"items":[{"dataTime":"2026-08-20 14:00",\
        "pm10Value":"-","pm25Value":"80","o3Value":"-","khaiValue":"-",\
        "pm10Grade1h":"-","pm25Grade1h":"-","o3Grade":"-","khaiGrade":"-"}]},\
        "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """
        let quality = try AirQualityClient.decode(data(json), stationName: "중구")
        #expect(quality.pm10 == nil)
        #expect(quality.pm10Grade == nil)
        #expect(quality.pm25 == 80)
        #expect(quality.pm25Grade == .veryBad)
        #expect(quality.o3 == nil)
        #expect(quality.khai == nil)
        #expect(quality.khaiGrade == nil)
    }

    @Test("오류 응답 — resultCode ≠ 00이면 throw (스토어가 unavailable로 접는다)")
    func errorResult() {
        let json = """
        {"response":{"body":{"totalCount":0,"items":[]},\
        "header":{"resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","resultCode":"30"}}}
        """
        #expect(throws: (any Error).self) {
            try AirQualityClient.decode(data(json), stationName: "중구")
        }
    }

    @Test("빈 응답 — 항목이 없으면 throw")
    func emptyItems() {
        let json = """
        {"response":{"body":{"totalCount":0,"items":[]},\
        "header":{"resultMsg":"NORMAL_CODE","resultCode":"00"}}}
        """
        #expect(throws: (any Error).self) {
            try AirQualityClient.decode(data(json), stationName: "중구")
        }
    }
}
