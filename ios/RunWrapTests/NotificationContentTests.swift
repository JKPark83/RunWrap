import Foundation
import Testing
import UserNotifications
@testable import RunWrap

/// 알림 본문 빌더(순수 함수)와 리포트 캐시 왕복 검증 (계획서 M8).
/// now = 2026-08-10T09:00:00Z 고정.
struct NotificationContentTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    @Test("운동 직후 본문 — 거리·페이스가 있으면 한 줄 요약으로 만든다")
    func workoutBodyFull() {
        // 8.2km, 페이스 332초/km = 5′32″ → durationSec = 332 × 8.2
        let run = RunSummary(id: UUID(), start: now, durationSec: 332 * 8.2,
                             distanceMeters: 8_200, avgHeartRate: 150)
        #expect(NotificationScheduler.workoutBody(run: run)
            == "8.2 km · 5′32″/km — 리포트에 반영됐어요")
    }

    @Test("운동 직후 본문 — 거리 없는 세션(수동 기록 등)은 기본 문구로 낸다")
    func workoutBodyMinimal() {
        let run = RunSummary(id: UUID(), start: now, durationSec: 1_800,
                             distanceMeters: nil, avgHeartRate: nil)
        #expect(NotificationScheduler.workoutBody(run: run) == "오늘 러닝 — 리포트에 반영됐어요")
    }

    @Test("주간 본문 — 캐시가 있으면 횟수·거리·헤드라인, 없으면 기본 문구")
    func weeklyBody() {
        let snapshot = ReportSnapshot(generatedAt: now,
                                      headline: "안정적으로 리듬을 지킨 한 주였습니다.",
                                      suggestion: nil, weekKm: 21.4, runCount: 3)
        #expect(NotificationScheduler.weeklyBody(snapshot: snapshot, at: now)
            == "최근 7일 3회 · 21.4 km — 안정적으로 리듬을 지킨 한 주였습니다.")
        #expect(NotificationScheduler.weeklyBody(snapshot: nil, at: now)
            == "이번 주 러닝을 정리했어요 — 리포트를 열어보세요")
    }

    @Test("주간 본문 신선도 — 발송 시각 기준 48시간 이내 스냅샷만 수치를 싣는다 (이슈 #61)")
    func weeklyBodyStaleness() {
        // 스냅샷 생성 = now(2026-08-10T09:00Z)
        let snapshot = ReportSnapshot(generatedAt: now,
                                      headline: "안정적으로 리듬을 지킨 한 주였습니다.",
                                      suggestion: nil, weekKm: 21.4, runCount: 3)
        let fallback = "이번 주 러닝을 정리했어요 — 리포트를 열어보세요"

        // 47h59m 뒤 발송(2026-08-12T08:59Z) → 48h 이내라 신선 → 수치 포함
        let fresh = ISO8601DateFormatter().date(from: "2026-08-12T08:59:00Z")!
        #expect(!NotificationScheduler.isStale(snapshot, at: fresh))
        #expect(NotificationScheduler.weeklyBody(snapshot: snapshot, at: fresh)
            == "최근 7일 3회 · 21.4 km — 안정적으로 리듬을 지킨 한 주였습니다.")

        // 48h01m 뒤 발송(2026-08-12T09:01Z) → 48h 초과라 오래됨 → 기본 문구
        let stale = ISO8601DateFormatter().date(from: "2026-08-12T09:01:00Z")!
        #expect(NotificationScheduler.isStale(snapshot, at: stale))
        #expect(NotificationScheduler.weeklyBody(snapshot: snapshot, at: stale) == fallback)

        // 스냅샷이 없으면 발송 시각과 무관하게 기본 문구
        #expect(NotificationScheduler.weeklyBody(snapshot: nil, at: fresh) == fallback)
    }

    /// 주간 발송 시각 테스트용 — 시간대를 서울로 고정해 실행 환경과 무관하게 한다
    private var seoul: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }

    @Test("주간 발송 시각 — 다음 (요일, 시) 정각부터 7일 간격으로 정확한 날짜 4건 (이슈 #94)")
    func weeklyFireDates() {
        // now = 2026-08-10T09:00Z = 8/10(월) 18:00 KST. 일요일(1) 18시 → 다음 회차는 8/16(일)
        let dates = NotificationScheduler.weeklyFireDates(from: now, weekday: 1, hour: 18,
                                                          count: 4, calendar: seoul)
        #expect(dates.map(\.day) == [16, 23, 30, 6])      // 8/16·8/23·8/30·9/6
        #expect(dates.map(\.month) == [8, 8, 8, 9])
        #expect(dates.allSatisfy { $0.year == 2026 && $0.hour == 18 && $0.minute == 0 })
        // 요일 반복 트리거가 아니라 날짜 고정이어야 첫 회와 백업이 겹치지 않는다
        #expect(dates.allSatisfy { $0.weekday == nil })
    }

    @Test("주간 발송 시각 — 같은 요일 이전 시각이면 오늘, 정각이면 다음 주부터 센다 (이슈 #94)")
    func weeklyFireDatesSameDay() {
        // now = 8/10(월) 18:00 KST. 월요일(2) 19시 → 오늘 19시가 첫 회
        let later = NotificationScheduler.weeklyFireDates(from: now, weekday: 2, hour: 19,
                                                          count: 2, calendar: seoul)
        #expect(later.map(\.day) == [10, 17])
        // 월요일 18시 정각 = now → 이미 지난 것으로 보고 8/17부터
        let exact = NotificationScheduler.weeklyFireDates(from: now, weekday: 2, hour: 18,
                                                          count: 1, calendar: seoul)
        #expect(exact.map(\.day) == [17])
        // count 0 → 빈 목록
        #expect(NotificationScheduler.weeklyFireDates(from: now, weekday: 2, hour: 18,
                                                      count: 0, calendar: seoul).isEmpty)
    }

    @Test("주간 백업 id — 첫 회 id와 겹치지 않는 3건 (이슈 #94)")
    func weeklyBackupIds() {
        #expect(NotificationScheduler.weeklyBackupIds
            == ["runwrap.weekly.2", "runwrap.weekly.3", "runwrap.weekly.4"])
        #expect(!NotificationScheduler.weeklyBackupIds.contains(NotificationScheduler.weeklyId))
    }

    @Test("알림 권한 판정 — 허용·임시·앱 클립만 발송 가능, 거부·미결정은 불가 (이슈 #94)")
    func authorizationGranted() {
        #expect(NotificationScheduler.isGranted(.authorized))
        #expect(NotificationScheduler.isGranted(.provisional))
        #expect(NotificationScheduler.isGranted(.ephemeral))
        #expect(!NotificationScheduler.isGranted(.denied))
        #expect(!NotificationScheduler.isGranted(.notDetermined))
    }

    @Test("스냅샷 생성 — 최근 7일 거리 합과 횟수를 담는다")
    func snapshotMake() {
        let report = WeeklyReport(dateRange: "8.3 – 8.9", weeks: [],
                                  distance: nil, acwr: nil, efficiency: nil,
                                  streakWeeks: 2, weekRunCount: 3)
        // 창은 6일 전 자정 ~ now (weekRunCount와 같은 창, 이슈 #75).
        // 2일 전 10km는 창 안, 9일 전 10km는 창 밖.
        // 8일째 날(창 시작 1분 전) 7km는 롤링 7×86_400 안이지만 창 밖 → weekKm 10
        let windowStart = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 86_400))
        let runs = [RunSummary(id: UUID(), start: now.addingTimeInterval(-2 * 86_400),
                               durationSec: 3_000, distanceMeters: 10_000, avgHeartRate: 150),
                    RunSummary(id: UUID(), start: now.addingTimeInterval(-9 * 86_400),
                               durationSec: 3_000, distanceMeters: 10_000, avgHeartRate: 150),
                    RunSummary(id: UUID(), start: windowStart.addingTimeInterval(-60),
                               durationSec: 2_100, distanceMeters: 7_000, avgHeartRate: 150)]
        #expect(runs[2].start >= now.addingTimeInterval(-7 * 86_400))  // 전제: 롤링 7일 안
        let snapshot = ReportSnapshot.make(report: report, runs: runs, level: .intermediate, now: now)
        #expect(abs(snapshot.weekKm - 10) < 0.001)
        #expect(snapshot.runCount == 3)
        #expect(snapshot.headline == report.headline(level: .intermediate))
        #expect(snapshot.generatedAt == now)
    }

    @Test("수분 알람 판정 — 25°C 이상 + 토글 on + 시각 미경과일 때만 러닝 1시간 전")
    func hydrationAlarm() throws {
        // 로컬 시간대 기준 오전 9시로 고정 — 시간대와 무관하게 결정론적
        let base = ISO8601DateFormatter().date(from: "2026-08-10T00:00:00Z")!
        let now = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: base)!

        // 30°C 예보, 19시 러닝 → 18시 정각 알람
        let components = try #require(NotificationScheduler.hydrationAlarm(
            forecastMaxC: 30, runHour: 19, enabled: true, now: now))
        #expect(components.hour == 18)
        #expect(components.minute == 0)
        #expect(components.day == Calendar.current.component(.day, from: now))

        // 24.9°C 예보 → 기준 미달
        #expect(NotificationScheduler.hydrationAlarm(
            forecastMaxC: 24.9, runHour: 19, enabled: true, now: now) == nil)
        // 토글 off
        #expect(NotificationScheduler.hydrationAlarm(
            forecastMaxC: 30, runHour: 19, enabled: false, now: now) == nil)
        // 8시 러닝 → 알람 시각 7시가 이미 지났다 (now 9시)
        #expect(NotificationScheduler.hydrationAlarm(
            forecastMaxC: 30, runHour: 8, enabled: true, now: now) == nil)
    }

    @Test("수분 알람 본문 — 예보 기온을 정수로 반올림해 넣는다")
    func hydrationBody() {
        #expect(NotificationScheduler.hydrationBody(forecastMaxC: 30.4)
            == "오늘 30°C 예보 — 러닝 1시간 전 500ml 마셔두세요")
    }

    @Test("예보 디코드 — daily가 있으면 최고기온, 없으면 nil")
    func forecastDecode() throws {
        let withDaily = """
        {
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 },
          "daily": { "temperature_2m_max": [31.6] }
        }
        """
        #expect(try WeatherClient.decode(Data(withDaily.utf8)).forecastMaxC == 31.6)

        let withoutDaily = """
        {
          "current": { "temperature_2m": 29.4, "apparent_temperature": 33.1,
                       "relative_humidity_2m": 78, "wind_speed_10m": 3.6, "precipitation": 0.2 }
        }
        """
        #expect(try WeatherClient.decode(Data(withoutDaily.utf8)).forecastMaxC == nil)
    }

    @Test("캐시 왕복 — 저장한 스냅샷을 그대로 복원하고, 파일이 없으면 nil")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-cache-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(ReportCache.load(from: dir) == nil)

        let snapshot = ReportSnapshot(generatedAt: now,
                                      headline: "몸이 좋아지고 있는 한 주였습니다.",
                                      suggestion: "지금 리듬 그대로 이어가면 됩니다.",
                                      weekKm: 32.5, runCount: 4)
        ReportCache.save(snapshot, in: dir)
        #expect(ReportCache.load(from: dir) == snapshot)
    }

    @Test("캐시 삭제 — 데모 모드를 끄면 비운 캐시는 nil로 읽힌다 (이슈 #44)")
    func cacheClear() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-cache-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        ReportCache.save(ReportSnapshot(generatedAt: now, headline: "데모 헤드라인",
                                        suggestion: nil, weekKm: 30, runCount: 5), in: dir)
        #expect(ReportCache.load(from: dir) != nil)

        ReportCache.clear(in: dir)
        #expect(ReportCache.load(from: dir) == nil)
        // 파일이 없을 때 다시 지워도 조용히 넘어간다
        ReportCache.clear(in: dir)
    }
}
