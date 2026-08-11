import Foundation

/// 심박 드리프트(Pw:HR 디커플링) 엔진 — 같은 페이스인데 후반 심박이 슬금슬금
/// 오르면 유산소 기반이 아직 부족하다는 신호다 (Friel 디커플링, 기획 문서
/// "HealthKit 미활용 데이터 활용 제안 A2"). 세션을 시간 중앙으로 전·후반을
/// 나눠 효율(EF = 거리 ÷ 총 심박수)을 비교한다. 세션 상세 화면 카드의 재료.
///
/// 인터벌·빌드업처럼 구간마다 페이스가 크게 바뀌는 러닝은 디커플링 해석이
/// 성립하지 않는다 — 정속주 전용 지표라 전/후반 페이스 차이가 크면 판정하지
/// 않는다. "틀린 인사이트는 없느니만 못하다."
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
    private static let improvingThreshold = -2.0  // 이하면 후반이 더 효율적
    private static let cautionThreshold = 5.0     // 미만이면 유산소 기반 탄탄(Friel 기준)

    static func compute(hrSamples: [(time: Date, bpm: Double)],
                        distanceSamples: [(start: Date, end: Date, meters: Double)],
                        start: Date, durationSec: Double) -> Result? {
        guard durationSec >= minDurationSec else { return nil }

        let midpoint = start.addingTimeInterval(durationSec / 2)
        let end = start.addingTimeInterval(durationSec)

        let firstHR = hrSamples.filter { $0.time < midpoint }
        let secondHR = hrSamples.filter { $0.time >= midpoint }
        guard firstHR.count >= minSamplesPerHalf, secondHR.count >= minSamplesPerHalf else { return nil }

        let (firstMeters, secondMeters) = splitDistance(distanceSamples, at: midpoint)
        guard firstMeters > 0, secondMeters > 0 else { return nil }

        let firstMinutes = midpoint.timeIntervalSince(start) / 60
        let secondMinutes = end.timeIntervalSince(midpoint) / 60

        // 전/후반 페이스(분/km) 차이가 크면 정속주가 아니다 — 디커플링 해석 불가
        let firstPace = firstMinutes * 1_000 / firstMeters
        let secondPace = secondMinutes * 1_000 / secondMeters
        let paceDiffPct = abs(secondPace / firstPace - 1) * 100
        guard paceDiffPct <= maxPaceDiffPct else { return nil }

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

    private static func average(_ values: [Double]) -> Double {
        values.reduce(0, +) / Double(values.count)
    }
}
