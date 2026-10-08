package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 알림 본문 빌더(순수 함수)와 리포트 캐시 왕복 검증 (계획서 M8).
/// now = 2026-08-10T09:00:00Z 고정.
/// (Android: 러닝 직후 자동 알림 `workoutBody` 2건과 알림 권한 판정 `isGranted` 1건은 ios-only)
class NotificationContentTests {
    private val now = iso("2026-08-10T09:00:00Z")

    private fun uuid(): String = UUID.randomUUID().toString().uppercase()

    @Test
    @DisplayName("주간 본문 — 캐시가 있으면 횟수·거리·헤드라인, 없으면 기본 문구")
    fun weeklyBody() {
        val snapshot = ReportSnapshot(generatedAt = now,
                                      headline = "안정적으로 리듬을 지킨 한 주였습니다.",
                                      suggestion = null, weekKm = 21.4, runCount = 3,
                                      showsDistanceNumbers = true)
        assertEquals("최근 7일 3회 · 21.4 km — 안정적으로 리듬을 지킨 한 주였습니다.",
                     NotificationScheduler.weeklyBody(snapshot = snapshot, at = now))
        assertEquals("이번 주 러닝을 정리했어요 — 리포트를 열어보세요",
                     NotificationScheduler.weeklyBody(snapshot = null, at = now))
    }

    @Test
    @DisplayName("주간 본문 신선도 — 발송 시각 기준 48시간 이내 스냅샷만 수치를 싣는다 (이슈 #61)")
    fun weeklyBodyStaleness() {
        // 스냅샷 생성 = now(2026-08-10T09:00Z)
        val snapshot = ReportSnapshot(generatedAt = now,
                                      headline = "안정적으로 리듬을 지킨 한 주였습니다.",
                                      suggestion = null, weekKm = 21.4, runCount = 3,
                                      showsDistanceNumbers = true)
        val fallback = "이번 주 러닝을 정리했어요 — 리포트를 열어보세요"

        // 47h59m 뒤 발송(2026-08-12T08:59Z) → 48h 이내라 신선 → 수치 포함
        val fresh = iso("2026-08-12T08:59:00Z")
        assertFalse(NotificationScheduler.isStale(snapshot, at = fresh))
        assertEquals("최근 7일 3회 · 21.4 km — 안정적으로 리듬을 지킨 한 주였습니다.",
                     NotificationScheduler.weeklyBody(snapshot = snapshot, at = fresh))

        // 48h01m 뒤 발송(2026-08-12T09:01Z) → 48h 초과라 오래됨 → 기본 문구
        val stale = iso("2026-08-12T09:01:00Z")
        assertTrue(NotificationScheduler.isStale(snapshot, at = stale))
        assertEquals(fallback, NotificationScheduler.weeklyBody(snapshot = snapshot, at = stale))

        // 스냅샷이 없으면 발송 시각과 무관하게 기본 문구
        assertEquals(fallback, NotificationScheduler.weeklyBody(snapshot = null, at = fresh))
    }

    @Test
    @DisplayName("주간 본문 레벨 게이트 — 런린이 스냅샷은 km 없이 횟수·헤드라인만 싣는다 (이슈 #141)")
    fun weeklyBodyBeginnerHidesDistance() {
        // 런린이는 ReportGate.showsNumbers(.distance) = false → make가 showsDistanceNumbers false로 채운다
        val report = WeeklyReport(dateRange = "8.3 – 8.9", weeks = emptyList(),
                                  distance = null, acwr = null, efficiency = null,
                                  streakWeeks = 2, ranThisWeek = true, weekRunCount = 3)
        val runs = listOf(RunSummary(id = uuid(), start = now.minusSeconds(2L * 86_400),
                                     durationSec = 3_000.0, distanceMeters = 10_000.0, avgHeartRate = 150.0))
        val snapshot = ReportSnapshot.make(report = report, runs = runs, level = RunnerLevel.beginner,
                                           now = now, zone = testZone)
        assertFalse(snapshot.showsDistanceNumbers)

        val body = NotificationScheduler.weeklyBody(snapshot = snapshot, at = now)
        assertFalse(body.contains("km"))
        assertEquals("최근 7일 3회 — ${report.headline(level = RunnerLevel.beginner)}", body)
    }

