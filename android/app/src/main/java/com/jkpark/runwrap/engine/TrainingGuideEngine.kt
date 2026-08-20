package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/// 훈련 가이드 스냅샷 — TrainingGuideEngine.guide()의 결과. iOS `TrainingGuide` 이식.
data class TrainingGuide(
    val prediction: Prediction?,
    val zones: PaceZones?,
    val prescription: Prescription,
    val balance: Balance?,
) {
    /// 목표 레이스 완주 예측 — 최근 8주 안의 PR이 없으면 null (처방만 노출)
    data class Prediction(
        val race: RaceDistance,
        val predictedSec: Double,
        val baseLabel: String,       // 근거 PR 종목 ("5K")
        val baseTimeSec: Double,     // 근거 PR 기록(초)
        val goalSec: Double?,        // 목표 기록 미입력이면 null
        val tone: RRTone,            // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
    )

    /// 훈련 페이스 존 — 예측과 같은 PR에서 역산한 VDOT 기준 (Daniels & Gilbert 1979).
    /// 페이스는 모두 초/km. 존 상수는 VDOT 50(5K 19:57)에서 Daniels 표와 대조해 검증했다
    /// (이지 4′54″~5′38″ / 템포 4′15″ / 인터벌 3′55″).
    data class PaceZones(
        val vdot: Double,
        /// 이지·LSD 페이스 구간 — 빠른 끝(74%)…느린 끝(62%)
        val easySecPerKm: ClosedFloatingPointRange<Double>,
        val tempoSecPerKm: Double,       // 템포(역치)런 — 88%
        val intervalSecPerKm: Double,    // 인터벌 — 97.5% (vVDOT 부근)
        val goalSecPerKm: Double?,       // 목표 레이스 페이스 — 미입력·비현실적(가드 참고)이면 null
    )

    /// 주기화 단계 — 대회 날짜 기준 남은 주 수로 가른다 (가정 — 일반 플랜 관례)
    enum class Phase {
        BASE,       // 기초기 — 볼륨 쌓기
        BUILD,      // 강화기 — 퀄리티 도입
        PEAK,       // 피크 — 최대 볼륨·강도
        TAPER,      // 테이퍼 — 볼륨 감량, 강도 유지
        RACE_WEEK;  // 대회 주간 — 가볍게만

        val label: String
            get() = when (this) {
                BASE -> "기초기"
                BUILD -> "강화기"
                PEAK -> "피크"
                TAPER -> "테이퍼"
                RACE_WEEK -> "대회 주간"
            }
    }

    data class Prescription(
        val weeklyKmLow: Double,     // 만성 부하 그대로 (×1.0) — 테이퍼 구간은 감량 하한
        val weeklyKmHigh: Double,    // ×1.1 (10% 룰 상한), 피크 주간 거리에서 멈춘다
        val lsdKmLow: Double,
        val lsdKmHigh: Double,       // 대회 주간은 0 — 롱런 없이 간다
        val tempoCount: Int,         // 이번 주 템포런 권장 횟수
        val intervalCount: Int,      // 이번 주 인터벌 권장 횟수
        val phase: Phase?,           // 대회 날짜 미설정·지난 날짜면 null
        val daysToRace: Int?,        // D-day (대회 당일 = 0)
        val peakWeeklyKm: Double,    // 이 종목·레벨의 권장 피크 주간 거리 (참고 표시용)
        val batteryLimited: Boolean, // 배터리 하향 보정이 적용됐는지
    ) {
        val qualityCount: Int get() = tempoCount + intervalCount
    }

    /// 최근 7일 세션의 강도 밸런스 — 이번 주 세션이 없으면 null
    data class Balance(
        val easyCount: Int,
        val lsdCount: Int,
        val speedCount: Int,
        val speedSharePct: Double,   // 세션 수 기준 스피드 비중 (가정 — 시간 아닌 횟수)
        val tone: RRTone,            // 스피드 20% 초과면 caution, 그 외 steady
    )

    /// 세션 강도 분류 (가정 — 계획서 M7): LSD = 주간 최장 && 주간 총거리의 35% 이상 /
    /// 스피드 = 4주 평균 페이스보다 10% 이상 빠름 / 나머지 easy. LSD 판정이 스피드보다 우선.
    enum class SessionKind { EASY, LSD, SPEED }
}

