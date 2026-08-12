import SwiftUI

/// 설정 — 프로필(목적·레벨) 변경. 진입: 리포트 홈 헤더의 기어 아이콘 (계획서 M2).
/// 이후 마일스톤의 알림 토글·목표 입력도 이 화면에 추가된다.
struct SettingsScreen: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage(ProfileKey.goal) private var goalRaw = RunGoal.training.rawValue
    @AppStorage(ProfileKey.level) private var levelRaw = RunLevel.experienced.rawValue
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0
    // 알림 (계획서 M8) — 기본값은 NotificationScheduler.rescheduleWeekly의 폴백과 같아야 한다
    @AppStorage(NotifyKey.workoutEnabled) private var workoutNotify = false
    @AppStorage(NotifyKey.weeklyEnabled) private var weeklyNotify = false
    @AppStorage(NotifyKey.weeklyWeekday) private var weeklyWeekday = 1
    @AppStorage(NotifyKey.weeklyHour) private var weeklyHour = 18
    @AppStorage(NotifyKey.hydrationEnabled) private var hydrationNotify = false
    @AppStorage(NotifyKey.runHour) private var runHour = 19
    // 데모 모드 — 워치 기록이 없는 기기(심사자 포함)에서 합성 데이터로 화면을 보여준다 (DemoMode)
    @AppStorage(DemoMode.key) private var demoMode = false

    /// 앱 내 개인정보 처리방침 — 심사 지침 5.1.1(i)이 요구하는 앱 내 접근 경로.
    /// 원본은 저장소의 docs/privacy.html이고, 같은 내용을 Vercel 정적 배포로 서비스한다
    /// (프로젝트 runmisae-privacy). 방침을 고치면 원본과 배포본을 함께 갱신한다.
    /// App Store Connect의 개인정보 처리방침 URL에도 같은 주소를 넣는다.
    private static let privacyPolicyURL = URL(string: "https://runmisae-privacy.vercel.app/privacy.html")!

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                section(title: "러닝 목적") {
                    ForEach(RunGoal.allCases, id: \.self) { goal in
                        optionRow(label: goal.label, caption: goal.caption,
                                  isSelected: goalRaw == goal.rawValue) {
                            goalRaw = goal.rawValue
                            // 몸무게 읽기는 다이어트 목적 전용 — 이때만 추가 권한 요청
                            if goal == .diet { Task { await health.requestDietAccess() } }
                        }
                    }
                }
                section(title: "러닝 레벨") {
                    ForEach(RunLevel.allCases, id: \.self) { level in
                        optionRow(label: level.label, caption: level.caption,
                                  isSelected: levelRaw == level.rawValue) {
                            levelRaw = level.rawValue
                        }
                    }
                }
                // 목표 레이스 — 훈련 가이드(계획서 M7)의 진단 대상. 없음이면 카드 자체가 꺼진다
                section(title: "목표 레이스") {
                    optionRow(label: "없음", caption: "훈련 가이드 카드를 끕니다",
                              isSelected: raceGoalRaw.isEmpty) {
                        raceGoalRaw = ""
                    }
                    ForEach(RaceDistance.allCases, id: \.self) { race in
                        optionRow(label: race.label,
                                  caption: String(format: "%.1f km", race.km),
                                  isSelected: raceGoalRaw == race.rawValue) {
                            raceGoalRaw = race.rawValue
                        }
                    }
                }
                if !raceGoalRaw.isEmpty {
                    section(title: "목표 기록") { goalTimeRow }
                }
                // 알림 — 로컬 알림 2종 (계획서 M8). 토글을 켤 때 시스템 권한을 요청한다
                section(title: "알림") {
                    toggleRow(label: "운동 직후 인사이트",
                              caption: "워치 러닝이 끝나면 요약을 보내드려요",
                              isOn: $workoutNotify)
                    toggleRow(label: "주간 리포트",
                              caption: "매주 정한 시각에 한 주를 정리해 드려요",
                              isOn: $weeklyNotify)
                    if weeklyNotify { weeklyTimeRow }
                    // 수분 알람 (계획서 M9) — 예보는 오늘 탭이 날씨를 조회할 때 확인한다
                    toggleRow(label: "더운 날 수분 알람",
                              caption: "최고기온 25°C 이상이면 러닝 1시간 전에 알려드려요",
                              isOn: $hydrationNotify)
                    if hydrationNotify { runHourRow }
                }

                // 데모 모드 (DemoMode) — 워치 기록이 없어도 화면을 둘러볼 수 있게 하는 경로.
                // 심사자용이자 신규 사용자용이며, 심사 노트에 켜는 방법을 그대로 밝힌다.
                section(title: "데모 모드") {
                    toggleRow(label: "샘플 데이터로 둘러보기",
                              caption: "워치 기록 없이도 모든 카드를 미리 볼 수 있어요. 건강 데이터는 읽지 않습니다",
                              isOn: $demoMode)
                }

                section(title: "개인정보") {
                    linkRow(label: "개인정보 처리방침",
                            caption: "건강 데이터는 기기 안에서만 처리합니다",
                            url: Self.privacyPolicyURL)
                }

                Text("리포트 카드의 구성과 문장 톤이 프로필에 맞춰 바뀝니다. 러닝 기록 자체는 그대로예요.")
                    .font(.system(size: 11.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.horizontal, 4)
            }
            .padding(.horizontal, 18)
            .padding(.top, 12)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .navigationTitle("설정")
        .navigationBarTitleDisplayMode(.inline)
        // 데모 모드를 켜면 합성 데이터로, 끄면 실제 HealthKit 기록으로 다시 채운다
        .onChange(of: demoMode) { _, _ in
            Task { await health.load() }
        }
        .onChange(of: workoutNotify) { _, isOn in
            if isOn { Task { _ = await NotificationScheduler.requestAuthorization() } }
        }
        .onChange(of: weeklyNotify) { _, isOn in
            Task {
                if isOn { _ = await NotificationScheduler.requestAuthorization() }
                await NotificationScheduler.rescheduleWeekly()
            }
        }
        .onChange(of: weeklyWeekday) { _, _ in
            Task { await NotificationScheduler.rescheduleWeekly() }
        }
        .onChange(of: weeklyHour) { _, _ in
            Task { await NotificationScheduler.rescheduleWeekly() }
        }
        .onChange(of: hydrationNotify) { _, isOn in
            if isOn {
                Task { _ = await NotificationScheduler.requestAuthorization() }
            } else {
                // 예약된 당일분이 있으면 거둔다 — 예보는 오늘 탭이 다시 조회할 때 확인
                Task { await NotificationScheduler.rescheduleHydration(forecastMaxC: nil) }
            }
        }
    }

    /// 외부 링크 행 — optionRow와 같은 레이아웃, 우측만 링크 표시
    private func linkRow(label: String, caption: String, url: URL) -> some View {
        Link(destination: url) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text)
                    Text(caption)
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                }
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right.square")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 알림 토글 행 — optionRow와 같은 레이아웃, 우측만 스위치
    private func toggleRow(label: String, caption: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text(caption)
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text2)
            }
            Spacer(minLength: 8)
            Toggle(label, isOn: isOn)
                .labelsHidden()
                .tint(RR.brand)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    /// 주로 달리는 시각 — 수분 알람은 이 시각 1시간 전에 온다 (기본 19시)
    private var runHourRow: some View {
        HStack(spacing: 8) {
            Text("주로 달리는 시각")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(RR.text)
            Spacer()
            Picker("러닝 시각", selection: $runHour) {
                ForEach(1..<24, id: \.self) { Text("\($0)시").tag($0) }
            }
        }
        .pickerStyle(.menu)
        .tint(RR.brand)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    /// 주간 알림 시각 — 요일·시 메뉴 피커 (기본 일 18:00)
    private var weeklyTimeRow: some View {
        HStack(spacing: 8) {
            Text("받는 시각")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(RR.text)
            Spacer()
            Picker("요일", selection: $weeklyWeekday) {
                ForEach(1...7, id: \.self) { day in
                    Text(Self.weekdayNames[day - 1] + "요일").tag(day)
                }
            }
            Picker("시각", selection: $weeklyHour) {
                ForEach(0..<24, id: \.self) { Text("\($0)시").tag($0) }
            }
        }
        .pickerStyle(.menu)
        .tint(RR.brand)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    /// UNCalendarNotificationTrigger의 weekday와 같은 순서 (1 = 일요일)
    private static let weekdayNames = ["일", "월", "화", "수", "목", "금", "토"]

    /// 목표 기록 입력 — 시:분:초 휠. 0:00:00이면 미입력으로 취급해 예측만 보여준다
    private var goalTimeRow: some View {
        HStack(spacing: 0) {
            timeWheel(unit: "시간", range: 0..<7,
                      value: Binding(get: { raceGoalSec / 3_600 },
                                     set: { raceGoalSec = $0 * 3_600 + raceGoalSec % 3_600 }))
            timeWheel(unit: "분", range: 0..<60,
                      value: Binding(get: { raceGoalSec % 3_600 / 60 },
                                     set: { raceGoalSec = raceGoalSec / 3_600 * 3_600
                                                        + $0 * 60 + raceGoalSec % 60 }))
            timeWheel(unit: "초", range: 0..<60,
                      value: Binding(get: { raceGoalSec % 60 },
                                     set: { raceGoalSec = raceGoalSec / 60 * 60 + $0 }))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    private func timeWheel(unit: String, range: Range<Int>, value: Binding<Int>) -> some View {
        Picker(unit, selection: value) {
            ForEach(range, id: \.self) { Text("\($0)\(unit)").tag($0) }
        }
        .pickerStyle(.wheel)
        .frame(height: 108)
        .clipped()
    }

    private func section(title: String, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(RR.text2)
                .padding(.horizontal, 4)
            VStack(spacing: 0) { rows() }
                .rrCard(radius: 20)
        }
    }

    private func optionRow(label: String, caption: String,
                           isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text)
                    Text(caption)
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                }
                Spacer(minLength: 8)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(isSelected ? RR.brand : RR.text3.opacity(0.5))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
