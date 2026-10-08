package com.jkpark.runwrap.store

import android.content.Context
import com.jkpark.runwrap.engine.CoursePOIFile
import com.jkpark.runwrap.engine.EngineJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream

/// 번들 CoursePOI.json(전국 급수·화장실·편의점 약 5.6만 건) 로더 — 코스 탭 전용.
/// HealthKit과 무관하지만 스토어 계층 규칙(StateFlow + sealed State)을 따른다.
/// 데이터는 tools/course-poi 파이프라인이 만들어 앱에 내장한다 — 조회는 전부 온디바이스 (기획서 §4.13).
/// (Android: 화면 소유 — Course ViewModel이 인스턴스를 가진다)
class CoursePOIStore(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Loaded(val file: CoursePOIFile) : State
        data object Failed : State
    }

    private val context = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @OptIn(ExperimentalSerializationApi::class)   // decodeFromStream (kotlinx.serialization 1.9 기준 실험 API)
    suspend fun load() {
        if (_state.value is State.Loaded) return
        _state.value = State.Loading
        // 수 MB JSON 디코드는 백그라운드에서 — 메인 스레드 프레임 드랍 방지
        val file = withContext(Dispatchers.IO) {
            try {
                // 스트림 디코드 — 6.6MB 본문을 통째로 문자열로 올리지 않아 메모리 피크를 줄인다
                context.assets.open("CoursePOI.json").use { EngineJson.decodeFromStream<CoursePOIFile>(it) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        _state.value = file?.let { State.Loaded(it) } ?: State.Failed
    }
}
