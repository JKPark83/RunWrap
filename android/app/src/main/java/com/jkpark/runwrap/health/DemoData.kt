package com.jkpark.runwrap.health

import android.os.Build
import com.jkpark.runwrap.engine.AirGrade
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.CrossTraining
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.VitalsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/// 데모 모드 게이트 — iOS `DemoMode` 이식.
/// 에뮬레이터에는 워치·삼성헬스 기록이 없어 항상 합성 데이터 경로를 허용한다.
/// 실기기 토글(설정 화면)은 M5에서 잇는다 — iOS의 UserDefaults 토글 대응.
object DemoMode {
    /// 에뮬레이터 판별 — Google 공식 단일 API가 없어 통용되는 빌드 지문 휴리스틱을 쓴다
    val isEmulator: Boolean =
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone") ||
            Build.PRODUCT.contains("sdk")

    /// 합성 데이터를 쓸지 여부 — HC 조회가 비었을 때의 폴백 게이트.
    /// iOS와 달리 에뮬레이터에서도 시더로 HC에 실데이터를 넣을 수 있으므로
    /// "항상 합성"이 아니라 "조회 결과가 비면 합성"으로 쓴다 (HealthConnectStore 참조).
    val isActive: Boolean get() = isEmulator
}

/// 합성 러닝 약 6개월 — iOS `DemoData.swift` 이식. 에뮬레이터 폴백이자 시더(debug)의 주입 재료.
///
/// 최근 4주는 주간 리포트 3개 지표가 서로 다른 톤으로 모두 계산되도록 고정 배열로 유지:
/// 주간 증가율 +23%(경고) · ACWR 1.4(다소 높음) · 심박 효율 +6%(체력 상승)
///
/// 그 이전 22주는 시드 고정(0xC0FFEE) 생성 — 월이 지날수록 페이스가 월 −2초/km씩
/// 완만히 향상되는 패턴을 심어 장기 추이(발전상) 화면 검증에 쓴다.
object DemoData {
    fun runs(now: Instant = Instant.now()): List<RunSummary> = recentTuned(now) + history(now)

    /// 최근 4주(1~26일 전) — 리포트 홈 톤 시나리오에 맞춘 고정 배열.
    /// 생성 러닝이 28일 창(증가율·ACWR·EF 계산 구간)에 섞이면 톤이 바뀌므로
    /// 이 구간만은 손으로 조정한 값을 유지한다.
    /// 케이던스는 최근 2주 평균 168 vs 이전 2주 165.4로 심어
    /// 주법 추이 카드가 개선 톤(+2 spm 이상)으로 계산되게 한다.
    /// 날씨는 HC 세션에 메타데이터가 없어 실경로에서는 못 채우지만, 합성에는 iOS와 같이
    /// 심어 열 보정 카드(HeatEngine)의 화면 검증 재료로 쓴다.
    private fun recentTuned(now: Instant): List<RunSummary> = listOf(
        run(now, 0, daysAgo = 1.0, km = 10.0, minPerKm = 6.1, hr = 145.0, cadence = 171.0, tempC = 28.0, humidityPct = 72.0),
        run(now, 1, daysAgo = 3.0, km = 8.0, minPerKm = 5.9, hr = 147.0, cadence = 170.0, tempC = 26.0, humidityPct = 65.0),
        run(now, 2, daysAgo = 5.0, km = 6.6, minPerKm = 6.0, hr = 144.0, indoor = true, cadence = 169.0),
        run(now, 3, daysAgo = 8.0, km = 10.0, minPerKm = 6.2, hr = 146.0, cadence = 167.0, tempC = 30.0, humidityPct = 78.0),
        run(now, 4, daysAgo = 10.0, km = 6.0, minPerKm = 5.8, hr = 148.0, cadence = 166.0, tempC = 22.0, humidityPct = 55.0),
        run(now, 5, daysAgo = 12.0, km = 4.0, minPerKm = 6.0, hr = 145.0, indoor = true, cadence = 165.0),
        run(now, 6, daysAgo = 16.0, km = 6.0, minPerKm = 6.1, hr = 153.0, cadence = 166.0, tempC = 27.0, humidityPct = 70.0),
        run(now, 7, daysAgo = 18.0, km = 5.0, minPerKm = 6.0, hr = 152.0, indoor = true, cadence = 165.0),
        run(now, 8, daysAgo = 20.0, km = 5.0, minPerKm = 6.2, hr = 154.0, cadence = 166.0),
        run(now, 9, daysAgo = 23.0, km = 6.0, minPerKm = 6.0, hr = 153.0, cadence = 165.0, tempC = 25.0, humidityPct = 68.0),
        run(now, 10, daysAgo = 26.0, km = 5.0, minPerKm = 6.1, hr = 155.0, indoor = true, cadence = 165.0),
    )

