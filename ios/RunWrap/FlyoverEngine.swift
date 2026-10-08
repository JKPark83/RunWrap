import Foundation

/// 앱 안 경로 플라이오버 (이슈 #224, 경로 기능 리서치 §04 B′안) — 카메라 키프레임과 재생 진행률 → 경로 위 위치.
/// 카메라는 `mapCameraKeyframeAnimator`가, 점·HUD는 `TimelineView`가 같은 재생 시계로 그린다.
/// 둘이 어긋나지 않게 키프레임은 "그 지점에 실제로 도착한 시각"에 맞춰 놓는다 —
/// 키프레임 사이 길이가 실제 소요 시간에 비례하므로 빨리 달린 구간은 카메라도 빨리 지나간다.
/// 순수 로직이라 MapKit·CoreLocation을 모른다 — 좌표는 위경도 Double로만 다룬다.
enum FlyoverEngine {
    /// 이보다 점이 적으면 경로가 너무 성겨 플라이오버를 내지 않는다(진입 버튼 미노출)
    static let minPoints = 20
    /// 재생 길이(초) — 이슈 #224 "15~20초로 정규화"
    static let playbackSec: Double = 18
    /// 카메라 키프레임 구간 수 — 이슈 #224 "30~60개"
    static let keyframeCount = 40
    /// heading 이동 평균 반폭 — 앞뒤 2개씩 5개 평균 (급커브에서 카메라가 튀지 않게)
    static let headingHalfWindow = 2
    /// 현재 페이스를 재는 뒤쪽 창(m) — 직전 500m 평균
    static let paceWindowM: Double = 500
    /// 이보다 덜 달렸으면 페이스 표본이 부족해 내지 않는다(nil)
    static let minPaceDistanceM: Double = 100

    /// 카메라 키프레임 — `durationSec`는 직전 키프레임에서 여기까지 걸리는 재생 시간(첫 키프레임은 0)
    struct Keyframe: Equatable {
        let lat: Double
        let lon: Double
        /// 진행 방위(도, 북=0 시계방향) — 이동 평균 후 연속값으로 펼쳐 359→1처럼 넘어갈 때 한 바퀴 돌지 않는다
        let headingDeg: Double
        let durationSec: Double
    }

    /// 재생 진행률 t 시점의 경로 위 상태
    struct Frame: Equatable {
        let lat: Double
        let lon: Double
        /// 완전히 지나온 경로 점 수 — 지나온 경로 선은 앞 `passedCount`개 점 + 현재 위치
        let passedCount: Int
        let distanceM: Double
        let elapsedSec: Double
        /// 직전 500m 평균 페이스(초/km) — 100m 미만이면 nil
        let paceSecPerKm: Double?
    }

    /// 누적 거리·경과 시간을 미리 쌓아 둔 경로
    struct Track {
        let points: [TrackPoint]
        let cumulativeM: [Double]
        let elapsedSec: [Double]
        var totalM: Double { cumulativeM[cumulativeM.count - 1] }
        var totalSec: Double { elapsedSec[elapsedSec.count - 1] }
    }

    /// 점이 `minPoints` 미만이거나 거리·시간이 0이면 nil — 플라이오버를 내지 않는다.
    /// `distanceM`(세션 기록 거리)을 주면 누적 거리를 그 길이로 맞춘다 — GPS 경로 길이는 워치 기록 거리와
    /// 조금씩 달라서, 그대로 두면 HUD 끝 거리가 세션 카드의 거리와 어긋난다
    static func track(_ route: [TrackPoint], distanceM: Double? = nil) -> Track? {
        guard route.count >= minPoints else { return nil }
        var cumulative: [Double] = [0]
        var elapsed: [Double] = [0]
        cumulative.reserveCapacity(route.count)
        elapsed.reserveCapacity(route.count)
        let start = route[0].time
        for i in 1..<route.count {
            cumulative.append(cumulative[i - 1] + haversineM(route[i - 1], route[i]))
            // 시각이 뒤집힌 점이 섞여도 경과 시간은 줄지 않게 한다 (이진 탐색 전제)
            elapsed.append(max(elapsed[i - 1], route[i].time.timeIntervalSince(start)))
        }
        let raw = cumulative[route.count - 1]
        guard raw > 0, elapsed[route.count - 1] > 0 else { return nil }
        if let distanceM, distanceM > 0 {
            cumulative = cumulative.map { $0 * distanceM / raw }
        }
        return Track(points: route, cumulativeM: cumulative, elapsedSec: elapsed)
    }

