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
    var hrMaxEstimated = false    // true면 HRmax 추정치(관찰 최대·Tanaka)가 둘 다 없어 190 폴백
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
    /// hrMaxBpm: HealthStore.hrMaxBpm 값 그대로 — 존 HRmax 산출을 TrainingGuideEngine.hrMax
    /// 한 곳으로 모은다 (이슈 #48). 스토어 참조가 아니라 값만 받는다
    func load(run: RunSummary, others: [RunSummary] = [], hrMaxBpm: Double?) async {
        guard detail == nil, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        // 데모 모드에서는 HealthKit을 건드리지 않고 합성 상세를 만든다 (DemoMode)
        if DemoMode.isActive {
            detail = Self.synthetic(for: run, hrMaxBpm: hrMaxBpm)
            formSnapshots = Self.syntheticSnapshots(others: others, excluding: run.id)
        } else {
            detail = await fetch(run: run, hrMaxBpm: hrMaxBpm)
            formSnapshots = await fetchFormSnapshots(others: others, excluding: run.id)
        }
    }

    // MARK: - 실기기: HealthKit 조회

    private func fetch(run: RunSummary, hrMaxBpm: Double?) async -> WorkoutDetail {
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
            let points = hrSamples.map {
                (time: $0.startDate, bpm: $0.quantity.doubleValue(for: bpmUnit))
            }
            let zoneHrMax = TrainingGuideEngine.zoneHrMax(hrMaxBpm)
            detail.zones = TrainingGuideEngine.heartRateZones(samples: points, hrMax: zoneHrMax.bpm)
            detail.hrMaxEstimated = zoneHrMax.estimated
            detail.hrMaxBpm = zoneHrMax.bpm
            detail.maxHeartRateBpm = TrainingGuideEngine.sessionPeakBpm(points.map(\.bpm))
        }

        let distanceSamples = ((try? await fetchQuantitySamples(.distanceWalkingRunning, in: workout)) ?? [])
            .map { (start: $0.startDate, end: $0.endDate, meters: $0.quantity.doubleValue(for: .meter())) }
        // 오토포즈·신호 대기 구간 — 스플릿·드리프트에서 정지 시간을 뺀다 (이슈 #47)
        let pauses = Self.pauses(of: workout, distanceSamples: distanceSamples)
        if !distanceSamples.isEmpty {
            detail.splits = ActiveTimeline.splits(distanceSamples: distanceSamples, pauses: pauses)
                .map { WorkoutDetail.Split(index: $0.index, paceSecPerKm: $0.paceSecPerKm) }
        }

        // 심박 드리프트 — 존·스플릿용으로 이미 가져온 샘플을 재사용한다 (추가 쿼리 없음, 제안 문서 A2)
        if !hrSamples.isEmpty, !distanceSamples.isEmpty {
            detail.drift = DriftEngine.compute(
                hrSamples: hrSamples.map {
                    (time: $0.startDate, bpm: $0.quantity.doubleValue(for: bpmUnit))
                },
                distanceSamples: distanceSamples,
                start: workout.startDate,
                durationSec: workout.duration,
                pauses: pauses,
                end: workout.endDate)
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

    /// 워크아웃 이벤트 → 정지 구간. 사용자 정지와 모션(오토포즈) 정지를 모두 읽는다.
    /// 이벤트가 없는 기록은 벽시계와 활동 시간이 30초 넘게 어긋날 때만 거리 샘플 공백으로
    /// 추정한다 — 정지 없는 세션의 GPS 공백까지 빼지 않기 위해서 (감사 리포트 M6)
    private static func pauses(of workout: HKWorkout,
                               distanceSamples: [(start: Date, end: Date, meters: Double)]) -> [DateInterval] {
        let markers: [(date: Date, kind: ActiveTimeline.Marker)] = (workout.workoutEvents ?? [])
            .compactMap { event in
                switch event.type {
                case .pause: (date: event.dateInterval.start, kind: .pause)
                case .resume: (date: event.dateInterval.start, kind: .resume)
                case .motionPaused: (date: event.dateInterval.start, kind: .motionPause)
                case .motionResumed: (date: event.dateInterval.start, kind: .motionResume)
                default: nil
                }
            }
        if !markers.isEmpty {
            return ActiveTimeline.pauses(markers: markers, start: workout.startDate, end: workout.endDate)
        }
        let unaccounted = workout.endDate.timeIntervalSince(workout.startDate) - workout.duration
        guard unaccounted > 30 else { return [] }
        return ActiveTimeline.gapPauses(distanceSamples)
    }

    // MARK: - 데모 모드: 합성 데이터 (run.id 시드 — 같은 세션은 항상 같은 모양)

    static func synthetic(for run: RunSummary, hrMaxBpm: Double?) -> WorkoutDetail {
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
        let zoneHrMax = TrainingGuideEngine.zoneHrMax(hrMaxBpm)
        detail.hrMaxEstimated = zoneHrMax.estimated

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
        // 실기기와 같은 경로로 HRmax를 주입받는다 (이슈 #48). 세션 최고 심박은 hrMax 관찰 표본과
        // 같은 run.maxHeartRate를 쓰고 HRmax로 캡한다 — 데모에서 "HRmax의 104%"가 나오지 않게.
        // jitter는 maxHeartRate가 없을 때만 쓰지만 rng 호출 순서 유지를 위해 항상 뽑는다
        detail.hrMaxBpm = zoneHrMax.bpm
        let jitter = rng.unit()
        let peak = run.maxHeartRate ?? (run.avgHeartRate ?? 150) + 22 + jitter * 12
        detail.maxHeartRateBpm = min(peak, zoneHrMax.bpm)
        if run.durationSec >= 1_800 {
            // 후반 처짐 스플릿과 결이 맞는 완만한 양수 디커플링 (2~8%)
            let decoupling = 2 + rng.unit() * 6
            let firstEF = 1.9 + rng.unit() * 0.4
            detail.drift = DriftEngine.Result(decouplingPct: decoupling,
                                              firstHalfEF: firstEF,
                                              secondHalfEF: firstEF / (1 + decoupling / 100),
                                              tone: decoupling < 5 ? .steady : .caution)
        }

        // 신호 대기 시나리오 세션은 합성 샘플을 실제 엔진에 통과시켜 위 난수 값을 덮어쓴다 —
        // 정지 구간 제외가 화면에서 보이게 하는 검증용 (이슈 #47)
        if let scenario = DemoData.pauseScenario(for: run) {
            detail.splits = ActiveTimeline.splits(distanceSamples: scenario.distance, pauses: scenario.pauses)
                .map { WorkoutDetail.Split(index: $0.index, paceSecPerKm: $0.paceSecPerKm) }
            detail.drift = DriftEngine.compute(hrSamples: scenario.hr,
                                               distanceSamples: scenario.distance,
                                               start: run.start,
                                               durationSec: run.durationSec,
                                               pauses: scenario.pauses,
                                               end: scenario.end)
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
