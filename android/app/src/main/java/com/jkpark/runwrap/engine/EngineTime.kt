package com.jkpark.runwrap.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/// 엔진 공용 시간 유틸 — iOS는 기기 시간대(Calendar.current)를 쓰지만 이 앱은 한국 전용이라
/// 엔진 결정론을 위해 KST로 고정한다 (계획서 M2 이식 규칙: Calendar(KST) → ZoneId("Asia/Seoul")).
internal val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/// ISO 8601 주(월요일 시작)의 시작일 — iOS `Calendar(iso8601).dateInterval(of: .weekOfYear)` 대응
internal fun weekStart(instant: Instant): LocalDate =
    instant.atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/// iOS `Date.timeIntervalSince` 대응 — 초 단위 Double (ms 정밀도)
internal fun secondsBetween(from: Instant, to: Instant): Double =
    (to.toEpochMilli() - from.toEpochMilli()) / 1000.0
