import Foundation
import Testing
@testable import RunWrap

/// 설정 '정보' 섹션의 순수 로직 검증 (이슈 #185) — 프로필 설정 초기화 대상 키, 피드백 메일 URL.
/// 시각에 의존하지 않는다 (UserDefaults·URL만 다룬다).
struct ProfileResetTests {
    /// 격리 UserDefaults — 테스트마다 새 스위트
    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "test.\(UUID())")!
    }

    @Test("초기화 대상 — 대회 목표·심박 기준 6개와 알림 설정 7개를 모두 포함한다")
    func keysIncludeTargets() {
        let expected = [ProfileKey.raceGoal, ProfileKey.raceGoalSec, ProfileKey.raceDate,
                        ProfileKey.hrMaxManual, ProfileKey.restingHRManual, ProfileKey.hrZoneMethod,
                        NotifyKey.workoutEnabled, NotifyKey.weeklyEnabled, NotifyKey.weeklyWeekday,
                        NotifyKey.weeklyHour, NotifyKey.hydrationEnabled, NotifyKey.runHour,
                        NotifyKey.raceEnabled]
        #expect(Set(ProfileReset.keys) == Set(expected))
        #expect(ProfileReset.keys.count == expected.count)   // 중복 없음
    }

    @Test("초기화 제외 — 설문 답(레벨·목적)·온보딩 시각·목표 이력·승급 거절·데모 모드·성장 사이클은 지우지 않는다")
    func keysExcludePreserved() {
        // 레벨이 비면 첫 설문이 다시 열려 성장 사이클을 새로 쓴다 (RootView, OnboardingFlowScreen.persist)
        let preserved = [ProfileKey.levelV2, ProfileKey.purposes, ProfileKey.onboardedAt,
                         ProfileKey.weeklyGoalChanges, ProfileKey.promotionDeclinedAt, DemoMode.key,
                         GrowthKey.cycleStartedAt, GrowthKey.maxStage, GrowthKey.cycleID,
                         NotifyKey.lastWorkoutStart]
        for key in preserved {
            #expect(!ProfileReset.keys.contains(key), "\(key)는 초기화 대상이 아니어야 한다")
        }
    }

    @Test("reset — 대상 키는 지우고 주간 목표는 기본 2회로 쓰며, 무관한 키는 남긴다")
    func resetRemovesOnlyTargets() {
        let defaults = isolatedDefaults()
        for key in ProfileReset.keys { defaults.set("x", forKey: key) }
        defaults.set(5, forKey: ProfileKey.weeklyGoal)
        defaults.set(RunnerLevel.beginner.rawValue, forKey: ProfileKey.levelV2)
        defaults.set(1_780_000_000.0, forKey: ProfileKey.onboardedAt)
        defaults.set("kept", forKey: "collection.x")

        ProfileReset.reset(defaults: defaults)

        for key in ProfileReset.keys {
            #expect(defaults.object(forKey: key) == nil, "\(key)가 남아 있다")
        }
        // 키를 지우면 스냅샷이 integer(forKey:)로 0을 백업한다 — 값으로 써서 막는다
        #expect(defaults.integer(forKey: ProfileKey.weeklyGoal) == 2)
        #expect(defaults.string(forKey: ProfileKey.levelV2) == RunnerLevel.beginner.rawValue)
        #expect(defaults.double(forKey: ProfileKey.onboardedAt) == 1_780_000_000)
        #expect(defaults.string(forKey: "collection.x") == "kept")
    }

    @Test("피드백 메일 — mailto 주소·제목·본문(버전 한 줄)을 URLComponents로 인코딩한다")
    func feedbackMailURL() throws {
        let url = try #require(FeedbackMail.url(appVersion: "1.3", build: "4",
                                                systemVersion: "17.5", deviceModel: "iPhone16,1"))
        #expect(url.scheme == "mailto")
        // 한글·공백·줄바꿈은 퍼센트 인코딩돼 원문 공백이 남지 않는다
        #expect(!url.absoluteString.contains(" "))
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.path == "sanaigon@gmail.com")
        let items = components.queryItems ?? []
        #expect(items.first { $0.name == "subject" }?.value == "[런미새] 피드백")
        let body = try #require(items.first { $0.name == "body" }?.value)
        #expect(body.hasPrefix("앱 1.3 (4) / iOS 17.5 / iPhone16,1\n\n----\n"))
    }

    @Test("피드백 메일 — 본문에 건강 데이터 관련 단어가 없다")
    func feedbackMailHasNoHealthData() {
        let body = FeedbackMail.body(appVersion: "1.3", build: "4", systemVersion: "17.5", deviceModel: "iPhone16,1")
        for word in ["심박", "bpm", "거리", "km", "페이스", "HRV", "수면", "체중", "러닝 기록", "건강"] {
            #expect(!body.contains(word), "본문에 '\(word)'가 들어 있다")
        }
    }

    @Test("iOS 버전 표기 — patch가 0이면 빼고, 있으면 붙인다")
    func systemVersionLabel() {
        #expect(FeedbackMail.systemVersion(OperatingSystemVersion(majorVersion: 17, minorVersion: 5, patchVersion: 0)) == "17.5")
        #expect(FeedbackMail.systemVersion(OperatingSystemVersion(majorVersion: 18, minorVersion: 0, patchVersion: 1)) == "18.0.1")
    }
}
