package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor

/// 홈 판단 카드 엔진 — "오늘 뛸까 말까, 뛰면 얼마나"를 한 카드로 답한다 (기획서 v0.8 §6).
/// `now`를 주입받아 결정론적이다. UI를 모른다 — 색이 아니라 RRTone까지만 정한다.
/// iOS `TodayVerdictEngine.swift` 이식.
///
/// 재료는 대부분 이미 있는 엔진의 결과를 조립할 뿐이다:
/// 배터리(`BatteryEngine`) · 날씨/복장(`OutfitRules`) · 주간 처방(`TrainingGuideEngine`).
/// 여기서 새로 계산하는 것은 **오늘 권장 거리** 하나뿐이다.
///
/// 미노출 가드는 두 겹이다. 카드 전체는 러닝 기록이 하나도 없으면 내지 않고(null),
/// 줄 단위로는 재료가 없으면 값 대신 유도 문구(`Hint`)를 낸다 —
/// 값을 지어내지 않으면서도 "무엇을 하면 켜지는지"는 알려주기 위해서다.
data class TodayVerdict(
    /// 제목줄 판정 — 배터리가 없으면 판정하지 않는다(null). 화면은 배지를 감춘다
    val tone: RRTone?,
    val headline: String,
    val battery: Line,
    val weather: Line,
    val session: Line,
    val recovery: Line,
) {
    /// 판단 카드의 재료 한 줄 — 값이 있으면 `Value`, 없으면 무엇을 하면 켜지는지 `Hint`
    data class Line(
        val kind: Kind,
        val label: String,
        val content: Content,
        /// 값이 있고 상태 판정이 가능할 때만 — 유도 문구 줄은 항상 null
        val tone: RRTone?,
    ) {
        /// 줄의 종류 — 아이콘·이동 목적지 매핑은 화면 몫이다
        enum class Kind { BATTERY, WEATHER, SESSION, RECOVERY }

        sealed class Content {
            data class Value(val text: String) : Content()
            data class Hint(val text: String) : Content()
        }
    }

    val lines: List<Line> get() = listOf(battery, weather, session, recovery)
}

object TodayVerdictEngine {
    /// 날씨 재료 — 화면이 위치·네트워크 상태를 값으로 접어 넘긴다 (엔진은 네트워크를 모른다)
    sealed class WeatherInput {
        data object Loading : WeatherInput()
        data object Denied : WeatherInput()       // 위치 권한 거부 — 설정으로 보내야 한다
        data object Unavailable : WeatherInput()  // 위치·날씨 조회 실패
        data class Current(val weather: CurrentWeather) : WeatherInput()
    }

    /// - runs: 전체 러닝 이력
    /// - battery: 체력 배터리. 표본이 부족하면 null로 들어온다
    /// - weather: 날씨 재료 (화면이 위치·네트워크 상태를 접어 넘긴다)
    /// - guide: 주간 처방. 목표 레이스 미설정이거나 기록 3주 미만이면 null
    /// - hasRaceGoal: 목표 레이스 설정 여부 — guide가 null인 이유를 갈라 유도 문구를 고른다
    /// - weeklyGoal: 주간 목표 러닝 횟수
    /// - level: 러너 레벨 — 오늘의 훈련(인터벌 스펙 등)을 정할 때 쓴다
    fun verdict(
        runs: List<RunSummary>,
        battery: BatteryReport?,
        weather: WeatherInput,
        guide: TrainingGuide?,
        hasRaceGoal: Boolean,
        weeklyGoal: Int,
        level: RunnerLevel = RunnerLevel.INTERMEDIATE,
        now: Instant,
    ): TodayVerdict? {
        // 기록이 하나도 없으면 네 줄이 전부 유도 문구가 된다 — 환영이 아니라 과제 목록으로
        // 읽히므로 카드 자체를 내지 않는다 (홈은 첫 러닝 안내만 남긴다)
        if (runs.isEmpty()) return null

        val (tone, headline) = verdictHeadline(battery)
        return TodayVerdict(
            tone = tone,
            headline = headline,
            battery = batteryLine(battery),
            weather = weatherLine(weather, now),
            session = sessionLine(runs, battery, guide, hasRaceGoal, weeklyGoal, level, now),
            recovery = recoveryLine(runs, now),
        )
    }

    // MARK: - 제목줄

