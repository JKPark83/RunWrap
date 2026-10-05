package com.jkpark.runwrap.engine

import java.time.Instant

/// 레벨 판정 엔진 (기획서 §3) — Foundation만 import하는 순수 로직.
/// 설문 답으로 3단계(런린이/런잘알/런친놈)를 결정론적으로 판정하고,
/// 실제 러닝 기록에서 상위 레벨 승급 근거를 감지한다. UI를 모른다 — `RunnerLevel`까지만 결정한다.
object LevelEngine {
    /// 판정 결정표(기획서 §3) — 위에서부터 순서대로 평가, 동률(경계)은 낮은 쪽으로 배정한다.
    /// 상위 레벨 리포트를 하위 실력자에게 주면 이해 못 하는 처방·부하 지표 과신으로 다칠 수 있어
    /// 애매하면 하향한다(미노출 가드와 같은 철학).
    fun decide(a: OnboardingAnswers): RunnerLevel {
        // 1. 무경험이면 무조건 런린이
        if (a.q1Experience == OnboardingAnswers.Q1.novice) return RunnerLevel.beginner

        // 2. 풀코스 완주 AND 풀 기록 4:30 이내 AND 월 200km 이상 → 런친놈
        if (a.q2Longest == OnboardingAnswers.Q2Longest.fullFinisher &&
            a.q3Record == OnboardingAnswers.Q3Record.fullUnder430 &&
            a.q4Monthly == OnboardingAnswers.Q4Monthly.over200) {
            return RunnerLevel.advanced
        }

        // 3. 하프~풀 또는 풀코스 완주(2번 미충족) → 런잘알
        if (a.q2Longest == OnboardingAnswers.Q2Longest.halfToFull ||
            a.q2Longest == OnboardingAnswers.Q2Longest.fullFinisher) {
            return RunnerLevel.intermediate
        }

        // 4. 10km 기록이 1시간 이내 → 런잘알
        if (a.q3Record == OnboardingAnswers.Q3Record.tenUnder60) return RunnerLevel.intermediate

        // 5. 그 외 전부 → 런린이
        return RunnerLevel.beginner
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
    fun promotionCandidate(current: RunnerLevel, runs: List<RunSummary>, now: Instant): PromotionEvidence? {
        // 이미 최상위면 더 올릴 곳이 없다
        if (!(current.rank < RunnerLevel.advanced.rank)) return null

        val fourWeeksAgo = now.minusSeconds(28L * 86_400)
        // 페이스 가드(paceSecPerKm)를 통과한 기록만 — 0초·비현실 페이스 기록은 근거가 아니다 (이슈 #78)
        val recentRuns = runs
            .filter { it.start >= fourWeeksAgo && it.start <= now && it.paceSecPerKm != null }
            .sortedByDescending { it.start }

        // 런린이 → 런잘알: 가장 최근 근거 세션 하나. 10km 환산 조건을 먼저 본다(기획서 L2 서술 순서)
        if (current == RunnerLevel.beginner) {
            for (run in recentRuns) {
                val km = run.distanceKm ?: continue
                if (km >= 10 && run.durationSec / km * 10 <= 3_600) return PromotionEvidence.tenKmPace(run)
                if (km >= 21.0975) return PromotionEvidence.halfFinish(run)
            }
            return null
        }

        // 런잘알 → 런친놈: 월환산 마일리지 AND 풀 4:30 이내 — 하나라도 빠지면 후보 아님
        val totalKm = recentRuns.fold(0.0) { sum, run -> sum + (run.distanceKm ?: 0.0) }
        val monthlyKm = totalKm * 30 / 28
        if (!(monthlyKm >= 200)) return null
        val full = recentRuns.firstOrNull {
            (it.distanceKm ?: 0.0) >= 42.195 && it.durationSec <= 16_200  // 4h30m
        } ?: return null
        return PromotionEvidence.fullUnder430(full, monthlyKm = monthlyKm)
    }
}

/// 승급 근거 — 엔진이 판정과 함께 돌려줘 홈 승급 카드가 근거별 문장을 고른다 (기획서 §3).
sealed interface PromotionEvidence {
    /// 10km 이상 세션의 10km 환산 기록 1시간 이내 → 런잘알
    data class tenKmPace(val run: RunSummary) : PromotionEvidence
    /// 하프 이상 완주 → 런잘알
    data class halfFinish(val run: RunSummary) : PromotionEvidence
    /// 풀 4:30 이내 완주 + 최근 4주 월환산 200km 이상 → 런친놈
    data class fullUnder430(val run: RunSummary, val monthlyKm: Double) : PromotionEvidence

    /// 이 근거로 제안하는 레벨
    val target: RunnerLevel
        get() = when (this) {
            is tenKmPace, is halfFinish -> RunnerLevel.intermediate
            is fullUnder430 -> RunnerLevel.advanced
        }
}
