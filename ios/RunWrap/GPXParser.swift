import Foundation

/// 코스 좌표 한 점 — 엔진 계층은 CoreLocation을 모르므로 자체 타입을 쓴다 (계획서 M12-2).
struct GeoPoint: Equatable {
    let lat: Double
    let lon: Double
}

/// GPX 파일에서 코스 좌표를 뽑는 파서 — 코스 보급 가이드의 입구 (기획서 §4.13).
/// 기록 트랙(trkpt)을 우선 읽고, 트랙 없이 경로 계획만 있는 파일은 rtept로 폴백한다.
/// wpt만 있는 파일은 "경로"가 아니므로 빈 배열 — 화면이 안내 문구로 처리한다
/// (계획서 M12 오픈 이슈 #4).
enum GPXParser {
    static func parse(_ data: Data) -> [GeoPoint] {
        let collector = Collector()
        let parser = XMLParser(data: data)
        parser.delegate = collector
        parser.parse()
        return collector.trackPoints.isEmpty ? collector.routePoints : collector.trackPoints
    }

    private final class Collector: NSObject, XMLParserDelegate {
        var trackPoints: [GeoPoint] = []
        var routePoints: [GeoPoint] = []

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes attributeDict: [String: String]) {
            guard elementName == "trkpt" || elementName == "rtept",
                  let lat = attributeDict["lat"].flatMap(Double.init),
                  let lon = attributeDict["lon"].flatMap(Double.init) else { return }
            let point = GeoPoint(lat: lat, lon: lon)
            if elementName == "trkpt" {
                trackPoints.append(point)
            } else {
                routePoints.append(point)
            }
        }
    }
}
