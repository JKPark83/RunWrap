package com.jkpark.runwrap.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/// 달리기 좋은 시간대 — 기준 점수를 넘는 칸이 이어진 구간 하나. end는 마지막 칸의 끝 시각(18~20시면 20:00)
data class RunWindow(
    val start: Instant,
    val end: Instant,
    /// 구간에 든 칸 점수(score)의 평균, 반올림
    val avgScore: Int,
    /// 구간 평균 실제 기온 — 화면의 주 숫자 (이슈 #220)
    val temperatureC: Double,
    /// 구간 평균 체감온도 — 주 숫자 옆 보조 표기
    val apparentC: Double,
    /// 구간에서 가장 높은 강수확률 — 비 걱정은 최악 칸 기준으로 말한다
    val precipitationProbabilityPct: Int,
    /// 오늘 남은 구간이 없어 내일에서 고른 구간 — 라벨에 "내일"을 붙인다
    val isTomorrow: Boolean = false,
)

/// 달리기 좋은 시간 추천 엔진 (이슈 #173, #219) — open-meteo 시간대별 예보를 칸마다 점수 매기고
/// 오늘 남은 시간 중 기준 점수 이상인 칸이 이어진 구간을 **모두** 돌려준다 (오늘 구간이 없으면 내일에서).
/// Foundation만 쓰고 `now`를 주입받아 결정론적이다.
///
/// 기온 구간은 **실제 기온** 기준이다 (이슈 #219) — 체감 16~24°C는 산책 날씨라 달리면 덥다.
/// El Helou et al. 2012 (PLoS ONE, 6대 메이저 마라톤 10년치 약 179만 명): 성적이 가장 좋은 구간은
/// 약 5~15°C, 최적점 7~10°C 부근이고 최적보다 더울 때의 손해가 추울 때보다 훨씬 크다(비대칭).
/// 그래서 고온 쪽은 최적 구간을 벗어나 10점까지 5°C(15→20°C)만에 떨어뜨려 추운 쪽(7→0°C, 7°C)보다 가파르게 두고,
/// 고온다습(WeatherAdviceRules.isHumidHeat)을 따로 더 깎는다.
/// 러닝 이름·조언(WeatherAdviceRules)은 추천 기준(70점)을 넘는 4~17°C에서만 긍정 톤을 내
/// 카드끼리 모순된 말을 하지 않게 한다.
/// 감점 폭은 가정 — 사용 피드백으로 조정.
/// 기준 점수를 넘는 칸이 없으면 추천을 내지 않는다(빈 리스트) — "틀린 인사이트는 없느니만 못하다".
object RunWindowEngine {
    /// 한국 달력 — 한국 사용자 전용 앱이라 추천 시간대(05~22시)를 KST로 고정한다
    /// (Android: iOS `Calendar`는 `ZoneId`로 옮긴다 — 그레고리력은 java.time 기본)
    val kst: ZoneId = KST

    /// 추천 기준 점수 — 기온이 만점 구간을 한 단계 벗어난 칸(70)까지, 또는 만점 칸에 가벼운 감점
    /// (바람 5m/s −10, 강수확률 30% −20) 하나까지만 허용하는 선
    const val goodScore = 70

    /// 한 칸 점수 0~100.
    /// 기온 7~15°C 100 · 4~7/15~17°C 70 · 0~4/17~20°C 40 · 그 밖 10,
    /// 고온다습(습도 80%↑·19°C↑) −20, 강수확률 60%↑ −40 / 30%↑ −20, 강수량 > 0mm −20,
    /// 바람 8m/s↑ −20 / 5m/s↑ −10, 비 판정(WeatherAdviceRules.isRaining — 이슬비 코드 포함) −30.
    /// 0 아래로는 내려가지 않는다
    fun score(h: HourlyWeather): Int {
        val t = h.temperatureC
        var score = when {
            t >= 7 && t < 15 -> 100
            (t >= 4 && t < 7) || (t >= 15 && t < 17) -> 70
            (t >= 0 && t < 4) || (t >= 17 && t < 20) -> 40
            else -> 10
        }
        if (WeatherAdviceRules.isHumidHeat(temperatureC = h.temperatureC, humidityPct = h.humidityPct)) {
            score -= 20
        }
        if (h.precipitationProbabilityPct >= 60) {
            score -= 40
        } else if (h.precipitationProbabilityPct >= 30) {
            score -= 20
        }
        if (h.precipitationMm > 0) score -= 20
        // 바람 8m/s는 복장 룰의 바람막이 기준(OutfitRules.windbreakerMs)과 같은 선
        if (h.windMs >= 8) {
            score -= 20
        } else if (h.windMs >= 5) {
            score -= 10
        }
        if (WeatherAdviceRules.isRaining(code = h.weatherCode, precipitationMm = h.precipitationMm)) {
            score -= 30
        }
        return max(0, score)
    }

