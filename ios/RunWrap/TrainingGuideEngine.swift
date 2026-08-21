import Foundation

/// 훈련 가이드 엔진 v2 (기획서 §4.9) — 목표 레이스 진단, 훈련 페이스 존, 주간 처방, 오늘의 훈련.
/// 순수 로직: Foundation만 쓰고 now·레벨·배터리 톤을 값으로 주입받아 결정론적이다.
///
/// 산식 출처:
/// - 완주 예측: Riegel 공식 T2 = T1 × (D2/D1)^1.06 (Riegel 1981).
///   T1은 유효 표본 중 예측이 가장 좋게 나오는 세션이다. 표본은 공인 거리 PR이 아니라
///   **실제 러닝 세션**에서 뽑는다 — 6~9km처럼 공인 거리 사이에 걸린 세션만 뛰는 러너가
///   예측을 통째로 못 받던 문제 때문이다 (이슈 #24). 창은 1주 우선·없을 때만 4주→8주.
/// - 현재 기력·훈련 페이스: Daniels & Gilbert(1979) "Oxygen Power"의 VDOT 공식.
///   같은 세션에서 VDOT를 역산하고 %VDOT 구간으로 페이스 존을 정한다
///   (이지 62~74% / 템포 88% / 인터벌 97.5% — Daniels' Running Formula의 존 정의).
///   훈련 페이스는 목표 기록이 아니라 **현재 실력** 기준이다 — 목표가 현재보다 빨라도
///   페이스 존은 올라가지 않고, 갭은 진단(예상 vs 목표)으로만 보여준다 (부상 방지).
/// - 권장 주간 거리: 만성 부하(4주 주평균) × 1.0~1.1 — 10% 룰 상한.
///   종목·레벨별 피크 주간 거리에 닿으면 더 올리지 않는다 (가정 — 일반 훈련 플랜 관례).
/// - 주기화: 대회 날짜가 있으면 남은 주 수로 기초→강화→피크→테이퍼→대회 주간을 가르고
///   테이퍼 구간은 볼륨을 내린다 (가정 — 일반 플랜 관례: 테이퍼 60~70%, 대회 주간 40~50%).
/// - LSD 목표: 권장 주간의 25~35%. 체력 배터리가 overload/caution이면 25% 하한으로
///   고정하고 인터벌을 끈다 (기획서 §4.9 + v2 확장).
/// - 강도 밸런스: (easy+LSD) : 스피드 세션 수를 80/20 원칙과 비교 (Seiler 80/20).
///
/// 가드는 ReportEngine.acwr와 동일 — 기록이 3주 미만이거나 만성 부하가 주 3km 미만이면
/// 진단·처방 전체를 내지 않는다(nil). 예측·페이스 존은 유효 표본(5km 이상, 외삽 3배 이내)이
/// 8주 안에 하나도 없으면 따로 내지 않는다 — 틀린 페이스는 없느니만 못하다.

struct TrainingGuide: Equatable {
    /// 목표 레이스 완주 예측 — 유효 표본이 없으면 nil (처방만 노출)
    struct Prediction: Equatable {
        let race: RaceDistance
        let predictedSec: Double
        let baseLabel: String        // 근거 세션 거리 ("7.4km")
        let baseTimeSec: Double      // 근거 세션 기록(초)
        /// 표본을 찾은 창(일) — 7·28·56. 1주(7)가 아니면 화면이 "최근 4주 기준"처럼 밝힌다
        let baseWindowDays: Int
        let goalSec: Double?         // 목표 기록 미입력이면 nil
        let tone: RRTone             // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
    }

    /// 훈련 페이스 존 — 예측과 같은 세션에서 역산한 VDOT 기준 (Daniels & Gilbert 1979).
    /// 페이스는 모두 초/km. 존 상수는 VDOT 50(5K 19:57)에서 Daniels 표와 대조해 검증했다
    /// (이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″).
    struct PaceZones: Equatable {
        let vdot: Double
        /// 이지·LSD 페이스 구간 — 빠른 끝(74%)…느린 끝(62%)
        let easySecPerKm: ClosedRange<Double>
        let tempoSecPerKm: Double        // 템포(역치)런 — 88%
        let intervalSecPerKm: Double     // 인터벌 — 97.5% (vVDOT 부근)
        let goalSecPerKm: Double?        // 목표 레이스 페이스 — 미입력·비현실적(가드 참고)이면 nil
    }

