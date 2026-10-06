package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId

/// iOS WeatherStore.swift 중 순수 판정(`needsRefresh`)만 엔진에 둔다.
/// 위치 권한 → 좌표 → 날씨 조회 → 수분 알람 재예약으로 이어지는 기동 로딩 본체(상태 전이)는 :app store/WeatherStore.kt가 맡는다.
object WeatherStore {
    /// 다시 받아야 하는가 — 결론이 없거나, maxAge(기본 30분)를 넘겼거나, 날짜가 바뀌었을 때.
    /// 날짜 조건은 당일 최고기온 기반 수분 알람(계획서 M9)이 날마다 새로 예약돼야 해서다.
    /// 시계가 뒤로 가 fetchedAt이 미래면 낡은 것으로 본다 (AirQualityEngine.isFresh와 같은 규칙)
    /// (Android: iOS `calendar: Calendar = .current`는 `zone`으로 주입받는다)
    fun needsRefresh(fetchedAt: Instant?, now: Instant, zone: ZoneId,
                     maxAge: Double = 30.0 * 60): Boolean {
        if (fetchedAt == null) return true
        val age = now.timeIntervalSince1970 - fetchedAt.timeIntervalSince1970
        if (!(age >= 0 && age < maxAge)) return true
        return fetchedAt.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate()
    }
}
