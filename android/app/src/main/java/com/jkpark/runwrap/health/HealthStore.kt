package com.jkpark.runwrap.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.jkpark.runwrap.engine.ActiveTimeline
import com.jkpark.runwrap.engine.BestEffortCache
import com.jkpark.runwrap.engine.BestEffortEngine
import com.jkpark.runwrap.engine.BestEffortTable
import com.jkpark.runwrap.engine.DemoData
import com.jkpark.runwrap.engine.DistanceSample
import com.jkpark.runwrap.engine.KeyValueStore
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.SessionSlices
import com.jkpark.runwrap.engine.SleepBlocks
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.VitalsSnapshot
import com.jkpark.runwrap.engine.ZoneHistogram
import com.jkpark.runwrap.engine.ZoneTimeCache
import com.jkpark.runwrap.engine.timeIntervalSince1970
import com.jkpark.runwrap.store.DemoMode
import com.jkpark.runwrap.store.appSupportDir
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Health Connect 읽기 전용 래퍼 — 권한 확인 + 최근 러닝 조회 (MVP 1단계)
///
/// (Android: iOS HealthKit과 달리 HC는 허용 여부를 조회할 수 있다. 그래도 안내는 iOS와 같게
/// 실제 조회 결과(빈 목록 여부)로만 한다 — 운동 권한이 없으면 빈 목록을 띄운다.
/// 권한 요청 시트는 화면이 띄우고(HealthPermissions 참고), 응답 뒤 connect()/load()를 부른다.
/// 모든 함수는 메인 스레드에서 부른다 — 상태는 메인에서만 바꾼다.)
class HealthStore(context: Context, private val settings: KeyValueStore) {
    sealed interface State {
        data object Idle : State          // 권한 요청 전
        data object Loading : State
        data class Loaded(val runs: List<RunSummary>) : State
        data object Unavailable : State   // Health Connect 미설치·업데이트 필요 기기
        data class Failed(val message: String) : State
    }

    private val context = context.applicationContext
    private val cacheDir = appSupportDir(this.context)
    private val scope = MainScope()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    /// 체력 배터리용 활력징후 — 러닝 목록과 별개로 실패해도 리포트는 뜬다
    private val _vitals = MutableStateFlow<VitalsSnapshot?>(null)
    val vitals: StateFlow<VitalsSnapshot?> = _vitals.asStateFlow()
    /// 심폐 체력 카드용 최근 12주 VO₂max 표본 (ml/kg/min) — 주 단위 평균은 엔진이 계산한다
    private val _vo2Max = MutableStateFlow<List<Pair<Instant, Double>>>(emptyList())
    val vo2Max: StateFlow<List<Pair<Instant, Double>>> = _vo2Max.asStateFlow()
    /// 심폐 체력 카드 보조 지표용 최근 12주 심박 회복(HRR) 표본 (bpm)
    /// (Android: HC에 심박 회복 타입이 없어 데모가 아니면 늘 비어 있다 — 엔진 가드로 카드가 사라진다)
    private val _hrrTrend = MutableStateFlow<List<Pair<Instant, Double>>>(emptyList())
    val hrrTrend: StateFlow<List<Pair<Instant, Double>>> = _hrrTrend.asStateFlow()
    /// 최대 심박(bpm) 추정과 출처 — 관찰 최대(최근 12주 세션 최고 심박 2번째 값)와
    /// Tanaka(2001) 중 큰 쪽, 둘 다 없으면 190 폴백 (TrainingGuideEngine.hrMaxEstimate).
    /// 수동 입력은 여기서 섞지 않는다 — 스토어는 HC 값만 내고, 화면이
    /// TrainingGuideEngine.heartRateProfile로 수동값과 합친다 (이슈 #34, #48, #56)
    private val _hrMaxEstimate = MutableStateFlow(
        TrainingGuideEngine.HrMaxEstimate(TrainingGuideEngine.fallbackHrMaxBpm,
                                          com.jkpark.runwrap.engine.HeartRateProfile.Source.fallback))
    val hrMaxEstimate: StateFlow<TrainingGuideEngine.HrMaxEstimate> = _hrMaxEstimate.asStateFlow()
    /// 안정 심박(bpm) 최근값 — 최근 28일 중 가장 최근 표본. Karvonen 존의 재료 (이슈 #56)
    private val _restingHRBpm = MutableStateFlow<Double?>(null)
    val restingHRBpm: StateFlow<Double?> = _restingHRBpm.asStateFlow()
    /// 최근 28일 러닝의 세션별 심박 bpm 히스토그램 — 기간별 심박존 분포(80/20) 카드 재료.
    /// 존 경계는 화면이 표시 시점의 심박 기준으로 적용한다 (이슈 #165)
    private val _zoneHistograms = MutableStateFlow<Map<String, ZoneHistogram>>(emptyMap())
    val zoneHistograms: StateFlow<Map<String, ZoneHistogram>> = _zoneHistograms.asStateFlow()
    /// 이미 목록이 떠 있을 때 새로 고침이 실패한 사유 — 기존 목록을 Failed로 덮지 않으려고
    /// 따로 싣는다. 아직 표시하는 화면은 없고, 추후 토스트 안내용으로 발행한다 (이슈 #58)
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    /// 워크아웃별 베스트 에포트(이슈 #166) — 거리별 최고 기록(PersonalRecords)의 재료.
    /// 영구 캐시(BestEffortCache)를 먼저 싣고, 빠진 워크아웃은 기동마다 점진 백필한다
    private val _bestEfforts = MutableStateFlow<BestEffortTable>(emptyMap())
    val bestEfforts: StateFlow<BestEffortTable> = _bestEfforts.asStateFlow()
    /// 베스트 에포트가 아직 계산되지 않은 워크아웃 수 — 성장기 PB 카드의 "분석 중" 캡션용
    private val _bestEffortPending = MutableStateFlow(0)
    val bestEffortPending: StateFlow<Int> = _bestEffortPending.asStateFlow()
    /// 현재 목록이 데모 합성 데이터인지 — 데모를 끄고 처음 조회할 때 실패하면 합성 목록을
    /// "기존 목록"으로 지켜선 안 되므로(#44의 캐시 보호가 풀린다) Failed로 보낸다 (이슈 #58)
    private var isDemoLoaded = false

