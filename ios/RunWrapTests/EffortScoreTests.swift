import Foundation
import HealthKit
import Testing
@testable import RunWrap

/// Apple 운동 노력도 (이슈 #178) — 척도 구간 라벨, 출처 라벨, 권한 묶음 포함, 데모 합성 범위를 검증한다.
@Suite("Apple 운동 노력도")
@MainActor
struct EffortScoreTests {
    /// 고정 시각 — 합성 시드가 시작 시각 비트를 섞으므로 now를 주입해 결정론적으로 만든다
    private let now = ISO8601DateFormatter().date(from: "2026-09-30T07:00:00+09:00")!

    @Test("구간 라벨 — Apple 척도 1~3 편안 · 4~6 보통 · 7~8 힘듦 · 9~10 전력")
    func labelBoundaries() {
        // 경계는 3.5·6.5·8.5 — 정수 척도는 각자 Apple 구간에 떨어진다
        #expect(EffortScore.label(for: 1) == "편안")
        #expect(EffortScore.label(for: 3) == "편안")
        #expect(EffortScore.label(for: 4) == "보통")
        #expect(EffortScore.label(for: 6) == "보통")
        #expect(EffortScore.label(for: 7) == "힘듦")
        #expect(EffortScore.label(for: 8) == "힘듦")
        #expect(EffortScore.label(for: 9) == "전력")
        #expect(EffortScore.label(for: 10) == "전력")
    }

    @Test("출처 라벨 — 직접 입력과 Apple 추정을 구분하고, label은 score를 따른다")
    func sourceLabel() {
        let manual = EffortScore(score: 7, isEstimated: false)
        let estimated = EffortScore(score: 5, isEstimated: true)
        #expect(manual.sourceLabel == "직접 입력")
        #expect(estimated.sourceLabel == "Apple 추정")
        #expect(manual.label == "힘듦")
        #expect(estimated.label == "보통")
    }

    @Test("권한 묶음 — iOS 18 이상이면 기본 요청에 두 노력도 타입이 들어간다")
    func standardIncludesEffortTypes() {
        if #available(iOS 18, *) {
            #expect(HealthPermissions.standard.contains(HKQuantityType(.workoutEffortScore)))
            #expect(HealthPermissions.standard.contains(HKQuantityType(.estimatedWorkoutEffortScore)))
        } else {
            #expect(HealthPermissions.effort.isEmpty)
        }
    }

    @Test("데모 합성 — 노력도는 4~8 정수의 Apple 추정이고 같은 세션이면 같은 값")
    func syntheticEffortInRange() throws {
        let profile = HeartRateProfile(hrMax: 190, hrMaxSource: .fallback,
                                       restingHR: nil, zoneMethod: .percentMax)
        // 시드만 바꿔 여러 세션을 만든다 — 4 + Int(unit × 5)라 4...8 밖으로 나가지 않는다
        for index in 1...20 {
            let run = RunSummary(id: DemoData.demoID(index), start: now, durationSec: 3_000,
                                 distanceMeters: 8_000, avgHeartRate: 150)
            let effort = try #require(WorkoutDetailStore.synthetic(for: run, heartRate: profile).effort)
            #expect(effort.isEstimated)
            #expect((4...8).contains(effort.score))
            #expect(effort.score == effort.score.rounded())
            #expect(WorkoutDetailStore.synthetic(for: run, heartRate: profile).effort == effort)
        }
    }
}
