package com.jkpark.runwrap.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.jkpark.runwrap.engine.DriftEngine
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.dayOf
import com.jkpark.runwrap.engine.secondsBetween
import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// 지도 폴리라인용 좌표 — 지도 SDK 타입(Google Maps LatLng)에 스토어가 묶이지 않게 분리
data class GeoPoint(val latitude: Double, val longitude: Double)

/// 세션 상세 화면용 추가 데이터 — 경로·구간 페이스·심박 존·케이던스.
/// RunSummary(목록)에 없는 값만 지연 조회한다. iOS `WorkoutDetail` 이식.
///
/// iOS 대비 빠진 필드와 이유:
/// - elevationM — HC 상승 고도(ElevationGained)는 선언 권한 목록에 없다 (계획서 §2.2 제외 항목)
/// - 러닝 다이내믹스 4종 — HC에 진폭·접촉시간 레코드가 없어 주법 섹션 자체가 없다 (계획서 §2.2)
data class WorkoutDetail(
    val route: List<GeoPoint> = emptyList(),
    /// 경로는 HC에서 세션별 추가 동의가 필요할 수 있다 — true면 M5에서
    /// `ExerciseRouteRequestContract` 런처로 동의를 청한다 (iOS에는 없는 개념)
    val routeConsentRequired: Boolean = false,
    val splits: List<Split> = emptyList(),
    val zones: List<Double>? = null,          // Z1~Z5 비율 (합 1)
    val cadenceSpm: Double? = null,
    val hrMaxEstimated: Boolean = false,      // true면 생년이 없어 HRmax 190 폴백
    /// 세션 최고 심박(bpm)과 존 계산에 쓴 HRmax — 존 카드의 "최고 심박 · HRmax 대비 %" 라인 재료
    val maxHeartRateBpm: Double? = null,
    val hrMaxBpm: Double? = null,
    /// 심박 드리프트(Pw:HR 디커플링) — 존·스플릿용 샘플을 재사용해 추가 쿼리 없음 (제안 문서 A2)
    val drift: DriftEngine.Result? = null,
) {
    data class Split(val index: Int, val paceSecPerKm: Double)   // index는 1부터 (실제 km 번호)
}

/// 세션 상세 지연 조회 — iOS `WorkoutDetailStore.swift` 이식 (계획서 M4).
///
/// iOS와의 구조 차이:
/// - 생년월일: HC에는 프로필 레코드가 없어 앱이 받은 값(온보딩, M5)을 인자로 넘긴다.
///   null이면 iOS와 같은 190 폴백 + 추정 표기.
/// - 시리즈 샘플: 워크아웃 연결(linked) 개념이 없어 세션을 기록한 앱(dataOrigin)으로
///   좁힌다 — 같은 구간을 두 앱이 기록했을 때의 중복 합산 방지 (iOS sameSourceDuring 대응).
class WorkoutDetailStore(private val context: Context) {
    private val _detail = MutableStateFlow<WorkoutDetail?>(null)
    val detail: StateFlow<WorkoutDetail?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun load(run: RunSummary, birthYear: Int? = null, now: Instant = Instant.now()) {
        if (_detail.value != null || _isLoading.value) return
        _isLoading.value = true
        try {
            // 합성 세션(id "demo-N")은 HC에 없다 — id 시드 합성 상세를 만든다 (DemoData 참조)
            _detail.value = if (run.id.startsWith("demo-")) synthetic(run)
                            else fetch(run, birthYear, now)
        } finally {
            _isLoading.value = false
        }
    }

    /// 세션별 경로 동의(ExerciseRouteRequestContract) 결과 반영 — 동의 배너를 지도로 바꾼다 (계획서 M5)
    fun applyConsentRoute(points: List<GeoPoint>) {
        _detail.value = _detail.value?.copy(route = thin(points), routeConsentRequired = false)
    }

    // MARK: - 실기기: Health Connect 조회

