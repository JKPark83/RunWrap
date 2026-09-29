import Foundation

/// 에어코리아 측정소별 실시간 측정정보 클라이언트 (data.go.kr 15073861, 이슈 #8).
/// WeatherClient와 같은 패턴 — URLComponents + async/await, 디코드는 fixture 테스트를 위해 분리.
/// 요청에는 측정소명과 인증키만 실린다 — 사용자 좌표·건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
///
/// 게이트웨이 방어 (이슈 #83): 5xx·타임아웃은 짧은 백오프로 최대 2회 재시도하고(전체 10초 안),
/// 한도 초과·키 오류는 quotaExceeded로 따로 알려 스토어가 자정까지 호출을 멈추게 한다.
struct AirQualityClient {
    enum ClientError: Error, Equatable {
        /// resultCode ≠ "00"(한도·키 오류 제외)·항목 없음·HTTP 오류·JSON이 아닌 본문 — 스토어는 unavailable로 접는다
        case badResponse
        /// 트래픽 한도 초과(resultCode 22) 또는 인증키 오류(30·31) — 그날은 다시 불러도 같은 답이다
        case quotaExceeded
    }

    /// 재시도 백오프 — 같은 게이트웨이를 쓰는 tools/air-quality/fetch_stations.py는 504가 절반가량이라
    /// 재시도가 필수지만, 앱은 홈 진입 흐름이라 짧게 두 번만 (0.5초 → 1초)
    private static let retryDelays: [TimeInterval] = [0.5, 1]
    /// 재시도를 포함한 전체 상한 — 날씨와 같은 10초
    private static let totalBudget: TimeInterval = 10
    /// 시도 1회의 타임아웃 — 10초를 첫 시도에 다 쓰면 멈춘 응답(타임아웃)은 재시도할 틈이 없다.
    /// 3초씩 끊으면 세 번 모두 멈춰도 3 + 0.5 + 3 + 1 + 2.5 = 10초 안에 끝난다
    private static let maxAttemptTimeout: TimeInterval = 3

