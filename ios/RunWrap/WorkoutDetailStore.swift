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
    /// 세션 최고 심박(bpm)과 존 계산에 쓴 HRmax — 존 카드의 "최고 심박 · HRmax 대비 %" 라인 재료
    var maxHeartRateBpm: Double?
    var hrMaxBpm: Double?
    /// 심박 드리프트(Pw:HR 디커플링) — 존·스플릿용 샘플을 재사용해 추가 쿼리 없음 (제안 문서 A2)
    var drift: DriftEngine.Result?

    // 러닝 다이내믹스 (기획서 §4.8, 계획서 M4) — 실외 세션에만 기록된다 (실내는 애플이 기록하지 않음)
    var verticalOscillationCm: Double?
    var groundContactMs: Double?
    var strideLengthM: Double?
    var runningPowerW: Double?
}

@MainActor
final class WorkoutDetailStore: ObservableObject {
    @Published private(set) var detail: WorkoutDetail?
    @Published private(set) var isLoading = false
    /// 주법 기준선 재료 — 최근 28일 야외 세션들의 다이내믹스 스냅샷 (계획서 M4)
    @Published private(set) var formSnapshots: [FormSnapshot] = []

    private let store = HKHealthStore()

    /// others: 기준선 재료 후보(전체 목록 그대로) — 창·표본 가드는 FormEngine이 건다
    func load(run: RunSummary, others: [RunSummary] = []) async {
        guard detail == nil, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        // 데모 모드에서는 HealthKit을 건드리지 않고 합성 상세를 만든다 (DemoMode)
        if DemoMode.isActive {
            detail = Self.synthetic(for: run)
            formSnapshots = Self.syntheticSnapshots(others: others, excluding: run.id)
        } else {
            detail = await fetch(run: run)
            formSnapshots = await fetchFormSnapshots(others: others, excluding: run.id)
        }
    }

    // MARK: - 실기기: HealthKit 조회

    private func fetch(run: RunSummary) async -> WorkoutDetail {
        var detail = WorkoutDetail()
        guard HKHealthStore.isHealthDataAvailable(),
              let workout = try? await fetchWorkout(id: run.id) else { return detail }

        // 실내(트레드밀) 세션에는 경로·고도가 없다 — 쿼리 자체를 생략한다 (계획서 M1)
        if !run.isIndoor {
            detail.route = (try? await fetchRoute(of: workout)) ?? []

            if let elevation = workout.metadata?[HKMetadataKeyElevationAscended] as? HKQuantity {
                detail.elevationM = elevation.doubleValue(for: .meter())
            }

            // 러닝 다이내믹스 — 실내에는 기록되지 않는다(애플 공식). isIndoor 분기에 더해
            // 쿼리가 비면 nil로 남아 화면의 미노출 가드와 이중으로 걸린다 (계획서 M4)
            detail.verticalOscillationCm = await average(.runningVerticalOscillation,
                                                         unit: .meterUnit(with: .centi), in: workout)
            detail.groundContactMs = await average(.runningGroundContactTime,
                                                   unit: .secondUnit(with: .milli), in: workout)
            detail.strideLengthM = await average(.runningStrideLength, unit: .meter(), in: workout)
            detail.runningPowerW = await average(.runningPower, unit: .watt(), in: workout)
        }

        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        let hrSamples = (try? await fetchQuantitySamples(.heartRate, in: workout)) ?? []
        if !hrSamples.isEmpty {
            let (hrMax, estimated) = heartRateMax()
            detail.zones = Self.zoneFractions(samples: hrSamples, hrMax: hrMax)
            detail.hrMaxEstimated = estimated
            detail.hrMaxBpm = hrMax
            detail.maxHeartRateBpm = hrSamples.map { $0.quantity.doubleValue(for: bpmUnit) }.max()
        }

        let distanceSamples = (try? await fetchQuantitySamples(.distanceWalkingRunning, in: workout)) ?? []
        if !distanceSamples.isEmpty {
            detail.splits = Self.splits(from: distanceSamples)
        }

        // 심박 드리프트 — 존·스플릿용으로 이미 가져온 샘플을 재사용한다 (추가 쿼리 없음, 제안 문서 A2)
        if !hrSamples.isEmpty, !distanceSamples.isEmpty {
            detail.drift = DriftEngine.compute(
                hrSamples: hrSamples.map {
                    (time: $0.startDate, bpm: $0.quantity.doubleValue(for: bpmUnit))
                },
                distanceSamples: distanceSamples.map {
                    (start: $0.startDate, end: $0.endDate,
                     meters: $0.quantity.doubleValue(for: .meter()))
                },
                start: workout.startDate,
                durationSec: workout.duration)
        }

        // 케이던스: ① 평균 속도 ÷ 평균 보폭 (다이내믹스가 있는 워치)
        //          ② 걸음 수 합 ÷ 분 (실내·구형 워치 폴백) — 계획서 M4
        if let stride = detail.strideLengthM, stride > 0,
           let meters = run.distanceMeters, workout.duration > 60 {
            detail.cadenceSpm = (meters / workout.duration) / stride * 60
        } else if let steps = try? await fetchStepSum(in: workout), workout.duration > 60 {
            detail.cadenceSpm = steps / (workout.duration / 60)
        }
        return detail
    }

