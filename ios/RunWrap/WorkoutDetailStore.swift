import Foundation
import CoreLocation
import HealthKit

/// 세션 상세 화면용 추가 데이터 — 경로·구간 페이스·심박 존·케이던스·상승 고도.
/// RunSummary(목록)에 없는 값만 지연 조회한다.
struct WorkoutDetail {
    struct Split: Identifiable {
        let index: Int            // 1부터
        let paceSecPerKm: Double
        var id: Int { index }
    }

    var route: [CLLocationCoordinate2D] = []
    var splits: [Split] = []
    var zones: [Double]?          // Z1~Z5 비율 (합 1)
    var cadenceSpm: Double?
    var elevationM: Double?
    var hrMaxEstimated = false    // true면 생년월일이 없어 HRmax 190 폴백
}

@MainActor
final class WorkoutDetailStore: ObservableObject {
    @Published private(set) var detail: WorkoutDetail?
    @Published private(set) var isLoading = false

    private let store = HKHealthStore()

    func load(run: RunSummary) async {
        guard detail == nil, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        #if targetEnvironment(simulator)
        detail = Self.synthetic(for: run)
        #else
        detail = await fetch(run: run)
        #endif
    }

    // MARK: - 실기기: HealthKit 조회

    #if !targetEnvironment(simulator)
    private func fetch(run: RunSummary) async -> WorkoutDetail {
        var detail = WorkoutDetail()
        guard HKHealthStore.isHealthDataAvailable(),
              let workout = try? await fetchWorkout(id: run.id) else { return detail }

        detail.route = (try? await fetchRoute(of: workout)) ?? []

        if let elevation = workout.metadata?[HKMetadataKeyElevationAscended] as? HKQuantity {
            detail.elevationM = elevation.doubleValue(for: .meter())
        }

        if let hrSamples = try? await fetchQuantitySamples(.heartRate, in: workout), !hrSamples.isEmpty {
            let (hrMax, estimated) = heartRateMax()
            detail.zones = Self.zoneFractions(samples: hrSamples, hrMax: hrMax)
            detail.hrMaxEstimated = estimated
        }

        if let distanceSamples = try? await fetchQuantitySamples(.distanceWalkingRunning, in: workout) {
            detail.splits = Self.splits(from: distanceSamples)
        }

        if let steps = try? await fetchStepSum(in: workout), workout.duration > 60 {
            detail.cadenceSpm = steps / (workout.duration / 60)
        }
        return detail
    }

