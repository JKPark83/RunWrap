package com.jkpark.runwrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.jkpark.runwrap.debug.RecordDumpScreen
import com.jkpark.runwrap.health.HealthPermissions
import kotlinx.coroutines.launch

/// M0 검증 스파이크 진입 화면 — HC 가용성 확인 → 권한 요청 → (디버그 빌드) 레코드 덤프.
/// 정식 온보딩·홈 화면은 M5에서 만들며, 이 화면은 그때 교체된다.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { RootScreen() }
            }
        }
    }
}

@Composable
private fun RootScreen() {
    val context = LocalContext.current
    val sdkStatus = remember { HealthConnectClient.getSdkStatus(context) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("런미새 M0 — Health Connect 검증", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // minSdk 34에서 HC는 플랫폼 내장이라 원칙적으로 항상 사용 가능해야 한다.
        // 불가라면 기기 문제이므로 상태 코드를 그대로 보여 준다.
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            Text("Health Connect 사용 불가 (status=$sdkStatus)")
            return@Column
        }

        val client = remember { HealthConnectClient.getOrCreate(context) }
        var granted by remember { mutableStateOf(emptySet<String>()) }
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { result -> granted = result }

        LaunchedEffect(Unit) {
            granted = client.permissionController.getGrantedPermissions()
        }

        Text("허용된 권한: ${granted.size} / ${HealthPermissions.standard.size}")
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { launcher.launch(HealthPermissions.standard) }) {
                Text("권한 요청")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch { granted = client.permissionController.getGrantedPermissions() }
            }) {
                Text("새로고침")
            }
        }
        Spacer(Modifier.height(16.dp))

        RecordDumpScreen(client)
    }
}
