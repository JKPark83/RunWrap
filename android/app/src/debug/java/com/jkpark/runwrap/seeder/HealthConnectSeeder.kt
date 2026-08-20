package com.jkpark.runwrap.seeder

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import com.jkpark.runwrap.engine.CrossTraining
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.engine.dayOf
import com.jkpark.runwrap.health.DemoData
import com.jkpark.runwrap.health.SplitMix64
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/// 에뮬레이터 시더 — DemoData를 Health Connect에 실제 레코드로 insert한다 (계획서 M4).
///
/// 목적: 배포 앱의 읽기 경로(HealthConnectStore·WorkoutDetailStore)를 합성 폴백이 아니라
/// "HC에서 진짜로 읽어 오는" 전체 파이프라인으로 검증한다. 세션 목록·집계·시리즈·수면·
/// 활력징후가 전부 실경로 코드로 흘러야 갤럭시 실기기(M6) 전에 회귀를 잡을 수 있다.
///
/// debug 소스셋 전용 — 쓰기 권한 선언(src/debug/AndroidManifest.xml)과 함께
/// 릴리스 빌드에서는 존재하지 않는다. 배포 앱은 읽기 전용 원칙 유지.
object HealthConnectSeeder {
    /// insert하는 레코드 타입의 쓰기 권한 — debug 매니페스트 선언과 1:1
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(Vo2MaxRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(SkinTemperatureRecord::class),
        // 경로는 레코드 타입이 아니라 세션 내포 데이터 — 전용 상수로만 존재한다
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )

    /// 세션 목록 검증(M4 완료 기준)에 필수인 최소 쓰기 권한 — 시더 버튼 노출 게이트.
    /// 나머지 타입은 기기 HC 모듈이 모르면(예: API 34 에뮬레이터의 SkinTemperature)
    /// 부여 자체가 불가하므로, seed()/wipe()가 부여된 권한만큼만 처리한다.
    val coreWritePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    private val KST: ZoneOffset = ZoneOffset.ofHours(9)

    /// DemoData 전 시나리오를 insert하고 넣은 레코드 수를 돌려준다.
    /// 중복 실행하면 데이터가 겹쳐 쌓인다 — 다시 넣기 전에 wipe()를 먼저 쓴다.
    suspend fun seed(client: HealthConnectClient, now: Instant = Instant.now()): Int {
        // 쓰기 권한을 먼저 본다 — 경로는 세션 레코드에 내포돼 레코드 단위 필터로 못 거르고,
        // 권한 없이 실으면 insert 전체가 SecurityException으로 죽는다
        val granted = client.permissionController.getGrantedPermissions()
        val routeAllowed = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in granted

        val records = mutableListOf<Record>()
        DemoData.runs(now).forEach { records += runRecords(it, withRoute = routeAllowed) }
        DemoData.vo2Max(now).forEach { (time, value) ->
            records += Vo2MaxRecord(
                metadata = Metadata.manualEntry(),
                time = time, zoneOffset = KST,
                vo2MillilitersPerMinuteKilogram = value,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
            )
        }
        DemoData.sleepNights(now).forEach { records += sleepRecord(it) }
        records += vitalsRecords(now)
        DemoData.crossTrainings(now).forEach { records += crossRecords(it) }

        // 쓰기 권한이 부여된 타입만 넣는다 — 기기 HC가 모르는 타입은 건너뛴다
        val insertable = records.filter { HealthPermission.getWritePermission(it::class) in granted }

        // 한 번에 넣기엔 많다(러닝 시리즈 포함 수천 건) — 배치 상한을 넉넉히 피해 나눠 넣는다
        insertable.chunked(500).forEach { client.insertRecords(it) }
        return insertable.size
    }

    /// 이 앱이 넣은 레코드를 전부 지운다 — HC는 앱이 자기 소유 레코드만 지울 수 있어
    /// 시간 창 삭제로 충분하다 (다른 앱 데이터는 건드리지 못한다)
    suspend fun wipe(client: HealthConnectClient, now: Instant = Instant.now()) {
        val window = TimeRangeFilter.between(now.minusSeconds(200 * 86_400L),
                                             now.plusSeconds(86_400L))
        val granted = client.permissionController.getGrantedPermissions()
        listOf(ExerciseSessionRecord::class, DistanceRecord::class, HeartRateRecord::class,
               StepsRecord::class, TotalCaloriesBurnedRecord::class, Vo2MaxRecord::class,
               SleepSessionRecord::class, HeartRateVariabilityRmssdRecord::class,
               RestingHeartRateRecord::class, RespiratoryRateRecord::class,
               SkinTemperatureRecord::class)
            .filter { HealthPermission.getWritePermission(it) in granted }
            .forEach { client.deleteRecords(it, window) }
    }

    // MARK: - 러닝 1회 → 세션 + 시리즈

