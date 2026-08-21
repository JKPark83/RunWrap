import Foundation
import Testing
@testable import RunWrap

/// 훈련 가이드 엔진 검증 — Riegel 예측, VDOT 페이스 존, 주기화, 오늘의 훈련,
/// 세션 분류, 배터리 하향 보정, 표본 가드.
/// now = 2026-08-10T09:00:00Z = KST 2026-08-10(월) 18:00 고정 —
/// 이번 주(ISO 8601) 창은 08-10 00:00 ~ 08-17 00:00 KST, 남은 날은 7일이다.
struct TrainingGuideEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!
    var engine: TrainingGuideEngine { TrainingGuideEngine(now: now) }

    private func run(daysAgo: Double, km: Double, paceSecPerKm: Double = 360) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: paceSecPerKm * km, distanceMeters: km * 1_000,
                   avgHeartRate: 150)
    }

    /// 28일 동안 10km × 12회 = 총 120km → chronic(4주 주평균) = 30km.
    /// daysAgo 1~26.3 — 창(28일) 안이고 최고령이 21일을 넘어 가드를 통과한다.
    private var baseRuns: [RunSummary] {
        (0..<12).map { run(daysAgo: Double($0) * 2.3 + 1, km: 10) }
    }

    /// 공인 거리 사이의 사각지대 회귀 표본. 7km × 8회가 8주에 걸쳐 있어
    /// 훈련 가이드 전체 가드(3주 이상·주 3km 이상)도 통과한다.
    private var nonStandardRuns: [RunSummary] {
        (0..<8).map { record(km: 7.0, timeSec: 2_520, daysAgo: Double($0) * 7 + 2) }
    }

    /// 예측 표본이 될 세션 하나 — 거리·기록을 직접 지정한다 (이슈 #24로 입력이 세션이 됐다)
    private func record(km: Double, timeSec: Double, daysAgo: Double) -> RunSummary {
        run(daysAgo: daysAgo, km: km, paceSecPerKm: timeSec / km)
    }

    @Test("Riegel 예측 — 5K 25:00 세션이면 10K는 52:07로 예측한다")
    func riegelPrediction() throws {
        // T2 = 1500 × (10/5)^1.06 = 1500 × 2.0849 ≈ 3127초 = 52:07
        let runs = [record(km: 5.0, timeSec: 1_500, daysAgo: 3)]
        let predicted = try #require(TrainingGuideEngine.predictedTime(
            for: .tenK, runs: runs, now: now))
        #expect(abs(predicted - 3_127) < 1)
    }

    @Test("Riegel 표본 창 — 8주(56일)가 지난 세션으로는 예측하지 않는다")
    func riegelWindow() {
        let runs = [record(km: 5.0, timeSec: 1_500, daysAgo: 57)]
        #expect(TrainingGuideEngine.predictedTime(for: .tenK, runs: runs, now: now) == nil)
    }

    // MARK: - 표본 적격성·창 (이슈 #24)

    @Test("공인 거리 사각지대 — 6~9km 세션만 있어도 예측이 나온다 (이슈 #24 회귀)")
    func predictsFromNonStandardDistances() throws {
        // 6~9km는 5K 창(≤5.5)과 10K 창(≥10) 어디에도 안 걸려 PR이 0개였던 구간이다.
        // 7km를 42분(360초/km)에 → 10K = 2520 × (10/7)^1.06 ≈ 3678초
        let predicted = try #require(TrainingGuideEngine.predictedTime(
            for: .tenK, runs: nonStandardRuns, now: now))
        #expect(abs(predicted - 3_678) < 1)
    }

    @Test("공인 거리 사각지대 — 같은 표본에서 훈련 페이스 존도 함께 나온다 (이슈 #24 회귀)")
    func zonesFromNonStandardDistances() throws {
        // 예측과 존은 같은 7km-only bestPrediction에서 나오므로 한쪽만 나오는 일이 없어야 한다
        let guide = try #require(engine.guide(runs: nonStandardRuns, race: .tenK,
                                              goalSec: nil, batteryTone: nil))
        let prediction = try #require(guide.prediction)
        #expect(guide.zones != nil)
        #expect(prediction.baseLabel == "7.0km")
        #expect(prediction.baseTimeSec == 2_520)
    }

    @Test("표본 최소 거리 — 5km 미만 세션은 예측에 쓰지 않는다")
    func rejectsShortSamples() {
        // 4.9km는 하한(5.0) 미달 — 페이스 변동성이 커 외삽 기반이 못 된다
        let short = [record(km: 4.9, timeSec: 1_470, daysAgo: 2)]
        #expect(TrainingGuideEngine.predictedTime(for: .tenK, runs: short, now: now) == nil)
        // 5.0km는 경계 안쪽이라 통과한다
        let exact = [record(km: 5.0, timeSec: 1_500, daysAgo: 2)]
        #expect(TrainingGuideEngine.predictedTime(for: .tenK, runs: exact, now: now) != nil)
    }

    @Test("외삽 배율 상한 — 목표가 세션 거리의 3배를 넘으면 예측하지 않는다")
    func rejectsExcessiveExtrapolation() {
        // 5km → 풀(42.195km)은 8.4배 — Riegel 신뢰 구간(약 1/3~3배) 밖이라 제외한다
        let runs = [record(km: 5.0, timeSec: 1_500, daysAgo: 2)]
        #expect(TrainingGuideEngine.predictedTime(for: .full, runs: runs, now: now) == nil)
        // 14.1km면 42.195/14.1 = 2.99배로 상한 안쪽 — 예측이 나온다
        let long = [record(km: 14.1, timeSec: 5_076, daysAgo: 2)]
        #expect(TrainingGuideEngine.predictedTime(for: .full, runs: long, now: now) != nil)
    }

    @Test("표본 창 우선순위 — 1주 안에 세션이 있으면 더 빠른 8주 전 기록을 쓰지 않는다")
    func recentWindowWinsOverFasterOldSample() throws {
        // 3일 전 느린 세션(10km 60분)과 40일 전 빠른 세션(10km 45분)이 함께 있는 상황.
        // 창 간 비교를 하지 않으므로 1주 창의 느린 쪽이 이긴다 — 의도된 동작(이슈 #24)
        let runs = [record(km: 10.0, timeSec: 3_600, daysAgo: 3),
                    record(km: 10.0, timeSec: 2_700, daysAgo: 40)]
        let best = try #require(TrainingGuideEngine.bestPrediction(for: .tenK, runs: runs,
                                                                   now: now))
        #expect(best.windowDays == 7)
        #expect(abs(best.sec - 3_600) < 1)
    }

    @Test("표본 창 폴백 — 1주에 세션이 없으면 4주 → 8주로 넓힌다")
    func fallsBackToWiderWindows() throws {
        // 20일 전 세션뿐 — 1주 창은 비었고 4주 창에서 잡힌다
        let fourWeek = try #require(TrainingGuideEngine.bestPrediction(
            for: .tenK, runs: [record(km: 10.0, timeSec: 3_600, daysAgo: 20)], now: now))
        #expect(fourWeek.windowDays == 28)

        // 40일 전 세션뿐 — 4주도 비어 8주 창까지 넓어진다
        let eightWeek = try #require(TrainingGuideEngine.bestPrediction(
            for: .tenK, runs: [record(km: 10.0, timeSec: 3_600, daysAgo: 40)], now: now))
        #expect(eightWeek.windowDays == 56)
    }

    @Test("근거 표기 — 공인 종목명이 아니라 실제 세션 거리로 적는다")
    func baseLabelUsesActualDistance() throws {
        let runs = (0..<8).map { record(km: 7.4, timeSec: 2_664, daysAgo: Double($0) * 3 + 1) }
        let guide = try #require(engine.guide(runs: runs, race: .tenK,
                                              goalSec: nil, batteryTone: nil))
        let prediction = try #require(guide.prediction)
        #expect(prediction.baseLabel == "7.4km")
        #expect(prediction.baseWindowDays == 7)
    }

    @Test("세션 분류 — 주간 최장은 LSD, 4주 평균보다 10% 빠르면 스피드, 나머지 easy")
    func sessionClassification() {
        // 주간 총 28km: 최장 14km ≥ 28×0.35 = 9.8 → LSD.
        // 4주 평균 페이스 360 기준: 320 ≤ 324(= 360×0.9) → 스피드, 365는 easy.
        let week = [run(daysAgo: 1, km: 14, paceSecPerKm: 360),
                    run(daysAgo: 3, km: 6, paceSecPerKm: 320),
                    run(daysAgo: 5, km: 8, paceSecPerKm: 365)]
        #expect(TrainingGuideEngine.classify(week: week, avg4wPaceSec: 360)
            == [.lsd, .speed, .easy])
    }

    @Test("배터리 하향 보정 — overload/caution이면 LSD 상한을 25% 하한으로 내린다")
    func batteryLowersLSD() throws {
        // chronic 30 → 주간 30~33km, LSD 정상 7.5(30×0.25)~11.55(33×0.35)
        let normal = try #require(engine.guide(runs: baseRuns, race: .tenK,
                                               goalSec: nil, batteryTone: .steady))
        #expect(abs(normal.prescription.weeklyKmLow - 30) < 0.01)
        #expect(abs(normal.prescription.weeklyKmHigh - 33) < 0.01)
        #expect(abs(normal.prescription.lsdKmLow - 7.5) < 0.01)
        #expect(abs(normal.prescription.lsdKmHigh - 11.55) < 0.01)
        #expect(normal.prescription.batteryLimited == false)

        // caution이면 상한도 30×0.25 = 7.5로 고정된다
        let limited = try #require(engine.guide(runs: baseRuns, race: .tenK,
                                                goalSec: nil, batteryTone: .caution))
        #expect(abs(limited.prescription.lsdKmHigh - 7.5) < 0.01)
        #expect(limited.prescription.batteryLimited)
    }

    @Test("표본 부족 가드 — 기록 3주 미만이거나 만성 부하가 주 3km 미만이면 침묵(nil)")
    func insufficientGuard() {
        // 기록이 11일치뿐 — 최고령이 21일보다 최근이라 가드에 걸린다
        let young = (0..<6).map { run(daysAgo: Double($0) * 2 + 1, km: 10) }
        #expect(engine.guide(runs: young, race: .tenK,
                             goalSec: nil, batteryTone: nil) == nil)

        // 3주는 넘지만 4주 총 8km → chronic 2km/주 < 3
        let tiny = [run(daysAgo: 25, km: 4), run(daysAgo: 10, km: 4)]
        #expect(engine.guide(runs: tiny, race: .tenK,
                             goalSec: nil, batteryTone: nil) == nil)
    }

    @Test("목표 대비 판정 — 예측이 목표보다 빠르면 improving, 5% 넘게 느리면 caution")
    func predictionTone() throws {
        // 이번 주 5K 25:00 세션이 최속 → 예측 3127초 (baseRuns 10km 60분보다 빠르다)
        let runs = baseRuns + [record(km: 5.0, timeSec: 1_500, daysAgo: 3)]
        // 목표 3200(53:20) → 달성권 improving
        let ok = try #require(engine.guide(runs: runs, race: .tenK,
                                           goalSec: 3_200, batteryTone: nil))
        #expect(ok.prediction?.tone == .improving)
        // 목표 2900(48:20) → 3127 > 2900×1.05 = 3045 → caution
        let gap = try #require(engine.guide(runs: runs, race: .tenK,
                                            goalSec: 2_900, batteryTone: nil))
        #expect(gap.prediction?.tone == .caution)
    }

    // MARK: - VDOT 페이스 존

    @Test("VDOT 역산 — 5K 19:57이면 VDOT ≈ 50 (Daniels 표 대조)")
    func vdotFromRecord() throws {
        let vdot = try #require(TrainingGuideEngine.vdot(distanceKm: 5, timeSec: 1_197))
        #expect(abs(vdot - 50) < 0.5)
    }

    @Test("페이스 존 — VDOT 50이면 이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″ (Daniels 표)")
    func paceZones() throws {
        // 5K 19:57 → VDOT ≈ 50. 존 상수(62~74/88/97.5%)를 Daniels 표 페이스와 대조한다.
        // baseRuns(10km 60분)보다 빨라 이 세션이 표본으로 뽑힌다
        let runs = baseRuns + [record(km: 5.0, timeSec: 1_197, daysAgo: 3)]
        let guide = try #require(engine.guide(runs: runs, race: .tenK,
                                              goalSec: nil, batteryTone: nil))
        let zones = try #require(guide.zones)
        #expect(abs(zones.easySecPerKm.lowerBound - 294) < 3)   // 4′54″ (74%)
        #expect(abs(zones.easySecPerKm.upperBound - 338) < 3)   // 5′38″ (62%)
        #expect(abs(zones.tempoSecPerKm - 255) < 3)             // 4′15″ (88%)
        #expect(abs(zones.intervalSecPerKm - 235) < 3)          // 3′55″ (97.5%)
    }

    @Test("페이스 존 가드 — 유효 표본(5km 이상)이 없으면 예측·존을 내지 않는다 (처방만 노출)")
    func zonesRequireRecentRecord() throws {
        // 4km × 24회 = 4주 96km → chronic 24km/주로 처방 가드는 통과하지만,
        // 모든 세션이 최소 거리(5km) 미만이라 예측 표본이 하나도 없다
        let shortOnly = (0..<24).map { run(daysAgo: Double($0) + 1, km: 4) }
        let guide = try #require(engine.guide(runs: shortOnly, race: .tenK,
                                              goalSec: nil, batteryTone: nil))
        #expect(guide.zones == nil)
        #expect(guide.prediction == nil)
        #expect(guide.prescription.weeklyKmLow > 0)   // 처방은 그대로 나온다
    }

    @Test("목표 페이스 가드 — 이지 존보다 느리거나 세계기록보다 빠르면 내지 않는다")
    func goalPaceGuard() throws {
        // VDOT 50 → 이지 느린 끝 ≈ 338초/km. 종목·목표 기록이 어긋난 입력 실수 시나리오.
        // 풀 목표까지 재는 테스트라 외삽 상한(3배)에 걸리지 않게 15km 세션을 쓴다
        // (42.195/15 = 2.81배). 15km 63:34는 VDOT 50 — 5K 19:57과 같은 기력이다
        let runs = baseRuns + [record(km: 15.0, timeSec: 3_814, daysAgo: 3)]
        func goalPace(race: RaceDistance, goalSec: Double) throws -> Double? {
            try #require(engine.guide(runs: runs, race: race,
                                      goalSec: goalSec, batteryTone: nil)).zones?.goalSecPerKm
        }
        // 10K 45:00 → 270초/km — 이지(338)보다 빠르고 세계기록(150)보다 느려 정상 노출
        #expect(try goalPace(race: .tenK, goalSec: 2_700) == 270)
        // 10K 3:00:00 → 1,080초/km — 이지 존보다 느리다 (하프·풀 목표가 10K에 남은 실수)
        #expect(try goalPace(race: .tenK, goalSec: 10_800) == nil)
        // 풀 30:00 → 42.7초/km — 5000m 세계기록 페이스(≈151초/km)보다 빠르다
        #expect(try goalPace(race: .full, goalSec: 1_800) == nil)
    }

    // MARK: - 주기화 (대회 날짜)

    @Test("주기화 단계 — 남은 주 수로 기초→강화→피크→테이퍼→대회 주간을 가른다")
    func phaseBoundaries() {
        // 풀코스(테이퍼 2주): 10주 기초 / 9주 강화 / 5주 피크 / 2주 테이퍼 / 0주 대회 주간
        #expect(TrainingGuideEngine.phase(daysToRace: 70, race: .full) == .base)
        #expect(TrainingGuideEngine.phase(daysToRace: 63, race: .full) == .build)
        #expect(TrainingGuideEngine.phase(daysToRace: 35, race: .full) == .peak)
        #expect(TrainingGuideEngine.phase(daysToRace: 14, race: .full) == .taper)
        #expect(TrainingGuideEngine.phase(daysToRace: 3, race: .full) == .raceWeek)
        // 지난 날짜는 단계 없음, 10K는 테이퍼 없이 1주 전이 이미 피크
        #expect(TrainingGuideEngine.phase(daysToRace: -1, race: .full) == nil)
        #expect(TrainingGuideEngine.phase(daysToRace: 7, race: .tenK) == .peak)
    }

    @Test("주기화 볼륨 — 테이퍼는 60~70%, 대회 주간은 40~50%로 감량하고 LSD·퀄리티를 끈다")
    func periodizedVolume() throws {
        // chronic 30 → 테이퍼(풀코스 D-10): 30×0.6~0.7 = 18~21km
        let taper = try #require(engine.guide(
            runs: baseRuns, race: .full, goalSec: nil,
            raceDate: now.addingTimeInterval(10 * 86_400), batteryTone: nil))
        #expect(taper.prescription.phase == .taper)
        #expect(taper.prescription.daysToRace == 10)
        #expect(abs(taper.prescription.weeklyKmLow - 18) < 0.01)
        #expect(abs(taper.prescription.weeklyKmHigh - 21) < 0.01)

        // 대회 주간(D-3): 30×0.4~0.5 = 12~15km, LSD 없음, 퀄리티 0회
        let raceWeek = try #require(engine.guide(
            runs: baseRuns, race: .full, goalSec: nil,
            raceDate: now.addingTimeInterval(3 * 86_400), batteryTone: nil))
        #expect(raceWeek.prescription.phase == .raceWeek)
        #expect(abs(raceWeek.prescription.weeklyKmLow - 12) < 0.01)
        #expect(abs(raceWeek.prescription.weeklyKmHigh - 15) < 0.01)
        #expect(raceWeek.prescription.lsdKmHigh == 0)
        #expect(raceWeek.prescription.qualityCount == 0)
    }

    @Test("피크 상한 — 10% 룰 점증이 종목·레벨 피크 거리에 닿으면 더 올리지 않는다")
    func peakWeeklyKmCap() throws {
        // chronic 30, 5K 중급 피크 30 → 상한이 33(×1.1)이 아니라 30에서 멈춘다
        let guide = try #require(engine.guide(runs: baseRuns, race: .fiveK,
                                              goalSec: nil, batteryTone: nil))
        #expect(abs(guide.prescription.weeklyKmHigh - 30) < 0.01)
    }

    @Test("퀄리티 구성 — 레벨·단계별 템포/인터벌 횟수, 배터리 하향이면 인터벌 제외")
    func qualityMix() {
        // 날짜 미설정(nil)은 피크 수준: 초보 (1,0), 중급 (1,1)
        #expect(TrainingGuideEngine.qualityMix(phase: nil, level: .beginner,
                                               batteryLimited: false) == (1, 0))
        #expect(TrainingGuideEngine.qualityMix(phase: nil, level: .intermediate,
                                               batteryLimited: false) == (1, 1))
        // 기초기는 템포만, 대회 주간은 전부 끈다
        #expect(TrainingGuideEngine.qualityMix(phase: .base, level: .intermediate,
                                               batteryLimited: false) == (1, 0))
        #expect(TrainingGuideEngine.qualityMix(phase: .raceWeek, level: .advanced,
                                               batteryLimited: false) == (0, 0))
        // 배터리 하향 주간은 인터벌을 빼고 템포만 남긴다
        #expect(TrainingGuideEngine.qualityMix(phase: nil, level: .advanced,
                                               batteryLimited: true) == (1, 0))
    }

    // MARK: - 오늘의 훈련

    @Test("오늘의 훈련 — 어제 롱런(LSD 하한 이상)을 뛰었으면 오늘은 회복 이지런")
    func todayAfterHardDay() throws {
        // baseRuns의 최신 러닝 = daysAgo 1(어제 저녁) 10km ≥ LSD 하한 7.5 → 하드-이지 원칙
        let guide = try #require(engine.guide(runs: baseRuns, race: .tenK,
                                              goalSec: nil, batteryTone: .steady))
        let today = engine.todayWorkout(runs: baseRuns, guide: guide,
                                        batteryTone: .steady, weeklyGoal: 4)
        #expect(today.kind == .easy)
        #expect(today.reason == .hardRecently)
    }

    @Test("오늘의 훈련 — 남은 횟수가 1회면 미완의 롱런(LSD)부터 처방한다")
    func todayLsdDue() throws {
        // 이번 주 0회, 최근 러닝은 그제(토) — 주 1회 목표라 오늘이 롱런의 마지막 기회
        let runs = (0..<12).map { run(daysAgo: Double($0) * 2.2 + 2, km: 10) }
        let guide = try #require(engine.guide(runs: runs, race: .tenK,
                                              goalSec: nil, batteryTone: .steady))
        let today = engine.todayWorkout(runs: runs, guide: guide,
                                        batteryTone: .steady, weeklyGoal: 1)
        #expect(today.kind == .lsd)
        #expect(today.reason == .lsdDue)
        // LSD 구간 7.5~11.55의 중앙값 9.525km — 상한 11km(10×1.1)에 걸리지 않는다
        let km = try #require(today.distanceKm)
        #expect(abs(km - 9.525) < 0.01)
    }

    @Test("오늘의 훈련 — 퀄리티 잔여면 템포런 20분 분량을 T 페이스로 처방한다")
    func todayTempo() throws {
        // 이번 주 스피드 0회 < 템포 처방 1회 → 템포 차례. T ≈ 255초 × 20분 → 4.7km.
        // 표본은 6일 전 5km 19:57(VDOT 50) — 1주 창(이슈 #24 우선 창) 안이라 같은 창의
        // 10km 60:00 세션들을 제치고 예측·존 표본이 된다. now가 월요일이라 6일 전은
        // 지난주(화)라서 이번 주 세션 분류에는 들어가지 않는다
        let runs = (0..<12).map { run(daysAgo: Double($0) * 2.2 + 2, km: 10) }
            + [record(km: 5.0, timeSec: 1_197, daysAgo: 6)]
        let guide = try #require(engine.guide(runs: runs, race: .tenK,
                                              goalSec: nil, batteryTone: .steady))
        let today = engine.todayWorkout(runs: runs, guide: guide,
                                        batteryTone: .steady, weeklyGoal: 4)
        #expect(today.kind == .tempo)
        #expect(today.reason == .qualityDue)
        let km = try #require(today.distanceKm)
        #expect(abs(km - 4.7) < 0.1)
        #expect(today.paceSecPerKm?.lowerBound == today.paceSecPerKm?.upperBound)
    }

    @Test("오늘의 훈련 — 이번 주 스피드 1회를 이미 했으면 인터벌 차례다 (중급 5×800m)")
    func todayInterval() throws {
        // 목요일 저녁 기준: 화요일에 빠른 6km(310 ≤ 4주 평균 355.8×0.9)를 이미 뛰었다.
        // 하드-이지 창(어제 0시~)은 지났고, 남은 날 4일 > 2라 LSD 규칙도 건너뛴다
        let now2 = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!
        let engine2 = TrainingGuideEngine(now: now2)
        func run2(daysAgo: Double, km: Double, paceSecPerKm: Double = 360) -> RunSummary {
            RunSummary(id: UUID(), start: now2.addingTimeInterval(-daysAgo * 86_400),
                       durationSec: paceSecPerKm * km, distanceMeters: km * 1_000,
                       avgHeartRate: 150)
        }
        var runs = (0..<11).map { run2(daysAgo: Double($0) * 2.1 + 4, km: 10) }
        runs.append(run2(daysAgo: 2, km: 6, paceSecPerKm: 310))
        // 예측·존 표본 — 7일 전 5km 19:57(VDOT 50). 지지난 주라 이번 주 분류엔 안 들어간다
        runs.append(run2(daysAgo: 7, km: 5, paceSecPerKm: 239.4))
        let guide = try #require(engine2.guide(runs: runs, race: .tenK,
                                               goalSec: nil, batteryTone: .steady))
        let today = engine2.todayWorkout(runs: runs, guide: guide,
                                         batteryTone: .steady, weeklyGoal: 4)
        #expect(today.kind == .interval(reps: 5, meters: 800))
        #expect(today.reason == .qualityDue)
        #expect(today.distanceKm == 4.0)   // 5 × 800m 본훈련 합계
    }
}
