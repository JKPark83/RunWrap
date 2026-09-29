import Foundation

/// 심박 드리프트(Pw:HR 디커플링) 엔진 — 같은 페이스인데 후반 심박이 슬금슬금
/// 오르면 유산소 기반이 아직 부족하다는 신호다 (Friel 디커플링, 기획 문서
/// "HealthKit 미활용 데이터 활용 제안 A2"). 세션을 시간 중앙으로 전·후반을
/// 나눠 효율(EF = 거리 ÷ 총 심박수)을 비교한다. 세션 상세 화면 카드의 재료.
///
/// 인터벌·빌드업처럼 구간마다 페이스가 크게 바뀌는 러닝은 디커플링 해석이
/// 성립하지 않는다 — 정속주 전용 지표라 전/후반 페이스 차이가 크면 판정하지
/// 않는다. "틀린 인사이트는 없느니만 못하다."
///
/// 전·후반 평균만 보면 워밍업·쿨다운이 대칭인 인터벌(빠름/느림 교대)은 평균이 같아
/// 통과해 버린다 — 심박 지연으로 흔들린 EF 비교가 "유산소 기반 부족"으로 나간다.
/// 그래서 1km 스플릿 페이스의 변동계수(표준편차 ÷ 평균)도 본다: 8%를 넘으면 정속주가
/// 아니다. 스플릿 시간에서는 정지 구간을 뺀다(ActiveTimeline — 전·후반 페이스와 같은 원칙).
/// 스플릿이 3개 미만이면 변동을 판단할 표본이 없어 이 가드는 건너뛴다 (감사 2026-09-29, 이슈 #100).
enum DriftEngine {
    struct Result: Equatable {
        let decouplingPct: Double  // (전반 EF ÷ 후반 EF − 1) × 100. 양수 = 후반 효율 저하(드리프트)
        let firstHalfEF: Double    // meters per beat
        let secondHalfEF: Double
        let tone: RRTone
    }

    private static let minDurationSec = 1_800.0  // Friel 권장 60분 이상, 우리는 30분을 하한으로 완화
    private static let minSamplesPerHalf = 20
    private static let maxPaceDiffPct = 10.0      // 이 이상 벌어지면 정속주로 보지 않는다(인터벌·빌드업)
    private static let maxSplitCV = 0.08          // 1km 스플릿 페이스 변동계수 상한 — 넘으면 인터벌로 본다
    private static let minSplitsForCV = 3         // 이보다 적으면 변동계수 가드를 건너뛴다
    private static let minTailMeters = 500.0      // 마지막 잔여 구간은 이 이상일 때만 스플릿으로 친다
    private static let improvingThreshold = -2.0  // 이하면 후반이 더 효율적
    private static let cautionThreshold = 5.0     // 미만이면 유산소 기반 탄탄(Friel 기준)

    private static let maxTimelineGapSec = 30.0  // 벽시계 − 활동 − 정지 합이 이보다 크면 타임라인 복원 불가 (감사 M5)

