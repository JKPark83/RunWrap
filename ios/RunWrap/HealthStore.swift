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
    /// 체력 배터리용 활력징후 — 러닝 목록과 별개로 실패해도 리포트는 뜬다
    @Published private(set) var vitals: VitalsSnapshot?
    /// 심폐 체력 카드용 최근 12주 VO₂max 표본 (ml/kg/min) — 주 단위 평균은 엔진이 계산한다
    @Published private(set) var vo2Max: [(date: Date, value: Double)] = []
    /// 최근 2주 비러닝 운동 — 주간 리포트 보조 문장 재료. ACWR에는 절대 섞지 않는다 (제안 문서 A3)
    @Published private(set) var crossTrainings: [CrossTraining] = []
    /// 심폐 체력 카드 보조 지표용 최근 12주 심박 회복(HRR) 표본 (bpm)
    @Published private(set) var hrrTrend: [(date: Date, value: Double)] = []
    /// 최대 심박(bpm) 추정과 출처 — 관찰 최대(최근 12주 세션 최고 심박 2번째 값)와
    /// Tanaka(2001) 중 큰 쪽, 둘 다 없으면 190 폴백 (TrainingGuideEngine.hrMaxEstimate).
    /// 수동 입력은 여기서 섞지 않는다 — 스토어는 HealthKit 값만 내고, 화면이
    /// TrainingGuideEngine.heartRateProfile로 수동값과 합친다 (이슈 #34, #48, #56)
    @Published private(set) var hrMaxEstimate: (bpm: Double, source: HeartRateProfile.Source) =
        (TrainingGuideEngine.fallbackHrMaxBpm, .fallback)
    /// 안정 심박(bpm) 최근값 — 최근 28일 중 가장 최근 표본. Karvonen 존의 재료 (이슈 #56)
    @Published private(set) var restingHRBpm: Double?
    /// 최근 28일 러닝의 세션별 심박 bpm 히스토그램 — 기간별 심박존 분포(80/20) 카드 재료.
    /// 존 경계는 화면이 표시 시점의 심박 기준으로 적용한다 (이슈 #165)
    @Published private(set) var zoneHistograms: [UUID: ZoneHistogram] = [:]
    /// 이미 목록이 떠 있을 때 새로 고침이 실패한 사유 — 기존 목록을 .failed로 덮지 않으려고
    /// 따로 싣는다. 아직 표시하는 화면은 없고, 추후 토스트 안내용으로 발행한다 (이슈 #58)
    @Published private(set) var lastError: String?
    /// 워크아웃별 베스트 에포트(이슈 #166) — 거리별 최고 기록(PersonalRecords)의 재료.
    /// 영구 캐시(BestEffortCache)를 먼저 싣고, 빠진 워크아웃은 기동마다 점진 백필한다
    @Published private(set) var bestEfforts: BestEffortTable = [:]
    /// 베스트 에포트가 아직 계산되지 않은 워크아웃 수 — 성장기 PB 카드의 "분석 중" 캡션용
    @Published private(set) var bestEffortPending: Int = 0
    /// 현재 목록이 데모 합성 데이터인지 — 데모를 끄고 처음 조회할 때 실패하면 합성 목록을
    /// "기존 목록"으로 지켜선 안 되므로(#44의 캐시 보호가 풀린다) .failed로 보낸다 (이슈 #58)
    private var isDemoLoaded = false

    private let store = HKHealthStore()

    /// 데모 모드면 합성 데이터를 그대로 물려 HealthKit을 아예 건드리지 않는다.
    /// 시뮬레이터에서는 항상 이 경로다 (DemoMode.isActive).
    init() {
        if DemoMode.isActive { fillWithDemoData() }
    }

    private func fillWithDemoData() {
        isDemoLoaded = true
        state = .loaded(DemoData.runs)
        vitals = DemoData.vitals
        vo2Max = DemoData.vo2Max
        crossTrainings = DemoData.crossTrainings
        hrrTrend = DemoData.hrrTrend
        hrMaxEstimate = TrainingGuideEngine.hrMaxEstimate(runs: DemoData.runs, now: Date(), birthYear: nil)
        // 시뮬레이터에서도 Karvonen 존을 고를 수 있게 합성 활력징후의 안정 심박을 그대로 쓴다
        restingHRBpm = DemoData.vitals.restingHR?.today
        zoneHistograms = DemoData.zoneHistograms
        bestEfforts = DemoData.bestEfforts
        bestEffortPending = 0
    }

    /// 최초 연결: 권한 요청 → 바로 조회
    func connect() async {
        if DemoMode.isActive {
            fillWithDemoData()
            return
        }
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        // 이미 목록이 떠 있으면(재진단) 로딩으로 내리지 않는다 — 루트가 스플래시로 바뀌며
        // 메인 탭이 재생성돼 탭 선택이 홈으로 초기화된다. load()와 같은 원칙 (이슈 #102)
        let wasLoaded: Bool
        if case .loaded = state, !isDemoLoaded { wasLoaded = true } else { wasLoaded = false; state = .loading }
        do {
            try await store.requestAuthorization(toShare: [], read: HealthPermissions.standard)
            await load()
        } catch {
            // 떠 있던 목록은 지키고 사유만 싣는다 — load()의 실패 처리와 같다 (이슈 #58)
            if wasLoaded {
                lastError = error.localizedDescription
            } else {
                state = .failed(error.localizedDescription)
            }
        }
    }

    func load() async {
        if DemoMode.isActive {
            fillWithDemoData()
            return
        }
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        // 이미 목록이 떠 있으면 조용히 갱신 (.refreshable이 로딩 화면으로 튀지 않게)
        let wasLoaded: Bool
        if case .loaded = state, !isDemoLoaded { wasLoaded = true } else { wasLoaded = false; state = .loading }
        do {
            // 업데이트로 읽기 항목이 늘 수 있어 매번 요청 — 이미 응답한 항목은 시트가 뜨지 않는다
            try? await store.requestAuthorization(toShare: [], read: HealthPermissions.standard)
            // 개수 제한 없이 전부 — 최근 N개로 자르면 장기 사용자의 사이클 초반 러닝이
            // 성장 XP 재계산에서 빠진다 (이슈 #29). 요약 변환은 통계 재사용이라 수천 건도 가볍다
            let workouts = try await fetchRunningWorkouts(limit: HKObjectQueryNoLimit)
            var summaries = workouts.map(Self.summary(of:))
            // 케이던스 백필 — 주법 추이(계획서 M4) 재료. 걸음 수는 워크아웃 통계에 없어
            // 워크아웃마다 쿼리해야 한다 — 추이 창인 최근 28일만 채워 쿼리 수를 줄인다.
            let cadenceCutoff = Date().addingTimeInterval(-28 * 86_400)
            for (index, workout) in workouts.enumerated() where workout.startDate >= cadenceCutoff {
                summaries[index].cadenceSpm = await cadenceSpm(of: workout)
            }
            // HRmax — 관찰 최대 우선, 폴백은 생년 기반 Tanaka (읽기 권한은 core에 이미 있다)
            hrMaxEstimate = TrainingGuideEngine.hrMaxEstimate(
                runs: summaries, now: Date(),
                birthYear: (try? store.dateOfBirthComponents())?.year)
            // 안정 심박은 목록을 띄우기 전에 받는다 — 첫 실행에 목록이 먼저 뜨면 그 사이 연
            // 세션 상세가 안정 심박 없이 %HRmax로 존을 굳힌다(상세는 한 번만 로드). 이슈 #56
            restingHRBpm = await latestRestingHR()
            lastError = nil
            isDemoLoaded = false
            state = .loaded(summaries)
            // 권한 응답 후(온보딩 connect)·데모 해제 후 첫 조회 성공 — 기동 때 권한 전이라 실패했거나
            // 데모라 건너뛴 옵저버를 여기서 다시 건다. 이미 살아 있으면 no-op (이슈 #154)
            restartObservingWorkoutsIfNeeded()
            // 캐시된 베스트 에포트를 먼저 반영 — 계산은 함수 끝의 백필이 목록 표시와 별개로 한다 (이슈 #166)
            bestEfforts = BestEffortCache.load()
            bestEffortPending = workouts.filter { bestEfforts[$0.uuid] == nil }.count
            vitals = await fetchVitals()
            vo2Max = await fetchVo2Max()
            crossTrainings = await fetchCrossTrainings()
            hrrTrend = await fetchHrrTrend()
            await backfillZoneHistograms(workouts: workouts)
            backfillBestEfforts(workouts: workouts)
        } catch {
            // 이미 떠 있던 목록은 지키고 사유만 싣는다 — 잠금 중 백그라운드 새로 고침 한 번의
            // 실패가 화면 전체를 오류로 바꾸면 안 된다 (이슈 #58). 목록이 없던 첫 로드만 .failed
            if wasLoaded {
                lastError = error.localizedDescription
            } else {
                state = .failed(error.localizedDescription)
            }
        }
    }

    // MARK: - 백그라운드 워크아웃 감지 (계획서 M8)

    /// 새 워크아웃 저장을 감지하는 옵저버 — 러닝 종료 직후 알림(보조 경로)의 재료.
    /// 등록 직후에도 한 번 불리므로 중복 알림 필터링은 호출부(onUpdate) 몫이다.
    private var workoutObserver: HKObserverQuery?
    /// 앱이 넘긴 옵저버 콜백 — 옵저버가 죽었을 때 스토어가 같은 콜백으로 다시 걸 수 있게 보관한다 (이슈 #154)
    private var workoutUpdateHandler: (@Sendable () async -> Void)?

    /// 옵저버 등록 + 백그라운드 딜리버리 켜기 — 앱 기동마다 호출해도 안전 (재등록 가드).
    /// 데모 모드·시뮬레이터에서는 no-op (시뮬레이터는 백그라운드 딜리버리 자체를 지원하지 않는다).
    /// 콜백이 오류를 받으면(권한 전 등록 등) 옵저버를 걷어 두고,
    /// 다음 조회 성공 때 restartObservingWorkoutsIfNeeded가 다시 건다 (이슈 #154)
    func startObservingWorkouts(onUpdate: @escaping @Sendable () async -> Void) {
        workoutUpdateHandler = onUpdate
        guard !DemoMode.isActive,
              HKHealthStore.isHealthDataAvailable(), workoutObserver == nil else { return }
        let query = HKObserverQuery(sampleType: .workoutType(),
                                    predicate: HKQuery.predicateForWorkouts(with: .running)) { [weak self] query, done, error in
            // 계약: 성패와 무관하게 completionHandler를 반드시 부른다 —
            // 3회 미호출이 쌓이면 background delivery 자체가 끊긴다
            Task {
                if error == nil {
                    await onUpdate()
                } else {
                    // 권한 전 등록 등으로 오류를 받은 옵저버는 다시 불리지 않는다 — 재등록 대상으로 돌린다
                    await self?.discardWorkoutObserver(query)
                }
                done()
            }
        }
        store.execute(query)
        workoutObserver = query
        // 실패해도 옵저버는 걷지 않는다 — 포그라운드에서는 옵저버가 그대로 동작하고, 여기서 걷으면
        // 등록 직후 첫 콜백 → load() → 재등록 → 첫 콜백…의 재조회 루프가 생길 수 있다.
        // 포그라운드 재계산이 1차 경로이므로 조용히 넘어간다
        store.enableBackgroundDelivery(for: .workoutType(), frequency: .immediate) { _, _ in }
    }

    /// 앱이 등록한 적 있는 콜백으로 옵저버를 다시 건다 — 등록한 적이 없거나 살아 있으면 no-op
    private func restartObservingWorkoutsIfNeeded() {
        guard let workoutUpdateHandler else { return }
        startObservingWorkouts(onUpdate: workoutUpdateHandler)
    }

    /// 죽은 옵저버를 멈추고 비운다 — 그 사이 새로 건 옵저버는 건드리지 않는다
    private func discardWorkoutObserver(_ query: HKObserverQuery) {
        guard workoutObserver === query else { return }
        store.stop(query)
        workoutObserver = nil
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

    // MARK: - 베스트 에포트 백필 (이슈 #166)

    /// 한 번에 계산할 워크아웃 수 — 워크아웃마다 거리 샘플 쿼리가 필요해 기동당 비용을 묶는다
    private static let bestEffortBatchSize = 60
    /// 백필 Task가 도는 중인지 — 새로 고침이 겹쳐도 같은 워크아웃을 두 번 계산하지 않는다
    private var isBackfillingBestEfforts = false
    /// 이번 기동에 쿼리가 실패한 워크아웃 — 남은 개수에서 빼 PB 감지가 영원히 막히지 않게 한다.
    /// 캐시에는 넣지 않으므로 다음 기동에 다시 시도한다
    private var bestEffortFailedIDs = Set<UUID>()

    /// 캐시에 없는 워크아웃을 최신부터 최대 60개 계산해 저장한다. load()는 기다리지 않는다 —
    /// 목록 표시를 막지 않고, 끝나면 published 값만 갱신한다. 쿼리 실패(기기 잠금 등)한
    /// 워크아웃은 캐시에 넣지 않아 다음 기동에 다시 시도한다.
    private func backfillBestEfforts(workouts: [HKWorkout]) {
        guard !isBackfillingBestEfforts else { return }
        let pending = workouts.filter { bestEfforts[$0.uuid] == nil }   // workouts는 최신순
        bestEffortPending = pending.count
        guard !pending.isEmpty else { return }
        isBackfillingBestEfforts = true
        Task {
            var computed: BestEffortTable = [:]
            for workout in pending.prefix(Self.bestEffortBatchSize) {
                guard let samples = try? await workoutQuantitySamples(.distanceWalkingRunning,
                                                                      in: workout) else {
                    bestEffortFailedIDs.insert(workout.uuid)
                    continue
                }
                computed[workout.uuid] = BestEffortEngine.bestEfforts(distanceSamples: samples.map {
                    (start: $0.startDate, end: $0.endDate, meters: $0.quantity.doubleValue(for: .meter()))
                })
            }
            let table = bestEfforts.merging(computed) { _, new in new }
            BestEffortCache.save(table)
            bestEfforts = table
            bestEffortPending = workouts.filter {
                table[$0.uuid] == nil && !bestEffortFailedIDs.contains($0.uuid)
            }.count
            isBackfillingBestEfforts = false
        }
    }

    // MARK: - 워크아웃 표본 조회 (백필 공용)

    /// 워크아웃에 연결된 수량 샘플 — 연결 샘플 우선, 없으면 같은 기록 기기의 시간 범위 폴백.
    /// WorkoutDetailStore.fetchQuantitySamples와 같은 규칙 (이슈 #165 #166)
    private func workoutQuantitySamples(_ id: HKQuantityTypeIdentifier,
                                        in workout: HKWorkout) async throws -> [HKQuantitySample] {
        let linked = try await quantitySamples(id, predicate: .linked(to: workout))
        if !linked.isEmpty { return linked }
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

    // MARK: - 케이던스 백필 (주법 추이)

    /// 워크아웃 평균 케이던스(spm) = 걸음 수 합 ÷ 분.
    /// 연결 샘플 우선, 없으면 기록 기기로 좁힌 시간 범위 폴백 —
    /// 아이폰·워치 이중 기록 합산을 피하는 이유는 WorkoutDetailStore.fetchStepSum 참고.
    private func cadenceSpm(of workout: HKWorkout) async -> Double? {
        guard workout.duration > 60 else { return nil }
        if let steps = await stepSum(predicate: HKQuery.predicateForObjects(from: workout)),
           steps > 0 {
            return steps / (workout.duration / 60)
        }
        let sameSource = NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: workout.startDate,
                                        end: workout.endDate, options: []),
            HKQuery.predicateForObjects(from: workout.sourceRevision.source),
        ])
        guard let steps = await stepSum(predicate: sameSource), steps > 0 else { return nil }
        return steps / (workout.duration / 60)
    }

    private func stepSum(predicate: NSPredicate) async -> Double? {
        await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(quantityType: HKQuantityType(.stepCount),
                                          quantitySamplePredicate: predicate,
                                          options: .cumulativeSum) { _, stats, _ in
                continuation.resume(returning: stats?.sumQuantity()?.doubleValue(for: .count()))
            }
            store.execute(query)
        }
    }

    // MARK: - 심박존 히스토그램 백필 (기간별 강도 배분, 이슈 #165)

    /// 최근 28일 러닝의 세션별 bpm 히스토그램을 채운다. 캐시에 있는 워크아웃은 쿼리하지 않고,
    /// 없는 것만 심박 샘플을 읽는다. 샘플이 없는 세션은 빈 히스토그램으로 남겨 매번 다시 묻지 않는다.
    /// 쿼리 자체가 실패한 세션(잠금 중 등)은 비워 두고 다음 조회 때 다시 시도한다.
    /// 창은 ZoneDistributionEngine과 같은 28일 전 자정 ~ 지금, 창 밖 항목은 버린다.
    private func backfillZoneHistograms(workouts: [HKWorkout], now: Date = .now) async {
        let windowStart = Calendar.current.startOfDay(for: now.addingTimeInterval(-28 * 86_400))
        let recent = workouts.filter { $0.startDate >= windowStart }
        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        var cache = ZoneTimeCache.load()
        for workout in recent where cache[workout.uuid] == nil {
            guard let samples = try? await workoutQuantitySamples(.heartRate, in: workout) else { continue }
            // 방금 끝난 워크아웃은 워치의 심박 샘플이 아직 동기화 전일 수 있다 — 빈 히스토그램을
            // 굳히면 그 세션은 영원히 존 없음으로 남으므로, 1시간은 지나야 빈 결과를 캐시한다
            if samples.isEmpty, now.timeIntervalSince(workout.endDate) < 3_600 { continue }
            cache[workout.uuid] = ZoneHistogram.make(samples: samples.map {
                (time: $0.startDate, bpm: $0.quantity.doubleValue(for: bpmUnit))
            })
        }
        cache = ZoneTimeCache.prune(cache, keepingIDs: Set(recent.map(\.uuid)))
        ZoneTimeCache.save(cache)
        zoneHistograms = cache
    }

    // MARK: - 활력징후 (체력 배터리)

    /// 안정 심박 최근값 (이슈 #56) — vitals.restingHR.today는 기준선이 있어야 나와서 따로 본다.
    /// 28일 창 밖은 침묵(nil). 권한은 HealthPermissions.recovery에 이미 있다
    private func latestRestingHR(now: Date = .now) async -> Double? {
        let samples = (try? await quantitySamples(HKQuantityType(.restingHeartRate),
                                                  from: now.addingTimeInterval(-28 * 86_400),
                                                  to: now)) ?? []
        return samples.max { $0.startDate < $1.startDate }?
            .quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
    }

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
        snapshot.hrr = await hrrReading(now: now)
        snapshot.sleepNights = await fetchSleepNights(now: now)
        return snapshot
    }

    /// 심박 회복(HRR) 스냅샷 — 야외 러닝 종료 후 워치가 자동 기록하는 1분 하락 폭 (제안 문서 B1).
    /// 매일 생기는 지표가 아니라 일 단위 기준선 대신 표본 단위로 만든다:
    /// 최근 표본 = 오늘 값, 그보다 앞선 표본들의 평균 = 기저,
    /// Reading.baselineDays 자리에는 기저 '표본 수'를 넣는다 (엔진의 hrrMinBaselineCount 가드용).
    private func hrrReading(now: Date) async -> VitalsSnapshot.Reading? {
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let samples = (try? await quantitySamples(HKQuantityType(.heartRateRecoveryOneMinute),
                                                  from: now.addingTimeInterval(-42 * 86_400),
                                                  to: now)) ?? []
        let sorted = samples.sorted { $0.startDate < $1.startDate }
        // 마지막 러닝이 오래됐으면 "오늘의 회복"을 말할 수 없다 — 3일 지난 값은 침묵 (미노출 가드)
        guard let latest = sorted.last,
              latest.startDate >= now.addingTimeInterval(-3 * 86_400) else { return nil }
        let baseline = sorted.dropLast().map { $0.quantity.doubleValue(for: bpm) }
        guard !baseline.isEmpty else { return nil }
        return .init(today: latest.quantity.doubleValue(for: bpm),
                     baseline: baseline.reduce(0, +) / Double(baseline.count),
                     baselineDays: baseline.count)
    }

    /// 최근 2주 밤별 수면 상세 — 수면 질(깊은+렘 비율)·취침 규칙성 팩터의 재료 (제안 문서 A5).
    /// 밤 구분: 잠든 구간을 먼저 수면 블록으로 묶고, 블록이 끝난 날짜(기상일)에 배정해 기상일마다 가장 긴
    /// 블록만 밤 수면으로 인정한다(같은 날 낮잠 제외 — SleepBlocks.nightBlocks, 이슈 #107).
    /// 3시간 미만은 밤 수면으로 보지 않고 버린다.
    private func fetchSleepNights(now: Date) async -> [VitalsSnapshot.SleepNight] {
        let samples = (try? await categorySamples(HKCategoryType(.sleepAnalysis),
                                                  from: now.addingTimeInterval(-14 * 86_400),
                                                  to: now)) ?? []
        let asleepValues = Set(HKCategoryValueSleepAnalysis.allAsleepValues.map(\.rawValue))
        let stageValues: Set<Int> = [HKCategoryValueSleepAnalysis.asleepCore.rawValue,
                                     HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
                                     HKCategoryValueSleepAnalysis.asleepREM.rawValue]
        let deepRemValues: Set<Int> = [HKCategoryValueSleepAnalysis.asleepDeep.rawValue,
                                       HKCategoryValueSleepAnalysis.asleepREM.rawValue]

        let asleepSamples = samples.filter { asleepValues.contains($0.value) }
        // 날짜로 먼저 묶지 않는다 — 자정 전에 끝난 표본이 전날로 떨어져 밤이 잘린다 (이슈 #107).
        // 워치+아이폰 이중 기록 병합 후 기상일마다 가장 긴 블록만 센다 — 같은 날 낮잠은 제외 (이슈 #99)
        let nights = SleepBlocks.nightBlocks(asleepSamples.map { (start: $0.startDate, end: $0.endDate) },
                                             calendar: Calendar.current)
        return nights.compactMap { night, main -> VitalsSnapshot.SleepNight? in
            guard let blockStart = main.first?.start, let blockEnd = main.last?.end else { return nil }
            let asleepHours = SleepBlocks.asleepSec(main) / 3_600
            guard asleepHours >= 3 else { return nil }

            // 단계 비율은 단계를 기록한 소스(워치)의 표본만으로 계산 —
            // 아이폰의 asleepUnspecified가 분모에 섞이면 비율이 왜곡된다. 낮잠 표본은 빼고 그 블록 안만.
            let blockSamples = asleepSamples.filter { $0.startDate >= blockStart && $0.endDate <= blockEnd }
            let stageSec = blockSamples.filter { stageValues.contains($0.value) }
                .reduce(0.0) { $0 + $1.endDate.timeIntervalSince($1.startDate) }
            let deepRemSec = blockSamples.filter { deepRemValues.contains($0.value) }
                .reduce(0.0) { $0 + $1.endDate.timeIntervalSince($1.startDate) }

            // 취침 시각: 정오(전날 12:00) 기준 경과 분 — 자정 넘김(23시=660, 새벽 1시=780)을
            // 연속값으로 다뤄 표준편차 계산이 깨지지 않게 한다
            let bedtime = blockStart.timeIntervalSince(night.addingTimeInterval(-12 * 3_600)) / 60
            return VitalsSnapshot.SleepNight(date: night,
                                             asleepHours: asleepHours,
                                             deepRemFraction: stageSec > 0 ? deepRemSec / stageSec : nil,
                                             bedtimeMinutes: bedtime)
        }
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

    /// 지난밤 수면 시간 — 최근 24시간 안에 끝난 수면 블록 중 가장 긴 것을 겹침 없이 합산한다(낮잠 제외)
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
        // 워치+아이폰 등 여러 소스가 같은 밤을 중복 기록할 수 있어 구간을 병합하고,
        // 낮잠이 밤 수면에 더해지지 않게 기상일마다 가장 긴 블록만 센다 (이슈 #99)
        let intervals = samples
            .filter { asleepValues.contains($0.value) }
            .map { (start: $0.startDate, end: $0.endDate) }
        // 24시간 컷은 표본이 아니라 블록 기준 — 표본 종료로 자르면 23:30에 볼 때 지난밤 22:00–23:30의
        // 짧은 스테이지 표본이 잘려 나간다 (이슈 #107). 24시간 안에 끝난 블록 중 가장 긴 것을 지난밤으로
        // 본다 — 마지막 블록만 보면 자정 직후 30분 눈 붙인 기록이 진짜 지난밤을 가린다.
        let recent = SleepBlocks.nightBlocks(intervals, calendar: Calendar.current)
            .filter { ($0.block.last?.end ?? .distantPast) > cutoff }
        guard let lastNight = recent.max(by: { SleepBlocks.asleepSec($0.block) < SleepBlocks.asleepSec($1.block) })
        else { return nil }
        let total = SleepBlocks.asleepSec(lastNight.block)
        guard total >= 3_600 else { return nil }  // 1시간 미만이면 수면 기록으로 보지 않는다
        return total / 3_600
    }

    /// 최근 12주 심박 회복(HRR) 표본 — 심폐 체력 카드의 보조 지표 재료 (제안 문서 B1).
    /// 야외 러닝 종료 직후 워치가 자동 기록한다 (실내·중도 종료 세션에는 없을 수 있다).
    private func fetchHrrTrend(now: Date = .now) async -> [(date: Date, value: Double)] {
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let samples = (try? await quantitySamples(HKQuantityType(.heartRateRecoveryOneMinute),
                                                  from: now.addingTimeInterval(-84 * 86_400),
                                                  to: now)) ?? []
        return samples.map { (date: $0.startDate,
                              value: $0.quantity.doubleValue(for: bpm)) }
    }

    // MARK: - 크로스 트레이닝 (비러닝 운동)

    /// 최근 2주 비러닝 워크아웃 — 주간 리포트 보조 문장(CrossTrainingEngine) 재료 (제안 문서 A3).
    /// 러닝만 빼고 전부 가져와 종류 매핑은 앱에서 한다 — 종류별 쿼리 N번보다 싸다.
    private func fetchCrossTrainings(now: Date = .now) async -> [CrossTraining] {
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
            HKQuery.predicateForSamples(withStart: now.addingTimeInterval(-14 * 86_400), end: now),
            NSCompoundPredicate(notPredicateWithSubpredicate:
                HKQuery.predicateForWorkouts(with: .running)),
        ])
        let byRecent = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)
        let workouts: [HKWorkout] = (try? await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: .workoutType(),
                                      predicate: predicate,
                                      limit: 200,
                                      sortDescriptors: [byRecent]) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (samples as? [HKWorkout]) ?? [])
                }
            }
            store.execute(query)
        }) ?? []
        return workouts.map { workout in
            CrossTraining(start: workout.startDate,
                          durationSec: workout.duration,
                          kind: Self.crossKind(of: workout.workoutActivityType),
                          kcal: workout.statistics(for: HKQuantityType(.activeEnergyBurned))?
                              .sumQuantity()?
                              .doubleValue(for: .kilocalorie()))
        }
    }

    /// HealthKit 운동 종류 → 크로스 트레이닝 종류 — 러너가 실제로 병행하는 종목 위주로 추리고
    /// 나머지는 other로 뭉친다 (종류별 문장을 다 만들 수는 없다)
    private static func crossKind(of type: HKWorkoutActivityType) -> CrossTraining.Kind {
        switch type {
        case .cycling: .cycling
        case .traditionalStrengthTraining, .functionalStrengthTraining, .coreTraining: .strength
        case .swimming: .swimming
        case .hiking: .hiking
        case .walking: .walking
        case .yoga, .pilates: .yoga
        case .highIntensityIntervalTraining: .hiit
        default: .other
        }
    }

    /// 최근 12주 VO₂max 표본 — 심폐 체력 추이 카드의 재료.
    /// 워치가 야외 러닝·걷기 세션에서 추정해 기록한다 (실내 세션에는 없다).
    private func fetchVo2Max(now: Date = .now) async -> [(date: Date, value: Double)] {
        // ml/(kg·min) — HKUnit(from:) 문자열 파싱 대신 명시적으로 조립한다
        let unit = HKUnit.literUnit(with: .milli)
            .unitDivided(by: HKUnit.gramUnit(with: .kilo).unitMultiplied(by: .minute()))
        let samples = (try? await quantitySamples(HKQuantityType(.vo2Max),
                                                  from: now.addingTimeInterval(-84 * 86_400),
                                                  to: now)) ?? []
        return samples.map { (date: $0.startDate,
                              value: $0.quantity.doubleValue(for: unit)) }
    }

    private func categorySamples(_ type: HKCategoryType,
                                 from: Date,
                                 to: Date) async throws -> [HKCategorySample] {
        let predicate = HKQuery.predicateForSamples(withStart: from, end: to)
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(sampleType: type,
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
        // 세션 최고 심박 — HRmax 관찰 추정(이슈 #34) 재료. 통계 재사용이라 쿼리 비용 없음
        let maxBpm = workout.statistics(for: HKQuantityType(.heartRate))?
            .maximumQuantity()?
            .doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
        let kcal = workout.statistics(for: HKQuantityType(.activeEnergyBurned))?
            .sumQuantity()?
            .doubleValue(for: .kilocalorie())
        // 실내 여부 — 메타데이터 부재 시 야외 취급 (미기록 = 야외 가정, 기획서 §4.6)
        let isIndoor = (workout.metadata?[HKMetadataKeyIndoorWorkout] as? Bool) ?? false
        // 날씨 — 워치가 야외 세션에 자동으로 붙인다. 추가 쿼리 없이 열 보정 재료가 된다 (제안 문서 A1)
        let tempC = (workout.metadata?[HKMetadataKeyWeatherTemperature] as? HKQuantity)?
            .doubleValue(for: .degreeCelsius())
        let humidity = (workout.metadata?[HKMetadataKeyWeatherHumidity] as? HKQuantity)
            .flatMap(Self.humidityPercent)
        return RunSummary(id: workout.uuid,
                          start: workout.startDate,
                          durationSec: workout.duration,
                          distanceMeters: distance,
                          avgHeartRate: bpm,
                          maxHeartRate: maxBpm,
                          calories: kcal,
                          isIndoor: isIndoor,
                          weatherTempC: tempC,
                          weatherHumidityPct: humidity)
    }

    /// 습도 메타데이터를 0~100(%)로 정규화 — 기록 주체에 따라 0~1(비율)로도,
    /// %×100으로도 관측된 사례가 있어 방어적으로 맞춘다. 그래도 벗어나면 nil
    /// (HeatEngine의 1...100 가드와 이중 방어).
    private static func humidityPercent(_ quantity: HKQuantity) -> Double? {
        let raw = quantity.doubleValue(for: .percent())
        if raw <= 1 { return raw * 100 }
        if raw <= 100 { return raw }
        if raw <= 10_000 { return raw / 100 }
        return nil
    }
}
