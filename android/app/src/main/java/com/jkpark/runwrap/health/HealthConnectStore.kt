package com.jkpark.runwrap.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.jkpark.runwrap.engine.CrossTraining
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.engine.dayOf
import com.jkpark.runwrap.engine.secondsBetween
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Health Connect 읽기 전용 래퍼 — iOS `HealthStore.swift` 이식 (계획서 M4).
///
/// iOS와의 구조 차이:
/// - 권한 요청 시트는 Activity 계약(런처)이라 스토어가 못 띄운다 — 화면이 요청하고,
///   스토어는 조회 결과·SecurityException으로만 상태를 판정한다.
/// - iOS와 달리 읽기 권한의 허용 여부를 조회할 수 있지만, 거부 사실 자체를 UI 분기에
///   쓰지 않는 iOS 정책(빈 결과로만 안내)을 유지한다 — 조회 실패 시 unavailable로 접는다.
/// - HRR(심박 회복)은 HC에 레코드 타입이 없어 vitals·추이 모두 없다 (계획서 §2.2).
/// - 세션 날씨 메타데이터가 HC에 없어 weatherTempC/HumidityPct는 실경로에서 항상 null —
///   열 보정 카드(HeatEngine)는 미노출 가드로 접힌다.
class HealthConnectStore(private val context: Context) {
    sealed interface State {
        data object Idle : State           // 권한 요청 전
        data object Loading : State
        data class Loaded(val runs: List<RunSummary>) : State
        data object Unavailable : State    // HC 사용 불가 기기 또는 권한 거부
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /// 체력 배터리용 활력징후 — 러닝 목록과 별개로 실패해도 리포트는 뜬다
    private val _vitals = MutableStateFlow<VitalsSnapshot?>(null)
    val vitals: StateFlow<VitalsSnapshot?> = _vitals.asStateFlow()

    /// 심폐 체력 카드용 최근 12주 VO₂max 표본 (ml/kg/min) — 주 단위 평균은 엔진이 계산한다
    private val _vo2Max = MutableStateFlow<List<Pair<Instant, Double>>>(emptyList())
    val vo2Max: StateFlow<List<Pair<Instant, Double>>> = _vo2Max.asStateFlow()