    /// 데모 모드면 합성 데이터를 그대로 물려 HC를 아예 건드리지 않는다.
    init {
        if (DemoMode.isActive(settings)) fillWithDemoData()
    }

    private fun fillWithDemoData() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val runs = DemoData.runs(now)
        val vitals = DemoData.vitals(now)
        isDemoLoaded = true
        _state.value = State.Loaded(runs)
        _vitals.value = vitals
        _vo2Max.value = DemoData.vo2Max(now)
        _hrrTrend.value = DemoData.hrrTrend(now)
        _hrMaxEstimate.value = TrainingGuideEngine.hrMaxEstimate(runs, now, zone, birthYear = null)
        // 데모에서도 Karvonen 존을 고를 수 있게 합성 활력징후의 안정 심박을 그대로 쓴다
        _restingHRBpm.value = vitals.restingHR?.today
        _zoneHistograms.value = DemoData.zoneHistograms(now, zone)
        _bestEfforts.value = DemoData.bestEfforts(now)
        _bestEffortPending.value = 0
    }

    private fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    /// 화면이 권한 시트를 띄울지 고르는 재료 — 목록의 필수 재료인 운동 세션 읽기 권한만 본다.
    /// (경로 권한은 Android 14에서 세션별 동의라 늘 거부로 보여 core 전체를 보면 매번 시트가 뜬다)
    suspend fun hasPermissions(): Boolean = isAvailable() &&
        HealthPermission.getReadPermission(ExerciseSessionRecord::class) in client.permissionController.getGrantedPermissions()

    /// 최초 연결 — iOS는 여기서 권한 시트를 띄우지만 Android는 화면이 HC 권한 계약으로 띄운 뒤 부른다.
    /// 시트 응답 뒤 조회만 남아 load()와 같다
    suspend fun connect() = load()

    suspend fun load() {
        if (DemoMode.isActive(settings)) {
            fillWithDemoData()
            return
        }
        if (!isAvailable()) {
            _state.value = State.Unavailable
            return
        }
        // 이미 목록이 떠 있으면 조용히 갱신 (당겨서 새로 고침이 로딩 화면으로 튀지 않게)
        val wasLoaded = _state.value is State.Loaded && !isDemoLoaded
        if (!wasLoaded) _state.value = State.Loading
        try {
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val granted = client.permissionController.getGrantedPermissions()
            // 운동 권한이 없으면 빈 목록 — iOS의 "거부 = 빈 결과"와 같은 안내로 떨어진다
            val sessions = if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                fetchRunningSessions(now)
            } else {
                emptyList()
            }
            // 개수 제한 없이 전부 — 최근 N개로 자르면 장기 사용자의 사이클 초반 러닝이
            // 성장 XP 재계산에서 빠진다 (이슈 #29).
            // (Android: HC에는 워크아웃 통계가 없어 거리·심박·칼로리를 기간 단위로 한 번씩 읽어 세션별로 나눈다)
            val records = if (sessions.isEmpty()) null else readPeriod(sessions, now, granted)
            val summaries = sessions.map { summary(it, records) }
            // HRmax — 관찰 최대 우선, 폴백은 생년 기반 Tanaka (Android: HC에 생년이 없어 관찰 최대·190만)
            _hrMaxEstimate.value = TrainingGuideEngine.hrMaxEstimate(summaries, now, zone, birthYear = null)
            // 안정 심박은 목록을 띄우기 전에 받는다 — 첫 실행에 목록이 먼저 뜨면 그 사이 연
            // 세션 상세가 안정 심박 없이 %HRmax로 존을 굳힌다(상세는 한 번만 로드). 이슈 #56
            _restingHRBpm.value = latestRestingHR(now)
            _lastError.value = null
            isDemoLoaded = false
            _state.value = State.Loaded(summaries)
            // 캐시된 베스트 에포트를 먼저 반영 — 계산은 함수 끝의 백필이 목록 표시와 별개로 한다 (이슈 #166)
            _bestEfforts.value = BestEffortCache.load(cacheDir)
            _bestEffortPending.value = sessions.count { _bestEfforts.value[it.metadata.id] == null }
            _vitals.value = fetchVitals(now, zone)
            _vo2Max.value = fetchVo2Max(now)
            // (Android: HC에 심박 회복 타입이 없어 비운다 — 데모에서 넘어온 합성 값을 남기지 않는다)
            _hrrTrend.value = emptyList()
            backfillZoneHistograms(sessions, records?.heartRate, now, zone)
            backfillBestEfforts(sessions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 이미 떠 있던 목록은 지키고 사유만 싣는다 — 새로 고침 한 번의 실패가
            // 화면 전체를 오류로 바꾸면 안 된다 (이슈 #58). 목록이 없던 첫 로드만 Failed
            val message = e.localizedMessage ?: e.toString()
            if (wasLoaded) _lastError.value = message else _state.value = State.Failed(message)
        }
    }

    // (Android: iOS의 백그라운드 워크아웃 옵저버(계획서 M8)는 옮기지 않는다 —
    // 백그라운드 HC 읽기 권한을 요청하지 않는 결정이라 동기화는 앱이 열릴 때만 한다)

    /// 러닝(실외·트레드밀) 세션 — 최신순
    private suspend fun fetchRunningSessions(now: Instant): List<ExerciseSessionRecord> =
        client.readAll(ExerciseSessionRecord::class, TimeRangeFilter.before(now), ascending = false)
            .filter { it.exerciseType in runningTypes }

    // MARK: - 베스트 에포트 백필 (이슈 #166)

    /// 백필이 도는 중인지 — 새로 고침이 겹쳐도 같은 워크아웃을 두 번 계산하지 않는다
    private var isBackfillingBestEfforts = false
    /// 이번 기동에 쿼리가 실패한 워크아웃 — 남은 개수에서 빼 PB 감지가 영원히 막히지 않게 한다.
    /// 캐시에는 넣지 않으므로 다음 기동에 다시 시도한다
    private val bestEffortFailedIDs = mutableSetOf<String>()

    /// 캐시에 없는 워크아웃을 최신부터 최대 60개 계산해 저장한다. load()는 기다리지 않는다 —
    /// 목록 표시를 막지 않고, 끝나면 상태 값만 갱신한다. 쿼리 실패한 워크아웃은
    /// 캐시에 넣지 않아 다음 기동에 다시 시도한다.
    private fun backfillBestEfforts(sessions: List<ExerciseSessionRecord>) {
        if (isBackfillingBestEfforts) return
        val pending = sessions.filter { _bestEfforts.value[it.metadata.id] == null }   // sessions는 최신순
        _bestEffortPending.value = pending.size
        if (pending.isEmpty()) return
        isBackfillingBestEfforts = true
        scope.launch {
            val computed = LinkedHashMap<String, Map<Double, Double>>()
            for (session in pending.take(bestEffortBatchSize)) {
                val samples = quietly { distanceSamples(client, session) }
                if (samples == null) {
                    bestEffortFailedIDs += session.metadata.id
                    continue
                }
                computed[session.metadata.id] = BestEffortEngine.bestEfforts(samples)
            }
            val table = _bestEfforts.value + computed
            BestEffortCache.save(table, cacheDir)
            _bestEfforts.value = table
            _bestEffortPending.value = sessions.count {
                table[it.metadata.id] == null && it.metadata.id !in bestEffortFailedIDs
            }
            isBackfillingBestEfforts = false
        }
    }

    // MARK: - 요약 (목록)

    /// 목록 요약 재료 — 기록한 앱(dataOrigin 패키지 이름)별로 묶는다. 읽기 실패·권한 없음은 null
    private class PeriodRecords(
        val distance: Map<String, SessionSlices.Totals>?,
        val heartRate: Map<String, List<TrainingGuideEngine.HeartRateSample>>?,
        val activeCalories: Map<String, SessionSlices.Totals>?,
        val totalCalories: Map<String, SessionSlices.Totals>?,
        /// 케이던스 백필 창(최근 28일)만
        val steps: Map<String, SessionSlices.Totals>?,
        val cadenceCutoff: Instant,
    )

    /// 가장 오래된 세션부터 지금까지를 타입마다 한 번씩 읽는다 — 세션을 기록한 앱의 표본만.
    /// 세션마다 집계를 부르면 기록이 수백 건일 때 HC 읽기 호출 한도에 걸린다
    ///
    /// 권한이 없는 타입만 nil이다. 읽기 실패(호출 한도·IPC 오류 등)는 그대로 던져 load()가 기존 목록을 지키게 한다 —
    /// 조용히 nil로 바꾸면 전 세션의 거리·심박이 빈 목록이 Loaded로 굳고 리포트 캐시까지 덮어쓴다
    // ponytail: 전 기간 심박을 한 번에 메모리에 올린다 — 수년 치 기록에서 느리거나 무거우면
    // 월 단위로 끊어 읽고 세션별 평균·최고만 남긴다 (실기기에서 측정 후 결정)
    private suspend fun readPeriod(sessions: List<ExerciseSessionRecord>, now: Instant, granted: Set<String>): PeriodRecords {
        val origins = sessions.map { it.metadata.dataOrigin }.toSet()
        val range = TimeRangeFilter.between(sessions.minOf { it.startTime }, now)
        suspend fun <T : Record> read(type: KClass<T>, range: TimeRangeFilter): List<T>? =
            if (HealthPermission.getReadPermission(type) in granted) client.readAll(type, range, origins) else null
        suspend fun <T : Record> totals(type: KClass<T>, range: TimeRangeFilter,
                                        amount: (T) -> SessionSlices.Amount): Map<String, SessionSlices.Totals>? =
            read(type, range)
                ?.groupBy({ it.metadata.dataOrigin.packageName }, amount)
                ?.mapValues { SessionSlices.Totals(it.value) }
        // 케이던스 백필 — 주법 추이(계획서 M4) 재료. 추이 창인 최근 28일만 채운다
        val cadenceCutoff = now.minusSeconds(28 * 86_400L)
        return PeriodRecords(
            distance = totals(DistanceRecord::class, range) {
                SessionSlices.Amount(it.startTime, it.endTime, it.distance.inMeters)
            },
            heartRate = read(HeartRateRecord::class, range)
                ?.groupBy({ it.metadata.dataOrigin.packageName }, { it.samples })
                ?.mapValues { (_, samples) ->
                    samples.flatten().sortedBy { it.time }
                        .map { TrainingGuideEngine.HeartRateSample(it.time, it.beatsPerMinute.toDouble()) }
                },
            activeCalories = totals(ActiveCaloriesBurnedRecord::class, range) {
                SessionSlices.Amount(it.startTime, it.endTime, it.energy.inKilocalories)
            },
            totalCalories = totals(TotalCaloriesBurnedRecord::class, range) {
                SessionSlices.Amount(it.startTime, it.endTime, it.energy.inKilocalories)
            },
            steps = totals(StepsRecord::class, TimeRangeFilter.between(cadenceCutoff, now)) {
                SessionSlices.Amount(it.startTime, it.endTime, it.count.toDouble())
            },
            cadenceCutoff = cadenceCutoff,
        )
    }

    /// 세션 → RunSummary. 거리·심박·칼로리·걸음을 기간 단위로 읽어 둔 표본에서 세션 구간만 잘라 쓴다
    private fun summary(session: ExerciseSessionRecord, records: PeriodRecords?): RunSummary {
        val origin = session.metadata.dataOrigin.packageName
        fun Map<String, SessionSlices.Totals>?.sum(): Double? = this?.get(origin)?.sum(session.startTime, session.endTime)
        val bpm = records?.heartRate?.get(origin)
            ?.let { SessionSlices.within(it, session.startTime, session.endTime) }.orEmpty()
            .map { it.bpm }
        val durationSec = session.activeSeconds()
        // 세션 소모 칼로리 — 활동 칼로리 우선, 없으면(그것만 쓰는 기록 앱) 총 칼로리
        val kcal = records?.activeCalories.sum() ?: records?.totalCalories.sum()
        // 케이던스(spm) = 걸음 수 합 ÷ 분 (1분 이하 세션은 침묵)
        val steps = if (records != null && session.startTime >= records.cadenceCutoff) records.steps.sum() else null
        val cadence = if (durationSec > 60 && steps != null && steps > 0) steps / (durationSec / 60) else null
        // 실내 여부 — 트레드밀 타입만 실내 (미기록 = 야외 가정, 기획서 §4.6)
        // (Android: HC에는 운동 당시 날씨 메타데이터가 없다 — 기온·습도는 nil이라 열 보정이 침묵한다)
        return RunSummary(id = session.metadata.id,
                          start = session.startTime,
                          durationSec = durationSec,
                          distanceMeters = records?.distance.sum(),
                          avgHeartRate = if (bpm.isEmpty()) null else bpm.sum() / bpm.size,
                          // 세션 최고 심박 — HRmax 관찰 추정(이슈 #34) 재료
                          maxHeartRate = bpm.maxOrNull(),
                          calories = kcal,
                          isIndoor = session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
                          cadenceSpm = cadence,
                          end = session.endTime)
    }

    // MARK: - 심박존 히스토그램 백필 (기간별 강도 배분, 이슈 #165)

    /// 최근 28일 러닝의 세션별 bpm 히스토그램을 채운다. 캐시에 있는 워크아웃은 쿼리하지 않고,
    /// 없는 것만 심박 샘플을 쓴다. 샘플이 없는 세션은 빈 히스토그램으로 남겨 매번 다시 묻지 않는다.
    /// 쿼리 자체가 실패하면(hr == nil) 비워 두고 다음 조회 때 다시 시도한다.
    /// 창은 ZoneDistributionEngine과 같은 28일 전 자정 ~ 지금, 창 밖 항목은 버린다.
    /// (Android: 세션별로 다시 읽지 않고 목록 요약용으로 기간 단위로 읽어 둔 심박을 나눠 쓴다)
    private fun backfillZoneHistograms(sessions: List<ExerciseSessionRecord>,
                                       hr: Map<String, List<TrainingGuideEngine.HeartRateSample>>?,
                                       now: Instant, zone: ZoneId) {
        val windowStart = startOfDay(now.minusSeconds(28 * 86_400L), zone)
        val recent = sessions.filter { it.startTime >= windowStart }
        val cache = ZoneTimeCache.load(cacheDir).toMutableMap()
        for (session in recent) {
            if (cache[session.metadata.id] != null) continue
            val samples = hr?.let {
                SessionSlices.within(it[session.metadata.dataOrigin.packageName].orEmpty(),
                                     session.startTime, session.endTime)
            } ?: continue
            // 방금 끝난 워크아웃은 워치의 심박 샘플이 아직 동기화 전일 수 있다 — 빈 히스토그램을
            // 굳히면 그 세션은 영원히 존 없음으로 남으므로, 1시간은 지나야 빈 결과를 캐시한다
            if (samples.isEmpty() && now.timeIntervalSince1970 - session.endTime.timeIntervalSince1970 < 3_600) continue
            cache[session.metadata.id] = ZoneHistogram.make(samples)
        }
        val pruned = ZoneTimeCache.prune(cache, keepingIDs = recent.map { it.metadata.id }.toSet())
        ZoneTimeCache.save(pruned, cacheDir)
        _zoneHistograms.value = pruned
    }

    // MARK: - 활력징후 (체력 배터리)

    /// 안정 심박 최근값 (이슈 #56) — vitals.restingHR.today는 기준선이 있어야 나와서 따로 본다.
    /// 28일 창 밖은 침묵(nil)
    private suspend fun latestRestingHR(now: Instant): Double? =
        quietly { client.readAll(RestingHeartRateRecord::class, lastDays(now, 28)) }
            ?.maxByOrNull { it.time }?.beatsPerMinute?.toDouble()

    /// 최근 28일 활력징후를 모아 스냅샷으로 만든다 — 없는 항목은 nil로 남긴다
    private suspend fun fetchVitals(now: Instant, zone: ZoneId): VitalsSnapshot {
        val range = lastDays(now, 29)
        return VitalsSnapshot(
            // (Android: HC는 RMSSD만 준다 — iOS SDNN과 절대값이 다르지만 엔진은 개인 기준선 대비 변화만 본다)
            hrvMs = reading(now, zone) {
                client.readAll(HeartRateVariabilityRmssdRecord::class, range).map { it.time to it.heartRateVariabilityMillis }
            },
            restingHR = reading(now, zone) {
                client.readAll(RestingHeartRateRecord::class, range).map { it.time to it.beatsPerMinute.toDouble() }
            },
            respiratoryRate = reading(now, zone) {
                client.readAll(RespiratoryRateRecord::class, range).map { it.time to it.rate }
            },
            // (Android: 피부 온도는 기기 기준선 대비 편차(°C)로 온다. 엔진은 오늘 − 개인 기준선만 보므로
            // 편차끼리 빼도 iOS 절대 온도와 같은 의미다)
            wristTempC = reading(now, zone) {
                client.readAll(SkinTemperatureRecord::class, range).flatMap { r -> r.deltas.map { it.time to it.delta.inCelsius } }
            },
            sleepHours = lastNightSleepHours(now, zone),
            // (Android: HC에 심박 회복(HRR) 타입이 없다 — nil이면 배터리 엔진이 그 팩터를 뺀다)
            hrr = null,
            sleepNights = fetchSleepNights(now, zone),
        )
    }

    /// 최근 2주 밤별 수면 상세 — 수면 질(깊은+렘 비율)·취침 규칙성 팩터의 재료 (제안 문서 A5).
    /// 밤 구분: 잠든 구간을 먼저 수면 블록으로 묶고, 블록이 끝난 날짜(기상일)에 배정해 기상일마다 가장 긴
    /// 블록만 밤 수면으로 인정한다(같은 날 낮잠 제외 — SleepBlocks.nightBlocks, 이슈 #107).
    /// 3시간 미만은 밤 수면으로 보지 않고 버린다.
    private suspend fun fetchSleepNights(now: Instant, zone: ZoneId): List<VitalsSnapshot.SleepNight> {
        val asleep = quietly { asleepStages(lastDays(now, 14)) } ?: return emptyList()
        // 날짜로 먼저 묶지 않는다 — 자정 전에 끝난 표본이 전날로 떨어져 밤이 잘린다 (이슈 #107).
        // 여러 앱 이중 기록 병합 후 기상일마다 가장 긴 블록만 센다 — 같은 날 낮잠은 제외 (이슈 #99)
        val nights = SleepBlocks.nightBlocks(asleep.map { SleepBlocks.Interval(it.startTime, it.endTime) }, zone)
        return nights.mapNotNull { (night, main) ->
            val blockStart = main.firstOrNull()?.start ?: return@mapNotNull null
            val blockEnd = main.lastOrNull()?.end ?: return@mapNotNull null
            val asleepHours = SleepBlocks.asleepSec(main) / 3_600
            if (!(asleepHours >= 3)) return@mapNotNull null

            // 단계 비율은 단계를 기록한 표본만으로 계산 — 단계 없는 "잠" 표본이 분모에 섞이면
            // 비율이 왜곡된다. 낮잠 표본은 빼고 그 블록 안만.
            val blockSamples = asleep.filter { it.startTime >= blockStart && it.endTime <= blockEnd }
            val stageSec = blockSamples.filter { it.stage in stagedTypes }.sumOf { seconds(it.startTime, it.endTime) }
            val deepRemSec = blockSamples.filter { it.stage in deepRemTypes }.sumOf { seconds(it.startTime, it.endTime) }

            // 취침 시각: 정오(전날 12:00) 기준 경과 분 — 자정 넘김(23시=660, 새벽 1시=780)을
            // 연속값으로 다뤄 표준편차 계산이 깨지지 않게 한다
            val bedtime = seconds(night.minusSeconds(12 * 3_600L), blockStart) / 60
            VitalsSnapshot.SleepNight(date = night,
                                      asleepHours = asleepHours,
                                      deepRemFraction = if (stageSec > 0) deepRemSec / stageSec else null,
                                      bedtimeMinutes = bedtime)
        }
    }

    /// 최근 29일 표본 → 오늘 값 + 그 이전 일평균 기준선
    ///
    /// 활력징후는 주로 수면 중 기록되므로 "오늘 값"은 오늘 날짜의 표본 평균,
    /// 없으면 어제 표본으로 대체한다. 기준선은 그보다 앞선 날들의 일평균 평균.
    private suspend fun reading(now: Instant, zone: ZoneId,
                                fetch: suspend () -> List<Pair<Instant, Double>>): VitalsSnapshot.Reading? {
        val samples = quietly { fetch() }
        if (samples.isNullOrEmpty()) return null
        val byDay = LinkedHashMap<Instant, MutableList<Double>>()
        for ((time, value) in samples) {
            byDay.getOrPut(startOfDay(time, zone)) { mutableListOf() }.add(value)
        }
        fun mean(values: List<Double>): Double = values.fold(0.0) { a, b -> a + b } / values.size

        val today = startOfDay(now, zone)
        val todayKey = if (byDay[today] != null) today else today.minusSeconds(86_400)
        val todayValues = byDay[todayKey] ?: return null
        val baselineDays = byDay.filterKeys { it < todayKey }
        if (baselineDays.isEmpty()) return null
        return VitalsSnapshot.Reading(today = mean(todayValues),
                                      baseline = mean(baselineDays.values.map(::mean)),
                                      baselineDays = baselineDays.size)
    }

    /// 지난밤 수면 시간 — 최근 24시간 안에 끝난 수면 블록 중 가장 긴 것을 겹침 없이 합산한다(낮잠 제외)
    private suspend fun lastNightSleepHours(now: Instant, zone: ZoneId): Double? {
        val asleep = quietly { asleepStages(TimeRangeFilter.between(now.minusSeconds(36 * 3_600L), now)) } ?: return null
        val cutoff = now.minusSeconds(24 * 3_600L)
        // 여러 앱이 같은 밤을 중복 기록할 수 있어 구간을 병합하고,
        // 낮잠이 밤 수면에 더해지지 않게 기상일마다 가장 긴 블록만 센다 (이슈 #99)
        // 24시간 컷은 표본이 아니라 블록 기준 — 표본 종료로 자르면 23:30에 볼 때 지난밤 22:00–23:30의
        // 짧은 스테이지 표본이 잘려 나간다 (이슈 #107). 24시간 안에 끝난 블록 중 가장 긴 것을 지난밤으로
        // 본다 — 마지막 블록만 보면 자정 직후 30분 눈 붙인 기록이 진짜 지난밤을 가린다.
        val recent = SleepBlocks.nightBlocks(asleep.map { SleepBlocks.Interval(it.startTime, it.endTime) }, zone)
            .filter { (it.block.lastOrNull()?.end ?: Instant.MIN) > cutoff }
        val lastNight = recent.maxByOrNull { SleepBlocks.asleepSec(it.block) } ?: return null
        val total = SleepBlocks.asleepSec(lastNight.block)
        if (total < 3_600) return null  // 1시간 미만이면 수면 기록으로 보지 않는다
        return total / 3_600
    }

    /// 잠든 구간 — 단계가 있으면 잠 단계만, 단계 없는 세션은 세션 전체를 "잠"으로 본다
    /// (iOS asleepUnspecified 대응). 깸·침대 밖·미상 단계는 뺀다
    private suspend fun asleepStages(range: TimeRangeFilter): List<SleepSessionRecord.Stage> =
        client.readAll(SleepSessionRecord::class, range).flatMap { session ->
            if (session.stages.isEmpty()) {
                listOf(SleepSessionRecord.Stage(session.startTime, session.endTime, SleepSessionRecord.STAGE_TYPE_SLEEPING))
            } else {
                session.stages.filter { it.stage in asleepTypes }
            }
        }

    /// 최근 12주 VO₂max 표본 — 심폐 체력 추이 카드의 재료
    private suspend fun fetchVo2Max(now: Instant): List<Pair<Instant, Double>> =
        quietly { client.readAll(Vo2MaxRecord::class, lastDays(now, 84)) }
            ?.map { it.time to it.vo2MillilitersPerMinuteKilogram }.orEmpty()

    private fun lastDays(now: Instant, days: Long): TimeRangeFilter =
        TimeRangeFilter.between(now.minusSeconds(days * 86_400), now)

    private companion object {
        /// 한 번에 계산할 워크아웃 수 — 워크아웃마다 거리 표본 조회가 필요해 기동당 비용을 묶는다
        const val bestEffortBatchSize = 60
        val runningTypes = setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                                 ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)
        val asleepTypes = setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_LIGHT,
                                SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM)
        /// 단계를 기록한 표본 — iOS asleepCore·Deep·REM 대응 (LIGHT = core)
        val stagedTypes = setOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP,
                                SleepSessionRecord.STAGE_TYPE_REM)
        val deepRemTypes = setOf(SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM)
    }
}

