package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/// 훈련 부하 추세 — TRIMP 기반 체력(CTL)·피로(ATL)·폼(TSB) (이슈 #177, 기획서 §4 훈련 부하).
///
/// **왜 ACWR과 따로 두는가.** ACWR(ReportEngine.acwrLoad)은 거리만 보므로 같은 10km라도
/// 조깅과 템포런을 같은 부하로 친다. 여기서는 세션마다 심박 강도 × 시간(TRIMP, Banister 1991)을
/// 훈련 자극으로 삼고, 매일 누적한 값을 두 시상수로 가중 평균한다 —
/// 42일 = 체력(CTL), 7일 = 피로(ATL), 폼(TSB) = 체력 − 피로 (Coggan PMC 42/7 관례).
///
/// 미노출 가드: 지수 가중 평균은 초기값 0에서 출발하므로 이력이 42일보다 짧으면 CTL이 실제보다
/// 낮게 나온다(acwrLoad의 28일 가드와 같은 논리). 최근 42일 심박 세션이 8회 미만이어도 내지 않는다.
data class TrainingLoad(
    /// 최근 28일 + 오늘 = 29개, 오래된 → 최신, day는 자정
    val points: List<Point>,
    /// 오늘 체력
    val ctl: Double,
    /// 오늘 피로
    val atl: Double,
    /// 전날 CTL − 전날 ATL (Coggan 관례: 오늘의 폼은 어제까지의 잔고)
    val tsb: Double,
    val band: Band,
    val tone: RRTone,
    /// 최근 42일 TRIMP 산출 세션 수
    val sessionCount: Int,
) {
    data class Point(
        /// 그날 자정 (calendar.startOfDay)
        val day: Instant,
        val ctl: Double,
        val atl: Double,
        /// 그날 기준 전날 잔고 — 전날 CTL − 전날 ATL
        val tsb: Double,
    )

    /// 폼 구간 — 경계는 TrainingLoadEngine의 상수 (Coggan/TrainingPeaks 관례)
    enum class Band {
        overload, productive, maintain, fresh, detraining
    }
}

object TrainingLoadEngine {
    /// 미노출 가드 — 최근 42일 창에 TRIMP 세션이 이보다 적으면 카드를 내지 않는다 (주 1~2회 × 6주)
    const val minSessions = 8
    /// 체력(CTL)·피로(ATL) 시상수(일) — Coggan PMC 42/7
    const val ctlDays = 42.0
    const val atlDays = 7.0
    /// 추세 차트 길이 — 오늘 이전 28일
    const val trendDays = 28
    /// 안정 심박 폴백 — 안정 심박이 없으면 성인 평균 60bpm으로 HRr을 만든다
    const val fallbackRestingHR = 60.0

    /// TSB 구간 경계 (Coggan/TrainingPeaks 관례):
    /// −30 미만 과부하 · −30~−10 미만 체력 쌓는 중 · −10~+5 유지 · +5 초과~+25 가벼움 · +25 초과 훈련 부족
    const val overloadTSB = -30.0
    const val productiveTSB = -10.0
    const val freshTSB = 5.0
    const val detrainingTSB = 25.0

    /// 세션 TRIMP (Banister 1991) — 분 × HRr × 0.64 × e^(1.92 × HRr).
    /// HRr = (평균 심박 − 안정) / (HRmax − 안정), 0…1로 자른다(안정 심박 이하면 0 → TRIMP 0).
    /// 성별 상수는 남성(0.64·1.92) 하나만 쓴다 — 성별을 묻지 않는 프라이버시 원칙.
    /// HRmax가 190 폴백이면 근거가 없어 nil (대회 노력도와 같은 `reliableHrMax`)
    fun trimp(run: RunSummary, profile: HeartRateProfile): Double? {
        val avg = run.avgHeartRate ?: return null
        if (!(run.durationSec > 0)) return null
        val hrMax = profile.reliableHrMax ?: return null
        val rest = profile.restingHR ?: fallbackRestingHR
        val hrr = min(max((avg - rest) / (hrMax - rest), 0.0), 1.0)
        return run.durationSec / 60 * hrr * 0.64 * exp(1.92 * hrr)
    }