    /// RunSummary 하나를 세션(+야외 경로)·심박 시리즈·거리 구간·걸음·칼로리 레코드로 펼친다.
    /// 시리즈는 상세 화면(존·스플릿·드리프트·지도) 검증 재료라 요약값과 결이 맞게 합성한다.
    private fun runRecords(run: RunSummary, withRoute: Boolean): List<Record> {
        val start = run.start
        val end = start.plusMillis((run.durationSec * 1_000).roundToLong())
        val rng = SplitMix64(run.id.hashCode().toLong().toULong())
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                metadata = Metadata.manualEntry(),
                startTime = start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                exerciseType = if (run.isIndoor) {
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
                } else ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "시드 러닝",
                // 실내는 경로가 없다 — WorkoutDetailStore의 실내 생략 분기와 짝 (계획서 M1)
                exerciseRoute = if (!run.isIndoor && withRoute) syntheticRoute(run, start) else null,
            ))

        // 심박 — 30초 간격, 평균을 사이에 두고 전반 낮고 후반 높은 완만한 램프.
        // 존 분포가 퍼지고 드리프트(양수 디커플링)가 계산되게 하는 모양이다
        val avgHR = run.avgHeartRate ?: 150.0
        val hrSamples = generateSequence(0.0) { it + 30 }
            .takeWhile { it < run.durationSec }
            .map { sec ->
                val progress = sec / run.durationSec
                val bpm = avgHR - 6 + progress * 14 + (rng.unit() - 0.5) * 6
                HeartRateRecord.Sample(time = start.plusSeconds(sec.toLong()),
                                       beatsPerMinute = bpm.roundToLong())
            }
            .toList()
        if (hrSamples.isNotEmpty()) {
            records += HeartRateRecord(
                metadata = Metadata.manualEntry(),
                startTime = start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                samples = hrSamples,
            )
        }

        // 거리 — 2분 구간으로 나눠 넣는다. 후반 1/4은 살짝 처져 스플릿 화면에서
        // 후반 페이스 저하가 보인다 (합성 상세의 마지막 1/4 페이드와 같은 시나리오)
        val meters = run.distanceMeters ?: 0.0
        if (meters > 0) {
            val segments = maxOf(1, (run.durationSec / 120).toInt())
            val weights = (0 until segments).map { i ->
                val fade = if (i >= segments * 3 / 4) 0.96 else 1.0
                fade * (1 + (rng.unit() - 0.5) * 0.06)
            }
            val weightSum = weights.sum()
            val segSec = run.durationSec / segments
            weights.forEachIndexed { i, w ->
                val segStart = start.plusMillis((i * segSec * 1_000).roundToLong())
                val segEnd = if (i == segments - 1) end
                             else start.plusMillis(((i + 1) * segSec * 1_000).roundToLong())
                records += DistanceRecord(
                    metadata = Metadata.manualEntry(),
                    startTime = segStart, startZoneOffset = KST,
                    endTime = segEnd, endZoneOffset = KST,
                    distance = Length.meters(meters * w / weightSum),
                )
            }
        }

        // 걸음 — 목록 케이던스 백필(걸음 합 ÷ 분)의 재료
        if (run.durationSec > 60) {
            val cadence = run.cadenceSpm ?: 165.0
            records += StepsRecord(
                metadata = Metadata.manualEntry(),
                startTime = start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                count = (cadence * run.durationSec / 60).roundToLong(),
            )
        }

        run.calories?.let {
            records += TotalCaloriesBurnedRecord(
                metadata = Metadata.manualEntry(),
                startTime = start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                energy = Energy.kilocalories(it),
            )
        }
        return records
    }

    /// 야외 세션용 합성 경로 — WorkoutDetailStore.synthetic의 한강 순환 타원과 같은 모양.
    /// 시드를 분리해 뒤에 오는 심박·거리 합성의 재현성을 깨지 않는다 (케이던스 시드 분리와 같은 이유)
    private fun syntheticRoute(run: RunSummary, start: Instant): ExerciseRoute {
        val rng = SplitMix64(run.id.hashCode().toLong().toULong() + 0x0407EuL)
        val km = run.distanceKm ?: 8.0
        val centerLat = 37.520 + rng.unit() * 0.02
        val centerLon = 126.94 + rng.unit() * 0.03
        val phase = rng.unit() * 2 * PI
        val radius = 0.0016 * sqrt(km)
        val points = 140
        // 마지막 점이 세션 끝을 넘지 않게 1초 여유 — HC가 구간 밖 좌표를 거부한다
        val spanSec = maxOf(run.durationSec - 1, 1.0)
        return ExerciseRoute((0..points).map { i ->
            val t = i.toDouble() / points * 2 * PI
            val wobble = 1 + 0.10 * sin(t * 3 + phase) + 0.05 * sin(t * 7)
            ExerciseRoute.Location(
                time = start.plusMillis((spanSec * i / points * 1_000).roundToLong()),
                latitude = centerLat + radius * wobble * sin(t) * 0.72,
                longitude = centerLon + radius * wobble * cos(t),
            )
        })
    }

    // MARK: - 수면·활력징후·크로스 트레이닝

    /// SleepNight 요약을 단계 있는 수면 세션으로 되살린다 — 스토어가 다시 요약하면
    /// 원래 값(수면 시간·깊은잠+렘 비율·취침 시각)이 나와야 한다
    private fun sleepRecord(night: VitalsSnapshot.SleepNight): Record {
        val wakeDayStart = dayOf(night.date).atStartOfDay(SEOUL).toInstant()
        val bedtimeMin = night.bedtimeMinutes ?: 690.0   // 전날 정오 기준 경과 분 (690 = 23:30)
        val start = wakeDayStart.minusSeconds(12 * 3_600L)
            .plusSeconds((bedtimeMin * 60).roundToLong())
        val asleepSec = (night.asleepHours * 3_600).roundToLong()
        val end = start.plusSeconds(asleepSec)

        val fraction = night.deepRemFraction ?: 0.35
        val deepSec = (asleepSec * fraction / 2).roundToLong()
        val remSec = (asleepSec * fraction).roundToLong() - deepSec
        val lightSec = asleepSec - deepSec - remSec
        val lightFirst = (lightSec * 0.6).roundToLong()

        var cursor = start
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        fun stage(sec: Long, type: Int) {
            if (sec <= 0) return
            stages += SleepSessionRecord.Stage(cursor, cursor.plusSeconds(sec), type)
            cursor = cursor.plusSeconds(sec)
        }
        stage(lightFirst, SleepSessionRecord.STAGE_TYPE_LIGHT)
        stage(deepSec, SleepSessionRecord.STAGE_TYPE_DEEP)
        stage(lightSec - lightFirst, SleepSessionRecord.STAGE_TYPE_LIGHT)
        stage(remSec, SleepSessionRecord.STAGE_TYPE_REM)

        return SleepSessionRecord(
            metadata = Metadata.manualEntry(),
            startTime = start, startZoneOffset = KST,
            endTime = end, endZoneOffset = KST,
            stages = stages,
        )
    }

    /// 29일치 일별 활력징후 — DemoData.vitals의 "오늘 vs 기준선" 목표값이
    /// 스토어의 일평균 계산을 거쳐 그대로 복원되도록 오늘만 값을 튼다
    private fun vitalsRecords(now: Instant): List<Record> {
        val rng = SplitMix64(0xB10E5uL)
        val records = mutableListOf<Record>()
        for (daysAgo in 0..28) {
            val time = now.minusSeconds(daysAgo * 86_400L + 300)   // 매일 같은 시각, 오늘은 5분 전
            val isToday = daysAgo == 0
            records += HeartRateVariabilityRmssdRecord(
                metadata = Metadata.manualEntry(),
                time = time, zoneOffset = KST,
                heartRateVariabilityMillis = if (isToday) 57.0 else 62 + (rng.unit() - 0.5) * 3,
            )
            records += RestingHeartRateRecord(
                metadata = Metadata.manualEntry(),
                time = time, zoneOffset = KST,
                beatsPerMinute = if (isToday) 53L else 51L,
            )
            records += RespiratoryRateRecord(
                metadata = Metadata.manualEntry(),
                time = time, zoneOffset = KST,
                rate = if (isToday) 14.6 else 14.2 + (rng.unit() - 0.5) * 0.3,
            )
            // 갤럭시 워치 방식 그대로 기준 온도 + 델타로 넣는다 — 스토어의 절대값 환산 검증
            records += SkinTemperatureRecord(
                metadata = Metadata.manualEntry(),
                startTime = time.minusSeconds(3_600), startZoneOffset = KST,
                endTime = time, endZoneOffset = KST,
                deltas = listOf(SkinTemperatureRecord.Delta(
                    time = time.minusSeconds(1_800),
                    delta = TemperatureDelta.celsius(
                        if (isToday) 0.1 else (rng.unit() - 0.5) * 0.06),
                )),
                baseline = Temperature.celsius(36.4),
            )
        }
        return records
    }

    private fun crossRecords(cross: CrossTraining): List<Record> {
        val end = cross.start.plusMillis((cross.durationSec * 1_000).roundToLong())
        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                metadata = Metadata.manualEntry(),
                startTime = cross.start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                exerciseType = when (cross.kind) {
                    CrossTraining.Kind.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                    CrossTraining.Kind.STRENGTH ->
                        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                    CrossTraining.Kind.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                },
                title = "시드 크로스",
            ))
        cross.kcal?.let {
            records += TotalCaloriesBurnedRecord(
                metadata = Metadata.manualEntry(),
                startTime = cross.start, startZoneOffset = KST,
                endTime = end, endZoneOffset = KST,
                energy = Energy.kilocalories(it),
            )
        }
        return records
    }
}
