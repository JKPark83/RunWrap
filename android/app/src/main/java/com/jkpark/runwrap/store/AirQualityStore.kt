package com.jkpark.runwrap.store

import android.content.Context
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.AirQualityClient
import com.jkpark.runwrap.engine.AirQualityEngine
import com.jkpark.runwrap.engine.AirStation
import com.jkpark.runwrap.engine.AirStationFile
import com.jkpark.runwrap.engine.EngineJson
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.engine.ReferenceDateInstantSerializer
import com.jkpark.runwrap.net.fetch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import com.jkpark.runwrap.engine.WeatherStore as WeatherStoreRules

/// 현재 위치 대기질 스토어 — 최근접 측정소를 찾아 실시간 수치를 받아온다 (이슈 #8).
/// HealthKit과 무관하지만 스토어 계층 규칙(StateFlow + sealed State)을 따른다.
/// 위치는 직접 다루지 않는다 — TodayScreen이 위치 결론을 받은 시점에 좌표를 넘겨 호출한다.
///
/// 데이터를 내지 못하는 모든 경우(측정소 목록 없음·커버리지 밖·인증키 없음·통신장애·네트워크 실패)는
/// unavailable 하나로 접는다 — 화면은 이때 카드를 아예 그리지 않는다 (미노출 가드).
///
/// 호출량 방어 (이슈 #8, #83): data.go.kr 트래픽 한도(개발계정 500건/일)는 기기당이 아니라
/// **인증키당**이다 — 모든 사용자가 한 키를 나눠 쓰므로 사용자 수십 명이면 넘는다. 그래서 세 겹으로 막는다.
/// 1. 응답 캐시 — Application Support에 측정 시각 기준으로 캐시한다(AirQualityEngine.isFresh:
///    측정 정시 + 1시간 20분). 측정값 자체가 시간 단위 갱신이라 정보 손실이 없다.
/// 2. negative cache — 한도 초과·키 오류는 다음 KST 자정까지, 그 밖의 실패는 10분 동안
///    네트워크를 타지 않는다(AirQualityEngine.isBlocked). 실패한 키로 헛호출을 반복하지 않는다.
/// 3. 짧은 재시도 — 5xx·타임아웃만 클라이언트가 전체 10초 안에서 최대 2회 다시 부른다(AirQualityClient).
///
/// (Android: 화면 소유 — 각 화면 ViewModel이 인스턴스를 가진다. 응답 캐시는 파일이라 인스턴스끼리 공유된다.
///  iOS 시뮬레이터의 DemoData 분기는 옮기지 않는다 — 에뮬레이터도 실제 조회 경로를 탄다.
///  위젯 스냅샷용 `cachedFreshGrade`는 위젯이 ios-only라 옮기지 않는다)
class AirQualityStore(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Loaded(val quality: AirQuality) : State
        data object Unavailable : State
    }

    private val context = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /// 마지막으로 결론이 난 시각(성공·실패 모두) — 포그라운드 복귀 갱신의 기준 (이슈 #69)
    var fetchedAt: Instant? = null
        private set

    suspend fun load(latitude: Double, longitude: Double) {
        if (_state.value != State.Idle) return
        _state.value = State.Loading
        try {
            _state.value = resolve(latitude, longitude)
        } finally {
            // 화면 이탈로 취소돼도 .loading에 남기지 않는다 — iOS는 취소가 .unavailable 결론으로 끝난다
            if (_state.value == State.Loading) _state.value = State.Unavailable
        }
    }

    /// 당겨서 새로고침 — 직전 카드를 유지한 채 다시 조회한다 (loading을 거치면 카드가 깜빡인다).
    /// 응답 캐시(측정 시각 기준)는 그대로 적용되므로 잦은 새로고침이 트래픽 한도를 위협하지 않는다 —
    /// 측정값 자체가 시간 단위 갱신이라 정보 손실도 없다. 위치가 바뀌어 측정소가 달라졌을 때만 네트워크를 탄다
    suspend fun refresh(latitude: Double, longitude: Double) {
        _state.value = resolve(latitude, longitude)
    }

    /// 포그라운드 복귀 갱신 (이슈 #69) — 날씨와 같은 신선도 규칙(WeatherStore.needsRefresh)으로
    /// 낡았을 때만 refresh()한다. 값이 없는 .unavailable은 바로 다시 시도한다.
    /// 첫 조회 전·조회 중이면 홈의 load() 경로에 맡긴다. 응답 캐시·negative cache는 resolve 안에서 그대로 적용된다
    suspend fun refreshIfStale(latitude: Double, longitude: Double, now: Instant = Instant.now(),
                               zone: ZoneId = ZoneId.systemDefault()) {
        when (_state.value) {
            State.Idle, State.Loading -> return
            State.Unavailable -> {}
            is State.Loaded -> if (!WeatherStoreRules.needsRefresh(fetchedAt, now, zone)) return
        }
        refresh(latitude, longitude)
    }

    /// 최근접 측정소 탐색 → 캐시 확인 → 조회의 본체 — 결론(State)만 돌려준다
    private suspend fun resolve(latitude: Double, longitude: Double): State {
        // 어느 경로로 끝나든 결론 시각을 남긴다 — refreshIfStale의 기준
        try {
            val stations = withContext(Dispatchers.IO) { readStations(context) } ?: return State.Unavailable
            val station = AirQualityEngine.nearestStation(GeoPoint(latitude, longitude), stations)
                ?: return State.Unavailable

            // 같은 측정소의 아직 만료 전 측정값이면 재조회하지 않는다 — 한도 방어의 본체
            val now = Instant.now()
            val cached = readCache(context)
            val cachedQuality = cached?.quality
            if (cachedQuality != null && cachedQuality.stationName == station.name &&
                AirQualityEngine.isFresh(cachedQuality.dataTime, cached.fetchedAt, now)) {
                return State.Loaded(cachedQuality)
            }

            // negative cache — 한도는 인증키 단위라 측정소와 무관하게 막는다 (이슈 #83)
            if (AirQualityEngine.isBlocked(cached?.blockedUntil, now)) return State.Unavailable

            val serviceKey = serviceKey(context) ?: return State.Unavailable

            // 차단 기록 — 직전 측정값은 남겨 둔다. 차단이 풀린 뒤에도 신선도 규칙이 그대로 판정한다
            fun block(quotaExceeded: Boolean) = writeCache(context, Cache(
                fetchedAt = cached?.fetchedAt ?: now, quality = cachedQuality,
                blockedUntil = AirQualityEngine.blockedUntil(quotaExceeded, now)))

            val quality = try {
                AirQualityClient.fetch(station.name, serviceKey)
            } catch (e: IOException) {
                // 낡은 캐시로 대체하지 않는다 — 시간 단위로 변하는 값이라 "지금 공기"로 내밀 수 없다.
                // 화면 이탈 등 취소는 서버 탓이 아니다 — CancellationException은 여기 오지 않아 차단을 기록하지 않는다
                block(quotaExceeded = false)
                return State.Unavailable
            } catch (e: AirQualityClient.ClientError) {
                block(quotaExceeded = e == AirQualityClient.ClientError.quotaExceeded)
                return State.Unavailable
            }
            // 통신장애 측정소는 응답은 정상인데 수치가 전부 "-"로 온다 — 지표를 내지 않는다
            // 측정 시각이 3시간 넘게 낡은 값도 "지금 공기"로 내밀지 않는다 (이슈 #83).
            // 둘 다 곧 풀리지 않는 측정소 사정이라 10분 막는다 — 진입마다 헛호출하지 않게
            if (!AirQualityEngine.hasReading(quality) || !AirQualityEngine.isRecent(quality.dataTime, now)) {
                block(quotaExceeded = false)
                return State.Unavailable
            }
            writeCache(context, Cache(fetchedAt = now, quality = quality))
            return State.Loaded(quality)
        } finally {
            fetchedAt = Instant.now()
        }
    }

    // 응답 캐시 (Application Support, ReportCache와 같은 위치 정책)
    @Serializable
    private data class Cache(
        @Serializable(with = ReferenceDateInstantSerializer::class)
        val fetchedAt: Instant,
        /// 실패만 기록된 경우(첫 조회부터 한도 초과 등) nil
        val quality: AirQuality? = null,
        /// negative cache 해제 시각 (이슈 #83) — 성공하면 nil로 지워진다. 기존 캐시 파일엔 없어서 nil로 읽힌다
        @Serializable(with = ReferenceDateInstantSerializer::class)
        val blockedUntil: Instant? = null,
    )

    /// 인증키 파일 — 형식: {"serviceKey": "<디코딩 인증키>"}
    @Serializable
    private data class KeyFile(val serviceKey: String)

    companion object {
        /// 전국 측정소 목록 — air-stations.yml 배치가 갱신하는 번들 AirStations.json.
        /// 비어 있으면(아직 인증키로 한 번도 채우지 않은 상태) 기능 전체가 조용히 숨는다
        private fun readStations(context: Context): List<AirStation>? =
            readAsset<AirStationFile>(context, "AirStations.json")?.stations?.takeIf { it.isNotEmpty() }

        /// data.go.kr 인증키 — 번들 AirQualityKey.json(gitignore 대상, 커밋 금지)에서 읽는다.
        /// 파일이 없어도 앱은 성립한다 — 대기질만 숨는다
        private fun serviceKey(context: Context): String? =
            readAsset<KeyFile>(context, "AirQualityKey.json")?.serviceKey?.takeIf { it.isNotEmpty() }

        private inline fun <reified T> readAsset(context: Context, name: String): T? =
            try {
                EngineJson.decodeFromString<T>(context.assets.open(name).use { it.readBytes() }.decodeToString())
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun cacheFile(context: Context) = File(appSupportDir(context), "AirQuality.json")

        private fun readCache(context: Context): Cache? =
            try {
                EngineJson.decodeFromString<Cache>(cacheFile(context).readText())
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun writeCache(context: Context, cache: Cache) =
            writeAtomically(cacheFile(context), EngineJson.encodeToString(cache).encodeToByteArray())
    }
}