    /// 4~25주 전 — 주 2~3회, 거리 6~18km 변주.
    /// daysAgo가 항상 28을 넘도록 배치해 최근 4주 지표 창을 침범하지 않는다.
    private fun history(now: Instant): List<RunSummary> {
        val rng = SplitMix64(seed = 0xC0FFEEuL)
        return (4 until 26).flatMap { week ->
            val count = 2 + (rng.next() % 2uL).toInt()
            (0 until count).map { slot ->
                val daysAgo = week * 7 + 0.5 + slot * 2.2 + rng.unit() * 1.4
                val monthsAgo = week / 4.33
                val basePace = 6.0 + monthsAgo * 2 / 60  // 과거일수록 느림 = 현재로 오며 향상
                run(now, week * 10 + slot + 100,
                    daysAgo = daysAgo,
                    km = 6 + rng.unit() * 12,
                    minPerKm = basePace + (rng.unit() - 0.5) * 0.15,
                    hr = 144 + rng.unit() * 10,
                    indoor = slot == 1)  // 주 1회꼴 실내(트레드밀) 세션
            }
        }
    }

    private fun run(now: Instant, index: Int, daysAgo: Double, km: Double, minPerKm: Double,
                    hr: Double, indoor: Boolean = false, cadence: Double? = null,
                    tempC: Double? = null, humidityPct: Double? = null) = RunSummary(
        id = "demo-$index",   // 고정 id — 합성 상세(id 시드)가 세션마다 항상 같은 모양이 된다
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * minPerKm * 60,
        distanceMeters = km * 1_000,
        avgHeartRate = hr,
        calories = km * 62,  // 체중 70kg 언저리 러닝 소모 근사 (≈1.036 kcal/kg/km)
        isIndoor = indoor,
        cadenceSpm = cadence,
        weatherTempC = tempC,
        weatherHumidityPct = humidityPct,
    )

    /// 합성 VO₂max — 12주에 걸친 완만한 상승(주 +0.3), 주 1~2회 추정 기록.
    /// 4주 전 대비 +1.2 언저리가 되도록 기울여 심폐 체력 카드가 개선 톤으로 계산되게 한다.
    fun vo2Max(now: Instant = Instant.now()): List<Pair<Instant, Double>> {
        val rng = SplitMix64(seed = 0xF17uL)
        return (0 until 12).flatMap { week ->
            val count = 1 + (rng.next() % 2uL).toInt()
            (0 until count).map { slot ->
                val daysAgo = week * 7 + slot * 3 + rng.unit() * 2
                val value = 45.2 - week * 0.3 + (rng.unit() - 0.5) * 0.5  // 과거일수록 낮다
                now.minusMillis((daysAgo * 86_400_000).roundToLong()) to value
            }
        }
    }

