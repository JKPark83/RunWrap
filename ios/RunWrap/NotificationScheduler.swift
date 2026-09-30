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
    // 즐겨찾기 대회 접수 알림 (이슈 #172)
    static let raceEnabled = "notify.race"
}

/// 로컬 알림 (계획서 M8) — 권한 요청 + 운동 직후 인사이트 / 주간 리포트 예약.
/// 전부 온디바이스 로컬 알림이다 — 원격 푸시·네트워크 전송이 없다.
/// 본문 빌더는 순수 static 함수로 분리해 테스트한다 (NotificationContentTests).
enum NotificationScheduler {
    /// 같은 id로 다시 add하면 기존 예약이 교체된다
    static let weeklyId = "runwrap.weekly"
    /// 주간 백업 알림 id — 첫 회 다음 주부터 3주치 (이슈 #94). 취소할 때 weeklyId와 함께 지운다
    static let weeklyBackupIds = (2...4).map { "\(weeklyId).\($0)" }
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

    /// 주간 본문 — 캐시 스냅샷이 있으면 횟수·거리·헤드라인, 없으면 기본 문구.
    /// 횟수·거리 모두 최근 7일(6일 전 자정 ~ 지금, 리포트 헤더와 같은 창) 기준이라
    /// 문구도 "최근 7일"로 맞춘다 (이슈 #21, #75).
    /// trigger는 알림이 실제로 울릴 시각 — 그때 스냅샷이 오래됐으면 수치 없이 기본 문구로 낸다 (이슈 #61)
    static func weeklyBody(snapshot: ReportSnapshot?, at trigger: Date) -> String {
        guard let snapshot, !isStale(snapshot, at: trigger) else {
            return "이번 주 러닝을 정리했어요 — 리포트를 열어보세요"
        }
        // 런린이는 주간 거리를 숫자 없이 문장만 본다 — 화면과 같은 게이트가 알림에도 걸린다 (이슈 #141)
        guard snapshot.showsDistanceNumbers else {
            return String(format: "최근 7일 %d회 — %@", snapshot.runCount, snapshot.headline)
        }
        return String(format: "최근 7일 %d회 · %.1f km — %@",
                      snapshot.runCount, snapshot.weekKm, snapshot.headline)
    }

    /// 주간 백업 본문 (이슈 #94) — 앱을 오래 안 열면 스냅샷이 낡으므로 수치를 싣지 않는다
    static let weeklyBackupBody = "이번 주 러닝은 어땠나요? 런미새 리포트에서 확인해 보세요"

    /// 스냅샷 신선도 판정 (이슈 #61) — 발송 시각 기준 maxAge(기본 48시간)를 넘겼으면 오래된 것.
    /// 앱을 며칠 안 열면 캐시가 갱신되지 않아 "최근 7일" 수치가 실제와 어긋난다 —
    /// 틀린 수치를 보내느니 기본 문구가 낫다. 정확히 maxAge인 경우는 아직 신선하다
    static func isStale(_ snapshot: ReportSnapshot, at trigger: Date,
                        maxAge: TimeInterval = 48 * 3_600) -> Bool {
        trigger.timeIntervalSince(snapshot.generatedAt) > maxAge
    }

    /// 주간 발송 시각 count건 — from 이후 첫 (요일, 시) 정각부터 7일 간격 (이슈 #94).
    /// 요일·시만 맞추는 트리거는 시작일을 못 정해 첫 회와 겹치므로, 회차마다 정확한
    /// 날짜(year/month/day/hour/minute)로 만들어 각각 repeats: false로 예약한다.
    /// 첫 회는 스냅샷 본문, 나머지는 백업 본문이다 — 1주 넘게 앱을 안 열어도 4주까지는 끊기지 않는다.
    /// 정확히 (요일, 시) 정각인 from은 이미 지난 것으로 보고 다음 주부터 센다 (nextTriggerDate와 같은 규약)
    static func weeklyFireDates(from: Date, weekday: Int, hour: Int, count: Int,
                                calendar: Calendar = .current) -> [DateComponents] {
        guard count > 0,
              let first = calendar.nextDate(after: from,
                                            matching: DateComponents(hour: hour, minute: 0, weekday: weekday),
                                            matchingPolicy: .nextTime) else { return [] }
        return (0..<count).compactMap { week in
            calendar.date(byAdding: .day, value: 7 * week, to: first).map {
                calendar.dateComponents([.year, .month, .day, .hour, .minute], from: $0)
            }
        }
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

    /// 대회 접수 알림 1건 (이슈 #172) — 예약 직전의 순수 값. fire는 KST 시간대가 담긴 날짜 성분
    struct RaceAlarm: Equatable {
        let id: String
        let title: String
        let body: String
        let fire: DateComponents
    }

    /// 대회 접수 알림 id 접두 — 재예약 때 이 접두의 대기 요청을 전부 거둔다
    static let raceIdPrefix = "runwrap.race."

    /// 즐겨찾기 대회 접수 알림 (순수 함수, 이슈 #172) — 대회마다 접수 시작일 오전 9시와
    /// 마감 3일 전 오전 9시(KST, 대회는 전부 국내 개최라 RaceEngine 달력). now 이후 시각만 낸다.
    /// 대회일이 가까운 순으로 알림이 1건이라도 있는 대회를 최대 limit개까지 — 기기의 대기 알림
    /// 상한(64건)을 주간·수분 알림과 나눠 쓰기 때문이다. 접수기간을 모르면 그 대회는 0건 (미노출 가드)
    static func raceAlarms(favorites: [RaceEngine.Entry], now: Date, limit: Int = 20) -> [RaceAlarm] {
        let calendar = RaceEngine.calendar
        func nineAM(_ day: Date?, minusDays: Int) -> DateComponents? {
            guard let day,
                  let shifted = calendar.date(byAdding: .day, value: -minusDays, to: day),
                  let fire = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: shifted),
                  fire > now else { return nil }
            return calendar.dateComponents([.timeZone, .year, .month, .day, .hour, .minute], from: fire)
        }
        let perRace: [[RaceAlarm]] = favorites
            .sorted { ($0.raceDate, $0.race.id) < ($1.raceDate, $1.race.id) }
            .map { entry in
                let race = entry.race
                var alarms: [RaceAlarm] = []
                if let fire = nineAM(RaceEngine.day(race.registerStart), minusDays: 0) {
                    alarms.append(RaceAlarm(id: "\(raceIdPrefix)\(race.id).start", title: "대회 접수 알림",
                                            body: "\(race.name) 접수가 오늘 시작돼요", fire: fire))
                }
                if let fire = nineAM(RaceEngine.day(race.registerEnd), minusDays: 3) {
                    alarms.append(RaceAlarm(id: "\(raceIdPrefix)\(race.id).end", title: "대회 접수 알림",
                                            body: "\(race.name) 접수 마감 3일 전이에요", fire: fire))
                }
                return alarms
            }
        return perRace.filter { !$0.isEmpty }.prefix(max(limit, 0)).flatMap { $0 }
    }