    private suspend fun fetch(run: RunSummary, birthYear: Int?, now: Instant): WorkoutDetail {
        val session = runCatching {
            client.readRecord(ExerciseSessionRecord::class, run.id).record
        }.getOrNull() ?: return WorkoutDetail()

        var route: List<GeoPoint> = emptyList()
        var consentRequired = false
        // 실내(트레드밀) 세션에는 경로가 없다 — 확인 자체를 생략한다 (계획서 M1)
        if (!run.isIndoor) {
            when (val result = session.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> route = thin(result.exerciseRoute.route)
                    .map { GeoPoint(it.latitude, it.longitude) }
                is ExerciseRouteResult.ConsentRequired -> consentRequired = true
                else -> {}   // NoData — 경로 없이 기록된 세션
            }
        }

        val hrSamples = readAll(HeartRateRecord::class, session)
            .flatMap { record ->
                record.samples.map { DriftEngine.HrSample(it.time, it.beatsPerMinute.toDouble()) }
            }
            .sortedBy { it.time }

        val distanceSamples = readAll(DistanceRecord::class, session)
            .map { DriftEngine.DistanceSample(it.startTime, it.endTime, it.distance.inMeters) }
            .sortedBy { it.start }

        val (hrMax, estimated) = heartRateMax(birthYear, now)
        val durationSec = secondsBetween(session.startTime, session.endTime)

        return WorkoutDetail(
            route = route,
            routeConsentRequired = consentRequired,
            splits = if (distanceSamples.isNotEmpty()) splits(distanceSamples) else emptyList(),
            zones = if (hrSamples.isNotEmpty()) zoneFractions(hrSamples, hrMax) else null,
            // 케이던스: 목록(HealthConnectStore)이 백필한 값을 재사용하고, 백필 창(28일) 밖의
            // 과거 세션만 걸음 수로 새로 계산한다 — iOS의 보폭 우선 산식은 다이내믹스가 없어 불가
            cadenceSpm = run.cadenceSpm ?: stepsCadence(session, durationSec),
            hrMaxEstimated = estimated,
            maxHeartRateBpm = hrSamples.maxOfOrNull { it.bpm },
            hrMaxBpm = if (hrSamples.isNotEmpty()) hrMax else null,
            // 심박 드리프트 — 존·스플릿용으로 이미 가져온 샘플을 재사용한다 (추가 쿼리 없음)
            drift = if (hrSamples.isNotEmpty() && distanceSamples.isNotEmpty()) {
                DriftEngine.compute(hrSamples, distanceSamples,
                                    start = session.startTime, durationSec = durationSec)
            } else null,
        )
    }

    /// 세션 평균 케이던스 폴백 = 걸음 수 합 ÷ 분 — 세션을 기록한 앱의 걸음만 합산
    private suspend fun stepsCadence(session: ExerciseSessionRecord,
                                     durationSec: Double): Double? {
        if (durationSec <= 60) return null
        val steps = runCatching {
            readAll(StepsRecord::class, session).sumOf { it.count }
        }.getOrNull() ?: return null
        if (steps <= 0) return null
        return steps / (durationSec / 60)
    }

    /// Tanaka 공식 HRmax = 208 − 0.7×나이. 생년이 없으면 190 폴백(추정 표기)
    private fun heartRateMax(birthYear: Int?, now: Instant): Pair<Double, Boolean> {
        if (birthYear != null) {
            val age = dayOf(now).year - birthYear
            if (age in 10..100) return 208 - 0.7 * age to false
        }
        return 190.0 to true
    }

