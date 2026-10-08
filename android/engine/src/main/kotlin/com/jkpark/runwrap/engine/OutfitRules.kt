package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId

/// 러닝 복장 아이템 — 룰(엔진)은 조합까지만 정하고 아이콘·라벨은 화면이 매핑한다
/// (RRTone과 같은 분리 — 엔진은 UI를 모른다)
enum class OutfitItem {
    singlet, shortSleeve, longSleeve, shorts, tights,
    jacket, gloves, windbreaker,
    waterproofCap, waterproofJacket,
    thermalTop, thermalBottom, beanie, neckWarmer,
    sunCap, sunglasses, sunscreen;

    val rawValue: String get() = name

    /// 노출 라벨 — 오늘 화면의 타일과 홈 판단 카드의 한 줄이 같은 표기를 쓰도록 한곳에 둔다.
    /// 아이콘 매핑은 여전히 화면 몫이다 (엔진은 UI를 모른다)
    val label: String
        get() = when (this) {
            singlet -> "싱글렛"
            shortSleeve -> "반팔 티"
            longSleeve -> "긴팔 티"
            shorts -> "반바지"
            tights -> "타이츠"
            jacket -> "자켓"
            gloves -> "장갑"
            windbreaker -> "바람막이"
            waterproofCap -> "방수 캡"
            waterproofJacket -> "방수 자켓"
            thermalTop -> "방한 상의"
            thermalBottom -> "방한 하의"
            beanie -> "비니"
            neckWarmer -> "넥워머"
            sunCap -> "러닝 캡"
            sunglasses -> "선글라스"
            sunscreen -> "선크림"
        }

