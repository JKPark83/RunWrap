package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/// 대회 목표 상태 엔진 (이슈 #21) — 목표 레이스·목표 기록·대회 날짜 설정 상태에 따라
/// 체력 배터리 카드 하단에 보여줄 내용을 결정한다. D-day와 열 보정 예상 완주 기록·
/// 평균 페이스가 재료다. 순수 로직: now를 주입받아 결정론적이다.
///
/// 산식:
/// - 완주 예측: TrainingGuideEngine.racePrediction (이슈 #34) — 실제 러닝 세션 기준
///   **구간**으로 낸다. 느린 끝은 Riegel("훈련 페이스 그대로"), 빠른 끝은 대회 노력도(EF)
///   환산("대회 강도로 뛴다면"). 표본 창은 대회 예측 전용(4주 우선 → 12주,
///   테이퍼 중엔 12주만)이고, 오래된 표본은 VO₂max 추세 비율로 현재 체력에 맞춘다.
///   설정에서 직접 입력한 지난 대회 기록도 표본으로 경쟁한다 (이슈 #35).
/// - 열 보정은 **양방향**이다 (이슈 #33): ① 표본은 세션 당시 더위를 제거해 중립 조건으로
///   환산하고(racePrediction 안의 neutralTimeSec), ② 중립 기준 예상 페이스에 대회 월의
///   평년 더위(기상청 서울 1991–2020 평년값 상수표)를 **더한다**. ①이 없으면 한여름 훈련의
///   더위 페널티가 선선한 가을 대회 예측에 그대로 실린다. 직접 입력한 대회 기록은 세션 날씨가
///   없어 기록 월의 평년값(monthlyNormals)으로 같은 환산을 거친다 (이슈 #100).
///
/// 미노출 가드는 nil 대신 Status 케이스로 표현한다 — 화면이 각 상태에 맞는
/// 안내 문구를 보여줘야 하기 때문이다("틀린 인사이트는 없느니만 못하다"는 그대로:
/// 예측은 ready일 때만 낸다).
/// (Android: `Calendar.current` 대신 `zone`을 주입받는다)
object RaceOutlookEngine {
    /// 배터리 카드 하단 섹션의 상태 — 화면은 이 값을 switch로 그리기만 한다
    sealed interface Status {
        /// 레이스·기록·날짜 중 하나라도 미설정 → 설정 안내 문구만
        data object notConfigured : Status
        /// 대회 날짜가 이미 지났다 → 다음 목표 설정 안내
        data class raceFinished(val race: RaceDistance) : Status
        /// 최소 거리·목표별 3배 외삽 가드를 통과한 표본이 없어 예측 불가 → D-day만 보여준다
        data class awaitingRecords(val race: RaceDistance, val daysToRace: Int) : Status
        /// 예측 가능 → D-day + 예상 완주 기록·페이스
        data class ready(val outlook: Outlook) : Status
    }

    data class Outlook(
        val race: RaceDistance,
        val daysToRace: Int,               // 대회 당일 = 0
        /// 구간의 느린 끝 — "훈련 페이스 그대로"의 Riegel 예측 (열 보정 포함)
        val predictedSec: Double,
        val predictedPaceSecPerKm: Double,
        /// 구간의 빠른 끝 — "대회 노력도로 뛴다면"의 EF 환산 (열 보정 포함).
        /// 심박·HRmax가 없으면 nil → 화면은 느린 끝 단일 값만 보여준다 (이슈 #34)
        val predictedFastSec: Double?,
        val heatDeltaSecPerKm: Double,     // 0이면 열 보정 없음 (평년 기준 선선한 달)
        /// 표본 세션의 더위를 제거하며 빠진 보정량(초/km) — 0이면 선선한 날·실내 세션 (이슈 #33)
        val sampleHeatDeltaSecPerKm: Double,
        /// VO₂max 추세 보정 배율 — 1.0이면 보정 없음. 근거 문구용 (이슈 #34)
        val fitnessRatio: Double,
        val raceMonth: Int,                // 1~12 — 열 보정 근거 문구용
        val goalSec: Double,
        val tone: RRTone,                  // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
        /// 표본을 찾은 창(일) — 28·84. 화면이 근거 시점을 밝힌다 (이슈 #24·#34)
        val sampleWindowDays: Int,
        /// 직접 입력한 대회 기록이 근거인지 — true면 sampleWindowDays 대신
        /// "입력한 대회 기록 기준"을 밝힌다 (이슈 #35)
        val isRaceRecord: Boolean,
    )

