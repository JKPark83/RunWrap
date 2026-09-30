import Foundation
import HealthKit
import WorkoutKit

/// 오늘 권장 훈련 → Apple Watch 커스텀 워크아웃 변환기 (이슈 #197).
///
/// 홈 '오늘 권장' 줄이 내는 처방(`TodayWorkout`)을 WorkoutKit `CustomWorkout`으로 옮긴다.
/// 전달은 시스템 미리보기 시트(`.workoutPreview`)가 맡는다 — 사용자가 시트에서 직접 워치에 추가하므로
/// 권한 프롬프트가 없고, 앱은 결과를 알 수 없다. 건강 데이터를 읽거나 쓰지 않는다.
/// UI를 모른다 — 화면은 여기서 만든 플랜과 구성 요약 문구를 그대로 쓴다.
///
/// 구성 규칙 (사용자 결정):
/// - 이지런·LSD: 워밍업·쿨다운 없이 거리 목표 한 구간
/// - 템포·인터벌(퀄리티): 앞뒤로 10분 워밍업·쿨다운
/// - 페이스 존이 있으면 본훈련 구간에 속도 범위 알림을 건다
///
/// 페이스 → 속도: WorkoutKit(iOS 17)에는 페이스 알림 타입이 없고 속도 알림(`SpeedRangeAlert`)만 있다.
/// 페이스(초/km)는 속도의 역수라 `1_000 / 초 = m/s`로 바꾸면 범위의 양끝이 뒤집힌다 —
/// 느린 페이스(큰 값)가 속도 하한이 된다. 워치는 속도 범위를 다시 페이스로 보여준다.
///
/// 미노출 원칙: 만든 구간의 목표·알림 중 하나라도 워치가 지원하지 않으면(`supportsGoal`·`supportsAlert`)
/// 플랜을 내지 않는다(nil) — 반쯤 빠진 훈련을 보내느니 보내지 않는다.
enum WatchWorkoutBuilder {
    /// 퀄리티 세션 앞뒤 워밍업·쿨다운 길이 (분) — 사용자 결정
    static let warmupMinutes: Double = 10

    /// 한 점 페이스(템포·인터벌 존은 하한=상한)에 주는 앞뒤 여유 (초/km).
    /// 폭이 0인 범위는 워치가 늘 "범위 밖"으로 알려 알림이 잡음이 된다 — 일반 관례, 기획서 근거 없음
    static let pointPaceToleranceSec: Double = 5

    private static let activity: HKWorkoutActivityType = .running
    private static let location: HKWorkoutSessionLocationType = .outdoor

    /// 오늘의 처방 → 커스텀 워크아웃. 달리지 않는 날(휴식·완료)이거나 워치가 지원하지 않는 구성이면 nil
    static func customWorkout(for today: TodayWorkout, displayName: String) -> CustomWorkout? {
        let alert = today.paceSecPerKm.map { speedAlert(paceSecPerKm: $0) }
        // 거리는 0.1km로 반올림한다 — 홈 줄·시트가 `Format.km`(소수 1자리)로 보여주는 값과 워치 목표가 같게
        let distance: WorkoutGoal = today.distanceKm.map { .distance(($0 * 10).rounded() / 10, .kilometers) } ?? .open
        let warmup = WorkoutStep(goal: .time(warmupMinutes, .minutes))

        let workout: CustomWorkout
        switch today.kind {
        case .rest, .doneCount, .doneKm:
            return nil
        case .easy, .lsd:
            workout = CustomWorkout(activity: activity, location: location, displayName: displayName,
                                    blocks: [IntervalBlock(steps: [IntervalStep(.work, goal: distance,
                                                                                alert: alert)])])
        case .tempo:
            workout = CustomWorkout(activity: activity, location: location, displayName: displayName,
                                    warmup: warmup,
                                    blocks: [IntervalBlock(steps: [IntervalStep(.work, goal: distance,
                                                                                alert: alert)])],
                                    cooldown: warmup)
        case .interval(let reps, let meters):
            let steps = [
                IntervalStep(.work, goal: .distance(Double(meters), .meters), alert: alert),
                IntervalStep(.recovery, goal: .time(recoverySeconds(meters: meters), .seconds)),
            ]
            workout = CustomWorkout(activity: activity, location: location, displayName: displayName,
                                    warmup: warmup,
                                    blocks: [IntervalBlock(steps: steps, iterations: reps)],
                                    cooldown: warmup)
        }
        guard isSupported(workout) else { return nil }
        return workout
    }

