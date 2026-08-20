package com.jkpark.runwrap.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.health.HealthConnectStore
import com.jkpark.runwrap.health.HealthPermissions
import com.jkpark.runwrap.seeder.SeederPanel
import com.jkpark.runwrap.ui.Format
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/// M4 검증 화면 (debug 전용) — 권한 요청 → 시더 → 임시 세션 목록 / 레코드 덤프.
/// M5에서 MainActivity가 정식 화면으로 바뀌면서 여기로 밀려났다.
/// 실행: adb shell am start -n com.jkpark.runwrap/.debug.DevActivity
class DevActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { DevScreen() }
            }
        }
    }
}

@Composable
private fun DevScreen() {
    val context = LocalContext.current
    val sdkStatus = remember { HealthConnectClient.getSdkStatus(context) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("런미새 M4 — 세션 목록 검증", style = MaterialTheme.typography.titleLarge)
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

        Text("허용된 읽기 권한: ${(granted intersect HealthPermissions.standard).size}" +
             " / ${HealthPermissions.standard.size}")
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
        Spacer(Modifier.height(12.dp))

        SeederPanel(client)
        Spacer(Modifier.height(12.dp))

        // 두 화면 다 LazyColumn을 가져 겹쳐 그리면 무한 높이로 크래시한다 — 토글로 하나만
        var tab by remember { mutableIntStateOf(0) }
        Row {
            OutlinedButton(onClick = { tab = 0 }, enabled = tab != 0) { Text("세션 목록") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { tab = 1 }, enabled = tab != 1) { Text("레코드 덤프") }
        }
        Spacer(Modifier.height(8.dp))

        if (tab == 0) RunListScreen() else RecordDumpScreen(client)
    }
}

/// 임시 세션 목록 — HealthConnectStore 실경로 검증용 (계획서 M4 완료 기준).
@Composable
private fun RunListScreen() {
    val context = LocalContext.current
    val store = remember { HealthConnectStore(context) }
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { store.load() }

    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick = { scope.launch { store.load() } }) { Text("목록 새로고침") }
        Spacer(Modifier.height(8.dp))
        when (val current = state) {
            HealthConnectStore.State.Idle,
            HealthConnectStore.State.Loading -> Text("불러오는 중…")
            HealthConnectStore.State.Unavailable ->
                Text("Health Connect를 쓸 수 없거나 권한이 없습니다")
            is HealthConnectStore.State.Failed -> Text("실패: ${current.message}")
            is HealthConnectStore.State.Loaded -> {
                Text("${current.runs.size}개 세션", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(current.runs, key = { it.id }) { run ->
                        RunRow(run)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val runDateFormat =
    DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN).withZone(SEOUL)

@Composable
private fun RunRow(run: RunSummary) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(runDateFormat.format(run.start) + if (run.isIndoor) " · 실내" else "",
             style = MaterialTheme.typography.titleSmall)
        val km = run.distanceKm?.let { "${Format.km(it)}km" } ?: "거리 없음"
        val pace = run.paceSecPerKm?.let { Format.paceKm(it) } ?: "-"
        val hr = run.avgHeartRate?.let { " · ♥${it.toInt()}" } ?: ""
        Text("$km · $pace$hr", style = MaterialTheme.typography.bodyMedium)
    }
}
