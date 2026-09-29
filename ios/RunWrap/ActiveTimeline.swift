import Foundation

/// 활동 타임라인 엔진 — 오토포즈·신호 대기로 멈춘 시간을 km 스플릿과 심박 드리프트에서
/// 빼기 위한 순수 로직 (감사 리포트 M5·M6, 이슈 #47).
///
/// 왜 필요한가: `HKWorkout.duration`은 일시정지를 뺀 활동 시간인데, 샘플 시각은
/// 벽시계라 둘을 그대로 섞으면 정지 시간이 스플릿에 더해지고(“페이스 유지 실패” 오판)
/// 드리프트의 중앙 시각이 어긋나 거짓 improving이 나온다.
/// HealthKit 타입은 받지 않는다 — 스토어가 이벤트·샘플을 값 타입으로 바꿔 넘긴다.
enum ActiveTimeline {
    /// 워크아웃 이벤트를 엔진용으로 옮긴 표식. 사용자 정지(pause/resume)와
    /// 모션 정지(motionPaused/motionResumed)는 서로 섞여 올 수 있어 따로 추적한다.
    enum Marker {
        case pause, resume, motionPause, motionResume
    }

    /// 표식 → 정지 구간. 사용자 정지와 모션 정지 중 하나라도 걸려 있으면 정지 상태로 본다
    /// (둘을 한 줄로 합치면 pause→motionPause→motionResume→resume 순서에서 정지를 덜 뺀다).
    /// 짝 없는 재개·중복 정지는 무시하고, 끝까지 닫히지 않은 정지는 end에서 닫는다.
    /// 결과는 [start, end]로 자르고 길이 0인 구간은 버린다.
    static func pauses(markers: [(date: Date, kind: Marker)], start: Date, end: Date) -> [DateInterval] {
        var userPaused = false
        var motionPaused = false
        var pauseStart: Date?
        var result: [DateInterval] = []

        func close(at date: Date) {
            guard let from = pauseStart else { return }
            let clippedStart = max(from, start)
            let clippedEnd = min(date, end)
            if clippedEnd > clippedStart {
                result.append(DateInterval(start: clippedStart, end: clippedEnd))
            }
            pauseStart = nil
        }

        for marker in markers.sorted(by: { $0.date < $1.date }) {
            let wasPaused = userPaused || motionPaused
            switch marker.kind {
            case .pause: userPaused = true
            case .resume: userPaused = false
            case .motionPause: motionPaused = true
            case .motionResume: motionPaused = false
            }
            let isPaused = userPaused || motionPaused
            if !wasPaused, isPaused {
                pauseStart = marker.date
            } else if wasPaused, !isPaused {
                close(at: marker.date)
            }
        }
        close(at: end)
        return result
    }

    /// 시각이 정지 구간 안인지 — 재개 시각의 샘플은 활동으로 친다(반열린 구간)
    static func isPaused(_ date: Date, pauses: [DateInterval]) -> Bool {
        pauses.contains { $0.start <= date && date < $0.end }
    }

    /// from~to 벽시계 구간에서 정지 구간과 겹친 만큼을 뺀 활동 초
    static func activeSeconds(from: Date, to: Date, pauses: [DateInterval]) -> Double {
        let wall = max(to.timeIntervalSince(from), 0)
        let paused = pauses.reduce(0.0) { sum, pause in
            let overlap = min(to, pause.end).timeIntervalSince(max(from, pause.start))
            return sum + max(overlap, 0)
        }
        return max(wall - paused, 0)
    }

    /// start부터 활동 seconds초가 흐른 벽시계 시각 — 앞선 정지 구간 길이를 차례로 더한다
    static func wallTime(afterActive seconds: Double, from start: Date, pauses: [DateInterval]) -> Date {
        var cursor = start
        var remaining = seconds
        for pause in pauses.sorted(by: { $0.start < $1.start }) where pause.end > cursor {
            let runUntilPause = max(pause.start.timeIntervalSince(cursor), 0)
            if remaining <= runUntilPause { return cursor.addingTimeInterval(remaining) }
            remaining -= runUntilPause
            cursor = pause.end
        }
        return cursor.addingTimeInterval(remaining)
    }

    /// 이벤트가 없는 기록의 폴백 — 거리 샘플 사이가 minGap초 이상 벌어진 곳을 정지로 본다
    /// (감사 리포트 M6 제안값 10초). 정지가 없는 세션의 GPS 공백까지 빼지 않도록
    /// 호출부가 “벽시계 − 활동 시간 > 30초”일 때만 부른다.
    static func gapPauses(_ samples: [(start: Date, end: Date, meters: Double)],
                          minGap: TimeInterval = 10) -> [DateInterval] {
        let sorted = samples.sorted { $0.start < $1.start }
        guard var lastEnd = sorted.first?.end else { return [] }
        var result: [DateInterval] = []
        for sample in sorted.dropFirst() {
            if sample.start.timeIntervalSince(lastEnd) >= minGap {
                result.append(DateInterval(start: lastEnd, end: sample.start))
            }
            lastEnd = max(lastEnd, sample.end)
        }
        return result
    }

    /// 누적 거리 샘플 → km 스플릿. km 경계는 샘플 사이를 선형 보간한다.
    /// index는 실제 km 번호 — 데이터 오류로 건너뛴 구간이 있어도 눈금이 밀리지 않는다.
    /// 경계 사이 시간에서 정지 구간을 뺀다 — pauses가 비면 벽시계 차이 그대로다.
    static func splits(distanceSamples samples: [(start: Date, end: Date, meters: Double)],
                       pauses: [DateInterval]) -> [(index: Int, paceSecPerKm: Double)] {
        var result: [(index: Int, paceSecPerKm: Double)] = []
        var cumulative: Double = 0        // m
        var boundaryTime: Date? = samples.first?.start
        var nextBoundary: Double = 1000

        for sample in samples {
            let meters = sample.meters
            let before = cumulative
            cumulative += meters
            while cumulative >= nextBoundary, meters > 0 {
                let fraction = (nextBoundary - before) / meters
                let duration = sample.end.timeIntervalSince(sample.start)
                let crossing = sample.start.addingTimeInterval(duration * fraction)
                if let start = boundaryTime {
                    let sec = activeSeconds(from: start, to: crossing, pauses: pauses)
                    if sec > 60 {  // 60초/km 미만은 데이터 오류로 본다
                        result.append((index: Int(nextBoundary / 1000), paceSecPerKm: sec))
                    }
                }
                boundaryTime = crossing
                nextBoundary += 1000
            }
        }
        return result
    }
}
