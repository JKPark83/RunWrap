import Foundation

/// 에어코리아 측정소별 실시간 측정정보 클라이언트 (data.go.kr 15073861, 이슈 #8).
/// WeatherClient와 같은 패턴 — URLComponents + async/await, 디코드는 fixture 테스트를 위해 분리.
/// 요청에는 측정소명과 인증키만 실린다 — 사용자 좌표·건강 데이터는 어떤 형태로도 전송하지 않는다 (기획서 §6).
struct AirQualityClient {
    enum ClientError: Error {
        /// resultCode ≠ "00" 또는 응답에 항목이 없음 — 스토어는 unavailable로 접는다
        case badResponse
    }

    /// 홈 진입 후 백그라운드로 도는 조회지만 날씨와 같은 10초 상한을 둔다 —
    /// 오래 기다린 끝의 낡은 수치보다 "안 보여주기"가 낫다 (미노출 가드)
    private static let bounded: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 10
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
        let (data, _) = try await session.data(from: components.url!)
        return try Self.decode(data, stationName: stationName)
    }

    /// 응답 디코드 — 수치는 문자열로 오고 결측·통신장애는 "-"다. 숫자로 못 읽으면 nil로 접고,
    /// 등급은 API 공식 등급을 우선 쓰되 빠진 항목만 공식 구간표로 보완한다 (AirQualityEngine 참조)
    static func decode(_ data: Data, stationName: String) throws -> AirQuality {
        let response = try JSONDecoder().decode(Response.self, from: data)
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
