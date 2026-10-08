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

    /// 경로 원본 — 솎지 않은 전체 점(시각·고도·속도 포함). 솎기는 표시 직전에 한다 (#222)
    var route: [TrackPoint] = []
    var splits: [Split] = []
    var zones: [Double]?          // Z1~Z5 비율 (합 1)
    var cadenceSpm: Double?
    var elevationM: Double?
    /// 세션 최고 심박(bpm) — 존 카드의 "최고 심박 · HRmax 대비 %" 라인 재료
    var maxHeartRateBpm: Double?
    /// 존 계산에 쓴 심박 기준 — 존 카드의 방식·HRmax 출처·HRmax 대비 % 재료 (이슈 #56)
    var heartRate: HeartRateProfile?
    /// 심박 드리프트(Pw:HR 디커플링) — 존·스플릿용 샘플을 재사용해 추가 쿼리 없음 (제안 문서 A2)
    var drift: DriftEngine.Result?

    // 러닝 다이내믹스 (기획서 §4.8, 계획서 M4) — 실외 세션에만 기록된다 (실내는 애플이 기록하지 않음)
    var verticalOscillationCm: Double?
    var groundContactMs: Double?
    var strideLengthM: Double?
    var runningPowerW: Double?

    /// Apple 운동 노력도 — iOS 18 미만이거나 기록이 없으면 nil(미노출) (이슈 #178)
    var effort: EffortScore?
}

/// Apple 운동 노력도 (이슈 #178) — 워치에서 입력한 1~10 척도. 직접 입력 > Apple 추정
struct EffortScore: Equatable {
    let score: Double
    let isEstimated: Bool

    /// Apple 척도 구간: 1~3 편안 · 4~6 보통 · 7~8 힘듦 · 9~10 전력
    var label: String { Self.label(for: score) }
    var sourceLabel: String { isEstimated ? "Apple 추정" : "직접 입력" }

    /// 구간 경계는 정수 척도 사이(3.5·6.5·8.5)에 둔다 — 추정값이 소수로 와도 가까운 구간에 붙는다
    static func label(for score: Double) -> String {
        if score < 3.5 { return "편안" }
        if score < 6.5 { return "보통" }
        if score < 8.5 { return "힘듦" }
        return "전력"
    }
}

@MainActor
final class WorkoutDetailStore: ObservableObject {
    @Published private(set) var detail: WorkoutDetail?
    @Published private(set) var isLoading = false
    /// 워크아웃 조회 자체가 실패했는지 — '데이터 없음'(빈 detail)과 구분해 다시 시도를 띄운다.
    /// 실패하면 detail을 nil로 둬 load() 재호출이 guard에 막히지 않는다 (이슈 #102)
    @Published private(set) var loadFailed = false
    /// 주법 기준선 재료 — 세션 직전 28일 야외 세션들의 다이내믹스 스냅샷 (계획서 M4)
    @Published private(set) var formSnapshots: [FormSnapshot] = []
    /// 스냅샷 조회 중 — 이 동안 표본 부족 안내 대신 로딩 문구를 띄운다 (이슈 #92)
    @Published private(set) var isLoadingSnapshots = false
    /// 스냅샷 조회 세대 — 마지막으로 시작한 조회만 결과를 반영한다.
    /// load()는 detail 조회 중 세대가 바뀌었으면 옛 others로 재조회하지 않는다
    private var snapshotGeneration = 0

    private let store = HKHealthStore()

    /// others: 기준선 재료 후보(전체 목록 그대로) — 창·표본 가드는 FormEngine이 건다
    /// heartRate: 화면이 TrainingGuideEngine.heartRateProfile로 해석한 값만 받는다 —
    /// 존 기준 산출을 엔진 한 곳으로 모은다 (이슈 #48·#56). 스토어 참조가 아니라 값만 받는다
    func load(run: RunSummary, others: [RunSummary] = [], heartRate: HeartRateProfile) async {
        guard detail == nil, !isLoading else { return }
        isLoading = true
        loadFailed = false
        defer { isLoading = false }
        // detail 조회 동안 목록이 로드돼 화면이 reloadSnapshots를 이미 불렀다면
        // 진입 시점의 (비어 있을 수 있는) others로 그 조회를 덮지 않는다 (이슈 #92)
        let startGeneration = snapshotGeneration
        // 데모 모드에서는 HealthKit을 건드리지 않고 합성 상세를 만든다 (DemoMode)
        if DemoMode.isActive {
            detail = Self.synthetic(for: run, heartRate: heartRate)
        } else {
            detail = await fetch(run: run, heartRate: heartRate)
            loadFailed = detail == nil
        }
        guard snapshotGeneration == startGeneration else { return }
        await reloadSnapshots(others: others, excluding: run)
    }

