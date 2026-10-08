package com.jkpark.runwrap.health

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import com.jkpark.runwrap.engine.ActiveTimeline
import com.jkpark.runwrap.engine.DriftEngine
import com.jkpark.runwrap.engine.FormEngine
import com.jkpark.runwrap.engine.FormSnapshot
import com.jkpark.runwrap.engine.HeartRateProfile
import com.jkpark.runwrap.engine.KeyValueStore
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.TrackPoint
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.WorkoutDetail
import com.jkpark.runwrap.engine.instantSince1970
import com.jkpark.runwrap.engine.timeIntervalSince1970
import com.jkpark.runwrap.store.DemoMode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jkpark.runwrap.engine.WorkoutDetailStore as WorkoutDetailRules

/// 세션 상세 화면용 추가 데이터 조회 — 경로·구간 페이스·심박 존·케이던스.
/// RunSummary(목록)에 없는 값만 지연 조회한다. 합성 규칙(데모)은 엔진 WorkoutDetailStore(WorkoutDetailRules)에 있다.
/// 모든 함수는 메인 스레드에서 부른다.
class WorkoutDetailStore(context: Context, private val settings: KeyValueStore) {
    private val context = context.applicationContext

    private val _detail = MutableStateFlow<WorkoutDetail?>(null)
    val detail: StateFlow<WorkoutDetail?> = _detail.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    /// 세션 조회 자체가 실패했는지 — '데이터 없음'(빈 detail)과 구분해 다시 시도를 띄운다.
    /// 실패하면 detail을 null로 둬 load() 재호출이 가드에 막히지 않는다 (이슈 #102)
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()
    /// 주법 기준선 재료 — 세션 직전 28일 야외 세션들의 스냅샷 (계획서 M4)
    private val _formSnapshots = MutableStateFlow<List<FormSnapshot>>(emptyList())
    val formSnapshots: StateFlow<List<FormSnapshot>> = _formSnapshots.asStateFlow()
    /// 스냅샷 조회 중 (이슈 #92) — (Android: 스냅샷이 HC를 조회하지 않아 늘 false. 화면 대응용으로 둔다)
    private val _isLoadingSnapshots = MutableStateFlow(false)
    val isLoadingSnapshots: StateFlow<Boolean> = _isLoadingSnapshots.asStateFlow()
    /// (Android 전용) 경로가 세션별 동의를 기다리는지 — Android 14는 경로 읽기를 세션마다 묻는다.
    /// 화면이 routeConsentContract(세션 id)를 띄우고 결과를 applyConsentedRoute로 넘긴다
    private val _routeConsentRequired = MutableStateFlow(false)
    val routeConsentRequired: StateFlow<Boolean> = _routeConsentRequired.asStateFlow()
    /// 세션별 경로 동의 계약 — 입력은 세션 id(RunSummary.id), 결과는 경로 원본(거절·없음이면 빈 목록)
    val routeConsentContract: ActivityResultContract<String, List<TrackPoint>> = object : ActivityResultContract<String, List<TrackPoint>>() {
        private val consent = ExerciseRouteRequestContract()
        override fun createIntent(context: Context, input: String): Intent = consent.createIntent(context, input)
        override fun parseResult(resultCode: Int, intent: Intent?): List<TrackPoint> =
            consent.parseResult(resultCode, intent)?.route.orEmpty().trackPoints()
    }

    /// 동의 시트 결과를 상세에 싣는다 — 거절해도 플래그는 내려 같은 세션에서 다시 묻지 않는다
    fun applyConsentedRoute(route: List<TrackPoint>) {
        _routeConsentRequired.value = false
        val detail = _detail.value ?: return
        if (route.isNotEmpty()) _detail.value = detail.copy(route = route)
    }

    /// 스냅샷 조회 세대 — load()는 detail 조회 중 세대가 바뀌었으면 옛 others로 재조회하지 않는다
    private var snapshotGeneration = 0

    /// others: 기준선 재료 후보(전체 목록 그대로) — 창·표본 가드는 FormEngine이 건다
    /// heartRate: 화면이 TrainingGuideEngine.heartRateProfile로 해석한 값만 받는다 (이슈 #48·#56)
    suspend fun load(run: RunSummary, others: List<RunSummary> = emptyList(), heartRate: HeartRateProfile) {
        if (_detail.value != null || _isLoading.value) return
        _isLoading.value = true
        _loadFailed.value = false
        _routeConsentRequired.value = false
        try {
            // detail 조회 동안 목록이 로드돼 화면이 reloadSnapshots를 이미 불렀다면
            // 진입 시점의 (비어 있을 수 있는) others로 그 조회를 덮지 않는다 (이슈 #92)
            val startGeneration = snapshotGeneration
            // 데모 모드에서는 HC를 건드리지 않고 합성 상세를 만든다 (DemoMode)
            if (DemoMode.isActive(settings)) {
                _detail.value = WorkoutDetailRules.synthetic(run, heartRate)
            } else {
                _detail.value = fetch(run, heartRate)
                _loadFailed.value = _detail.value == null
            }
            if (snapshotGeneration != startGeneration) return
            reloadSnapshots(others, excluding = run)
        } finally {
            _isLoading.value = false
        }
    }

