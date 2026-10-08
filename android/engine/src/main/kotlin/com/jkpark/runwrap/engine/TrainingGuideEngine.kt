package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/// 훈련 가이드 엔진 v2 (기획서 §4.9) — 목표 레이스 진단, 훈련 페이스 존, 주간 처방, 오늘의 훈련.
/// 순수 로직: Foundation만 쓰고 now·레벨·배터리 톤을 값으로 주입받아 결정론적이다.
///
/// 산식 출처:
/// - 완주 예측: Riegel 공식 T2 = T1 × (D2/D1)^1.06 (Riegel 1981).
///   T1은 유효 표본 중 예측이 가장 좋게 나오는 세션이다. 표본은 공인 거리 PR이 아니라
///   **실제 러닝 세션**에서 뽑는다 — 6~9km처럼 공인 거리 사이에 걸린 세션만 뛰는 러너가
///   예측을 통째로 못 받던 문제 때문이다 (이슈 #24). 창은 1주 우선·없을 때만 4주→8주.
/// - 현재 기력·훈련 페이스: Daniels & Gilbert(1979) "Oxygen Power"의 VDOT 공식.
///   같은 세션에서 VDOT를 역산하고 %VDOT 구간으로 페이스 존을 정한다
///   (이지 62~74% / 템포 88% / 인터벌 97.5% — Daniels' Running Formula의 존 정의).
///   훈련 페이스는 목표 기록이 아니라 **현재 실력** 기준이다 — 목표가 현재보다 빨라도
///   페이스 존은 올라가지 않고, 갭은 진단(예상 vs 목표)으로만 보여준다 (부상 방지).
/// - 권장 주간 거리: 만성 부하(4주 주평균) × 1.0~1.1 — 10% 룰 상한.
///   종목·레벨별 피크 주간 거리에 닿으면 더 올리지 않는다 (가정 — 일반 훈련 플랜 관례).
/// - 주기화: 대회 날짜가 있으면 남은 주 수로 기초→강화→피크→테이퍼→대회 주간을 가르고
///   테이퍼 구간은 볼륨을 내린다 (가정 — 일반 플랜 관례: 테이퍼 60~70%, 대회 주간 40~50%).
/// - LSD 목표: 권장 주간의 25~35%. 체력 배터리가 overload/caution이면 25% 하한으로
///   고정하고 인터벌을 끈다 (기획서 §4.9 + v2 확장).
/// - 강도 밸런스: (easy+LSD) : 스피드 세션 수를 80/20 원칙과 비교 (Seiler 80/20).
///
/// 가드: 기록이 3주 미만이거나 만성 부하가 주 3km 미만이면 진단·처방 전체를 내지 않는다(nil).
/// ACWR(4주)보다 느슨한 3주 기준이다 — 여기서 만성 부하는 비율이 아니라 처방 볼륨의 기준이라
/// 분모가 작게 잡혀도 처방이 적게 나오는 안전한 쪽으로 틀린다 (이슈 #49). 예측·페이스 존은 유효 표본(5km 이상, 외삽 3배 이내)이
/// 8주 안에 하나도 없으면 따로 내지 않는다 — 틀린 페이스는 없느니만 못하다.

data class TrainingGuide(
    val prediction: Prediction?,
    val zones: PaceZones?,
    val prescription: Prescription,
    val balance: Balance?,
) {
    /// 목표 레이스 완주 예측 — 유효 표본이 없으면 nil (처방만 노출)
    data class Prediction(
        val race: RaceDistance,
        val predictedSec: Double,
        val baseLabel: String,        // 근거 세션 거리 ("7.4km")
        val baseTimeSec: Double,      // 근거 세션 기록(초)
        /// 표본을 찾은 창(일) — 7·28·56. 1주(7)가 아니면 화면이 "최근 4주 기준"처럼 밝힌다
        val baseWindowDays: Int,
        val goalSec: Double?,         // 목표 기록 미입력이면 nil
        val tone: RRTone,             // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
    )

    /// 훈련 페이스 존 — 예측과 같은 세션에서 역산한 VDOT 기준 (Daniels & Gilbert 1979).
    /// 페이스는 모두 초/km. 존 상수는 VDOT 50(5K 19:57)에서 Daniels 표와 대조해 검증했다
    /// (이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″).
    data class PaceZones(
        val vdot: Double,
        /// 이지·LSD 페이스 구간 — 빠른 끝(74%)…느린 끝(62%)
        val easySecPerKm: ClosedFloatingPointRange<Double>,
        val tempoSecPerKm: Double,        // 템포(역치)런 — 88%
        val intervalSecPerKm: Double,     // 인터벌 — 97.5% (vVDOT 부근)
        val goalSecPerKm: Double?,        // 목표 레이스 페이스 — 미입력·비현실적(가드 참고)이면 nil
    )

    /// 주기화 단계 — 대회 날짜 기준 남은 주 수로 가른다 (가정 — 일반 플랜 관례)
    enum class Phase {
        base,       // 기초기 — 볼륨 쌓기
        build,      // 강화기 — 퀄리티 도입
        peak,       // 피크 — 최대 볼륨·강도
        taper,      // 테이퍼 — 볼륨 감량, 강도 유지
        raceWeek;   // 대회 주간 — 가볍게만

        val label: String
            get() = when (this) {
                base -> "기초기"
                build -> "강화기"
                peak -> "피크"
                taper -> "테이퍼"
                raceWeek -> "대회 주간"
            }
    }

    data class Prescription(
        val weeklyKmLow: Double,      // 만성 부하 그대로 (×1.0) — 테이퍼 구간은 감량 하한
        val weeklyKmHigh: Double,     // ×1.1 (10% 룰 상한), 피크 주간 거리에서 멈춘다
        val lsdKmLow: Double,
        val lsdKmHigh: Double,        // 대회 주간은 0 — 롱런 없이 간다
        val tempoCount: Int,          // 이번 주 템포런 권장 횟수
        val intervalCount: Int,       // 이번 주 인터벌 권장 횟수
        val phase: Phase?,            // 대회 날짜 미설정·지난 날짜면 nil
        val daysToRace: Int?,         // D-day (대회 당일 = 0)
        val peakWeeklyKm: Double,     // 이 종목·레벨의 권장 피크 주간 거리 (참고 표시용)
        val batteryLimited: Boolean,  // 배터리 하향 보정이 적용됐는지
    ) {
        val qualityCount: Int get() = tempoCount + intervalCount
    }

    /// 최근 7일 세션의 강도 밸런스 — 이번 주 세션이 없으면 nil
    data class Balance(
        val easyCount: Int,
        val lsdCount: Int,
        val speedCount: Int,
        val speedSharePct: Double,    // 세션 수 기준 스피드 비중 (가정 — 시간 아닌 횟수)
        val tone: RRTone,             // 스피드 20% 초과면 caution, 그 외 steady
    )

    /// 세션 강도 분류 (가정 — 계획서 M7): LSD = 주간 최장 && 주간 총거리의 35% 이상 /
    /// 스피드 = 4주 평균 페이스보다 10% 이상 빠름 / 나머지 easy. LSD 판정이 스피드보다 우선.
    enum class SessionKind { easy, lsd, speed }
}