    /// 경로를 거리로 `count` 등분한 `count + 1`개 키프레임. duration 합 = `playbackSec`
    static func keyframes(_ track: Track, count: Int = keyframeCount,
                          playbackSec: Double = playbackSec) -> [Keyframe] {
        let stops = (0...count).map { sample(track, keys: track.cumulativeM,
                                             value: track.totalM * Double($0) / Double(count)) }
        // 방위: 다음 지점을 향한다(마지막은 직전 방향 유지)
        let raw = stops.indices.map { k in
            k < count ? bearingDeg(stops[k], stops[k + 1]) : bearingDeg(stops[k - 1], stops[k])
        }
        // 원형 이동 평균 — 방위는 각도라 sin·cos 평균으로 낸다
        var headings: [Double] = []
        for k in raw.indices {
            let window = raw[max(0, k - headingHalfWindow)...min(count, k + headingHalfWindow)]
            let s = window.reduce(0) { $0 + sin($1 * .pi / 180) }
            let c = window.reduce(0) { $0 + cos($1 * .pi / 180) }
            let mean = atan2(s, c) * 180 / .pi
            guard let previous = headings.last else { headings.append(mean); continue }
            // 직전 값과의 차이를 (-180, 180]로 접어 연속값으로 펼친다
            var delta = (mean - previous).truncatingRemainder(dividingBy: 360)
            if delta > 180 { delta -= 360 }
            if delta <= -180 { delta += 360 }
            headings.append(previous + delta)
        }
        return stops.indices.map { k in
            let duration = k == 0 ? 0
                : (stops[k].elapsedSec - stops[k - 1].elapsedSec) / track.totalSec * playbackSec
            return Keyframe(lat: stops[k].lat, lon: stops[k].lon,
                            headingDeg: headings[k], durationSec: duration)
        }
    }

    /// 진행률 t(0~1) → 경로 위 위치. 카메라 키프레임과 같은 시간축(실제 경과 시간 비례)이다
    static func frame(_ track: Track, progress t: Double) -> Frame {
        let current = sample(track, keys: track.elapsedSec, value: min(max(t, 0), 1) * track.totalSec)
        var pace: Double?
        if current.distanceM >= minPaceDistanceM {
            let from = sample(track, keys: track.cumulativeM,
                              value: max(0, current.distanceM - paceWindowM))
            pace = (current.elapsedSec - from.elapsedSec) / (current.distanceM - from.distanceM) * 1_000
        }
        return Frame(lat: current.lat, lon: current.lon, passedCount: current.passedCount,
                     distanceM: current.distanceM, elapsedSec: current.elapsedSec, paceSecPerKm: pace)
    }

    // MARK: 보간

    private struct Sample {
        let lat: Double, lon: Double, distanceM: Double, elapsedSec: Double, passedCount: Int
    }

    /// 오름차순 `keys`(누적 거리 또는 경과 시간)에서 `value`가 놓인 구간을 찾아 선형 보간한다
    private static func sample(_ track: Track, keys: [Double], value: Double) -> Sample {
        // value 이상인 첫 키 — 이진 탐색
        var lo = 0, hi = keys.count - 1
        while lo < hi {
            let mid = (lo + hi) / 2
            if keys[mid] < value { lo = mid + 1 } else { hi = mid }
        }
        let i = lo
        let a = track.points[max(i - 1, 0)], b = track.points[i]
        let span = i > 0 ? keys[i] - keys[i - 1] : 0
        let f = span > 0 ? min(1, max(0, (value - keys[i - 1]) / span)) : 1
        let j = max(i - 1, 0)
        return Sample(lat: a.lat + (b.lat - a.lat) * f,
                      lon: a.lon + (b.lon - a.lon) * f,
                      distanceM: track.cumulativeM[j] + (track.cumulativeM[i] - track.cumulativeM[j]) * f,
                      elapsedSec: track.elapsedSec[j] + (track.elapsedSec[i] - track.elapsedSec[j]) * f,
                      passedCount: f >= 1 ? i + 1 : i)
    }

    /// 두 좌표의 대권 거리(m) — 하버사인, 지구 반지름 6,371km (Android `RoutePrivacy`와 같은 식)
    private static func haversineM(_ a: TrackPoint, _ b: TrackPoint) -> Double {
        let dLat = (b.lat - a.lat) * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(a.lat * .pi / 180) * cos(b.lat * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000 * asin(min(1, h.squareRoot()))
    }

    /// a → b 초기 방위(도, 0~360)
    private static func bearingDeg(_ a: Sample, _ b: Sample) -> Double {
        let lat1 = a.lat * .pi / 180, lat2 = b.lat * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let deg = atan2(y, x) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }
}