    /// 주기화 단계 — 대회 날짜 기준 남은 주 수로 가른다 (가정 — 일반 플랜 관례)
    enum Phase: Equatable {
        case base       // 기초기 — 볼륨 쌓기
        case build      // 강화기 — 퀄리티 도입
        case peak       // 피크 — 최대 볼륨·강도
        case taper      // 테이퍼 — 볼륨 감량, 강도 유지
        case raceWeek   // 대회 주간 — 가볍게만

        var label: String {
            switch self {
            case .base: "기초기"
            case .build: "강화기"
            case .peak: "피크"
            case .taper: "테이퍼"
            case .raceWeek: "대회 주간"
            }
        }
    }

    struct Prescription: Equatable {
        let weeklyKmLow: Double      // 만성 부하 그대로 (×1.0) — 테이퍼 구간은 감량 하한
        let weeklyKmHigh: Double     // ×1.1 (10% 룰 상한), 피크 주간 거리에서 멈춘다
        let lsdKmLow: Double
        let lsdKmHigh: Double        // 대회 주간은 0 — 롱런 없이 간다
        let tempoCount: Int          // 이번 주 템포런 권장 횟수
        let intervalCount: Int       // 이번 주 인터벌 권장 횟수
        let phase: Phase?            // 대회 날짜 미설정·지난 날짜면 nil
        let daysToRace: Int?         // D-day (대회 당일 = 0)
        let peakWeeklyKm: Double     // 이 종목·레벨의 권장 피크 주간 거리 (참고 표시용)
        let batteryLimited: Bool     // 배터리 하향 보정이 적용됐는지

        var qualityCount: Int { tempoCount + intervalCount }
    }

    /// 최근 7일 세션의 강도 밸런스 — 이번 주 세션이 없으면 nil
    struct Balance: Equatable {
        let easyCount: Int
        let lsdCount: Int
        let speedCount: Int
        let speedSharePct: Double    // 세션 수 기준 스피드 비중 (가정 — 시간 아닌 횟수)
        let tone: RRTone             // 스피드 20% 초과면 caution, 그 외 steady
    }

    /// 세션 강도 분류 (가정 — 계획서 M7): LSD = 주간 최장 && 주간 총거리의 35% 이상 /
    /// 스피드 = 4주 평균 페이스보다 10% 이상 빠름 / 나머지 easy. LSD 판정이 스피드보다 우선.
    enum SessionKind: Equatable { case easy, lsd, speed }

    let prediction: Prediction?
    let zones: PaceZones?
    let prescription: Prescription
    let balance: Balance?
}

/// 오늘의 훈련 추천 — 형태·거리·페이스. 이력·배터리로 정하는 동적 추천이라
/// 요일에 묶이지 않는다. 화면은 이 값을 문장으로 그린다 (라벨은 Kind.label).
struct TodayWorkout: Equatable {
    enum Kind: Equatable {
        case rest                            // 배터리 방전 임박 — 오늘의 처방은 쉬는 것
        case doneCount                       // 주간 목표 횟수 달성
        case doneKm                          // 주간 목표 거리 달성
        case easy
        case lsd
        case tempo
        case interval(reps: Int, meters: Int)

        /// 화면 공통 라벨 — 홈 카드·리포트 카드가 같은 이름을 쓴다
        var label: String {
            switch self {
            case .rest: "휴식"
            case .doneCount, .doneKm: "완료"
            case .easy: "이지런"
            case .lsd: "LSD"
            case .tempo: "템포런"
            case .interval(let reps, let meters): "인터벌 \(reps)×\(meters)m"
            }
        }
    }

    /// 왜 이 훈련인가 — 화면이 캡션 문장을 고르는 근거
    enum Reason: Equatable {
        case battery        // 배터리 주의 → 가볍게
        case hardRecently   // 어제·오늘 고강도/롱런 → 회복
        case lsdDue         // 이번 주 롱런 미완 — 남은 기회가 적다
        case qualityDue     // 이번 주 퀄리티 세션 잔여
        case fill           // 잔여 거리 소화
        case none           // rest·done 계열
    }

