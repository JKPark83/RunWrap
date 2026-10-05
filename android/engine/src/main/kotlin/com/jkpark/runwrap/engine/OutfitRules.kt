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

/// 복장 룰 — 체감온도 구간을 기본으로 습도·바람·강수·자외선·계절을 가산한다
/// (확장 요구, 2026-08-12). 구간은 계획서 M6 표, 가산 조건 출처:
/// - 자외선 보호 하한 UV 3: WHO Global Solar UV Index (Moderate부터 보호 권고)
/// - 고온다습·추위 레이어링: Nike 기온별 러닝 복장 가이드, 러닝 커뮤니티 겨울 복장 관례
/// 계절은 주입받은 now의 월로 판정한다 (기상학적 구분 — 여름 6~8월, 겨울 12~2월).
/// (Android: iOS `Calendar.current`의 월은 주입받은 `zone`으로 읽는다)
object OutfitRules {
    /// 바람막이를 더하는 풍속 하한 (m/s)
    const val windbreakerMs = 8.0
    /// 자외선 보호 세트를 더하는 UV 지수 하한 (WHO Moderate)
    const val sunProtectionUV = 3.0

    fun outfit(apparentC: Double, humidityPct: Double, windMs: Double,
               precipitationMm: Double, weatherCode: Int?, uvIndex: Double?,
               now: Instant, zone: ZoneId): List<OutfitItem> {
        val raining = WeatherAdviceRules.isRaining(code = weatherCode, precipitationMm = precipitationMm)
        val items: MutableList<OutfitItem>

        when {
            apparentC >= 24 ->
                items = mutableListOf(OutfitItem.singlet, OutfitItem.shorts)
            apparentC >= 16 && apparentC < 24 ->
                // 고온다습(≥80%)이면 땀이 증발하지 못해 한 단계 가볍게 — 반팔 대신 싱글렛
                items = if (humidityPct >= 80) mutableListOf(OutfitItem.singlet, OutfitItem.shorts)
                        else mutableListOf(OutfitItem.shortSleeve, OutfitItem.shorts)
            apparentC >= 8 && apparentC < 16 ->
                items = mutableListOf(OutfitItem.longSleeve, OutfitItem.tights)
            apparentC >= 0 && apparentC < 8 -> {
                items = mutableListOf(OutfitItem.longSleeve, OutfitItem.jacket, OutfitItem.tights, OutfitItem.gloves)
                // 겨울에는 같은 온도라도 귀 시림이 커 비니를 더한다
                if (isWinter(now, zone)) items.add(OutfitItem.beanie)
            }
            else ->  // 영하
                items = mutableListOf(OutfitItem.thermalTop, OutfitItem.thermalBottom, OutfitItem.beanie,
                                      OutfitItem.neckWarmer, OutfitItem.gloves)
        }

        // 강수 가산 — 따뜻하면 챙으로 비만 막고(방수 캡), 서늘하면 체온 유지까지(방수 자켓)
        if (raining) {
            items.add(if (apparentC >= 16) OutfitItem.waterproofCap else OutfitItem.waterproofJacket)
        }

        // 바람 가산 — 8~24°C에서 바람막이. 24°C 이상 더위엔 겹옷이 역효과, 8°C 미만은 자켓이 겸한다
        if (windMs >= windbreakerMs && apparentC >= 8 && apparentC < 24) {
            items.add(OutfitItem.windbreaker)
        }

        // 자외선 가산 — UV 3 이상이면 캡·선글라스·선크림 세트.
        // 여름에 UV 값이 없으면(야간 제외 API 누락) 보호 세트를 기본 포함한다.
        // 8°C 미만은 비니 영역이라 제외, 우천 시에는 이미 해가 없어 제외.
        val uv = uvIndex ?: (if (isSummer(now, zone)) sunProtectionUV else 0.0)
        if (uv >= sunProtectionUV && apparentC >= 8 && !raining) {
            items.addAll(listOf(OutfitItem.sunCap, OutfitItem.sunglasses, OutfitItem.sunscreen))
        }

        return items
    }

    private fun month(now: Instant, zone: ZoneId): Int = now.atZone(zone).monthValue

    private fun isSummer(now: Instant, zone: ZoneId): Boolean = month(now, zone) in 6..8
    private fun isWinter(now: Instant, zone: ZoneId): Boolean = month(now, zone) == 12 || month(now, zone) <= 2
}