    // MARK: 권한·예약

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// 지금 알림을 보낼 수 있는지 (이슈 #94) — 시스템 설정에서 거부·해제했으면 false
    static func authorizationGranted() async -> Bool {
        isGranted(await UNUserNotificationCenter.current().notificationSettings().authorizationStatus)
    }

    /// 권한 상태 판정 (순수 함수) — 임시(provisional)·앱 클립(ephemeral) 허용도 발송 가능으로 본다
    static func isGranted(_ status: UNAuthorizationStatus) -> Bool {
        switch status {
        case .authorized, .provisional, .ephemeral: true
        default: false
        }
    }

    /// 포그라운드 복귀 동기화 (이슈 #94) — 시스템 알림 권한이 거부돼 있으면 켜진 토글을 모두 끈다.
    /// 토글은 켜져 있는데 알림은 조용히 안 나가는 상태를 남기지 않는다. 미결정(notDetermined)은 건드리지 않는다
    static func disableTogglesIfDenied() async {
        guard await UNUserNotificationCenter.current().notificationSettings()
            .authorizationStatus == .denied else { return }
        let defaults = UserDefaults.standard
        for key in [NotifyKey.workoutEnabled, NotifyKey.weeklyEnabled, NotifyKey.hydrationEnabled,
                    NotifyKey.raceEnabled] {
            defaults.set(false, forKey: key)
        }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [hydrationId])
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

    /// 주간 알림 재예약 — 설정을 읽어 끄면 전부 취소, 켜면 다음 (요일·시)부터 4주치를 예약한다.
    /// 첫 회(weeklyId)는 최신 캐시 스냅샷 본문, 이후 3주(weeklyBackupIds)는 수치 없는 백업 본문 (이슈 #94).
    /// 포그라운드 진입·설정 변경 때마다 다시 불려 첫 회 본문이 항상 최신 캐시를 반영하고 4주 창이 밀린다
    static func rescheduleWeekly() async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [weeklyId] + weeklyBackupIds)

        let defaults = UserDefaults.standard
        guard defaults.bool(forKey: NotifyKey.weeklyEnabled) else { return }
        let weekday = defaults.object(forKey: NotifyKey.weeklyWeekday) as? Int ?? 1
        let hour = defaults.object(forKey: NotifyKey.weeklyHour) as? Int ?? 18

        let fireDates = weeklyFireDates(from: Date(), weekday: weekday, hour: hour,
                                        count: 1 + weeklyBackupIds.count)
        for (index, components) in fireDates.enumerated() {
            let content = UNMutableNotificationContent()
            content.title = "주간 러닝 리포트"
            if index == 0 {
                // 신선도는 예약 시각이 아니라 실제 발송 시각 기준으로 본다 (이슈 #61)
                let fireDate = Calendar.current.date(from: components) ?? Date()
                content.body = weeklyBody(snapshot: ReportCache.load(), at: fireDate)
            } else {
                content.body = weeklyBackupBody
            }
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let id = index == 0 ? weeklyId : weeklyBackupIds[index - 1]
            try? await center.add(UNNotificationRequest(identifier: id,
                                                        content: content, trigger: trigger))
        }
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

    /// 대회 접수 알림 재예약 (이슈 #172) — 기존 `runwrap.race.` 요청을 전부 거두고,
    /// 토글이 켜져 있고 알림 권한이 있을 때만 즐겨찾기 대회분을 다시 건다.
    /// 대회 목록 로드·즐겨찾기 토글·설정 토글 때 불린다 (RaceStore.rescheduleRaceAlarms)
    static func rescheduleRaceAlarms(entries: [RaceEngine.Entry], favorites: [Int],
                                     enabled: Bool, now: Date) async {
        let center = UNUserNotificationCenter.current()
        let stale = await center.pendingNotificationRequests()
            .map(\.identifier).filter { $0.hasPrefix(raceIdPrefix) }
        center.removePendingNotificationRequests(withIdentifiers: stale)

        guard enabled, await authorizationGranted() else { return }
        let favoriteSet = Set(favorites)
        for alarm in raceAlarms(favorites: entries.filter { favoriteSet.contains($0.id) }, now: now) {
            let content = UNMutableNotificationContent()
            content.title = alarm.title
            content.body = alarm.body
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(dateMatching: alarm.fire, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: alarm.id,
                                                        content: content, trigger: trigger))
        }
    }
}