    /// 판정은 체력 배터리 톤 하나로 한다 — 오늘의 결정을 가르는 건 회복 상태다.
    /// 배터리가 없으면 판정하지 않고 중립 문구만 둔다 ("틀린 인사이트는 없느니만 못하다")
    private fun verdictHeadline(battery: BatteryReport?): Pair<RRTone?, String> {
        if (battery == null) return null to "오늘은 어떻게 가실까요"
        return when (battery.tone) {
            RRTone.OVERLOAD -> RRTone.OVERLOAD to "오늘은 쉬시는 게 이깁니다"
            RRTone.CAUTION -> RRTone.CAUTION to "가볍게만 다녀오세요"
            RRTone.STEADY -> RRTone.STEADY to "평소대로 가셔도 돼요"
            RRTone.IMPROVING -> RRTone.IMPROVING to "몸이 좋습니다, 밀어붙여도 돼요"
        }
    }

    // MARK: - 체력 배터리

    private fun batteryLine(battery: BatteryReport?): TodayVerdict.Line {
        if (battery == null) {
            // 배터리 가드는 활력징후 표본이다 — 며칠이 더 필요한지는 배터리가 null인 시점에
            // 알 수 없으므로(요인별 기준선이 제각각) 숫자 없이 조건만 말한다
            return TodayVerdict.Line(
                kind = TodayVerdict.Line.Kind.BATTERY,
                label = "체력 배터리",
                content = TodayVerdict.Line.Content.Hint("워치를 차고 주무시면 켜져요"),
                tone = null,
            )
        }
        return TodayVerdict.Line(
            kind = TodayVerdict.Line.Kind.BATTERY,
            label = "체력 배터리",
            content = TodayVerdict.Line.Content.Value("${battery.level} · ${battery.statusLabel}"),
            tone = battery.tone,
        )
    }

    // MARK: - 날씨·복장

    private fun weatherLine(weather: WeatherInput, now: Instant): TodayVerdict.Line {
        val label = "날씨"
        fun line(content: TodayVerdict.Line.Content) =
            TodayVerdict.Line(TodayVerdict.Line.Kind.WEATHER, label, content, null)
        return when (weather) {
            WeatherInput.Loading -> line(TodayVerdict.Line.Content.Hint("날씨를 불러오는 중"))
            WeatherInput.Denied -> line(TodayVerdict.Line.Content.Hint("설정에서 위치 허용하기"))
            WeatherInput.Unavailable -> line(TodayVerdict.Line.Content.Hint("날씨를 불러오지 못했어요"))
            is WeatherInput.Current ->
                line(TodayVerdict.Line.Content.Value(weatherPhrase(weather.weather, now)))
        }
    }

    /// 날씨 줄의 조각 — 홈 카드는 아이콘 옆에 조각으로 나눠 쓰고, 한 줄로 합치면 문구가 된다.
    /// 하늘 상태(WMO 코드)는 여기 없다. 복장이 이미 조건을 요약하고,
    /// 판단을 가르는 건 맑음/흐림이 아니라 강수 여부다 (아이콘은 화면 몫)
    data class WeatherParts(
        val temperature: String,  // "체감 22°C"
        val raining: Boolean,
        val outfit: String?,      // "반팔 티+반바지" — 상·하의를 낼 수 없으면 null
    )

    fun weatherParts(current: CurrentWeather, now: Instant): WeatherParts {
        val outfit = OutfitRules.outfit(
            apparentC = current.apparentC,
            humidityPct = current.humidityPct,
            windMs = current.windMs,
            precipitationMm = current.precipitationMm,
            uvIndex = current.uvIndex,
            now = now,
        )
        return WeatherParts(
            temperature = "체감 ${roundAwayFromZero(current.apparentC).toInt()}°C",
            raining = current.precipitationMm > 0,
            outfit = outfitPhrase(outfit),
        )
    }

    /// Swift `.rounded()` 대응 — 반올림을 0에서 먼 쪽으로 (체감온도는 음수가 있을 수 있다)
    private fun roundAwayFromZero(x: Double): Double =
        if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

    /// "체감 22°C · 반팔 티+반바지" — 비가 오면 가운데에 "비"를 끼운다
    private fun weatherPhrase(current: CurrentWeather, now: Instant): String {
        val parts = weatherParts(current, now)
        return listOfNotNull(
            parts.temperature,
            if (parts.raining) "비" else null,
            parts.outfit,
        ).joinToString(" · ")
    }

    /// 상의·하의 한 벌만 뽑아 "반팔+반바지"로 —
    /// 나머지 소품(모자·장갑·선크림)은 오늘 시트에서 본다
    private fun outfitPhrase(items: List<OutfitItem>): String? {
        val tops = listOf(
            OutfitItem.SINGLET, OutfitItem.SHORT_SLEEVE,
            OutfitItem.LONG_SLEEVE, OutfitItem.THERMAL_TOP,
        )
        val bottoms = listOf(OutfitItem.SHORTS, OutfitItem.TIGHTS, OutfitItem.THERMAL_BOTTOM)
        val picked = listOf(tops, bottoms).mapNotNull { group ->
            items.firstOrNull { it in group }?.label
        }
        return if (picked.isEmpty()) null else picked.joinToString("+")
    }

