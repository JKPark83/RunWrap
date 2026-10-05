package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId

/// 기간별 심박존 분포 + 80/20 강도 배분 (이슈 #165) — 최근 28일 러닝의 존별 누적 시간.
///
/// 근거: Seiler & Kjerland(2006) — 엘리트 지구력 선수는 훈련 시간의 약 80%를 LT1(1차 젖산 역치)
/// 아래에서 보낸다. 기획서 §4.9의 "장거리/스피드 밸런스 — 80/20 원칙과 비교"를 세션 유형 추정이
/// 아니라 실제 심박 시간으로 본다. Z1+Z2(강도 비율 0.7 미만)를 이지 강도로 친다.
data class ZoneDistribution(
    /// 최근 4개 달력 주, 오래된 → 최신 (세션이 없는 주는 0으로 채운다)
    val weeks: List<WeekBar>,
    /// 28일 누적 Z1~Z5 비율 (합 1)
    val zoneShare: List<Double>,
    /// Z1+Z2 비율 — 80/20 판정 기준
    val easyShare: Double,
    /// 히스토그램이 있는 28일 창 세션 수
    val sessionCount: Int,
    val tone: RRTone,
) {
    data class WeekBar(
        /// 달력 주(ISO 8601, 월요일 시작) 시작일
        val weekStart: Instant,
        /// "8월 2째주" — Format.weekLabel
        val label: String,
        /// Z1~Z5 누적 초
        val zoneSeconds: List<Double>,
    )
}

object ZoneDistributionEngine {
    /// 미노출 가드 — 28일 창에 심박 기록이 있는 세션이 이보다 적으면 카드를 내지 않는다.
    /// 주 2회 × 4주: 몇 번의 세션으로 강도 배분을 단정하면 "틀린 인사이트"가 된다
    const val minSessions = 8
    /// 이지 비율 판정 경계 — 0.80 이상 유지(Seiler 80/20), 0.70 이상 주의, 그 밑은 과부하
    const val steadyEasyShare = 0.80
    const val cautionEasyShare = 0.70

    /// Swift 튜플 `(share:, tone:, sessions:)` 대응
    data class EasyShare(val share: Double, val tone: RRTone, val sessions: Int)

    /// 존 경계는 표시 시점의 `profile`로 적용한다 — 히스토그램은 bpm 단위라 심박 기준을 바꿔도 다시 계산된다.
    /// 존 매핑은 세션 상세(TrainingGuideEngine.heartRateZones)와 같은 `zoneIndex(intensity:)`.
    /// 창은 28일 전 자정 ~ now. 주 막대는 최근 4개 달력 주라 4주 전 주의 일부(최대 6일)는
    /// 누적 비율에만 들어가고 막대에는 없다.
    fun compute(histograms: Map<String, ZoneHistogram>,
                runs: List<RunSummary>,
                profile: HeartRateProfile,
                now: Instant,
                zone: ZoneId): ZoneDistribution? {
        val windowStart = now.minusSeconds(28L * 86_400).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        // 빈 히스토그램(심박 샘플 없는 세션)은 표본으로 세지 않는다
        val sessions = runs
            .filter { it.start >= windowStart && it.start <= now }
            .mapNotNull { run ->
                val histogram = histograms[run.id]
                if (histogram == null || histogram.secondsByBpm.isEmpty()) return@mapNotNull null
                run.start to zoneSeconds(histogram, profile)
            }
        if (sessions.size < minSessions) return null

        val totals = sum(sessions.map { it.second })
        val total = totals.sum()
        if (!(total > 0)) return null
        val easyShare = (totals[0] + totals[1]) / total

        // 주 경계는 Format.weekLabel과 같은 ISO 8601 달력 주 (ReportEngine.weekBars와 같은 방식)
        val currentWeekStart = isoWeekStart(now, zone)
        val weeks = (0 until 4).reversed().map { back ->
            val startDay = currentWeekStart.minusWeeks(back.toLong())
            val start = startDay.atStartOfDay(zone).toInstant()
            val end = startDay.plusWeeks(1).atStartOfDay(zone).toInstant()
            val inWeek = sessions.filter { it.first >= start && it.first < end }
            ZoneDistribution.WeekBar(weekStart = start, label = Format.weekLabel(start, zone),
                                     zoneSeconds = sum(inWeek.map { it.second }))
        }

        return ZoneDistribution(weeks = weeks,
                                zoneShare = totals.map { it / total },
                                easyShare = easyShare,
                                sessionCount = sessions.size,
                                tone = tone(easyShare))
    }

    /// 임의 구간 [start, end)의 이지 비율 — 월간 결산(이슈 #167)의 강도 배분 카드용.
    /// 존 매핑·표본 가드(8회)·톤 경계는 `compute`와 같다. 히스토그램 캐시가 28일 창이라
    /// 오래된 달은 표본이 모자라 nil이 되는 게 정상이다
    /// (Android: DateInterval 대신 start·end를 따로 받는다 — 끝은 포함하지 않는다)
    fun easyShare(histograms: Map<String, ZoneHistogram>,
                  runs: List<RunSummary>,
                  profile: HeartRateProfile,
                  start: Instant,
                  end: Instant): EasyShare? {
        val zones = runs
            .filter { it.start >= start && it.start < end }
            .mapNotNull { run ->
                val histogram = histograms[run.id]
                if (histogram == null || histogram.secondsByBpm.isEmpty()) return@mapNotNull null
                zoneSeconds(histogram, profile)
            }
        if (zones.size < minSessions) return null
        val totals = sum(zones)
        val total = totals.sum()
        if (!(total > 0)) return null
        val share = (totals[0] + totals[1]) / total
        return EasyShare(share, tone(share), zones.size)
    }

    /// 이지 비율 → 톤 (0.80 유지 · 0.70 주의 · 그 밑 과부하)
    private fun tone(easyShare: Double): RRTone =
        if (easyShare >= steadyEasyShare) RRTone.steady
        else if (easyShare >= cautionEasyShare) RRTone.caution
        else RRTone.overload

    /// 히스토그램 → Z1~Z5 초
    private fun zoneSeconds(histogram: ZoneHistogram, profile: HeartRateProfile): List<Double> {
        val seconds = DoubleArray(5)
        for ((bpm, sec) in histogram.secondsByBpm) {
            seconds[TrainingGuideEngine.zoneIndex(profile.intensity(bpm.toDouble()))] += sec
        }
        return seconds.toList()
    }

    /// 세션별 Z1~Z5 초를 존별로 합산 — 비어 있으면 0 다섯 개
    private fun sum(zones: List<List<Double>>): List<Double> {
        val acc = DoubleArray(5)
        for (session in zones) {
            for (zone in 0 until 5) acc[zone] += session[zone]
        }
        return acc.toList()
    }
}