    /// 워크아웃 구간 샘플 평균 — 다이내믹스는 세션 평균 하나면 충분하다 (계획서 M4)
    private func average(_ id: HKQuantityTypeIdentifier, unit: HKUnit,
                         in workout: HKWorkout) async -> Double? {
        guard let samples = try? await fetchQuantitySamples(id, in: workout),
              !samples.isEmpty else { return nil }
        return samples.map { $0.quantity.doubleValue(for: unit) }.reduce(0, +)
            / Double(samples.count)
    }

    /// 기준선 재료 수집 — 최근 28일 야외 세션의 케이던스·진폭·접촉시간.
    /// 케이던스는 목록(HealthStore)이 백필한 값을 재사용해 세션당 쿼리를 줄인다.
    private func fetchFormSnapshots(others: [RunSummary],
                                    excluding id: UUID) async -> [FormSnapshot] {
        let cutoff = Date().addingTimeInterval(-FormEngine.windowDays * 86_400)
        let candidates = others
            .filter { !$0.isIndoor && $0.id != id && $0.start >= cutoff }
            .prefix(20)  // 쿼리 상한 — 기준선 평균에는 20회면 충분하다
        var snapshots: [FormSnapshot] = []
        for other in candidates {
            guard let workout = try? await fetchWorkout(id: other.id) else { continue }
            snapshots.append(FormSnapshot(
                id: other.id, start: other.start,
                cadenceSpm: other.cadenceSpm,
                verticalOscillationCm: await average(.runningVerticalOscillation,
                                                     unit: .meterUnit(with: .centi), in: workout),
                groundContactMs: await average(.runningGroundContactTime,
                                               unit: .secondUnit(with: .milli), in: workout)))
        }
        return snapshots
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

    /// 워크아웃에 연결된 샘플만 읽는다. 시간 범위로만 잡으면 같은 구간을 아이폰과 워치가
    /// 각각 기록했을 때 둘 다 합산돼 거리(=km 스플릿 개수)가 부풀려진다.
    private func fetchQuantitySamples(_ id: HKQuantityTypeIdentifier,
                                      in workout: HKWorkout) async throws -> [HKQuantitySample] {
        let linked = try await quantitySamples(id, predicate: .linked(to: workout))
        if !linked.isEmpty { return linked }
        // 샘플을 워크아웃에 연결하지 않는 기록도 있다 — 이때는 기록한 기기 하나로만 좁힌다
        return try await quantitySamples(id, predicate: .sameSourceDuring(workout))
    }

    private func quantitySamples(_ id: HKQuantityTypeIdentifier,
                                 predicate: NSPredicate) async throws -> [HKQuantitySample] {
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
        // 걸음도 같은 이유로 중복 합산될 수 있다 (케이던스가 부풀려짐)
        if let linked = try await stepSum(predicate: .linked(to: workout)), linked > 0 {
            return linked
        }
        return try await stepSum(predicate: .sameSourceDuring(workout))
    }

    private func stepSum(predicate: NSPredicate) async throws -> Double? {
        try await withCheckedThrowingContinuation { continuation in
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
    /// index는 실제 km 번호 — 데이터 오류로 건너뛴 구간이 있어도 눈금이 밀리지 않는다.
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
                        result.append(WorkoutDetail.Split(index: Int(nextBoundary / 1000),
                                                          paceSecPerKm: sec))
                    }
                }
                boundaryTime = crossing
                nextBoundary += 1000
            }
        }
        return result
    }

    // MARK: - 데모 모드: 합성 데이터 (run.id 시드 — 같은 세션은 항상 같은 모양)

    static func synthetic(for run: RunSummary) -> WorkoutDetail {
        var rng = SplitMix64(seed: UInt64(bitPattern: Int64(run.id.hashValue)))
        var detail = WorkoutDetail()

        let km = run.distanceKm ?? 8
        let basePace = run.paceSecPerKm ?? 360

        // 실내(트레드밀)에는 경로·고도가 없다 — 스플릿·존·케이던스는 그대로 만든다 (계획서 M1)
        if !run.isIndoor {
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

        let dynamics = syntheticDynamics(for: run)
        detail.cadenceSpm = dynamics.cadenceSpm
        detail.verticalOscillationCm = dynamics.oscillationCm
        detail.groundContactMs = dynamics.contactMs
        detail.strideLengthM = dynamics.strideM
        detail.runningPowerW = dynamics.powerW
        if !run.isIndoor {
            detail.elevationM = 30 + rng.unit() * 70
        }

        // 최고 심박·드리프트 합성 — 기존 rng 호출 뒤에 둬 위 값들의 재현성을 깨지 않는다
        detail.hrMaxBpm = 190  // 합성은 생년월일이 없어 폴백 HRmax와 맞춘다
        detail.maxHeartRateBpm = min((run.avgHeartRate ?? 150) + 22 + rng.unit() * 12, 188)
        if run.durationSec >= 1_800 {
            // 후반 처짐 스플릿과 결이 맞는 완만한 양수 디커플링 (2~8%)
            let decoupling = 2 + rng.unit() * 6
            let firstEF = 1.9 + rng.unit() * 0.4
            detail.drift = DriftEngine.Result(decouplingPct: decoupling,
                                              firstHalfEF: firstEF,
                                              secondHalfEF: firstEF / (1 + decoupling / 100),
                                              tone: decoupling < 5 ? .steady : .caution)
        }
        return detail
    }

    /// 다이내믹스 합성 — 상세와 기준선 스냅샷이 같은 값을 보도록 시드를 분리해 둔다.
    /// 실내는 다이내믹스 미기록(애플 공식)이라 케이던스만 만든다 (계획서 M4).
    static func syntheticDynamics(for run: RunSummary)
        -> (cadenceSpm: Double, oscillationCm: Double?, contactMs: Double?,
            strideM: Double?, powerW: Double?) {
        var rng = SplitMix64(seed: UInt64(bitPattern: Int64(run.id.hashValue)) &+ 0x51DE)
        let cadence = 163 + rng.unit() * 14
        guard !run.isIndoor else {
            return (cadenceSpm: cadence, oscillationCm: nil, contactMs: nil,
                    strideM: nil, powerW: nil)
        }
        let speed = (run.distanceMeters ?? 8_000) / max(run.durationSec, 60)  // m/s
        return (cadenceSpm: cadence,
                oscillationCm: 6.6 + rng.unit() * 2.6,
                contactMs: 225 + rng.unit() * 60,
                strideM: speed / cadence * 60,  // 속도 ÷ 케이던스 = 걸음당 거리 — 값끼리 정합
                powerW: 205 + rng.unit() * 70)
    }

    /// 기준선 스냅샷 합성 — 필터 기준은 실기기 fetchFormSnapshots와 동일
    static func syntheticSnapshots(others: [RunSummary], excluding id: UUID) -> [FormSnapshot] {
        let cutoff = Date().addingTimeInterval(-FormEngine.windowDays * 86_400)
        return others
            .filter { !$0.isIndoor && $0.id != id && $0.start >= cutoff }
            .map { other in
                let dynamics = syntheticDynamics(for: other)
                return FormSnapshot(id: other.id, start: other.start,
                                    cadenceSpm: dynamics.cadenceSpm,
                                    verticalOscillationCm: dynamics.oscillationCm,
                                    groundContactMs: dynamics.contactMs)
            }
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
}

private extension NSPredicate {
    /// 이 워크아웃에 연결된 샘플만
    static func linked(to workout: HKWorkout) -> NSPredicate {
        HKQuery.predicateForObjects(from: workout)
    }

    /// 워크아웃 시간대 + 워크아웃을 기록한 기기 하나 (연결 정보가 없을 때의 폴백)
    static func sameSourceDuring(_ workout: HKWorkout) -> NSPredicate {
        NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: workout.startDate,
                                        end: workout.endDate, options: []),
            HKQuery.predicateForObjects(from: workout.sourceRevision.source),
        ])
    }
}
