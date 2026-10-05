package com.jkpark.runwrap.engine

/// 설정 '정보' 섹션의 프로필 설정 초기화 (이슈 #185) — 목표·심박·알림 설정만 기본값으로 되돌린다.
///
/// 성장 사이클·도감·대회 기록·러닝화·iCloud 백업 메타는 건드리지 않는다 — "성장은 되돌리지 않는다"(기획서 §5).
/// 설문 답인 레벨·러닝 목적도 지우지 않는다:
/// - 레벨(`levelV2`)이 비면 RootView가 온보딩 미완료로 보고 첫 설문을 다시 연다. 첫 설문 저장은
///   성장 사이클(시작 시각·최고 단계·식별자)을 새로 쓰므로 XP를 잃는다 (이슈 #29, #44).
/// - 러닝 목적은 최소 1개가 불변식이다(설문·설정 모두). 둘 다 '다시 진단'으로만 바꾼다 (기획서 §7).
/// 온보딩 시각·주간 목표 변경 이력·승급 거절 시각도 보존한다.
object ProfileReset {
    /// 지우는(= @AppStorage 기본값으로 돌아가는) UserDefaults 키 — 대회 목표·심박 기준·알림 설정
    val keys: List<String> = listOf(
        ProfileKey.raceGoal, ProfileKey.raceGoalSec, ProfileKey.raceDate,
        ProfileKey.hrMaxManual, ProfileKey.restingHRManual, ProfileKey.hrZoneMethod,
        NotifyKey.workoutEnabled, NotifyKey.weeklyEnabled, NotifyKey.weeklyWeekday, NotifyKey.weeklyHour,
        NotifyKey.hydrationEnabled, NotifyKey.runHour, NotifyKey.raceEnabled,
    )

    /// 주간 목표 기본값 — 설정·홈의 @AppStorage 기본값, 설문 무경험자 기본값과 같다 (§2).
    /// 키를 지우지 않고 값을 쓰는 이유: 스냅샷(`ProgressSnapshot.readLocal`)은 `integer(forKey:)`로 읽어
    /// 키가 없으면 0을 백업하고, 복원 시 주 0회 목표가 된다
    const val defaultWeeklyGoal = 2

    /// (Android: iOS `defaults: UserDefaults = .standard` 기본값은 두지 않는다 — :app이 저장소를 넘긴다)
    fun reset(defaults: KeyValueStore) {
        for (key in keys) defaults.remove(key)
        defaults.set(ProfileKey.weeklyGoal, defaultWeeklyGoal)
    }
}
