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

    @Test("대칭 인터벌(빠름/느림 교대·전후반 평균 같음)은 스플릿 변동 가드로 판정하지 않는다")
    func symmetricIntervalReturnsNil() {
        // 1km 빠름(300초)·1km 느림(420초)을 12번 교대 — 전반 6km·후반 6km 모두 2,160초
        // → 전/후반 페이스 차 0%로 기존 가드는 통과한다
        var dist: [(start: Date, end: Date, meters: Double)] = []
        var t = start
        for block in 0..<12 {
            let sec: TimeInterval = block.isMultiple(of: 2) ? 300 : 420
            dist += distanceSamples(from: t, to: t.addingTimeInterval(sec), totalMeters: 1_000)
            t = t.addingTimeInterval(sec)
        }
        let mid = start.addingTimeInterval(2_160)
        let end = start.addingTimeInterval(4_320)
        // 후반 심박 +8% — 가드가 없으면 +8% caution("유산소 기반 부족")이 나갈 세션
        let hr = hrSamples(from: start, to: mid, bpm: 150) + hrSamples(from: mid, to: end, bpm: 162)

        // 스플릿 [300, 420] × 6 → 평균 360, 표준편차 60 → 변동계수 60/360 ≈ 0.167 > 0.08
        #expect(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                    start: start, durationSec: 4_320) == nil)
    }

    @Test("중앙을 걸치는 거리 샘플은 시간 비례로 전/후반에 배분된다")
    func straddlingDistanceSampleSplitsProportionally() throws {
        let mid = start.addingTimeInterval(1_800)
        let end = start.addingTimeInterval(3_600)
        let hr = hrSamples(from: start, to: mid, bpm: 140) + hrSamples(from: mid, to: end, bpm: 150)
        // 전반 전용 1,000m(0:00~15:00) + 중앙을 걸치는 3,000m(15:00~60:00, 2,700초 구간)
        // 중앙(30:00)은 걸치는 구간 시작(15:00)에서 900/2,700 = 1/3 지점
        // → 전반 배분 3,000 × 1/3 = 1,000m, 후반 배분 3,000 × 2/3 = 2,000m
        // 합산: 전반 1,000+1,000 = 2,000m, 후반 2,000m (전/후반 거리·시간 동일 → 페이스 가드 통과)
        // 두 샘플 모두 900초/km 등속이라 스플릿 4개가 모두 900초 → 변동계수 0 (이슈 #100 가드 통과)
        let dist: [(start: Date, end: Date, meters: Double)] = [
            (start: start, end: start.addingTimeInterval(900), meters: 1_000),
            (start: start.addingTimeInterval(900), end: start.addingTimeInterval(3_600), meters: 3_000),
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

    // MARK: - 일시정지 (이슈 #47)

    /// 활동 60분(벽시계 61분) 정속주, 벽시계 600~660초에 60초 신호 대기.
    /// 속도 5,000m/1,800초로 달리는 구간에만 거리 샘플을 깐다.
    private func pausedRunDistance() -> [(start: Date, end: Date, meters: Double)] {
        let speed = 5_000.0 / 1_800
        return distanceSamples(from: start, to: start.addingTimeInterval(600), totalMeters: speed * 600)
             + distanceSamples(from: start.addingTimeInterval(660), to: start.addingTimeInterval(3_660),
                               totalMeters: speed * 3_000)
    }

    @Test("전반 60초 신호 대기가 거짓 improving을 만들지 않는다")
    func earlyPauseDoesNotFakeImproving() throws {
        let pause = DateInterval(start: start.addingTimeInterval(600), end: start.addingTimeInterval(660))
        // 달리는 동안 150bpm, 정지 중 110bpm(10초 간격 6개)
        let hr = hrSamples(from: start, to: start.addingTimeInterval(600), bpm: 150)
               + hrSamples(from: start.addingTimeInterval(600), to: start.addingTimeInterval(660), bpm: 110)
               + hrSamples(from: start.addingTimeInterval(660), to: start.addingTimeInterval(3_660), bpm: 150)
        let dist = pausedRunDistance()

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                       start: start, durationSec: 3_600,
                                                       pauses: [pause],
                                                       end: start.addingTimeInterval(3_660)))

        // 활동 중앙 1800초 = 벽시계 1860초(정지 60초만큼 밀림)
        // 전반 거리 2.78m/s × (600 + 1200) = 5,000m, 후반 2.78 × 1800 = 5,000m
        // 정지 심박을 빼면 양쪽 모두 150bpm → EF 동일 → 디커플링 0.0%
        #expect(abs(result.decouplingPct) < 0.01)
        #expect(result.tone == .steady)

        // 비교: 정지 구간을 모르면(수정 전 동작) 중앙이 벽시계 1800초에 잡혀
        // 전반 4,833m·후반 5,167m(페이스 차 6.45% — 가드 통과), 전반 심박 (174×150 + 6×110)/180 = 148.67
        // → EF1/EF2 = (4,833/5,167) × (150/148.67) ≈ 0.944 → −5.6% improving
        let naive = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: dist,
                                                      start: start, durationSec: 3_600))
        #expect(abs(naive.decouplingPct - (-5.61)) < 0.05)
        #expect(naive.tone == .improving)
    }

    @Test("정지 구간으로 벽시계와 활동 시간이 맞춰지지 않으면 판정하지 않는다")
    func unaccountedWallTimeReturnsNil() {
        let pause = DateInterval(start: start.addingTimeInterval(600), end: start.addingTimeInterval(660))
        let hr = hrSamples(from: start, to: start.addingTimeInterval(3_660), bpm: 150)

        // 벽시계 3,780 − 활동 3,600 = 180초인데 정지 합은 60초 → 설명 안 되는 120초 > 30초
        #expect(DriftEngine.compute(hrSamples: hr, distanceSamples: pausedRunDistance(),
                                    start: start, durationSec: 3_600,
                                    pauses: [pause],
                                    end: start.addingTimeInterval(3_780)) == nil)
    }

    @Test("정지가 있어도 후반 심박 +8%면 caution 판정은 그대로다")
    func pausedRunKeepsCaution() throws {
        let pause = DateInterval(start: start.addingTimeInterval(600), end: start.addingTimeInterval(660))
        // 활동 중앙(벽시계 1860초) 이후만 162bpm(= 150 × 1.08)
        let hr = hrSamples(from: start, to: start.addingTimeInterval(600), bpm: 150)
               + hrSamples(from: start.addingTimeInterval(600), to: start.addingTimeInterval(660), bpm: 110)
               + hrSamples(from: start.addingTimeInterval(660), to: start.addingTimeInterval(1_860), bpm: 150)
               + hrSamples(from: start.addingTimeInterval(1_860), to: start.addingTimeInterval(3_660), bpm: 162)

        let result = try #require(DriftEngine.compute(hrSamples: hr, distanceSamples: pausedRunDistance(),
                                                       start: start, durationSec: 3_600,
                                                       pauses: [pause],
                                                       end: start.addingTimeInterval(3_660)))

        // 거리·활동 시간이 전/후반 동일 → EF1/EF2 = 162/150 = 1.08 → +8.0%
        #expect(abs(result.decouplingPct - 8.0) < 0.01)
        #expect(result.tone == .caution)
    }
}
