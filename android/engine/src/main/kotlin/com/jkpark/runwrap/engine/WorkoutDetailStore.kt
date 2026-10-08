package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/// 세션 상세 화면용 추가 데이터 — 경로·구간 페이스·심박 존·케이던스·상승 고도.
/// RunSummary(목록)에 없는 값만 지연 조회한다.
data class WorkoutDetail(
    /// 경로 원본 — 솎지 않은 전체 점(시각·고도·속도 포함). 솎기는 표시 직전에 한다 (#222)
    val route: List<TrackPoint> = emptyList(),
    val splits: List<Split> = emptyList(),
    val zones: List<Double>? = null,          // Z1~Z5 비율 (합 1)
    val cadenceSpm: Double? = null,
    val elevationM: Double? = null,
    /// 세션 최고 심박(bpm) — 존 카드의 "최고 심박 · HRmax 대비 %" 라인 재료
    val maxHeartRateBpm: Double? = null,
    /// 존 계산에 쓴 심박 기준 — 존 카드의 방식·HRmax 출처·HRmax 대비 % 재료 (이슈 #56)
    val heartRate: HeartRateProfile? = null,
    /// 심박 드리프트(Pw:HR 디커플링) — 존·스플릿용 샘플을 재사용해 추가 쿼리 없음 (제안 문서 A2)
    val drift: DriftEngine.Result? = null,

    // 러닝 다이내믹스 (기획서 §4.8, 계획서 M4) — 실외 세션에만 기록된다 (실내는 애플이 기록하지 않음)
    val verticalOscillationCm: Double? = null,
    val groundContactMs: Double? = null,
    val strideLengthM: Double? = null,
    val runningPowerW: Double? = null,

    /// Apple 운동 노력도 — iOS 18 미만이거나 기록이 없으면 nil(미노출) (이슈 #178)
    val effort: EffortScore? = null,
) {
    data class Split(
        val index: Int,            // 1부터
        val paceSecPerKm: Double,
    ) {
        val id: Int get() = index
    }
}

/// Apple 운동 노력도 (이슈 #178) — 워치에서 입력한 1~10 척도. 직접 입력 > Apple 추정
data class EffortScore(
    val score: Double,
    val isEstimated: Boolean,
) {
    /// Apple 척도 구간: 1~3 편안 · 4~6 보통 · 7~8 힘듦 · 9~10 전력
    val label: String get() = label(score)
    val sourceLabel: String get() = if (isEstimated) "Apple 추정" else "직접 입력"

    companion object {
        /// 구간 경계는 정수 척도 사이(3.5·6.5·8.5)에 둔다 — 추정값이 소수로 와도 가까운 구간에 붙는다
        fun label(score: Double): String {
            if (score < 3.5) return "편안"
            if (score < 6.5) return "보통"
            if (score < 8.5) return "힘듦"
            return "전력"
        }
    }
}

/// (Android: iOS 스토어 중 데모 모드 합성부(순수 로직)만 옮긴다. HealthKit 조회·`@Published` 상태는
///  :app의 Health Connect 스토어가 맡는다)
object WorkoutDetailStore {
    // MARK: - 데모 모드: 합성 데이터 (syntheticSeed — 같은 세션은 항상 같은 모양)

    /// 합성 시드 — run.id.hashValue는 프로세스마다 달라져(Hasher 무작위 시드) 쓰지 않는다.
    /// uuid 16바이트 + 시작 시각 비트 패턴을 FNV-1a(64비트)로 섞는다 (이슈 #102)
    /// (Android: id가 UUID 형식이면 iOS와 같은 16바이트, 아니면(Health Connect id 등) UTF-8 바이트)
    fun syntheticSeed(run: RunSummary): ULong {
        val idBytes = uuidBytes(run.id) ?: run.id.encodeToByteArray()
        val bits = run.start.timeIntervalSince1970.toRawBits()
        val startBytes = ByteArray(8) { (bits ushr (8 * it)).toByte() }   // littleEndian
        var hash: ULong = 0xCBF2_9CE4_8422_2325uL          // FNV-1a 64비트 오프셋 basis
        for (byte in idBytes + startBytes) {
            hash = (hash xor byte.toUByte().toULong()) * 0x0000_0100_0000_01B3uL  // FNV 64비트 소수
        }
        return hash
    }

    /// UUID 문자열 → 16바이트 (iOS `uuid` 튜플 순서). 형식이 아니면 null
    private fun uuidBytes(id: String): ByteArray? {
        if (id.length != 36) return null
        val uuid = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { return null }
        return ByteArray(16) { i ->
            val word = if (i < 8) uuid.mostSignificantBits else uuid.leastSignificantBits
            (word ushr (8 * (7 - i % 8))).toByte()
        }
    }

