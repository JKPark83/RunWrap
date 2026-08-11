import HealthKit

/// HealthKit 읽기 권한 묶음 — 기능별 분리 (제안 문서 §권한 전략)
///
/// 왜 나누나: 권한 시트에 항목이 한꺼번에 18개씩 뜨면 사용자가 내용을 읽지 않고
/// 거부하기 쉽다. 러닝 리포트에 필수인 묶음(core)과 회복 신호 묶음(recovery)은
/// 첫 연결에 함께 요청하되, 몸무게(diet)는 다이어트 목표를 고른 사용자에게만
/// 요청해 "왜 몸무게를 달라고 하지?"라는 불신을 없앤다.
///
/// 읽기 권한은 허용 여부를 앱이 조회할 수 없으므로(애플 정책) 요청은 멱등적으로
/// 반복해도 안전하다 — 이미 응답한 항목은 시트가 다시 뜨지 않는다.
enum HealthPermissions {
    /// 첫 연결(온보딩)에 요청 — 러닝 목록·세션 상세·주법·심폐 체력의 필수 재료
    static let core: Set<HKObjectType> = [
        .workoutType(),
        HKSeriesType.workoutRoute(),                    // 세션 상세: 경로
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.heartRate),
        HKQuantityType(.stepCount),                     // 세션 상세: 케이던스
        HKQuantityType(.activeEnergyBurned),            // 다이어트 카드: 세션 소모 칼로리
        HKQuantityType(.vo2Max),                        // 리포트: 심폐 체력(VO₂max) 추이
        HKQuantityType(.runningVerticalOscillation),    // 주법 리포트: 수직 진폭 (기획서 §4.8, 실외 전용)
        HKQuantityType(.runningGroundContactTime),      // 주법 리포트: 지면 접촉 시간
        HKQuantityType(.runningStrideLength),           // 주법 리포트: 보폭 — 케이던스 우선 산출 재료
        HKQuantityType(.runningPower),                  // 주법 리포트: 러닝 파워
        HKCharacteristicType(.dateOfBirth),             // 심박 존: HRmax 추정 (Tanaka)
    ]

    /// 체력 배터리 회복 신호 묶음 — 첫 연결에 core와 함께 요청
    static let recovery: Set<HKObjectType> = [
        HKQuantityType(.heartRateVariabilitySDNN),      // 체력 배터리: 회복 신호
        HKQuantityType(.restingHeartRate),
        HKQuantityType(.respiratoryRate),
        HKQuantityType(.appleSleepingWristTemperature),
        HKQuantityType(.heartRateRecoveryOneMinute),    // 체력 배터리·심폐 체력: 심박 회복 (제안 문서 B1)
        HKCategoryType(.sleepAnalysis),                 // 수면 시간 + 단계·취침 규칙성 (제안 문서 A5)
    ]

    /// 다이어트 목표 선택 시에만 요청 — 목표와 무관한 사용자에게 몸무게를 묻지 않는다
    static let diet: Set<HKObjectType> = [
        HKQuantityType(.bodyMass),                      // 다이어트 카드: 몸무게 추이
    ]

    /// 첫 연결·load()에서 쓰는 기본 요청 묶음
    static var standard: Set<HKObjectType> { core.union(recovery) }
}