    /// 합성 활력징후 — 과부하 주간 시나리오에 맞춘 "회복 덜 됨" 상태.
    ///
    /// HRV 하락 + 안정 심박 상승 + 수면 질 저하 + ACWR 초과가 겹쳐 체력 배터리가
    /// 20%대(주의)로 계산되도록 맞춰 놓았다. iOS와의 차이: HC에 HRR이 없어 HRR 감점(−1)이
    /// 빠진다 — 포인트 합: HRV −6, 안정 심박 −6, 수면 +1, 수면 질 −8, ACWR −2 → 50−21 = 29.
    /// 수면 시간은 7.1h로 충분한데 깊은잠+렘이 뚝 떨어진 시나리오 — "잤는데 얕게 잔 날".
    fun vitals(now: Instant = Instant.now()): VitalsSnapshot = VitalsSnapshot(
        hrvMs = VitalsSnapshot.Reading(today = 57.0, baseline = 62.0, baselineDays = 28),
        restingHR = VitalsSnapshot.Reading(today = 53.0, baseline = 51.0, baselineDays = 28),
        respiratoryRate = VitalsSnapshot.Reading(today = 14.6, baseline = 14.2, baselineDays = 28),
        skinTempC = VitalsSnapshot.Reading(today = 36.5, baseline = 36.4, baselineDays = 21),
        sleepHours = 7.1,
        sleepNights = sleepNights(now),
    )

    /// 합성 밤별 수면 — 최근 14일. 마지막 밤만 깊은잠+렘 비율을 0.27로 떨어뜨려
    /// (평소 0.34~0.38, 상대 하락 20% 이상) 수면 질 감점(−8pt)이 데모에서 보이게 한다.
    /// 취침 시각은 23:30 전후 ±40분(정오 기준 650~730분)으로 규칙적이라
    /// 수면 리듬 감점(SD > 90분)은 트리거하지 않는다.
    fun sleepNights(now: Instant = Instant.now()): List<VitalsSnapshot.SleepNight> {
        val rng = SplitMix64(seed = 0x5EE9uL)
        return (0 until 14).map { i ->
            val daysAgo = (13 - i).toDouble()
            val isLatest = i == 13
            VitalsSnapshot.SleepNight(
                date = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
                asleepHours = 6.5 + rng.unit() * 1.2,
                deepRemFraction = if (isLatest) 0.27 else 0.34 + rng.unit() * 0.04,
                bedtimeMinutes = 690 + (rng.unit() - 0.5) * 80,
            )
        }
    }

    /// 합성 대기질 — 에뮬레이터에는 위치·측정소 실데이터가 없다 (이슈 #8).
    /// '보통' 시나리오: 배지·수치·등급·측정소 캡션이 모두 그려지는 구성을 확인하는 재료
    fun airQuality(now: Instant = Instant.now()): AirQuality = AirQuality(
        stationName = "중구",
        dataTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")   // 에어코리아 dataTime 형식
            .withZone(ZoneId.of("Asia/Seoul")).format(now),
        pm10 = 34.0, pm25 = 19.0, o3 = 0.031, khai = 68.0,
        pm10Grade = AirGrade.MODERATE, pm25Grade = AirGrade.MODERATE,
        o3Grade = AirGrade.MODERATE, khaiGrade = AirGrade.MODERATE,
    )

    /// 합성 크로스 트레이닝 — 이번 주 자전거 90분 + 근력 45분 (계 2시간 15분).
    /// 걷기 25분 세션은 CrossTrainingEngine의 30분 미만 걷기 가드에 걸러지는 걸 확인하는 재료.
    fun crossTrainings(now: Instant = Instant.now()): List<CrossTraining> = listOf(
        CrossTraining(start = now.minusSeconds(2 * 86_400L),
                      durationSec = 90 * 60.0, kind = CrossTraining.Kind.CYCLING, kcal = 520.0),
        CrossTraining(start = now.minusSeconds(4 * 86_400L),
                      durationSec = 45 * 60.0, kind = CrossTraining.Kind.STRENGTH, kcal = 210.0),
        CrossTraining(start = now.minusSeconds(5 * 86_400L),
                      durationSec = 25 * 60.0, kind = CrossTraining.Kind.WALKING, kcal = 90.0),
    )
}

/// 재현 가능한 경량 난수 — iOS DemoData의 SplitMix64와 같은 구현
internal class SplitMix64(seed: ULong) {
    private var state = seed

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