/// 오늘의 훈련 추천 — 형태·거리·페이스. 이력·배터리로 정하는 동적 추천이라
/// 요일에 묶이지 않는다. 화면은 이 값을 문장으로 그린다 (라벨은 Kind.label).
data class TodayWorkout(
    val kind: Kind,
    val reason: Reason,
    val distanceKm: Double?,                                  // rest·done이면 nil. 인터벌은 본훈련 합계
    val paceSecPerKm: ClosedFloatingPointRange<Double>?,      // 페이스 존이 없으면(유효 예측 표본 없음) nil
) {
    sealed interface Kind {
        data object rest : Kind                                  // 배터리 방전 임박 — 오늘의 처방은 쉬는 것
        data object doneCount : Kind                             // 주간 목표 횟수 달성
        data object doneKm : Kind                                // 주간 목표 거리 달성
        data object easy : Kind
        data object lsd : Kind
        data object tempo : Kind
        data class interval(val reps: Int, val meters: Int) : Kind

        /// 화면 공통 라벨 — 홈 카드·리포트 카드가 같은 이름을 쓴다
        val label: String
            get() = when (this) {
                rest -> "휴식"
                doneCount, doneKm -> "완료"
                easy -> "이지런"
                lsd -> "LSD"
                tempo -> "템포런"
                is interval -> "인터벌 ${reps}×${meters}m"
            }
    }

    /// 왜 이 훈련인가 — 화면이 캡션 문장을 고르는 근거
    enum class Reason {
        battery,        // 배터리 주의 → 가볍게
        hardRecently,   // 어제·오늘 고강도/롱런 → 회복
        lsdDue,         // 이번 주 롱런 미완 — 남은 기회가 적다
        qualityDue,     // 이번 주 퀄리티 세션 잔여
        fill,           // 잔여 거리 소화
        none,           // rest·done 계열
    }
}

// MARK: - 심박 기준 (이슈 #56)

/// 심박 존 방식 (이슈 #56) — %HRmax(기본) / Karvonen(HRR, Karvonen 1957).
/// 저장값(rawValue)은 영문 고정: 라벨 문구를 바꿔도 저장값이 깨지지 않는다
enum class HeartRateZoneMethod {
    percentMax, karvonen;

    val rawValue: String get() = name

    val label: String
        get() = when (this) {
            percentMax -> "%HRmax"
            karvonen -> "Karvonen(HRR)"
        }

