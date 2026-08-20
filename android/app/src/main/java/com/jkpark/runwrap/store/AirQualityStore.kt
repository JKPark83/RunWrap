package com.jkpark.runwrap.store

import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.GeoPoint
import com.jkpark.runwrap.health.DemoData
import com.jkpark.runwrap.health.DemoMode
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// 홈 날씨 타일의 미세·초미세 등급 — iOS `AirQualityStore.swift`의 최소 이식.
///
/// M5 범위: 에뮬레이터(데모 모드)는 합성 대기질을 보여주고, 실기기 경로는 Unavailable로
/// 접는다 — 실경로에 필요한 번들 측정소 JSON(AirStations.json)과 에어코리아 serviceKey
/// (gitignore 대상 번들 파일) 이식은 오픈 이슈다. 미노출 가드 원칙에 따라 등급이 없으면
/// 화면이 배지를 그리지 않으므로, Unavailable이어도 홈은 깨지지 않는다.
class AirQualityStore {
    sealed class State {
        data object Idle : State()
        data class Loaded(val quality: AirQuality) : State()
        data object Unavailable : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh(coordinate: GeoPoint?, now: Instant = Instant.now()) {
        if (DemoMode.isActive) {
            _state.value = State.Loaded(DemoData.airQuality(now))
            return
        }
        // 실경로(측정소 선택 + 에어코리아 조회)는 미이식 — 값을 지어내지 않는다
        _state.value = State.Unavailable
    }
}
