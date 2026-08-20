package com.jkpark.runwrap.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/// 가벼운 사용자 설정 — iOS `@AppStorage` 키 대응 (현재 iOS가 쓰는 키는 이 둘뿐이다).
/// DataStore Preferences 단일 파일("settings")로 관리한다.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        /// 온보딩에서 HC 연결(권한 요청)을 마쳤는지 — iOS "didConnectHealth"
        val didConnectHealth = booleanPreferencesKey("didConnectHealth")

        /// 코스 탭에서 마지막으로 보던 코스 이름 — iOS "lastCourseName"
        val lastCourseName = stringPreferencesKey("lastCourseName")
    }

    val didConnectHealth: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.didConnectHealth] ?: false }

    val lastCourseName: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.lastCourseName] ?: "" }

    suspend fun setDidConnectHealth(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.didConnectHealth] = value }
    }

    suspend fun setLastCourseName(value: String) {
        context.settingsDataStore.edit { it[Keys.lastCourseName] = value }
    }
}
