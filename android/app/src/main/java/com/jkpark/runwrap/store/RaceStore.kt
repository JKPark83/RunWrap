package com.jkpark.runwrap.store

import android.content.Context
import com.jkpark.runwrap.engine.EngineJson
import com.jkpark.runwrap.engine.NotificationScheduler
import com.jkpark.runwrap.engine.NotifyKey
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RaceFavorites
import com.jkpark.runwrap.engine.RaceFile
import com.jkpark.runwrap.engine.RaceKey
import com.jkpark.runwrap.net.httpGet
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.jkpark.runwrap.engine.RaceStore as RaceStoreRules

/// 대회정보 스토어 — 원격 Races.json을 받아오고 캐시로 오프라인을 버틴다 (계획서 M13-3).
/// HealthKit과 무관하지만 스토어 계층 규칙(StateFlow + sealed State)을 따른다.
/// 네트워크는 수신 전용 — 건강 데이터를 포함해 어떤 사용자 데이터도 내보내지 않는다 (기획서 §6).
/// (Android: supportedSchemaVersion·needsRefresh는 엔진 `object RaceStore`에 있다)
class RaceStore(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Loaded(val file: RaceFile) : State
        data object Failed : State
    }

    private val context = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /// 직전 원격 갱신이 실패했는지 — 헤더 캡션이 "방금 새로 받진 못했어요"로 알린다 (#145)
    private val _lastRefreshFailed = MutableStateFlow(false)
    val lastRefreshFailed: StateFlow<Boolean> = _lastRefreshFailed.asStateFlow()
    /// 마지막 원격 갱신 성공 시각 — 탭 복귀·포그라운드 복귀 때 6시간 규칙으로 다시 받을지 정한다 (#145)
    var lastRefreshedAt: Instant? = null
        private set

    suspend fun load() {
        if (_state.value is State.Loaded) {
            // 이미 목록이 있으면 오래됐을 때만 원격을 다시 받는다 — 앱을 며칠 켜 둬도 어제 자료에 머물지 않게 (#145)
            if (RaceStoreRules.needsRefresh(lastRefreshedAt, Instant.now())) refresh()
            return
        }
        _state.value = State.Loading
        // 로컬(캐시 → 번들)을 먼저 보여주고 원격은 뒤에서 갱신 — 첫 화면이 네트워크를 기다리지 않는다
        withContext(Dispatchers.IO) { readLocal(context) }?.let {
            _state.value = State.Loaded(it)
            rescheduleRaceAlarms()
        }
        try {
            refresh()
        } finally {
            if (_state.value is State.Loading) _state.value = State.Failed
        }
    }

    /// 원격 갱신 — 실패하면 로컬 상태를 유지하고 실패만 표시한다 (당겨서 새로고침에서도 호출).
    /// 탭을 떠나 취소된 요청은 실패가 아니다 — CancellationException으로 빠져나가 표시하지 않는다
    suspend fun refresh() {
        val file = fetchRemote(context)
        if (file == null) {
            _lastRefreshFailed.value = true
            return
        }
        _state.value = State.Loaded(file)
        lastRefreshedAt = Instant.now()
        _lastRefreshFailed.value = false
        rescheduleRaceAlarms()
    }

    /// 즐겨찾기 대회 접수 알림 재예약 (이슈 #172) — 목록이 로드될 때, 즐겨찾기·설정 토글이 바뀔 때 부른다
    fun rescheduleRaceAlarms() = rescheduleRaceAlarms(context, (_state.value as? State.Loaded)?.file)

    companion object {
        /// GitHub Actions가 매일 크롤해 커밋하는 원본 파일의 raw URL (계획서 M13-4).
        /// main이 아니라 dev를 읽는다 — main은 보호 규칙(PR 필수) 때문에 크롤 봇이 직접
        /// 커밋할 수 없어서, 새벽 크롤이 실제로 쌓이는 브랜치는 dev다. 스키마가 앱보다
        /// 앞서가 디코드에 실패하거나 schemaVersion이 지원 범위를 넘으면 fetchRemote가 nil을
        /// 돌려줘 로컬 데이터로 안전하게 남는다.
        private const val remoteURL = "https://raw.githubusercontent.com/JKPark83/RunWrap/dev/ios/RunWrap/Races.json"

        private fun cacheFile(context: Context) = File(appSupportDir(context), "Races.json")

        /// 목록이 아직 없으면(`file` null) 켜진 알림을 빈 목록으로 지우지 않도록 건너뛴다 (꺼진 경우엔 거두기만 한다).
        /// 부팅 리시버도 캐시·번들 목록으로 이 경로를 부른다
        fun rescheduleRaceAlarms(context: Context, file: RaceFile?) {
            val settings = SettingsStore(context)
            val enabled = settings.bool(NotifyKey.raceEnabled)
            if (file == null && enabled) return
            val now = Instant.now()
            NotificationScheduler.rescheduleRaceAlarms(
                context,
                entries = file?.let { RaceEngine.entries(it.races, now) }.orEmpty(),
                favorites = RaceFavorites.decode(settings.string(RaceKey.favorites) ?: ""),
                enabled = enabled, now = now)
        }

        private suspend fun fetchRemote(context: Context): RaceFile? {
            // 어제 자 HTTP 캐시 방지 (iOS reloadIgnoringLocalCacheData). 60초는 iOS URLSession.shared 기본값
            val response = try {
                httpGet(URL(remoteURL), timeoutMillis = 60_000, useCaches = false)
            } catch (_: IOException) {
                return null
            }
            if (response.status != 200) return null
            val file = decode(response.body) ?: return null
            if (file.schemaVersion > RaceStoreRules.supportedSchemaVersion) return null
            withContext(Dispatchers.IO) { writeAtomically(cacheFile(context), response.body) }
            return file
        }

        /// 캐시와 번들 중 더 최신 것 — 앱 업데이트 직후엔 번들이 오래된 캐시보다 새것일 수 있다.
        /// generatedAt이 같은 형식(+09:00)의 ISO8601이라 문자열 비교가 곧 시간 비교다.
        fun readLocal(context: Context): RaceFile? {
            val cached = runCatching { cacheFile(context).readBytes() }.getOrNull()?.let(::decode)
            val bundled = runCatching { context.assets.open("Races.json").use { it.readBytes() } }.getOrNull()?.let(::decode)
            if (cached != null && bundled != null) return if (cached.generatedAt >= bundled.generatedAt) cached else bundled
            return cached ?: bundled
        }

        private fun decode(data: ByteArray): RaceFile? =
            try {
                EngineJson.decodeFromString<RaceFile>(data.decodeToString())
            } catch (_: IllegalArgumentException) {   // SerializationException 포함
                null
            }
    }
}

/// iOS `Data.write(to:options: .atomic)` — 임시 파일에 쓰고 바꿔 끼운다. 실패는 조용히 삼킨다 (캐시일 뿐이다)
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) temp.delete()
    } catch (_: IOException) {
        temp.delete()
    }
}