    // MARK: - 오늘 권장 세션

    /// 오늘의 훈련은 `TrainingGuideEngine.todayWorkout`이 정한다 (형태·거리·페이스).
    /// 여기서는 그 결과를 홈 카드 한 줄 문구로 접는다 — "이지런 5.0km · 5′20″" 꼴.
    /// 이지런 이름은 배터리 톤에 따라 가볍게/이지런/빌드업으로 갈라 쓴다 (기존 규칙 유지)
    private fun sessionLine(
        runs: List<RunSummary>,
        battery: BatteryReport?,
        guide: TrainingGuide?,
        hasRaceGoal: Boolean,
        weeklyGoal: Int,
        level: RunnerLevel,
        now: Instant,
    ): TodayVerdict.Line {
        val label = "오늘 권장"
        fun line(content: TodayVerdict.Line.Content, tone: RRTone?) =
            TodayVerdict.Line(TodayVerdict.Line.Kind.SESSION, label, content, tone)

        if (guide == null) {
            // 처방이 없는 이유가 둘이라 유도 문구를 가른다 (목표 미설정 / 표본 부족)
            return line(
                TodayVerdict.Line.Content.Hint(
                    if (hasRaceGoal) "3주치 기록이 쌓이면 알려드려요" else "목표 대회를 정해 보세요"
                ),
                null,
            )
        }

        val today = TrainingGuideEngine(now, level)
            .todayWorkout(runs, guide, battery?.tone, weeklyGoal)
        return when (today.kind) {
            // 방전 임박에는 거리를 내지 않는다 — 오늘의 처방은 쉬는 것이다
            TodayWorkout.Kind.Rest ->
                line(TodayVerdict.Line.Content.Value("오늘은 휴식"), RRTone.OVERLOAD)
            TodayWorkout.Kind.DoneCount ->
                line(TodayVerdict.Line.Content.Value("이번 주 횟수를 다 채우셨어요"), RRTone.IMPROVING)
            TodayWorkout.Kind.DoneKm ->
                line(TodayVerdict.Line.Content.Value("이번 주 목표를 채우셨어요"), RRTone.IMPROVING)
            else ->
                line(
                    TodayVerdict.Line.Content.Value(sessionPhrase(today, battery?.tone)),
                    battery?.tone ?: RRTone.STEADY,
                )
        }
    }

    /// "이지런 5.0km · 5′20″" — 인터벌은 스펙이 이미 이름에 있어 거리를 겹쳐 쓰지 않고,
    /// 페이스는 존 구간의 중앙값 하나만 낸다 (구간 전체는 리포트 탭 카드에서 본다)
    private fun sessionPhrase(today: TodayWorkout, batteryTone: RRTone?): String {
        val name = if (today.kind == TodayWorkout.Kind.Easy) {
            intensityLabel(batteryTone)
        } else {
            today.kind.label
        }
        val km = when (today.kind) {
            is TodayWorkout.Kind.Interval -> null
            else -> today.distanceKm?.let { "${Format.km(it)}km" }
        }
        val pace = today.paceSecPerKm?.let {
            Format.pace((it.start + it.endInclusive) / 2)
        }
        return listOfNotNull(name + (km?.let { " $it" } ?: ""), pace).joinToString(" · ")
    }

    /// 강도 이름 — 배터리 톤 매핑. overload는 위에서 이미 갈라져 여기 오지 않는다
    private fun intensityLabel(tone: RRTone?): String = when (tone) {
        RRTone.CAUTION -> "가볍게"
        RRTone.IMPROVING -> "빌드업"
        else -> "이지런"      // steady · 배터리 없음
    }

    // MARK: - 회복 경과

    private fun recoveryLine(runs: List<RunSummary>, now: Instant): TodayVerdict.Line {
        // 호출부가 기록 유무를 이미 확인했다 — 여기 오면 마지막 러닝은 반드시 있다
        val last = runs.maxOfOrNull { it.start } ?: now
        val days = ChronoUnit.DAYS.between(dayOf(last), dayOf(now)).toInt()
        val text = when {
            days < 1 -> "오늘 다녀오셨어요"
            days == 1 -> "어제"
            else -> "${days}일 전"
        }
        return TodayVerdict.Line(
            kind = TodayVerdict.Line.Kind.RECOVERY,
            label = "마지막 러닝",
            content = TodayVerdict.Line.Content.Value(text),
            tone = null,
        )
    }
}
