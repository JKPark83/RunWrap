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
    /// 판정 기준: 기획서에는 "10km 60분 이내 세션 발견" 예시(시안 1h 문구 "지난주 10km를
    /// 59분에 달리셨더라고요")만 구체적으로 명시돼 있다. "최근 4주"라는 관측 기간과
    /// "10km 이상" 거리 조건은 기획서에 정확한 수치가 없어, §3 Q3(10km 1시간 이내 → 런잘알)
    /// 판정 기준을 실데이터에 대응시켜 이 엔진에서 정했다 — 튜닝 여지가 있다.
    static func promotionCandidate(current: RunnerLevel, runs: [RunSummary], now: Date) -> RunnerLevel? {
        // 이미 최상위면 더 올릴 곳이 없다
        guard current.rank < RunnerLevel.advanced.rank else { return nil }

        let fourWeeksAgo = now.addingTimeInterval(-28 * 86_400)
        let recentRuns = runs.filter { $0.start >= fourWeeksAgo && $0.start <= now }

        // 런린이 → 런잘알: 최근 4주 내 10km 이상을 60분 이내에 달린 기록이 있으면 후보
        if current == .beginner {
            let qualifies = recentRuns.contains { run in
                guard let km = run.distanceKm, km >= 10 else { return false }
                return run.durationSec <= 3_600
            }
            return qualifies ? .intermediate : nil
        }

        return nil
    }
}