    /// 세션 시간 창 + 세션을 기록한 앱(dataOrigin)으로 좁혀 끝까지 읽는다
    private suspend fun <T : Record> readAll(type: KClass<T>,
                                             session: ExerciseSessionRecord): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                dataOriginFilter = setOf(session.metadata.dataOrigin),
                pageSize = 1_000,
                pageToken = pageToken,
            ))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    companion object {
        /// 폴리라인은 ~600점이면 충분 — 과한 포인트는 솎는다
        private fun <T> thin(points: List<T>): List<T> {
            val stride = maxOf(1, points.size / 600)
            return points.filterIndexed { index, _ -> index % stride == 0 }
        }

        /// 심박 샘플 → Z1~Z5 시간 비율. 샘플 간격(≤15초 캡)으로 가중한다.
        internal fun zoneFractions(samples: List<DriftEngine.HrSample>,
                                   hrMax: Double): List<Double> {
            val seconds = DoubleArray(5)
            for ((i, sample) in samples.withIndex()) {
                val weight = if (i + 1 < samples.size) {
                    min(secondsBetween(sample.time, samples[i + 1].time), 15.0)
                } else 5.0   // 마지막 샘플은 다음 간격이 없다 — 통상 간격으로 가정
                val ratio = sample.bpm / hrMax
                val zone = if (ratio < 0.6) 0 else if (ratio < 0.7) 1
                           else if (ratio < 0.8) 2 else if (ratio < 0.9) 3 else 4
                seconds[zone] += maxOf(weight, 0.0)
            }
            val total = seconds.sum()
            if (total <= 0) return List(5) { 0.0 }
            return seconds.map { it / total }
        }

        /// 누적 거리 샘플 → km 스플릿. km 경계는 샘플 사이를 선형 보간한다.
        /// index는 실제 km 번호 — 데이터 오류로 건너뛴 구간이 있어도 눈금이 밀리지 않는다.
        internal fun splits(samples: List<DriftEngine.DistanceSample>): List<WorkoutDetail.Split> {
            val result = mutableListOf<WorkoutDetail.Split>()
            var cumulative = 0.0                            // m
            var boundaryTime: Instant? = samples.firstOrNull()?.start
            var nextBoundary = 1_000.0

            for (sample in samples) {
                val meters = sample.meters
                val before = cumulative
                cumulative += meters
                while (cumulative >= nextBoundary && meters > 0) {
                    val fraction = (nextBoundary - before) / meters
                    val duration = secondsBetween(sample.start, sample.end)
                    val crossing = sample.start.plusMillis((duration * fraction * 1_000).toLong())
                    boundaryTime?.let { start ->
                        val sec = secondsBetween(start, crossing)
                        if (sec > 60) {   // 60초/km 미만은 데이터 오류로 본다
                            result.add(WorkoutDetail.Split(index = (nextBoundary / 1_000).toInt(),
                                                           paceSecPerKm = sec))
                        }
                    }
                    boundaryTime = crossing
                    nextBoundary += 1_000
                }
            }
            return result
        }

        // MARK: - 데모 모드: 합성 데이터 (run.id 시드 — 같은 세션은 항상 같은 모양)

        internal fun synthetic(run: RunSummary): WorkoutDetail {
            val rng = DetailRng(run.id.hashCode().toLong().toULong())
            val km = run.distanceKm ?: 8.0
            val basePace = run.paceSecPerKm ?: 360.0

            // 실내(트레드밀)에는 경로가 없다 — 스플릿·존·케이던스는 그대로 만든다 (계획서 M1)
            val route = if (run.isIndoor) emptyList() else {
                // 한강 언저리 순환 코스 느낌의 타원 + 흔들림
                val centerLat = 37.520 + rng.unit() * 0.02
                val centerLon = 126.94 + rng.unit() * 0.03
                val radius = 0.0016 * sqrt(km)
                val points = 140
                (0..points).map { i ->
                    val t = i.toDouble() / points * 2 * PI
                    val wobble = 1 + 0.10 * sin(t * 3 + rng.offset) + 0.05 * sin(t * 7)
                    GeoPoint(latitude = centerLat + radius * wobble * sin(t) * 0.72,
                             longitude = centerLon + radius * wobble * cos(t))
                }
            }

            // 스플릿: 기본 페이스 ± 8초 흔들림, 마지막 1/4은 점점 처진다 (시안의 후반 드리프트)
            val fullKm = maxOf(km.toInt(), 1)
            val lastQuarterStart = fullKm - maxOf(fullKm / 4, 1)
            val splits = (1..fullKm).map { i ->
                var pace = basePace + (rng.unit() - 0.5) * 16
                if (i > lastQuarterStart) pace += (i - lastQuarterStart) * 6.0
                WorkoutDetail.Split(index = i, paceSecPerKm = pace)
            }

            val raw = listOf(0.08, 0.22, 0.44, 0.20, 0.06).map { it + (rng.unit() - 0.5) * 0.04 }
            val sum = raw.sum()
            val zones = raw.map { maxOf(it, 0.01) / sum }

            // 케이던스는 시드를 분리해 둔다 — iOS syntheticDynamics의 케이던스 부분만 이식
            // (진폭·접촉시간 등 나머지 다이내믹스는 HC에 없어 섹션째 제외, 계획서 §2.2)
            val cadence = 163 + DetailRng(run.id.hashCode().toLong().toULong() + 0x51DEuL).unit() * 14

            // 최고 심박·드리프트 합성 — 기존 rng 호출 뒤에 둬 위 값들의 재현성을 깨지 않는다
            val maxHR = min((run.avgHeartRate ?: 150.0) + 22 + rng.unit() * 12, 188.0)
            val drift = if (run.durationSec >= 1_800) {
                // 후반 처짐 스플릿과 결이 맞는 완만한 양수 디커플링 (2~8%)
                val decoupling = 2 + rng.unit() * 6
                val firstEF = 1.9 + rng.unit() * 0.4
                DriftEngine.Result(
                    decouplingPct = decoupling,
                    firstHalfEF = firstEF,
                    secondHalfEF = firstEF / (1 + decoupling / 100),
                    tone = if (decoupling < 5) RRTone.STEADY else RRTone.CAUTION,
                )
            } else null

            return WorkoutDetail(
                route = route,
                splits = splits,
                zones = zones,
                cadenceSpm = cadence,
                hrMaxEstimated = true,        // 합성은 생년이 없어 폴백 HRmax와 맞춘다
                maxHeartRateBpm = maxHR,
                hrMaxBpm = 190.0,
                drift = drift,
            )
        }
    }
}

/// 재현 가능한 경량 난수 — iOS WorkoutDetailStore의 SplitMix64 이식.
/// DemoData의 SplitMix64와 달리 생성 시 한 번 뽑아 위상(offset)을 만든다 —
/// 경로 흔들림(sin 위상)이 세션마다 달라지게 하는 재료. 스트림도 그만큼 어긋난다.
private class DetailRng(seed: ULong) {
    private var state: ULong
    val offset: Double   // 0..<2π

    init {
        state = seed + 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        offset = ((z xor (z shr 31)) shr 11).toDouble() / (1L shl 53).toDouble() * 2 * PI
    }

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /// 0..<1
    fun unit(): Double = (next() shr 11).toDouble() / (1L shl 53).toDouble()
}