    /// 시스템 미리보기 시트에 넘길 플랜 — `customWorkout`이 nil이면 nil (화면은 버튼을 감춘다)
    static func plan(for today: TodayWorkout, displayName: String) -> WorkoutPlan? {
        customWorkout(for: today, displayName: displayName).map { WorkoutPlan(.custom($0)) }
    }

    // MARK: - 순수 헬퍼 (테스트 대상)

    /// 페이스 범위(초/km, 느린 쪽이 upperBound) → 속도 범위(m/s).
    /// 하한=상한인 한 점 페이스는 앞뒤로 `pointPaceToleranceSec`만큼 넓힌 뒤 바꾼다
    static func speedRangeMetersPerSecond(paceSecPerKm: ClosedRange<Double>) -> ClosedRange<Double> {
        let pace = paceSecPerKm.lowerBound == paceSecPerKm.upperBound
            ? (paceSecPerKm.lowerBound - pointPaceToleranceSec)...(paceSecPerKm.upperBound + pointPaceToleranceSec)
            : paceSecPerKm
        return (1_000 / pace.upperBound)...(1_000 / pace.lowerBound)
    }

    /// 인터벌 회복 조깅 시간(초) — 본훈련 거리에 비례, 90~180초로 자른다.
    /// 400m → 90초, 800m → 120초, 1000m → 150초. 일반 관례, 기획서 근거 없음
    static func recoverySeconds(meters: Int) -> Double {
        min(180, max(90, Double(meters) * 0.15))
    }

    /// 워치 운동 목록에 보일 이름 — "런미새 · 인터벌 5×800m"
    static func displayName(for kind: TodayWorkout.Kind) -> String {
        "런미새 · \(kind.label)"
    }

    /// 구성 요약 한 줄 — 빌더 규칙과 같은 소스에서 낸다. 달리지 않는 날이면 nil.
    /// "5.0km · 페이스 알림" / "워밍업 10분 · 5×800m(회복 2분) · 쿨다운 10분 · 페이스 알림"
    static func summary(for today: TodayWorkout) -> String? {
        let warmup = "워밍업 \(minutesLabel(warmupMinutes * 60))"
        let cooldown = "쿨다운 \(minutesLabel(warmupMinutes * 60))"
        let distance = today.distanceKm.map { "\(Format.km($0))km" } ?? "거리 자유"
        let parts: [String]
        switch today.kind {
        case .rest, .doneCount, .doneKm:
            return nil
        case .easy, .lsd:
            parts = [distance]
        case .tempo:
            parts = [warmup, distance, cooldown]
        case .interval(let reps, let meters):
            let recovery = minutesLabel(recoverySeconds(meters: meters))
            parts = [warmup, "\(reps)×\(meters)m(회복 \(recovery))", cooldown]
        }
        return (parts + (today.paceSecPerKm == nil ? [] : ["페이스 알림"]))
            .joined(separator: " · ")
    }

    // MARK: - 내부

    private static func speedAlert(paceSecPerKm: ClosedRange<Double>) -> SpeedRangeAlert {
        .speed(speedRangeMetersPerSecond(paceSecPerKm: paceSecPerKm),
               unit: .metersPerSecond, metric: .current)
    }

    /// 모든 구간의 목표·알림이 워치에서 지원되는지 — 하나라도 아니면 false
    private static func isSupported(_ workout: CustomWorkout) -> Bool {
        let steps = [workout.warmup, workout.cooldown].compactMap { $0 }
            + workout.blocks.flatMap { $0.steps.map(\.step) }
        return steps.allSatisfy { step in
            guard CustomWorkout.supportsGoal(step.goal, activity: activity, location: location) else {
                return false
            }
            guard let alert = step.alert else { return true }
            return CustomWorkout.supportsAlert(alert, activity: activity, location: location)
        }
    }

    /// 120 → "2분", 90 → "1분 30초"
    private static func minutesLabel(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return s % 60 == 0 ? "\(s / 60)분" : "\(s / 60)분 \(s % 60)초"
    }
}
