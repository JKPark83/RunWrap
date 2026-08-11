import Foundation
import Testing
@testable import RunWrap

/// 주법 엔진 검증 — 기준선 표본 가드, 지표별 평균, 조언 규칙, 케이던스 추이.
/// now = 2026-08-10T09:00:00Z (월 18:00 KST) 고정 — 창 경계: 14일 전 = 7.27, 28일 전 = 7.13.
struct FormEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!
    var engine: FormEngine { FormEngine(now: now) }

    private func snap(daysAgo: Double, cadence: Double? = 170, vo: Double? = 7.0,
                      gct: Double? = 240, id: UUID = UUID()) -> FormSnapshot {
        FormSnapshot(id: id, start: now.addingTimeInterval(-daysAgo * 86_400),
                     cadenceSpm: cadence, verticalOscillationCm: vo, groundContactMs: gct)
    }

    /// 조언 테스트 공용 기준선 — 케이던스 170 spm, 진폭 7.0 cm, 접촉 240 ms
    let base = FormBaseline(cadenceSpm: 170, verticalOscillationCm: 7.0,
                            groundContactMs: 240, sessionCount: 6)

    private func run(daysAgo: Double, cadence: Double?) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: 3_600, distanceMeters: 10_000, avgHeartRate: 150,
                   cadenceSpm: cadence)
    }

    @Test("기준선 가드 — 창 안 표본 5회 미만이면 침묵(nil)")
    func baselineGuard() {
        let current = UUID()
        var snaps = [snap(daysAgo: 3), snap(daysAgo: 6), snap(daysAgo: 9), snap(daysAgo: 12)]
        #expect(engine.baseline(of: snaps, excluding: current) == nil)

        // 창 밖(28일 초과) 표본은 세지 않는다
        snaps.append(snap(daysAgo: 30))
        #expect(engine.baseline(of: snaps, excluding: current) == nil)

        // 본인 세션은 기준선에서 제외한다
        snaps.append(snap(daysAgo: 1, id: current))
        #expect(engine.baseline(of: snaps, excluding: current) == nil)

        // 창 안 타인 표본이 5회가 되는 순간 기준선이 나온다
        snaps.append(snap(daysAgo: 20))
        #expect(engine.baseline(of: snaps, excluding: current) != nil)
    }

    @Test("기준선 평균 — 지표별로 nil 표본은 빼고 평균낸다")
    func baselinePerMetricMean() throws {
        let snaps = [
            snap(daysAgo: 2, cadence: 160, vo: 6.0, gct: 230),
            snap(daysAgo: 5, cadence: 165, vo: 7.0, gct: 250),
            snap(daysAgo: 9, cadence: 170, vo: 8.0, gct: nil),
            snap(daysAgo: 14, cadence: 175, vo: nil, gct: nil),
            snap(daysAgo: 27, cadence: 180, vo: 7.0, gct: nil),
        ]
        let baseline = try #require(engine.baseline(of: snaps, excluding: UUID()))
        #expect(baseline.sessionCount == 5)
        #expect(baseline.cadenceSpm == 170)          // (160+165+170+175+180)/5
        #expect(baseline.verticalOscillationCm == 7.0)  // (6+7+8+7)/4
        #expect(baseline.groundContactMs == 240)     // (230+250)/2
    }

    @Test("조언 — 케이던스가 기준선보다 5% 넘게 낮으면 보폭 조언")
    func adviceCadence() {
        // 160 < 170 × 0.95 = 161.5 → 발화
        let session = snap(daysAgo: 0.1, cadence: 160)
        let advice = engine.advice(session: session, baseline: base)
        #expect(advice.map(\.kind) == [.cadence])
    }

    @Test("조언 — 진폭·접촉시간이 기준선의 110%를 넘으면 각각 발화")
    func adviceOscillationAndContact() {
        // 진폭 7.8 > 7.0 × 1.10 = 7.7, 접촉 270 > 240 × 1.10 = 264 → 둘 다 발화
        let session = snap(daysAgo: 0.1, cadence: 170, vo: 7.8, gct: 270)
        let advice = engine.advice(session: session, baseline: base)
        #expect(advice.map(\.kind) == [.oscillation, .contact])
    }

    @Test("조언 — 전 지표가 정상 범위면 침묵")
    func adviceSilence() {
        let session = snap(daysAgo: 0.1, cadence: 168, vo: 7.5, gct: 250)
        #expect(engine.advice(session: session, baseline: base).isEmpty)
    }

    @Test("조언 — 절대 밴드(진폭 4~10cm) 이탈은 비교 대신 측정 오류 안내")
    func adviceSensorBand() {
        // 12 cm는 기준선 110%(7.7)도 넘지만 절대 밴드 밖 → 진폭 조언 억제, 센서 안내만
        let session = snap(daysAgo: 0.1, cadence: 170, vo: 12, gct: 250)
        let advice = engine.advice(session: session, baseline: base)
        #expect(advice.map(\.kind) == [.sensor])
    }

    @Test("케이던스 추이 — 최근 2주 vs 이전 2주 평균, +2 spm 이상이면 개선 톤")
    func trendImproving() throws {
        let runs = [
            run(daysAgo: 1, cadence: 172), run(daysAgo: 3, cadence: 170),
            run(daysAgo: 5, cadence: 168),   // 최근 2주 평균 170
            run(daysAgo: 15, cadence: 164), run(daysAgo: 17, cadence: 166),
            run(daysAgo: 19, cadence: 165),  // 이전 2주 평균 165
        ]
        let trend = try #require(FormTrend.compute(runs: runs, now: now))
        #expect(trend.recentSpm == 170)
        #expect(trend.previousSpm == 165)
        #expect(trend.tone == .improving)  // delta +5 ≥ +2
    }

    @Test("케이던스 추이 가드 — 창마다 표본 3회 미만이면 nil")
    func trendGuard() {
        // 이전 2주 창의 케이던스 표본이 2개뿐 → 침묵
        let runs = [
            run(daysAgo: 1, cadence: 172), run(daysAgo: 3, cadence: 170),
            run(daysAgo: 5, cadence: 168),
            run(daysAgo: 15, cadence: 164), run(daysAgo: 17, cadence: 166),
            run(daysAgo: 19, cadence: nil),
        ]
        #expect(FormTrend.compute(runs: runs, now: now) == nil)
    }
}
