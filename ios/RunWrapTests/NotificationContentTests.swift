import Foundation
import Testing
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
        #expect(NotificationScheduler.weeklyBody(snapshot: snapshot)
            == "최근 7일 3회 · 21.4 km — 안정적으로 리듬을 지킨 한 주였습니다.")
        #expect(NotificationScheduler.weeklyBody(snapshot: nil)
            == "이번 주 러닝을 정리했어요 — 리포트를 열어보세요")
    }

    @Test("주간 트리거 — 요일·시가 정각 DateComponents로 옮겨진다")
    func weeklyTrigger() {
        let components = NotificationScheduler.weeklyTrigger(weekday: 1, hour: 18)
        #expect(components.weekday == 1)
        #expect(components.hour == 18)
        #expect(components.minute == 0)
    }

    @Test("스냅샷 생성 — 최근 7일 거리 합과 횟수를 담는다")
    func snapshotMake() {
        let report = WeeklyReport(dateRange: "8.3 – 8.9",
                                  distance: nil, acwr: nil, efficiency: nil,
                                  streakWeeks: 2, weekRunCount: 3)
        // 2일 전 10km는 7일 창 안, 9일 전 10km는 창 밖 → weekKm 10
        let runs = [RunSummary(id: UUID(), start: now.addingTimeInterval(-2 * 86_400),
                               durationSec: 3_000, distanceMeters: 10_000, avgHeartRate: 150),
                    RunSummary(id: UUID(), start: now.addingTimeInterval(-9 * 86_400),
                               durationSec: 3_000, distanceMeters: 10_000, avgHeartRate: 150)]
        let snapshot = ReportSnapshot.make(report: report, runs: runs, now: now)
        #expect(abs(snapshot.weekKm - 10) < 0.001)
        #expect(snapshot.runCount == 3)
        #expect(snapshot.headline == report.headline)
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
}
