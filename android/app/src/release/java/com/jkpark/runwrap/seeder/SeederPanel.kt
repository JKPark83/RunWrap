package com.jkpark.runwrap.seeder

import androidx.compose.runtime.Composable
import androidx.health.connect.client.HealthConnectClient

/// 릴리스 빌드용 빈 스텁 — 시더는 디버그 소스셋에만 있다 (계획서 M4).
/// MainActivity(메인 소스셋)가 참조하므로 모든 변형(variant)에 같은 시그니처가 필요하다.
@Composable
fun SeederPanel(client: HealthConnectClient) = Unit
