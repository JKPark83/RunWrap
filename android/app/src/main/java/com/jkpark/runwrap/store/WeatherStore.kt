package com.jkpark.runwrap.store

import android.content.Context
import com.jkpark.runwrap.engine.CurrentWeather
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.engine.NotificationScheduler
import com.jkpark.runwrap.engine.WeatherClient
import com.jkpark.runwrap.net.fetch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.jkpark.runwrap.engine.WeatherStore as WeatherStoreRules

/// 현재 위치 날씨 스토어 — 홈 판단 카드의 날씨 재료를 만든다 (계획서 M6).
///
/// 원래 HomeScreen이 `@State`로 들고 있던 로딩인데, 기동 스플래시가
/// "날씨가 준비될 때까지" 홈 노출을 미루려면 홈 밖(RootView)에서 진행 상태를
/// 알아야 해서 스토어로 올라왔다. 위치 권한 → 좌표 → 날씨 조회 → 수분 알람
/// 재예약까지가 `load()` 하나에 묶인 기동 로딩의 본체다.
/// (Android: 앱 단일 인스턴스(AppContainer). needsRefresh는 엔진 `object WeatherStore`에 있다.
///  위치 권한 요청은 화면 계약 몫이라 load() 전에 화면이 COARSE를 묻는다 — 미허용이면 바로 Denied)
class WeatherStore(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Loaded(val weather: CurrentWeather) : State
        data object Denied : State        // 위치 권한 거부 — 앱 안에서 다시 물을 수 없어 설정으로 보낸다
        data object Unavailable : State   // 위치 확인 실패 또는 네트워크 실패
    }

    private val context = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /// 마지막으로 잡힌 좌표 — 홈이 대기질(AirQualityStore) 조회에 쓴다.
    /// 날씨와 같은 위치 결론을 공유해 위치 조회를 두 번 하지 않는다
    private val _coordinate = MutableStateFlow<GeoPoint?>(null)
    val coordinate: StateFlow<GeoPoint?> = _coordinate.asStateFlow()
    /// 마지막으로 결론이 난 시각(성공·실패 모두) — 포그라운드 복귀 갱신의 기준 (이슈 #69).
    /// 실패도 찍는다: 값이 있는 .loaded에서 일시 실패했을 때 복귀마다 다시 두드리지 않게
    var fetchedAt: Instant? = null
        private set

    /// km 정확도면 충분하다 — 날씨 격자 자체가 그 단위다 (LocationProvider 주석 참조)
    private val location = LocationProvider(this.context)

    /// 휴대전화 전체 위치 서비스가 꺼졌는지 — LocationProvider 값을 비춘다 (이슈 #94).
    /// '오늘' 시트의 거부 안내가 앱 권한과 시스템 스위치를 구분해 그리는 재료 (이슈 #159)
    val servicesDisabled: StateFlow<Boolean> = location.servicesDisabled

    /// 결론이 났는가 — 스플래시 해제 조건. 성공뿐 아니라 거부·실패도 결론이다:
    /// 어차피 더 기다려도 값이 생기지 않으므로 홈을 열고 힌트 문구로 안내한다.
    val isSettled: Boolean
        get() = when (_state.value) {
            State.Idle, State.Loading -> false
            is State.Loaded, State.Denied, State.Unavailable -> true
        }

    /// 기동 로딩 — 위치의 결론(좌표·거부·실패)을 기다렸다가 날씨를 조회한다.
    /// 무한 대기의 안전망(타임아웃)은 스플래시를 쥔 RootView 몫이다.
    suspend fun load() {
        if (_state.value != State.Idle) return
        _state.value = State.Loading
        try {
            _state.value = resolve()
        } finally {
            // 취소돼도 .loading에 남기지 않는다 — iOS는 취소가 .unavailable 결론으로 끝난다(스플래시 해제)
            if (_state.value == State.Loading) _state.value = State.Unavailable
        }
    }

    /// 당겨서 새로고침 — 위치부터 다시 잡아 날씨를 갱신한다 (홈 pull-to-refresh).
    /// 직전 값을 유지한 채 결론만 바꾼다 — .loading을 거치면 isSettled가 풀려
    /// 스플래시(RootView.isBooting)가 되살아나고, 홈 날씨 타일도 값 대신 힌트로 튄다.
    suspend fun refresh() {
        // 기동 로딩이 아직 결론 전이면 그 결과를 기다리면 된다 — 중복 조회하지 않는다
        if (!isSettled) return
        val resolved = resolve()
        // 일시 실패(위치 미확정·네트워크)가 방금까지 보이던 값을 지우지 않는다 —
        // 직전 값이 더 정확한 근사다. 권한 거부는 의미 있는 결론이라 그대로 반영한다
        if (resolved == State.Unavailable && _state.value is State.Loaded) return
        _state.value = resolved
    }

    /// 포그라운드 복귀 갱신 (이슈 #69) — 결론이 낡았을 때만 refresh()를 부른다.
    /// 수분 알람 재예약이 날씨 조회에 묶여 있어, 앱을 켜 둔 채 날이 바뀌면 알람도 끊겼다.
    /// 값이 없는 결론(.denied·.unavailable)은 신선도와 무관하게 다시 시도한다 —
    /// 설정에서 방금 허용했거나 네트워크가 돌아왔을 수 있고, 보여 줄 값이 없으니 기다릴 이유도 없다.
    /// 여전히 거부면 LocationProvider.request()가 동기적으로 .denied를 내 비용이 거의 없다
    suspend fun refreshIfStale(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        when (_state.value) {
            State.Denied, State.Unavailable -> refresh()
            else -> if (WeatherStoreRules.needsRefresh(fetchedAt, now, zone)) refresh()
        }
    }

    /// 위치 요청부터 날씨 조회까지의 본체 — 결론(State)만 돌려주고 상태 전이는 호출부가 정한다
    private suspend fun resolve(): State {
        // 어느 경로로 끝나든 결론 시각을 남긴다 — refreshIfStale의 기준
        try {
            location.request()
            // LocationProvider는 콜백 기반이라 StateFlow로 결론만 소비한다
            val located = when (val conclusion = location.state.first {
                it != LocationProvider.State.Idle && it != LocationProvider.State.Loading
            }) {
                is LocationProvider.State.Located -> conclusion.point
                LocationProvider.State.Denied -> return State.Denied
                else -> return State.Unavailable
            }
            _coordinate.value = located
            return try {
                val current = WeatherClient.fetch(located.lat, located.lon)
                // 수분 알람 갱신 (계획서 M9) — 날씨를 받아온 이 시점이 당일분 예약 트리거다.
                // '오늘'이 시트가 된 뒤로 이 호출이 앱의 유일한 정기 트리거가 됐다
                NotificationScheduler.rescheduleHydration(context, current.forecastMaxC)
                State.Loaded(current)
            } catch (_: IOException) {
                State.Unavailable
            } catch (_: IllegalArgumentException) {   // 디코드 실패(SerializationException)
                State.Unavailable
            }
        } finally {
            fetchedAt = Instant.now()
        }
    }
}