// MARK: - 두 스토어 공용 (HealthStore·WorkoutDetailStore)

/// 페이지를 끝까지 넘겨 모두 읽는다
internal suspend fun <T : Record> HealthConnectClient.readAll(
    type: KClass<T>,
    range: TimeRangeFilter,
    origins: Set<DataOrigin> = emptySet(),
    ascending: Boolean = true,
): List<T> {
    val records = mutableListOf<T>()
    var token: String? = null
    do {
        val page = readRecords(ReadRecordsRequest(type, range, dataOriginFilter = origins,
                                                  ascendingOrder = ascending, pageSize = 5_000, pageToken = token))
        records += page.records
        token = page.pageToken
    } while (!token.isNullOrEmpty())
    return records
}

/// 부가 조회 실패는 조용히 nil — 목록·상세 표시를 막지 않는다. 취소는 그대로 올린다
internal suspend fun <T> quietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

internal fun ExerciseSessionRecord.timeRange(): TimeRangeFilter = TimeRangeFilter.between(startTime, endTime)

/// 세션을 기록한 앱 하나 — iOS의 sameSourceDuring 폴백 규칙 (이슈 #165 #166)
internal fun ExerciseSessionRecord.origin(): Set<DataOrigin> = setOf(metadata.dataOrigin)