    companion object {
        fun fromRawValue(rawValue: String): HeartRateZoneMethod? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/// 심박 기준 (이슈 #56) — 심박 존·대회 노력도·세션 상세 표기가 모두 이 값 하나를 쓴다.
/// 우선순위: 수동 입력 > 추정(관찰 최대·Tanaka 2001) > 190 폴백.
/// zoneMethod는 "실제 적용된" 방식이다 — Karvonen을 골랐어도 안정 심박이 없으면
/// %HRmax로 떨어진다 (HRR = HRmax − 안정 심박을 만들 재료가 없다)
data class HeartRateProfile(
    val hrMax: Double,
    val hrMaxSource: Source,
    /// 안정 심박 — 수동 > HealthKit 최근값, 범위 밖이면 nil
    val restingHR: Double?,
    val zoneMethod: HeartRateZoneMethod,
) {
    enum class Source {
        manual, observed, tanaka, fallback;

        val label: String
            get() = when (this) {
                manual -> "직접 입력"
                observed -> "관찰 최대"
                tanaka -> "생년 Tanaka"
                fallback -> "기본값"
            }
    }

    /// 대회 노력도용 HRmax — 190 폴백은 근거가 아니라 nil로 넘겨 Riegel로 떨어지게 한다
    /// (기존 동작 유지, RaceOutlookEngine의 hrMaxBpm nil 경로)
    val reliableHrMax: Double? get() = if (hrMaxSource == Source.fallback) null else hrMax

    /// 존 판정용 강도 비율 — %HRmax: bpm / HRmax,
    /// Karvonen(1957): (bpm − 안정) / (HRmax − 안정) = %HRR.
    /// 두 범위(최대 ≥120, 안정 ≤100)가 겹치지 않아 HRR은 항상 양수다
    fun intensity(bpm: Double): Double {
        val rest = restingHR
        if (zoneMethod != HeartRateZoneMethod.karvonen || rest == null) return bpm / hrMax
        return (bpm - rest) / (hrMax - rest)
    }

    companion object {
        /// 수동 입력 허용 범위 — 밖이면 무시하고 추정을 쓴다.
        /// 최대 심박은 세션 최고 심박 신뢰 범위(plausiblePeakBpm 120...230)와 같다
        val hrMaxRange: IntRange = 120..230
        val restingRange: IntRange = 30..100
    }
}

/// (Android: iOS `var now = Date()`는 기본값 없이 주입받고, `Calendar.current`·ISO 주 대신 `zone`을 받는다)
data class TrainingGuideEngine(
    val now: Instant,
    val zone: ZoneId,
    /// 퀄리티 세션 구성과 인터벌 스펙을 바꾼다 — 문장 난이도는 화면 몫.
    /// 기본값을 런잘알(intermediate)로 두는 이유는 ReportEngine.level과 같다 (§4 표준 톤).
    val level: RunnerLevel = RunnerLevel.intermediate,
) {
    // MARK: - 진단 (Riegel)

    /// 예측 입력 한 건 — Riegel·VDOT의 재료는 거리와 시간뿐이라 공인 거리일 필요가 없다.
    /// PersonalRecords.Entry가 아니라 실제 세션에서 직접 뽑는 이유는 `predictionSamples` 참고.
    data class PredictionSample(
        val distanceKm: Double,
        val timeSec: Double,
        val date: Instant,
        /// 세션 당시 기온·습도 — 열 중립 환산(neutralTimeSec)의 재료. 실내·미기록이면 nil (이슈 #33)
        val weatherTempC: Double? = null,
        val weatherHumidityPct: Double? = null,
        /// 세션 평균 심박 — 대회 노력도(EF) 환산의 재료. 미기록이면 환산을 건너뛴다 (이슈 #34)
        val avgHeartRate: Double? = null,
    ) {
        /// 근거 표기용 라벨 — 공인 거리가 아니므로 "7.4km"처럼 실제 거리로 적는다.
        /// 예전에는 "5K" 같은 공인 종목명이었다 (이슈 #24로 입력이 임의 거리가 되며 바뀜)
        val label: String get() = Format.km(distanceKm) + "km"

        /// 열 중립 환산 기록(초) — 세션 당시 더위를 제거해 "선선한 날이었다면"의 기록으로 바꾼다.
        /// 한여름 세션의 더위 페널티가 예측·VDOT에 그대로 실리던 문제의 수정이다 (이슈 #33).
        /// 날씨가 없거나(실내) 보정량이 노이즈 바닥(3초/km) 미만이면 원본 기록 그대로.
        val neutralTimeSec: Double
            get() {
                val adjusted = HeatEngine.adjustment(paceSecPerKm = timeSec / distanceKm,
                                                     tempC = weatherTempC,
                                                     humidityPct = weatherHumidityPct)
                    ?: return timeSec
                return adjusted.adjustedPaceSecPerKm * distanceKm
            }

        /// 중립 환산으로 빠진 보정량(초/km, 양수) — 화면이 "훈련 더위 −N초/km 반영"을 밝히는 근거
        val heatDeltaSecPerKm: Double get() = (timeSec - neutralTimeSec) / distanceKm
    }

    /// iOS `(sample: PredictionSample, sec: Double, windowDays: Int)` 튜플
    data class BestPrediction(val sample: PredictionSample, val sec: Double, val windowDays: Int)

    /// iOS `(bpm: Double, source: HeartRateProfile.Source)` 튜플
    data class HrMaxEstimate(val bpm: Double, val source: HeartRateProfile.Source)

    /// iOS `(date: Date, value: Double)` 튜플 — VO₂max 표본
    data class Vo2MaxSample(val date: Instant, val value: Double)

    /// iOS `(time: Date, bpm: Double)` 튜플 — 심박 샘플
    data class HeartRateSample(val time: Instant, val bpm: Double)

    /// iOS `(tempo: Int, interval: Int)` 튜플
    data class QualityMix(val tempo: Int, val interval: Int)

    /// iOS `(reps: Int, meters: Int)` 튜플
    data class IntervalSpec(val reps: Int, val meters: Int)

    // MARK: - 대회 예상 기록 (이슈 #34)

    /// 대회 예상 기록 — 구간의 두 끝과 근거 (이슈 #34).
    /// 값은 모두 **열 중립 기준**이다 — 대회일 더위는 RaceOutlookEngine이 따로 더한다.
    data class RacePrediction(
        val sample: PredictionSample,
        val windowDays: Int,
        /// 느린 끝 — "훈련 페이스 그대로 뛴다면"의 Riegel 외삽 (VO₂max 추세 보정 포함)
        val riegelSec: Double,
        /// 빠른 끝 — "대회 노력도로 뛴다면"의 EF 환산 (VO₂max 추세 보정 포함).
        /// 심박·HRmax가 없거나 표본이 이미 대회 노력도 이상이면 nil → 단일 값 표시
        val effortSec: Double?,
        /// VO₂max 추세 보정 배율 — 1.0이면 보정 없음. 화면이 근거 문구를 밝히는 데 쓴다
        val fitnessRatio: Double,
        /// 직접 입력한 대회 기록이 근거인지 — 화면이 "대회 기록 기준"을 밝히는 데 쓴다.
        /// true면 표본 창을 거치지 않았으므로 windowDays는 0이다 (이슈 #35)
        val isRaceRecord: Boolean,
    )

    companion object {
        /// 예측 입력의 최소 거리(km). 이보다 짧은 세션은 페이스 변동성이 커 외삽 기반이 못 된다
        /// (이슈 #24 — 정확도 우선으로 보수적으로 잡았다: "틀린 인사이트는 없느니만 못하다").
        const val minSampleKm = 5.0

        /// Riegel 외삽 배율 상한. 원 논문(Riegel 1981)의 신뢰 구간이 약 1/3~3배이고
        /// 그 밖은 오차가 급격히 커진다 — 5km 세션으로 풀코스(8.4배)를 추정하지 않는다.
        const val maxExtrapolationRatio = 3.0

        /// 표본 창 — 최근 1주를 우선하고, 없을 때만 4주 → 8주로 넓힌다 (이슈 #24).
        /// 창을 넘나들며 비교하지 않는 것이 핵심이다: 1주 안에 세션이 있으면 8주 전 레이스급
        /// 기록이 더 빨라도 쓰지 않는다. "최근 실력"이 "최고 기록"을 이긴다는 뜻이고,
        /// 이지런만 한 주에 예측이 느려지는 것은 버그가 아니라 의도된 동작이다.
        val sampleWindowDays: List<Int> = listOf(7, 28, 56)

        /// 표본 창 라벨 — "최근 1주"·"최근 4주"·"최근 8주". 화면이 예측 근거 시점을 밝히는 데 쓴다.
        /// 엔진이 문자열을 내는 예외인 이유는 창 상수(sampleWindowDays)와 한 곳에 두기 위해서다
        fun sampleWindowLabel(days: Int): String = "최근 ${max(1, days / 7)}주"

        /// Riegel 예측 — 유효 표본마다 T1×(D2/D1)^1.06을 계산해 최솟값을 고른다
        fun predictedTime(goal: RaceDistance, runs: List<RunSummary>, now: Instant): Double? =
            bestPrediction(goal, runs, now)?.sec

        /// 세션에서 예측 후보를 뽑는다 — 거리·페이스가 있고 최소 거리·외삽 배율 가드를 통과한 것만.
        /// 실내(트레드밀)도 거리·시간이 있으면 쓴다: 워치 추정 거리라 오차가 있지만
        /// 배제하면 겨울철 사용자가 예측을 통째로 잃는다.
        fun predictionSamples(goal: RaceDistance, runs: List<RunSummary>): List<PredictionSample> =
            runs.mapNotNull { run ->
                val km = run.distanceKm
                if (km == null || run.paceSecPerKm == null ||
                    km < minSampleKm ||
                    goal.km / km > maxExtrapolationRatio) return@mapNotNull null
                PredictionSample(distanceKm = km, timeSec = run.durationSec, date = run.start,
                                 weatherTempC = run.weatherTempC,
                                 weatherHumidityPct = run.weatherHumidityPct,
                                 avgHeartRate = run.avgHeartRate)
            }

        /// 가장 좁은 창부터 훑어 후보가 있는 첫 창에서 고른다 — 창 간 비교는 하지 않는다.
        /// 반환값의 window는 화면이 "최근 1주 기준"처럼 표본 시점을 밝히는 데 쓴다.
        /// 외삽 재료는 원본이 아니라 **열 중립 환산 기록**이다 (이슈 #33) — 반환 sec도
        /// "선선한 조건 기준" 예상 기록이고, 대회일 더위는 RaceOutlookEngine이 따로 더한다.
        fun bestPrediction(goal: RaceDistance, runs: List<RunSummary>, now: Instant): BestPrediction? {
            val samples = predictionSamples(goal, runs)
            for (days in sampleWindowDays) {
                val cutoff = now.minusSeconds(days * 86_400L)
                val best = samples.filter { it.date >= cutoff }
                    .map { BestPrediction(it, it.neutralTimeSec * (goal.km / it.distanceKm).pow(1.06), days) }
                    .minByOrNull { it.sec }
                if (best != null) return best
            }
            return null
        }

        /// 대회 예측 전용 표본 창 — 폼 읽기(sampleWindowDays)와 목적이 다르다.
        /// 폼 읽기는 "지금 이 순간의 실력"이라 최근 1주를 우선하지만, 대회 예측은
        /// "대회 당일 낼 수 있는 최대치"라 창을 넓게 잡는다: 최근 4주 우선, 없으면 12주.
        /// 테이퍼·대회 주간에는 4주 창을 건너뛴다 — 감량 주간의 이지런이 표본을
        /// 독식해 예측이 급락하는 것을 막는다 (테이퍼 중 실력은 떨어지지 않는다).
        val raceSampleWindowDays: List<Int> = listOf(28, 84)

        /// 대회 목표 심박 비율(%HRmax) — 일반적인 레이스 노력도 관례 (가정):
        /// 5K 96% / 10K 93% / 하프 90% / 풀 84% (Friel 존 구분 등 훈련서 관례 수준)
        fun raceEffortFraction(race: RaceDistance): Double = when (race) {
            RaceDistance.fiveK -> 0.96
            RaceDistance.tenK -> 0.93
            RaceDistance.half -> 0.90
            RaceDistance.full -> 0.84
        }

        /// 직접 입력한 대회 기록의 최대 나이(일) — 약 2년. 그보다 오래된 기록은
        /// 현재 체력을 반영한다고 보기 어렵고, VO₂max 추세 창(±14일)이 닿을 가능성도
        /// 낮아 보정 없이 그대로 실린다 — 내지 않는 쪽이 안전하다 (이슈 #35)
        const val maxRaceRecordAgeDays = 730

        /// 대회 예상 기록 계산 — 느린 끝은 Riegel, 빠른 끝은 대회 노력도(EF) 환산.
        ///
        /// 빠른 끝 산식: EF(효율 지수) = 속도(m/분) / 평균 심박 (ReportEngine.efficiency와 동일 정의).
        /// 대회 페이스 = 60,000 / (EF × HRmax × 종목별 %HRmax). 심박-속도 선형 가정은
        /// 고강도에서 오차가 커지는 근사지만, 이지런 표본밖에 없는 러너에게
        /// "전력이면 이 정도"의 상한을 주는 것이 목적이라 방향이 맞다.
        /// 훈련은 이지런만 하는 러너의 Riegel 예측이 실제 대회 기록보다 크게 느리다는
        /// 피드백의 수정이다 — 훈련 페이스는 대회 노력도가 아니다.
        /// 직접 입력한 지난 대회 기록(raceRecords)도 표본으로 경쟁한다 (이슈 #35).
        fun racePrediction(goal: RaceDistance, runs: List<RunSummary>, now: Instant, zone: ZoneId,
                           daysToRace: Int? = null, hrMaxBpm: Double? = null,
                           vo2MaxSamples: List<Vo2MaxSample> = emptyList(),
                           raceRecords: List<RaceRecord> = emptyList()): RacePrediction? {
            // 테이퍼·대회 주간이면 4주 창 건너뜀 — 12주 창에서 가장 강했던 세션을 쓴다
            var windows = raceSampleWindowDays
            if (daysToRace != null) {
                val phase = phase(daysToRace, goal)
                if (phase == TrainingGuide.Phase.taper || phase == TrainingGuide.Phase.raceWeek) {
                    windows = windows.drop(1)
                }
            }

            val samples = predictionSamples(goal, runs)
            var windowBest: BestPrediction? = null
            for (days in windows) {
                val cutoff = now.minusSeconds(days * 86_400L)
                val best = samples.filter { it.date >= cutoff }
                    .map { BestPrediction(it, it.neutralTimeSec * (goal.km / it.distanceKm).pow(1.06), days) }
                    .minByOrNull { it.sec }
                if (best != null) {
                    windowBest = best
                    break
                }
            }
            val training: RacePrediction? = windowBest?.let { best ->
                val ratio = fitnessRatio(sampleDate = best.sample.date, now = now,
                                         vo2MaxSamples = vo2MaxSamples)
                val effort = effortSec(goal, sample = best.sample, hrMaxBpm = hrMaxBpm)
                    ?.let { if (it < best.sec) it * ratio else null }  // Riegel보다 느리면 환산 무의미
                RacePrediction(sample = best.sample, windowDays = best.windowDays,
                               riegelSec = best.sec * ratio,
                               effortSec = effort,
                               fitnessRatio = ratio,
                               isRaceRecord = false)
            }

            // 직접 입력한 대회 기록 후보 (이슈 #35) — 표본 창을 적용하지 않는다: 몇 달 전
            // 대회 기록이라도 훈련 표본에는 없는 "전력 노력"의 증거다. 시점 차이는 VO₂max
            // 추세 배율이 보정한다. 최소 거리·3배 외삽·최대 나이 가드는 훈련 표본과 동일하다.
            // 페이스 타당성 가드(이슈 #93) — 오독·휠 실수 기록이 최솟값으로 항상 이기지 않게 한다.
            // 열 중립 환산(이슈 #100) — 대회 기록엔 세션 날씨가 없어 기록 월의 서울 평년값
            // (RaceOutlookEngine.monthlyNormals)을 날씨 자리에 넣는다. 그러면 neutralTimeSec이
            // 원본 − 보정량 × km, heatDeltaSecPerKm이 그 보정량(없으면 0)이 된다. 훈련 표본과 같은
            // 중립 기준이어야 RaceOutlookEngine이 대회 월 더위를 더할 때 여름 기록의 더위가 두 번 실리지 않는다.
            val recordCutoff = now.minusSeconds(maxRaceRecordAgeDays * 86_400L)
            val record: RacePrediction? = raceRecords
                .filter {
                    RaceRecord.isPlausible(timeSec = it.timeSec, km = it.race.km) &&
                        it.date <= now && it.date >= recordCutoff &&
                        it.race.km >= minSampleKm &&
                        goal.km / it.race.km <= maxExtrapolationRatio
                }
                .map { record ->
                    val month = record.date.atZone(zone).monthValue
                    val normal = RaceOutlookEngine.monthlyNormals[month - 1]
                    val sample = PredictionSample(distanceKm = record.race.km,
                                                  timeSec = record.timeSec, date = record.date,
                                                  weatherTempC = normal.tempC,
                                                  weatherHumidityPct = normal.humidityPct)
                    val ratio = fitnessRatio(sampleDate = record.date, now = now,
                                             vo2MaxSamples = vo2MaxSamples)
                    // 대회 기록은 정의상 전력 노력 — EF 환산(빠른 끝)을 만들지 않는다
                    RacePrediction(sample = sample, windowDays = 0,
                                   riegelSec = sample.neutralTimeSec *
                                       (goal.km / record.race.km).pow(1.06) * ratio,
                                   effortSec = null,
                                   fitnessRatio = ratio,
                                   isRaceRecord = true)
                }
                .minByOrNull { it.riegelSec }

            // 훈련 표본과 대회 기록이 모두 있으면 느린 끝(Riegel)이 빠른 쪽 — 표본끼리
            // 최솟값을 고르는 기존 규칙과 같은 뜻이다. 대회 기록이 이기면 EF 환산 구간은
            // 버린다: 실제 대회의 증거가 심박 선형 가정의 추정보다 우선이다.
            return when {
                training != null && record != null -> if (record.riegelSec < training.riegelSec) record else training
                training != null -> training
                record != null -> record
                else -> null
            }
        }

        /// 빠른 끝 — 대회 노력도(EF) 환산 (열 중립 기준). 재료가 없으면 nil
        private fun effortSec(goal: RaceDistance, sample: PredictionSample, hrMaxBpm: Double?): Double? {
            val avgHR = sample.avgHeartRate
            if (avgHR == null || avgHR !in 80.0..220.0 ||
                hrMaxBpm == null || !(hrMaxBpm > avgHR)) return null
            val neutralPace = sample.neutralTimeSec / sample.distanceKm
            val ef = (60_000 / neutralPace) / avgHR              // 속도(m/분) / 심박
            val targetHR = hrMaxBpm * raceEffortFraction(goal)
            val racePace = 60_000 / (ef * targetHR)
            // 세계기록보다 빠른 환산은 입력 이상(심박 미착용 등의 저심박) — 내지 않는다
            if (!(racePace >= minGoalPaceSecPerKm)) return null
            return racePace * goal.km
        }

        /// VO₂max 추세 보정 배율 — 오래된 표본을 현재 체력 기준으로 앞당긴다(또는 되돌린다).
        /// 애플워치 VO₂max는 절대값이 과소평가되는 경향이 있어(러닝 추정 한계) 절대값은
        /// 쓰지 않고 **비율만** 쓴다. 표본 시점 ±14일과 현재(최근 14일)의 평균을 비교하고,
        /// 각 창에 표본 2개 미만이거나 차이가 ±1.0 mL/kg/min 미만이면 보정하지 않는다(1.0)
        /// — 워치 추정 노이즈 수준의 변화로 예측을 흔들지 않는다. 배율은 ±15%로 제한한다.
        fun fitnessRatio(sampleDate: Instant, now: Instant, vo2MaxSamples: List<Vo2MaxSample>): Double {
            val halfWindow = 14.0 * 86_400
            val atSample = vo2MaxSamples.filter { abs(seconds(sampleDate, it.date)) <= halfWindow }
                .map { it.value }
            val currentFrom = instantSince1970(now.timeIntervalSince1970 - halfWindow)
            val current = vo2MaxSamples.filter { it.date <= now && it.date >= currentFrom }
                .map { it.value }
            if (atSample.size < 2 || current.size < 2) return 1.0
            val vo2AtSample = atSample.sum() / atSample.size
            val vo2Now = current.sum() / current.size
            if (!(vo2AtSample > 0 && vo2Now > 0 && abs(vo2Now - vo2AtSample) >= 1.0)) return 1.0
            return min(max(vo2AtSample / vo2Now, 0.85), 1.15)
        }

        /// HRmax 추정 — ① 관찰 최대: 최근 12주 세션별 최고 심박 중 2번째 값
        /// (1건뿐인 이상 스파이크 방어), 표본 3개 이상일 때만.
        /// ② Tanaka(2001) 208 − 0.7×나이.
        /// 관찰 최대는 "HRmax가 이보다 낮을 수는 없다"는 하한 증거라 Tanaka와 **큰 쪽**을 쓴다
        /// — 이지런만 한 러너의 관찰 최대는 HRmax를 크게 밑돈다. 둘 다 없으면 190 폴백.
        /// 출처를 함께 돌려줘 설정·세션 상세가 "관찰 최대/생년 Tanaka/기본값"을 밝힌다 (이슈 #48·#56).
        /// 수동 입력은 여기서 섞지 않는다 — heartRateProfile이 이 추정 위에 얹는다
        fun hrMaxEstimate(runs: List<RunSummary>, now: Instant, zone: ZoneId, birthYear: Int?): HrMaxEstimate {
            val cutoff = now.minusSeconds(84 * 86_400L)
            val peaks = runs.filter { it.start >= cutoff && it.start <= now }
                .mapNotNull { it.maxHeartRate }
                .filter { it in plausiblePeakBpm }   // 밖은 착용 불량·이상치
                .sortedDescending()
            val observed: Double? = if (peaks.size >= 3) peaks[1] else null
            val tanaka: Double? = birthYear?.let { year ->
                val age = now.atZone(zone).year - year
                if (age in 10..100) 208 - 0.7 * age else null
            }
            return when {
                observed != null && tanaka != null ->
                    if (observed >= tanaka) HrMaxEstimate(observed, HeartRateProfile.Source.observed)
                    else HrMaxEstimate(tanaka, HeartRateProfile.Source.tanaka)
                observed != null -> HrMaxEstimate(observed, HeartRateProfile.Source.observed)
                tanaka != null -> HrMaxEstimate(tanaka, HeartRateProfile.Source.tanaka)
                else -> HrMaxEstimate(fallbackHrMaxBpm, HeartRateProfile.Source.fallback)
            }
        }

        /// 심박 기준 해석 (이슈 #56) — 수동값(0 = 미설정, 범위 밖 무시) > 추정.
        /// 화면이 원시 @AppStorage 값을 그대로 넘겨 가공하지 않게 한다 — 우선순위·범위 가드는 이 한 곳에만.
        /// 안정 심박: 수동 > HealthKit 최근값(범위 안일 때만). Karvonen인데 안정 심박이 없으면 %HRmax
        fun heartRateProfile(estimate: HrMaxEstimate,
                             manualHrMax: Int, manualRestingHR: Int,
                             measuredRestingHR: Double?, zoneMethodRaw: String): HeartRateProfile {
            val hrMax = if (manualHrMax in HeartRateProfile.hrMaxRange) {
                HrMaxEstimate(manualHrMax.toDouble(), HeartRateProfile.Source.manual)
            } else {
                estimate
            }
            val restingRange = HeartRateProfile.restingRange
            val resting: Double? = when {
                manualRestingHR in restingRange -> manualRestingHR.toDouble()
                measuredRestingHR != null &&
                    measuredRestingHR in restingRange.first.toDouble()..restingRange.last.toDouble() -> measuredRestingHR
                else -> null
            }
            var method = HeartRateZoneMethod.fromRawValue(zoneMethodRaw) ?: HeartRateZoneMethod.percentMax
            if (method == HeartRateZoneMethod.karvonen && resting == null) method = HeartRateZoneMethod.percentMax
            return HeartRateProfile(hrMax = hrMax.bpm, hrMaxSource = hrMax.source,
                                    restingHR = resting, zoneMethod = method)
        }

        // MARK: - 심박 존 (세션 상세, 이슈 #48)

        /// 세션 최고 심박으로 믿을 수 있는 범위 — 밖은 착용 불량·이상치 (hrMaxEstimate 관찰 표본 필터와 공용)
        val plausiblePeakBpm: ClosedFloatingPointRange<Double> = 120.0..230.0

        /// HRmax 추정치(관찰 최대·Tanaka)가 둘 다 없을 때 존 계산에 쓰는 폴백
        const val fallbackHrMaxBpm = 190.0

        /// 세션 최고 심박 — 230 초과 스파이크(착용 불량)만 버린다. 하한은 두지 않는다:
        /// 120 아래로만 달린 쉬운 조깅에서도 최고 심박 줄은 유효하다. 남는 값이 없으면 nil
        fun sessionPeakBpm(bpms: List<Double>): Double? =
            bpms.filter { it <= plausiblePeakBpm.endInclusive }.maxOrNull()

        /// 심박 샘플 → Z1~Z5 시간 비율. 경계는 강도 비율 0.6/0.7/0.8/0.9 —
        /// %HRmax는 × HRmax, Karvonen(1957)은 × HRR + 안정 심박 (이슈 #56).
        /// Karvonen의 0.5는 Z1의 명목 하한일 뿐 그 아래도 Z1에 넣는다 — %HRmax가 0.6 아래를
        /// 전부 Z1로 넣는 것과 맞춰 워밍업·회복 조깅 시간이 비율에서 빠지지 않게 한다.
        /// 샘플 간격(≤15초 캡)으로 가중하고 마지막 샘플은 5초로 친다.
        fun heartRateZones(samples: List<HeartRateSample>, profile: HeartRateProfile): List<Double> {
            val zoneSeconds = DoubleArray(5)
            for ((i, sample) in samples.withIndex()) {
                val weight = if (i + 1 < samples.size) {
                    min(seconds(sample.time, samples[i + 1].time), 15.0)
                } else {
                    5.0
                }
                val zone = zoneIndex(intensity = profile.intensity(sample.bpm))
                zoneSeconds[zone] += max(weight, 0.0)
            }
            val total = zoneSeconds.sum()
            if (!(total > 0)) return listOf(0.0, 0.0, 0.0, 0.0, 0.0)
            return zoneSeconds.map { it / total }
        }

        /// 강도 비율 → 존 인덱스(0 = Z1 … 4 = Z5). 경계 0.6/0.7/0.8/0.9 —
        /// 세션 존(heartRateZones)과 기간별 분포(ZoneDistributionEngine)가 같은 경계를 쓰게 한 곳에 둔다 (이슈 #165)
        fun zoneIndex(intensity: Double): Int =
            if (intensity < 0.6) 0 else if (intensity < 0.7) 1 else if (intensity < 0.8) 2 else if (intensity < 0.9) 3 else 4

        // MARK: - 현재 기력 (Daniels VDOT)

        /// Daniels & Gilbert(1979) — 달리기 속도 v(m/분)의 산소 소비량 추정
        private fun vo2(atVelocity: Double): Double {
            val v = atVelocity
            return -4.60 + 0.182_258 * v + 0.000_104 * v * v
        }

        /// 지속 시간 t(분) 동안 유지 가능한 %VO₂max (Daniels & Gilbert 1979)
        private fun sustainableFraction(minutes: Double): Double {
            val t = minutes
            return 0.8 + 0.189_439_3 * exp(-0.012_778 * t) + 0.298_955_8 * exp(-0.193_260_5 * t)
        }

        /// PR 하나에서 VDOT를 역산한다. 공식 유효 구간을 벗어난 이상치는 버린다(nil)
        fun vdot(distanceKm: Double, timeSec: Double): Double? {
            if (!(distanceKm > 0 && timeSec > 0)) return null
            val minutes = timeSec / 60
            val velocity = distanceKm * 1_000 / minutes
            val value = vo2(atVelocity = velocity) / sustainableFraction(minutes = minutes)
            // 러너 실측 범위 밖이면 입력이 이상하다 (가정 — Daniels 표의 수록 구간 30~85)
            return if (value in 20.0..90.0) value else null
        }

        /// %VDOT 강도의 순항 페이스(초/km) — vo2 이차식을 속도에 대해 역산한다
        private fun pace(atFraction: Double, vdot: Double): Double {
            val target = atFraction * vdot
            val a = 0.000_104
            val b = 0.182_258
            val c = -(4.60 + target)
            val velocity = (-b + sqrt(b * b - 4 * a * c)) / (2 * a)   // m/분
            return 60_000 / velocity
        }

        /// 5000m 세계기록(12:35.36, 첩테게이 2020) 페이스가 약 2′31″/km — 이보다 빠른 목표
        /// 페이스는 사람 기록이 아니라 입력 실수다 (예: 종목을 풀로 바꿨는데 목표 기록이 30:00으로 남음)
        const val minGoalPaceSecPerKm = 150.0

        /// 대회 기록 페이스 상한 20′00″/km — RunSummary.paceSecPerKm 가드(150...1200초/km)와
        /// 같은 상한이다. 걷기보다 느린 대회 기록은 휠 실수로 보고 표본에서 뺀다 (이슈 #93)
        const val maxRacePaceSecPerKm = 1_200.0

        /// 존 상수 (Daniels' Running Formula): 이지 62~74% / 템포 88% / 인터벌 97.5%.
        /// VDOT 50에서 Daniels 표와 대조: 이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″ 일치.
        private fun zones(sample: PredictionSample, goalSec: Double?, raceKm: Double): TrainingGuide.PaceZones? {
            // 열 중립 환산 기록으로 VDOT를 역산한다 (이슈 #33) — 한여름 세션의 원본 기록으로
            // 계산하면 VDOT가 실제보다 낮게 나와 이지·템포·인터벌 존이 전부 느리게 처방된다
            val vdot = vdot(distanceKm = sample.distanceKm, timeSec = sample.neutralTimeSec) ?: return null
            val easy = pace(atFraction = 0.74, vdot = vdot)..pace(atFraction = 0.62, vdot = vdot)
            // 목표 페이스 가드 — 이지 존 느린 끝보다 느리면 훈련 정보가 없고(조깅으로도 달성),
            // 세계기록보다 빠르면 사람 기록이 아니다. 둘 다 종목·목표 기록이 어긋난 입력 실수라
            // 페이스를 내지 않는다(nil). "틀린 인사이트는 없느니만 못하다."
            val goalPace = goalSec?.let { it / raceKm }
                ?.let { if (it in minGoalPaceSecPerKm..easy.endInclusive) it else null }
            return TrainingGuide.PaceZones(
                vdot = vdot,
                easySecPerKm = easy,
                tempoSecPerKm = pace(atFraction = 0.88, vdot = vdot),
                intervalSecPerKm = pace(atFraction = 0.975, vdot = vdot),
                goalSecPerKm = goalPace)
        }

        // MARK: - 주기화 (대회 날짜)

        /// 테이퍼 주 수 (대회 주간 제외) — 가정: 풀 2주, 하프 1주, 5K/10K는 대회 주간만
        private fun taperWeeks(race: RaceDistance): Int = when (race) {
            RaceDistance.full -> 2
            RaceDistance.half -> 1
            RaceDistance.fiveK, RaceDistance.tenK -> 0
        }

        /// 남은 주 수로 단계를 가른다 — 피크 3주, 강화 4주, 그 앞은 전부 기초 (가정)
        fun phase(daysToRace: Int, race: RaceDistance): TrainingGuide.Phase? {
            if (daysToRace < 0) return null
            val weeks = daysToRace / 7
            val taper = taperWeeks(race)
            return when {
                weeks == 0 -> TrainingGuide.Phase.raceWeek
                weeks <= taper -> TrainingGuide.Phase.taper
                weeks <= taper + 3 -> TrainingGuide.Phase.peak
                weeks <= taper + 7 -> TrainingGuide.Phase.build
                else -> TrainingGuide.Phase.base
            }
        }

        /// 종목·레벨별 권장 피크 주간 거리(km) — 가정: 일반 입문·중급·상급 플랜 관례 수준.
        /// 10% 룰 점증이 여기 닿으면 더 올리지 않는다 ("이 목표에 이 이상은 필요 없다").
        fun peakWeeklyKm(race: RaceDistance, level: RunnerLevel): Double = when (race) {
            RaceDistance.fiveK -> when (level) {
                RunnerLevel.beginner -> 20.0
                RunnerLevel.intermediate -> 30.0
                RunnerLevel.advanced -> 45.0
            }
            RaceDistance.tenK -> when (level) {
                RunnerLevel.beginner -> 25.0
                RunnerLevel.intermediate -> 40.0
                RunnerLevel.advanced -> 55.0
            }
            RaceDistance.half -> when (level) {
                RunnerLevel.beginner -> 35.0
                RunnerLevel.intermediate -> 50.0
                RunnerLevel.advanced -> 70.0
            }
            RaceDistance.full -> when (level) {
                RunnerLevel.beginner -> 45.0
                RunnerLevel.intermediate -> 65.0
                RunnerLevel.advanced -> 90.0
            }
        }

        /// 단계·레벨별 퀄리티 세션 구성 (템포, 인터벌) — 가정: 80/20 안에서 주 1~2회.
        /// 날짜 미설정(nil)은 강화기 수준으로 본다. 배터리 하향 주간은 인터벌을 끈다.
        fun qualityMix(phase: TrainingGuide.Phase?, level: RunnerLevel, batteryLimited: Boolean): QualityMix {
            val mix = when (phase) {
                TrainingGuide.Phase.raceWeek -> QualityMix(0, 0)
                TrainingGuide.Phase.taper, TrainingGuide.Phase.base ->
                    if (level == RunnerLevel.beginner) QualityMix(0, 0) else QualityMix(1, 0)
                TrainingGuide.Phase.build -> if (level == RunnerLevel.advanced) QualityMix(1, 1) else QualityMix(1, 0)
                TrainingGuide.Phase.peak, null -> if (level == RunnerLevel.beginner) QualityMix(1, 0) else QualityMix(1, 1)
            }
            return if (batteryLimited) QualityMix(mix.tempo, 0) else mix
        }

        /// 목표 대비 판정 — 목표 미입력이면 중립(steady)
        private fun predictionTone(predicted: Double, goal: Double?): RRTone {
            if (goal == null) return RRTone.steady
            if (predicted <= goal) return RRTone.improving
            if (predicted <= goal * 1.05) return RRTone.steady
            return RRTone.caution
        }

        // MARK: - 오늘의 훈련

        /// 오늘 권장 거리의 상한 계수 — 최근 4주 최장 거리의 +10%까지만 (10% 룰, Gabbett 2016).
        /// 주 후반에 잔여량이 몰려 "오늘 12km" 같은 값이 나오는 걸 막는 가드다.
        const val longRunCapFactor = 1.1
        /// 잔여량이 이보다 적으면 거리를 내지 않는다 — "오늘 0.4km"는 처방이 아니라 잡음이다
        const val minPrescribedKm = 1.0

        /// 레벨별 인터벌 스펙 — 가정: 일반 관례 수준 (본훈련 총 1.6~5km, I 페이스)
        fun intervalSpec(level: RunnerLevel): IntervalSpec = when (level) {
            RunnerLevel.beginner -> IntervalSpec(4, 400)
            RunnerLevel.intermediate -> IntervalSpec(5, 800)
            RunnerLevel.advanced -> IntervalSpec(5, 1_000)
        }

        // MARK: - 세션 분류·밸런스

        /// 최근 7일 세션을 분류한다 — 입력 순서 그대로 반환. 최장 거리가 동률이면 모두 LSD 후보.
        fun classify(week: List<RunSummary>, avg4wPaceSec: Double?): List<TrainingGuide.SessionKind> {
            val totalKm = week.mapNotNull { it.distanceKm }.sum()
            val longest = week.mapNotNull { it.distanceKm }.maxOrNull() ?: 0.0
            return week.map { run ->
                val km = run.distanceKm ?: 0.0
                val pace = run.paceSecPerKm
                if (km > 0 && km == longest && km >= totalKm * 0.35) {
                    TrainingGuide.SessionKind.lsd
                } else if (pace != null && avg4wPaceSec != null && pace <= avg4wPaceSec * 0.9) {
                    TrainingGuide.SessionKind.speed
                } else {
                    TrainingGuide.SessionKind.easy
                }
            }
        }

        // MARK: - 주간 창 (ISO 8601, 월요일 시작 — 홈 목표 칩·TodayVerdict와 같은 정의)
        // (Android: `Calendar(identifier: .iso8601)` + `.current` 대신 주입받은 zone의 ISO 주(월요일 시작))

        private fun weekRuns(runs: List<RunSummary>, now: Instant, zone: ZoneId): List<RunSummary> {
            val weekStart = isoWeekStart(now, zone).atStartOfDay(zone).toInstant()
            return runs.filter { it.start >= weekStart && it.start <= now }
        }

        /// 오늘을 포함한 이번 주 남은 날 수 (목요일이면 목·금·토·일 = 4)
        private fun daysLeftInWeek(now: Instant, zone: ZoneId): Int {
            val weekEnd = isoWeekStart(now, zone).plusDays(7)
            return ChronoUnit.DAYS.between(localDay(now, zone), weekEnd).toInt()
        }

        private fun startOfYesterday(now: Instant, zone: ZoneId): Instant =
            localDay(now, zone).minusDays(1).atStartOfDay(zone).toInstant()

        /// 최근 N일 안의 최장 거리 — 상한 가드의 기준. 거리 표본이 없으면 nil(상한 없음)
        private fun longestKm(runs: List<RunSummary>, fromDaysAgo: Int, now: Instant): Double? {
            val from = now.minusSeconds(fromDaysAgo * 86_400L)
            return runs.filter { it.start >= from && it.start <= now }
                .mapNotNull { it.distanceKm }
                .maxOrNull()
        }

        /// 날짜 차이(일) — 자정 경계 기준이라 시각과 무관하다 (대회 당일 = 0)
        private fun days(from: Instant, to: Instant, zone: ZoneId): Int =
            ChronoUnit.DAYS.between(localDay(from, zone), localDay(to, zone)).toInt()

        private fun localDay(date: Instant, zone: ZoneId): LocalDate = date.atZone(zone).toLocalDate()

        /// `to.timeIntervalSince(from)` — Swift `Date`처럼 Double 초로 뺀다
        private fun seconds(from: Instant, to: Instant): Double = to.timeIntervalSince1970 - from.timeIntervalSince1970
    }

    // MARK: - 가이드 전체

    fun guide(runs: List<RunSummary>,
              race: RaceDistance, goalSec: Double?, raceDate: Instant? = null,
              batteryTone: RRTone?): TrainingGuide? {
        // 가드: 3주(21일) — ACWR의 28일보다 느슨하다. 만성 부하가 처방 볼륨 기준이라
        // 분모가 작으면 처방이 적게 나오는 안전한 쪽으로 틀린다 (이슈 #49)
        val oldest = runs.minOfOrNull { it.start }
        if (oldest == null || oldest > date(daysAgo = 21)) return null
        val chronic = totalKm(runs, fromDaysAgo = 28, toDaysAgo = 0) / 4
        if (!(chronic >= 3)) return null

        val limited = batteryTone == RRTone.overload || batteryTone == RRTone.caution
        val daysToRace = raceDate?.let { days(from = now, to = it, zone = zone) }
        val phase = daysToRace?.let { phase(it, race) }
        val peak = peakWeeklyKm(race = race, level = level)

        // 볼륨 — 평상시엔 10% 룰 점증(피크 거리에서 정지), 테이퍼 구간은 감량
        val weeklyLow: Double
        val weeklyHigh: Double
        when (phase) {
            TrainingGuide.Phase.taper -> {
                val built = min(chronic, peak)
                weeklyLow = built * 0.6
                weeklyHigh = built * 0.7
            }
            TrainingGuide.Phase.raceWeek -> {
                val built = min(chronic, peak)
                weeklyLow = built * 0.4
                weeklyHigh = built * 0.5
            }
            else -> {
                weeklyLow = chronic
                weeklyHigh = max(chronic, min(chronic * 1.1, peak))
            }
        }

        val quality = qualityMix(phase = phase, level = level, batteryLimited = limited)
        val prescription = TrainingGuide.Prescription(
            weeklyKmLow = weeklyLow,
            weeklyKmHigh = weeklyHigh,
            lsdKmLow = if (phase == TrainingGuide.Phase.raceWeek) 0.0 else weeklyLow * 0.25,
            lsdKmHigh = if (phase == TrainingGuide.Phase.raceWeek) 0.0
                        else (if (limited) weeklyLow * 0.25 else weeklyHigh * 0.35),
            tempoCount = quality.tempo,
            intervalCount = quality.interval,
            phase = phase,
            daysToRace = if ((daysToRace ?: -1) >= 0) daysToRace else null,
            peakWeeklyKm = peak,
            batteryLimited = limited)

        val best = bestPrediction(race, runs, now)
        val prediction = best?.let {
            TrainingGuide.Prediction(race = race,
                                     predictedSec = it.sec,
                                     baseLabel = it.sample.label,
                                     baseTimeSec = it.sample.timeSec,
                                     baseWindowDays = it.windowDays,
                                     goalSec = goalSec,
                                     tone = predictionTone(predicted = it.sec, goal = goalSec))
        }

        return TrainingGuide(prediction = prediction,
                             zones = best?.let { zones(sample = it.sample, goalSec = goalSec, raceKm = race.km) },
                             prescription = prescription,
                             balance = balance(runs))
    }

    // MARK: - 오늘의 훈련

    /// 이번 주(ISO 8601, 월요일 시작) 이력·배터리로 "오늘 뭘 뛸지" 하나를 고른다.
    ///
    /// 판정 순서 (위가 우선):
    /// 1. 배터리 방전 임박 → 휴식
    /// 2. 주간 횟수/거리를 다 채움 → 완료
    /// 3. 배터리 주의 → 이지 (강한 세션은 회복 뒤로)
    /// 4. 어제·오늘 고강도/롱런 → 이지 (하드-이지 원칙)
    /// 5. 롱런 미완 && 남은 날 ≤ 2 또는 남은 횟수 1 → LSD (마지막 기회)
    /// 6. 퀄리티 세션 잔여 → 템포 먼저, 다음 인터벌 (페이스 존 없으면 건너뛴다 —
    ///    페이스 없는 인터벌 처방은 잡음이다)
    /// 7. 그 외 → 이지 (잔여량을 남은 횟수로 분배, 롱런 몫은 남겨 둔다)
    fun todayWorkout(runs: List<RunSummary>, guide: TrainingGuide,
                     batteryTone: RRTone?, weeklyGoal: Int): TodayWorkout {
        val p = guide.prescription
        if (batteryTone == RRTone.overload) {
            return TodayWorkout(kind = TodayWorkout.Kind.rest, reason = TodayWorkout.Reason.none,
                                distanceKm = null, paceSecPerKm = null)
        }

        val week = weekRuns(runs, now, zone)
        val weekKm = week.mapNotNull { it.distanceKm }.sum()
        val doneCount = week.count(GrowthEngine::countsAsCompletedRun)  // 1km 미만은 횟수로 안 셈 (홈 칩과 동일 기준)
        val remainSessions = min(weeklyGoal - doneCount, daysLeftInWeek(now, zone))
        if (remainSessions <= 0) {
            return TodayWorkout(kind = TodayWorkout.Kind.doneCount, reason = TodayWorkout.Reason.none,
                                distanceKm = null, paceSecPerKm = null)
        }
        // 기준 주간량은 처방 구간의 중앙값 — 배터리 하향 주간은 하한으로 (TodayVerdict v1과 동일)
        val weeklyBase = if (p.batteryLimited) p.weeklyKmLow else (p.weeklyKmLow + p.weeklyKmHigh) / 2
        val remainKm = weeklyBase - weekKm
        if (!(remainKm >= minPrescribedKm)) {
            return TodayWorkout(kind = TodayWorkout.Kind.doneKm, reason = TodayWorkout.Reason.none,
                                distanceKm = null, paceSecPerKm = null)
        }

        val capKm = longestKm(runs, fromDaysAgo = 28, now = now)
            ?.let { it * longRunCapFactor } ?: Double.MAX_VALUE
        val easyPace = guide.zones?.easySecPerKm
        fun easy(reason: TodayWorkout.Reason, km: Double): TodayWorkout =
            TodayWorkout(kind = TodayWorkout.Kind.easy, reason = reason,
                         distanceKm = min(km, capKm), paceSecPerKm = easyPace)
        val splitKm = remainKm / remainSessions

        if (batteryTone == RRTone.caution) return easy(TodayWorkout.Reason.battery, km = splitKm)

        // 하드-이지 원칙: 어제 0시 이후에 스피드(4주 평균보다 10%+ 빠름) 또는
        // 롱런(LSD 하한 이상)을 뛰었으면 오늘은 회복이다
        val paces = runs.filter { it.start >= date(daysAgo = 28) }.mapNotNull { it.paceSecPerKm }
        val avgPace = if (paces.isEmpty()) null else paces.sum() / paces.size
        val hardSince = startOfYesterday(now, zone)
        val ranHardRecently = runs.any { run ->
            if (run.start < hardSince) return@any false
            val pace = run.paceSecPerKm
            if (pace != null && avgPace != null && pace <= avgPace * 0.9) return@any true
            p.lsdKmLow >= 1 && (run.distanceKm ?: 0.0) >= p.lsdKmLow
        }
        if (ranHardRecently) return easy(TodayWorkout.Reason.hardRecently, km = splitKm)

        // LSD — 이번 주 아직이고 남은 기회가 적으면 지금이 적기다
        val lsdDone = p.lsdKmLow >= 1 && week.any { (it.distanceKm ?: 0.0) >= p.lsdKmLow }
        val lsdMid = (p.lsdKmLow + p.lsdKmHigh) / 2
        if (p.lsdKmHigh >= 1 && !lsdDone &&
            (daysLeftInWeek(now, zone) <= 2 || remainSessions == 1)) {
            return TodayWorkout(kind = TodayWorkout.Kind.lsd, reason = TodayWorkout.Reason.lsdDue,
                                distanceKm = min(lsdMid, capKm), paceSecPerKm = easyPace)
        }

        // 퀄리티 — 이번 주 스피드로 분류된 세션 수가 처방보다 적으면 차례다
        val zones = guide.zones
        if (zones != null) {
            val speedDone = week.count { run ->
                val pace = run.paceSecPerKm
                pace != null && avgPace != null && pace <= avgPace * 0.9
            }
            if (speedDone < p.tempoCount) {
                // 템포 20분 — Daniels T 워크아웃 관례(20~40분)의 하한을 쓴다
                val tempoKm = (1_200 / zones.tempoSecPerKm * 10).swiftRounded() / 10
                return TodayWorkout(kind = TodayWorkout.Kind.tempo, reason = TodayWorkout.Reason.qualityDue,
                                    distanceKm = tempoKm,
                                    paceSecPerKm = zones.tempoSecPerKm..zones.tempoSecPerKm)
            }
            if (speedDone < p.qualityCount) {
                val spec = intervalSpec(level = level)
                return TodayWorkout(kind = TodayWorkout.Kind.interval(reps = spec.reps, meters = spec.meters),
                                    reason = TodayWorkout.Reason.qualityDue,
                                    distanceKm = (spec.reps * spec.meters).toDouble() / 1_000,
                                    paceSecPerKm = zones.intervalSecPerKm..zones.intervalSecPerKm)
            }
        }

        // 이지 — 잔여량 분배. 롱런이 남았으면 그 몫은 빼고 나눈다
        if (!lsdDone && p.lsdKmHigh >= 1 && remainSessions > 1) {
            val nonLsdKm = remainKm - lsdMid
            if (nonLsdKm >= minPrescribedKm) {
                return easy(TodayWorkout.Reason.fill, km = nonLsdKm / (remainSessions - 1))
            }
        }
        return easy(TodayWorkout.Reason.fill, km = splitKm)
    }

    private fun balance(runs: List<RunSummary>): TrainingGuide.Balance? {
        val week = runs.filter { it.start >= date(daysAgo = 7) }
        if (week.isEmpty()) return null
        val paces = runs.filter { it.start >= date(daysAgo = 28) }.mapNotNull { it.paceSecPerKm }
        val avgPace = if (paces.isEmpty()) null else paces.sum() / paces.size
        val kinds = classify(week = week, avg4wPaceSec = avgPace)
        val speed = kinds.count { it == TrainingGuide.SessionKind.speed }
        val lsd = kinds.count { it == TrainingGuide.SessionKind.lsd }
        val share = speed.toDouble() / kinds.size * 100
        return TrainingGuide.Balance(easyCount = kinds.size - speed - lsd,
                                     lsdCount = lsd,
                                     speedCount = speed,
                                     speedSharePct = share,
                                     tone = if (share > 20) RRTone.caution else RRTone.steady)
    }

    // MARK: - 공통 (ReportEngine과 같은 창 정의)

    private fun date(daysAgo: Int): Instant = now.minusSeconds(daysAgo * 86_400L)

    /// [now-fromDaysAgo, now-toDaysAgo) 창에 시작된 러닝의 거리 합 (km)
    private fun totalKm(runs: List<RunSummary>, fromDaysAgo: Int, toDaysAgo: Int): Double {
        val from = date(daysAgo = fromDaysAgo)
        val to = date(daysAgo = toDaysAgo)
        return runs.filter { it.start >= from && it.start < to }
            .mapNotNull { it.distanceKm }
            .sum()
    }
}
