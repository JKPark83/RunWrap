package com.jkpark.runwrap.engine

import java.time.Instant

/// 레벨 판정 엔진 (기획서 §3) — 순수 로직. iOS `LevelEngine.swift` 이식.
/// 설문 답으로 3단계(런린이/런잘알/런친놈)를 결정론적으로 판정하고,
/// 실제 러닝 기록에서 상위 레벨 승급 근거를 감지한다. UI를 모른다 — `RunnerLevel`까지만 결정한다.
object LevelEngine {
    /// 판정 결정표(기획서 §3) — 위에서부터 순서대로 평가, 동률(경계)은 낮은 쪽으로 배정한다.
    /// 상위 레벨 리포트를 하위 실력자에게 주면 이해 못 하는 처방·부하 지표 과신으로 다칠 수 있어
    /// 애매하면 하향한다(미노출 가드와 같은 철학).
    fun decide(a: OnboardingAnswers): RunnerLevel {
        // 1. 무경험이면 무조건 런린이
        if (a.q1Experience == OnboardingAnswers.Q1.NOVICE) return RunnerLevel.BEGINNER

        // 2. 풀코스 완주 AND 풀 기록 4:30 이내 AND 월 200km 이상 → 런친놈
        if (a.q2Longest == OnboardingAnswers.Q2Longest.FULL_FINISHER &&
            a.q3Record == OnboardingAnswers.Q3Record.FULL_UNDER_430 &&
            a.q4Monthly == OnboardingAnswers.Q4Monthly.OVER_200
        ) {
            return RunnerLevel.ADVANCED
        }

        // 3. 하프~풀 또는 풀코스 완주(2번 미충족) → 런잘알
        if (a.q2Longest == OnboardingAnswers.Q2Longest.HALF_TO_FULL ||
            a.q2Longest == OnboardingAnswers.Q2Longest.FULL_FINISHER
        ) {
            return RunnerLevel.INTERMEDIATE
        }

        // 4. 10km 기록이 1시간 이내 → 런잘알
        if (a.q3Record == OnboardingAnswers.Q3Record.TEN_UNDER_60) return RunnerLevel.INTERMEDIATE

        // 5. 그 외 전부 → 런린이
        return RunnerLevel.BEGINNER
    }

    /// 실데이터 승급 후보 판정 (기획서 §3 "실데이터 승급 제안").
    /// 승급만 하고 강등은 절대 없다 — "성장은 되돌리지 않는다".
    ///
    /// 판정 기준: 기획서에는 "10km 60분 이내 세션 발견" 예시(시안 1h 문구 "지난주 10km를
    /// 59분에 달리셨더라고요")만 구체적으로 명시돼 있다. "최근 4주"라는 관측 기간과
    /// "10km 이상" 거리 조건은 기획서에 정확한 수치가 없어, §3 Q3(10km 1시간 이내 → 런잘알)
    /// 판정 기준을 실데이터에 대응시켜 이 엔진에서 정했다 — 튜닝 여지가 있다.
    fun promotionCandidate(current: RunnerLevel, runs: List<RunSummary>, now: Instant): RunnerLevel? {
        // 이미 최상위면 더 올릴 곳이 없다
        if (current.rank >= RunnerLevel.ADVANCED.rank) return null

        val fourWeeksAgo = now.minusSeconds(28 * 86_400L)
        val recentRuns = runs.filter { it.start >= fourWeeksAgo && it.start <= now }

        // 런린이 → 런잘알: 최근 4주 내 10km 이상을 60분 이내에 달린 기록이 있으면 후보
        if (current == RunnerLevel.BEGINNER) {
            val qualifies = recentRuns.any { run ->
                (run.distanceKm ?: 0.0) >= 10 && run.durationSec <= 3_600
            }
            return if (qualifies) RunnerLevel.INTERMEDIATE else null
        }

        return null
    }
}
