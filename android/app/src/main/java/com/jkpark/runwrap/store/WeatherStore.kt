package com.jkpark.runwrap.store

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.jkpark.runwrap.engine.CurrentWeather
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.health.DemoMode
import com.jkpark.runwrap.net.WeatherClient
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/// 홈 브리핑용 현재 날씨 — iOS `WeatherStore.swift` 이식.
///
/// 위치는 Play Services 없이 플랫폼 `LocationManager`만 쓴다 (외부 의존성 0 유지).
/// minSdk 34라 FUSED_PROVIDER(API 31+)를 바로 쓸 수 있고, 날씨 조회는 좌표를
/// 소수 2자리로 반올림해 보내므로(WeatherClient) coarse 권한이면 충분하다.
///
/// iOS와 같은 규칙:
/// - `load()`는 Idle에서만 동작 — 화면 재진입마다 다시 부르지 않는다
/// - `refresh()`는 새 결과가 실패면 직전 Loaded 값을 지우지 않는다 (일시 실패 보호)
/// - 권한 거부는 그대로 Denied로 반영한다 — 화면이 "설정에서 허용" 유도 문구를 낸다
class WeatherStore(private val context: Context) {
    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data class Loaded(val weather: CurrentWeather) : State()
        data object Denied : State()
        data object Unavailable : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /// 마지막으로 확보한 좌표 — 대기질 측정소 선택(AirQualityStore)이 재사용한다
    private val _coordinate = MutableStateFlow<GeoPoint?>(null)
    val coordinate: StateFlow<GeoPoint?> = _coordinate.asStateFlow()

    val hasLocationPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun load() {
        if (_state.value != State.Idle) return
        refresh()
    }

    suspend fun refresh() {
        if (!hasLocationPermission) {
            _state.value = State.Denied
            return
        }
        val previous = _state.value
        if (previous !is State.Loaded) _state.value = State.Loading

        val weather = try {
            val point = resolvedCoordinate() ?: throw IllegalStateException("위치를 얻지 못했다")
            _coordinate.value = point
            WeatherClient.current(point.lat, point.lon)
        } catch (t: Exception) {
            null
        }
        _state.value = when {
            weather != null -> State.Loaded(weather)
            previous is State.Loaded -> previous  // 일시 실패가 직전 값을 지우지 않는다
            else -> State.Unavailable
        }
    }

    /// 날씨 조회에 쓸 좌표. 데모 모드(에뮬레이터)는 서울 고정 좌표를 쓴다 —
    /// GMS fused 프로바이더가 에뮬레이터 콘솔 geo fix를 소비하지 않는 경우가 흔해
    /// 위치만 우회하고, 네트워크·디코드 경로는 실코드 그대로 태운다.
    private suspend fun resolvedCoordinate(): GeoPoint? {
        if (DemoMode.isActive) return GeoPoint(37.57, 126.98)  // 서울시청 근방
        return currentLocation()?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /// 현재 위치 한 번 조회 — 10초 안에 못 얻으면 마지막 알려진 위치로 폴백.
    /// (에뮬레이터는 기본 목 위치가 lastKnown에만 있는 경우가 많다)
    private suspend fun currentLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(
                        LocationManager.FUSED_PROVIDER, signal, context.mainExecutor,
                    ) { location -> cont.resume(location) }
                } catch (t: Exception) {
                    cont.resume(null)
                }
            }
        }
        return fresh ?: lastKnownLocation(manager)
    }

    private fun lastKnownLocation(manager: LocationManager): Location? = try {
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (t: SecurityException) {
        null
    }
}