    /// iOS `(tempC: Double, humidityPct: Double)` 튜플
    data class MonthlyNormal(val tempC: Double, val humidityPct: Double)

    /// 서울 월별 평년값 (기상청 1991–2020, 평균기온 °C · 상대습도 %).
    /// 대회 장소를 모르는 채로 쓰는 근사치라 "대략"이라는 전제를 화면 문구에 남긴다.
    val monthlyNormals: List<MonthlyNormal> = listOf(
        MonthlyNormal(-1.9, 56.0), MonthlyNormal(0.7, 55.0), MonthlyNormal(6.1, 55.0),
        MonthlyNormal(12.6, 55.0), MonthlyNormal(18.2, 60.0), MonthlyNormal(22.7, 66.0),
        MonthlyNormal(25.3, 76.0), MonthlyNormal(26.1, 74.0), MonthlyNormal(21.6, 69.0),
        MonthlyNormal(15.0, 63.0), MonthlyNormal(7.5, 60.0), MonthlyNormal(0.2, 57.0),
    )

    fun status(race: RaceDistance?, goalSec: Double, raceDate: Instant?,
               runs: List<RunSummary>, now: Instant, zone: ZoneId,
               hrMaxBpm: Double? = null,
               vo2MaxSamples: List<TrainingGuideEngine.Vo2MaxSample> = emptyList(),
               raceRecords: List<RaceRecord> = emptyList()): Status {
        if (race == null || !(goalSec > 0) || raceDate == null) return Status.notConfigured
        val days = days(from = now, to = raceDate, zone = zone)
        if (days < 0) return Status.raceFinished(race = race)
        val best = TrainingGuideEngine.racePrediction(
            goal = race, runs = runs, now = now, zone = zone, daysToRace = days,
            hrMaxBpm = hrMaxBpm, vo2MaxSamples = vo2MaxSamples,
            raceRecords = raceRecords)
            ?: return Status.awaitingRecords(race = race, daysToRace = days)
        // riegelSec·effortSec는 이미 열 중립 환산 기준이다 (이슈 #33) — 여기서는 대회일 더위만 더한다
        val neutralPace = best.riegelSec / race.km
        val month = raceDate.atZone(zone).monthValue
        val normal = monthlyNormals[month - 1]
        // 보정량은 중립 기준 목표 페이스로 계산한다 — 훈련 더위가 섞인 페이스를 넣으면
        // 보정량 자체가 잘못된 기준으로 계산된다 (이슈 #33).
        // 열 점수가 낮거나 보정량이 노이즈 바닥(3초/km) 미만이면 nil → 보정 0
        val delta = HeatEngine.adjustment(paceSecPerKm = neutralPace,
                                          tempC = normal.tempC,
                                          humidityPct = normal.humidityPct)?.deltaSecPerKm ?: 0.0
        val pace = neutralPace + delta
        val predicted = pace * race.km
        // 빠른 끝에도 같은 보정량(초/km)을 더한다 — HeatEngine 보정량은 열 점수 기반이라
        // 페이스와 무관하게 동일하다
        val predictedFast = best.effortSec?.let { it + delta * race.km }

        // 톤 — 느린 끝이 목표 안이면 확실한 달성권(improving),
        // 빠른 끝만 목표 안이면 "대회 노력이면 가능"(steady) (이슈 #34)
        val tone = when {
            predicted <= goalSec -> RRTone.improving
            (predictedFast ?: predicted) <= goalSec || predicted <= goalSec * 1.05 -> RRTone.steady
            else -> RRTone.caution
        }
        return Status.ready(Outlook(race = race, daysToRace = days,
                                    predictedSec = predicted, predictedPaceSecPerKm = pace,
                                    predictedFastSec = predictedFast,
                                    heatDeltaSecPerKm = delta,
                                    sampleHeatDeltaSecPerKm = best.sample.heatDeltaSecPerKm,
                                    fitnessRatio = best.fitnessRatio,
                                    raceMonth = month,
                                    goalSec = goalSec, tone = tone,
                                    sampleWindowDays = best.windowDays,
                                    isRaceRecord = best.isRaceRecord))
    }

    /// 날짜 차이(일) — 자정 경계 기준이라 시각과 무관하다 (TrainingGuideEngine.days와 동일 정의)
    /// (Android: iOS는 Int?지만 nil이 나올 경로가 없어 Int로 돌려준다)
    private fun days(from: Instant, to: Instant, zone: ZoneId): Int =
        ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate()).toInt()
}
