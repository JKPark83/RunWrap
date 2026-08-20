package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/// 러닝 외 운동 세션 하나 — Health Connect의 다른 운동 타입에서 매핑한다 (스토어 몫)
data class CrossTraining(
    val start: Instant,
    val durationSec: Double,
    val kind: Kind,
    val kcal: Double? = null,
) {
    enum class Kind {
        CYCLING, STRENGTH, SWIMMING, HIKING, WALKING, YOGA, HIIT, OTHER;

        val label: String
            get() = when (this) {
                CYCLING -> "자전거"
                STRENGTH -> "근력"
                SWIMMING -> "수영"
                HIKING -> "등산"
                WALKING -> "걷기"
                YOGA -> "요가"
                HIIT -> "인터벌 트레이닝"
                OTHER -> "기타 운동"
            }
    }
}

/// 크로스 트레이닝 주간 요약 엔진 — 러닝 외 운동을 종목별로 묶어 한 문장으로 정리한다
/// (기획 문서 "HealthKit 미활용 데이터 활용 제안" — 러닝 부하와 별개인 전신 피로 알림).
/// iOS `CrossTrainingEngine.swift` 이식.
///
/// 미노출 가드: 세션이 없거나 주간 합산 20분 미만이면 null — 한 줄 요약할 거리도 안 된다.
/// 걷기는 30분 미만이면 일상 보행으로 보고 집계에서 뺀다.
object CrossTrainingEngine {
    data class Item(val label: String, val minutes: Int, val count: Int)

    data class Summary(
        val sessionCount: Int,
        val totalMinutes: Int,
        val breakdown: List<Item>,  // 종목별 시간, 많은 순
        val headline: String,
        val detail: String,
    )

    private const val MIN_WALKING_SEC = 30 * 60.0
    private const val MIN_TOTAL_MINUTES = 20

    fun weekly(cross: List<CrossTraining>, runs: List<RunSummary>, now: Instant): Summary? {
        val weekAgo = now.minusSeconds(7 * 86_400L)
        val weekSessions = cross
            .filter { it.start >= weekAgo && it.start <= now }
            .filterNot { it.kind == CrossTraining.Kind.WALKING && it.durationSec < MIN_WALKING_SEC }
        if (weekSessions.isEmpty()) return null

        val totalMinutes = (weekSessions.sumOf { it.durationSec } / 60).roundToInt()
        if (totalMinutes < MIN_TOTAL_MINUTES) return null

        // 종목 선언 순서로 훑되 시간 많은 순으로 정렬 (동률은 선언 순서 유지 — 안정 정렬)
        val breakdown = CrossTraining.Kind.entries.mapNotNull { kind ->
            val kindSessions = weekSessions.filter { it.kind == kind }
            if (kindSessions.isEmpty()) return@mapNotNull null
            Item(
                label = kind.label,
                minutes = (kindSessions.sumOf { it.durationSec } / 60).roundToInt(),
                count = kindSessions.size,
            )
        }.sortedByDescending { it.minutes }

        // 러닝 주간 비교는 반개구간 [7일 전, 지금) / [14일 전, 7일 전) — 경계 중복 방지
        val twoWeeksAgo = now.minusSeconds(14 * 86_400L)
        val thisWeekKm = runs.filter { it.start >= weekAgo && it.start < now }
            .sumOf { it.distanceKm ?: 0.0 }
        val lastWeekKm = runs.filter { it.start >= twoWeeksAgo && it.start < weekAgo }
            .sumOf { it.distanceKm ?: 0.0 }
        val decreased = lastWeekKm > 0 && thisWeekKm <= lastWeekKm * 0.8

        val top = breakdown.first()
        val headline = if (decreased) {
            "이번 주 러닝은 줄었지만 ${top.label} ${timeLabel(top.minutes)} — 다리는 쉬어도 심박은 출근했네요."
        } else {
            "러닝 ${"%.0f".format(Locale.ROOT, thisWeekKm)}km에 크로스 트레이닝 ${timeLabel(totalMinutes)}까지, " +
                "그중 ${top.label}이 ${timeLabel(top.minutes)}로 제일 많았어요 — 부지런히도 움직이셨어요."
        }

        return Summary(
            sessionCount = weekSessions.size,
            totalMinutes = totalMinutes,
            breakdown = breakdown,
            headline = headline,
            detail = "크로스 트레이닝 시간은 러닝 거리 부하(ACWR)에는 넣지 않아요. " +
                "몸의 피로는 따로 쌓이니 회복은 같이 챙기세요.",
        )
    }

    private fun timeLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }
}
