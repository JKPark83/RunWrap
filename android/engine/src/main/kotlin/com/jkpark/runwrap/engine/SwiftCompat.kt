package com.jkpark.runwrap.engine

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.withSign

/// Swift와 글자 단위로 같은 숫자 처리 — iOS 테스트 기대값을 그대로 옮기기 위한 헬퍼.
///
/// 왜 필요한가: Java/Kotlin 기본 동작이 Swift와 다르다.
/// - `String(format: "%.1f")`는 Double의 **정확한 이진값**을 기준으로 반올림하고, 정확히 중간이면 짝수 쪽이다
///   (0.25 → "0.2", 2.5 "%.0f" → "2", 0.35는 이진값이 0.3499…라 "0.3").
///   Java `String.format`은 HALF_UP이라 0.25 → "0.3"이 된다.
/// - Swift `.rounded()`는 중간값을 0에서 먼 쪽으로 보낸다(-2.5 → -3). Kotlin `roundToInt`는 -2.5 → -2.
///
/// 규칙 (android/engine/src/test/resources/swiftcompat_fixture.tsv — 실제 Swift 출력으로 검증):
/// - 음수 부호는 값의 부호 비트를 따른다: -0.0, -0.04 "%.1f" → "-0.0".
/// - NaN은 부호 플래그와 무관하게 "nan", 무한대는 "inf"/"-inf"("%+"면 "+inf").
/// - iOS가 실제로 쓰는 소수 지정자는 %.0f·%.1f·%.2f·%.3f·%+.0f·%+.1f뿐이다 — 폭·0 채움 등은 지원하지 않는다.
///   정수 지정자(%d·%02d·%04d)는 Java와 결과가 같으므로 `String.format`을 그대로 쓴다.

/// Swift `String(format: "%.<decimals>f", value)`, `plus`면 `"%+.<decimals>f"`.
fun fmt(value: Double, decimals: Int, plus: Boolean = false): String {
    if (value.isNaN()) return "nan"
    val negative = value.toRawBits() < 0
    val body = if (value.isInfinite()) "inf"
    else BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).abs().toPlainString()
    return when {
        negative -> "-$body"
        plus -> "+$body"
        else -> body
    }
}

/// Swift `x.rounded()` — 중간값은 0에서 먼 쪽(toNearestOrAwayFromZero). -0.4 → -0.0처럼 부호도 보존한다.
/// `floor(x + 0.5)`는 0.49999999999999994에서 1이 되므로 쓰지 않는다.
fun Double.swiftRounded(): Double {
    if (!isFinite()) return this
    val a = abs(this)
    val f = floor(a)
    return (if (a - f >= 0.5) f + 1 else f).withSign(this)
}

/// Swift `Int(x.rounded())`. Swift는 NaN·무한대·범위 밖에서 트랩하지만 여기서는 Kotlin `toInt()` 포화를 따른다 —
/// 엔진은 그런 값이 들어오기 전에 표본 가드로 걸러야 한다.
fun Double.swiftRoundedInt(): Int = swiftRounded().toInt()
