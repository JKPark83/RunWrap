import Foundation

/// 코스 좌표 한 점 — 엔진 계층은 CoreLocation을 모르므로 자체 타입을 쓴다 (계획서 M12-2).
/// Sendable은 코스 화면이 파싱·분석을 백그라운드 태스크에서 돌려 결과를 넘기기 때문이다 (#147)
struct GeoPoint: Equatable, Sendable {
    let lat: Double
    let lon: Double
}

/// GPX 파일에서 코스 좌표를 뽑는 파서 — 코스 보급 가이드의 입구 (기획서 §4.13).
/// 기록 트랙(trkpt)을 우선 읽고, 트랙 없이 경로 계획만 있는 파일은 rtept로 폴백한다.
/// wpt만 있는 파일은 "경로"가 아니므로 빈 배열 — 화면이 안내 문구로 처리한다
/// (계획서 M12 오픈 이슈 #4).
enum GPXParser {
    /// 세그먼트 구분 없이 이어 붙인 좌표 — 세그먼트를 모르는 호출부·테스트 호환용
    static func parse(_ data: Data) -> [GeoPoint] {
        parseSegments(data).flatMap { $0 }
    }

    /// 끊긴 구간을 나눠 읽는다 — trkseg·trk 경계마다 새 배열, 빈 세그먼트는 버린다 (#150).
    /// 두 트랙을 한 파일에 담은 GPX를 그냥 이으면 사이의 수 km 점프가 코스 거리·매칭에 섞인다.
    /// 기록 트랙(trkpt)은 1~수 초 간격이라 인접 점이 `maxLegMeters`보다 멀면 GPS 끊김·일시정지 뒤
    /// 재개로 보고 그 자리에서도 끊는다. 경로 계획(rtept)은 점이 드물어(직선 구간은 점 2개)
    /// 긴 간격이 정상이므로 끊지 않고 전체를 세그먼트 1개로 본다
    static func parseSegments(_ data: Data) -> [[GeoPoint]] {
        let collector = Collector()
        let parser = XMLParser(data: data)
        parser.delegate = collector
        parser.parse()
        collector.closeSegment()
        if !collector.trackSegments.isEmpty { return collector.trackSegments }
        return collector.routePoints.isEmpty ? [] : [collector.routePoints]
    }

    /// 기록 트랙에서 끊김으로 보는 인접 점 간격 — 1초 기록이면 1km는 달려서 낼 수 없는 거리다
    static let maxLegMeters = 1_000.0
    private static let metersPerDegree = 111_195.0

    /// 인접 두 점의 대략 거리(m) — 등장방형 근사. 끊김 판정용이라 정밀도는 중요치 않다
    static func roughMeters(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let lonScale = metersPerDegree * cos((a.lat + b.lat) / 2 * .pi / 180)
        return hypot((b.lon - a.lon) * lonScale, (b.lat - a.lat) * metersPerDegree)
    }

    private final class Collector: NSObject, XMLParserDelegate {
        var trackSegments: [[GeoPoint]] = []
        var currentSegment: [GeoPoint] = []
        var routePoints: [GeoPoint] = []

        /// 모으던 트랙 세그먼트를 닫는다 — 비어 있으면 버린다
        func closeSegment() {
            guard !currentSegment.isEmpty else { return }
            trackSegments.append(currentSegment)
            currentSegment = []
        }

        func parser(_ parser: XMLParser, didStartElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?,
                    attributes attributeDict: [String: String]) {
            if elementName == "trk" || elementName == "trkseg" {
                closeSegment()
                return
            }
            guard elementName == "trkpt" || elementName == "rtept",
                  let lat = attributeDict["lat"].flatMap(Double.init),
                  let lon = attributeDict["lon"].flatMap(Double.init),
                  // 범위 밖·비유한 좌표(lat 120, nan, inf 등)는 조용히 버린다 — 엔진의 경도 축척이
                  // 0 이하·NaN이 되어 크래시를 낸 원인이다 (2026-09-29 감사 M12)
                  lat.isFinite, lon.isFinite,
                  (-90...90).contains(lat), (-180...180).contains(lon) else { return }
            let point = GeoPoint(lat: lat, lon: lon)
            if elementName == "trkpt" {
                if let last = currentSegment.last, roughMeters(last, point) > maxLegMeters { closeSegment() }
                currentSegment.append(point)
            } else {
                routePoints.append(point)
            }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String,
                    namespaceURI: String?, qualifiedName qName: String?) {
            if elementName == "trk" || elementName == "trkseg" { closeSegment() }
        }
    }
}
