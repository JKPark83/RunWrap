package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 테스트 공용 헬퍼 — 이 둘 말고는 iOS처럼 각 테스트 파일이 private 헬퍼를 따로 둔다.

/// iOS 테스트가 `Calendar.current`에 기대는 자리에 주입하는 zone.
/// iOS 테스트는 로컬(KST)과 CI(UTC) 양쪽에서 통과하므로 여기서도 둘 다 돌린다:
/// `./gradlew :engine:test`(UTC), `./gradlew :engine:test -PtestZone=Asia/Seoul`.
/// iOS 테스트가 zone을 직접 고정한 곳(Asia/Seoul·UTC)은 이것을 쓰지 말고 그대로 고정한다.
val testZone: ZoneId = ZoneId.of(System.getProperty("runwrap.testZone") ?: "UTC")

/// ISO8601 문자열 → Instant. "2026-08-10T09:00:00Z"와 "2026-08-10T09:00:00+09:00" 둘 다 받는다.
fun iso(text: String): Instant = OffsetDateTime.parse(text).toInstant()

/// iOS 테스트의 `UserDefaults(suiteName:)` 격리 대응.
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun string(key: String): String? = values[key] as? String
    override fun int(key: String): Int = (values[key] as? Number)?.toInt() ?: 0
    override fun double(key: String): Double = (values[key] as? Number)?.toDouble() ?: 0.0
    override fun bool(key: String): Boolean = values[key] as? Boolean ?: false
    override fun contains(key: String): Boolean = key in values
    override fun set(key: String, value: String) { values[key] = value }
    override fun set(key: String, value: Int) { values[key] = value }
    override fun set(key: String, value: Double) { values[key] = value }
    override fun set(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

class EngineSupportTests {
    @Test
    @DisplayName("Swift 기준일(2001-01-01) 초 인코딩 — 정수 초와 소수 초가 왕복한다")
    fun referenceDateRoundTrip() {
        // 2026-08-10T09:00:00Z = 1_786_352_400(1970 기준) − 978_307_200 = 808_045_200(2001 기준)
        val whole = iso("2026-08-10T09:00:00Z")
        // 숫자 표기(8.080452E8)는 Swift와 다르지만 양쪽 디코더가 같은 값으로 읽는다 — 값만 본다
        assertEquals(808_045_200.0, EngineJson.encodeToString(ReferenceDateInstantSerializer, whole).toDouble())
        assertEquals(whole, EngineJson.decodeFromString(ReferenceDateInstantSerializer, "808045200"))
        val fractional = whole.plusMillis(250)
        val encoded = EngineJson.encodeToString(ReferenceDateInstantSerializer, fractional)
        assertEquals(fractional, EngineJson.decodeFromString(ReferenceDateInstantSerializer, encoded))
        assertEquals(whole, instantSince1970(whole.timeIntervalSince1970))
    }
}