    /// 체력·피로·폼 — 세션 TRIMP를 달력 일 단위로 합산해 첫 세션 날부터 오늘까지 하루씩
    /// 지수 가중 평균한다: `ctl += (t − ctl) / 42`, `atl += (t − atl) / 7` (세션 없는 날 t = 0, 초기값 0).
    /// 가드: TRIMP 세션 최고령이 42일 이상 전 AND 최근 42일 창 [now−42일, now)에 8회 이상.
    /// (Android: iOS `calendar: Calendar = .current` 대신 `zone`을 받는다)
    fun compute(runs: List<RunSummary>,
                profile: HeartRateProfile,
                now: Instant,
                zone: ZoneId): TrainingLoad? {
        val sessions = runs
            .filter { it.start <= now }
            .mapNotNull { run ->
                trimp(run = run, profile = profile)?.let { run.start to it }
            }
        val windowStart = now.minusSeconds((ctlDays * 86_400).toLong())
        val oldest = sessions.minOfOrNull { it.first } ?: return null
        if (!(oldest <= windowStart)) return null
        val sessionCount = sessions.count { it.first >= windowStart && it.first < now }
        if (sessionCount < minSessions) return null

        val daily = mutableMapOf<LocalDate, Double>()
        for ((start, trimp) in sessions) {
            val key = start.atZone(zone).toLocalDate()
            daily[key] = (daily[key] ?: 0.0) + trimp
        }

        // 하루씩 EWMA — 최고령이 42일 이상 전이라 상태는 항상 43일 이상 쌓인다
        val today = now.atZone(zone).toLocalDate()
        data class State(val day: LocalDate, val ctl: Double, val atl: Double)
        val states = mutableListOf<State>()
        var ctl = 0.0
        var atl = 0.0
        var day = oldest.atZone(zone).toLocalDate()
        while (day <= today) {
            val t = daily[day] ?: 0.0
            ctl += (t - ctl) / ctlDays
            atl += (t - atl) / atlDays
            states.add(State(day, ctl, atl))
            day = day.plusDays(1)
        }

        // 각 점의 TSB는 전날 잔고 (Coggan 관례)
        val points = (max(0, states.size - (trendDays + 1)) until states.size).map { i ->
            TrainingLoad.Point(day = states[i].day.atStartOfDay(zone).toInstant(),
                               ctl = states[i].ctl, atl = states[i].atl,
                               tsb = states[i - 1].ctl - states[i - 1].atl)
        }
        val latest = points.lastOrNull() ?: return null
        val band = band(tsb = latest.tsb)
        return TrainingLoad(points = points, ctl = latest.ctl, atl = latest.atl, tsb = latest.tsb,
                            band = band, tone = tone(band), sessionCount = sessionCount)
    }

    /// TSB → 폼 구간 (경계 상수 참고)
    fun band(tsb: Double): TrainingLoad.Band {
        if (tsb < overloadTSB) return TrainingLoad.Band.overload
        if (tsb < productiveTSB) return TrainingLoad.Band.productive
        if (tsb <= freshTSB) return TrainingLoad.Band.maintain
        if (tsb <= detrainingTSB) return TrainingLoad.Band.fresh
        return TrainingLoad.Band.detraining
    }

    /// 폼 구간 → 톤 — 체력 쌓는 피로는 좋아지는 중, 가벼움은 유지, 훈련 부족은 주의
    fun tone(band: TrainingLoad.Band): RRTone =
        when (band) {
            TrainingLoad.Band.overload -> RRTone.overload
            TrainingLoad.Band.productive -> RRTone.improving
            TrainingLoad.Band.maintain, TrainingLoad.Band.fresh -> RRTone.steady
            TrainingLoad.Band.detraining -> RRTone.caution
        }
}