    /// 기준선 스냅샷만 다시 조회한다 — 진입 시 목록이 아직 로드 전이라 빈 목록으로
    /// 한 번 불렸을 때, 목록이 로드되면 화면이 부른다. detail은 재조회하지 않는다 (이슈 #92)
    func reloadSnapshots(others: [RunSummary], excluding run: RunSummary) async {
        snapshotGeneration += 1
        let generation = snapshotGeneration
        isLoadingSnapshots = true
        let snapshots = DemoMode.isActive
            ? Self.syntheticSnapshots(others: others, excluding: run)
            : await fetchFormSnapshots(others: others, excluding: run)
        guard generation == snapshotGeneration else { return }  // 더 새 조회가 결과를 낸다
        formSnapshots = snapshots
        isLoadingSnapshots = false
    }

    // MARK: - 실기기: HealthKit 조회

    /// 워크아웃 조회가 에러로 실패하면 nil — 워크아웃은 있는데 경로·스플릿이 없는 정상 케이스
    /// (실내 등)는 빈 detail을 돌려준다. 둘을 섞으면 실패가 '기록 없음'으로 굳는다 (이슈 #102)
    private func fetch(run: RunSummary, heartRate: HeartRateProfile) async -> WorkoutDetail? {
        var detail = WorkoutDetail()
        guard HKHealthStore.isHealthDataAvailable() else { return detail }
        let workout: HKWorkout
        do {
            guard let found = try await fetchWorkout(id: run.id) else { return detail }
            workout = found
        } catch {
            return nil
        }

        // 운동 노력도는 실내·실외 모두 기록된다 — iOS 18 전용 API (이슈 #178)
        if #available(iOS 18, *) {
            detail.effort = await fetchEffortScore(of: workout)
        }

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
            detail.zones = TrainingGuideEngine.heartRateZones(samples: points, profile: heartRate)
            detail.heartRate = heartRate
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

    /// 워크아웃에 연결된 노력도 — 직접 입력이 있으면 그것, 없으면 Apple 추정, 둘 다 없으면 nil.
    /// 노력도는 워크아웃이 끝난 뒤 따로 저장되는 샘플이라 관계 쿼리로 연결을 읽는다 (이슈 #178).
    /// 콜백형 HKWorkoutEffortRelationshipQuery는 장기 실행 쿼리(stop 필요)라 1회성 async 래퍼인
    /// 쿼리 디스크립터를 쓴다. 실패는 조용히 nil — 부가 정보라 화면을 막지 않는다
    @available(iOS 18, *)
    private func fetchEffortScore(of workout: HKWorkout) async -> EffortScore? {
        let descriptor = HKWorkoutEffortRelationshipQueryDescriptor(
            predicate: HKQuery.predicateForObject(with: workout.uuid),
            anchor: nil, option: .default)  // 전부 받아 아래 규칙(직접 입력 > 추정)으로 고른다
        guard let result = try? await descriptor.result(for: store) else { return nil }
        let samples = result.relationships
            .flatMap { $0.samples ?? [] }
            .compactMap { $0 as? HKQuantitySample }
        // 같은 종류가 여러 개면(수정 입력 등) 가장 최근 것
        func latest(_ id: HKQuantityTypeIdentifier) -> HKQuantitySample? {
            let type = HKQuantityType(id)
            let matching = samples.filter { $0.quantityType == type }
            return matching.max { $0.endDate < $1.endDate }
        }
        if let manual = latest(.workoutEffortScore) {
            return EffortScore(score: manual.quantity.doubleValue(for: .appleEffortScore()),
                               isEstimated: false)
        }
        if let estimated = latest(.estimatedWorkoutEffortScore) {
            return EffortScore(score: estimated.quantity.doubleValue(for: .appleEffortScore()),
                               isEstimated: true)
        }
        return nil
    }

    /// 워크아웃 구간 샘플 평균 — 다이내믹스는 세션 평균 하나면 충분하다 (계획서 M4)
    private func average(_ id: HKQuantityTypeIdentifier, unit: HKUnit,
                         in workout: HKWorkout) async -> Double? {
        guard let samples = try? await fetchQuantitySamples(id, in: workout),
              !samples.isEmpty else { return nil }
        return samples.map { $0.quantity.doubleValue(for: unit) }.reduce(0, +)
            / Double(samples.count)
    }

