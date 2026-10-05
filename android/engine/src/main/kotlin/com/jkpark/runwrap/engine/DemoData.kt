package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.exp

/// 데모 모드 게이트 — 합성 데이터(DemoData)를 실기기에서도 켤 수 있게 하는 스위치
///
/// 왜 필요한가: 심사자 아이폰에는 애플워치 러닝 기록이 없다. 표본이 없으면 이 앱은
/// 설계상 지표를 아예 내지 않으므로(미노출 가드) 화면이 텅 비고, App Store 심사
/// 지침 2.1(App Completeness)에 걸린다. 애플은 이런 경우 앱에 내장된 데모 모드를
/// 허용하되 심사 노트에 켜는 방법을 밝히라고 요구한다 — 숨긴 기능이 아니어야
/// 지침 2.3.1에도 걸리지 않으므로 설정 화면에 그대로 노출한다.
///
/// 시뮬레이터는 워치 기록이 있을 수 없어 토글과 무관하게 항상 켜진 것으로 다룬다.
/// (Android: 키만 둔다. `isEnabled`·`isActive`는 저장소·빌드 분기에 기대므로 :app이 맡는다)
object DemoMode {
    const val key = "demoModeEnabled"
}

/// 합성 러닝 약 6개월 — 시뮬레이터 기본 데이터이자 실기기 "샘플 리포트 둘러보기"의 재료
///
/// 최근 4주는 주간 리포트 3개 지표가 서로 다른 톤으로 모두 계산되도록 고정 배열로 유지:
/// 주간 증가율 +23%(경고) · ACWR 1.4(다소 높음) · 심박 효율 +6%(체력 상승)
///
/// 그 이전 22주는 시드 고정(0xC0FFEE) 생성 — 월이 지날수록 페이스가 월 −2초/km씩
/// 완만히 향상되는 패턴을 심어 장기 추이(발전상) 화면 검증에 쓴다 (계획서 M0).
/// (Android: iOS는 `Date()`를 읽는 static 프로퍼티지만 여기서는 `now`(필요하면 `zone`)를 주입받는 함수다.
///  iOS `static let runs`의 "한 번만 만든다"는 같은 now를 넘기면 같은 목록이 나오는 것으로 대신한다 —
///  :app이 데모 진입 시각 하나를 잡아 재사용한다)
object DemoData {
    /// 한 번만 만든다 — 계산 프로퍼티면 접근마다 UUID·시각이 새로 생겨, 같은 세션을 다시 열 때
    /// 합성 상세(시드 = id·시작 시각)가 달라진다. fillWithDemoData가 여러 번 불려도 같은 목록 (이슈 #102)
    fun runs(now: Instant): List<RunSummary> = recentTuned(now) + history(now)

    /// 인덱스 기반 결정적 UUID — 00000000-0000-0000-0000-000000000001 꼴 (이슈 #102)
    fun demoID(index: Int): String = String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", index)

    /// 최근 4주(1~26일 전) — 리포트 홈 톤 시나리오에 맞춘 고정 배열.
    /// 생성 러닝이 28일 창(증가율·ACWR·EF 계산 구간)에 섞이면 톤이 바뀌므로
    /// 이 구간만은 손으로 조정한 값을 유지한다.
    /// 케이던스는 최근 2주 평균 168 vs 이전 2주 165.4로 심어
    /// 주법 추이 카드가 개선 톤(+2 spm 이상)으로 계산되게 한다 (계획서 M4).
    /// 날씨는 야외 세션 일부에만 심는다 — 한여름(열 점수 46 초과, 보정 큼)·
    /// 초여름(38~46 구간)·선선한 날(38 이하 → 보정 카드 미노출 가드 확인)을 섞는다.
    private fun recentTuned(now: Instant): List<RunSummary> = listOf(
        run(now, daysAgo = 1.0, km = 10.0, minPerKm = 6.1, hr = 145.0, cadence = 171.0, tempC = 28.0, humidityPct = 72.0,
            id = pausedRunID),  // 신호 대기 정지 시나리오(pauseScenario) — 이슈 #47
        run(now, daysAgo = 3.0, km = 8.0, minPerKm = 5.9, hr = 147.0, cadence = 170.0, tempC = 26.0, humidityPct = 65.0,
            id = demoID(2)),
        run(now, daysAgo = 5.0, km = 6.6, minPerKm = 6.0, hr = 144.0, indoor = true, cadence = 169.0,
            id = demoID(3)),
        run(now, daysAgo = 8.0, km = 10.0, minPerKm = 6.2, hr = 146.0, cadence = 167.0, tempC = 30.0, humidityPct = 78.0,
            id = demoID(4)),
        run(now, daysAgo = 10.0, km = 6.0, minPerKm = 5.8, hr = 148.0, cadence = 166.0, tempC = 22.0, humidityPct = 55.0,
            id = demoID(5)),
        run(now, daysAgo = 12.0, km = 4.0, minPerKm = 6.0, hr = 145.0, indoor = true, cadence = 165.0,
            id = demoID(6)),
        run(now, daysAgo = 16.0, km = 6.0, minPerKm = 6.1, hr = 153.0, cadence = 166.0, tempC = 27.0, humidityPct = 70.0,
            id = demoID(7)),
        run(now, daysAgo = 18.0, km = 5.0, minPerKm = 6.0, hr = 152.0, indoor = true, cadence = 165.0,
            id = demoID(8)),
        run(now, daysAgo = 20.0, km = 5.0, minPerKm = 6.2, hr = 154.0, cadence = 166.0,
            id = demoID(9)),
        run(now, daysAgo = 23.0, km = 6.0, minPerKm = 6.0, hr = 153.0, cadence = 165.0, tempC = 25.0, humidityPct = 68.0,
            id = demoID(10)),
        run(now, daysAgo = 26.0, km = 5.0, minPerKm = 6.1, hr = 155.0, indoor = true, cadence = 165.0,
            id = demoID(11)),
    )