/// 세션의 정지 구간 — HC 일시정지 세그먼트를 정지/재개 표식으로 바꿔 ActiveTimeline에 넘긴다 (이슈 #47).
/// (Android: HC는 활동 시간을 따로 주지 않아 iOS의 "벽시계 − 활동 시간 > 30초면 거리 공백으로 추정"
/// 폴백은 성립하지 않는다 — 세그먼트가 없으면 정지 없음)
internal fun ExerciseSessionRecord.pauses(): List<ClosedRange<Instant>> {
    val markers = segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
        .sortedBy { it.startTime }
        .flatMap {
            listOf(ActiveTimeline.MarkerEvent(it.startTime, ActiveTimeline.Marker.pause),
                   ActiveTimeline.MarkerEvent(it.endTime, ActiveTimeline.Marker.resume))
        }
    return ActiveTimeline.pauses(markers, startTime, endTime)
}

/// 활동 시간(초) — iOS HKWorkout.duration 대응, 정지 구간을 뺀다
internal fun ExerciseSessionRecord.activeSeconds(): Double =
    ActiveTimeline.activeSeconds(startTime, endTime, pauses())

/// 세션 시간대 + 세션을 기록한 앱 하나의 거리 표본 — 베스트 에포트 백필·상세 공용.
/// (Android: HC에는 iOS의 "워크아웃 연결 샘플"이 없어 iOS 폴백 규칙(같은 기록 기기)만 쓴다 —
/// 시간대로만 잡으면 폰·워치 앱이 같은 구간을 각각 기록했을 때 거리가 두 번 더해진다)
internal suspend fun distanceSamples(client: HealthConnectClient, session: ExerciseSessionRecord): List<DistanceSample> =
    client.readAll(DistanceRecord::class, session.timeRange(), session.origin())
        .map { DistanceSample(it.startTime, it.endTime, it.distance.inMeters) }
        .sortedBy { it.start }

/// 세션 동안 같은 앱이 기록한 심박 표본 — 시각 오름차순. 심박 레코드는 여러 표본을 묶어 세션 밖까지
/// 걸칠 수 있어 표본 시각으로 한 번 더 자른다
internal suspend fun heartRateSamples(client: HealthConnectClient,
                                      session: ExerciseSessionRecord): List<TrainingGuideEngine.HeartRateSample> =
    client.readAll(HeartRateRecord::class, session.timeRange(), session.origin())
        .flatMap { it.samples }
        .filter { it.time >= session.startTime && it.time <= session.endTime }
        .sortedBy { it.time }
        .map { TrainingGuideEngine.HeartRateSample(it.time, it.beatsPerMinute.toDouble()) }

private fun startOfDay(time: Instant, zone: ZoneId): Instant = time.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

private fun seconds(from: Instant, to: Instant): Double = to.timeIntervalSince1970 - from.timeIntervalSince1970