    /// 기준선 재료 수집 — 세션 직전 28일 야외 세션의 케이던스·진폭·접촉시간.
    /// 창은 '지금'이 아니라 run.start 기준 — 과거 세션을 그 이후 기록과 비교하지 않는다 (이슈 #92).
    /// 케이던스는 목록(HealthStore)이 백필한 값을 재사용해 세션당 쿼리를 줄인다.
    private func fetchFormSnapshots(others: [RunSummary],
                                    excluding run: RunSummary) async -> [FormSnapshot] {
        let cutoff = run.start.addingTimeInterval(-FormEngine.windowDays * 86_400)
        let candidates = others
            .filter { !$0.isIndoor && $0.id != run.id && $0.start >= cutoff && $0.start < run.start }
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

    private func fetchRoute(of workout: HKWorkout) async throws -> [TrackPoint] {
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
                    // 원본 전체를 보관한다 — 음수 정확도·속도는 '측정 무효'라 nil로 둔다 (애플 문서)
                    continuation.resume(returning: locations.map { location in
                        TrackPoint(lat: location.coordinate.latitude,
                                   lon: location.coordinate.longitude,
                                   time: location.timestamp,
                                   elevationM: location.verticalAccuracy < 0 ? nil : location.altitude,
                                   horizontalAccuracyM: location.horizontalAccuracy,
                                   speedMps: location.speed < 0 ? nil : location.speed)
                    })
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

    // MARK: - 데모 모드: 합성 데이터 (syntheticSeed — 같은 세션은 항상 같은 모양)

    /// 합성 시드 — run.id.hashValue는 프로세스마다 달라져(Hasher 무작위 시드) 쓰지 않는다.
    /// uuid 16바이트 + 시작 시각 비트 패턴을 FNV-1a(64비트)로 섞는다 (이슈 #102)
    nonisolated static func syntheticSeed(for run: RunSummary) -> UInt64 {
        let idBytes = withUnsafeBytes(of: run.id.uuid) { Array($0) }
        let startBytes = withUnsafeBytes(of: run.start.timeIntervalSince1970.bitPattern.littleEndian) { Array($0) }
        var hash: UInt64 = 0xCBF2_9CE4_8422_2325          // FNV-1a 64비트 오프셋 basis
        for byte in idBytes + startBytes {
            hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01B3  // FNV 64비트 소수
        }
        return hash
    }

    static func synthetic(for run: RunSummary, heartRate: HeartRateProfile) -> WorkoutDetail {
        var rng = SplitMix64(seed: syntheticSeed(for: run))
        var detail = WorkoutDetail()

        let km = run.distanceKm ?? 8
        let basePace = run.paceSecPerKm ?? 360

        // 실내(트레드밀)에는 경로·고도가 없다 — 스플릿·존·케이던스는 그대로 만든다 (계획서 M1)
        if !run.isIndoor {
            // 한강 언저리 순환 코스 느낌의 타원 + 흔들림
            let center = (lat: 37.520 + rng.unit() * 0.02, lon: 126.94 + rng.unit() * 0.03)
            let radius = 0.0016 * km.squareRoot()
            let points = 140
            // 시각은 시작부터 세션 시간을 points 등분한 일정 간격
            let interval = run.durationSec / Double(points)
            detail.route = (0...points).map { i in
                let t = Double(i) / Double(points) * 2 * .pi
                let wobble = 1 + 0.10 * sin(t * 3 + rng.offset) + 0.05 * sin(t * 7)
                return TrackPoint(lat: center.lat + radius * wobble * sin(t) * 0.72,
                                  lon: center.lon + radius * wobble * cos(t),
                                  time: run.start.addingTimeInterval(interval * Double(i)),
                                  elevationM: nil, horizontalAccuracyM: 5, speedMps: nil)
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
        detail.zones = zones   // 합성 비율은 존 방식과 무관하다 (데모 한계 — Karvonen 계산은 엔진 테스트가 맡는다)

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
        // 실기기와 같은 경로로 심박 기준을 주입받는다 (이슈 #48·#56). 세션 최고 심박은 HRmax 관찰 표본과
        // 같은 run.maxHeartRate를 쓰고 HRmax로 캡한다 — 데모에서 "HRmax의 104%"가 나오지 않게.
        // jitter는 maxHeartRate가 없을 때만 쓰지만 rng 호출 순서 유지를 위해 항상 뽑는다
        detail.heartRate = heartRate
        let jitter = rng.unit()
        let peak = run.maxHeartRate ?? (run.avgHeartRate ?? 150) + 22 + jitter * 12
        detail.maxHeartRateBpm = min(peak, heartRate.hrMax)
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

        // 노력도 합성 — 4~8 정수(Apple 추정). 맨 끝에서 뽑아 위 값들의 재현성을 깨지 않는다 (이슈 #178)
        detail.effort = EffortScore(score: Double(4 + Int(rng.unit() * 5)), isEstimated: true)
        return detail
    }

    /// 다이내믹스 합성 — 상세와 기준선 스냅샷이 같은 값을 보도록 시드를 분리해 둔다.
    /// 실내는 다이내믹스 미기록(애플 공식)이라 케이던스만 만든다 (계획서 M4).
    static func syntheticDynamics(for run: RunSummary)
        -> (cadenceSpm: Double, oscillationCm: Double?, contactMs: Double?,
            strideM: Double?, powerW: Double?) {
        var rng = SplitMix64(seed: syntheticSeed(for: run) &+ 0x51DE)
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
    static func syntheticSnapshots(others: [RunSummary], excluding run: RunSummary) -> [FormSnapshot] {
        let cutoff = run.start.addingTimeInterval(-FormEngine.windowDays * 86_400)
        return others
            .filter { !$0.isIndoor && $0.id != run.id && $0.start >= cutoff && $0.start < run.start }
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

/// HealthStore의 베스트 에포트 백필도 같은 규칙을 쓴다 (이슈 #166)
extension NSPredicate {
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
