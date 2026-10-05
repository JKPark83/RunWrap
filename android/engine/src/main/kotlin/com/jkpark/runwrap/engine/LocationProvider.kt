package com.jkpark.runwrap.engine

/// iOS LocationProvider.swift 중 순수 판정(`isCoarse`)만 엔진에 둔다.
/// 위치 1회 조회(권한 요청·좌표 수신·위치 서비스 스위치 확인)는 :app이 맡는다.
object LocationProvider {
    /// 좌표가 흐린지 판정 — 순수 함수라 테스트한다.
    /// 대략적 위치(reducedAccuracy)는 1~20km 단위로 뭉개져 온다. 주변 보급 반경이 1km라
    /// 오차가 수백 m만 넘어도 가까운 순서가 뒤집히므로 500m를 넘으면 흐리다고 본다.
    /// 음수 horizontalAccuracy는 좌표가 무효라는 뜻이라 역시 흐린 쪽으로 친다 (CLLocation 문서)
    /// (Android: `reducedAccuracy`는 "대략적 위치만 허용"(정밀 위치 권한 미허용)이면 true.
    ///  `Location.hasAccuracy()`가 false면 호출부가 horizontalAccuracy에 음수(-1)를 넘겨 흐린 쪽으로 친다)
    fun isCoarse(reducedAccuracy: Boolean,
                 horizontalAccuracy: Double,
                 threshold: Double = 500.0): Boolean =
        reducedAccuracy || horizontalAccuracy < 0 || horizontalAccuracy > threshold
}
