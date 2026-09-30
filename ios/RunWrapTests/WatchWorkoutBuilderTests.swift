import Foundation
import Testing
import WorkoutKit
@testable import RunWrap

/// 워치 전송 빌더 검증 (이슈 #197) — 페이스→속도 변환, 회복 시간 규칙, 처방 종류별 구성, 표시 문구.
/// 빌더는 시각을 쓰지 않아 `now` 주입이 필요 없다 (입력 처방만으로 결정론적).
struct WatchWorkoutBuilderTests {
    private func today(_ kind: TodayWorkout.Kind, km: Double?,
                       pace: ClosedRange<Double>?) -> TodayWorkout {
        TodayWorkout(kind: kind, reason: .fill, distanceKm: km, paceSecPerKm: pace)
    }

    private func build(_ workout: TodayWorkout) -> CustomWorkout? {
        WatchWorkoutBuilder.customWorkout(for: workout,
                                          displayName: WatchWorkoutBuilder.displayName(for: workout.kind))
    }

    // MARK: - 순수 헬퍼

    @Test("페이스→속도 — 5′00″~5′30″/km는 느린 쪽이 속도 하한: 1000/330 ~ 1000/300 m/s")
    func speedRangeFromPace() {
        let range = WatchWorkoutBuilder.speedRangeMetersPerSecond(paceSecPerKm: 300...330)
        // 1000 / 330 ≈ 3.03 m/s, 1000 / 300 ≈ 3.33 m/s
        #expect(range.lowerBound == 1_000.0 / 330)
        #expect(range.upperBound == 1_000.0 / 300)
        #expect(range.lowerBound < range.upperBound)
    }

    @Test("페이스→속도 — 한 점 페이스(템포·인터벌)는 앞뒤 5초 넓혀 폭 0 범위를 피한다")
    func speedRangeWidensPointPace() {
        let range = WatchWorkoutBuilder.speedRangeMetersPerSecond(paceSecPerKm: 240...240)
        // 235~245초/km → 1000/245 ~ 1000/235 m/s
        #expect(range.lowerBound == 1_000.0 / 245)
        #expect(range.upperBound == 1_000.0 / 235)
    }

    @Test("회복 시간 — 본훈련 거리 × 0.15초, 90~180초로 자른다")
    func recoveryRule() {
        #expect(WatchWorkoutBuilder.recoverySeconds(meters: 400) == 90)     // 60 → 하한 90
        #expect(WatchWorkoutBuilder.recoverySeconds(meters: 800) == 120)    // 800 × 0.15
        #expect(WatchWorkoutBuilder.recoverySeconds(meters: 1_000) == 150)  // 1000 × 0.15
    }

    @Test("표시 이름 — '런미새 · ' + 공통 라벨")
    func displayNames() {
        #expect(WatchWorkoutBuilder.displayName(for: .interval(reps: 5, meters: 800)) == "런미새 · 인터벌 5×800m")
        #expect(WatchWorkoutBuilder.displayName(for: .easy) == "런미새 · 이지런")
        #expect(WatchWorkoutBuilder.displayName(for: .tempo) == "런미새 · 템포런")
    }