/// 오늘의 훈련 추천 — 형태·거리·페이스. 이력·배터리로 정하는 동적 추천이라
/// 요일에 묶이지 않는다. 화면은 이 값을 문장으로 그린다 (라벨은 Kind.label).
data class TodayWorkout(
    val kind: Kind,
    val reason: Reason,
    val distanceKm: Double?,                              // rest·done이면 null. 인터벌은 본훈련 합계
    val paceSecPerKm: ClosedFloatingPointRange<Double>?,  // 페이스 존이 없으면(최근 PR 없음) null
) {
    sealed class Kind {
        data object Rest : Kind()       // 배터리 방전 임박 — 오늘의 처방은 쉬는 것
        data object DoneCount : Kind()  // 주간 목표 횟수 달성
        data object DoneKm : Kind()     // 주간 목표 거리 달성
        data object Easy : Kind()
        data object Lsd : Kind()
        data object Tempo : Kind()
        data class Interval(val reps: Int, val meters: Int) : Kind()

        /// 화면 공통 라벨 — 홈 카드·리포트 카드가 같은 이름을 쓴다
        val label: String
            get() = when (this) {
                Rest -> "휴식"
                DoneCount, DoneKm -> "완료"
                Easy -> "이지런"
                Lsd -> "LSD"
                Tempo -> "템포런"
                is Interval -> "인터벌 ${reps}×${meters}m"
            }
    }

    /// 왜 이 훈련인가 — 화면이 캡션 문장을 고르는 근거
    enum class Reason {
        BATTERY,        // 배터리 주의 → 가볍게
        HARD_RECENTLY,  // 어제·오늘 고강도/롱런 → 회복
        LSD_DUE,        // 이번 주 롱런 미완 — 남은 기회가 적다
        QUALITY_DUE,    // 이번 주 퀄리티 세션 잔여
        FILL,           // 잔여 거리 소화
        NONE,           // rest·done 계열
    }
}