    /// 최근 2주 비러닝 운동 — 주간 리포트 보조 문장 재료. ACWR에는 절대 섞지 않는다 (제안 문서 A3)
    private val _crossTrainings = MutableStateFlow<List<CrossTraining>>(emptyList())
    val crossTrainings: StateFlow<List<CrossTraining>> = _crossTrainings.asStateFlow()

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun load(now: Instant = Instant.now()) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            _state.value = State.Unavailable
            return
        }
        // 이미 목록이 떠 있으면 조용히 갱신 (당겨서 새로고침이 로딩 화면으로 튀지 않게)
        if (_state.value !is State.Loaded) _state.value = State.Loading
        try {
            val runs = loadRecentRuns(now)
            // 에뮬레이터 폴백 — 시더로 넣은 레코드가 있으면 실경로, 없으면 합성 데이터.
            // iOS 시뮬레이터의 "항상 합성"과 달리 HC는 시더 주입이 가능해 조건부다 (DemoMode 참조)
            if (runs.isEmpty() && DemoMode.isActive) {
                fillWithDemoData(now)
                return
            }
            _state.value = State.Loaded(runs)
            _vitals.value = fetchVitals(now)
            _vo2Max.value = fetchVo2Max(now)
            _crossTrainings.value = fetchCrossTrainings(now)
        } catch (security: SecurityException) {
            // 권한 거부 — 크래시 없이 unavailable로 접는다 (계획서 M4 완료 기준)
            _state.value = State.Unavailable
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: t::class.simpleName ?: "알 수 없는 오류")
        }
    }

    private fun fillWithDemoData(now: Instant) {
        _state.value = State.Loaded(DemoData.runs(now))
        _vitals.value = DemoData.vitals(now)
        _vo2Max.value = DemoData.vo2Max(now)
        _crossTrainings.value = DemoData.crossTrainings(now)
    }

    // MARK: - 러닝 목록

    /// 최근 26주 러닝 세션 → RunSummary. 세션별 거리·칼로리·평균 심박은 세션 시간 창의
    /// aggregate로 채운다 — iOS의 워크아웃 statistics 쿼리 대응 (계획서 M4 스케치).
    private suspend fun loadRecentRuns(now: Instant): List<RunSummary> {
        val sessions = readAll(ExerciseSessionRecord::class,
                               from = now.minusSeconds(26 * 7 * 86_400L), to = now)
            .filter {
                it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                    it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            }
            .sortedByDescending { it.startTime }
            .take(100)   // iOS fetchRunningWorkouts(limit: 100) 대응

        val cadenceCutoff = now.minusSeconds(28 * 86_400L)
        return sessions.map { session ->
            val aggregate = runCatching {
                client.aggregate(AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL,
                                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                                    HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                ))
            }.getOrNull()
            val durationSec = secondsBetween(session.startTime, session.endTime)
            RunSummary(
                id = session.metadata.id,
                start = session.startTime,
                durationSec = durationSec,
                distanceMeters = aggregate?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                avgHeartRate = aggregate?.get(HeartRateRecord.BPM_AVG)?.toDouble(),
                calories = aggregate?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
                isIndoor = session.exerciseType ==
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
                // 케이던스 백필 — 주법 추이 재료. 걸음 수 쿼리 비용 때문에 추이 창인
                // 최근 28일만 채워 쿼리 수를 줄인다 (iOS와 동일 정책)
                cadenceSpm = if (session.startTime >= cadenceCutoff) {
                    cadenceSpm(session.startTime, session.endTime, durationSec)
                } else null,
            )
        }
    }

    /// 세션 평균 케이던스(spm) = 걸음 수 합 ÷ 분 — iOS cadenceSpm(of:) 대응.
    /// HC aggregate는 시간 창 안의 걸음을 합산한다 — 같은 창을 두 앱이 기록하면 부풀 수 있으나
    /// 세션 창 겹침 기록은 드물어 iOS의 소스 좁힘 폴백까지는 옮기지 않는다 (가정)
    private suspend fun cadenceSpm(start: Instant, end: Instant, durationSec: Double): Double? {
        if (durationSec <= 60) return null
        val steps = runCatching {
            client.aggregate(AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ))[StepsRecord.COUNT_TOTAL]
        }.getOrNull() ?: return null
        if (steps <= 0) return null
        return steps / (durationSec / 60)
    }

    // MARK: - 활력징후 (체력 배터리)

    /// 최근 28일 활력징후를 모아 스냅샷으로 만든다 — 없는 항목은 null로 남긴다
    private suspend fun fetchVitals(now: Instant): VitalsSnapshot = VitalsSnapshot(
        hrvMs = reading(HeartRateVariabilityRmssdRecord::class, now) {
            it.time to it.heartRateVariabilityMillis
        },
        restingHR = reading(RestingHeartRateRecord::class, now) {
            it.time to it.beatsPerMinute.toDouble()
        },
        respiratoryRate = reading(RespiratoryRateRecord::class, now) { it.time to it.rate },
        skinTempC = skinTempReading(now),
        sleepHours = lastNightSleepHours(now),
        sleepNights = fetchSleepNights(now),
    )

    /// 최근 29일 표본 → 오늘 값 + 그 이전 일평균 기준선.
    ///
    /// 활력징후는 주로 수면 중 기록되므로 "오늘 값"은 오늘 날짜의 표본 평균,
    /// 없으면 어제 표본으로 대체한다. 기준선은 그보다 앞선 날들의 일평균 평균.
    private suspend fun <T : Record> reading(
        type: KClass<T>,
        now: Instant,
        sample: (T) -> Pair<Instant, Double>,
    ): VitalsSnapshot.Reading? {
        val samples = runCatching {
            readAll(type, from = now.minusSeconds(29 * 86_400L), to = now).map(sample)
        }.getOrNull() ?: return null
        if (samples.isEmpty()) return null
        return readingFrom(samples, now)
    }

    private fun readingFrom(samples: List<Pair<Instant, Double>>,
                            now: Instant): VitalsSnapshot.Reading? {
        val byDay: Map<LocalDate, List<Double>> =
            samples.groupBy({ dayOf(it.first) }, { it.second })
        val today = dayOf(now)
        val todayKey = if (byDay.containsKey(today)) today else today.minusDays(1)
        val todayValues = byDay[todayKey] ?: return null
        val baselineDays = byDay.filterKeys { it < todayKey }
        if (baselineDays.isEmpty()) return null
        return VitalsSnapshot.Reading(
            today = todayValues.average(),
            baseline = baselineDays.values.map { it.average() }.average(),
            baselineDays = baselineDays.size,
        )
    }

    /// 피부 온도 — HC는 절대값이 아니라 기준 온도 + 델타 목록으로 기록한다 (갤럭시 워치).
    /// 기준이 있으면 절대값(기준+델타)으로, 없으면 델타 그대로 쓴다 — BatteryEngine은
    /// 기준선 대비 상대 변화만 보므로 좌표계가 일관되기만 하면 된다.
    private suspend fun skinTempReading(now: Instant): VitalsSnapshot.Reading? {
        val records = runCatching {
            readAll(SkinTemperatureRecord::class, from = now.minusSeconds(29 * 86_400L), to = now)
        }.getOrNull() ?: return null
        val samples = records.flatMap { record ->
            val base = record.baseline?.inCelsius ?: 0.0
            record.deltas.map { it.time to base + it.delta.inCelsius }
        }
        if (samples.isEmpty()) return null
        return readingFrom(samples, now)
    }

    // MARK: - 수면

    /// "잠든" 구간 — 단계가 있으면 깨어 있음(AWAKE 계열)을 뺀 단계 구간, 없으면 세션 전체
    private fun asleepIntervals(session: SleepSessionRecord): List<Pair<Instant, Instant>> {
        val asleepStages = session.stages.filter { it.stage in ASLEEP_STAGES }
        if (asleepStages.isNotEmpty()) return asleepStages.map { it.startTime to it.endTime }
        if (session.stages.isNotEmpty()) return emptyList()   // 전부 AWAKE 등 — 잠든 구간 없음
        return listOf(session.startTime to session.endTime)
    }

    /// 여러 소스가 같은 밤을 중복 기록할 수 있어 구간을 병합한다 (iOS와 같은 이유)
    private fun merge(intervals: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for (interval in sorted) {
            val last = merged.lastOrNull()
            if (last != null && interval.first <= last.second) {
                if (interval.second > last.second) merged[merged.size - 1] = last.first to interval.second
            } else {
                merged.add(interval)
            }
        }
        return merged
    }

    /// 지난밤 수면 시간 — 최근 24시간에 끝난 "잠든" 구간을 겹침 없이 합산
    private suspend fun lastNightSleepHours(now: Instant): Double? {
        val sessions = runCatching {
            readAll(SleepSessionRecord::class, from = now.minusSeconds(36 * 3_600L), to = now)
        }.getOrNull() ?: return null
        val cutoff = now.minusSeconds(24 * 3_600L)
        val merged = merge(sessions.filter { it.endTime > cutoff }.flatMap(::asleepIntervals))
        val total = merged.sumOf { secondsBetween(it.first, it.second) }
        if (total < 3_600) return null   // 1시간 미만이면 수면 기록으로 보지 않는다
        return total / 3_600
    }

    /// 최근 2주 밤별 수면 상세 — 수면 질(깊은+렘 비율)·취침 규칙성 팩터의 재료 (제안 문서 A5).
    /// 밤 구분: 세션 종료 시각이 속한 날짜(기상일)로 묶고, 3시간 미만은 낮잠으로 보고 버린다.
    private suspend fun fetchSleepNights(now: Instant): List<VitalsSnapshot.SleepNight> {
        val sessions = runCatching {
            readAll(SleepSessionRecord::class, from = now.minusSeconds(14 * 86_400L), to = now)
        }.getOrNull() ?: return emptyList()

        return sessions.groupBy { dayOf(it.endTime) }
            .mapNotNull { (night, nightSessions) ->
                val merged = merge(nightSessions.flatMap(::asleepIntervals))
                val asleepHours = merged.sumOf { secondsBetween(it.first, it.second) } / 3_600
                if (asleepHours < 3) return@mapNotNull null

                // 단계 비율은 단계를 기록한 세션의 표본만으로 계산 — 단계 없는 세션이
                // 분모에 섞이면 비율이 왜곡된다 (iOS의 asleepUnspecified 제외와 같은 이유)
                val stages = nightSessions.flatMap { it.stages }
                val stageSec = stages.filter { it.stage in ASLEEP_STAGES }
                    .sumOf { secondsBetween(it.startTime, it.endTime) }
                val deepRemSec = stages.filter { it.stage in DEEP_REM_STAGES }
                    .sumOf { secondsBetween(it.startTime, it.endTime) }

                // 취침 시각: 정오(전날 12:00 KST) 기준 경과 분 — 자정 넘김(23시=660, 새벽 1시=780)을
                // 연속값으로 다뤄 표준편차 계산이 깨지지 않게 한다
                val nightMidnight = night.atStartOfDay(SEOUL).toInstant()
                val bedtime = merged.firstOrNull()?.let {
                    secondsBetween(nightMidnight.minusSeconds(12 * 3_600L), it.first) / 60
                }
                VitalsSnapshot.SleepNight(
                    date = nightMidnight,
                    asleepHours = asleepHours,
                    deepRemFraction = if (stageSec > 0) deepRemSec / stageSec else null,
                    bedtimeMinutes = bedtime,
                )
            }
            .sortedBy { it.date }
    }

    // MARK: - VO₂max · 크로스 트레이닝

    /// 최근 12주 VO₂max 표본 — 심폐 체력 추이 카드의 재료 (갤럭시 워치가 추정 기록)
    private suspend fun fetchVo2Max(now: Instant): List<Pair<Instant, Double>> = runCatching {
        readAll(Vo2MaxRecord::class, from = now.minusSeconds(84 * 86_400L), to = now)
            .map { it.time to it.vo2MillilitersPerMinuteKilogram }
    }.getOrDefault(emptyList())

    /// 최근 2주 비러닝 세션 — 주간 리포트 보조 문장(CrossTrainingEngine) 재료 (제안 문서 A3).
    /// 러닝만 빼고 전부 가져와 종류 매핑은 앱에서 한다 (iOS와 동일 정책)
    private suspend fun fetchCrossTrainings(now: Instant): List<CrossTraining> = runCatching {
        readAll(ExerciseSessionRecord::class, from = now.minusSeconds(14 * 86_400L), to = now)
            .filter {
                it.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING &&
                    it.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            }
            .map { session ->
                val kcal = runCatching {
                    client.aggregate(AggregateRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(session.startTime,
                                                                  session.endTime),
                    ))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                }.getOrNull()
                CrossTraining(
                    start = session.startTime,
                    durationSec = secondsBetween(session.startTime, session.endTime),
                    kind = crossKind(session.exerciseType),
                    kcal = kcal,
                )
            }
    }.getOrDefault(emptyList())

    /// HC 운동 종류 → 크로스 트레이닝 종류 — 러너가 실제로 병행하는 종목 위주로 추리고
    /// 나머지는 OTHER로 뭉친다 (iOS crossKind(of:) 대응)
    private fun crossKind(exerciseType: Int): CrossTraining.Kind = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CrossTraining.Kind.CYCLING
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> CrossTraining.Kind.STRENGTH
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> CrossTraining.Kind.SWIMMING
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> CrossTraining.Kind.HIKING
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> CrossTraining.Kind.WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> CrossTraining.Kind.YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING ->
            CrossTraining.Kind.HIIT
        else -> CrossTraining.Kind.OTHER
    }

    // MARK: - 공용

    /// 페이지네이션을 끝까지 따라가며 전부 읽는다 — HC 기본 페이지(1000건)를 넘는 심박
    /// 시계열 등 대비. 시간 창은 겹침 기준(iOS predicateForSamples 대응)
    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                pageSize = 1_000,
                pageToken = pageToken,
            ))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private companion object {
        /// 잠든 것으로 치는 수면 단계 — AWAKE·AWAKE_IN_BED·OUT_OF_BED·UNKNOWN 제외
        val ASLEEP_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
        val DEEP_REM_STAGES = setOf(
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
    }
}
