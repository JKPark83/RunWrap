package com.jkpark.runwrap.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.jkpark.runwrap.RunWrapApp
import com.jkpark.runwrap.engine.DemoData
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.health.HealthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/// 디버그 전용 Health Connect 시더 — 에뮬레이터에는 워치 기록이 없어 실제 HC 읽기 경로
/// (HealthStore·WorkoutDetailStore)를 돌려 볼 재료가 없다. 데모 모드(DemoData를 스토어가 바로 씀)와 달리
/// 같은 합성 러닝을 HC 레코드로 써 넣어, 앱이 권한 → 읽기 → 세션 구간 자르기까지 실제 경로로 읽게 한다.
///
/// 쓰는 법 (앱을 띄워 둔 채로):
/// ```
/// for p in EXERCISE EXERCISE_ROUTE DISTANCE HEART_RATE STEPS ACTIVE_CALORIES_BURNED VO2_MAX SLEEP \
///          HEART_RATE_VARIABILITY RESTING_HEART_RATE RESPIRATORY_RATE; do
///   adb shell pm grant com.jkpark.runwrap android.permission.health.WRITE_$p; done
/// adb shell am broadcast -n com.jkpark.runwrap/.debug.HealthSeedReceiver
/// adb logcat -s HealthSeed      # "완료 — 레코드 N개"가 찍히면 끝
/// adb shell am broadcast -n com.jkpark.runwrap/.debug.HealthSeedReceiver --es do dump   # 앱이 읽은 결과를 로그로
/// ```
/// `--es do dump`는 쓰지 않고 HealthStore.load()를 돌려 읽힌 러닝·회복 신호를 로그에 찍는다 —
/// 실기기에서 삼성헬스 데이터가 어떻게 읽히는지 볼 때도 쓴다(디버그 빌드의 기기 로그에만 남는다).
/// dump는 **앱이 화면에 떠 있을 때만** 된다: 백그라운드 읽기 권한을 요청하지 않으므로 화면 밖에서 부르면
/// HC가 "…from other applications" SecurityException으로 거절하고 상태가 Failed로 찍힌다(정상 동작).
/// 다시 부르면 이 앱이 쓴 레코드를 지우고 새로 쓴다(HC는 자기 앱 레코드만 지울 수 있다).
/// 한계: 자기 앱이 쓴 경로는 동의 없이 읽히므로 "다른 앱 경로 동의" 흐름과 삼성헬스 동기화 데이터의
/// 실제 모양(표본 간격·기록 앱 분리)은 이걸로 검증되지 않는다 — 실기기에서만.
class HealthSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("do") == "dump") return dump(context)
        val client = HealthConnectClient.getOrCreate(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val now = Instant.now()
                types.forEach { client.deleteRecords(it, TimeRangeFilter.before(now.plusSeconds(86_400))) }
                val records = DemoData.runs(now).filter { it.endsBefore(now) }.flatMap(::runRecords) + vitals(now)
                records.chunked(500).forEach { client.insertRecords(it) }
                Log.i(TAG, "완료 — 레코드 ${records.size}개")
            }.onFailure { Log.e(TAG, "실패 — 쓰기 권한(pm grant)을 확인", it) }
        }
    }

    private fun dump(context: Context) {
        val health = (context.applicationContext as RunWrapApp).container.health
        CoroutineScope(Dispatchers.Main).launch {
            health.load()
            val state = health.state.value
            Log.i(TAG, "상태 ${if (state is HealthStore.State.Loaded) "Loaded" else state} · 권한 ${health.hasPermissions()}")
            (state as? HealthStore.State.Loaded)?.runs?.let { runs ->
                Log.i(TAG, "러닝 ${runs.size}개 (실내 ${runs.count { it.isIndoor }})")
                runs.take(8).forEach { Log.i(TAG, "  $it") }
            }
            Log.i(TAG, "vitals ${health.vitals.value}")
            Log.i(TAG, "vo2Max ${health.vo2Max.value.size}개 · 안정심박 ${health.restingHRBpm.value} · HRmax ${health.hrMaxEstimate.value}")
            Log.i(TAG, "심박존 히스토그램 ${health.zoneHistograms.value.size}개 · 베스트 에포트 ${health.bestEfforts.value.size}개(대기 ${health.bestEffortPending.value})")
        }
    }

    private companion object {
        const val TAG = "HealthSeed"
        val zone: ZoneId = ZoneId.systemDefault()
        val types = listOf(
            ExerciseSessionRecord::class, DistanceRecord::class, StepsRecord::class, HeartRateRecord::class,
            ActiveCaloriesBurnedRecord::class, Vo2MaxRecord::class, RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class, SleepSessionRecord::class,
        )

        fun offset(at: Instant) = zone.rules.getOffset(at)
        fun meta() = Metadata.manualEntry()
        fun RunSummary.end(): Instant = start.plusMillis((durationSec * 1_000).toLong())
        fun RunSummary.endsBefore(now: Instant) = end() < now

        /// 러닝 하나 → 세션 + 1분 단위 거리·걸음 + 15초 간격 심박 + 칼로리 (+ 야외면 경로)
        fun runRecords(run: RunSummary): List<Record> {
            val start = run.start
            val end = run.end()
            val meters = run.distanceMeters ?: 0.0
            val minutes = (run.durationSec / 60).toInt().coerceAtLeast(1)
            // 분마다 ±6% 흔들되 합은 총거리와 같게
            val weights = (0 until minutes).map { 1 + 0.06 * sin(it * 0.7) }
            val weightSum = weights.sum()
            val cadence = run.cadenceSpm ?: 172.0
            val perMinute = (0 until minutes).flatMap { i ->
                val from = start.plusSeconds(i * 60L)
                val to = if (i == minutes - 1) end else start.plusSeconds((i + 1) * 60L)
                listOf<Record>(
                    DistanceRecord(from, offset(from), to, offset(to), Length.meters(meters * weights[i] / weightSum), meta()),
                    StepsRecord(from, offset(from), to, offset(to), cadence.toLong(), meta()),
                )
            }
            val avg = run.avgHeartRate ?: 150.0
            val peak = run.maxHeartRate ?: (avg + 20)
            val beats = (0 until (run.durationSec / 15).toInt()).map { i ->
                val t = i * 15.0
                // 첫 3분은 워밍업으로 올라오고, 마지막 1분에 최고 심박을 찍는다
                val bpm = when {
                    t < 180 -> avg - 25 + 25 * t / 180
                    t > run.durationSec - 60 -> peak
                    else -> avg + 4 * sin(t / 90)
                }
                HeartRateRecord.Sample(start.plusSeconds(t.toLong()), bpm.toLong())
            }
            val route = if (run.isIndoor) null else ExerciseRoute((0..(run.durationSec / 15).toInt()).map { i ->
                // 여의도 한강공원에서 동쪽으로 갔다 돌아오는 왕복 — 위도 37.5°에서 경도 1° ≈ 88km
                val progress = i * 15.0 / run.durationSec
                val out = meters / 2 * (1 - kotlin.math.abs(1 - 2 * progress))
                ExerciseRoute.Location(
                    time = minOf(start.plusSeconds(i * 15L), end.minusMillis(1)),
                    latitude = 37.5270 + 0.0012 * sin(out / 400) + (if (progress > 0.5) 0.0002 else 0.0),
                    longitude = 126.9340 + out / 88_000,
                )
            }.distinctBy { it.time })
            return perMinute + listOf<Record>(
                ExerciseSessionRecord(
                    startTime = start, startZoneOffset = offset(start), endTime = end, endZoneOffset = offset(end),
                    metadata = meta(),
                    exerciseType = if (run.isIndoor) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
                                   else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    title = "런미새 합성 러닝", exerciseRoute = route,
                ),
                HeartRateRecord(start, offset(start), end, offset(end), beats, meta()),
                ActiveCaloriesBurnedRecord(start, offset(start), end, offset(end),
                                           Energy.kilocalories(run.calories ?: 0.0), meta()),
            )
        }

        /// 회복 신호 30일 + 수면 14밤 + VO₂max — 체력 배터리·수면 카드 재료
        fun vitals(now: Instant): List<Record> {
            val today = now.atZone(zone).toLocalDate()
            val daily = (0 until 30).flatMap { d ->
                val at = today.minusDays(d.toLong()).atTime(LocalTime.of(5, 0)).atZone(zone).toInstant()
                if (at >= now) return@flatMap emptyList<Record>()
                listOf<Record>(
                    RestingHeartRateRecord(at, offset(at), (52 + 2 * sin(d * 0.9)).toLong(), meta()),
                    HeartRateVariabilityRmssdRecord(at, offset(at), 46 + 6 * sin(d * 0.6), meta()),
                    RespiratoryRateRecord(at, offset(at), 14.5 + 0.4 * sin(d * 0.5), meta()),
                )
            }
            val sleep = (0 until 14).mapNotNull { d ->
                val wake = today.minusDays(d.toLong()).atTime(LocalTime.of(6, 45)).atZone(zone).toInstant()
                val bed = wake.minusSeconds((7 * 60 + 15 + (d % 3) * 10) * 60L)
                if (wake >= now) return@mapNotNull null
                // 얕은 50분 → 깊은 25분 → 얕은 20분 → 렘 20분을 기상까지 되풀이
                val cycle = listOf(SleepSessionRecord.STAGE_TYPE_LIGHT to 50L, SleepSessionRecord.STAGE_TYPE_DEEP to 25L,
                                   SleepSessionRecord.STAGE_TYPE_LIGHT to 20L, SleepSessionRecord.STAGE_TYPE_REM to 20L)
                val stages = generateSequence(0) { it + 1 }
                    .runningFold(bed to bed) { (_, from), i -> from to from.plusSeconds(cycle[i % 4].second * 60) }
                    .drop(1).withIndex()
                    .takeWhile { it.value.first < wake }
                    .map { (i, span) -> SleepSessionRecord.Stage(span.first, minOf(span.second, wake), cycle[i % 4].first) }
                    .toList()
                SleepSessionRecord(startTime = bed, startZoneOffset = offset(bed), endTime = wake, endZoneOffset = offset(wake),
                                   metadata = meta(), stages = stages)
            }
            val vo2 = DemoData.vo2Max(now).filter { it.first < now }.map { (at, value) ->
                Vo2MaxRecord(time = at, zoneOffset = offset(at), metadata = meta(), vo2MillilitersPerMinuteKilogram = value,
                             measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER)
            }
            return daily + sleep + vo2
        }
    }
}