    companion object {
        fun fromRawValue(rawValue: String): OutfitItem? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// 복장 룰 — "달릴 때 기온"(실제 기온 + runWarmthC) 구간을 기본으로 바람·강수·자외선·계절을 가산한다
/// (습도는 +10 기준에서 복장을 바꾸지 않는다 — humidityPct는 받기만 하고 쓰지 않는다. 16..<24 구간 주석 참조)
/// (확장 요구, 2026-08-12 · 이슈 #219). 구간은 계획서 M6 표, 가산 조건 출처:
/// - 기온 +10°C: "실제 기온보다 10°C 따뜻하다고 생각하고 입어라", "출발 후 5~10분만 쌀쌀할 정도로 입어라"
///   (러닝 레이어링 통념 — GQ Korea 러닝 레이어링 가이드, Brooks Running 기온별 복장 가이드).
///   몸은 5~10분만 달려도 열을 내 출발 기온 기준으로 입으면 금세 너무 덥다
/// - 자외선 보호 하한 UV 3: WHO Global Solar UV Index (Moderate부터 보호 권고)
/// - 고온다습·추위 레이어링: Nike 기온별 러닝 복장 가이드, 러닝 커뮤니티 겨울 복장 관례
/// 계절은 주입받은 now의 월로 판정한다 (기상학적 구분 — 여름 6~8월, 겨울 12~2월).
/// (Android: iOS `Calendar.current`의 월은 주입받은 `zone`으로 읽는다)
object OutfitRules {
    /// 바람막이를 더하는 풍속 하한 (m/s)
    const val windbreakerMs = 8.0
    /// 자외선 보호 세트를 더하는 UV 지수 하한 (WHO Moderate)
    const val sunProtectionUV = 3.0
    /// 달리면서 몸이 내는 열만큼 실제 기온에 더하는 보정 (°C) — "10°C 따뜻하다고 생각하고 입어라"
    const val runWarmthC = 10.0

    fun outfit(temperatureC: Double, humidityPct: Double, windMs: Double,
               precipitationMm: Double, weatherCode: Int?, uvIndex: Double?,
               now: Instant, zone: ZoneId): List<OutfitItem> {
        val raining = WeatherAdviceRules.isRaining(code = weatherCode, precipitationMm = precipitationMm)
        // 구간·가산 경계는 모두 이 "달릴 때 기온"으로 본다
        val runningC = temperatureC + runWarmthC
        val items: MutableList<OutfitItem>

        when {
            runningC >= 24 ->
                items = mutableListOf(OutfitItem.singlet, OutfitItem.shorts)
            runningC >= 16 && runningC < 24 ->
                // 습도는 복장을 바꾸지 않는다 — 이 구간은 실제 6~14°C라 고온다습(WeatherAdviceRules.isHumidHeat,
                // 19°C↑)에 들지 않고, 고온다습한 실제 기온은 이미 위 싱글렛 구간이다
                items = mutableListOf(OutfitItem.shortSleeve, OutfitItem.shorts)
            runningC >= 8 && runningC < 16 ->
                items = mutableListOf(OutfitItem.longSleeve, OutfitItem.tights)
            runningC >= 0 && runningC < 8 -> {
                items = mutableListOf(OutfitItem.longSleeve, OutfitItem.jacket, OutfitItem.tights, OutfitItem.gloves)
                // 겨울에는 같은 온도라도 귀 시림이 커 비니를 더한다
                if (isWinter(now, zone)) items.add(OutfitItem.beanie)
            }
            else ->  // 영하
                items = mutableListOf(OutfitItem.thermalTop, OutfitItem.thermalBottom, OutfitItem.beanie,
                                      OutfitItem.neckWarmer, OutfitItem.gloves)
        }

        // 강수 가산 — 따뜻하면 챙으로 비만 막고(방수 캡), 서늘하면 체온 유지까지(방수 자켓).
        // 젖은 옷은 달려서 낸 열을 빼앗으므로 +10 보정을 다 믿지 않고 달릴 때 20°C(실제 10°C) 미만은 자켓
        if (raining) {
            items.add(if (runningC >= 20) OutfitItem.waterproofCap else OutfitItem.waterproofJacket)
        }

        // 바람 가산 — 달릴 때 8~24°C(실제 −2~14°C)에서 바람막이.
        // 그 위 더위엔 겹옷이 역효과, 아래는 자켓이 겸한다
        if (windMs >= windbreakerMs && runningC >= 8 && runningC < 24) {
            items.add(OutfitItem.windbreaker)
        }

        // 자외선 가산 — UV 3 이상이면 캡·선글라스·선크림 세트.
        // 여름에 UV 값이 없으면(야간 제외 API 누락) 보호 세트를 기본 포함한다.
        // 달릴 때 8°C 미만(실제 −2°C 미만)은 비니 영역이라 제외, 우천 시에는 이미 해가 없어 제외.
        val uv = uvIndex ?: (if (isSummer(now, zone)) sunProtectionUV else 0.0)
        if (uv >= sunProtectionUV && runningC >= 8 && !raining) {
            items.addAll(listOf(OutfitItem.sunCap, OutfitItem.sunglasses, OutfitItem.sunscreen))
        }

        return items
    }

    /// 복장 카드 한 줄 — +10°C로 입으면 출발 직후는 춥다는 걸 미리 말해 둔다 (이슈 #219 §3 문구).
    /// 실제 20°C 미만일 때만 — 기온 점수가 10점(더위)으로 떨어지는 20°C부터는 싱글렛으로도 출발이 쌀쌀하지 않아
    /// "덥다"는 조언 카드와 모순된다
    fun startChillNote(temperatureC: Double): String? =
        if (temperatureC < 20) "출발 후 5~10분은 쌀쌀할 수 있어요 — 몸이 데워지면 딱 맞아요" else null

    private fun month(now: Instant, zone: ZoneId): Int = now.atZone(zone).monthValue

    private fun isSummer(now: Instant, zone: ZoneId): Boolean = month(now, zone) in 6..8
    private fun isWinter(now: Instant, zone: ZoneId): Boolean = month(now, zone) == 12 || month(now, zone) <= 2
}
