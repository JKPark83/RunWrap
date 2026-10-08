import Foundation

/// 러닝 기록 → GPX 1.1 문자열 (이슈 #222). Apple 건강 앱은 운동을 GPX로 내보내지 못해서
/// 사용자가 요청할 때만 Strava·Garmin 등으로 옮길 파일을 만든다 — 앱이 직접 전송하지 않는다.
/// 심박은 Garmin TrackPointExtension v2(`gpxtpx:hr`·`gpxtpx:speed`)에 싣는다.
/// 규칙(이슈 #222 §1):
/// - 모든 `<trkpt>`에 `<time>`(ISO8601 UTC) — Strava는 시각이 없으면 "Time information is missing"으로 거부한다
/// - 값이 없으면 0을 쓰지 않고 요소를 생략한다 (hr 1~255만, 고도·속도는 nil이면 생략)
/// - 세그먼트는 `GPXParser.maxLegMeters`와 같은 기준으로 나눠, 내보낸 파일을 다시 읽어도 같은 세그먼트가 된다
/// ponytail: 케이던스(`gpxtpx:cad`)는 점별 걸음 샘플 쿼리가 없고 단위(spm·spm/2)도 Strava 확인 전이라 생략 —
/// 실기기 업로드로 단위를 정하면 걸음 샘플을 심박처럼 병합해 붙인다
enum GPXWriter {
    /// 심박 병합 허용 오차 — 점 시각에서 이보다 먼 샘플은 붙이지 않는다 (이슈 #222)
    static let heartRateToleranceSec = 5.0

    /// 인접 점이 `GPXParser.maxLegMeters`보다 멀면 끊는다 — 일시정지·GPS 끊김 뒤 재개 (파서와 같은 기준)
    static func segments(_ points: [TrackPoint]) -> [[TrackPoint]] {
        var result: [[TrackPoint]] = []
        var current: [TrackPoint] = []
        for point in points {
            if let last = current.last,
               GPXParser.roughMeters(GeoPoint(lat: last.lat, lon: last.lon),
                                     GeoPoint(lat: point.lat, lon: point.lon)) > GPXParser.maxLegMeters {
                result.append(current)
                current = []
            }
            current.append(point)
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    /// heartRates: 시각 오름차순 심박 샘플 — 존 계산에 쓴 샘플을 그대로 받는다(추가 쿼리 없음)
    static func gpx(name: String, start: Date, segments: [[TrackPoint]],
                    heartRates: [(time: Date, bpm: Double)] = []) -> String {
        let iso = ISO8601DateFormatter()  // 기본 옵션 = UTC "2026-10-08T06:00:00Z"
        var lines = [
            #"<?xml version="1.0" encoding="UTF-8"?>"#,
            #"<gpx version="1.1" creator="런미새" xmlns="http://www.topografix.com/GPX/1/1" "#
                + #"xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2">"#,
            "  <metadata><time>\(iso.string(from: start))</time></metadata>",
            "  <trk>",
            "    <name>\(escaped(name))</name>",
            "    <type>running</type>",
        ]
        for segment in segments where !segment.isEmpty {
            lines.append("    <trkseg>")
            for point in segment {
                lines.append(#"      <trkpt lat="\#(String(format: "%.7f", point.lat))" lon="\#(String(format: "%.7f", point.lon))">"#)
                if let elevation = point.elevationM {
                    lines.append("        <ele>\(String(format: "%.1f", elevation))</ele>")
                }
                lines.append("        <time>\(iso.string(from: point.time))</time>")
                var extensions: [String] = []
                if let bpm = nearestBpm(at: point.time, in: heartRates) {
                    extensions.append("<gpxtpx:hr>\(bpm)</gpxtpx:hr>")
                }
                if let speed = point.speedMps {
                    extensions.append("<gpxtpx:speed>\(String(format: "%.2f", speed))</gpxtpx:speed>")
                }
                if !extensions.isEmpty {
                    lines.append("        <extensions><gpxtpx:TrackPointExtension>"
                                 + extensions.joined() + "</gpxtpx:TrackPointExtension></extensions>")
                }
                lines.append("      </trkpt>")
            }
            lines.append("    </trkseg>")
        }
        lines += ["  </trk>", "</gpx>", ""]
        return lines.joined(separator: "\n")
    }

    /// "러닝-2026-10-08.gpx" — 날짜는 기기 시간대, 서기 연도
    static func fileName(start: Date, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return "러닝-\(formatter.string(from: start)).gpx"
    }

    /// ±5초 안의 가장 가까운 샘플(이진 탐색). 1~255 bpm 밖이면 생략
    private static func nearestBpm(at time: Date, in samples: [(time: Date, bpm: Double)]) -> Int? {
        guard !samples.isEmpty else { return nil }
        var low = 0, high = samples.count
        while low < high {
            let mid = (low + high) / 2
            if samples[mid].time < time { low = mid + 1 } else { high = mid }
        }
        let nearest = [low - 1, low].filter(samples.indices.contains).min {
            abs(samples[$0].time.timeIntervalSince(time)) < abs(samples[$1].time.timeIntervalSince(time))
        }
        guard let nearest,
              abs(samples[nearest].time.timeIntervalSince(time)) <= heartRateToleranceSec else { return nil }
        let bpm = Int(samples[nearest].bpm.rounded())
        return (1...255).contains(bpm) ? bpm : nil
    }

    private static func escaped(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}
