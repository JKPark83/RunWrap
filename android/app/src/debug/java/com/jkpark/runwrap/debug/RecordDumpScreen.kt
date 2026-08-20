package com.jkpark.runwrap.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

/// 디버그 전용 레코드 덤프 — 최근 30일 HC 레코드를 타입별 건수·출처·최신 샘플로 보여 준다.
/// 목적: 계획서 §2.2 ⚠️ 5항목(경로·케이던스·HRV·안정심박·호흡수/피부온)을 갤럭시
/// 실기기에서 판정해 `docs/plan/android-m0-검증노트.md`에 기록하는 것.
/// 릴리스 소스셋의 동명 스텁과 시그니처를 맞춘다.
@Composable
fun RecordDumpScreen(client: HealthConnectClient) {
    var rows by remember { mutableStateOf(listOf<DumpRow>()) }
    var running by remember { mutableStateOf(false) }
    var consentSessionId by remember { mutableStateOf<String?>(null) }
    var routeText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 경로는 읽기 권한이 아니라 세션별 동의 — ConsentRequired 세션 id로 동의 시트를 띄운다.
    val routeLauncher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        routeText = if (route == null) "경로 동의 거부됨 또는 데이터 없음"
        else "경로 수신: ${route.route.size}개 지점"
    }

    Column(Modifier.fillMaxWidth()) {
        Button(enabled = !running, onClick = {
            running = true
            scope.launch {
                val result = dumpAll(client)
                rows = result.rows
                consentSessionId = result.consentSessionId
                running = false
            }
        }) {
            Text(if (running) "덤프 중…" else "최근 30일 덤프 실행")
        }

        consentSessionId?.let { id ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { routeLauncher.launch(id) }) {
                Text("최신 세션 경로 열람 동의 요청")
            }
        }
        routeText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(rows) { row ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(row.type, style = MaterialTheme.typography.titleSmall)
                    Text(row.summary, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private data class DumpRow(val type: String, val summary: String)

private data class DumpResult(val rows: List<DumpRow>, val consentSessionId: String?)

/// §2.2 표의 전 타입을 순서대로 덤프한다. 개별 타입 실패(미지원 기기 등)는
/// 행 단위 오류로 기록하고 계속 진행한다 — 스파이크의 목적은 전수 판정이다.
private suspend fun dumpAll(client: HealthConnectClient): DumpResult {
    var consentSessionId: String? = null
    val rows = buildList {
        add(dumpType(client, ExerciseSessionRecord::class, "운동 세션 ExerciseSession") { r ->
            if (r.exerciseRouteResult is ExerciseRouteResult.ConsentRequired) {
                consentSessionId = r.metadata.id
            }
            "exerciseType=${r.exerciseType} · ${r.startTime} · 경로=${routeStatus(r.exerciseRouteResult)}"
        })
        add(dumpType(client, DistanceRecord::class, "거리 Distance") { r ->
            "%.0fm · %s".format(r.distance.inMeters, r.startTime)
        })
        add(dumpType(client, HeartRateRecord::class, "심박 시계열 HeartRate") { r ->
            "샘플 ${r.samples.size}개 · 마지막 ${r.samples.lastOrNull()?.beatsPerMinute}bpm"
        })
        add(dumpType(client, StepsRecord::class, "걸음수 Steps") { r ->
            "${r.count}보 · ${r.startTime}"
        })
        add(dumpType(client, StepsCadenceRecord::class, "케이던스 StepsCadence ⚠️") { r ->
            "샘플 ${r.samples.size}개 · 마지막 ${r.samples.lastOrNull()?.rate}spm"
        })
        add(dumpType(client, TotalCaloriesBurnedRecord::class, "칼로리 TotalCaloriesBurned") { r ->
            "%.0fkcal · %s".format(r.energy.inKilocalories, r.startTime)
        })
        add(dumpType(client, Vo2MaxRecord::class, "VO₂max Vo2Max") { r ->
            "%.1f · method=%d".format(r.vo2MillilitersPerMinuteKilogram, r.measurementMethod)
        })
        add(dumpType(client, PowerRecord::class, "파워 Power") { r ->
            "샘플 ${r.samples.size}개 · 마지막 ${r.samples.lastOrNull()?.power?.inWatts}W"
        })
        add(dumpType(client, SpeedRecord::class, "속도 Speed") { r ->
            "샘플 ${r.samples.size}개 · 마지막 ${r.samples.lastOrNull()?.speed?.inMetersPerSecond}m/s"
        })
        add(dumpType(client, SleepSessionRecord::class, "수면 SleepSession") { r ->
            "${r.startTime}~${r.endTime} · 단계 ${r.stages.size}개"
        })
        add(dumpType(client, HeartRateVariabilityRmssdRecord::class, "HRV(RMSSD) ⚠️") { r ->
            "%.1fms · %s".format(r.heartRateVariabilityMillis, r.time)
        })
        add(dumpType(client, RestingHeartRateRecord::class, "안정 심박 RestingHeartRate ⚠️") { r ->
            "${r.beatsPerMinute}bpm · ${r.time}"
        })
        add(dumpType(client, RespiratoryRateRecord::class, "호흡수 RespiratoryRate ⚠️") { r ->
            "%.1f회/분 · %s".format(r.rate, r.time)
        })
        add(dumpType(client, SkinTemperatureRecord::class, "피부온 SkinTemperature ⚠️") { r ->
            "델타 ${r.deltas.size}개 · 기준 ${r.baseline}"
        })
    }
    return DumpResult(rows, consentSessionId)
}

private fun routeStatus(result: ExerciseRouteResult): String = when (result) {
    is ExerciseRouteResult.Data -> "있음(${result.exerciseRoute.route.size}점)"
    is ExerciseRouteResult.ConsentRequired -> "동의 필요"
    is ExerciseRouteResult.NoData -> "없음"
    else -> "알 수 없음"
}

private suspend fun <T : Record> dumpType(
    client: HealthConnectClient,
    type: KClass<T>,
    name: String,
    describe: (T) -> String,
): DumpRow = try {
    val end = Instant.now()
    val response = client.readRecords(
        ReadRecordsRequest(
            recordType = type,
            timeRangeFilter = TimeRangeFilter.between(end.minus(30, ChronoUnit.DAYS), end),
            pageSize = 1000,
        )
    )
    val records = response.records
    if (records.isEmpty()) {
        DumpRow(name, "0건")
    } else {
        val origins = records.mapTo(sortedSetOf()) { it.metadata.dataOrigin.packageName }
        val count = if (response.pageToken != null) "${records.size}+" else "${records.size}"
        // ascendingOrder 기본값 true — 마지막 레코드가 최신이다.
        DumpRow(name, "${count}건 · 출처 ${origins.joinToString()}\n최신: ${describe(records.last())}")
    }
} catch (t: Throwable) {
    // SkinTemperature 등은 기기 HC 버전에 따라 미지원일 수 있다 — 오류도 판정 재료다.
    DumpRow(name, "오류: ${t.message ?: t::class.simpleName}")
}