    @Test
    @DisplayName("주간 본문 레벨 게이트 — 런잘알 스냅샷은 기존처럼 횟수·거리·헤드라인 (이슈 #141)")
    fun weeklyBodyIntermediateShowsDistance() {
        // 창 안 10km 1건 → weekKm 10.0, 횟수는 report.weekRunCount 3
        val report = WeeklyReport(dateRange = "8.3 – 8.9", weeks = emptyList(),
                                  distance = null, acwr = null, efficiency = null,
                                  streakWeeks = 2, ranThisWeek = true, weekRunCount = 3)
        val runs = listOf(RunSummary(id = uuid(), start = now.minusSeconds(2L * 86_400),
                                     durationSec = 3_000.0, distanceMeters = 10_000.0, avgHeartRate = 150.0))
        val snapshot = ReportSnapshot.make(report = report, runs = runs, level = RunnerLevel.intermediate,
                                           now = now, zone = testZone)
        assertTrue(snapshot.showsDistanceNumbers)
        assertEquals("최근 7일 3회 · 10.0 km — ${report.headline(level = RunnerLevel.intermediate)}",
                     NotificationScheduler.weeklyBody(snapshot = snapshot, at = now))
    }

    /// 주간 발송 시각 테스트용 — 시간대를 서울로 고정해 실행 환경과 무관하게 한다
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    @DisplayName("주간 발송 시각 — 다음 (요일, 시) 정각부터 7일 간격으로 정확한 날짜 4건 (이슈 #94)")
    fun weeklyFireDates() {
        // now = 2026-08-10T09:00Z = 8/10(월) 18:00 KST. 일요일(1) 18시 → 다음 회차는 8/16(일)
        val dates = NotificationScheduler.weeklyFireDates(from = now, weekday = 1, hour = 18,
                                                          count = 4, zone = seoul)
        assertEquals(listOf(16, 23, 30, 6), dates.map { it.dayOfMonth })      // 8/16·8/23·8/30·9/6
        assertEquals(listOf(8, 8, 8, 9), dates.map { it.monthValue })
        assertTrue(dates.all { it.year == 2026 && it.hour == 18 && it.minute == 0 })
        // 요일 반복 트리거가 아니라 날짜 고정이어야 첫 회와 백업이 겹치지 않는다
        // (Android: `weekday == nil` 확인은 대응이 없다 — LocalDateTime은 요일 성분 없이 날짜로만 정해진다)
    }

    @Test
    @DisplayName("주간 발송 시각 — 같은 요일 이전 시각이면 오늘, 정각이면 다음 주부터 센다 (이슈 #94)")
    fun weeklyFireDatesSameDay() {
        // now = 8/10(월) 18:00 KST. 월요일(2) 19시 → 오늘 19시가 첫 회
        val later = NotificationScheduler.weeklyFireDates(from = now, weekday = 2, hour = 19,
                                                          count = 2, zone = seoul)
        assertEquals(listOf(10, 17), later.map { it.dayOfMonth })
        // 월요일 18시 정각 = now → 이미 지난 것으로 보고 8/17부터
        val exact = NotificationScheduler.weeklyFireDates(from = now, weekday = 2, hour = 18,
                                                          count = 1, zone = seoul)
        assertEquals(listOf(17), exact.map { it.dayOfMonth })
        // count 0 → 빈 목록
        assertTrue(NotificationScheduler.weeklyFireDates(from = now, weekday = 2, hour = 18,
                                                         count = 0, zone = seoul).isEmpty())
    }

    @Test
    @DisplayName("주간 백업 id — 첫 회 id와 겹치지 않는 3건 (이슈 #94)")
    fun weeklyBackupIds() {
        assertEquals(listOf("runwrap.weekly.2", "runwrap.weekly.3", "runwrap.weekly.4"),
                     NotificationScheduler.weeklyBackupIds)
        assertFalse(NotificationScheduler.weeklyBackupIds.contains(NotificationScheduler.weeklyId))
    }

    @Test
    @DisplayName("스냅샷 생성 — 최근 7일 거리 합과 횟수를 담는다")
    fun snapshotMake() {
        val report = WeeklyReport(dateRange = "8.3 – 8.9", weeks = emptyList(),
                                  distance = null, acwr = null, efficiency = null,
                                  streakWeeks = 2, ranThisWeek = true, weekRunCount = 3)
        // 창은 6일 전 자정 ~ now (weekRunCount와 같은 창, 이슈 #75).
        // 2일 전 10km는 창 안, 9일 전 10km는 창 밖.
        // 8일째 날(창 시작 1분 전) 7km는 롤링 7×86_400 안이지만 창 밖 → weekKm 10
        val windowStart = now.minusSeconds(6L * 86_400).atZone(testZone).toLocalDate().atStartOfDay(testZone).toInstant()
        val runs = listOf(RunSummary(id = uuid(), start = now.minusSeconds(2L * 86_400),
                                     durationSec = 3_000.0, distanceMeters = 10_000.0, avgHeartRate = 150.0),
                          RunSummary(id = uuid(), start = now.minusSeconds(9L * 86_400),
                                     durationSec = 3_000.0, distanceMeters = 10_000.0, avgHeartRate = 150.0),
                          RunSummary(id = uuid(), start = windowStart.minusSeconds(60),
                                     durationSec = 2_100.0, distanceMeters = 7_000.0, avgHeartRate = 150.0))
        assertTrue(runs[2].start >= now.minusSeconds(7L * 86_400))  // 전제: 롤링 7일 안
        val snapshot = ReportSnapshot.make(report = report, runs = runs, level = RunnerLevel.intermediate,
                                           now = now, zone = testZone)
        assertTrue(abs(snapshot.weekKm - 10) < 0.001)
        assertEquals(3, snapshot.runCount)
        assertEquals(report.headline(level = RunnerLevel.intermediate), snapshot.headline)
        assertEquals(now, snapshot.generatedAt)
    }