    let kind: Kind
    let reason: Reason
    let distanceKm: Double?                    // rest·done이면 nil. 인터벌은 본훈련 합계
    let paceSecPerKm: ClosedRange<Double>?     // 페이스 존이 없으면(유효 예측 표본 없음) nil
}

struct TrainingGuideEngine {
    var now = Date()
    /// 퀄리티 세션 구성과 인터벌 스펙을 바꾼다 — 문장 난이도는 화면 몫.
    /// 기본값을 런잘알(intermediate)로 두는 이유는 ReportEngine.level과 같다 (§4 표준 톤).
    var level: RunnerLevel = .intermediate

    // MARK: - 진단 (Riegel)

    /// 예측 입력 한 건 — Riegel·VDOT의 재료는 거리와 시간뿐이라 공인 거리일 필요가 없다.
    /// PersonalRecords.Entry가 아니라 실제 세션에서 직접 뽑는 이유는 `predictionSamples` 참고.
    struct PredictionSample: Equatable {
        let distanceKm: Double
        let timeSec: Double
        let date: Date
        /// 근거 표기용 라벨 — 공인 거리가 아니므로 "7.4km"처럼 실제 거리로 적는다.
        /// 예전에는 "5K" 같은 공인 종목명이었다 (이슈 #24로 입력이 임의 거리가 되며 바뀜)
        var label: String { Format.km(distanceKm) + "km" }
    }

    /// 예측 입력의 최소 거리(km). 이보다 짧은 세션은 페이스 변동성이 커 외삽 기반이 못 된다
    /// (이슈 #24 — 정확도 우선으로 보수적으로 잡았다: "틀린 인사이트는 없느니만 못하다").
    static let minSampleKm = 5.0

    /// Riegel 외삽 배율 상한. 원 논문(Riegel 1981)의 신뢰 구간이 약 1/3~3배이고
    /// 그 밖은 오차가 급격히 커진다 — 5km 세션으로 풀코스(8.4배)를 추정하지 않는다.
    static let maxExtrapolationRatio = 3.0

    /// 표본 창 — 최근 1주를 우선하고, 없을 때만 4주 → 8주로 넓힌다 (이슈 #24).
    /// 창을 넘나들며 비교하지 않는 것이 핵심이다: 1주 안에 세션이 있으면 8주 전 레이스급
    /// 기록이 더 빨라도 쓰지 않는다. "최근 실력"이 "최고 기록"을 이긴다는 뜻이고,
    /// 이지런만 한 주에 예측이 느려지는 것은 버그가 아니라 의도된 동작이다.
    static let sampleWindowDays: [Int] = [7, 28, 56]

    /// 표본 창 라벨 — "최근 1주"·"최근 4주"·"최근 8주". 화면이 예측 근거 시점을 밝히는 데 쓴다.
    /// 엔진이 문자열을 내는 예외인 이유는 창 상수(sampleWindowDays)와 한 곳에 두기 위해서다
    static func sampleWindowLabel(days: Int) -> String {
        "최근 \(max(1, days / 7))주"
    }

    /// Riegel 예측 — 유효 표본마다 T1×(D2/D1)^1.06을 계산해 최솟값을 고른다
    static func predictedTime(for goal: RaceDistance, runs: [RunSummary],
                              now: Date) -> Double? {
        bestPrediction(for: goal, runs: runs, now: now)?.sec
    }

    /// 세션에서 예측 후보를 뽑는다 — 거리·페이스가 있고 최소 거리·외삽 배율 가드를 통과한 것만.
    /// 실내(트레드밀)도 거리·시간이 있으면 쓴다: 워치 추정 거리라 오차가 있지만
    /// 배제하면 겨울철 사용자가 예측을 통째로 잃는다.
    static func predictionSamples(for goal: RaceDistance,
                                  runs: [RunSummary]) -> [PredictionSample] {
        runs.compactMap { run -> PredictionSample? in
            guard let km = run.distanceKm, run.paceSecPerKm != nil,
                  km >= minSampleKm,
                  goal.km / km <= maxExtrapolationRatio else { return nil }
            return PredictionSample(distanceKm: km, timeSec: run.durationSec, date: run.start)
        }
    }

