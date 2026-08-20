package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.Format
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/// 걷뛰(걷기-뛰기) 프로그램 엔진 (기획서 §2 무경험 온보딩) — 런린이 무경험자를 8주에 걸쳐
/// "쉬지 않고 25분 뛰기"까지 데려가는 주차별 처방. Couch to 5K 계열 프로그램의 관행적 사다리.
/// iOS `WalkRunEngine.swift` 이식.
object WalkRunEngine {
    /// 이번 주 걷뛰 처방 한 장 — 홈 탭 카드가 그대로 그린다
    data class Plan(
        val week: Int,             // 1부터 시작하는 주차 (8주 이후는 8주차 유지)
        val walkMinutes: Double,
        val runMinutes: Double,
        val sets: Int,
        val doneThisWeek: Int,     // 이번 ISO 주에 나간 횟수
        val weeklyGoal: Int,
    ) {
        val headline: String
            get() = "걷기 ${Format.walkRunMinutes(walkMinutes)}분 · 뛰기 ${Format.walkRunMinutes(runMinutes)}분 × ${sets}세트"

        val weekBadge: String get() = "걷뛰 ${week}주차"

        val progressLine: String get() = "이번 주 $doneThisWeek / ${weeklyGoal}회 했어요"

        val totalMinutes: Double get() = (walkMinutes + runMinutes) * sets
    }

    /// 8주 사다리 (걷기 분, 뛰기 분) — 세트당 5분 고정, 걷기를 줄이고 뛰기를 늘린다.
    /// 8주차(0, 5)는 "쉬지 않고 25분" — 이후로는 이 단계를 유지한다
    private val LADDER = listOf(
        4.0 to 1.0,
        3.0 to 2.0,
        2.0 to 3.0,
        2.0 to 3.0,
        1.5 to 3.5,
        1.0 to 4.0,
        0.5 to 4.5,
        0.0 to 5.0,
    )

    private const val SETS = 5

    /// 이번 주 처방 — 걷뛰 사이클이 시작 안 됐거나(cycleStartedAt == null) 주간 목표가 없으면 null
    fun plan(
        cycleStartedAt: Instant?,
        weeklyGoal: Int,
        runs: List<RunSummary>,
        now: Instant,
    ): Plan? {
        if (cycleStartedAt == null || weeklyGoal <= 0) return null

        val elapsedSec = secondsBetween(cycleStartedAt, now)
        // Swift Int() 대응 — 0 방향 절사. 시작 직후(0초)도 1주차
        val week = max(1, (elapsedSec / (7 * 86_400)).toInt() + 1)
        val (walk, run) = LADDER[min(week, LADDER.size) - 1]

        val weekStartInstant = weekStart(now).atStartOfDay(SEOUL).toInstant()
        val doneThisWeek = runs.count { it.start >= weekStartInstant && it.start <= now }

        return Plan(
            week = week,
            walkMinutes = walk,
            runMinutes = run,
            sets = SETS,
            doneThisWeek = doneThisWeek,
            weeklyGoal = weeklyGoal,
        )
    }
}
