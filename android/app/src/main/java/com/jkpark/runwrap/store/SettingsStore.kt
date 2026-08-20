package com.jkpark.runwrap.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/// 가벼운 사용자 설정 — iOS `@AppStorage` 키 대응. 키 문자열은 iOS `ProfileKey`/`GrowthKey`와
/// 동일하게 맞춘다 (크로스 참조 편의). DataStore Preferences 단일 파일("settings")로 관리한다.
/// 시각 값은 iOS의 timeIntervalSince1970(Double) 대신 epoch 초(Long)로 저장한다 — 0 = 미설정.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        /// 온보딩에서 HC 연결(권한 요청)을 마쳤는지 — iOS "didConnectHealth"
        val didConnectHealth = booleanPreferencesKey("didConnectHealth")

        /// 코스 탭에서 마지막으로 보던 코스 이름 — iOS "lastCourseName"
        val lastCourseName = stringPreferencesKey("lastCourseName")

        /// 러너 레벨 (RunnerLevel.storageValue) — 빈 문자열이면 온보딩 미완료
        val levelV2 = stringPreferencesKey("profile.levelV2")

        /// 러닝 목적 — RunPurpose.encode 형식("health,record"). 빈 문자열이면 미선택
        val purposes = stringPreferencesKey("profile.purposes")

        /// 생년 (예: 1990) — HRmax(Tanaka) 계산용. HC에는 프로필 데이터가 없어
        /// 온보딩에서 직접 입력받는다 (계획서 §2.2, iOS는 HealthKit dateOfBirth 사용). 0 = 미설정
        val birthYear = intPreferencesKey("profile.birthYear")

        /// 주간 러닝 목표 횟수 — 성장 XP 주간 보너스 분모이자 홈 목표 칩의 기준
        val weeklyGoal = intPreferencesKey("profile.weeklyGoal")

        /// 온보딩 완료 시각 (epoch 초)
        val onboardedAt = longPreferencesKey("profile.onboardedAt")

        /// 승급 제안을 거절한 시각 — 4주간 다시 묻지 않는다 (기획서 §3)
        val promotionDeclinedAt = longPreferencesKey("profile.promotionDeclinedAt")

        /// 목표 레이스 종목 (RaceDistance.storageValue) — 빈 문자열이면 미설정 → 훈련 가이드 미노출
        val raceGoal = stringPreferencesKey("profile.raceGoal")

        /// 목표 기록 (초) — 0이면 미입력으로 취급해 예측만 보여준다
        val raceGoalSec = intPreferencesKey("profile.raceGoalSec")

        /// 대회 날짜 (epoch 초) — 0이면 미설정. 있으면 훈련 가이드가 D-day 주기화로 처방을 조절
        val raceDate = longPreferencesKey("profile.raceDate")

        /// 현재 성장 사이클 시작 시각 (epoch 초). 첫 사이클 = 온보딩 시각
        val cycleStartedAt = longPreferencesKey("growth.cycleStartedAt")

        /// 이번 사이클에서 도달한 최고 단계 — "성장은 되돌리지 않는다"의 구현 장치
        val maxStage = intPreferencesKey("growth.maxStage")
    }

    val didConnectHealth: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.didConnectHealth] ?: false }

    val lastCourseName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.lastCourseName] ?: "" }

    val levelV2: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.levelV2] ?: "" }

    val purposes: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.purposes] ?: "" }

    val birthYear: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.birthYear] ?: 0 }

    val weeklyGoal: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.weeklyGoal] ?: 2 }

    val onboardedAt: Flow<Long> =
        context.settingsDataStore.data.map { it[Keys.onboardedAt] ?: 0L }

    val promotionDeclinedAt: Flow<Long> =
        context.settingsDataStore.data.map { it[Keys.promotionDeclinedAt] ?: 0L }

    val raceGoal: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.raceGoal] ?: "" }

    val raceGoalSec: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.raceGoalSec] ?: 0 }

    val raceDate: Flow<Long> =
        context.settingsDataStore.data.map { it[Keys.raceDate] ?: 0L }

    val cycleStartedAt: Flow<Long> =
        context.settingsDataStore.data.map { it[Keys.cycleStartedAt] ?: 0L }

    val maxStage: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.maxStage] ?: 1 }

    suspend fun setDidConnectHealth(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.didConnectHealth] = value }
    }

    suspend fun setLastCourseName(value: String) {
        context.settingsDataStore.edit { it[Keys.lastCourseName] = value }
    }

    suspend fun setLevelV2(value: String) {
        context.settingsDataStore.edit { it[Keys.levelV2] = value }
    }

    suspend fun setPurposes(value: String) {
        context.settingsDataStore.edit { it[Keys.purposes] = value }
    }

    suspend fun setBirthYear(value: Int) {
        context.settingsDataStore.edit { it[Keys.birthYear] = value }
    }

    suspend fun setWeeklyGoal(value: Int) {
        context.settingsDataStore.edit { it[Keys.weeklyGoal] = value }
    }

    suspend fun setOnboardedAt(value: Long) {
        context.settingsDataStore.edit { it[Keys.onboardedAt] = value }
    }

    suspend fun setPromotionDeclinedAt(value: Long) {
        context.settingsDataStore.edit { it[Keys.promotionDeclinedAt] = value }
    }

    suspend fun setRaceGoal(value: String) {
        context.settingsDataStore.edit { it[Keys.raceGoal] = value }
    }

    suspend fun setRaceGoalSec(value: Int) {
        context.settingsDataStore.edit { it[Keys.raceGoalSec] = value }
    }

    suspend fun setRaceDate(value: Long) {
        context.settingsDataStore.edit { it[Keys.raceDate] = value }
    }

    suspend fun setCycleStartedAt(value: Long) {
        context.settingsDataStore.edit { it[Keys.cycleStartedAt] = value }
    }

    suspend fun setMaxStage(value: Int) {
        context.settingsDataStore.edit { it[Keys.maxStage] = value }
    }
}