    @Test
    @DisplayName("수분 알람 판정 — 25°C 이상 + 토글 on + 시각 미경과일 때만 러닝 1시간 전")
    fun hydrationAlarm() {
        // 로컬 시간대 기준 오전 9시로 고정 — 시간대와 무관하게 결정론적
        val base = iso("2026-08-10T00:00:00Z")
        val now = base.atZone(testZone).toLocalDate().atTime(9, 0).atZone(testZone).toInstant()

        // 30°C 예보, 19시 러닝 → 18시 정각 알람
        val components = assertNotNull(NotificationScheduler.hydrationAlarm(
            forecastMaxC = 30.0, runHour = 19, enabled = true, now = now, zone = testZone))
        assertEquals(18, components.hour)
        assertEquals(0, components.minute)
        assertEquals(now.atZone(testZone).dayOfMonth, components.dayOfMonth)

        // 24.9°C 예보 → 기준 미달
        assertNull(NotificationScheduler.hydrationAlarm(
            forecastMaxC = 24.9, runHour = 19, enabled = true, now = now, zone = testZone))
        // 토글 off
        assertNull(NotificationScheduler.hydrationAlarm(
            forecastMaxC = 30.0, runHour = 19, enabled = false, now = now, zone = testZone))
        // 8시 러닝 → 알람 시각 7시가 이미 지났다 (now 9시)
        assertNull(NotificationScheduler.hydrationAlarm(
            forecastMaxC = 30.0, runHour = 8, enabled = true, now = now, zone = testZone))
    }

    @Test
    @DisplayName("수분 알람 본문 — 예보 기온을 정수로 반올림해 넣는다")
    fun hydrationBody() {
        assertEquals("오늘 30°C 예보 — 러닝 1시간 전 500ml 마셔두세요",
                     NotificationScheduler.hydrationBody(forecastMaxC = 30.4))
    }

    @Test
    @DisplayName("예보 디코드 — daily가 있으면 최고기온, 없으면 nil")
    fun forecastDecode() {
        val withDaily = """
        {
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 },
          "daily": { "temperature_2m_max": [31.6] }
        }
        """.trimIndent()
        assertEquals(31.6, WeatherClient.decode(withDaily.encodeToByteArray(), now).forecastMaxC)

        val withoutDaily = """
        {
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 }
        }
        """.trimIndent()
        assertNull(WeatherClient.decode(withoutDaily.encodeToByteArray(), now).forecastMaxC)
    }

    private fun makeTempDir(): File =
        Files.createTempDirectory("runwrap-cache-test-${UUID.randomUUID()}").toFile()

