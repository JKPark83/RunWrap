import Foundation
import Testing
@testable import RunWrap

/// GPX 내보내기 (이슈 #222) — 파서 왕복·요소 생략·UTC 시각·이스케이프·심박 병합.
struct GPXWriterTests {
    private let start = ISO8601DateFormatter().date(from: "2026-10-08T06:00:00Z")!
    /// 위도 0.0001° ≈ 11m (GPXParser 기준 111,195m/°)
    private func point(_ i: Int, lat: Double = 37.5, elevation: Double? = nil, speed: Double? = nil) -> TrackPoint {
        TrackPoint(lat: lat + Double(i) * 0.0001, lon: 127.0, time: start.addingTimeInterval(Double(i) * 3),
                   elevationM: elevation, horizontalAccuracyM: 5, speedMps: speed)
    }

    @Test("GPX 왕복 — 내보낸 파일을 GPXParser가 같은 좌표·세그먼트로 읽는다")
    func roundTrip() {
        // 0~4번 점 뒤 위도 0.02°(≈2.2km) 점프 → maxLegMeters(1km) 초과라 세그먼트 2개
        let first = (0..<5).map { point($0) }
        let second = (5..<9).map { point($0, lat: 37.52) }
        let segments = GPXWriter.segments(first + second)
        #expect(segments == [first, second])

        let gpx = GPXWriter.gpx(name: "아침 러닝", start: start, segments: segments)
        let parsed = GPXParser.parseSegments(Data(gpx.utf8))
        #expect(parsed.count == 2)
        #expect(parsed.map(\.count) == [5, 4])
        // 좌표는 소수 7자리로 쓴다 — 1e-7° 안에서 같다
        for (written, read) in zip(segments.flatMap { $0 }, parsed.flatMap { $0 }) {
            #expect(abs(written.lat - read.lat) < 1e-7)
            #expect(abs(written.lon - read.lon) < 1e-7)
        }
    }

    @Test("심박 없음 — hr 요소를 0이 아니라 생략한다")
    func omitsMissingHeartRate() {
        let gpx = GPXWriter.gpx(name: "러닝", start: start, segments: [[point(0), point(1)]])
        #expect(!gpx.contains("gpxtpx:hr"))
        // 고도·속도도 없으면 생략 — 확장 블록 자체가 없다
        #expect(!gpx.contains("<ele>"))
        #expect(!gpx.contains("<extensions>"))
    }

    @Test("심박 병합 — ±5초 안 가장 가까운 샘플을 쓰고, 벗어나면 생략한다")
    func mergesNearestHeartRate() {
        // 점 시각 0s·3s·30s. 샘플 -2s(150)·4s(162.4)·20s(170)
        // 0s → -2s(2초) 150 / 3s → 4s(1초) 162 / 30s → 최근접 20s가 10초 → 생략
        let samples = [(time: start.addingTimeInterval(-2), bpm: 150.0),
                       (time: start.addingTimeInterval(4), bpm: 162.4),
                       (time: start.addingTimeInterval(20), bpm: 170.0)]
        let gpx = GPXWriter.gpx(name: "러닝", start: start, segments: [[point(0), point(1), point(10)]],
                                heartRates: samples)
        #expect(gpx.components(separatedBy: "<gpxtpx:hr>").count - 1 == 2)
        #expect(gpx.contains("<gpxtpx:hr>150</gpxtpx:hr>"))
        #expect(gpx.contains("<gpxtpx:hr>162</gpxtpx:hr>"))
    }

    @Test("시각 UTC 포맷 — 모든 trkpt에 ISO8601 Z 시각, 고도·속도는 값이 있을 때만")
    func utcTimeAndOptionalElements() {
        let gpx = GPXWriter.gpx(name: "러닝", start: start,
                                segments: [[point(0, elevation: 12.34, speed: 3.456), point(1)]])
        #expect(gpx.contains("<metadata><time>2026-10-08T06:00:00Z</time></metadata>"))
        #expect(gpx.contains("<time>2026-10-08T06:00:03Z</time>"))
        #expect(gpx.components(separatedBy: "<time>").count - 1 == 3)  // metadata 1 + trkpt 2
        #expect(gpx.contains("<ele>12.3</ele>"))
        #expect(gpx.contains("<gpxtpx:speed>3.46</gpxtpx:speed>"))
    }

    @Test("운동 이름 XML 이스케이프 — & < > \" '")
    func escapesName() {
        let gpx = GPXWriter.gpx(name: #"한강 <5K> & "LSD" 'easy'"#, start: start, segments: [[point(0)]])
        #expect(gpx.contains("<name>한강 &lt;5K&gt; &amp; &quot;LSD&quot; &apos;easy&apos;</name>"))
        // 이스케이프한 파일은 파서가 끝까지 읽는다
        #expect(GPXParser.parse(Data(gpx.utf8)).count == 1)
    }

    @Test("파일 이름 — 러닝-연-월-일.gpx (기기 시간대)")
    func fileName() {
        // 2026-10-08T06:00Z = 서울 15:00, 호놀룰루(-10) 전날 20:00
        #expect(GPXWriter.fileName(start: start, timeZone: TimeZone(identifier: "Asia/Seoul")!) == "러닝-2026-10-08.gpx")
        #expect(GPXWriter.fileName(start: start, timeZone: TimeZone(identifier: "Pacific/Honolulu")!) == "러닝-2026-10-07.gpx")
    }
}
