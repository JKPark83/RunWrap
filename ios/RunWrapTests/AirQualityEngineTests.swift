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

    @Test("캐시 폴백 1시간 — dataTime이 없으면 59분 전은 신선, 61분 전·미래 시각은 아니다")
    func freshness() {
        let now = ISO8601DateFormatter().date(from: "2026-08-20T10:00:00+09:00")!
        #expect(AirQualityEngine.isFresh(dataTime: nil, fetchedAt: now.addingTimeInterval(-59 * 60), now: now))
        #expect(!AirQualityEngine.isFresh(dataTime: nil, fetchedAt: now.addingTimeInterval(-61 * 60), now: now))
        // 기기 시계 역행(미래 fetchedAt)은 신선으로 치지 않는다 — 캐시를 다시 받는 쪽이 안전
        #expect(!AirQualityEngine.isFresh(dataTime: nil, fetchedAt: now.addingTimeInterval(600), now: now))
        // 형식이 어긋난 dataTime도 같은 폴백
        #expect(AirQualityEngine.isFresh(dataTime: "측정중", fetchedAt: now.addingTimeInterval(-59 * 60), now: now))
    }
}

/// 측정 시각(dataTime) 기반 신선도·낡은 측정값 가드 (이슈 #83) — 모든 시각은 KST 고정
struct AirQualityFreshnessTests {
    private func kst(_ text: String) -> Date {
        ISO8601DateFormatter().date(from: text + "+09:00")!
    }

    @Test("dataTime 파싱 — KST로 읽고, 자정 표기 \"24:00\"은 다음 날 00:00")
    func measuredAt() {
        #expect(AirQualityEngine.measuredAt("2026-08-20 14:00") == kst("2026-08-20T14:00:00"))
        #expect(AirQualityEngine.measuredAt("2026-08-20 24:00") == kst("2026-08-21T00:00:00"))
        #expect(AirQualityEngine.measuredAt(nil) == nil)
        #expect(AirQualityEngine.measuredAt("-") == nil)
        #expect(AirQualityEngine.measuredAt("2026-08-20") == nil)
        // 달·일 범위 밖은 Calendar가 다음 달로 넘기지 않도록 거른다
        #expect(AirQualityEngine.measuredAt("2026-13-01 10:00") == nil)
        #expect(AirQualityEngine.measuredAt("2026-08-32 10:00") == nil)
    }

    @Test("만료 = 측정 정시 + 1시간 20분 — 14:55에 받은 14:00 값은 15:20에 만료된다")
    func dataTimeExpiry() {
        let fetchedAt = kst("2026-08-20T14:55:00")
        // 14:00 + 80분 = 15:20 → 15:19는 신선, 15:20은 만료
        #expect(AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00", fetchedAt: fetchedAt,
                                         now: kst("2026-08-20T15:19:00")))
        #expect(!AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00", fetchedAt: fetchedAt,
                                          now: kst("2026-08-20T15:20:00")))
        // 예전 규칙(받은 지 1시간 → 15:55까지)이면 신선이었을 15:30 — 이제는 15:00 값을 받으러 간다
        #expect(!AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00", fetchedAt: fetchedAt,
                                          now: kst("2026-08-20T15:30:00")))
    }

    @Test("재조회 하한 10분 — 서버가 아직 지난 정시 값을 줘도 받은 지 10분 안은 신선하다")
    func minimumRefetchInterval() {
        // 15:20에 다시 받았는데 15:00 값 공개가 늦어 또 14:00 값 → dataTime 기준으론 받자마자 만료
        let fetchedAt = kst("2026-08-20T15:20:00")
        #expect(AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00", fetchedAt: fetchedAt,
                                         now: kst("2026-08-20T15:29:00")))
        // 15:20 + 10분 = 15:30 → 이때부터 다시 받으러 간다
        #expect(!AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00", fetchedAt: fetchedAt,
                                          now: kst("2026-08-20T15:30:00")))
    }

    @Test("dataTime이 있어도 미래 fetchedAt(시계 역행)은 신선으로 치지 않는다")
    func futureFetchedAt() {
        let now = kst("2026-08-20T14:30:00")
        #expect(!AirQualityEngine.isFresh(dataTime: "2026-08-20 14:00",
                                          fetchedAt: now.addingTimeInterval(600), now: now))
    }

    @Test("낡은 측정값 가드 — now보다 3시간 이상 뒤처지면 미노출, 못 읽으면 막지 않는다")
    func recent() {
        let now = kst("2026-08-20T17:00:00")
        // 17:00 − 14:01 = 2시간 59분 → 노출, 17:00 − 14:00 = 정확히 3시간 → 미노출
        #expect(AirQualityEngine.isRecent(dataTime: "2026-08-20 14:01", now: now))
        #expect(!AirQualityEngine.isRecent(dataTime: "2026-08-20 14:00", now: now))
        #expect(!AirQualityEngine.isRecent(dataTime: "2026-08-19 17:00", now: now))
        #expect(AirQualityEngine.isRecent(dataTime: nil, now: now))
    }
}

