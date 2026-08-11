import Foundation
import UserNotifications

/// 알림 설정 키 (@AppStorage/UserDefaults 공용) — ProfileKey와 같은 패턴
enum NotifyKey {
    static let workoutEnabled = "notify.workout"          // 운동 직후 인사이트 토글
    static let weeklyEnabled = "notify.weekly"            // 주간 리포트 토글
    static let weeklyWeekday = "notify.weekly.weekday"    // 1 = 일 … 7 = 토 (Calendar 규약)
    static let weeklyHour = "notify.weekly.hour"          // 기본 18시
    /// 마지막으로 운동 알림을 보낸 세션의 시작 시각 (timeIntervalSince1970) — 중복 발송 가드
    static let lastWorkoutStart = "notify.workout.lastStart"
    // 수분 알람 (계획서 M9)
    static let hydrationEnabled = "notify.hydration"
    static let runHour = "notify.runHour"                 // 주로 달리는 시각, 기본 19시
}

/// 로컬 알림 (계획서 M8) — 권한 요청 + 운동 직후 인사이트 / 주간 리포트 예약.
/// 전부 온디바이스 로컬 알림이다 — 원격 푸시·네트워크 전송이 없다.
/// 본문 빌더는 순수 static 함수로 분리해 테스트한다 (NotificationContentTests).
enum NotificationScheduler {
    /// 같은 id로 다시 add하면 기존 예약이 교체된다
    static let weeklyId = "runwrap.weekly"
    static let workoutId = "runwrap.workout"
    static let hydrationId = "runwrap.hydration"

    // MARK: 본문 빌더 (순수 함수)

    /// 운동 직후 본문 — "8.2 km · 5′32″/km — 리포트에 반영됐어요"
    static func workoutBody(run: RunSummary) -> String {
        var parts: [String] = []
        if let km = run.distanceKm { parts.append(Format.km(km) + " km") }
        if let pace = run.paceSecPerKm { parts.append(Format.paceKm(pace)) }
        let lead = parts.isEmpty ? "오늘 러닝" : parts.joined(separator: " · ")
        return "\(lead) — 리포트에 반영됐어요"
    }

    /// 주간 본문 — 캐시 스냅샷이 있으면 횟수·거리·헤드라인, 없으면 기본 문구
    static func weeklyBody(snapshot: ReportSnapshot?) -> String {
        guard let snapshot else { return "이번 주 러닝을 정리했어요 — 리포트를 열어보세요" }
        return String(format: "이번 주 %d회 · %.1f km — %@",
                      snapshot.runCount, snapshot.weekKm, snapshot.headline)
    }

    /// 주간 트리거 시각 — 다음 (요일, 시)의 정각. repeats: false로 예약하고
    /// 포그라운드 진입마다 최신 캐시 본문으로 재예약한다 (반복 예약 아님)
    static func weeklyTrigger(weekday: Int, hour: Int) -> DateComponents {
        DateComponents(hour: hour, minute: 0, weekday: weekday)
    }

    /// 수분 알람 시각 판정 (순수 함수, 계획서 M9) — 조건을 모두 만족하면 오늘
    /// "러닝 1시간 전" 정각의 DateComponents, 아니면 nil.
    /// 조건: 토글 on · 예보 최고 ≥ 25°C(기획서 §4.11) · 알람 시각이 아직 지나지 않음.
    static func hydrationAlarm(forecastMaxC: Double, runHour: Int,
                               enabled: Bool, now: Date) -> DateComponents? {
        guard enabled, forecastMaxC >= 25.0, runHour >= 1 else { return nil }
        let calendar = Calendar.current
        guard let alarmDate = calendar.date(bySettingHour: runHour - 1, minute: 0,
                                            second: 0, of: now),
              alarmDate > now else { return nil }   // 이미 지난 시각이면 오늘분은 없다
        return calendar.dateComponents([.year, .month, .day, .hour, .minute], from: alarmDate)
    }

    /// 수분 알람 본문 — "오늘 30°C 예보 — 러닝 1시간 전 500ml 마셔두세요"
    static func hydrationBody(forecastMaxC: Double) -> String {
        String(format: "오늘 %.0f°C 예보 — 러닝 1시간 전 500ml 마셔두세요", forecastMaxC)
    }

    // MARK: 권한·예약

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// 운동 직후 인사이트 — 즉시 발송 (트리거 nil)
    static func sendWorkoutInsight(body: String) async {
        let content = UNMutableNotificationContent()
        content.title = "오늘의 러닝"
        content.body = body
        content.sound = .default
        try? await UNUserNotificationCenter.current()
            .add(UNNotificationRequest(identifier: workoutId, content: content, trigger: nil))
    }

    /// 주간 알림 재예약 — 설정을 읽어 끄면 취소, 켜면 다음 (요일·시) 1건만 예약.
    /// 포그라운드 진입·설정 변경 때마다 불려 본문이 항상 최신 캐시를 반영한다
    static func rescheduleWeekly() async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [weeklyId])

        let defaults = UserDefaults.standard
        guard defaults.bool(forKey: NotifyKey.weeklyEnabled) else { return }
        let weekday = defaults.object(forKey: NotifyKey.weeklyWeekday) as? Int ?? 1
        let hour = defaults.object(forKey: NotifyKey.weeklyHour) as? Int ?? 18

        let content = UNMutableNotificationContent()
        content.title = "주간 러닝 리포트"
        content.body = weeklyBody(snapshot: ReportCache.load())
        content.sound = .default
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: weeklyTrigger(weekday: weekday, hour: hour), repeats: false)
        try? await center.add(UNNotificationRequest(identifier: weeklyId,
                                                    content: content, trigger: trigger))
    }

    /// 수분 알람 재예약 (계획서 M9) — 오늘 탭이 날씨를 받아올 때마다 당일분 1건을
    /// 갱신한다. 백그라운드 예보 폴링은 하지 않는다 — 앱을 열지 않은 날은 알람도 없다.
    static func rescheduleHydration(forecastMaxC: Double?, now: Date = Date()) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [hydrationId])

        let defaults = UserDefaults.standard
        guard let forecastMaxC,
              let components = hydrationAlarm(
                  forecastMaxC: forecastMaxC,
                  runHour: defaults.object(forKey: NotifyKey.runHour) as? Int ?? 19,
                  enabled: defaults.bool(forKey: NotifyKey.hydrationEnabled),
                  now: now) else { return }

        let content = UNMutableNotificationContent()
        content.title = "수분 보충"
        content.body = hydrationBody(forecastMaxC: forecastMaxC)
        content.sound = .default
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: hydrationId,
                                                    content: content, trigger: trigger))
    }
}
