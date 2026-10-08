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

    /// 공유 카드 구간 페이스 표의 한 줄 — "3km 5:41" 또는 묶었을 때 "3–4km 5:41" (이슈 #221)
    data class SplitRow(
        val label: String,
        val paceSecPerKm: Double,
        /// 가장 빠른 줄 — 카드에서 강조한다 (같으면 앞 줄)
        val isFastest: Boolean,
    )

    /// 1km 스플릿 페이스 → 카드 표 줄. 9:16 카드에 지도와 함께 2열 × 5줄(`maxRows` 10)까지만 들어가므로
    /// 그보다 많으면 연속 구간을 ceil(n / maxRows)km씩 묶어 평균 페이스를 적는다 —
    /// 하프(21구간)는 3km씩 7줄, 풀(42구간)은 5km씩 9줄(마지막은 41–42km). 구간 거리가 같아(1km) 단순 평균이 곧 시간 가중 평균이다.
    /// 3구간 미만이면 표가 의미 없어 빈 배열 — 세션 상세 스플릿 카드와 같은 가드 (미노출 원칙)
    fun splitRows(paces: List<Double>, maxRows: Int = 10): List<SplitRow> {
        if (paces.size < 3 || maxRows <= 0) return emptyList()
        val size = (paces.size + maxRows - 1) / maxRows
        val groups = (paces.indices step size).map { start ->
            val end = minOf(start + size, paces.size)
            val label = if (end - start == 1) "${start + 1}km" else "${start + 1}–${end}km"
            label to paces.subList(start, end).sum() / (end - start)
        }
        val fastest = groups.indices.minBy { groups[it].second }
        return groups.mapIndexed { i, (label, pace) -> SplitRow(label, pace, i == fastest) }
    }
}