/// negative cache — 차단 해제 시각·차단 판정 (이슈 #83)
struct AirQualityBlockTests {
    private func iso(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    @Test("한도 초과 — 다음 KST 자정까지 막는다 (기기 시간대와 무관)")
    func quotaUntilMidnight() {
        // KST 23:30 → 같은 날 밤 자정(다음 날 00:00)
        #expect(AirQualityEngine.blockedUntil(quotaExceeded: true, now: iso("2026-08-20T23:30:00+09:00"))
                == iso("2026-08-21T00:00:00+09:00"))
        // UTC 16:30 = KST 다음 날 01:30 → UTC 날짜가 아니라 KST 날짜 기준 다음 자정(22일 00:00)
        #expect(AirQualityEngine.blockedUntil(quotaExceeded: true, now: iso("2026-08-20T16:30:00Z"))
                == iso("2026-08-22T00:00:00+09:00"))
        // 정확히 자정에 막히면 하루 뒤 자정까지
        #expect(AirQualityEngine.blockedUntil(quotaExceeded: true, now: iso("2026-08-21T00:00:00+09:00"))
                == iso("2026-08-22T00:00:00+09:00"))
    }

    @Test("그 밖의 실패 — 10분만 막는다")
    func otherFailureTenMinutes() {
        let now = iso("2026-08-20T14:00:00+09:00")
        #expect(AirQualityEngine.blockedUntil(quotaExceeded: false, now: now)
                == now.addingTimeInterval(600))
    }

    @Test("차단 판정 — 기록 없음·해제 시각 도달은 통과, 그 전은 차단")
    func isBlocked() {
        let until = iso("2026-08-21T00:00:00+09:00")
        #expect(!AirQualityEngine.isBlocked(blockedUntil: nil, now: until))
        #expect(AirQualityEngine.isBlocked(blockedUntil: until, now: until.addingTimeInterval(-1)))
        #expect(!AirQualityEngine.isBlocked(blockedUntil: until, now: until))
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

    @Test("한도 초과 JSON — resultCode 22는 quotaExceeded (스토어가 자정까지 막는다)")
    func quotaExceeded() {
        let json = """
        {"response":{"header":{"resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR",\
        "resultCode":"22"}}}
        """
        #expect(throws: AirQualityClient.ClientError.quotaExceeded) {
            try AirQualityClient.decode(data(json), stationName: "중구")
        }
    }

    @Test("키 오류 — resultCode 30·31도 quotaExceeded, 그 밖의 오류 코드는 badResponse")
    func keyErrors() {
        for (code, expected) in [("30", AirQualityClient.ClientError.quotaExceeded),
                                 ("31", .quotaExceeded), ("03", .badResponse)] {
            let json = """
            {"response":{"body":{"totalCount":0,"items":[]},\
            "header":{"resultMsg":"ERROR","resultCode":"\(code)"}}}
            """
            #expect(throws: expected) {
                try AirQualityClient.decode(data(json), stationName: "중구")
            }
        }
    }

    @Test("XML 본문 — 게이트웨이 한도 오류(returnReasonCode 22)는 quotaExceeded, 그 밖은 badResponse")
    func xmlBodies() {
        let quota = """
        <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>\
        <returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>\
        <returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>
        """
        #expect(throws: AirQualityClient.ClientError.quotaExceeded) {
            try AirQualityClient.decode(data(quota), stationName: "중구")
        }
        let timeout = """
        <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>\
        <returnAuthMsg>SERVICETIMEOUT</returnAuthMsg>\
        <returnReasonCode>04</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>
        """
        #expect(throws: AirQualityClient.ClientError.badResponse) {
            try AirQualityClient.decode(data(timeout), stationName: "중구")
        }
    }

    @Test("JSON이 아닌 본문 — 디코드 실패도 badResponse로 구분한다")
    func garbageBody() {
        #expect(throws: AirQualityClient.ClientError.badResponse) {
            try AirQualityClient.decode(data("Unexpected errors"), stationName: "중구")
        }
    }
}

