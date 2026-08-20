package com.jkpark.runwrap.engine

import java.time.Instant

/// 러닝 복장 아이템 — 노출 라벨과 저장값 분리 (iOS rawValue 대응)
enum class OutfitItem(val storageValue: String) {
    SINGLET("singlet"), SHORT_SLEEVE("shortSleeve"), LONG_SLEEVE("longSleeve"),
    SHORTS("shorts"), TIGHTS("tights"), JACKET("jacket"), GLOVES("gloves"),
    WINDBREAKER("windbreaker"), WATERPROOF_CAP("waterproofCap"), WATERPROOF_JACKET("waterproofJacket"),
    THERMAL_TOP("thermalTop"), THERMAL_BOTTOM("thermalBottom"), BEANIE("beanie"),
    NECK_WARMER("neckWarmer"), SUN_CAP("sunCap"), SUNGLASSES("sunglasses"), SUNSCREEN("sunscreen");

    val label: String
        get() = when (this) {
            SINGLET -> "싱글렛"
            SHORT_SLEEVE -> "반팔 티"
            LONG_SLEEVE -> "긴팔 티"
            SHORTS -> "반바지"
            TIGHTS -> "타이츠"
            JACKET -> "자켓"
            GLOVES -> "장갑"
            WINDBREAKER -> "바람막이"
            WATERPROOF_CAP -> "방수 캡"
            WATERPROOF_JACKET -> "방수 자켓"
            THERMAL_TOP -> "방한 상의"
            THERMAL_BOTTOM -> "방한 하의"
            BEANIE -> "비니"
            NECK_WARMER -> "넥워머"
            SUN_CAP -> "러닝 캡"
            SUNGLASSES -> "선글라스"
            SUNSCREEN -> "선크림"
        }
}

/// 복장 추천 규칙 — 체감온도·습도·바람·비·자외선으로 러닝 복장을 조합한다.
/// 러닝 커뮤니티의 "달릴 때는 체감보다 10도 더운 옷" 관행을 체감온도 구간표로 옮긴 것.
/// iOS `OutfitRules.swift` 이식.
object OutfitRules {
    private const val WINDBREAKER_MS = 8.0     // 이 이상 바람이면 바람막이 (간절기 한정)
    private const val SUN_PROTECTION_UV = 3.0  // WHO "보통" 구간 시작 — 이 이상이면 햇빛 대비

    /// 추천 복장 목록 — 기본 복장(체감온도 구간) + 비·바람·자외선 조건 추가.
    /// uvIndex가 null이면 계절로 추정한다 (여름 한낮 러닝이 흔하므로 여름엔 보통(3.0) 취급)
    fun outfit(
        apparentC: Double,
        humidityPct: Double,
        windMs: Double,
        precipitationMm: Double,
        uvIndex: Double?,
        now: Instant,
    ): List<OutfitItem> {
        val raining = precipitationMm > 0
        val month = now.atZone(SEOUL).monthValue
        val isSummer = month in 6..8
        val isWinter = month == 12 || month == 1 || month == 2

        val items = mutableListOf<OutfitItem>()
        when {
            apparentC >= 24 -> items += listOf(OutfitItem.SINGLET, OutfitItem.SHORTS)
            apparentC >= 16 ->
                // 후텁지근하면(습도 80% 이상) 한 단계 가볍게
                items += if (humidityPct >= 80) {
                    listOf(OutfitItem.SINGLET, OutfitItem.SHORTS)
                } else {
                    listOf(OutfitItem.SHORT_SLEEVE, OutfitItem.SHORTS)
                }
            apparentC >= 8 -> items += listOf(OutfitItem.LONG_SLEEVE, OutfitItem.TIGHTS)
            apparentC >= 0 -> {
                items += listOf(OutfitItem.LONG_SLEEVE, OutfitItem.JACKET, OutfitItem.TIGHTS, OutfitItem.GLOVES)
                if (isWinter) items += OutfitItem.BEANIE
            }
            else -> items += listOf(
                OutfitItem.THERMAL_TOP, OutfitItem.THERMAL_BOTTOM,
                OutfitItem.BEANIE, OutfitItem.NECK_WARMER, OutfitItem.GLOVES,
            )
        }

        if (raining) {
            // 더울 땐 비를 맞고 뛰는 게 낫다 — 시야 확보용 캡만. 쌀쌀하면 저체온 대비 자켓
            items += if (apparentC >= 16) OutfitItem.WATERPROOF_CAP else OutfitItem.WATERPROOF_JACKET
        }
        if (windMs >= WINDBREAKER_MS && apparentC >= 8 && apparentC < 24) {
            items += OutfitItem.WINDBREAKER
        }
        val uv = uvIndex ?: if (isSummer) 3.0 else 0.0
        if (uv >= SUN_PROTECTION_UV && apparentC >= 8 && !raining) {
            items += listOf(OutfitItem.SUN_CAP, OutfitItem.SUNGLASSES, OutfitItem.SUNSCREEN)
        }
        return items
    }
}
