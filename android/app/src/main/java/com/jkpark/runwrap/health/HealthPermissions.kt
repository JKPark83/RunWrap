package com.jkpark.runwrap.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord

/// Health Connect 읽기 권한 묶음 — iOS `HealthPermissions.swift`의 기능별 분리 철학을 유지한다:
/// 달리기 해석에 실제로 쓰는 것만 묻는다 (러닝 코어 + 회복 신호).
///
/// iOS와의 차이 (계획서 §2.2):
/// - 주법 타입(수직 진폭·접촉 시간·보폭)은 HC에 레코드가 없어 요청 자체가 불가 — v1 제외.
/// - 생년월일 특성 타입이 없어 HRmax 추정 재료는 온보딩 직접 입력으로 대체.
/// - 경로(ExerciseRoute)는 권한이 아니라 세션별 동의(`ExerciseRouteRequestContract`)로 얻는다.
///
/// 여기 나열한 권한은 `app/src/main/AndroidManifest.xml`의 `<uses-permission>` 선언과
/// 반드시 1:1로 일치해야 한다 — 매니페스트에 없는 권한은 시트에서 조용히 거부된다.
object HealthPermissions {
    /// 러닝 리포트 필수 재료 — iOS `core` 대응
    val core: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsCadenceRecord::class), // READ_STEPS와 같은 권한
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(PowerRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
    )

    /// 체력 배터리 회복 신호 — iOS `recovery` 대응 (SDNN→RMSSD 대체, 계획서 오픈 이슈 #4)
    val recovery: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
    )

    /// 30일 초과 이력 읽기 — ACWR 4주·PR 8주 산출에 필요 (계획서 §2.3)
    val history: Set<String> = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)

    /// 첫 연결(온보딩)에서 한 번에 요청하는 기본 묶음
    val standard: Set<String> = core + recovery + history
}