    /// 가장 좁은 창부터 훑어 후보가 있는 첫 창에서 고른다 — 창 간 비교는 하지 않는다.
    /// 반환값의 window는 화면이 "최근 1주 기준"처럼 표본 시점을 밝히는 데 쓴다.
    static func bestPrediction(for goal: RaceDistance, runs: [RunSummary], now: Date)
        -> (sample: PredictionSample, sec: Double, windowDays: Int)? {
        let samples = predictionSamples(for: goal, runs: runs)
        for days in sampleWindowDays {
            let cutoff = now.addingTimeInterval(-Double(days) * 86_400)
            let best = samples.filter { $0.date >= cutoff }
                .map { (sample: $0, sec: $0.timeSec * pow(goal.km / $0.distanceKm, 1.06)) }
                .min { $0.sec < $1.sec }
            if let best { return (best.sample, best.sec, days) }
        }
        return nil
    }

    // MARK: - 현재 기력 (Daniels VDOT)

    /// Daniels & Gilbert(1979) — 달리기 속도 v(m/분)의 산소 소비량 추정
    private static func vo2(atVelocity v: Double) -> Double {
        -4.60 + 0.182_258 * v + 0.000_104 * v * v
    }

    /// 지속 시간 t(분) 동안 유지 가능한 %VO₂max (Daniels & Gilbert 1979)
    private static func sustainableFraction(minutes t: Double) -> Double {
        0.8 + 0.189_439_3 * exp(-0.012_778 * t) + 0.298_955_8 * exp(-0.193_260_5 * t)
    }

    /// PR 하나에서 VDOT를 역산한다. 공식 유효 구간을 벗어난 이상치는 버린다(nil)
    static func vdot(distanceKm: Double, timeSec: Double) -> Double? {
        guard distanceKm > 0, timeSec > 0 else { return nil }
        let minutes = timeSec / 60
        let velocity = distanceKm * 1_000 / minutes
        let value = vo2(atVelocity: velocity) / sustainableFraction(minutes: minutes)
        // 러너 실측 범위 밖이면 입력이 이상하다 (가정 — Daniels 표의 수록 구간 30~85)
        return (20...90).contains(value) ? value : nil
    }

    /// %VDOT 강도의 순항 페이스(초/km) — vo2 이차식을 속도에 대해 역산한다
    private static func pace(atFraction fraction: Double, vdot: Double) -> Double {
        let target = fraction * vdot
        let a = 0.000_104, b = 0.182_258, c = -(4.60 + target)
        let velocity = (-b + (b * b - 4 * a * c).squareRoot()) / (2 * a)   // m/분
        return 60_000 / velocity
    }

    /// 5000m 세계기록(12:35.36, 첩테게이 2020) 페이스가 약 2′31″/km — 이보다 빠른 목표
    /// 페이스는 사람 기록이 아니라 입력 실수다 (예: 종목을 풀로 바꿨는데 목표 기록이 30:00으로 남음)
    static let minGoalPaceSecPerKm = 150.0

    /// 존 상수 (Daniels' Running Formula): 이지 62~74% / 템포 88% / 인터벌 97.5%.
    /// VDOT 50에서 Daniels 표와 대조: 이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″ 일치.
    private static func zones(sample: PredictionSample, goalSec: Double?,
                              raceKm: Double) -> TrainingGuide.PaceZones? {
        guard let vdot = vdot(distanceKm: sample.distanceKm, timeSec: sample.timeSec) else {
            return nil
        }
        let easy = pace(atFraction: 0.74, vdot: vdot)...pace(atFraction: 0.62, vdot: vdot)
        // 목표 페이스 가드 — 이지 존 느린 끝보다 느리면 훈련 정보가 없고(조깅으로도 달성),
        // 세계기록보다 빠르면 사람 기록이 아니다. 둘 다 종목·목표 기록이 어긋난 입력 실수라
        // 페이스를 내지 않는다(nil). "틀린 인사이트는 없느니만 못하다."
        let goalPace = goalSec.map { $0 / raceKm }
            .flatMap { (minGoalPaceSecPerKm...easy.upperBound).contains($0) ? $0 : nil }
        return TrainingGuide.PaceZones(
            vdot: vdot,
            easySecPerKm: easy,
            tempoSecPerKm: pace(atFraction: 0.88, vdot: vdot),
            intervalSecPerKm: pace(atFraction: 0.975, vdot: vdot),
            goalSecPerKm: goalPace)
    }

