package com.jkpark.runwrap.engine

import java.time.Instant

/// 대회정보 스토어의 순수 판정부 — iOS `RaceStore`(스토어 계층)에서 테스트하는 정적 함수만 옮긴다.
/// 원격 수신·캐시·상태는 :app의 store/net 몫이다.
/// 네트워크는 수신 전용 — 건강 데이터를 포함해 어떤 사용자 데이터도 내보내지 않는다 (기획서 §6).
object RaceStore {
    /// 앱이 읽을 수 있는 Races.json 스키마 버전 — 원격이 이보다 크면 캐시하지 않고 버린다 (#144)
    const val supportedSchemaVersion = 1

    /// 원격을 다시 받을 때인가 — 받은 적 없거나 maxAge(기본 6시간)보다 오래됐으면 true (#145).
    /// 배치는 하루 한 번이라 6시간이면 새벽 갱신을 당일 안에 따라잡는다.
    /// 스토어는 테스트하지 않으므로 판정만 순수 함수로 뺀다.
    fun needsRefresh(lastRefreshedAt: Instant?, now: Instant, maxAge: Double = 21_600.0): Boolean {
        if (lastRefreshedAt == null) return true
        return now.timeIntervalSince1970 - lastRefreshedAt.timeIntervalSince1970 > maxAge
    }
}
