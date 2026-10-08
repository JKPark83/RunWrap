package com.jkpark.runwrap.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord

/// Health Connect 읽기 권한 묶음 — 기능별 분리 (제안 문서 §권한 전략)
///
/// 왜 나누나: 권한 시트에 항목이 한꺼번에 18개씩 뜨면 사용자가 내용을 읽지 않고
/// 거부하기 쉽다. 러닝 리포트에 필수인 묶음(core)과 회복 신호 묶음(recovery),
/// 그리고 운동 노력도 묶음(effort, 이슈 #178)만 첫 연결에 함께 요청한다 — 달리기 해석에 실제로 쓰는 것만 묻는다.
///
/// v0.7에서 몸무게(bodyMass)는 아예 요청하지 않는다 (기획서 §2 Q9 각주):
/// "체중 관리"를 목적으로 골라도 결과 수치(체중)가 아니라 행동(달리기)을 보상하므로
/// 몸무게를 추적할 이유가 없다. 묻지 않는 권한이 가장 안전한 권한이다.
///
/// (Android: HealthKit과 달리 HC는 허용된 권한을 조회할 수 있다(getGrantedPermissions).
/// 요청 시트는 스토어가 아니라 화면이 `PermissionController.createRequestPermissionResultContract()`로
/// 띄운다 — 이미 응답한 항목은 다시 묻지 않으므로 반복 요청해도 안전하다.
/// 여기 문자열은 모두 AndroidManifest.xml에 선언돼 있어야 시트에 뜬다.)
object HealthPermissions {
    /// 첫 연결(온보딩)에 요청 — 러닝 목록·세션 상세·심폐 체력의 필수 재료
    /// (Android: 러닝 다이내믹스(수직 진폭·접촉 시간·보폭)와 생년은 HC에 타입이 없어 묻지 않는다)
    val core: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        "android.permission.health.READ_EXERCISE_ROUTES",                  // 세션 상세: 경로 (HC 클라이언트에 읽기 상수가 없다)
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),             // 세션 상세: 케이던스
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class), // 세션 상세·공유 카드: 세션 소모 칼로리
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),  // 활동 칼로리를 안 쓰는 기록 앱의 폴백
        HealthPermission.getReadPermission(Vo2MaxRecord::class),            // 리포트: 심폐 체력(VO₂max) 추이
        HealthPermission.getReadPermission(PowerRecord::class),             // 주법 리포트: 러닝 파워
    )

    /// 체력 배터리 회복 신호 묶음 — 첫 연결에 core와 함께 요청
    /// (Android: 심박 회복(HRR) 타입이 HC에 없어 묻지 않는다)
    val recovery: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class), // 체력 배터리: 회복 신호 (RMSSD — iOS는 SDNN)
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),      // 수면 시간 + 단계·취침 규칙성 (제안 문서 A5)
    )

    /// 세션 상세: 운동 노력도 (이슈 #178) — HC에 대응 타입이 없어 빈 셋 (iOS 18 미만과 같은 취급)
    val effort: Set<String> = emptySet()

    /// 첫 연결·load()에서 쓰는 기본 요청 묶음.
    /// 30일보다 오래된 기록은 HISTORY 권한이 있어야 읽힌다 — 주간 추세(최소 8주)·완주 예측·최고 기록·월간 회고와
    /// 성장 XP 재계산(전체 기록, 이슈 #29)이 그 기간을 본다
    val standard: Set<String> = core + recovery + effort + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
}
