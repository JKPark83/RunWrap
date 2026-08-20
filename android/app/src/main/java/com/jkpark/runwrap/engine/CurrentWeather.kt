package com.jkpark.runwrap.engine

/// Open-Meteo 현재 날씨 스냅샷 — iOS `WeatherClient.swift`의 `CurrentWeather` 이식.
/// 네트워크 클라이언트(WeatherClient)는 M4 몫이라 여기서는 순수 데이터만 둔다 —
/// TodayVerdictEngine·OutfitRules가 엔진 계층에서 이 타입을 입력으로 받는다.
data class CurrentWeather(
    val temperatureC: Double,
    val apparentC: Double,       // 체감온도 — 복장·문구는 전부 이 값 기준
    val humidityPct: Double,
    val windMs: Double,
    val precipitationMm: Double, // 0보다 크면 "비" 취급
    /// 오늘 예보 최고기온 — 응답에 daily가 없으면 null
    val forecastMaxC: Double? = null,
    /// WMO 날씨 코드 — 아이콘·라벨 매핑은 화면 몫 (응답에 없으면 null)
    val weatherCode: Int? = null,
    /// 현재 자외선지수 (응답에 없으면 null)
    val uvIndex: Double? = null,
)