    // MARK: - 주기화 (대회 날짜)

    /// 테이퍼 주 수 (대회 주간 제외) — 가정: 풀 2주, 하프 1주, 5K/10K는 대회 주간만
    private static func taperWeeks(for race: RaceDistance) -> Int {
        switch race {
        case .full: 2
        case .half: 1
        case .fiveK, .tenK: 0
        }
    }

    /// 남은 주 수로 단계를 가른다 — 피크 3주, 강화 4주, 그 앞은 전부 기초 (가정)
    static func phase(daysToRace: Int, race: RaceDistance) -> TrainingGuide.Phase? {
        guard daysToRace >= 0 else { return nil }
        let weeks = daysToRace / 7
        let taper = taperWeeks(for: race)
        switch weeks {
        case 0: return .raceWeek
        case ...taper: return .taper
        case ...(taper + 3): return .peak
        case ...(taper + 7): return .build
        default: return .base
        }
    }

    /// 종목·레벨별 권장 피크 주간 거리(km) — 가정: 일반 입문·중급·상급 플랜 관례 수준.
    /// 10% 룰 점증이 여기 닿으면 더 올리지 않는다 ("이 목표에 이 이상은 필요 없다").
    static func peakWeeklyKm(race: RaceDistance, level: RunnerLevel) -> Double {
        switch (race, level) {
        case (.fiveK, .beginner): 20
        case (.fiveK, .intermediate): 30
        case (.fiveK, .advanced): 45
        case (.tenK, .beginner): 25
        case (.tenK, .intermediate): 40
        case (.tenK, .advanced): 55
        case (.half, .beginner): 35
        case (.half, .intermediate): 50
        case (.half, .advanced): 70
        case (.full, .beginner): 45
        case (.full, .intermediate): 65
        case (.full, .advanced): 90
        }
    }

    /// 단계·레벨별 퀄리티 세션 구성 (템포, 인터벌) — 가정: 80/20 안에서 주 1~2회.
    /// 날짜 미설정(nil)은 강화기 수준으로 본다. 배터리 하향 주간은 인터벌을 끈다.
    static func qualityMix(phase: TrainingGuide.Phase?, level: RunnerLevel,
                           batteryLimited: Bool) -> (tempo: Int, interval: Int) {
        let mix: (tempo: Int, interval: Int) = switch phase {
        case .raceWeek: (0, 0)
        case .taper, .base: level == .beginner ? (0, 0) : (1, 0)
        case .build: level == .advanced ? (1, 1) : (1, 0)
        case .peak, nil: level == .beginner ? (1, 0) : (1, 1)
        }
        return batteryLimited ? (mix.tempo, 0) : mix
    }

    // MARK: - 가이드 전체

    func guide(runs: [RunSummary],
               race: RaceDistance, goalSec: Double?, raceDate: Date? = nil,
               batteryTone: RRTone?) -> TrainingGuide? {
        // 가드: ReportEngine.acwr와 동일 기준
        guard let oldest = runs.map(\.start).min(),
              oldest <= date(daysAgo: 21) else { return nil }
        let chronic = totalKm(runs, fromDaysAgo: 28, toDaysAgo: 0) / 4
        guard chronic >= 3 else { return nil }

        let limited = batteryTone == .overload || batteryTone == .caution
        let daysToRace = raceDate.map { Self.days(from: now, to: $0) }
        let phase = daysToRace.flatMap { Self.phase(daysToRace: $0, race: race) }
        let peak = Self.peakWeeklyKm(race: race, level: level)

        // 볼륨 — 평상시엔 10% 룰 점증(피크 거리에서 정지), 테이퍼 구간은 감량
        let (weeklyLow, weeklyHigh): (Double, Double)
        switch phase {
        case .taper:
            let built = min(chronic, peak)
            (weeklyLow, weeklyHigh) = (built * 0.6, built * 0.7)
        case .raceWeek:
            let built = min(chronic, peak)
            (weeklyLow, weeklyHigh) = (built * 0.4, built * 0.5)
        default:
            (weeklyLow, weeklyHigh) = (chronic, max(chronic, min(chronic * 1.1, peak)))
        }

        let quality = Self.qualityMix(phase: phase, level: level, batteryLimited: limited)
        let prescription = TrainingGuide.Prescription(
            weeklyKmLow: weeklyLow,
            weeklyKmHigh: weeklyHigh,
            lsdKmLow: phase == .raceWeek ? 0 : weeklyLow * 0.25,
            lsdKmHigh: phase == .raceWeek ? 0
                     : (limited ? weeklyLow * 0.25 : weeklyHigh * 0.35),
            tempoCount: quality.tempo,
            intervalCount: quality.interval,
            phase: phase,
            daysToRace: (daysToRace ?? -1) >= 0 ? daysToRace : nil,
            peakWeeklyKm: peak,
            batteryLimited: limited)

        let best = Self.bestPrediction(for: race, runs: runs, now: now)
        let prediction = best.map { best in
            TrainingGuide.Prediction(race: race,
                                     predictedSec: best.sec,
                                     baseLabel: best.sample.label,
                                     baseTimeSec: best.sample.timeSec,
                                     baseWindowDays: best.windowDays,
                                     goalSec: goalSec,
                                     tone: Self.predictionTone(predicted: best.sec, goal: goalSec))
        }

        return TrainingGuide(prediction: prediction,
                             zones: best.flatMap {
                                 Self.zones(sample: $0.sample, goalSec: goalSec, raceKm: race.km)
                             },
                             prescription: prescription,
                             balance: balance(runs: runs))
    }

