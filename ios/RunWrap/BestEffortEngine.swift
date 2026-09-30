import Foundation

/// 베스트 에포트 (이슈 #166) — 한 세션 안에서 목표 거리(1K·5K·10K·하프·풀)를
/// 가장 빨리 지난 연속 구간의 소요 시간. 거리별 최고 기록(PersonalRecords)의 재료.
///
/// 왜: 예전 PR 산식(세션 평균 페이스 × 공인 거리)은 10km 세션 안의 빠른 5km를 놓치고,
/// 완주 거리가 공인 거리 근처인 세션만 후보로 삼았다. 거리 샘플 시계열에서 직접 재면
/// 긴 세션 안의 구간 기록도 잡힌다.
///
/// 시간은 벽시계 기준(정지 포함) — 대회 기록과 같은 정의라 보수적이다.
/// 순수 로직: Foundation만 쓰고 입력만으로 결과가 정해진다.
enum BestEffortEngine {
    /// 목표 거리 5종 (m) — 하프·풀은 공인 거리
    static let targets: [(label: String, meters: Double)] = [
        ("1K", 1_000), ("5K", 5_000), ("10K", 10_000), ("하프", 21_097.5), ("풀", 42_195),
    ]

    /// 타당 페이스 범위(초/km) — RunSummary.paceSecPerKm과 같은 기준 (이슈 #76).
    /// 범위 밖 구간은 GPS 튐·걷기 같은 러닝이 아닌 표본으로 보고 항목째 버린다.
    static let plausiblePace: ClosedRange<Double> = 150...1_200

    /// 거리 샘플 → 누적 (time, meters) 타임라인 → 목표 거리별 최소 소요 시간(초).
    /// 못 채운 거리·타당 범위 밖 기록은 빠진다 (key = targetMeters)
    static func bestEfforts(distanceSamples: [(start: Date, end: Date, meters: Double)]) -> [Double: Double] {
        let samples = distanceSamples
            .filter { $0.meters > 0 }             // 음수·0 거리 샘플은 건너뛴다
            .sorted { $0.start < $1.start }
        guard let first = samples.first else { return [:] }

        // 누적 포인트 — 맨 앞은 (첫 샘플 시작, 0m), 이후는 샘플 끝 시각의 누적 거리
        var cum: [(t: Double, d: Double)] = [(first.start.timeIntervalSinceReferenceDate, 0)]
        var total = 0.0
        for sample in samples {
            total += sample.meters
            cum.append((sample.end.timeIntervalSinceReferenceDate, total))
        }

        var result: [Double: Double] = [:]
        for target in targets where total >= target.meters {
            guard let best = minimumTime(cum, distance: target.meters) else { continue }
            let pace = best / (target.meters / 1_000)
            guard plausiblePace.contains(pace) else { continue }
            result[target.meters] = best
        }
        return result
    }

    /// 투 포인터 — 시작점 i마다 cum[j].d − cum[i].d ≥ D인 최소 j를 유지하고,
    /// 구간 (j−1, j) 안에서 정확히 D가 되는 시각을 선형 보간한다. O(n)
    private static func minimumTime(_ cum: [(t: Double, d: Double)], distance: Double) -> Double? {
        var best: Double?
        var j = 1
        for i in 0..<cum.count {
            j = max(j, i + 1)
            while j < cum.count, cum[j].d - cum[i].d < distance { j += 1 }
            guard j < cum.count else { break }   // 이 시작점부터는 D를 못 채운다 — 뒤도 마찬가지
            let prev = cum[j - 1]
            let goal = cum[i].d + distance
            let fraction = (goal - prev.d) / (cum[j].d - prev.d)
            let time = prev.t + fraction * (cum[j].t - prev.t) - cum[i].t
            if best.map({ time < $0 }) ?? true { best = time }
        }
        return best
    }
}
