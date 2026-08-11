import Foundation
import HealthKit

/// HealthKit 읽기 전용 래퍼 — 권한 요청 + 최근 러닝 조회 (MVP 1단계)
///
/// 읽기 권한은 허용 여부를 앱이 조회할 수 없다(애플 정책 — 거부 사실 자체가
/// 민감 정보). 그래서 요청 후 실제 조회 결과(빈 목록 여부)로만 안내한다.
@MainActor
final class HealthStore: ObservableObject {
    enum State: Equatable {
        case idle          // 권한 요청 전
        case loading
        case loaded([RunSummary])
        case unavailable   // HealthKit 미지원 기기 (iPad 등)
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    private let store = HKHealthStore()

    private let readTypes: Set<HKObjectType> = [
        .workoutType(),
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.heartRate),
    ]

    /// 최초 연결: 권한 요청 → 바로 조회
    func connect() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        state = .loading
        do {
            try await store.requestAuthorization(toShare: [], read: readTypes)
            await load()
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func load() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        state = .loading
        do {
            let workouts = try await fetchRunningWorkouts(limit: 100)
            state = .loaded(workouts.map(Self.summary(of:)))
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func fetchRunningWorkouts(limit: Int) async throws -> [HKWorkout] {
        let predicate = HKQuery.predicateForWorkouts(with: .running)
        let byRecent = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: .workoutType(),
                                      predicate: predicate,
                                      limit: limit,
                                      sortDescriptors: [byRecent]) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (samples as? [HKWorkout]) ?? [])
                }
            }
            store.execute(query)
        }
    }

    private static func summary(of workout: HKWorkout) -> RunSummary {
        let distance = workout.statistics(for: HKQuantityType(.distanceWalkingRunning))?
            .sumQuantity()?
            .doubleValue(for: .meter())
        let bpm = workout.statistics(for: HKQuantityType(.heartRate))?
            .averageQuantity()?
            .doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
        return RunSummary(id: workout.uuid,
                          start: workout.startDate,
                          durationSec: workout.duration,
                          distanceMeters: distance,
                          avgHeartRate: bpm)
    }
}
