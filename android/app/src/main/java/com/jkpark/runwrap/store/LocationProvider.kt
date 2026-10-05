package com.jkpark.runwrap.store

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import com.jkpark.runwrap.engine.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jkpark.runwrap.engine.LocationProvider as LocationProviderRules

/// 현재 위치 1회 조회 — LocationManager.getCurrentLocation 래퍼 (계획서 M6).
/// HealthStore의 enum State 패턴을 따른다. 위치는 좌표를 쓰고 버릴 뿐 저장하지 않는다.
///
/// 정확도는 쓰는 쪽이 정한다. 날씨는 km면 충분하지만(격자 자체가 그 단위다),
/// 코스 탭의 주변 보급은 300m 밖 음수대를 짚어야 해서 km 오차로는 순서가 뒤집힌다.
///
/// (Android: play-services-location은 쓰지 않는다 — 플랫폼 FUSED_PROVIDER, 없으면 GPS/NETWORK.
///  권한 요청(ACCESS_COARSE/FINE)은 Activity 결과 계약이라 화면이 띄우고, 이 클래스는 권한을 확인만 한다 —
///  미허용이면 iOS의 notDetermined 다이얼로그 대신 바로 Denied다. 화면은 권한 응답 뒤 request()를 다시 부른다)
class LocationProvider(
    private val context: Context,
    /// 기본값은 날씨용 km 정확도. 보급 지점처럼 100m 단위가 의미를 갖는 쪽은 true(iOS NearestTenMeters)를 넘긴다
    private val fine: Boolean = false,
) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Located(val point: GeoPoint) : State
        data object Denied : State   // 권한 거부 — 화면은 안내 문구로 대체한다
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /// 받은 좌표가 흐린지(대략적 위치) — '정확한 위치'(FINE)가 허용되지 않았거나 오차가 큰 경우 true.
    /// 날씨는 km 단위라 이 값을 보지 않는다. 주변 보급처럼 100m가 의미를 갖는 쪽만 가드로 쓴다 (이슈 #74)
    private val _isCoarse = MutableStateFlow(false)
    val isCoarse: StateFlow<Boolean> = _isCoarse.asStateFlow()
    /// 휴대전화 전체 위치 서비스가 꺼졌는지 (이슈 #94) — 이때도 .denied로 와서,
    /// 안내 카드가 앱 권한이 아니라 시스템 스위치(Settings.ACTION_LOCATION_SOURCE_SETTINGS)를 가리키도록 구분한다
    private val _servicesDisabled = MutableStateFlow(false)
    val servicesDisabled: StateFlow<Boolean> = _servicesDisabled.asStateFlow()

    private val manager = context.getSystemService(LocationManager::class.java)
    private var pending: CancellationSignal? = null

    fun request() {
        _isCoarse.value = false
        val enabled = manager.isLocationEnabled
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) || !enabled) {
            _servicesDisabled.value = !enabled
            _state.value = State.Denied
            return
        }
        _state.value = State.Loading
        pending?.cancel()
        val signal = CancellationSignal().also { pending = it }
        val provider = when {
            manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            fine && granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val quality = if (fine) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        try {
            manager.getCurrentLocation(provider, LocationRequest.Builder(0).setQuality(quality).build(),
                                       signal, context.mainExecutor, ::deliver)
        } catch (_: SecurityException) {
            _state.value = State.Failed
        } catch (_: IllegalArgumentException) {   // 기기에 그 provider가 없다
            _state.value = State.Failed
        }
    }

    /// '정확한 위치'가 꺼진 사용자에게 정확한 위치를 청한 뒤 부른다 — iOS는 임시 정밀 위치(purpose key `NearbySupply`),
    /// Android는 화면이 FINE을 다시 요청해 12+ 정밀 업그레이드 다이얼로그를 띄운다.
    /// 허락받았으면 좌표를 다시 받고, 거절되면 isCoarse를 그대로 둬 안내 카드가 남는다.
    fun requestFullAccuracy() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) request()
    }

    private fun deliver(location: Location?) {
        // state보다 먼저 세운다 — state의 변화에서 검색 가드가 이 값을 본다
        _isCoarse.value = location?.let {
            LocationProviderRules.isCoarse(
                reducedAccuracy = !granted(Manifest.permission.ACCESS_FINE_LOCATION),
                // 정확도가 없는 좌표는 CLLocation의 음수 horizontalAccuracy(무효)와 같이 흐린 쪽으로 친다
                horizontalAccuracy = if (it.hasAccuracy()) it.accuracy.toDouble() else -1.0)
        } ?: false
        _state.value = location?.let { State.Located(GeoPoint(it.latitude, it.longitude)) } ?: State.Failed
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