    /// 4~25주 전 — 주 2~3회, 거리 6~18km 변주.
    /// daysAgo가 항상 28을 넘도록 배치해 최근 4주 지표 창을 침범하지 않는다.
    private fun history(now: Instant): List<RunSummary> {
        val rng = SplitMix64(seed = 0xC0FFEEuL)
        return (4 until 26).flatMap { week ->
            val count = 2 + (rng.next() % 2uL).toInt()
            (0 until count).map { slot ->
                val daysAgo = week.toDouble() * 7 + 0.5 + slot.toDouble() * 2.2 + rng.unit() * 1.4
                val monthsAgo = week.toDouble() / 4.33
                val basePace = 6.0 + monthsAgo * 2 / 60  // 과거일수록 느림 = 현재로 오며 향상
                run(now, daysAgo = daysAgo,
                    km = 6 + rng.unit() * 12,
                    minPerKm = basePace + (rng.unit() - 0.5) * 0.15,
                    hr = 144 + rng.unit() * 10,
                    indoor = slot == 1,  // 주 1회꼴 실내(트레드밀) 세션
                    id = demoID(100 + week * 10 + slot))  // 주·슬롯 인덱스 — 최근 4주(2~11)와 겹치지 않는다
            }
        }
    }

    private fun run(now: Instant, daysAgo: Double, km: Double, minPerKm: Double,
                    hr: Double, indoor: Boolean = false,
                    cadence: Double? = null,
                    tempC: Double? = null, humidityPct: Double? = null,
                    id: String): RunSummary =
        RunSummary(id = id,
                   start = adding(now, -daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = hr,
                   maxHeartRate = hr + 26,   // 평균 대비 +26bpm 근사 — HRmax 관찰 추정(이슈 #34)용
                   calories = km * 62,  // 체중 70kg 언저리 러닝 소모 근사 (≈1.036 kcal/kg/km)
                   isIndoor = indoor,
                   cadenceSpm = cadence,
                   weatherTempC = tempC,
                   weatherHumidityPct = humidityPct)

    /// 신호 대기 정지 시나리오를 심은 세션(1일 전 10km)의 고정 ID.
    /// 새 세션을 28일 창에 추가하면 리포트 홈 톤(증가율·ACWR·EF)이 바뀌므로 기존 세션을 쓴다.
    const val pausedRunID = "4E3A7C1D-2B9F-4E57-A0C6-47F1A2B3C4D5"

    /// iOS `pauseScenario` 반환 튜플 `(distance:hr:pauses:end:)`
    data class PauseScenario(
        val distance: List<DistanceSample>,
        val hr: List<DriftEngine.HeartRateSample>,
        val pauses: List<ClosedRange<Instant>>,
        val end: Instant,
    )

    /// 도심 신호 대기 시나리오 — 세션 상세의 스플릿·드리프트 카드가 정지 구간을 빼고
    /// 계산되는지 시뮬레이터에서 확인하는 재료 (이슈 #47). pausedRunID 세션에만 값을 준다.
    ///
    /// 활동 시간은 세션 요약 그대로(10km · 3,660초), km별 페이스는 평균 ±4초 고정 흔들림.
    /// 정지 1: 1.5km 지점 90초(전반 — 드리프트 중앙 시각 검증),
    /// 정지 2: 8.5km 지점 120초(후반 1/4 — 스플릿 문장 검증). 정지 중에는 거리 샘플이 없고
    /// 심박은 115bpm(제외되는지 드러내는 값). 달리는 동안 심박은 145→150bpm으로 완만히 오른다.
    /// 기대 화면: 스플릿은 막대가 튀지 않고 "고르게 유지" 문장(마지막 2km 흔들림 합 0),
    /// 드리프트는 전반 평균 146.25 · 후반 148.75bpm, 거리 5,000m씩 → 148.75/146.25 − 1 ≈ +1.7% steady.
    /// (정지를 빼지 않으면 2km·9km 스플릿이 90·120초 느려져 "페이스 유지 실패"가 뜬다)
    fun pauseScenario(run: RunSummary): PauseScenario? {
        if (run.id != pausedRunID) return null
        val jitter = listOf(-3.0, 2.0, -1.0, 4.0, -2.0, 1.0, -4.0, 3.0, 0.0, 0.0)  // 합 0 · 전반 5km 합 0 — 난수를 쓰지 않는다
        val pauseAfterHalfKm = mapOf(2 to 90.0, 16 to 120.0)      // 반 km 구간 번호(0부터, 2 = 1.5km 끝) → 정지 초
        val basePace = run.durationSec / 10
        val samplesPerHalfKm = 20  // 반 km ≈ 183초 → 약 9초 간격, 샘플당 25m(누적 오차 없이 km 경계에 딱 맞는다)

        val distance = mutableListOf<DistanceSample>()
        val hr = mutableListOf<DriftEngine.HeartRateSample>()
        val pauses = mutableListOf<ClosedRange<Instant>>()
        var cursor = run.start
        var activeElapsed = 0.0

        for (half in 0 until 20) {
            val segmentSec = (basePace + jitter[half / 2]) / 2
            for (j in 0 until samplesPerHalfKm) {
                val sampleStart = adding(cursor, segmentSec * j.toDouble() / samplesPerHalfKm.toDouble())
                distance.add(DistanceSample(start = sampleStart,
                                            end = adding(sampleStart, segmentSec / samplesPerHalfKm.toDouble()),
                                            meters = 500 / samplesPerHalfKm.toDouble()))
            }
            var offset = 0.0
            while (offset < segmentSec) {   // stride(from: 0.0, to: segmentSec, by: 5)
                val bpm = 145 + 5 * (activeElapsed + offset) / run.durationSec
                hr.add(DriftEngine.HeartRateSample(time = adding(cursor, offset), bpm = bpm))
                offset += 5
            }
            cursor = adding(cursor, segmentSec)
            activeElapsed += segmentSec

            pauseAfterHalfKm[half]?.let { pauseSec ->
                pauses.add(cursor..adding(cursor, pauseSec))   // DateInterval(start:duration:)
                var pauseOffset = 0.0
                while (pauseOffset < pauseSec) {
                    hr.add(DriftEngine.HeartRateSample(time = adding(cursor, pauseOffset), bpm = 115.0))
                    pauseOffset += 5
                }
                cursor = adding(cursor, pauseSec)
            }
        }
        return PauseScenario(distance = distance, hr = hr, pauses = pauses, end = cursor)
    }

    /// 합성 VO₂max — 12주에 걸친 완만한 상승(주 +0.3), 주 1~2회 추정 기록.
    /// 4주 전 대비 +1.2 언저리가 되도록 기울여 심폐 체력 카드가 개선 톤으로 계산되게 한다.
    fun vo2Max(now: Instant): List<Pair<Instant, Double>> {
        val rng = SplitMix64(seed = 0xF17uL)
        return (0 until 12).flatMap { week ->
            val count = 1 + (rng.next() % 2uL).toInt()
            (0 until count).map { slot ->
                val daysAgo = week.toDouble() * 7 + slot.toDouble() * 3 + rng.unit() * 2
                val value = 45.2 - week.toDouble() * 0.3 + (rng.unit() - 0.5) * 0.5  // 과거일수록 낮다
                adding(now, -daysAgo * 86_400) to value
            }
        }
    }

    /// 합성 활력징후 — 과부하 주간 시나리오에 맞춘 "회복 덜 됨" 상태
    ///
    /// HRV 하락 + 안정 심박 상승 + 심박 회복 소폭 하락 + 수면 질 저하 + ACWR 초과가
    /// 겹쳐 체력 배터리가 20%대(주의)로 계산되도록 맞춰 놓았다.
    /// 포인트 합: HRV −6, 안정 심박 −6, HRR −1, 수면 +1, 수면 질 −8, ACWR −2 → 50−22 = 28.
    /// 수면 시간은 7.1h로 충분한데 깊은잠+렘이 뚝 떨어진 시나리오 — "잤는데 얕게 잔 날".
    fun vitals(now: Instant): VitalsSnapshot =
        VitalsSnapshot(hrvMs = VitalsSnapshot.Reading(today = 57.0, baseline = 62.0, baselineDays = 28),
                       restingHR = VitalsSnapshot.Reading(today = 53.0, baseline = 51.0, baselineDays = 28),
                       hrr = VitalsSnapshot.Reading(today = 30.0, baseline = 31.0, baselineDays = 9),  // baselineDays 자리는 표본 수
                       respiratoryRate = VitalsSnapshot.Reading(today = 14.6, baseline = 14.2, baselineDays = 28),
                       wristTempC = VitalsSnapshot.Reading(today = 36.5, baseline = 36.4, baselineDays = 21),
                       sleepHours = 7.1,
                       sleepNights = sleepNights(now))

    /// 합성 밤별 수면 — 최근 14일. 마지막 밤만 깊은잠+렘 비율을 0.27로 떨어뜨려
    /// (평소 0.34~0.38, 상대 하락 20% 이상) 수면 질 감점(−8pt)이 데모에서 보이게 한다.
    /// 취침 시각은 23:30 전후 ±40분(정오 기준 650~730분)으로 규칙적이라
    /// 수면 리듬 감점(SD > 90분)은 트리거하지 않는다.
    private fun sleepNights(now: Instant): List<VitalsSnapshot.SleepNight> {
        val rng = SplitMix64(seed = 0x5EE9uL)
        return (0 until 14).map { i ->
            val daysAgo = (13 - i).toDouble()
            val isLatest = i == 13
            VitalsSnapshot.SleepNight(
                date = adding(now, -daysAgo * 86_400),
                asleepHours = 6.5 + rng.unit() * 1.2,
                deepRemFraction = if (isLatest) 0.27 else 0.34 + rng.unit() * 0.04,
                bedtimeMinutes = 690 + (rng.unit() - 0.5) * 80)
        }
    }

    /// 합성 심박 회복(HRR) — 12주에 걸친 완만한 상승(주 +0.45bpm), 주 1~2회 야외 러닝 후 기록.
    /// 84일 창 추세(ReportEngine.hrrTrend)가 개선 톤(+2bpm 초과)으로 계산되게 한다.
    /// 배터리의 오늘 값(30, 기준선 31 대비 소폭 하락)과는 창이 다르다 —
    /// 12주 추세는 오르는 중인데 오늘 하루만 살짝 낮은, 흔한 과부하 주간 그림.
    fun hrrTrend(now: Instant): List<Pair<Instant, Double>> {
        val rng = SplitMix64(seed = 0x48EAuL)
        return (0 until 12).flatMap { week ->
            val count = 1 + (rng.next() % 2uL).toInt()
            (0 until count).map { slot ->
                val daysAgo = week.toDouble() * 7 + slot.toDouble() * 3 + rng.unit() * 2
                val value = 31.2 - week.toDouble() * 0.45 + (rng.unit() - 0.5) * 1.5  // 과거일수록 낮다
                adding(now, -daysAgo * 86_400) to value
            }
        }
    }

    /// 합성 세션별 심박 히스토그램 (이슈 #165) — 기간별 심박존 분포(80/20) 카드 재료.
    ///
    /// 최근 28일(28일 전 자정 이후) 세션마다 두 개의 종 모양(σ 5bpm, ±12bpm로 자름) 분포를 섞는다:
    /// 세션 평균 심박 중심의 "빠른 구간"과 HRmax 62% 중심의 "이지 구간".
    /// 데모 세션 평균 심박(144~155)은 관찰 HRmax(≈180)의 80% 이상이라 평균 심박만으로 만들면
    /// 이지 비율이 0%가 된다 — 세션마다 이지 구간 비중을 0.68~0.82로 시드 고정해
    /// 28일 누적 이지 비율이 0.75 언저리(주의 톤)로 계산되게 한다. 총 초 = 세션 시간.
    fun zoneHistograms(now: Instant, zone: ZoneId): Map<String, ZoneHistogram> {
        val runs = runs(now)
        val windowStart = adding(now, -28.0 * 86_400).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        // fillWithDemoData와 같은 추정 — %HRmax 기본 설정에서 이지 구간이 Z1~Z2에 머물게 한다
        val easyCenter = (TrainingGuideEngine.hrMaxEstimate(runs = runs, now = now, zone = zone, birthYear = null).bpm * 0.62)
            .swiftRounded()
        val offsets = (-12..12).toList()
        val bell = offsets.map { exp(-(it * it).toDouble() / (2 * 5 * 5)) }
        val bellSum = bell.fold(0.0) { acc, v -> acc + v }

        val result = mutableMapOf<String, ZoneHistogram>()
        for (run in runs) {
            if (!(run.start >= windowStart && run.start <= now)) continue
            val rng = SplitMix64(seed = WorkoutDetailStore.syntheticSeed(run) + 0x2020uL)
            val easyFraction = 0.68 + rng.unit() * 0.14
            val hardCenter = (run.avgHeartRate ?: 150.0).swiftRounded()
            val seconds = mutableMapOf<Int, Double>()
            for ((i, offset) in offsets.withIndex()) {
                val share = bell[i] / bellSum * run.durationSec
                seconds[easyCenter.toInt() + offset] = (seconds[easyCenter.toInt() + offset] ?: 0.0) + share * easyFraction
                seconds[hardCenter.toInt() + offset] = (seconds[hardCenter.toInt() + offset] ?: 0.0) + share * (1 - easyFraction)
            }
            result[run.id] = ZoneHistogram(secondsByBpm = seconds)
        }
        return result
    }

    /// 합성 대기질 — 시뮬레이터에는 위치·측정소 실데이터가 없다 (이슈 #8).
    /// '보통' 시나리오: 배지·수치·등급·측정소 캡션이 모두 그려지는 구성을 확인하는 재료
    /// (Android: iOS DateFormatter는 기기 시간대를 쓰므로 `zone`을 받는다)
    fun airQuality(now: Instant, zone: ZoneId): AirQuality {
        val t = now.atZone(zone)
        // 에어코리아 dataTime 형식 "yyyy-MM-dd HH:00"
        val dataTime = String.format(Locale.ROOT, "%04d-%02d-%02d %02d:00", t.year, t.monthValue, t.dayOfMonth, t.hour)
        return AirQuality(stationName = "중구",
                          dataTime = dataTime,
                          pm10 = 34.0, pm25 = 19.0, o3 = 0.031, khai = 68.0,
                          pm10Grade = AirGrade.moderate, pm25Grade = AirGrade.moderate,
                          o3Grade = AirGrade.moderate, khaiGrade = AirGrade.moderate)
    }

    /// 합성 베스트 에포트 (이슈 #166) — 세션 거리 안에 드는 목표 거리마다
    /// 평균 페이스 × D × (0.93~0.99). 세션 안 가장 빠른 구간은 평균보다 조금 빠르다는 근사.
    /// 시드는 세션 인덱스라 결정론적이다 (runs가 static let이라 순서도 고정).
    fun bestEfforts(now: Instant): BestEffortTable =
        runs(now).withIndex().associate { (index, run) ->
            val rng = SplitMix64(seed = 0xBE57uL + index.toULong())
            val pace = run.paceSecPerKm
            val meters = run.distanceMeters
            if (pace == null || meters == null) return@associate run.id to emptyMap<Double, Double>()
            val efforts = BestEffortEngine.targets.filter { it.meters <= meters }.map { target ->
                target.meters to pace * target.meters / 1_000 * (0.93 + rng.unit() * 0.06)
            }
            run.id to efforts.toMap()
        }

    /// `Date.addingTimeInterval` — ActiveTimeline·DriftEngine과 같은 Double 초 덧셈
    private fun adding(date: Instant, seconds: Double): Instant = instantSince1970(date.timeIntervalSince1970 + seconds)
}

/// 재현 가능한 경량 난수 — WorkoutDetailStore.synthetic의 것과 같은 구현.
/// 그쪽은 시뮬레이터 전용 private이고 DemoData는 실기기 샘플 리포트에서도 쓰여 별도로 둔다.
private class SplitMix64(seed: ULong) {
    private var state: ULong = seed

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