    private func fetchWorkout(id: UUID) async throws -> HKWorkout? {
        try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: .workoutType(),
                                      predicate: HKQuery.predicateForObject(with: id),
                                      limit: 1, sortDescriptors: nil) { _, samples, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: samples?.first as? HKWorkout) }
            }
            store.execute(query)
        }
    }

    private func fetchRoute(of workout: HKWorkout) async throws -> [CLLocationCoordinate2D] {
        let routeSample: HKWorkoutRoute? = try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: HKSeriesType.workoutRoute(),
                                      predicate: HKQuery.predicateForObjects(from: workout),
                                      limit: 1, sortDescriptors: nil) { _, samples, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: samples?.first as? HKWorkoutRoute) }
            }
            store.execute(query)
        }
        guard let routeSample else { return [] }

        var locations: [CLLocation] = []
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKWorkoutRouteQuery(route: routeSample) { _, batch, done, error in
                if let error { continuation.resume(throwing: error); return }
                locations.append(contentsOf: batch ?? [])
                if done {
                    // 폴리라인은 ~600점이면 충분 — 과한 포인트는 솎는다
                    let stride = max(1, locations.count / 600)
                    let thinned = locations.enumerated()
                        .filter { $0.offset % stride == 0 }
                        .map { $0.element.coordinate }
                    continuation.resume(returning: thinned)
                }
            }
            store.execute(query)
        }
    }

    private func fetchQuantitySamples(_ id: HKQuantityTypeIdentifier,
                                      in workout: HKWorkout) async throws -> [HKQuantitySample] {
        let predicate = HKQuery.predicateForSamples(withStart: workout.startDate,
                                                    end: workout.endDate, options: [])
        let byStart = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: HKQuantityType(id), predicate: predicate,
                                      limit: HKObjectQueryNoLimit,
                                      sortDescriptors: [byStart]) { _, samples, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: (samples as? [HKQuantitySample]) ?? []) }
            }
            store.execute(query)
        }
    }

    private func fetchStepSum(in workout: HKWorkout) async throws -> Double? {
        let predicate = HKQuery.predicateForSamples(withStart: workout.startDate,
                                                    end: workout.endDate, options: [])
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKStatisticsQuery(quantityType: HKQuantityType(.stepCount),
                                          quantitySamplePredicate: predicate,
                                          options: .cumulativeSum) { _, stats, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: stats?.sumQuantity()?.doubleValue(for: .count())) }
            }
            store.execute(query)
        }
    }

    /// Tanaka 공식 HRmax = 208 − 0.7×나이. 생년월일이 없으면 190 폴백(추정 표기)
    private func heartRateMax() -> (Double, estimated: Bool) {
        if let dob = try? store.dateOfBirthComponents(),
           let birthYear = dob.year {
            let age = Calendar.current.component(.year, from: Date()) - birthYear
            if (10...100).contains(age) { return (208 - 0.7 * Double(age), false) }
        }
        return (190, true)
    }

    /// 심박 샘플 → Z1~Z5 시간 비율. 샘플 간격(≤15초 캡)으로 가중한다.
    static func zoneFractions(samples: [HKQuantitySample], hrMax: Double) -> [Double] {
        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        var seconds = [Double](repeating: 0, count: 5)
        for (i, sample) in samples.enumerated() {
            let bpm = sample.quantity.doubleValue(for: bpmUnit)
            let weight: Double
            if i + 1 < samples.count {
                weight = min(samples[i + 1].startDate.timeIntervalSince(sample.startDate), 15)
            } else {
                weight = 5
            }
            let ratio = bpm / hrMax
            let zone = ratio < 0.6 ? 0 : ratio < 0.7 ? 1 : ratio < 0.8 ? 2 : ratio < 0.9 ? 3 : 4
            seconds[zone] += max(weight, 0)
        }
        let total = seconds.reduce(0, +)
        guard total > 0 else { return [0, 0, 0, 0, 0] }
        return seconds.map { $0 / total }
    }

    /// 누적 거리 샘플 → km 스플릿. km 경계는 샘플 사이를 선형 보간한다.
    static func splits(from samples: [HKQuantitySample]) -> [WorkoutDetail.Split] {
        var result: [WorkoutDetail.Split] = []
        var cumulative: Double = 0        // m
        var boundaryTime: Date? = samples.first?.startDate
        var nextBoundary: Double = 1000

        for sample in samples {
            let meters = sample.quantity.doubleValue(for: .meter())
            let before = cumulative
            cumulative += meters
            while cumulative >= nextBoundary, meters > 0 {
                let fraction = (nextBoundary - before) / meters
                let duration = sample.endDate.timeIntervalSince(sample.startDate)
                let crossing = sample.startDate.addingTimeInterval(duration * fraction)
                if let start = boundaryTime {
                    let sec = crossing.timeIntervalSince(start)
                    if sec > 60 {  // 60초/km 미만은 데이터 오류로 본다
                        result.append(WorkoutDetail.Split(index: result.count + 1, paceSecPerKm: sec))
                    }
                }
                boundaryTime = crossing
                nextBoundary += 1000
            }
        }
        return result
    }
    #endif

    // MARK: - 시뮬레이터: 합성 데이터 (run.id 시드 — 같은 세션은 항상 같은 모양)

    #if targetEnvironment(simulator)
    static func synthetic(for run: RunSummary) -> WorkoutDetail {
        var rng = SplitMix64(seed: UInt64(bitPattern: Int64(run.id.hashValue)))
        var detail = WorkoutDetail()

        let km = run.distanceKm ?? 8
        let basePace = run.paceSecPerKm ?? 360

        // 한강 언저리 순환 코스 느낌의 타원 + 흔들림
        let center = (lat: 37.520 + rng.unit() * 0.02, lon: 126.94 + rng.unit() * 0.03)
        let radius = 0.0016 * km.squareRoot()
        let points = 140
        detail.route = (0...points).map { i in
            let t = Double(i) / Double(points) * 2 * .pi
            let wobble = 1 + 0.10 * sin(t * 3 + rng.offset) + 0.05 * sin(t * 7)
            return CLLocationCoordinate2D(
                latitude: center.lat + radius * wobble * sin(t) * 0.72,
                longitude: center.lon + radius * wobble * cos(t))
        }

        // 스플릿: 기본 페이스 ± 8초 흔들림, 마지막 1/4은 점점 처진다 (시안의 후반 드리프트)
        let fullKm = max(Int(km), 1)
        detail.splits = (1...fullKm).map { i in
            var pace = basePace + (rng.unit() - 0.5) * 16
            let lastQuarterStart = fullKm - max(fullKm / 4, 1)
            if i > lastQuarterStart {
                pace += Double(i - lastQuarterStart) * 6
            }
            return WorkoutDetail.Split(index: i, paceSecPerKm: pace)
        }

        var zones = [0.08, 0.22, 0.44, 0.20, 0.06].map { $0 + (rng.unit() - 0.5) * 0.04 }
        let sum = zones.reduce(0, +)
        zones = zones.map { max($0, 0.01) / sum }
        detail.zones = zones
        detail.hrMaxEstimated = true

        detail.cadenceSpm = 168 + rng.unit() * 14
        detail.elevationM = 30 + rng.unit() * 70
        return detail
    }

    /// 재현 가능한 경량 난수 (SplitMix64)
    private struct SplitMix64 {
        var state: UInt64
        let offset: Double
        init(seed: UInt64) {
            state = seed &+ 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            offset = Double((z ^ (z >> 31)) >> 11) / Double(1 << 53) * 2 * .pi
        }
        mutating func next() -> UInt64 {
            state = state &+ 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
        /// 0..<1
        mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
    }
    #endif
}