    fun synthetic(run: RunSummary, heartRate: HeartRateProfile): WorkoutDetail {
        val rng = SplitMix64(seed = syntheticSeed(run))
        var detail = WorkoutDetail()

        val km = run.distanceKm ?: 8.0
        val basePace = run.paceSecPerKm ?: 360.0

        // 실내(트레드밀)에는 경로·고도가 없다 — 스플릿·존·케이던스는 그대로 만든다 (계획서 M1)
        if (!run.isIndoor) {
            detail = detail.copy(route = syntheticRoute(run, rng))
        }

        // 스플릿: 기본 페이스 ± 8초 흔들림, 마지막 1/4은 점점 처진다 (시안의 후반 드리프트)
        val fullKm = maxOf(km.toInt(), 1)
        detail = detail.copy(splits = (1..fullKm).map { i ->
            var pace = basePace + (rng.unit() - 0.5) * 16
            val lastQuarterStart = fullKm - maxOf(fullKm / 4, 1)
            if (i > lastQuarterStart) {
                pace += (i - lastQuarterStart).toDouble() * 6
            }
            WorkoutDetail.Split(index = i, paceSecPerKm = pace)
        })

        var zones = listOf(0.08, 0.22, 0.44, 0.20, 0.06).map { it + (rng.unit() - 0.5) * 0.04 }
        val sum = zones.fold(0.0) { acc, v -> acc + v }
        zones = zones.map { maxOf(it, 0.01) / sum }
        detail = detail.copy(zones = zones)   // 합성 비율은 존 방식과 무관하다 (데모 한계 — Karvonen 계산은 엔진 테스트가 맡는다)

        val dynamics = syntheticDynamics(run)
        detail = detail.copy(cadenceSpm = dynamics.cadenceSpm,
                             verticalOscillationCm = dynamics.oscillationCm,
                             groundContactMs = dynamics.contactMs,
                             strideLengthM = dynamics.strideM,
                             runningPowerW = dynamics.powerW)
        if (!run.isIndoor) {
            detail = detail.copy(elevationM = 30 + rng.unit() * 70)
        }

        // 최고 심박·드리프트 합성 — 기존 rng 호출 뒤에 둬 위 값들의 재현성을 깨지 않는다
        // 실기기와 같은 경로로 심박 기준을 주입받는다 (이슈 #48·#56). 세션 최고 심박은 HRmax 관찰 표본과
        // 같은 run.maxHeartRate를 쓰고 HRmax로 캡한다 — 데모에서 "HRmax의 104%"가 나오지 않게.
        // jitter는 maxHeartRate가 없을 때만 쓰지만 rng 호출 순서 유지를 위해 항상 뽑는다
        detail = detail.copy(heartRate = heartRate)
        val jitter = rng.unit()
        val peak = run.maxHeartRate ?: ((run.avgHeartRate ?: 150.0) + 22 + jitter * 12)
        detail = detail.copy(maxHeartRateBpm = minOf(peak, heartRate.hrMax))
        if (run.durationSec >= 1_800) {
            // 후반 처짐 스플릿과 결이 맞는 완만한 양수 디커플링 (2~8%)
            val decoupling = 2 + rng.unit() * 6
            val firstEF = 1.9 + rng.unit() * 0.4
            detail = detail.copy(drift = DriftEngine.Result(decouplingPct = decoupling,
                                                            firstHalfEF = firstEF,
                                                            secondHalfEF = firstEF / (1 + decoupling / 100),
                                                            tone = if (decoupling < 5) RRTone.steady else RRTone.caution))
        }

        // 신호 대기 시나리오 세션은 합성 샘플을 실제 엔진에 통과시켜 위 난수 값을 덮어쓴다 —
        // 정지 구간 제외가 화면에서 보이게 하는 검증용 (이슈 #47)
        DemoData.pauseScenario(run)?.let { scenario ->
            detail = detail.copy(
                splits = ActiveTimeline.splits(distanceSamples = scenario.distance, pauses = scenario.pauses)
                    .map { WorkoutDetail.Split(index = it.index, paceSecPerKm = it.paceSecPerKm) },
                drift = DriftEngine.compute(hrSamples = scenario.hr,
                                            distanceSamples = scenario.distance,
                                            start = run.start,
                                            durationSec = run.durationSec,
                                            pauses = scenario.pauses,
                                            end = scenario.end))
        }

        // 노력도 합성 — 4~8 정수(Apple 추정). 맨 끝에서 뽑아 위 값들의 재현성을 깨지 않는다 (이슈 #178)
        detail = detail.copy(effort = EffortScore(score = (4 + (rng.unit() * 5).toInt()).toDouble(), isEstimated = true))
        return detail
    }