/// 재시도 판단 — 5xx·타임아웃만 일시 장애로 보고 다시 부른다 (이슈 #83)
struct AirQualityClientRetryTests {
    @Test("5xx는 재시도, 2xx·4xx·상태 없음은 재시도하지 않는다")
    func statuses() {
        #expect(AirQualityClient.shouldRetry(status: 504, error: nil))
        #expect(AirQualityClient.shouldRetry(status: 500, error: nil))
        #expect(!AirQualityClient.shouldRetry(status: 200, error: nil))
        #expect(!AirQualityClient.shouldRetry(status: 404, error: nil))
        #expect(!AirQualityClient.shouldRetry(status: 429, error: nil))
        #expect(!AirQualityClient.shouldRetry(status: nil, error: nil))
    }

    @Test("백오프 — 0.5초·1초 두 번만, 백오프 뒤 1초도 못 쓰면 포기")
    func delays() {
        #expect(AirQualityClient.retryDelay(attempt: 1, remaining: 7) == 0.5)
        #expect(AirQualityClient.retryDelay(attempt: 2, remaining: 3.5) == 1)
        #expect(AirQualityClient.retryDelay(attempt: 3, remaining: 9) == nil)
        // 남은 1.9초 ≤ 1초 백오프 + 1초 시도
        #expect(AirQualityClient.retryDelay(attempt: 2, remaining: 1.9) == nil)
    }

    @Test("매번 멈춰 타임아웃돼도 10초 안에 세 번 시도한다 — 시도당 타임아웃 3초 상한")
    func stalledAttemptsFitBudget() {
        // 3 + 0.5 + 3 + 1 + 2.5 = 10 — 첫 시도가 10초를 다 쓰면 타임아웃 재시도가 불가능하다
        var remaining: TimeInterval = 10
        var attempts = 0
        while true {
            remaining -= AirQualityClient.attemptTimeout(remaining: remaining)
            attempts += 1
            guard let delay = AirQualityClient.retryDelay(attempt: attempts, remaining: remaining) else { break }
            remaining -= delay
        }
        #expect(attempts == 3)
        #expect(remaining >= 0)
    }

    @Test("URLError.timedOut만 재시도 — 오프라인·취소·기타 오류는 즉시 포기")
    func errors() {
        #expect(AirQualityClient.shouldRetry(status: nil, error: URLError(.timedOut)))
        #expect(!AirQualityClient.shouldRetry(status: nil, error: URLError(.notConnectedToInternet)))
        #expect(!AirQualityClient.shouldRetry(status: nil, error: URLError(.cancelled)))
        #expect(!AirQualityClient.shouldRetry(status: nil, error: CancellationError()))
    }
}

