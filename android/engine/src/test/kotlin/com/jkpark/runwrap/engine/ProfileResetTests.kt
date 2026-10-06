package com.jkpark.runwrap.engine

import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 설정 '정보' 섹션의 순수 로직 검증 (이슈 #185) — 프로필 설정 초기화 대상 키, 피드백 메일 URL.
/// 시각에 의존하지 않는다 (UserDefaults·URL만 다룬다).
/// (Android: iOS 버전 표기 `FeedbackMail.systemVersion` 1건은 ios-only)
class ProfileResetTests {
    /// 격리 UserDefaults — 테스트마다 새 스위트
    private fun isolatedDefaults(): KeyValueStore = InMemoryKeyValueStore()

    @Test
    @DisplayName("초기화 대상 — 대회 목표·심박 기준 6개와 알림 설정 7개를 모두 포함한다")
    fun keysIncludeTargets() {
        val expected = listOf(ProfileKey.raceGoal, ProfileKey.raceGoalSec, ProfileKey.raceDate,
                              ProfileKey.hrMaxManual, ProfileKey.restingHRManual, ProfileKey.hrZoneMethod,
                              NotifyKey.workoutEnabled, NotifyKey.weeklyEnabled, NotifyKey.weeklyWeekday,
                              NotifyKey.weeklyHour, NotifyKey.hydrationEnabled, NotifyKey.runHour,
                              NotifyKey.raceEnabled)
        assertEquals(expected.toSet(), ProfileReset.keys.toSet())
        assertEquals(expected.size, ProfileReset.keys.size)   // 중복 없음
    }

    @Test
    @DisplayName("초기화 제외 — 설문 답(레벨·목적)·온보딩 시각·목표 이력·승급 거절·데모 모드·성장 사이클은 지우지 않는다")
    fun keysExcludePreserved() {
        // 레벨이 비면 첫 설문이 다시 열려 성장 사이클을 새로 쓴다 (RootView, OnboardingFlowScreen.persist)
        val preserved = listOf(ProfileKey.levelV2, ProfileKey.purposes, ProfileKey.onboardedAt,
                               ProfileKey.weeklyGoalChanges, ProfileKey.promotionDeclinedAt, DemoMode.key,
                               GrowthKey.cycleStartedAt, GrowthKey.maxStage, GrowthKey.cycleID,
                               NotifyKey.lastWorkoutStart)
        for (key in preserved) {
            assertFalse(ProfileReset.keys.contains(key), "${key}는 초기화 대상이 아니어야 한다")
        }
    }

    @Test
    @DisplayName("reset — 대상 키는 지우고 주간 목표는 기본 2회로 쓰며, 무관한 키는 남긴다")
    fun resetRemovesOnlyTargets() {
        val defaults = isolatedDefaults()
        for (key in ProfileReset.keys) defaults.set(key, "x")
        defaults.set(ProfileKey.weeklyGoal, 5)
        defaults.set(ProfileKey.levelV2, RunnerLevel.beginner.rawValue)
        defaults.set(ProfileKey.onboardedAt, 1_780_000_000.0)
        defaults.set("collection.x", "kept")

        ProfileReset.reset(defaults = defaults)

        for (key in ProfileReset.keys) {
            assertFalse(defaults.contains(key), "${key}가 남아 있다")
        }
        // 키를 지우면 스냅샷이 integer(forKey:)로 0을 백업한다 — 값으로 써서 막는다
        assertEquals(2, defaults.int(ProfileKey.weeklyGoal))
        assertEquals(RunnerLevel.beginner.rawValue, defaults.string(ProfileKey.levelV2))
        assertEquals(1_780_000_000.0, defaults.double(ProfileKey.onboardedAt))
        assertEquals("kept", defaults.string("collection.x"))
    }

    /// iOS `URLComponents(url:).queryItems` 대응 — 이름·값의 퍼센트 인코딩을 푼다 (`+`는 공백으로 바꾸지 않는다)
    private fun queryItems(url: URI): List<Pair<String, String>> =
        url.rawSchemeSpecificPart.substringAfter('?', "").split('&').map { item ->
            fun decode(text: String) = URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)
            decode(item.substringBefore('=')) to decode(item.substringAfter('=', ""))
        }

    @Test
    @DisplayName("피드백 메일 — mailto 주소·제목·본문(버전 한 줄)을 URLComponents로 인코딩한다")
    fun feedbackMailURL() {
        val url = FeedbackMail.url(appVersion = "1.3", build = "4",
                                   systemVersion = "17.5", deviceModel = "iPhone16,1")
        assertEquals("mailto", url.scheme)
        // 한글·공백·줄바꿈은 퍼센트 인코딩돼 원문 공백이 남지 않는다
        assertFalse(url.toString().contains(" "))
        assertEquals("sanaigon@gmail.com", url.rawSchemeSpecificPart.substringBefore('?'))
        val items = queryItems(url)
        assertEquals("[런미새] 피드백", items.firstOrNull { it.first == "subject" }?.second)
        val body = assertNotNull(items.firstOrNull { it.first == "body" }?.second)
        assertTrue(body.startsWith("앱 1.3 (4) / iOS 17.5 / iPhone16,1\n\n----\n"))
    }

    @Test
    @DisplayName("피드백 메일 — 본문에 건강 데이터 관련 단어가 없다")
    fun feedbackMailHasNoHealthData() {
        val body = FeedbackMail.body(appVersion = "1.3", build = "4", systemVersion = "17.5", deviceModel = "iPhone16,1")
        for (word in listOf("심박", "bpm", "거리", "km", "페이스", "HRV", "수면", "체중", "러닝 기록", "건강")) {
            assertFalse(body.contains(word), "본문에 '${word}'가 들어 있다")
        }
    }
}