    /// - durationSec: 활동 시간(일시정지 제외, HKWorkout.duration)
    /// - pauses: 정지 구간(벽시계). 중앙 시각을 활동 기준으로 옮기고 정지 중 심박을 뺀다 (이슈 #47)
    /// - end: 워크아웃 종료 벽시계 시각. 주어지면 정지 구간으로 벽시계와 활동 시간이
    ///   맞춰지는지 확인하고, 안 맞으면 판정하지 않는다(표본 부족 원칙)
    static func compute(hrSamples: [(time: Date, bpm: Double)],
                        distanceSamples: [(start: Date, end: Date, meters: Double)],
                        start: Date, durationSec: Double,
                        pauses: [DateInterval] = [], end: Date? = nil) -> Result? {
        guard durationSec >= minDurationSec else { return nil }

        if let end {
            let pausedSec = pauses.reduce(0.0) { $0 + $1.duration }
            let unaccounted = end.timeIntervalSince(start) - durationSec - pausedSec
            guard abs(unaccounted) <= maxTimelineGapSec else { return nil }
        }

        // 중앙 시각은 활동 시간의 절반 지점 — 앞선 정지 구간만큼 벽시계로 밀린다
        let midpoint = ActiveTimeline.wallTime(afterActive: durationSec / 2, from: start, pauses: pauses)

        // 정지 중 심박(신호 대기의 낮은 심박)은 효율 비교에서 뺀다
        let activeHR = hrSamples.filter { !ActiveTimeline.isPaused($0.time, pauses: pauses) }
        let firstHR = activeHR.filter { $0.time < midpoint }
        let secondHR = activeHR.filter { $0.time >= midpoint }
        guard firstHR.count >= minSamplesPerHalf, secondHR.count >= minSamplesPerHalf else { return nil }

        let (firstMeters, secondMeters) = splitDistance(distanceSamples, at: midpoint)
        guard firstMeters > 0, secondMeters > 0 else { return nil }

        // 전·후반 모두 활동 시간의 절반 (정지 구간은 중앙 시각 계산에서 이미 건너뛰었다)
        let firstMinutes = durationSec / 2 / 60
        let secondMinutes = firstMinutes

        // 전/후반 페이스(분/km) 차이가 크면 정속주가 아니다 — 디커플링 해석 불가
        let firstPace = firstMinutes * 1_000 / firstMeters
        let secondPace = secondMinutes * 1_000 / secondMeters
        let paceDiffPct = abs(secondPace / firstPace - 1) * 100
        guard paceDiffPct <= maxPaceDiffPct else { return nil }

        // 대칭 인터벌은 전/후반 평균이 같아 위 가드를 통과한다 — 스플릿 변동으로 한 번 더 거른다
        let splits = splitPaces(distanceSamples, pauses: pauses)
        if splits.count >= minSplitsForCV {
            let mean = average(splits)
            let variance = average(splits.map { ($0 - mean) * ($0 - mean) })
            guard sqrt(variance) / mean <= maxSplitCV else { return nil }
        }

        let firstBPM = average(firstHR.map { $0.bpm })
        let secondBPM = average(secondHR.map { $0.bpm })

        // EF = 거리(m) ÷ 총 심박수. 총 심박수 = 평균 bpm × 구간 길이(분)
        let firstEF = firstMeters / (firstBPM * firstMinutes)
        let secondEF = secondMeters / (secondBPM * secondMinutes)
        guard firstEF.isFinite, secondEF.isFinite, firstEF > 0, secondEF > 0 else { return nil }

        let decouplingPct = (firstEF / secondEF - 1) * 100

        let tone: RRTone
        if decouplingPct <= improvingThreshold {
            tone = .improving
        } else if decouplingPct < cautionThreshold {
            tone = .steady
        } else {
            tone = .caution  // overload는 쓰지 않는다 — 드리프트 단독으로 과부하 판정은 과함
        }

        return Result(decouplingPct: decouplingPct, firstHalfEF: firstEF, secondHalfEF: secondEF, tone: tone)
    }

    /// 거리 샘플을 중앙 시각 기준 전·후반에 배분한다. 한 샘플이 중앙을 걸치면
    /// 구간 길이 비례로 나눈다.
    private static func splitDistance(_ samples: [(start: Date, end: Date, meters: Double)],
                                      at midpoint: Date) -> (first: Double, second: Double) {
        var first = 0.0
        var second = 0.0
        for sample in samples {
            let duration = sample.end.timeIntervalSince(sample.start)
            guard duration > 0 else {
                if sample.start < midpoint { first += sample.meters } else { second += sample.meters }
                continue
            }
            if sample.end <= midpoint {
                first += sample.meters
            } else if sample.start >= midpoint {
                second += sample.meters
            } else {
                let firstPortion = midpoint.timeIntervalSince(sample.start) / duration
                first += sample.meters * firstPortion
                second += sample.meters * (1 - firstPortion)
            }
        }
        return (first, second)
    }

    /// 1km 스플릿 페이스(초/km) — 누적 거리 기준, km 경계는 샘플 안에서 선형 보간한다
    /// (ActiveTimeline.splits와 같은 방식). 마지막 잔여 구간은 0.5km 이상일 때만
    /// km당 페이스로 환산해 넣는다. 스플릿 시간에서 정지 구간을 뺀다.
    /// 60초/km 미만은 데이터 오류로 보고 버린다(ActiveTimeline.splits와 같은 기준).
    private static func splitPaces(_ samples: [(start: Date, end: Date, meters: Double)],
                                   pauses: [DateInterval]) -> [Double] {
        let sorted = samples.sorted { $0.start < $1.start }
        guard var boundaryTime = sorted.first?.start, let lastEnd = sorted.last?.end else { return [] }
        var paces: [Double] = []
        var cumulative = 0.0
        var nextBoundary = 1_000.0

        for sample in sorted where sample.meters > 0 {
            let before = cumulative
            cumulative += sample.meters
            while cumulative >= nextBoundary {
                let fraction = (nextBoundary - before) / sample.meters
                let crossing = sample.start.addingTimeInterval(
                    sample.end.timeIntervalSince(sample.start) * fraction)
                paces.append(ActiveTimeline.activeSeconds(from: boundaryTime, to: crossing, pauses: pauses))
                boundaryTime = crossing
                nextBoundary += 1_000
            }
        }

        let tailMeters = cumulative - (nextBoundary - 1_000)
        if tailMeters >= minTailMeters {
            let sec = ActiveTimeline.activeSeconds(from: boundaryTime, to: lastEnd, pauses: pauses)
            paces.append(sec * 1_000 / tailMeters)
        }
        return paces.filter { $0 > 60 }
    }

    private static func average(_ values: [Double]) -> Double {
        values.reduce(0, +) / Double(values.count)
    }
}
