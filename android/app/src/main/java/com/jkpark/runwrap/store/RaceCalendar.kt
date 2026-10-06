package com.jkpark.runwrap.store

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.jkpark.runwrap.engine.KST
import com.jkpark.runwrap.engine.RaceCalendar
import com.jkpark.runwrap.engine.RaceEngine

/// 대회 캘린더 추가 (이슈 #172) — 상세 화면 '캘린더에 추가' 버튼을 눌렀을 때만 이벤트 1건을 쓴다.
/// iOS 17의 쓰기 전용 권한만 청한다 — 기존 일정은 읽지 않고, 어디에도 전송하지 않는다.
/// (Android: 캘린더 권한 없이 일정 추가 인텐트로 캘린더 앱의 새 일정 화면을 채워 연다 — 저장은 사용자가 그 화면에서 한다.
///  그래서 거부(`RaceCalendarError.denied`)·기본 캘린더 없음(`noCalendar`)은 없고, 결과(저장/취소)도 알 수 없다.
///  받을 캘린더 앱이 없으면 `ActivityNotFoundException`을 던진다 — 호출부가 '추가하지 못했어요'로 안내한다.
///  일정 화면에 URL 칸이 없어 홈페이지는 메모 둘째 줄에 넣는다)
fun RaceCalendar.add(context: Context, entry: RaceEngine.Entry) {
    val race = entry.race
    val schedule = schedule(entry)
    val notes = listOfNotNull("런미새에서 추가", race.homepage).joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, race.name)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, schedule.isAllDay)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, schedule.start.toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, schedule.end.toEpochMilli())
        .putExtra(CalendarContract.Events.EVENT_LOCATION, race.place ?: race.region)
        .putExtra(CalendarContract.Events.DESCRIPTION, notes)
    if (!schedule.isAllDay) intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, KST.id)
    context.startActivity(intent)
}