    @Test
    @DisplayName("캐시 왕복 — 저장한 스냅샷을 그대로 복원하고, 파일이 없으면 nil")
    fun cacheRoundTrip() {
        val dir = makeTempDir()
        try {
            assertNull(ReportCache.load(dir))

            val snapshot = ReportSnapshot(generatedAt = now,
                                          headline = "몸이 좋아지고 있는 한 주였습니다.",
                                          suggestion = "지금 리듬 그대로 이어가면 됩니다.",
                                          weekKm = 32.5, runCount = 4,
                                          showsDistanceNumbers = true)
            ReportCache.save(snapshot, dir)
            assertEquals(snapshot, ReportCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("캐시 호환 — showsDistanceNumbers 키가 없는 기존 캐시는 수치 노출(true)로 읽는다 (이슈 #141)")
    fun cacheDecodesLegacySnapshot() {
        // 이슈 #141 이전 형식 — generatedAt은 JSONEncoder 기본(2001-01-01 기준 초)
        // now.timeIntervalSinceReferenceDate = 1_786_352_400 − 978_307_200 = 808_045_200 (Swift 보간 "808045200.0")
        val legacy = """
        {"generatedAt": 808045200.0, "headline": "예전 헤드라인",
         "weekKm": 12.5, "runCount": 2}
        """
        val snapshot = EngineJson.decodeFromString<ReportSnapshot>(legacy)
        assertTrue(snapshot.showsDistanceNumbers)
        assertEquals(now, snapshot.generatedAt)
        assertNull(snapshot.suggestion)
        assertEquals(2, snapshot.runCount)
    }

    @Test
    @DisplayName("캐시 삭제 — 데모 모드를 끄면 비운 캐시는 nil로 읽힌다 (이슈 #44)")
    fun cacheClear() {
        val dir = makeTempDir()
        try {
            ReportCache.save(ReportSnapshot(generatedAt = now, headline = "데모 헤드라인",
                                            suggestion = null, weekKm = 30.0, runCount = 5,
                                            showsDistanceNumbers = true), dir)
            assertNotNull(ReportCache.load(dir))

            ReportCache.clear(dir)
            assertNull(ReportCache.load(dir))
            // 파일이 없을 때 다시 지워도 조용히 넘어간다
            ReportCache.clear(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // MARK: 대회 접수 알림 (이슈 #172)

    private fun raceEntries(races: List<Race>): List<RaceEngine.Entry> =
        RaceEngine.entries(races, now = now)

    @Test
    @DisplayName("대회 접수 알림 — 접수 시작일 09:00 KST, 이미 지난 마감 3일 전 알림은 뺀다 (이슈 #172)")
    fun raceAlarmsStartAndPastEnd() {
        // now = 2026-08-10T09:00Z = 8/10 18:00 KST
        // 대회 1: 접수 8/20~9/5 → 시작 8/20 09:00 KST, 마감 3일 전 9/2 09:00 KST
        // 대회 2: 마감 8/12만 → 3일 전 8/9 09:00 KST는 이미 지나 0건
        val alarms = NotificationScheduler.raceAlarms(favorites = raceEntries(listOf(
            Race(id = 1, name = "서울달리기", date = "2026-09-20",
                 registerStart = "2026-08-20", registerEnd = "2026-09-05"),
            Race(id = 2, name = "한강 10K", date = "2026-09-27", registerEnd = "2026-08-12"),
        )), now = now)

        assertEquals(listOf("runwrap.race.1.start", "runwrap.race.1.end"), alarms.map { it.id })
        val start = assertNotNull(alarms.firstOrNull())
        assertEquals("서울달리기 접수가 오늘 시작돼요", start.body)
        assertEquals(ZoneId.of("Asia/Seoul"), start.fire.zone)
        assertEquals(listOf(2026, 8, 20, 9, 0),
                     listOf(start.fire.year, start.fire.monthValue, start.fire.dayOfMonth, start.fire.hour, start.fire.minute))
        // 성분이 가리키는 절대 시각 — 8/20 09:00 KST = 8/20 00:00Z (기기 시간대와 무관)
        assertEquals(iso("2026-08-20T00:00:00Z"), start.fire.toInstant())
        // (Android: UNCalendarNotificationTrigger.nextTriggerDate 확인은 ios-only — 예약 API는 :app이 맡는다)

        val end = assertNotNull(alarms.lastOrNull())
        assertEquals("서울달리기 접수 마감 3일 전이에요", end.body)
        assertEquals(listOf(9, 2, 9), listOf(end.fire.monthValue, end.fire.dayOfMonth, end.fire.hour))
    }

    @Test
    @DisplayName("대회 접수 알림 — 접수기간을 모르는 대회는 0건 (이슈 #172)")
    fun raceAlarmsNoPeriod() {
        val alarms = NotificationScheduler.raceAlarms(
            favorites = raceEntries(listOf(Race(id = 3, name = "기간 미상", date = "2026-10-01"))), now = now)
        assertTrue(alarms.isEmpty())
    }

    @Test
    @DisplayName("대회 접수 알림 — 대회일 순으로 limit개 대회까지만, 알림 없는 대회는 개수에 안 센다 (이슈 #172)")
    fun raceAlarmsLimit() {
        // 입력 순서와 무관하게 대회일 순: 9/5(기간 미상, 0건) → 9/12(id 11) → 9/19(id 12) → 9/26(id 13)
        // limit 2 → 알림이 있는 앞의 두 대회(11, 12)의 시작 알림만 남고 13은 잘린다
        val alarms = NotificationScheduler.raceAlarms(favorites = raceEntries(listOf(
            Race(id = 13, name = "C", date = "2026-09-26", registerStart = "2026-08-21"),
            Race(id = 11, name = "A", date = "2026-09-12", registerStart = "2026-08-21"),
            Race(id = 10, name = "미상", date = "2026-09-05"),
            Race(id = 12, name = "B", date = "2026-09-19", registerStart = "2026-08-21"),
        )), now = now, limit = 2)
        assertEquals(listOf("runwrap.race.11.start", "runwrap.race.12.start"), alarms.map { it.id })
    }
}
