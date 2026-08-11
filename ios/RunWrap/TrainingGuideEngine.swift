import Foundation

/// 훈련 가이드 엔진 (기획서 §4.9, 계획서 M7) — 목표 레이스 진단과 주간 처방.
/// 순수 로직: Foundation만 쓰고 now·레벨·배터리 톤을 값으로 주입받아 결정론적이다.
///
/// 산식 출처:
/// - 완주 예측: Riegel 공식 T2 = T1 × (D2/D1)^1.06 (Riegel 1981).
///   T1은 최근 8주 안의 PR 중 예측이 가장 좋게 나오는 기록.
/// - 권장 주간 거리: 만성 부하(4주 주평균) × 1.0~1.1 — 10% 룰 상한.
/// - LSD 목표: 권장 주간의 25~35%. 체력 배터리가 overload/caution이면
///   25% 하한으로 고정한다 (기획서 §4.9).
/// - 강도 밸런스: (easy+LSD) : 스피드 세션 수를 80/20 원칙과 비교 (Seiler 80/20).
///
/// 가드는 ReportEngine.acwr와 동일 — 기록이 3주 미만이거나 만성 부하가 주 3km 미만이면
/// 진단·처방 전체를 내지 않는다(nil). 틀린 처방은 없느니만 못하다.

struct TrainingGuide: Equatable {
    /// 목표 레이스 완주 예측 — 최근 8주 안의 PR이 없으면 nil (처방만 노출)
    struct Prediction: Equatable {
        let race: RaceDistance
        let predictedSec: Double
        let baseLabel: String        // 근거 PR 종목 ("5K")
        let baseTimeSec: Double      // 근거 PR 기록(초)
        let goalSec: Double?         // 목표 기록 미입력이면 nil
        let tone: RRTone             // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
    }

    struct Prescription: Equatable {
        let weeklyKmLow: Double      // 만성 부하 그대로 (×1.0)
        let weeklyKmHigh: Double     // ×1.1 — 10% 룰 상한
        let lsdKmLow: Double
        let lsdKmHigh: Double
        let speedSessionsMax: Int    // 숙련 2회 / 초보 1회
        let batteryLimited: Bool     // 배터리 하향 보정이 적용됐는지
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
    let prescription: Prescription
    let balance: Balance?
}

struct TrainingGuideEngine {
    var now = Date()
    /// 스피드 세션 상한만 바꾼다 — 문장 난이도는 화면 몫
    var level: RunLevel = .experienced

    // MARK: - 진단 (Riegel)

    /// Riegel 예측 — 최근 8주(56일) 안의 PR마다 T1×(D2/D1)^1.06을 계산해 최솟값을 고른다
    static func predictedTime(for goal: RaceDistance, records: [PersonalRecords.Entry],
                              now: Date) -> Double? {
        bestPrediction(for: goal, records: records, now: now)?.sec
    }

    private static func bestPrediction(for goal: RaceDistance, records: [PersonalRecords.Entry],
                                       now: Date) -> (entry: PersonalRecords.Entry, sec: Double)? {
        let cutoff = now.addingTimeInterval(-56 * 86_400)
        return records.filter { $0.date >= cutoff }
            .map { (entry: $0, sec: $0.timeSec * pow(goal.km / $0.distanceKm, 1.06)) }
            .min { $0.sec < $1.sec }
    }

    // MARK: - 가이드 전체

    func guide(runs: [RunSummary], records: [PersonalRecords.Entry],
               race: RaceDistance, goalSec: Double?, batteryTone: RRTone?) -> TrainingGuide? {
        // 가드: ReportEngine.acwr와 동일 기준
        guard let oldest = runs.map(\.start).min(),
              oldest <= date(daysAgo: 21) else { return nil }
        let chronic = totalKm(runs, fromDaysAgo: 28, toDaysAgo: 0) / 4
        guard chronic >= 3 else { return nil }

        let limited = batteryTone == .overload || batteryTone == .caution
        let weeklyLow = chronic
        let weeklyHigh = chronic * 1.1
        let prescription = TrainingGuide.Prescription(
            weeklyKmLow: weeklyLow,
            weeklyKmHigh: weeklyHigh,
            lsdKmLow: weeklyLow * 0.25,
            lsdKmHigh: limited ? weeklyLow * 0.25 : weeklyHigh * 0.35,
            speedSessionsMax: level == .beginner ? 1 : 2,
            batteryLimited: limited)

        let prediction = Self.bestPrediction(for: race, records: records, now: now).map { best in
            TrainingGuide.Prediction(race: race,
                                     predictedSec: best.sec,
                                     baseLabel: best.entry.label,
                                     baseTimeSec: best.entry.timeSec,
                                     goalSec: goalSec,
                                     tone: Self.predictionTone(predicted: best.sec, goal: goalSec))
        }

        return TrainingGuide(prediction: prediction,
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
}
