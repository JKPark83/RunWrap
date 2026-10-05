package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/// 알림 설정 키 (@AppStorage/UserDefaults 공용) — ProfileKey와 같은 패턴
object NotifyKey {
    const val workoutEnabled = "notify.workout"          // 운동 직후 인사이트 토글
    const val weeklyEnabled = "notify.weekly"            // 주간 리포트 토글
    const val weeklyWeekday = "notify.weekly.weekday"    // 1 = 일 … 7 = 토 (Calendar 규약)
    const val weeklyHour = "notify.weekly.hour"          // 기본 18시
    /// 마지막으로 운동 알림을 보낸 세션의 시작 시각 (timeIntervalSince1970) — 중복 발송 가드
    const val lastWorkoutStart = "notify.workout.lastStart"
    // 수분 알람 (계획서 M9)
    const val hydrationEnabled = "notify.hydration"
    const val runHour = "notify.runHour"                 // 주로 달리는 시각, 기본 19시
    // 즐겨찾기 대회 접수 알림 (이슈 #172)
    const val raceEnabled = "notify.race"
}

/// 로컬 알림 (계획서 M8) — 권한 요청 + 운동 직후 인사이트 / 주간 리포트 예약.
/// 전부 온디바이스 로컬 알림이다 — 원격 푸시·네트워크 전송이 없다.
/// 본문 빌더는 순수 static 함수로 분리해 테스트한다 (NotificationContentTests).
/// (Android: 순수부만 옮긴다. 권한·예약(UNUserNotificationCenter 대응)은 :app이 맡고,
///  러닝 직후 자동 알림 `workoutBody`는 ios-only다. DateComponents는 `LocalDateTime`(시간대 없는 성분),
///  시간대가 담긴 성분은 `ZonedDateTime`으로 낸다)
object NotificationScheduler {
    /// 같은 id로 다시 add하면 기존 예약이 교체된다
    const val weeklyId = "runwrap.weekly"
    /// 주간 백업 알림 id — 첫 회 다음 주부터 3주치 (이슈 #94). 취소할 때 weeklyId와 함께 지운다
    val weeklyBackupIds: List<String> = (2..4).map { "$weeklyId.$it" }
    const val workoutId = "runwrap.workout"
    const val hydrationId = "runwrap.hydration"

    // MARK: 본문 빌더 (순수 함수)

    /// 주간 본문 — 캐시 스냅샷이 있으면 횟수·거리·헤드라인, 없으면 기본 문구.
    /// 횟수·거리 모두 최근 7일(6일 전 자정 ~ 지금, 리포트 헤더와 같은 창) 기준이라
    /// 문구도 "최근 7일"로 맞춘다 (이슈 #21, #75).
    /// trigger는 알림이 실제로 울릴 시각 — 그때 스냅샷이 오래됐으면 수치 없이 기본 문구로 낸다 (이슈 #61)
    fun weeklyBody(snapshot: ReportSnapshot?, at: Instant): String {
        if (snapshot == null || isStale(snapshot, at = at)) {
            return "이번 주 러닝을 정리했어요 — 리포트를 열어보세요"
        }
        // 런린이는 주간 거리를 숫자 없이 문장만 본다 — 화면과 같은 게이트가 알림에도 걸린다 (이슈 #141)
        if (!snapshot.showsDistanceNumbers) {
            return "최근 7일 ${snapshot.runCount}회 — ${snapshot.headline}"
        }
        return "최근 7일 ${snapshot.runCount}회 · ${fmt(snapshot.weekKm, 1)} km — ${snapshot.headline}"
    }

    /// 주간 백업 본문 (이슈 #94) — 앱을 오래 안 열면 스냅샷이 낡으므로 수치를 싣지 않는다
    const val weeklyBackupBody = "이번 주 러닝은 어땠나요? 런미새 리포트에서 확인해 보세요"

    /// 스냅샷 신선도 판정 (이슈 #61) — 발송 시각 기준 maxAge(기본 48시간)를 넘겼으면 오래된 것.
    /// 앱을 며칠 안 열면 캐시가 갱신되지 않아 "최근 7일" 수치가 실제와 어긋난다 —
    /// 틀린 수치를 보내느니 기본 문구가 낫다. 정확히 maxAge인 경우는 아직 신선하다
    fun isStale(snapshot: ReportSnapshot, at: Instant, maxAge: Double = 48.0 * 3_600): Boolean =
        at.timeIntervalSince1970 - snapshot.generatedAt.timeIntervalSince1970 > maxAge

