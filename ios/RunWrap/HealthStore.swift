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

    #if targetEnvironment(simulator)
    // 시뮬레이터에는 워치 기록이 없다 — 권한 단계 없이 바로 합성 데이터를 보여준다
    @Published private(set) var state: State = .loaded(DemoData.runs)
    @Published private(set) var vitals: VitalsSnapshot? = DemoData.vitals
    #else
    @Published private(set) var state: State = .idle
    /// 체력 배터리용 활력징후 — 러닝 목록과 별개로 실패해도 리포트는 뜬다
    @Published private(set) var vitals: VitalsSnapshot?
    #endif

    private let store = HKHealthStore()

    private let readTypes: Set<HKObjectType> = [
        .workoutType(),
        HKSeriesType.workoutRoute(),                    // 세션 상세: 경로
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.heartRate),
        HKQuantityType(.stepCount),                     // 세션 상세: 케이던스
        HKCharacteristicType(.dateOfBirth),             // 심박 존: HRmax 추정 (Tanaka)
        HKQuantityType(.heartRateVariabilitySDNN),      // 체력 배터리: 회복 신호
        HKQuantityType(.restingHeartRate),
        HKQuantityType(.respiratoryRate),
        HKQuantityType(.appleSleepingWristTemperature),
        HKCategoryType(.sleepAnalysis),
    ]

    /// 최초 연결: 권한 요청 → 바로 조회
    func connect() async {
        #if targetEnvironment(simulator)
        state = .loaded(DemoData.runs)  // 시뮬레이터에는 워치 기록이 없어 합성 데이터로 대체
        #else
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
        #endif
    }

    func load() async {
        #if targetEnvironment(simulator)
        state = .loaded(DemoData.runs)
        vitals = DemoData.vitals
        #else
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        // 이미 목록이 떠 있으면 조용히 갱신 (.refreshable이 로딩 화면으로 튀지 않게)
        if case .loaded = state {} else { state = .loading }
        do {
            // 업데이트로 읽기 항목이 늘 수 있어 매번 요청 — 이미 응답한 항목은 시트가 뜨지 않는다
            try? await store.requestAuthorization(toShare: [], read: readTypes)
            let workouts = try await fetchRunningWorkouts(limit: 100)
            state = .loaded(workouts.map(Self.summary(of:)))
            vitals = await fetchVitals()
        } catch {
            state = .failed(error.localizedDescription)
        }
        #endif
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

    // MARK: - 활력징후 (체력 배터리)

    /// 최근 28일 활력징후를 모아 스냅샷으로 만든다 — 없는 항목은 nil로 남긴다
    private func fetchVitals(now: Date = .now) async -> VitalsSnapshot {
        var snapshot = VitalsSnapshot()
        snapshot.hrvMs = await reading(.heartRateVariabilitySDNN,
                                       unit: .secondUnit(with: .milli), now: now)
        snapshot.restingHR = await reading(.restingHeartRate,
                                           unit: HKUnit.count().unitDivided(by: .minute()), now: now)
        snapshot.respiratoryRate = await reading(.respiratoryRate,
                                                 unit: HKUnit.count().unitDivided(by: .minute()), now: now)
        snapshot.wristTempC = await reading(.appleSleepingWristTemperature,
                                            unit: .degreeCelsius(), now: now)
        snapshot.sleepHours = await lastNightSleepHours(now: now)
        return snapshot
    }

    /// 최근 29일 표본 → 오늘 값 + 그 이전 일평균 기준선
    ///
    /// 활력징후는 주로 수면 중 기록되므로 "오늘 값"은 오늘 날짜의 표본 평균,
    /// 없으면 어제 표본으로 대체한다. 기준선은 그보다 앞선 날들의 일평균 평균.
    private func reading(_ id: HKQuantityTypeIdentifier,
                         unit: HKUnit,
                         now: Date) async -> VitalsSnapshot.Reading? {
        guard let samples = try? await quantitySamples(HKQuantityType(id),
                                                       from: now.addingTimeInterval(-29 * 86_400),
                                                       to: now),
              !samples.isEmpty else { return nil }
        let calendar = Calendar.current
        var byDay: [Date: [Double]] = [:]
        for sample in samples {
            byDay[calendar.startOfDay(for: sample.startDate), default: []]
                .append(sample.quantity.doubleValue(for: unit))
        }
        func mean(_ values: [Double]) -> Double { values.reduce(0, +) / Double(values.count) }

        let today = calendar.startOfDay(for: now)
        let todayKey = byDay[today] != nil ? today : today.addingTimeInterval(-86_400)
        guard let todayValues = byDay[todayKey] else { return nil }
        let baselineDays = byDay.filter { $0.key < todayKey }
        guard !baselineDays.isEmpty else { return nil }
        return .init(today: mean(todayValues),
                     baseline: mean(baselineDays.values.map(mean)),
                     baselineDays: baselineDays.count)
    }

    /// 지난밤 수면 시간 — 최근 24시간에 끝난 "잠든" 구간을 겹침 없이 합산
    private func lastNightSleepHours(now: Date) async -> Double? {
        let predicate = HKQuery.predicateForSamples(withStart: now.addingTimeInterval(-36 * 3_600),
                                                    end: now)
        let samples: [HKCategorySample]? = try? await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: HKCategoryType(.sleepAnalysis),
                                      predicate: predicate,
                                      limit: HKObjectQueryNoLimit,
                                      sortDescriptors: nil) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (samples as? [HKCategorySample]) ?? [])
                }
            }
            store.execute(query)
        }
        guard let samples else { return nil }

        let asleepValues = Set(HKCategoryValueSleepAnalysis.allAsleepValues.map(\.rawValue))
        let cutoff = now.addingTimeInterval(-24 * 3_600)
        // 워치+아이폰 등 여러 소스가 같은 밤을 중복 기록할 수 있어 구간을 병합한다
        let intervals = samples
            .filter { asleepValues.contains($0.value) && $0.endDate > cutoff }
            .map { (start: $0.startDate, end: $0.endDate) }
            .sorted { $0.start < $1.start }
        var merged: [(start: Date, end: Date)] = []
        for interval in intervals {
            if let last = merged.last, interval.start <= last.end {
                if interval.end > last.end { merged[merged.count - 1].end = interval.end }
            } else {
                merged.append(interval)
            }
        }
        let total = merged.reduce(0.0) { $0 + $1.end.timeIntervalSince($1.start) }
        guard total >= 3_600 else { return nil }  // 1시간 미만이면 수면 기록으로 보지 않는다
        return total / 3_600
    }

    private func quantitySamples(_ type: HKQuantityType,
                                 from: Date,
                                 to: Date) async throws -> [HKQuantitySample] {
        let predicate = HKQuery.predicateForSamples(withStart: from, end: to)
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: type,
                                      predicate: predicate,
                                      limit: HKObjectQueryNoLimit,
                                      sortDescriptors: nil) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (samples as? [HKQuantitySample]) ?? [])
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
