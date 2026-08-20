package com.jkpark.runwrap.seeder

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

/// 시더 조작 패널 (debug 전용) — 쓰기 권한 요청 → DemoData 주입/삭제.
/// MainActivity(메인 소스셋)가 참조하므로 릴리스에는 같은 시그니처의 빈 스텁이 있다
/// (src/release/.../SeederPanel.kt — RecordDumpScreen과 같은 패턴).
@Composable
fun SeederPanel(client: HealthConnectClient) {
    var writeGranted by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { result -> writeGranted = result.containsAll(HealthConnectSeeder.coreWritePermissions) }

    LaunchedEffect(Unit) {
        // 게이트는 필수 5종만 본다 — 기기 HC가 모르는 타입(피부 온도 등)은
        // 부여가 안 되고, seed()가 부여된 만큼만 넣는다
        writeGranted = client.permissionController.getGrantedPermissions()
            .containsAll(HealthConnectSeeder.coreWritePermissions)
    }

    Column {
        Text("시더 (debug 전용)", style = MaterialTheme.typography.titleSmall)
        Row {
            if (!writeGranted) {
                Button(onClick = { launcher.launch(HealthConnectSeeder.writePermissions) }) {
                    Text("쓰기 권한 요청")
                }
            } else {
                Button(onClick = {
                    scope.launch {
                        status = "주입 중…"
                        status = runCatching { "${HealthConnectSeeder.seed(client)}건 주입 완료" }
                            .getOrElse { "주입 실패: ${it.message}" }
                    }
                }) {
                    Text("시드 주입")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "삭제 중…"
                        status = runCatching { HealthConnectSeeder.wipe(client); "삭제 완료" }
                            .getOrElse { "삭제 실패: ${it.message}" }
                    }
                }) {
                    Text("시드 삭제")
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
