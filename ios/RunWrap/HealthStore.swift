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
    @Published private(set) var bodyMass: [(date: Date, kg: Double)] = DemoData.bodyMass
    @Published private(set) var vo2Max: [(date: Date, value: Double)] = DemoData.vo2Max
    @Published private(set) var crossTrainings: [CrossTraining] = DemoData.crossTrainings
    @Published private(set) var hrrTrend: [(date: Date, value: Double)] = DemoData.hrrTrend
    #else
    @Published private(set) var state: State = .idle
    /// 체력 배터리용 활력징후 — 러닝 목록과 별개로 실패해도 리포트는 뜬다
    @Published private(set) var vitals: VitalsSnapshot?
    /// 몸무게 추이(다이어트 카드)용 최근 8주 표본 — 주 단위 평균은 엔진이 계산한다
    @Published private(set) var bodyMass: [(date: Date, kg: Double)] = []
    /// 심폐 체력 카드용 최근 12주 VO₂max 표본 (ml/kg/min) — 주 단위 평균은 엔진이 계산한다
    @Published private(set) var vo2Max: [(date: Date, value: Double)] = []
    /// 최근 2주 비러닝 운동 — 주간 리포트 보조 문장 재료. ACWR에는 절대 섞지 않는다 (제안 문서 A3)
    @Published private(set) var crossTrainings: [CrossTraining] = []
    /// 심폐 체력 카드 보조 지표용 최근 12주 심박 회복(HRR) 표본 (bpm)
    @Published private(set) var hrrTrend: [(date: Date, value: Double)] = []
    #endif

    private let store = HKHealthStore()

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
            try await store.requestAuthorization(toShare: [], read: HealthPermissions.standard)
            await load()
        } catch {
            state = .failed(error.localizedDescription)
        }
        #endif
    }

    /// 다이어트 목표를 고른 사용자에게만 몸무게 읽기를 요청 — 기능별 권한 분리
    /// (HealthPermissions.diet). 목표 선택 직후(온보딩·설정)에서 부른다.
    func requestDietAccess() async {
        #if !targetEnvironment(simulator)
        guard HKHealthStore.isHealthDataAvailable() else { return }
        try? await store.requestAuthorization(toShare: [], read: HealthPermissions.diet)
        bodyMass = await fetchBodyMass()
        #endif
    }

    func load() async {
        #if targetEnvironment(simulator)
        state = .loaded(DemoData.runs)
        vitals = DemoData.vitals
        bodyMass = DemoData.bodyMass
        vo2Max = DemoData.vo2Max
        crossTrainings = DemoData.crossTrainings
        hrrTrend = DemoData.hrrTrend
        #else
        guard HKHealthStore.isHealthDataAvailable() else {
            state = .unavailable
            return
        }
        // 이미 목록이 떠 있으면 조용히 갱신 (.refreshable이 로딩 화면으로 튀지 않게)
        if case .loaded = state {} else { state = .loading }
        do {
            // 업데이트로 읽기 항목이 늘 수 있어 매번 요청 — 이미 응답한 항목은 시트가 뜨지 않는다
            try? await store.requestAuthorization(toShare: [], read: HealthPermissions.standard)
            let workouts = try await fetchRunningWorkouts(limit: 100)
            var summaries = workouts.map(Self.summary(of:))
            // 케이던스 백필 — 주법 추이(계획서 M4) 재료. 걸음 수는 워크아웃 통계에 없어
            // 워크아웃마다 쿼리해야 한다 — 추이 창인 최근 28일만 채워 쿼리 수를 줄인다.
            let cadenceCutoff = Date().addingTimeInterval(-28 * 86_400)
            for (index, workout) in workouts.enumerated() where workout.startDate >= cadenceCutoff {
                summaries[index].cadenceSpm = await cadenceSpm(of: workout)
            }
            state = .loaded(summaries)
            vitals = await fetchVitals()
            bodyMass = await fetchBodyMass()
            vo2Max = await fetchVo2Max()
            crossTrainings = await fetchCrossTrainings()
            hrrTrend = await fetchHrrTrend()
        } catch {
            state = .failed(error.localizedDescription)
        }
        #endif
    }

    // MARK: - 백그라운드 워크아웃 감지 (계획서 M8)

    /// 새 워크아웃 저장을 감지하는 옵저버 — 러닝 종료 직후 알림(보조 경로)의 재료.
    /// 등록 직후에도 한 번 불리므로 중복 알림 필터링은 호출부(onUpdate) 몫이다.
    private var workoutObserver: HKObserverQuery?

    /// 옵저버 등록 + 백그라운드 딜리버리 켜기 — 앱 기동마다 호출해도 안전 (재등록 가드).
    /// 시뮬레이터는 백그라운드 딜리버리를 지원하지 않아 no-op.
    func startObservingWorkouts(onUpdate: @escaping @Sendable () async -> Void) {
        #if !targetEnvironment(simulator)
        guard HKHealthStore.isHealthDataAvailable(), workoutObserver == nil else { return }
        let query = HKObserverQuery(sampleType: .workoutType(),
                                    predicate: HKQuery.predicateForWorkouts(with: .running)) { _, done, error in
            // 계약: 성패와 무관하게 completionHandler를 반드시 부른다 —
            // 3회 미호출이 쌓이면 background delivery 자체가 끊긴다
            Task {
                if error == nil { await onUpdate() }
                done()
            }
        }
        store.execute(query)
        workoutObserver = query
        // 실패해도 조용히 넘어간다 — 포그라운드 재계산이 1차 경로, 옵저버는 보조
        store.enableBackgroundDelivery(for: .workoutType(), frequency: .immediate) { _, _ in }
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
    /// 밤 구분: 잠든 구간의 종료 시각이 속한 날짜(기상일)로 묶고, 3시간 미만은 낮잠으로 보고 버린다.
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

        let calendar = Calendar.current
        let byNight = Dictionary(grouping: samples.filter { asleepValues.contains($0.value) }) {
            calendar.startOfDay(for: $0.endDate)
        }
        return byNight.compactMap { night, nightSamples -> VitalsSnapshot.SleepNight? in
            // 워치+아이폰 이중 기록 대비 구간 병합으로 총 수면을 센다 (lastNightSleepHours와 같은 이유)
            let intervals = nightSamples.map { (start: $0.startDate, end: $0.endDate) }
                .sorted { $0.start < $1.start }
            var merged: [(start: Date, end: Date)] = []
            for interval in intervals {
                if let last = merged.last, interval.start <= last.end {
                    if interval.end > last.end { merged[merged.count - 1].end = interval.end }
                } else {
                    merged.append(interval)
                }
            }
            let asleepHours = merged.reduce(0.0) { $0 + $1.end.timeIntervalSince($1.start) } / 3_600
            guard asleepHours >= 3 else { return nil }

            // 단계 비율은 단계를 기록한 소스(워치)의 표본만으로 계산 —
            // 아이폰의 asleepUnspecified가 분모에 섞이면 비율이 왜곡된다
            let stageSec = nightSamples.filter { stageValues.contains($0.value) }
                .reduce(0.0) { $0 + $1.endDate.timeIntervalSince($1.startDate) }
            let deepRemSec = nightSamples.filter { deepRemValues.contains($0.value) }
                .reduce(0.0) { $0 + $1.endDate.timeIntervalSince($1.startDate) }

            // 취침 시각: 정오(전날 12:00) 기준 경과 분 — 자정 넘김(23시=660, 새벽 1시=780)을
            // 연속값으로 다뤄 표준편차 계산이 깨지지 않게 한다
            let bedtime = merged.first.map {
                $0.start.timeIntervalSince(night.addingTimeInterval(-12 * 3_600)) / 60
            }
            return VitalsSnapshot.SleepNight(date: night,
                                             asleepHours: asleepHours,
                                             deepRemFraction: stageSec > 0 ? deepRemSec / stageSec : nil,
                                             bedtimeMinutes: bedtime)
        }
        .sorted { $0.date < $1.date }
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

    /// 최근 8주 몸무게 표본 — 다이어트 카드의 재료 (계획서 M2)
    private func fetchBodyMass(now: Date = .now) async -> [(date: Date, kg: Double)] {
        let samples = (try? await quantitySamples(HKQuantityType(.bodyMass),
                                                  from: now.addingTimeInterval(-56 * 86_400),
                                                  to: now)) ?? []
        return samples.map { (date: $0.startDate,
                              kg: $0.quantity.doubleValue(for: .gramUnit(with: .kilo))) }
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