/// 훈련 가이드 엔진 v2 (기획서 §4.9) — 목표 레이스 진단, 훈련 페이스 존, 주간 처방, 오늘의 훈련.
/// 순수 로직: now·레벨·배터리 톤을 값으로 주입받아 결정론적이다. iOS `TrainingGuideEngine.swift` 이식.
///
/// 산식 출처:
/// - 완주 예측: Riegel 공식 T2 = T1 × (D2/D1)^1.06 (Riegel 1981).
///   T1은 최근 8주 안의 PR 중 예측이 가장 좋게 나오는 기록.
/// - 현재 기력·훈련 페이스: Daniels & Gilbert(1979) "Oxygen Power"의 VDOT 공식.
///   같은 PR에서 VDOT를 역산하고 %VDOT 구간으로 페이스 존을 정한다
///   (이지 62~74% / 템포 88% / 인터벌 97.5% — Daniels' Running Formula의 존 정의).
/// - 권장 주간 거리: 만성 부하(4주 주평균) × 1.0~1.1 — 10% 룰 상한.
/// - 주기화: 대회 날짜가 있으면 남은 주 수로 기초→강화→피크→테이퍼→대회 주간을 가른다.
/// - LSD 목표: 권장 주간의 25~35%. 배터리 overload/caution이면 25% 하한 고정 + 인터벌 오프.
/// - 강도 밸런스: (easy+LSD) : 스피드 세션 수를 80/20 원칙과 비교 (Seiler 80/20).
///
/// 가드는 ReportEngine.acwr와 동일 — 기록이 3주 미만이거나 만성 부하가 주 3km 미만이면
/// 진단·처방 전체를 내지 않는다(null). 페이스 존은 최근 8주 안의 PR이 없으면 따로 내지 않는다.
class TrainingGuideEngine(
    private val now: Instant,
    /// 퀄리티 세션 구성과 인터벌 스펙을 바꾼다 — 문장 난이도는 화면 몫.
    /// 기본값을 런잘알(intermediate)로 두는 이유는 ReportEngine.level과 같다 (§4 표준 톤)
    private val level: RunnerLevel = RunnerLevel.INTERMEDIATE,
) {
    companion object {
        // MARK: - 진단 (Riegel)

        /// Riegel 예측 — 최근 8주(56일) 안의 PR마다 T1×(D2/D1)^1.06을 계산해 최솟값을 고른다
        fun predictedTime(goal: RaceDistance, records: List<PersonalRecords.Entry>, now: Instant): Double? =
            bestPrediction(goal, records, now)?.second

        private fun bestPrediction(
            goal: RaceDistance,
            records: List<PersonalRecords.Entry>,
            now: Instant,
        ): Pair<PersonalRecords.Entry, Double>? {
            val cutoff = now.minusSeconds(56 * 86_400L)
            return records.filter { it.date >= cutoff }
                .map { it to it.timeSec * (goal.km / it.distanceKm).pow(1.06) }
                .minByOrNull { it.second }
        }

        // MARK: - 현재 기력 (Daniels VDOT)

        /// Daniels & Gilbert(1979) — 달리기 속도 v(m/분)의 산소 소비량 추정
        private fun vo2(v: Double): Double = -4.60 + 0.182_258 * v + 0.000_104 * v * v

        /// 지속 시간 t(분) 동안 유지 가능한 %VO₂max (Daniels & Gilbert 1979)
        private fun sustainableFraction(t: Double): Double =
            0.8 + 0.189_439_3 * exp(-0.012_778 * t) + 0.298_955_8 * exp(-0.193_260_5 * t)

        /// PR 하나에서 VDOT를 역산한다. 공식 유효 구간을 벗어난 이상치는 버린다(null)
        fun vdot(distanceKm: Double, timeSec: Double): Double? {
            if (distanceKm <= 0 || timeSec <= 0) return null
            val minutes = timeSec / 60
            val velocity = distanceKm * 1_000 / minutes
            val value = vo2(velocity) / sustainableFraction(minutes)
            // 러너 실측 범위 밖이면 입력이 이상하다 (가정 — Daniels 표의 수록 구간 30~85)
            return if (value in 20.0..90.0) value else null
        }

        /// %VDOT 강도의 순항 페이스(초/km) — vo2 이차식을 속도에 대해 역산한다
        private fun pace(fraction: Double, vdot: Double): Double {
            val target = fraction * vdot
            val a = 0.000_104
            val b = 0.182_258
            val c = -(4.60 + target)
            val velocity = (-b + sqrt(b * b - 4 * a * c)) / (2 * a)   // m/분
            return 60_000 / velocity
        }

        /// 5000m 세계기록(12:35.36, 첩테게이 2020) 페이스가 약 2′31″/km — 이보다 빠른 목표
        /// 페이스는 사람 기록이 아니라 입력 실수다 (예: 종목을 풀로 바꿨는데 목표가 30:00으로 남음)
        const val MIN_GOAL_PACE_SEC_PER_KM = 150.0

        /// 존 상수 (Daniels' Running Formula): 이지 62~74% / 템포 88% / 인터벌 97.5%
        private fun zones(
            entry: PersonalRecords.Entry,
            goalSec: Double?,
            raceKm: Double,
        ): TrainingGuide.PaceZones? {
            val vdot = vdot(entry.distanceKm, entry.timeSec) ?: return null
            val easy = pace(0.74, vdot)..pace(0.62, vdot)
            // 목표 페이스 가드 — 이지 존 느린 끝보다 느리면 훈련 정보가 없고(조깅으로도 달성),
            // 세계기록보다 빠르면 사람 기록이 아니다. 둘 다 입력 실수라 페이스를 내지 않는다(null)
            val goalPace = goalSec?.let { it / raceKm }
                ?.takeIf { it in MIN_GOAL_PACE_SEC_PER_KM..easy.endInclusive }
            return TrainingGuide.PaceZones(
                vdot = vdot,
                easySecPerKm = easy,
                tempoSecPerKm = pace(0.88, vdot),
                intervalSecPerKm = pace(0.975, vdot),
                goalSecPerKm = goalPace,
            )
        }

        // MARK: - 주기화 (대회 날짜)

        /// 테이퍼 주 수 (대회 주간 제외) — 가정: 풀 2주, 하프 1주, 5K/10K는 대회 주간만
        private fun taperWeeks(race: RaceDistance): Int = when (race) {
            RaceDistance.FULL -> 2
            RaceDistance.HALF -> 1
            RaceDistance.FIVE_K, RaceDistance.TEN_K -> 0
        }

        /// 남은 주 수로 단계를 가른다 — 피크 3주, 강화 4주, 그 앞은 전부 기초 (가정)
        fun phase(daysToRace: Int, race: RaceDistance): TrainingGuide.Phase? {
            if (daysToRace < 0) return null
            val weeks = daysToRace / 7
            val taper = taperWeeks(race)
            return when {
                weeks == 0 -> TrainingGuide.Phase.RACE_WEEK
                weeks <= taper -> TrainingGuide.Phase.TAPER
                weeks <= taper + 3 -> TrainingGuide.Phase.PEAK
                weeks <= taper + 7 -> TrainingGuide.Phase.BUILD
                else -> TrainingGuide.Phase.BASE
            }
        }

        /// 종목·레벨별 권장 피크 주간 거리(km) — 가정: 일반 입문·중급·상급 플랜 관례 수준.
        /// 10% 룰 점증이 여기 닿으면 더 올리지 않는다 ("이 목표에 이 이상은 필요 없다")
        fun peakWeeklyKm(race: RaceDistance, level: RunnerLevel): Double = when (race) {
            RaceDistance.FIVE_K -> when (level) {
                RunnerLevel.BEGINNER -> 20.0
                RunnerLevel.INTERMEDIATE -> 30.0
                RunnerLevel.ADVANCED -> 45.0
            }
            RaceDistance.TEN_K -> when (level) {
                RunnerLevel.BEGINNER -> 25.0
                RunnerLevel.INTERMEDIATE -> 40.0
                RunnerLevel.ADVANCED -> 55.0
            }
            RaceDistance.HALF -> when (level) {
                RunnerLevel.BEGINNER -> 35.0
                RunnerLevel.INTERMEDIATE -> 50.0
                RunnerLevel.ADVANCED -> 70.0
            }
            RaceDistance.FULL -> when (level) {
                RunnerLevel.BEGINNER -> 45.0
                RunnerLevel.INTERMEDIATE -> 65.0
                RunnerLevel.ADVANCED -> 90.0
            }
        }

        /// 단계·레벨별 퀄리티 세션 구성 (템포, 인터벌) — 가정: 80/20 안에서 주 1~2회.
        /// 날짜 미설정(null)은 강화기 수준으로 본다. 배터리 하향 주간은 인터벌을 끈다
        fun qualityMix(
            phase: TrainingGuide.Phase?,
            level: RunnerLevel,
            batteryLimited: Boolean,
        ): Pair<Int, Int> {
            val mix = when (phase) {
                TrainingGuide.Phase.RACE_WEEK -> 0 to 0
                TrainingGuide.Phase.TAPER, TrainingGuide.Phase.BASE ->
                    if (level == RunnerLevel.BEGINNER) 0 to 0 else 1 to 0
                TrainingGuide.Phase.BUILD ->
                    if (level == RunnerLevel.ADVANCED) 1 to 1 else 1 to 0
                TrainingGuide.Phase.PEAK, null ->
                    if (level == RunnerLevel.BEGINNER) 1 to 0 else 1 to 1
            }
            return if (batteryLimited) mix.first to 0 else mix
        }

        /// 목표 대비 판정 — 목표 미입력이면 중립(steady)
        private fun predictionTone(predicted: Double, goal: Double?): RRTone {
            if (goal == null) return RRTone.STEADY
            if (predicted <= goal) return RRTone.IMPROVING
            if (predicted <= goal * 1.05) return RRTone.STEADY
            return RRTone.CAUTION
        }

        // MARK: - 오늘의 훈련 상수

        /// 오늘 권장 거리의 상한 계수 — 최근 4주 최장 거리의 +10%까지만 (10% 룰, Gabbett 2016).
        /// 주 후반에 잔여량이 몰려 "오늘 12km" 같은 값이 나오는 걸 막는 가드다
        const val LONG_RUN_CAP_FACTOR = 1.1

        /// 잔여량이 이보다 적으면 거리를 내지 않는다 — "오늘 0.4km"는 처방이 아니라 잡음이다
        const val MIN_PRESCRIBED_KM = 1.0

        /// 레벨별 인터벌 스펙 (reps, meters) — 가정: 일반 관례 수준 (본훈련 총 1.6~5km, I 페이스)
        fun intervalSpec(level: RunnerLevel): Pair<Int, Int> = when (level) {
            RunnerLevel.BEGINNER -> 4 to 400
            RunnerLevel.INTERMEDIATE -> 5 to 800
            RunnerLevel.ADVANCED -> 5 to 1_000
        }

        // MARK: - 세션 분류

        /// 최근 7일 세션을 분류한다 — 입력 순서 그대로 반환. 최장 거리가 동률이면 모두 LSD 후보
        fun classify(week: List<RunSummary>, avg4wPaceSec: Double?): List<TrainingGuide.SessionKind> {
            val totalKm = week.mapNotNull { it.distanceKm }.sum()
            val longest = week.mapNotNull { it.distanceKm }.maxOrNull() ?: 0.0
            return week.map { run ->
                val km = run.distanceKm ?: 0.0
                if (km > 0 && km == longest && km >= totalKm * 0.35) {
                    return@map TrainingGuide.SessionKind.LSD
                }
                val pace = run.paceSecPerKm
                if (pace != null && avg4wPaceSec != null && pace <= avg4wPaceSec * 0.9) {
                    TrainingGuide.SessionKind.SPEED
                } else {
                    TrainingGuide.SessionKind.EASY
                }
            }
        }

        // MARK: - 주간 창 (ISO 8601, 월요일 시작 — 홈 목표 칩·TodayVerdict와 같은 정의)

        private fun weekRuns(runs: List<RunSummary>, now: Instant): List<RunSummary> {
            val start = weekStart(now).atStartOfDay(SEOUL).toInstant()
            return runs.filter { it.start >= start && it.start <= now }
        }

        /// 오늘을 포함한 이번 주 남은 날 수 (목요일이면 목·금·토·일 = 4)
        private fun daysLeftInWeek(now: Instant): Int =
            ChronoUnit.DAYS.between(dayOf(now), weekStart(now).plusDays(7)).toInt()

        private fun startOfYesterday(now: Instant): Instant =
            dayOf(now).minusDays(1).atStartOfDay(SEOUL).toInstant()

        /// 최근 N일 안의 최장 거리 — 상한 가드의 기준. 거리 표본이 없으면 null(상한 없음)
        private fun longestKm(runs: List<RunSummary>, fromDaysAgo: Int, now: Instant): Double? {
            val from = now.minusSeconds(fromDaysAgo * 86_400L)
            return runs.filter { it.start >= from && it.start <= now }
                .mapNotNull { it.distanceKm }
                .maxOrNull()
        }

        /// 날짜 차이(일) — 자정 경계 기준이라 시각과 무관하다 (대회 당일 = 0)
        private fun days(from: Instant, to: Instant): Int =
            ChronoUnit.DAYS.between(dayOf(from), dayOf(to)).toInt()
    }

    // MARK: - 가이드 전체

    fun guide(
        runs: List<RunSummary>,
        records: List<PersonalRecords.Entry>,
        race: RaceDistance,
        goalSec: Double?,
        raceDate: Instant? = null,
        batteryTone: RRTone?,
    ): TrainingGuide? {
        // 가드: ReportEngine.acwr와 동일 기준
        val oldest = runs.minOfOrNull { it.start } ?: return null
        if (oldest > date(21)) return null
        val chronic = totalKm(runs, 28, 0) / 4
        if (chronic < 3) return null

        val limited = batteryTone == RRTone.OVERLOAD || batteryTone == RRTone.CAUTION
        val daysToRace = raceDate?.let { days(now, it) }
        val phase = daysToRace?.let { phase(it, race) }
        val peak = peakWeeklyKm(race, level)

        // 볼륨 — 평상시엔 10% 룰 점증(피크 거리에서 정지), 테이퍼 구간은 감량
        val weeklyLow: Double
        val weeklyHigh: Double
        when (phase) {
            TrainingGuide.Phase.TAPER -> {
                val built = min(chronic, peak)
                weeklyLow = built * 0.6
                weeklyHigh = built * 0.7
            }
            TrainingGuide.Phase.RACE_WEEK -> {
                val built = min(chronic, peak)
                weeklyLow = built * 0.4
                weeklyHigh = built * 0.5
            }
            else -> {
                weeklyLow = chronic
                weeklyHigh = max(chronic, min(chronic * 1.1, peak))
            }
        }

        val quality = qualityMix(phase, level, limited)
        val prescription = TrainingGuide.Prescription(
            weeklyKmLow = weeklyLow,
            weeklyKmHigh = weeklyHigh,
            lsdKmLow = if (phase == TrainingGuide.Phase.RACE_WEEK) 0.0 else weeklyLow * 0.25,
            lsdKmHigh = when {
                phase == TrainingGuide.Phase.RACE_WEEK -> 0.0
                limited -> weeklyLow * 0.25
                else -> weeklyHigh * 0.35
            },
            tempoCount = quality.first,
            intervalCount = quality.second,
            phase = phase,
            daysToRace = daysToRace?.takeIf { it >= 0 },
            peakWeeklyKm = peak,
            batteryLimited = limited,
        )

        val best = bestPrediction(race, records, now)
        val prediction = best?.let { (entry, sec) ->
            TrainingGuide.Prediction(
                race = race,
                predictedSec = sec,
                baseLabel = entry.label,
                baseTimeSec = entry.timeSec,
                goalSec = goalSec,
                tone = predictionTone(sec, goalSec),
            )
        }

        return TrainingGuide(
            prediction = prediction,
            zones = best?.let { zones(it.first, goalSec, race.km) },
            prescription = prescription,
            balance = balance(runs),
        )
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
    /// 6. 퀄리티 세션 잔여 → 템포 먼저, 다음 인터벌 (페이스 존 없으면 건너뛴다)
    /// 7. 그 외 → 이지 (잔여량을 남은 횟수로 분배, 롱런 몫은 남겨 둔다)
    fun todayWorkout(
        runs: List<RunSummary>,
        guide: TrainingGuide,
        batteryTone: RRTone?,
        weeklyGoal: Int,
    ): TodayWorkout {
        val p = guide.prescription
        if (batteryTone == RRTone.OVERLOAD) {
            return TodayWorkout(TodayWorkout.Kind.Rest, TodayWorkout.Reason.NONE, null, null)
        }

        val week = weekRuns(runs, now)
        val weekKm = week.mapNotNull { it.distanceKm }.sum()
        val remainSessions = min(weeklyGoal - week.size, daysLeftInWeek(now))
        if (remainSessions <= 0) {
            return TodayWorkout(TodayWorkout.Kind.DoneCount, TodayWorkout.Reason.NONE, null, null)
        }
        // 기준 주간량은 처방 구간의 중앙값 — 배터리 하향 주간은 하한으로 (TodayVerdict v1과 동일)
        val weeklyBase = if (p.batteryLimited) p.weeklyKmLow else (p.weeklyKmLow + p.weeklyKmHigh) / 2
        val remainKm = weeklyBase - weekKm
        if (remainKm < MIN_PRESCRIBED_KM) {
            return TodayWorkout(TodayWorkout.Kind.DoneKm, TodayWorkout.Reason.NONE, null, null)
        }

        val capKm = longestKm(runs, 28, now)?.let { it * LONG_RUN_CAP_FACTOR } ?: Double.MAX_VALUE
        val easyPace = guide.zones?.easySecPerKm
        fun easy(reason: TodayWorkout.Reason, km: Double) =
            TodayWorkout(TodayWorkout.Kind.Easy, reason, min(km, capKm), easyPace)
        val splitKm = remainKm / remainSessions

        if (batteryTone == RRTone.CAUTION) return easy(TodayWorkout.Reason.BATTERY, splitKm)

        // 하드-이지 원칙: 어제 0시 이후에 스피드(4주 평균보다 10%+ 빠름) 또는
        // 롱런(LSD 하한 이상)을 뛰었으면 오늘은 회복이다
        val paces = runs.filter { it.start >= date(28) }.mapNotNull { it.paceSecPerKm }
        val avgPace = if (paces.isEmpty()) null else paces.sum() / paces.size
        val hardSince = startOfYesterday(now)
        val ranHardRecently = runs.any { run ->
            if (run.start < hardSince) return@any false
            val pace = run.paceSecPerKm
            if (pace != null && avgPace != null && pace <= avgPace * 0.9) return@any true
            p.lsdKmLow >= 1 && (run.distanceKm ?: 0.0) >= p.lsdKmLow
        }
        if (ranHardRecently) return easy(TodayWorkout.Reason.HARD_RECENTLY, splitKm)

        // LSD — 이번 주 아직이고 남은 기회가 적으면 지금이 적기다
        val lsdDone = p.lsdKmLow >= 1 && week.any { (it.distanceKm ?: 0.0) >= p.lsdKmLow }
        val lsdMid = (p.lsdKmLow + p.lsdKmHigh) / 2
        if (p.lsdKmHigh >= 1 && !lsdDone &&
            (daysLeftInWeek(now) <= 2 || remainSessions == 1)
        ) {
            return TodayWorkout(TodayWorkout.Kind.Lsd, TodayWorkout.Reason.LSD_DUE,
                min(lsdMid, capKm), easyPace)
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
                val tempoKm = (1_200 / zones.tempoSecPerKm * 10).roundToLong() / 10.0
                return TodayWorkout(TodayWorkout.Kind.Tempo, TodayWorkout.Reason.QUALITY_DUE,
                    tempoKm, zones.tempoSecPerKm..zones.tempoSecPerKm)
            }
            if (speedDone < p.qualityCount) {
                val (reps, meters) = intervalSpec(level)
                return TodayWorkout(TodayWorkout.Kind.Interval(reps, meters),
                    TodayWorkout.Reason.QUALITY_DUE,
                    (reps * meters) / 1_000.0,
                    zones.intervalSecPerKm..zones.intervalSecPerKm)
            }
        }

        // 이지 — 잔여량 분배. 롱런이 남았으면 그 몫은 빼고 나눈다
        if (!lsdDone && p.lsdKmHigh >= 1 && remainSessions > 1) {
            val nonLsdKm = remainKm - lsdMid
            if (nonLsdKm >= MIN_PRESCRIBED_KM) {
                return easy(TodayWorkout.Reason.FILL, nonLsdKm / (remainSessions - 1))
            }
        }
        return easy(TodayWorkout.Reason.FILL, splitKm)
    }

    // MARK: - 밸런스

    private fun balance(runs: List<RunSummary>): TrainingGuide.Balance? {
        val week = runs.filter { it.start >= date(7) }
        if (week.isEmpty()) return null
        val paces = runs.filter { it.start >= date(28) }.mapNotNull { it.paceSecPerKm }
        val avgPace = if (paces.isEmpty()) null else paces.sum() / paces.size
        val kinds = classify(week, avgPace)
        val speed = kinds.count { it == TrainingGuide.SessionKind.SPEED }
        val lsd = kinds.count { it == TrainingGuide.SessionKind.LSD }
        val share = speed.toDouble() / kinds.size * 100
        return TrainingGuide.Balance(
            easyCount = kinds.size - speed - lsd,
            lsdCount = lsd,
            speedCount = speed,
            speedSharePct = share,
            tone = if (share > 20) RRTone.CAUTION else RRTone.STEADY,
        )
    }

    // MARK: - 공통 (ReportEngine과 같은 창 정의)

    private fun date(daysAgo: Int): Instant = now.minusSeconds(daysAgo * 86_400L)

    /// [now-fromDaysAgo, now-toDaysAgo) 창에 시작된 러닝의 거리 합 (km)
    private fun totalKm(runs: List<RunSummary>, fromDaysAgo: Int, toDaysAgo: Int): Double {
        val from = date(fromDaysAgo)
        val to = date(toDaysAgo)
        return runs.filter { it.start >= from && it.start < to }
            .mapNotNull { it.distanceKm }
            .sum()
    }
}
