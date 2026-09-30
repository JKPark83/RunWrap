import Foundation

/// 레벨 판정 엔진 (기획서 §3) — Foundation만 import하는 순수 로직.
/// 설문 답으로 3단계(런린이/런잘알/런친놈)를 결정론적으로 판정하고,
/// 실제 러닝 기록에서 상위 레벨 승급 근거를 감지한다. UI를 모른다 — `RunnerLevel`까지만 결정한다.
enum LevelEngine {
    /// 판정 결정표(기획서 §3) — 위에서부터 순서대로 평가, 동률(경계)은 낮은 쪽으로 배정한다.
    /// 상위 레벨 리포트를 하위 실력자에게 주면 이해 못 하는 처방·부하 지표 과신으로 다칠 수 있어
    /// 애매하면 하향한다(미노출 가드와 같은 철학).
    static func decide(_ a: OnboardingAnswers) -> RunnerLevel {
        // 1. 무경험이면 무조건 런린이
        if a.q1Experience == .novice { return .beginner }

        // 2. 풀코스 완주 AND 풀 기록 4:30 이내 AND 월 200km 이상 → 런친놈
        if a.q2Longest == .fullFinisher,
           a.q3Record == .fullUnder430,
           a.q4Monthly == .over200 {
            return .advanced
        }

        // 3. 하프~풀 또는 풀코스 완주(2번 미충족) → 런잘알
        if a.q2Longest == .halfToFull || a.q2Longest == .fullFinisher {
            return .intermediate
        }

        // 4. 10km 기록이 1시간 이내 → 런잘알
        if a.q3Record == .tenUnder60 { return .intermediate }

        // 5. 그 외 전부 → 런린이
        return .beginner
    }

    /// 실데이터 승급 후보 판정 (기획서 §3 "실데이터 승급 제안").
    /// 승급만 하고 강등은 절대 없다 — "성장은 되돌리지 않는다".
    ///
    /// 판정 기준: 기획서 §3 레벨 표(L2·L3)와 승급 예시("10km 60분 이내 세션 발견, 또는 최근 4주
    /// 월환산 200km + 풀 4:30 이내 기록")를 실데이터에 대응시킨 근거 3종이다.
    /// - 런린이 → 런잘알: ① 10km 이상 세션의 10km 환산 기록 1시간 이내(L2 "10km 1시간 이내"),
    ///   또는 ② 하프(21.0975km) 이상 완주(L2 "하프 이상 완주").
    /// - 런잘알 → 런친놈: ③ 최근 28일 누적 km × 30/28(월환산) 200km 이상 **그리고**
    ///   풀(42.195km) 4시간 30분 이내 완주 — 둘 다 충족해야 한다(L3).
    /// 관측 창은 "최근 4주"(28일), 경계는 기획서 표현("이내"·"이상")대로 포함한다.
    /// 런린이가 런친놈 근거까지 갖춰도 한 단계(→ 런잘알)만 제안한다 — 애매하면 하향과 같은 철학.
    static func promotionCandidate(current: RunnerLevel, runs: [RunSummary], now: Date) -> PromotionEvidence? {
        // 이미 최상위면 더 올릴 곳이 없다
        guard current.rank < RunnerLevel.advanced.rank else { return nil }

        let fourWeeksAgo = now.addingTimeInterval(-28 * 86_400)
        // 페이스 가드(paceSecPerKm)를 통과한 기록만 — 0초·비현실 페이스 기록은 근거가 아니다 (이슈 #78)
        let recentRuns = runs
            .filter { $0.start >= fourWeeksAgo && $0.start <= now && $0.paceSecPerKm != nil }
            .sorted { $0.start > $1.start }

        // 런린이 → 런잘알: 가장 최근 근거 세션 하나. 10km 환산 조건을 먼저 본다(기획서 L2 서술 순서)
        if current == .beginner {
            for run in recentRuns {
                guard let km = run.distanceKm else { continue }
                if km >= 10, run.durationSec / km * 10 <= 3_600 { return .tenKmPace(run) }
                if km >= 21.0975 { return .halfFinish(run) }
            }
            return nil
        }

        // 런잘알 → 런친놈: 월환산 마일리지 AND 풀 4:30 이내 — 하나라도 빠지면 후보 아님
        let totalKm = recentRuns.reduce(0) { $0 + ($1.distanceKm ?? 0) }
        let monthlyKm = totalKm * 30 / 28
        guard monthlyKm >= 200 else { return nil }
        guard let full = recentRuns.first(where: {
            ($0.distanceKm ?? 0) >= 42.195 && $0.durationSec <= 16_200  // 4h30m
        }) else { return nil }
        return .fullUnder430(full, monthlyKm: monthlyKm)
    }
}

/// 승급 근거 — 엔진이 판정과 함께 돌려줘 홈 승급 카드가 근거별 문장을 고른다 (기획서 §3).
enum PromotionEvidence: Equatable {
    /// 10km 이상 세션의 10km 환산 기록 1시간 이내 → 런잘알
    case tenKmPace(RunSummary)
    /// 하프 이상 완주 → 런잘알
    case halfFinish(RunSummary)
    /// 풀 4:30 이내 완주 + 최근 4주 월환산 200km 이상 → 런친놈
    case fullUnder430(RunSummary, monthlyKm: Double)

    /// 이 근거로 제안하는 레벨
    var target: RunnerLevel {
        switch self {
        case .tenKmPace, .halfFinish: .intermediate
        case .fullUnder430: .advanced
        }
    }
}