    @Test("구성 요약 — 이지·템포·인터벌이 빌더 규칙과 같은 구성을 말한다, 달리지 않는 날은 nil")
    func summaries() {
        #expect(WatchWorkoutBuilder.summary(for: today(.easy, km: 5, pace: 300...330))
                == "5.0km · 페이스 알림")
        #expect(WatchWorkoutBuilder.summary(for: today(.tempo, km: 4.7, pace: 255...255))
                == "워밍업 10분 · 4.7km · 쿨다운 10분 · 페이스 알림")
        #expect(WatchWorkoutBuilder.summary(for: today(.interval(reps: 5, meters: 800), km: 4, pace: nil))
                == "워밍업 10분 · 5×800m(회복 2분) · 쿨다운 10분")
        #expect(WatchWorkoutBuilder.summary(for: today(.interval(reps: 4, meters: 400), km: 1.6, pace: nil))
                == "워밍업 10분 · 4×400m(회복 1분 30초) · 쿨다운 10분")
        #expect(WatchWorkoutBuilder.summary(for: today(.rest, km: nil, pace: nil)) == nil)
    }

    // MARK: - 커스텀 워크아웃 구성

    @Test("미노출 — 휴식·횟수 완료·거리 완료는 워크아웃을 만들지 않는다")
    func nonRunningDaysAreNil() {
        #expect(build(today(.rest, km: nil, pace: nil)) == nil)
        #expect(build(today(.doneCount, km: nil, pace: nil)) == nil)
        #expect(build(today(.doneKm, km: nil, pace: nil)) == nil)
    }

    @Test("이지런 — 워밍업·쿨다운 없이 5km 거리 목표 한 구간 + 속도 범위 알림")
    func easyWorkout() throws {
        let workout = try #require(build(today(.easy, km: 5, pace: 300...330)))
        #expect(workout.activity == .running)
        #expect(workout.location == .outdoor)
        #expect(workout.displayName == "런미새 · 이지런")
        #expect(workout.warmup == nil)
        #expect(workout.cooldown == nil)
        #expect(workout.blocks.count == 1)
        let block = try #require(workout.blocks.first)
        #expect(block.iterations == 1)
        #expect(block.steps.count == 1)
        let step = try #require(block.steps.first)
        #expect(step.purpose == .work)
        #expect(step.step.goal == .distance(5, .kilometers))
        let alert = try #require(step.step.alert as? SpeedRangeAlert)
        #expect(alert.target.lowerBound.converted(to: .metersPerSecond).value == 1_000.0 / 330)
    }

    @Test("이지런 — 페이스 존이 없으면 알림 없이 거리 목표만")
    func easyWithoutPace() throws {
        let workout = try #require(build(today(.lsd, km: 12, pace: nil)))
        let step = try #require(workout.blocks.first?.steps.first)
        #expect(step.step.goal == .distance(12, .kilometers))
        #expect(step.step.alert == nil)
    }

    @Test("거리 목표 — 화면 표기(소수 1자리)와 같게 0.1km로 반올림한다 (2.63 → 2.6km)")
    func distanceRoundedToTenth() throws {
        let workout = try #require(build(today(.easy, km: 2.63, pace: nil)))
        #expect(workout.blocks.first?.steps.first?.step.goal == .distance(2.6, .kilometers))
    }

    @Test("템포런 — 앞뒤 10분 워밍업·쿨다운 + 거리 목표 본훈련")
    func tempoWorkout() throws {
        let workout = try #require(build(today(.tempo, km: 4.7, pace: 255...255)))
        #expect(workout.warmup?.goal == .time(10, .minutes))
        #expect(workout.cooldown?.goal == .time(10, .minutes))
        #expect(workout.blocks.count == 1)
        #expect(workout.blocks.first?.steps.first?.step.goal == .distance(4.7, .kilometers))
        #expect(workout.blocks.first?.steps.first?.step.alert is SpeedRangeAlert)
    }

    @Test("인터벌 5×800m — 5회 반복 블록 하나: 800m 본훈련 + 120초 회복")
    func intervalWorkout() throws {
        let workout = try #require(build(today(.interval(reps: 5, meters: 800), km: 4,
                                               pace: 235...235)))
        #expect(workout.warmup?.goal == .time(10, .minutes))
        #expect(workout.cooldown?.goal == .time(10, .minutes))
        let block = try #require(workout.blocks.first)
        #expect(workout.blocks.count == 1)
        #expect(block.iterations == 5)
        #expect(block.steps.count == 2)
        #expect(block.steps[0].purpose == .work)
        #expect(block.steps[0].step.goal == .distance(800, .meters))
        #expect(block.steps[0].step.alert is SpeedRangeAlert)
        #expect(block.steps[1].purpose == .recovery)
        #expect(block.steps[1].step.goal == .time(120, .seconds))
        #expect(block.steps[1].step.alert == nil)
    }

    @Test("플랜 — 커스텀 워크아웃이 있으면 플랜으로 감싸고, 없으면 nil")
    func planWraps() {
        let easy = today(.easy, km: 5, pace: 300...330)
        #expect(WatchWorkoutBuilder.plan(for: easy, displayName: "런미새 · 이지런") != nil)
        #expect(WatchWorkoutBuilder.plan(for: today(.rest, km: nil, pace: nil),
                                         displayName: "런미새 · 휴식") == nil)
    }
}