    /// 목표 대비 판정 — 목표 미입력이면 중립(steady)
    private static func predictionTone(predicted: Double, goal: Double?) -> RRTone {
        guard let goal else { return .steady }
        if predicted <= goal { return .improving }
        if predicted <= goal * 1.05 { return .steady }
        return .caution
    }

    // MARK: - 오늘의 훈련

    /// 오늘 권장 거리의 상한 계수 — 최근 4주 최장 거리의 +10%까지만 (10% 룰, Gabbett 2016).
    /// 주 후반에 잔여량이 몰려 "오늘 12km" 같은 값이 나오는 걸 막는 가드다.
    static let longRunCapFactor = 1.1
    /// 잔여량이 이보다 적으면 거리를 내지 않는다 — "오늘 0.4km"는 처방이 아니라 잡음이다
    static let minPrescribedKm = 1.0

    /// 이번 주(ISO 8601, 월요일 시작) 이력·배터리로 "오늘 뭘 뛸지" 하나를 고른다.
    ///
    /// 판정 순서 (위가 우선):
    /// 1. 배터리 방전 임박 → 휴식
    /// 2. 주간 횟수/거리를 다 채움 → 완료
    /// 3. 배터리 주의 → 이지 (강한 세션은 회복 뒤로)
    /// 4. 어제·오늘 고강도/롱런 → 이지 (하드-이지 원칙)
    /// 5. 롱런 미완 && 남은 날 ≤ 2 또는 남은 횟수 1 → LSD (마지막 기회)
    /// 6. 퀄리티 세션 잔여 → 템포 먼저, 다음 인터벌 (페이스 존 없으면 건너뛴다 —
    ///    페이스 없는 인터벌 처방은 잡음이다)
    /// 7. 그 외 → 이지 (잔여량을 남은 횟수로 분배, 롱런 몫은 남겨 둔다)
    func todayWorkout(runs: [RunSummary], guide: TrainingGuide,
                      batteryTone: RRTone?, weeklyGoal: Int) -> TodayWorkout {
        let p = guide.prescription
        if batteryTone == .overload {
            return TodayWorkout(kind: .rest, reason: .none, distanceKm: nil, paceSecPerKm: nil)
        }

        let week = Self.weekRuns(runs, now: now)
        let weekKm = week.compactMap(\.distanceKm).reduce(0, +)
        let remainSessions = min(weeklyGoal - week.count, Self.daysLeftInWeek(now))
        guard remainSessions > 0 else {
            return TodayWorkout(kind: .doneCount, reason: .none, distanceKm: nil, paceSecPerKm: nil)
        }
        // 기준 주간량은 처방 구간의 중앙값 — 배터리 하향 주간은 하한으로 (TodayVerdict v1과 동일)
        let weeklyBase = p.batteryLimited ? p.weeklyKmLow : (p.weeklyKmLow + p.weeklyKmHigh) / 2
        let remainKm = weeklyBase - weekKm
        guard remainKm >= Self.minPrescribedKm else {
            return TodayWorkout(kind: .doneKm, reason: .none, distanceKm: nil, paceSecPerKm: nil)
        }

        let capKm = Self.longestKm(runs, fromDaysAgo: 28, now: now)
            .map { $0 * Self.longRunCapFactor } ?? .greatestFiniteMagnitude
        let easyPace = guide.zones?.easySecPerKm
        func easy(_ reason: TodayWorkout.Reason, km: Double) -> TodayWorkout {
            TodayWorkout(kind: .easy, reason: reason,
                         distanceKm: min(km, capKm), paceSecPerKm: easyPace)
        }
        let splitKm = remainKm / Double(remainSessions)

        if batteryTone == .caution { return easy(.battery, km: splitKm) }

        // 하드-이지 원칙: 어제 0시 이후에 스피드(4주 평균보다 10%+ 빠름) 또는
        // 롱런(LSD 하한 이상)을 뛰었으면 오늘은 회복이다
        let paces = runs.filter { $0.start >= date(daysAgo: 28) }.compactMap(\.paceSecPerKm)
        let avgPace = paces.isEmpty ? nil : paces.reduce(0, +) / Double(paces.count)
        let hardSince = Self.startOfYesterday(now)
        let ranHardRecently = runs.contains { run in
            guard run.start >= hardSince else { return false }
            if let pace = run.paceSecPerKm, let avg = avgPace, pace <= avg * 0.9 { return true }
            return p.lsdKmLow >= 1 && (run.distanceKm ?? 0) >= p.lsdKmLow
        }
        if ranHardRecently { return easy(.hardRecently, km: splitKm) }

        // LSD — 이번 주 아직이고 남은 기회가 적으면 지금이 적기다
        let lsdDone = p.lsdKmLow >= 1 && week.contains { ($0.distanceKm ?? 0) >= p.lsdKmLow }
        let lsdMid = (p.lsdKmLow + p.lsdKmHigh) / 2
        if p.lsdKmHigh >= 1, !lsdDone,
           Self.daysLeftInWeek(now) <= 2 || remainSessions == 1 {
            return TodayWorkout(kind: .lsd, reason: .lsdDue,
                                distanceKm: min(lsdMid, capKm), paceSecPerKm: easyPace)
        }

        // 퀄리티 — 이번 주 스피드로 분류된 세션 수가 처방보다 적으면 차례다
        if let zones = guide.zones {
            let speedDone = week.filter { run in
                guard let pace = run.paceSecPerKm, let avg = avgPace else { return false }
                return pace <= avg * 0.9
            }.count
            if speedDone < p.tempoCount {
                // 템포 20분 — Daniels T 워크아웃 관례(20~40분)의 하한을 쓴다
                let tempoKm = (1_200 / zones.tempoSecPerKm * 10).rounded() / 10
                return TodayWorkout(kind: .tempo, reason: .qualityDue,
                                    distanceKm: tempoKm,
                                    paceSecPerKm: zones.tempoSecPerKm...zones.tempoSecPerKm)
            }
            if speedDone < p.qualityCount {
                let spec = Self.intervalSpec(level: level)
                return TodayWorkout(kind: .interval(reps: spec.reps, meters: spec.meters),
                                    reason: .qualityDue,
                                    distanceKm: Double(spec.reps * spec.meters) / 1_000,
                                    paceSecPerKm: zones.intervalSecPerKm...zones.intervalSecPerKm)
            }
        }

        // 이지 — 잔여량 분배. 롱런이 남았으면 그 몫은 빼고 나눈다
        if !lsdDone, p.lsdKmHigh >= 1, remainSessions > 1 {
            let nonLsdKm = remainKm - lsdMid
            if nonLsdKm >= Self.minPrescribedKm {
                return easy(.fill, km: nonLsdKm / Double(remainSessions - 1))
            }
        }
        return easy(.fill, km: splitKm)
    }