    /// 기준선 스냅샷만 다시 만든다 — 진입 시 목록이 아직 로드 전이라 빈 목록으로
    /// 한 번 불렸을 때, 목록이 로드되면 화면이 부른다. detail은 재조회하지 않는다 (이슈 #92).
    /// (Android: HC에 수직 진폭·접촉 시간이 없어 목록이 백필한 케이던스만 쓴다 — 세션별 조회 없음)
    fun reloadSnapshots(others: List<RunSummary>, excluding: RunSummary) {
        snapshotGeneration += 1
        val run = excluding
        _formSnapshots.value = if (DemoMode.isActive(settings)) {
            WorkoutDetailRules.syntheticSnapshots(others, run)
        } else {
            // 창은 '지금'이 아니라 run.start 기준 — 과거 세션을 그 이후 기록과 비교하지 않는다 (이슈 #92)
            val cutoff = instantSince1970(run.start.timeIntervalSince1970 - FormEngine.windowDays * 86_400)
            others.filter { !it.isIndoor && it.id != run.id && it.start >= cutoff && it.start < run.start }
                .take(20)  // 기준선 평균에는 20회면 충분하다
                .map { FormSnapshot(id = it.id, start = it.start, cadenceSpm = it.cadenceSpm,
                                    verticalOscillationCm = null, groundContactMs = null) }
        }
    }

    // MARK: - 실기기: Health Connect 조회

    /// 세션 조회가 에러로 실패하면 null — 세션은 있는데 경로·스플릿이 없는 정상 케이스
    /// (실내 등)는 빈 detail을 돌려준다. 둘을 섞으면 실패가 '기록 없음'으로 굳는다 (이슈 #102)
    private suspend fun fetch(run: RunSummary, heartRate: HeartRateProfile): WorkoutDetail? {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return WorkoutDetail()
        val client = HealthConnectClient.getOrCreate(context)
        val session = try {
            client.readRecord(ExerciseSessionRecord::class, run.id).record
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        // 실내(트레드밀) 세션에는 경로가 없다 — 쿼리 자체를 생략한다 (계획서 M1)
        // (Android: 상승 고도·수직 진폭·접촉 시간·보폭은 HC에서 받지 않아 null — 화면 미노출 가드로 빠진다)
        var route = emptyList<TrackPoint>()
        var power: Double? = null
        if (!run.isIndoor) {
            // 동의가 필요하면 빈 경로로 두고 화면에 동의 시트를 맡긴다 (NoData는 경로 없음)
            when (val result = session.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> route = result.exerciseRoute.route.trackPoints()
                is ExerciseRouteResult.ConsentRequired -> _routeConsentRequired.value = true
                else -> {}
            }
            power = quietly {
                client.readAll(PowerRecord::class, session.timeRange(), session.origin())
                    .flatMap { it.samples }
                    .filter { it.time >= session.startTime && it.time <= session.endTime }
                    .map { it.power.inWatts }
            }?.takeIf { it.isNotEmpty() }?.let { it.fold(0.0) { a, b -> a + b } / it.size }
        }

        val hrSamples = quietly { heartRateSamples(client, session) }.orEmpty()
        val distanceSamples = quietly { distanceSamples(client, session) }.orEmpty()
        // 오토포즈·일시정지 구간 — 스플릿·드리프트에서 정지 시간을 뺀다 (이슈 #47)
        val pauses = session.pauses()
        val durationSec = session.activeSeconds()

        // 케이던스: 걸음 수 합 ÷ 분 — (Android: 보폭이 없어 iOS ① 속도÷보폭 분기는 없다)
        val steps = quietly {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), session.timeRange(), session.origin()))
        }?.get(StepsRecord.COUNT_TOTAL)

        return WorkoutDetail(
            route = route,
            heartRateSamples = hrSamples,
            splits = ActiveTimeline.splits(distanceSamples, pauses).map { WorkoutDetail.Split(it.index, it.paceSecPerKm) },
            zones = if (hrSamples.isEmpty()) null else TrainingGuideEngine.heartRateZones(hrSamples, heartRate),
            cadenceSpm = if (steps != null && durationSec > 60) steps.toDouble() / (durationSec / 60) else null,
            maxHeartRateBpm = if (hrSamples.isEmpty()) null else TrainingGuideEngine.sessionPeakBpm(hrSamples.map { it.bpm }),
            heartRate = if (hrSamples.isEmpty()) null else heartRate,
            // 심박 드리프트 — 존·스플릿용으로 이미 가져온 샘플을 재사용한다 (추가 쿼리 없음, 제안 문서 A2)
            drift = if (hrSamples.isEmpty() || distanceSamples.isEmpty()) null else DriftEngine.compute(
                hrSamples = hrSamples.map { DriftEngine.HeartRateSample(it.time, it.bpm) },
                distanceSamples = distanceSamples,
                start = session.startTime,
                durationSec = durationSec,
                pauses = pauses,
                end = session.endTime),
            runningPowerW = power,
        )
    }
}

/// 원본 전체를 보관한다 — 솎기는 표시 직전(thinnedCoordinates)에 한다 (#222 선행)
/// (Android: HC에는 속도가 없어 speedMps = null. 고도는 HC가 값이 없을 때 null을 준다)
private fun List<ExerciseRoute.Location>.trackPoints(): List<TrackPoint> = map {
    TrackPoint(lat = it.latitude, lon = it.longitude, time = it.time,
               elevationM = it.altitude?.inMeters,
               horizontalAccuracyM = it.horizontalAccuracy?.inMeters,
               speedMps = null)
}
