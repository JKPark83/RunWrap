package com.jkpark.runwrap.engine

import java.time.Instant

/// 대회 캘린더 추가 (이슈 #172) — 상세 화면 '캘린더에 추가' 버튼을 눌렀을 때만 이벤트 1건을 쓴다.
/// iOS 17의 쓰기 전용 권한만 청한다 — 기존 일정은 읽지 않고, 어디에도 전송하지 않는다.
/// (Android: 이벤트 쓰기(`add`)와 `RaceCalendarError`는 캘린더 인텐트를 쓰는 :app 몫이고,
/// 엔진에는 이벤트 시각 계산만 둔다)
object RaceCalendar {
    /// 이벤트 시각 (순수 값) — 출발 시각을 알면 그 시각부터 4시간, 모르면 종일
    data class Schedule(
        val start: Instant,
        val end: Instant,
        val isAllDay: Boolean,
    )

    /// 출발 시각이 있을 때 이벤트 길이 — 풀코스 제한시간(보통 5시간) 안쪽의 대략적인 대회 일정
    const val raceDuration: Double = 4 * 3_600.0

    private val intPattern = Regex("[+-]?[0-9]+")

    /// 대회 → 이벤트 시각. startTime("HH:mm")은 KST 기준이고, 형식이 어긋나면 종일로 낸다
    fun schedule(entry: RaceEngine.Entry): Schedule {
        // Swift `split(separator:)`는 빈 조각을 버린다
        val fields = entry.race.startTime?.split(":")?.filter { it.isNotEmpty() } ?: emptyList()
        // Swift `Int(String)` — 부호 하나와 ASCII 숫자만 받는다
        val parts = fields.mapNotNull { field -> field.takeIf { intPattern.matches(it) }?.toIntOrNull() }
        if (parts.size == 2 && fields.size == 2 && fields[1].length == 2 &&
            parts[0] in 0 until 24 && parts[1] in 0 until 60) {
            val start = entry.raceDate.atZone(KST)
                .withHour(parts[0]).withMinute(parts[1]).withSecond(0).withNano(0).toInstant()
            return Schedule(start = start, end = start.plusSeconds(raceDuration.toLong()), isAllDay = false)
        }
        return Schedule(start = entry.raceDate, end = entry.raceDate, isAllDay = true)
    }
}