    /// 주간 발송 시각 count건 — from 이후 첫 (요일, 시) 정각부터 7일 간격 (이슈 #94).
    /// 요일·시만 맞추는 트리거는 시작일을 못 정해 첫 회와 겹치므로, 회차마다 정확한
    /// 날짜(year/month/day/hour/minute)로 만들어 각각 repeats: false로 예약한다.
    /// 첫 회는 스냅샷 본문, 나머지는 백업 본문이다 — 1주 넘게 앱을 안 열어도 4주까지는 끊기지 않는다.
    /// 정확히 (요일, 시) 정각인 from은 이미 지난 것으로 보고 다음 주부터 센다 (nextTriggerDate와 같은 규약)
    /// (Android: `calendar` 대신 `zone`을 받는다. weekday는 iOS Calendar 규약 1 = 일 … 7 = 토)
    fun weeklyFireDates(from: Instant, weekday: Int, hour: Int, count: Int, zone: ZoneId): List<LocalDateTime> {
        if (count <= 0) return emptyList()
        // `calendar.nextDate(after:matching:.nextTime)` — from 이후 첫 (요일, 시) 정각. 1주 안에 반드시 있다
        val fromDate = from.atZone(zone).toLocalDate()
        val first = (0L..7L).asSequence()
            .map { fromDate.plusDays(it) }
            .filter { it.dayOfWeek.value % 7 + 1 == weekday }
            .map { it.atTime(hour, 0).atZone(zone) }
            .firstOrNull { it.toInstant() > from } ?: return emptyList()
        return (0 until count).map { week -> first.plusDays(7L * week).toLocalDateTime() }
    }

    /// 수분 알람 시각 판정 (순수 함수, 계획서 M9) — 조건을 모두 만족하면 오늘
    /// "러닝 1시간 전" 정각의 DateComponents, 아니면 nil.
    /// 조건: 토글 on · 예보 최고 ≥ 25°C(기획서 §4.11) · 알람 시각이 아직 지나지 않음.
    fun hydrationAlarm(forecastMaxC: Double, runHour: Int, enabled: Boolean, now: Instant, zone: ZoneId): LocalDateTime? {
        if (!enabled || !(forecastMaxC >= 25.0) || runHour < 1) return null
        val alarmDate = now.atZone(zone).toLocalDate().atTime(runHour - 1, 0).atZone(zone)
        if (!(alarmDate.toInstant() > now)) return null   // 이미 지난 시각이면 오늘분은 없다
        return alarmDate.toLocalDateTime()
    }

    /// 수분 알람 본문 — "오늘 30°C 예보 — 러닝 1시간 전 500ml 마셔두세요"
    fun hydrationBody(forecastMaxC: Double): String =
        "오늘 ${fmt(forecastMaxC, 0)}°C 예보 — 러닝 1시간 전 500ml 마셔두세요"

    /// 대회 접수 알림 1건 (이슈 #172) — 예약 직전의 순수 값. fire는 KST 시간대가 담긴 날짜 성분
    data class RaceAlarm(
        val id: String,
        val title: String,
        val body: String,
        val fire: ZonedDateTime,
    )

    /// 대회 접수 알림 id 접두 — 재예약 때 이 접두의 대기 요청을 전부 거둔다
    const val raceIdPrefix = "runwrap.race."

    /// 즐겨찾기 대회 접수 알림 (순수 함수, 이슈 #172) — 대회마다 접수 시작일 오전 9시와
    /// 마감 3일 전 오전 9시(KST, 대회는 전부 국내 개최라 RaceEngine 달력). now 이후 시각만 낸다.
    /// 대회일이 가까운 순으로 알림이 1건이라도 있는 대회를 최대 limit개까지 — 기기의 대기 알림
    /// 상한(64건)을 주간·수분 알림과 나눠 쓰기 때문이다. 접수기간을 모르면 그 대회는 0건 (미노출 가드)
    fun raceAlarms(favorites: List<RaceEngine.Entry>, now: Instant, limit: Int = 20): List<RaceAlarm> {
        fun nineAM(day: Instant?, minusDays: Long): ZonedDateTime? {
            if (day == null) return null
            val fire = day.atZone(KST).toLocalDate().minusDays(minusDays).atTime(9, 0).atZone(KST)
            return if (fire.toInstant() > now) fire else null
        }
        val perRace: List<List<RaceAlarm>> = favorites
            .sortedWith(compareBy<RaceEngine.Entry> { it.raceDate }.thenBy { it.race.id })
            .map { entry ->
                val race = entry.race
                val alarms = mutableListOf<RaceAlarm>()
                nineAM(RaceEngine.day(race.registerStart), minusDays = 0)?.let { fire ->
                    alarms.add(RaceAlarm(id = "$raceIdPrefix${race.id}.start", title = "대회 접수 알림",
                                         body = "${race.name} 접수가 오늘 시작돼요", fire = fire))
                }
                nineAM(RaceEngine.day(race.registerEnd), minusDays = 3)?.let { fire ->
                    alarms.add(RaceAlarm(id = "$raceIdPrefix${race.id}.end", title = "대회 접수 알림",
                                         body = "${race.name} 접수 마감 3일 전이에요", fire = fire))
                }
                alarms
            }
        return perRace.filter { it.isNotEmpty() }.take(maxOf(limit, 0)).flatten()
    }
}