    /// 레벨별 인터벌 스펙 — 가정: 일반 관례 수준 (본훈련 총 1.6~5km, I 페이스)
    static func intervalSpec(level: RunnerLevel) -> (reps: Int, meters: Int) {
        switch level {
        case .beginner: (4, 400)
        case .intermediate: (5, 800)
        case .advanced: (5, 1_000)
        }
    }

    // MARK: - 세션 분류·밸런스

    /// 최근 7일 세션을 분류한다 — 입력 순서 그대로 반환. 최장 거리가 동률이면 모두 LSD 후보.
    static func classify(week: [RunSummary], avg4wPaceSec: Double?) -> [TrainingGuide.SessionKind] {
        let totalKm = week.compactMap(\.distanceKm).reduce(0, +)
        let longest = week.compactMap(\.distanceKm).max() ?? 0
        return week.map { run in
            let km = run.distanceKm ?? 0
            if km > 0, km == longest, km >= totalKm * 0.35 { return .lsd }
            if let pace = run.paceSecPerKm, let avg = avg4wPaceSec, pace <= avg * 0.9 {
                return .speed
            }
            return .easy
        }
    }

    private func balance(runs: [RunSummary]) -> TrainingGuide.Balance? {
        let week = runs.filter { $0.start >= date(daysAgo: 7) }
        guard !week.isEmpty else { return nil }
        let paces = runs.filter { $0.start >= date(daysAgo: 28) }.compactMap(\.paceSecPerKm)
        let avgPace = paces.isEmpty ? nil : paces.reduce(0, +) / Double(paces.count)
        let kinds = Self.classify(week: week, avg4wPaceSec: avgPace)
        let speed = kinds.filter { $0 == .speed }.count
        let lsd = kinds.filter { $0 == .lsd }.count
        let share = Double(speed) / Double(kinds.count) * 100
        return TrainingGuide.Balance(easyCount: kinds.count - speed - lsd,
                                     lsdCount: lsd,
                                     speedCount: speed,
                                     speedSharePct: share,
                                     tone: share > 20 ? .caution : .steady)
    }

