package com.jkpark.runwrap.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jkpark.runwrap.engine.KeyValueStore
import java.io.File

/// iOS `UserDefaults.standard` 대응 — SharedPreferences 파일 `settings` 하나에 iOS와 같은 키 이름으로 저장한다.
/// 엔진에는 `KeyValueStore`로 넘기고, 화면에는 `rememberSetting`으로 `@AppStorage`처럼 키별 상태를 준다.
///
/// SharedPreferences에는 Double 타입이 없어 `putLong(value.toRawBits())`로 넣는다 — 그래서 저장된 Long은
/// 항상 Double 비트로 읽는다(Long setter는 두지 않는다). 다른 타입으로 넣은 키를 읽을 때는 UserDefaults처럼
/// 변환한다(Int로 넣은 키를 `double()`로 읽으면 변환). 변환 규칙은 아래 `pref*` 함수 한 곳에 있다.
class SettingsStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun string(key: String): String? = prefs.all[key] as? String
    override fun int(key: String): Int = prefInt(prefs.all[key])
    override fun double(key: String): Double = prefDouble(prefs.all[key])
    override fun bool(key: String): Boolean = prefBool(prefs.all[key])
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun set(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun set(key: String, value: Double) = prefs.edit().putLong(key, value.toRawBits()).apply()
    override fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()

    /// `@AppStorage(key) var x = default` 대응 — 키가 없으면 `default`, 쓰면 저장소에 바로 쓴다.
    /// 다른 화면·엔진이 같은 키를 바꿔도(지우기 포함) 변경 리스너로 따라간다.
    @Composable
    fun rememberSetting(key: String, default: String): MutableState<String> =
        rememberSetting(key, { string(key) ?: default }, { set(key, it) })

    @Composable
    fun rememberSetting(key: String, default: Int): MutableState<Int> =
        rememberSetting(key, { if (contains(key)) int(key) else default }, { set(key, it) })

    @Composable
    fun rememberSetting(key: String, default: Double): MutableState<Double> =
        rememberSetting(key, { if (contains(key)) double(key) else default }, { set(key, it) })

    @Composable
    fun rememberSetting(key: String, default: Boolean): MutableState<Boolean> =
        rememberSetting(key, { if (contains(key)) bool(key) else default }, { set(key, it) })

    /// 위 4종이 못 다루는 값(옵셔널 `Data?`·`Int?` 등)은 읽기/쓰기를 직접 준다
    @Composable
    fun <T> rememberSetting(key: String, read: () -> T, write: (T) -> Unit): MutableState<T> {
        val backing = remember(key) { mutableStateOf(read()) }
        DisposableEffect(key) {
            // key == null은 clear() 알림 (API 30+)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == null || changed == key) backing.value = read()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return remember(key) { PrefState(backing, write) }
    }
}

private class PrefState<T>(private val backing: MutableState<T>, private val write: (T) -> Unit) : MutableState<T> {
    override var value: T
        get() = backing.value
        set(newValue) {
            backing.value = newValue
            write(newValue)
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

// UserDefaults `integer/double/bool(forKey:)`의 타입 변환. 없는 키·문자열은 0/false.
// Long은 이 저장소에서 Double 비트뿐이다(위 클래스 설명).
internal fun prefInt(raw: Any?): Int = when (raw) {
    is Int -> raw
    is Long -> Double.fromBits(raw).toInt()   // UserDefaults처럼 소수점 이하를 버린다
    is Boolean -> if (raw) 1 else 0
    else -> 0
}

internal fun prefDouble(raw: Any?): Double = when (raw) {
    is Int -> raw.toDouble()
    is Long -> Double.fromBits(raw)
    is Boolean -> if (raw) 1.0 else 0.0
    else -> 0.0
}

internal fun prefBool(raw: Any?): Boolean = when (raw) {
    is Boolean -> raw
    is Int -> raw != 0
    is Long -> Double.fromBits(raw) != 0.0
    else -> false
}

/// 데모 모드 게이트 — 합성 데이터(DemoData)를 실기기에서도 켤 수 있게 하는 스위치
///
/// 왜 필요한가: 심사자 아이폰에는 애플워치 러닝 기록이 없다. 표본이 없으면 이 앱은
/// 설계상 지표를 아예 내지 않으므로(미노출 가드) 화면이 텅 비고, App Store 심사
/// 지침 2.1(App Completeness)에 걸린다. 애플은 이런 경우 앱에 내장된 데모 모드를
/// 허용하되 심사 노트에 켜는 방법을 밝히라고 요구한다 — 숨긴 기능이 아니어야
/// 지침 2.3.1에도 걸리지 않으므로 설정 화면에 그대로 노출한다.
///
/// 시뮬레이터는 워치 기록이 있을 수 없어 토글과 무관하게 항상 켜진 것으로 다룬다.
/// (Android: 에뮬레이터 자동 활성은 옮기지 않는다 — 에뮬레이터는 디버그 빌드의 HC 시더로 실제 조회 경로를 탄다)
object DemoMode {
    const val key = "demoModeEnabled"

    /// 설정 화면 토글의 저장값
    fun isEnabled(settings: KeyValueStore): Boolean = settings.bool(key)

    /// 합성 데이터를 쓸지 여부. Health Connect 조회 경로를 타기 전에 이것부터 확인한다.
    fun isActive(settings: KeyValueStore): Boolean = isEnabled(settings)
}

/// iOS Application Support 아래 `RunWrap` 폴더 대응 — 캐시·JSON 파일 자리. 없으면 만든다.
fun appSupportDir(context: Context): File = File(context.filesDir, "RunWrap").apply { mkdirs() }
