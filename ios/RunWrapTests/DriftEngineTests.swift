import Foundation
import Testing
@testable import RunWrap

/// 심박 드리프트(디커플링) 엔진 — 정속 60분 세션 위주로 EF 비교와 미노출 가드를 검증한다.
struct DriftEngineTests {
    /// 고정 시각 — 이후 모든 세션은 여기서부터 상대 시간으로 구성한다
    private let start = ISO8601DateFormatter().date(from: "2026-08-10T07:00:00+09:00")!

    /// 구간에 10초 간격 심박 샘플을 깐다(bpm 고정값). step을 늘리면 샘플 수를 줄일 수 있다
    private func hrSamples(from: Date, to: Date, bpm: Double, step: TimeInterval = 10)
        -> [(time: Date, bpm: Double)] {
        var samples: [(time: Date, bpm: Double)] = []
        var t = from
        while t < to {
            samples.append((time: t, bpm: bpm))
            t = t.addingTimeInterval(step)
        }
        return samples
    }

    /// 구간에 총 거리를 100m 단위 샘플로 등속 배분한다
    private func distanceSamples(from: Date, to: Date, totalMeters: Double, chunk: Double = 100)
        -> [(start: Date, end: Date, meters: Double)] {
        let totalDur = to.timeIntervalSince(from)
        var samples: [(start: Date, end: Date, meters: Double)] = []
        var covered = 0.0
        var t = from
        while covered < totalMeters {
            let thisChunk = min(chunk, totalMeters - covered)
            let thisDur = totalDur * (thisChunk / totalMeters)
            let segEnd = t.addingTimeInterval(thisDur)
            samples.append((start: t, end: segEnd, meters: thisChunk))
            covered += thisChunk
            t = segEnd
        }
        return samples
    }

    @Test("정속 60분·후반 심박 +8% → 디커플링 +8% · caution")
    func higherSecondHalfHRGivesCaution() throws {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        let hr = hrSamples(from: start, to: mid, bpm: 140) + hrSamples(from: mid, to: end, bpm: 151.2)
        let dist = distanceSamples(from: start, to: mid, totalMeters: 5_000)
                 + distanceSamples(from: mid, to: end, totalMeters: 5_000)

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                       start: start, durationSec: 3_600))

        // 거리·시간이 전/후반 동일하므로 EF1/EF2 = secondBPM/firstBPM = 151.2/140 = 1.08
        // decouplingPct = (1.08 − 1) × 100 = 8.0
        #expect(abs(result.decouplingPct - 8.0) < 0.01)
        #expect(result.tone == .caution)
    }

    @Test("전·후반 심박이 같으면 디커플링 0% · steady")
    func evenHRGivesSteady() throws {
        let mid = start.addingTimeInterval(1_200)
        let end = start.addingTimeInterval(2_400)
        let hr = hrSamples(from: start, to: mid, bpm: 150) + hrSamples(from: mid, to: end, bpm: 150)
        let dist = distanceSamples(from: start, to: mid, totalMeters: 4_000)
                 + distanceSamples(from: mid, to: end, totalMeters: 4_000)

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                       start: start, durationSec: 2_400))

        #expect(abs(result.decouplingPct - 0.0) < 0.01)
        #expect(result.tone == .steady)
    }

    @Test("후반 심박이 3% 낮으면 디커플링 −3% · improving")
    func lowerSecondHalfHRGivesImproving() throws {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        let hr = hrSamples(from: start, to: mid, bpm: 150) + hrSamples(from: mid, to: end, bpm: 145.5)
        let dist = distanceSamples(from: start, to: mid, totalMeters: 5_000)
                 + distanceSamples(from: mid, to: end, totalMeters: 5_000)

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                       start: start, durationSec: 3_600))

        // EF1/EF2 = secondBPM/firstBPM = 145.5/150 = 0.97 → decouplingPct = (0.97 − 1) × 100 = −3.0
        #expect(abs(result.decouplingPct - (-3.0)) < 0.01)
        #expect(result.tone == .improving)
    }

    @Test("30분 미만 세션은 판정하지 않는다")
    func tooShortSessionReturnsNil() {
        let mid = start.addingTimeInterval(750)
        let end = start.addingTimeInterval(1_500)
        let hr = hrSamples(from: start, to: mid, bpm: 150) + hrSamples(from: mid, to: end, bpm: 150)
        let dist = distanceSamples(from: start, to: mid, totalMeters: 2_000)
                 + distanceSamples(from: mid, to: end, totalMeters: 2_000)

        #expect(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                    start: start, durationSec: 1_500) == nil)
    }

    @Test("전반 심박 샘플이 10개뿐이면 판정하지 않는다")
    func tooFewFirstHalfHRSamplesReturnsNil() {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        // step 180초 × 10개 = 전반 30분에 정확히 10개(최소 20개 미달)
        let hr = hrSamples(from: start, to: mid, bpm: 140, step: 180) + hrSamples(from: mid, to: end, bpm: 140)
        let dist = distanceSamples(from: start, to: mid, totalMeters: 5_000)
                 + distanceSamples(from: mid, to: end, totalMeters: 5_000)

        #expect(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                    start: start, durationSec: 3_600) == nil)
    }

    @Test("후반 페이스가 15% 느리면(빌드다운) 판정하지 않는다")
    func largePaceShiftReturnsNil() {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        let hr = hrSamples(from: start, to: mid, bpm: 150) + hrSamples(from: mid, to: end, bpm: 150)
        // 전반 4,600m·후반 4,000m, 같은 30분 → 후반 페이스가 4,600/4,000 = 1.15배(15%) 느리다
        let dist = distanceSamples(from: start, to: mid, totalMeters: 4_600)
                 + distanceSamples(from: mid, to: end, totalMeters: 4_000)

        #expect(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                    start: start, durationSec: 3_600) == nil)
    }

    @Test("중앙을 걸치는 거리 샘플은 시간 비례로 전/후반에 배분된다")
    func straddlingDistanceSampleSplitsProportionally() throws {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        let hr = hrSamples(from: start, to: mid, bpm: 140) + hrSamples(from: mid, to: end, bpm: 150)
        // 전반 전용 1,000m + 중앙을 걸치는 3,000m(15:00~40:00, 900초 구간)
        // 중앙(30:00)은 걸치는 구간 시작(25:00)에서 300/900 = 1/3 지점
        // → 전반 배분 3,000 × 1/3 = 1,000m, 후반 배분 3,000 × 2/3 = 2,000m
        // 합산: 전반 1,000+1,000 = 2,000m, 후반 2,000m (전/후반 거리·시간 동일 → 페이스 가드 통과)
        let dist: [(start: Date, end: Date, meters: Double)] = [
            (start: start, end: start.addingTimeInterval(500), meters: 1_000),
            (start: start.addingTimeInterval(1_500), end: start.addingTimeInterval(2_400), meters: 3_000),
        ]

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                       start: start, durationSec: 3_600))

        // EF1 = 2,000/(140×30) = 0.47619..., EF2 = 2,000/(150×30) = 0.44444...
        #expect(abs(result.firstHalfEF - 2_000.0 / (140 * 30)) < 0.0001)
        #expect(abs(result.secondHalfEF - 2_000.0 / (150 * 30)) < 0.0001)
        // decouplingPct = (150/140 − 1) × 100 ≈ 7.142857
        #expect(abs(result.decouplingPct - 7.142857) < 0.01)
        #expect(result.tone == .caution)
    }
}