    /// 홈 진입 후 백그라운드로 도는 조회지만 날씨와 같은 10초 상한을 둔다 —
    /// 오래 기다린 끝의 낡은 수치보다 "안 보여주기"가 낫다 (미노출 가드).
    /// 전체 상한은 current()의 재시도 루프가 지키고, 세션은 시도 1회 타임아웃만 건다
    private static let bounded: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = maxAttemptTimeout
        return URLSession(configuration: configuration)
    }()

    var session: URLSession = AirQualityClient.bounded

    func current(stationName: String, serviceKey: String) async throws -> AirQuality {
        var components = URLComponents(
            string: "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty")!
        components.queryItems = [
            .init(name: "serviceKey", value: serviceKey),
            .init(name: "returnType", value: "json"),
            .init(name: "stationName", value: stationName),
            .init(name: "dataTerm", value: "DAILY"),
            .init(name: "numOfRows", value: "1"),   // 최신 1건이면 충분
            .init(name: "pageNo", value: "1"),
            .init(name: "ver", value: "1.3"),       // 1.3부터 PM 1시간 등급(pm10/pm25Grade1h) 포함
        ]
        // data.go.kr 인증키는 "디코딩 키"(+·/·= 포함 원본)를 쓴다. URLComponents는 쿼리의
        // +를 그대로 두는데 서버가 공백으로 해석해 키가 깨진다 — %2B로 직접 치환한다
        components.percentEncodedQuery = components.percentEncodedQuery?
            .replacingOccurrences(of: "+", with: "%2B")
        let url = components.url!
        let deadline = Date().addingTimeInterval(Self.totalBudget)
        var lastError: Error = ClientError.badResponse
        for attempt in 0...Self.retryDelays.count {
            if attempt > 0 {
                guard let delay = Self.retryDelay(attempt: attempt, remaining: deadline.timeIntervalSinceNow)
                else { break }
                try await Task.sleep(for: .seconds(delay))
            }
            var request = URLRequest(url: url)
            request.timeoutInterval = Self.attemptTimeout(remaining: deadline.timeIntervalSinceNow)
            let data: Data
            let status: Int?
            do {
                let (body, response) = try await session.data(for: request)
                data = body
                status = (response as? HTTPURLResponse)?.statusCode
            } catch {
                guard Self.shouldRetry(status: nil, error: error) else { throw error }
                lastError = error
                continue
            }
            if Self.shouldRetry(status: status, error: nil) {
                // 5xx라도 본문이 한도·키 오류면 다시 불러도 같다 — 재시도 없이 바로 알린다
                if case ClientError.quotaExceeded? = Self.decodeError(data, stationName: stationName) {
                    throw ClientError.quotaExceeded
                }
                lastError = ClientError.badResponse
                continue
            }
            // 오류 본문(한도 초과·키 오류)은 decode가 quotaExceeded/badResponse로 가른다
            let quality = try Self.decode(data, stationName: stationName)
            guard let status, (200..<300).contains(status) else { throw ClientError.badResponse }
            return quality
        }
        throw lastError
    }

    /// attempt(1부터)번째 재시도 전 백오프 — 백오프 뒤 최소 1초는 시도할 수 있을 때만 준다.
    /// nil이면 포기한다 (재시도 횟수 소진 또는 10초 상한 임박)
    static func retryDelay(attempt: Int, remaining: TimeInterval) -> TimeInterval? {
        guard retryDelays.indices.contains(attempt - 1) else { return nil }
        let delay = retryDelays[attempt - 1]
        guard remaining > delay + 1 else { return nil }
        return delay
    }

    /// 시도 1회의 타임아웃 — 남은 예산과 maxAttemptTimeout(3초) 중 작은 쪽
    static func attemptTimeout(remaining: TimeInterval) -> TimeInterval {
        min(remaining, maxAttemptTimeout)
    }

    /// decode가 던질 오류만 꺼낸다 — 성공하면 nil
    private static func decodeError(_ data: Data, stationName: String) -> Error? {
        do {
            _ = try decode(data, stationName: stationName)
            return nil
        } catch {
            return error
        }
    }

    /// 재시도 판단 — 5xx(게이트웨이 SERVICETIMEOUT 504 등)와 URLError.timedOut만 일시 장애로 본다.
    /// 4xx·오프라인·취소 등은 다시 불러도 같으므로 즉시 포기한다
    static func shouldRetry(status: Int?, error: Error?) -> Bool {
        if let error {
            return (error as? URLError)?.code == .timedOut
        }
        guard let status else { return false }
        return (500..<600).contains(status)
    }

    /// 응답 디코드 — 수치는 문자열로 오고 결측·통신장애는 "-"다. 숫자로 못 읽으면 nil로 접고,
    /// 등급은 API 공식 등급을 우선 쓰되 빠진 항목만 공식 구간표로 보완한다 (AirQualityEngine 참조)
    ///
    /// 오류 판별 (이슈 #83): resultCode 22·30·31은 quotaExceeded, 그 밖의 오류는 badResponse.
    /// 게이트웨이는 한도·인증 오류를 returnType과 무관하게 XML로 주기도 한다 — `<`로 시작하는 본문은
    /// badResponse로 접되, returnReasonCode가 한도·키 오류면 quotaExceeded로 올린다
    static func decode(_ data: Data, stationName: String) throws -> AirQuality {
        let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("<") {
            let blocked = quotaCodes.contains { text.contains("<returnReasonCode>\($0)</returnReasonCode>") }
            throw blocked ? ClientError.quotaExceeded : ClientError.badResponse
        }
        guard let response = try? JSONDecoder().decode(Response.self, from: data) else {
            throw ClientError.badResponse
        }
        if quotaCodes.contains(response.response.header.resultCode) {
            throw ClientError.quotaExceeded
        }
        guard response.response.header.resultCode == "00",
              let item = response.response.body?.items.first else {
            throw ClientError.badResponse
        }
        let pm10 = number(item.pm10Value)
        let pm25 = number(item.pm25Value)
        let o3 = number(item.o3Value)
        let khai = number(item.khaiValue)
        return AirQuality(
            stationName: stationName,
            dataTime: item.dataTime,
            pm10: pm10,
            pm25: pm25,
            o3: o3,
            khai: khai,
            pm10Grade: grade(item.pm10Grade1h) ?? pm10.map(AirQualityEngine.pm10Grade),
            pm25Grade: grade(item.pm25Grade1h) ?? pm25.map(AirQualityEngine.pm25Grade),
            o3Grade: grade(item.o3Grade) ?? o3.map(AirQualityEngine.o3Grade),
            khaiGrade: grade(item.khaiGrade) ?? khai.map(AirQualityEngine.khaiGrade))
    }

    /// 22 LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR · 30 SERVICE_KEY_IS_NOT_REGISTERED_ERROR ·
    /// 31 DEADLINE_HAS_EXPIRED_ERROR — 셋 다 인증키 단위라 당일 재호출이 무의미하다
    private static let quotaCodes: Set<String> = ["22", "30", "31"]

    private static func number(_ raw: String?) -> Double? {
        raw.flatMap(Double.init)
    }

    private static func grade(_ raw: String?) -> AirGrade? {
        raw.flatMap(Int.init).flatMap(AirGrade.init(rawValue:))
    }

    /// 에어코리아 응답 스키마 그대로 — 필드명은 API의 camelCase를 따른다.
    /// 모든 수치가 문자열 타입인 것도 API 원문이다
    private struct Response: Codable {
        struct Header: Codable {
            let resultCode: String
        }
        struct Item: Codable {
            let dataTime: String?
            let pm10Value: String?
            let pm25Value: String?
            let o3Value: String?
            let khaiValue: String?
            let pm10Grade1h: String?
            let pm25Grade1h: String?
            let o3Grade: String?
            let khaiGrade: String?
        }
        struct Body: Codable {
            let items: [Item]
        }
        struct Inner: Codable {
            let header: Header
            let body: Body?
        }
        let response: Inner
    }
}
