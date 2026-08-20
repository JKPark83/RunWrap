import SwiftUI

/// 설정 — 프로필(목적·레벨) 변경. 진입: 홈 헤더의 기어 아이콘 (계획서 M2).
/// 이후 마일스톤의 알림 토글·목표 입력도 이 화면에 추가된다.
struct SettingsScreen: View {
    @EnvironmentObject private var health: HealthStore
    @AppStorage(ProfileKey.levelV2) private var levelRaw = RunnerLevel.beginner.rawValue
    @AppStorage(ProfileKey.purposes) private var purposesRaw = ""
    @AppStorage(ProfileKey.weeklyGoal) private var weeklyGoal = 2
    @AppStorage(ProfileKey.raceGoal) private var raceGoalRaw = ""
    @AppStorage(ProfileKey.raceGoalSec) private var raceGoalSec = 0
    // 대회 날짜 — 0이면 미설정. Date를 직접 저장할 수 없어 timeIntervalSince1970로 둔다
    @AppStorage(ProfileKey.raceDate) private var raceDateRaw = 0.0
    /// 다시 진단받기 — 온보딩 설문을 시트로 다시 띄운다 (기획서 §7)
    @State private var isRediagnosing = false
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
                // 레벨은 설문 결과라 여기서 직접 고르지 않는다 — 다시 진단받아야 바뀐다.
                // 손으로 올릴 수 있게 하면 게이트가 의미를 잃고, 감당 못 할 지표를 보게 된다 (기획서 §7)
                section(title: "내 레벨") {
                    currentLevelRow
                }
                section(title: "러닝 목적") {
                    ForEach(RunPurpose.allCases, id: \.self) { purpose in
                        let selected = RunPurpose.decode(purposesRaw)
                        optionRow(label: purpose.label,
                                  caption: selected.contains(purpose) ? "선택됨" : " ",
                                  isSelected: selected.contains(purpose)) {
                            togglePurpose(purpose)
                        }
                    }
                }
                // 주간 목표 — 성장 XP의 주간 보너스 분모이자 홈 목표 칩의 기준 (기획서 §5)
                section(title: "주간 러닝 목표") {
                    weeklyGoalRow
                }
                // 대회 목표 묶음 (이슈 #21) — 레이스·기록·날짜를 한 토글 아래 모은다.
                // 끄면 대회 날짜만 지운다 — 레이스·기록은 훈련 가이드·도감이 계속 쓴다
                section(title: "대회 목표") {
                    toggleRow(label: "대회 목표 설정",
                              caption: "레이스·기록·날짜를 정하면 리포트에 D-day와 예상 완주 기록이 떠요",
                              isOn: raceDateBinding)
                }
                if raceDateRaw > 0 {
                    section(title: "목표 레이스") {
                        ForEach(RaceDistance.allCases, id: \.self) { race in
                            optionRow(label: race.label,
                                      caption: String(format: "%.1f km", race.km),
                                      isSelected: raceGoalRaw == race.rawValue) {
                                raceGoalRaw = race.rawValue
                            }
                        }
                    }
                    section(title: "목표 기록") { goalTimeRow }
                    // 대회 날짜 — 훈련 가이드의 D-day 주기화와 리포트 D-day의 기준 (§4.9)
                    section(title: "대회 날짜") { raceDateRow }
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
        // 다시 진단받기 — 설문을 처음부터 다시 받는다.
        // 이전 답을 프리필하지 않는 건 의도다: 원답은 저장하지 않고 판정 결과만 남기며,
        // 다시 진단하는 이유는 그때와 지금이 달라졌기 때문이다 (기획서 §7)
        .sheet(isPresented: $isRediagnosing) {
            OnboardingFlowScreen(onFinish: { isRediagnosing = false })
                .environmentObject(health)
        }
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

    /// 현재 레벨 + 다시 진단받기 — 레벨 변경의 유일한 경로 (기획서 §7)
    private var currentLevelRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(RunnerLevel(rawValue: levelRaw)?.label ?? RunnerLevel.beginner.label)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text("설문 결과로 정해져요. 실력이 늘면 앱이 먼저 승급을 제안합니다")
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text2)
            }
            Spacer(minLength: 8)
            Button("다시 진단") { isRediagnosing = true }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.brand)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    /// 주간 목표 횟수 — 1~7회. 성장 보너스와 홈 목표 칩이 이 값을 분모로 쓴다
    private var weeklyGoalRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("주 \(weeklyGoal)회")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text("채우면 새가 자라는 보너스를 받아요")
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text2)
            }
            Spacer(minLength: 8)
            Stepper("", value: $weeklyGoal, in: 1...7)
                .labelsHidden()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    /// 목적 복수 선택 토글 — 최소 1개는 남긴다 (전부 끄면 문장 강조점을 정할 수 없다)
    private func togglePurpose(_ purpose: RunPurpose) {
        var selected = RunPurpose.decode(purposesRaw)
        if let index = selected.firstIndex(of: purpose) {
            guard selected.count > 1 else { return }
            selected.remove(at: index)
        } else {
            selected.append(purpose)
        }
        purposesRaw = RunPurpose.encode(selected)
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

    /// 대회 목표 토글 (이슈 #21) — 켜면 대회일 기본값으로 8주 뒤(일반적인 최소 준비 기간)를
    /// 넣고, 끄면 대회 날짜만 지운다. 레이스·기록 값은 남겨 다음에 켤 때 그대로 복원된다
    private var raceDateBinding: Binding<Bool> {
        Binding(get: { raceDateRaw > 0 },
                set: { isOn in
                    raceDateRaw = isOn
                        ? Date().addingTimeInterval(8 * 7 * 86_400).timeIntervalSince1970
                        : 0
                })
    }

    /// 대회 날짜 선택 — 오늘부터 1년 안. 지난 날짜는 고를 수 없다 (주기화가 무의미해진다)
    private var raceDateRow: some View {
        DatePicker("대회일",
                   selection: Binding(
                       get: { Date(timeIntervalSince1970: raceDateRaw) },
                       set: { raceDateRaw = $0.timeIntervalSince1970 }),
                   in: Date()...Date().addingTimeInterval(366 * 86_400),
                   displayedComponents: .date)
            .datePickerStyle(.compact)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(RR.text)
            .tint(RR.brand)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
    }

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
                .rrCard()
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
