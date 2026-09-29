import Foundation

/// 수면 구간 묶기 — 밤 수면에서 낮잠을 분리하는 순수 로직 (이슈 #99)
///
/// HealthKit 수면 표본은 워치+아이폰이 같은 밤을 중복 기록할 수 있어 먼저 겹침을 병합한다.
/// 기상일로만 묶으면 그날 오후 낮잠이 아침에 끝난 밤 수면에 더해져 수면 시간이 부풀고,
/// 2시간대 밤이 낮잠 덕에 표본 가드를 통과한다. 그래서 간격이 짧게 이어진 구간을 한 블록으로 보고
/// 가장 긴 블록만 그 밤의 수면으로 인정한다 — 새벽에 잠깐 깬 20분은 같은 블록, 낮잠은 제외.
enum SleepBlocks {
    typealias Interval = (start: Date, end: Date)

    /// 겹치거나 맞닿은 구간을 하나로 병합한다 (시작 시각순 정렬 결과)
    static func merge(_ intervals: [Interval]) -> [Interval] {
        var merged: [Interval] = []
        for interval in intervals.sorted(by: { $0.start < $1.start }) {
            if let last = merged.last, interval.start <= last.end {
                if interval.end > last.end { merged[merged.count - 1].end = interval.end }
            } else {
                merged.append(interval)
            }
        }
        return merged
    }

    /// 병합한 구간을 시간순으로 훑어 간격 `maxGapSec` 이내로 이어진 것끼리 블록으로 묶고,
    /// 잠든 시간 합계가 가장 긴 블록의 구간들만 돌려준다 (나머지는 낮잠으로 제외).
    /// 기본 간격 2시간 — 새벽에 깨어 있던 시간은 블록 안에 두되, 합계에는 넣지 않는다.
    static func mainBlock(_ intervals: [Interval], maxGapSec: Double = 7_200) -> [Interval] {
        var blocks: [[Interval]] = []
        for interval in merge(intervals) {
            if let lastEnd = blocks.last?.last?.end,
               interval.start.timeIntervalSince(lastEnd) <= maxGapSec {
                blocks[blocks.count - 1].append(interval)
            } else {
                blocks.append([interval])
            }
        }
        return blocks.max { asleepSec($0) < asleepSec($1) } ?? []
    }

    /// 구간들의 잠든 시간 합계(초)
    static func asleepSec(_ intervals: [Interval]) -> Double {
        intervals.reduce(0.0) { $0 + $1.end.timeIntervalSince($1.start) }
    }
}