    /// iOS `(cadenceSpm:oscillationCm:contactMs:strideM:powerW:)` 튜플
    data class Dynamics(
        val cadenceSpm: Double,
        val oscillationCm: Double?,
        val contactMs: Double?,
        val strideM: Double?,
        val powerW: Double?,
    )

    /// 다이내믹스 합성 — 상세와 기준선 스냅샷이 같은 값을 보도록 시드를 분리해 둔다.
    /// 실내는 다이내믹스 미기록(애플 공식)이라 케이던스만 만든다 (계획서 M4).
    fun syntheticDynamics(run: RunSummary): Dynamics {
        val rng = SplitMix64(seed = syntheticSeed(run) + 0x51DEuL)
        val cadence = 163 + rng.unit() * 14
        if (run.isIndoor) {
            return Dynamics(cadenceSpm = cadence, oscillationCm = null, contactMs = null,
                            strideM = null, powerW = null)
        }
        val speed = (run.distanceMeters ?: 8_000.0) / maxOf(run.durationSec, 60.0)  // m/s
        return Dynamics(cadenceSpm = cadence,
                        oscillationCm = 6.6 + rng.unit() * 2.6,
                        contactMs = 225 + rng.unit() * 60,
                        strideM = speed / cadence * 60,  // 속도 ÷ 케이던스 = 걸음당 거리 — 값끼리 정합
                        powerW = 205 + rng.unit() * 70)
    }

    /// 기준선 스냅샷 합성 — 필터 기준은 실기기 fetchFormSnapshots와 동일
    fun syntheticSnapshots(others: List<RunSummary>, excluding: RunSummary): List<FormSnapshot> {
        val run = excluding
        val cutoff = instantSince1970(run.start.timeIntervalSince1970 - FormEngine.windowDays * 86_400)
        return others
            .filter { !it.isIndoor && it.id != run.id && it.start >= cutoff && it.start < run.start }
            .map { other ->
                val dynamics = syntheticDynamics(other)
                FormSnapshot(id = other.id, start = other.start,
                             cadenceSpm = dynamics.cadenceSpm,
                             verticalOscillationCm = dynamics.oscillationCm,
                             groundContactMs = dynamics.contactMs)
            }
    }

    /// 합성 경로만 — 데모 코스 매칭이 세션마다 상세 전체를 만들지 않게 (이슈 #223). 상세의 경로와 같다
    fun syntheticRoute(run: RunSummary): List<TrackPoint> {
        if (run.isIndoor) return emptyList()
        return syntheticRoute(run, SplitMix64(seed = syntheticSeed(run)))
    }

    /// 한강 언저리 순환 코스 느낌의 타원 + 흔들림. 공유 코스 세션은 중심을 고정해
    /// '같은 코스' 카드가 시뮬레이터에서 보이게 한다 — 중심 난수는 그래도 뽑아 뒤 값의 재현성을 지킨다
    private fun syntheticRoute(run: RunSummary, rng: SplitMix64): List<TrackPoint> {
        val km = run.distanceKm ?: 8.0
        val randomLat = 37.520 + rng.unit() * 0.02
        val randomLon = 126.94 + rng.unit() * 0.03
        val shared = run.id in DemoData.sharedCourseRunIDs
        val centerLat = if (shared) DemoData.sharedCourseCenter.lat else randomLat
        val centerLon = if (shared) DemoData.sharedCourseCenter.lon else randomLon
        val radius = 0.0016 * sqrt(km)
        val points = 140
        // 시각은 시작부터 세션 시간을 points 등분한 일정 간격
        val interval = run.durationSec / points.toDouble()
        return (0..points).map { i ->
            val t = i.toDouble() / points.toDouble() * 2 * PI
            val wobble = 1 + 0.10 * sin(t * 3 + rng.offset) + 0.05 * sin(t * 7)
            TrackPoint(lat = centerLat + radius * wobble * sin(t) * 0.72,
                       lon = centerLon + radius * wobble * cos(t),
                       time = instantSince1970(run.start.timeIntervalSince1970 + interval * i.toDouble()),
                       elevationM = null, horizontalAccuracyM = 5.0, speedMps = null)
        }
    }

    /// 재현 가능한 경량 난수 (SplitMix64)
    private class SplitMix64(seed: ULong) {
        private var state: ULong = seed + 0x9E3779B97F4A7C15uL
        val offset: Double

        init {
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            offset = ((z xor (z shr 31)) shr 11).toDouble() / (1uL shl 53).toDouble() * 2 * PI
        }

        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }

        /// 0..<1
        fun unit(): Double = (next() shr 11).toDouble() / (1uL shl 53).toDouble()
    }
}
