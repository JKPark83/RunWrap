package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/// 엔진 공용 기반 — iOS에서 Foundation이 암묵적으로 해 주던 것(UserDefaults, JSONEncoder 기본 동작, KST 고정)을
/// 한곳에 모아 엔진 파일들이 같은 방식으로 쓰게 한다.

/// iOS가 `TimeZone(identifier: "Asia/Seoul")`로 고정한 곳에 쓴다 (RaceEngine·RunWindowEngine·AirQualityEngine 등).
/// `Calendar.current`를 쓰던 곳은 이것이 아니라 주입받은 `zone`을 쓴다.
val KST: ZoneId = ZoneId.of("Asia/Seoul")

/// iOS `UserDefaults` 대응. 엔진은 이 인터페이스로만 설정을 읽고 쓴다(:app이 구현을 주입).
/// 없는 키의 반환값은 UserDefaults와 같다 — `integer(forKey:)` 0, `double(forKey:)` 0.0, `bool(forKey:)` false,
/// `string(forKey:)` nil. `object(forKey:) != nil` 판별은 `contains`로 옮긴다.
/// iOS에서 `Data`(JSON)로 넣던 값은 JSON 문자열로 넣는다.
interface KeyValueStore {
    fun string(key: String): String?
    fun int(key: String): Int
    fun double(key: String): Double
    fun bool(key: String): Boolean
    fun contains(key: String): Boolean
    fun set(key: String, value: String)
    fun set(key: String, value: Int)
    fun set(key: String, value: Double)
    fun set(key: String, value: Boolean)
    fun remove(key: String)
}

/// Swift `JSONEncoder`/`JSONDecoder` 기본 동작과 같은 설정.
/// - nil인 Optional은 키를 쓰지 않는다 → `explicitNulls = false`
/// - 기본값과 같은 프로퍼티도 쓴다 → `encodeDefaults = true`
/// - 모르는 키는 무시한다 → `ignoreUnknownKeys = true`
/// Swift에서 없는 Optional 키는 nil이므로, 옮긴 `@Serializable` 클래스의 nullable 필드에는 `= null` 기본값을 준다.
val EngineJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/// Swift `JSONEncoder`의 기본 `Date` 인코딩 — 2001-01-01T00:00:00Z 기준 초(Double).
/// iOS가 `Codable`로 파일에 쓰는 시각 필드에 `@Serializable(with = ReferenceDateInstantSerializer::class)`로 붙인다.
/// (UserDefaults에 넣는 시각은 이것이 아니라 `timeIntervalSince1970` Double이다 — 섞지 않는다.)
object ReferenceDateInstantSerializer : KSerializer<Instant> {
    private const val REFERENCE_EPOCH_SECONDS = 978_307_200L

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReferenceDateInstant", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeDouble((value.epochSecond - REFERENCE_EPOCH_SECONDS) + value.nano / 1e9)
    }

    override fun deserialize(decoder: Decoder): Instant {
        val seconds = decoder.decodeDouble()
        val whole = floor(seconds)
        return Instant.ofEpochSecond(
            whole.toLong() + REFERENCE_EPOCH_SECONDS,
            ((seconds - whole) * 1e9).swiftRounded().toLong(),
        )
    }
}

/// `Date.timeIntervalSince1970` / `Date(timeIntervalSince1970:)` — UserDefaults에 시각을 Double로 넣는 곳에 쓴다.
val Instant.timeIntervalSince1970: Double get() = epochSecond + nano / 1e9

fun instantSince1970(seconds: Double): Instant {
    val whole = floor(seconds)
    return Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * 1e9).swiftRounded().toLong())
}
