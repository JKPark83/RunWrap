package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId

/// 공유 카드 하단 주간 요약 문구 (기획서 §4.4) — 순수 로직: now를 주입받아 결정론적이다.
///
/// 창은 '지금'이 아니라 그 세션 기준 달력 7일 — [startOfDay(run.start − 6일), run.start 다음 자정).
/// '지금' 기준이면 과거 세션 카드에 현재 주 수치가 찍힌다 (이슈 #92). 달력 창 방식은 이슈 #75와 같다.
object ShareSummary {
    /// "최근 7일 3회 · 24.5 km" — 창 안 기록이 없으면 nil (카드는 기본 문구로 대체한다)
    /// (Android: iOS `calendar: Calendar = .current` 대신 `zone`을 주입받는다 — 기본값 없음)
    fun weeklyLine(runs: List<RunSummary>, sessionStart: Instant, now: Instant,
                   zone: ZoneId): String? {
        val start = sessionStart.minusSeconds(6L * 86_400).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant()
        val sessionDay = sessionStart.atZone(zone).toLocalDate()
        val end = sessionDay.plusDays(1).atStartOfDay(zone).toInstant()
        val week = runs.filter { it.start >= start && it.start < end }
        if (week.isEmpty()) return null
        val km = week.mapNotNull { it.distanceKm }.fold(0.0) { acc, v -> acc + v }
        // 오늘 세션이면 창이 '최근 7일'과 같다 — 지난 세션은 그날(카드 헤더 날짜)까지 7일로 적는다
        val label = if (sessionDay == now.atZone(zone).toLocalDate()) "최근 7일" else "그날까지 7일"
        return "$label ${week.size}회 · ${Format.km(km)} km"
    }
}