    // MARK: - 공통 (ReportEngine과 같은 창 정의)

    private func date(daysAgo: Int) -> Date {
        now.addingTimeInterval(TimeInterval(-daysAgo * 86_400))
    }

    /// [now-fromDaysAgo, now-toDaysAgo) 창에 시작된 러닝의 거리 합 (km)
    private func totalKm(_ runs: [RunSummary], fromDaysAgo: Int, toDaysAgo: Int) -> Double {
        let from = date(daysAgo: fromDaysAgo)
        let to = date(daysAgo: toDaysAgo)
        return runs.filter { $0.start >= from && $0.start < to }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }

    // MARK: - 주간 창 (ISO 8601, 월요일 시작 — 홈 목표 칩·TodayVerdict와 같은 정의)

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        return calendar
    }

    private static func weekRuns(_ runs: [RunSummary], now: Date) -> [RunSummary] {
        guard let week = calendar.dateInterval(of: .weekOfYear, for: now) else { return [] }
        return runs.filter { $0.start >= week.start && $0.start <= now }
    }

    /// 오늘을 포함한 이번 주 남은 날 수 (목요일이면 목·금·토·일 = 4)
    private static func daysLeftInWeek(_ now: Date) -> Int {
        guard let week = calendar.dateInterval(of: .weekOfYear, for: now) else { return 1 }
        return calendar.dateComponents([.day],
                                       from: calendar.startOfDay(for: now),
                                       to: week.end).day ?? 1
    }

    private static func startOfYesterday(_ now: Date) -> Date {
        calendar.date(byAdding: .day, value: -1, to: calendar.startOfDay(for: now))
            ?? now.addingTimeInterval(-86_400)
    }

    /// 최근 N일 안의 최장 거리 — 상한 가드의 기준. 거리 표본이 없으면 nil(상한 없음)
    private static func longestKm(_ runs: [RunSummary], fromDaysAgo days: Int,
                                  now: Date) -> Double? {
        let from = now.addingTimeInterval(TimeInterval(-days * 86_400))
        return runs.filter { $0.start >= from && $0.start <= now }
            .compactMap(\.distanceKm)
            .max()
    }

    /// 날짜 차이(일) — 자정 경계 기준이라 시각과 무관하다 (대회 당일 = 0)
    private static func days(from: Date, to: Date) -> Int {
        calendar.dateComponents([.day],
                                from: calendar.startOfDay(for: from),
                                to: calendar.startOfDay(for: to)).day ?? -1
    }
}