    /// 오늘 남은 시간의 추천 구간 전부, 없으면(늦은 저녁·종일 비) 내일의 구간 전부. 시각 순.
    /// 예보는 지금부터 24칸뿐이라 내일은 지금 시각 전까지만 본다
    fun windows(hourly: List<HourlyWeather>, now: Instant,
                zone: ZoneId = kst): List<RunWindow> {
        val today = windows(hourly = hourly, now = now, day = now, zone = zone)
        if (today.isNotEmpty()) return today
        val tomorrow = now.atZone(zone).plusDays(1).toInstant()
        return windows(hourly = hourly, now = now, day = tomorrow, zone = zone).map { it.copy(isTomorrow = true) }
    }

    /// 달리기 좋은 칸 — 05~21시 칸(끝 ≤ 22시)이면서 점수가 goodScore 이상.
    /// 시간별 띠의 강조(오늘·내일 가리지 않고 모든 칸)와 추천 구간이 같은 기준을 쓴다 (이슈 #219 §2)
    fun isGood(h: HourlyWeather, zone: ZoneId = kst): Boolean =
        h.time.atZone(zone).hour in 5..21 && score(h) >= goodScore

    /// day 하루의 추천 구간 — 후보 칸은 now 이후 시작·day와 같은 날(KST)·isGood 칸.
    /// 좋은 칸을 한 시간 간격으로 이어 붙여 구간을 만든다.
    /// 값이 빠진 칸(한 시간 넘게 벌어진 칸)에서는 구간을 끊는다
    private fun windows(hourly: List<HourlyWeather>, now: Instant, day: Instant,
                        zone: ZoneId): List<RunWindow> {
        val dayDate = day.atZone(zone).toLocalDate()
        val good = hourly
            .filter { slot ->
                slot.time >= now && slot.time.atZone(zone).toLocalDate() == dayDate &&
                    isGood(slot, zone)
            }
            .sortedBy { it.time }

        val runs = mutableListOf<MutableList<HourlyWeather>>()
        for (slot in good) {
            val last = runs.lastOrNull()?.last()
            if (last != null && Duration.between(last.time, slot.time) == Duration.ofSeconds(3_600)) {
                runs.last().add(slot)
            } else {
                runs.add(mutableListOf(slot))
            }
        }
        return runs.map { run ->
            val count = run.size.toDouble()
            RunWindow(start = run[0].time,
                      end = run[run.size - 1].time.plusSeconds(3_600),
                      avgScore = (run.sumOf(::score).toDouble() / count).swiftRoundedInt(),
                      temperatureC = run.map { it.temperatureC }.fold(0.0) { acc, v -> acc + v } / count,
                      apparentC = run.map { it.apparentC }.fold(0.0) { acc, v -> acc + v } / count,
                      precipitationProbabilityPct = run.maxOfOrNull { it.precipitationProbabilityPct } ?: 0)
        }
    }

    /// "18~20시" — 시작·끝 시각의 시(hour)만
    fun rangeLabel(window: RunWindow, zone: ZoneId = kst): String {
        val start = window.start.atZone(zone).hour
        val end = window.end.atZone(zone).hour
        return "$start~${end}시"
    }

    /// "6~9시 · 18~21시" — 구간을 limit개까지 나열하고 나머지는 "외 N곳"으로 줄인다
    /// (홈 타일처럼 한 줄 폭이 좁은 곳용). 내일 구간이면 앞에 한 번만 "내일 "을 붙인다.
    /// 구간이 없으면 null (미노출)
    fun rangesLabel(windows: List<RunWindow>, limit: Int = Int.MAX_VALUE, zone: ZoneId = kst): String? {
        val first = windows.firstOrNull() ?: return null
        val ranges = windows.take(limit).joinToString(" · ") { rangeLabel(it, zone) }
        val rest = if (windows.size > limit) " 외 ${windows.size - limit}곳" else ""
        return (if (first.isTomorrow) "내일 " else "") + ranges + rest
    }
}
