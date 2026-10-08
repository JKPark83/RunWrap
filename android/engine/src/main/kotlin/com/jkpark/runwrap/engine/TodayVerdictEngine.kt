package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/// 홈 판단 카드 엔진 — "오늘 뛸까 말까, 뛰면 얼마나"를 한 카드로 답한다 (기획서 v0.8 §6).
/// Foundation만 쓰고 `now`를 주입받아 결정론적이다. UI를 모른다 — 색이 아니라 RRTone까지만 정한다.
///
/// 재료는 대부분 이미 있는 엔진의 결과를 조립할 뿐이다:
/// 배터리(`BatteryEngine`) · 날씨/복장(`WeatherClient`·`OutfitRules`) · 주간 처방(`TrainingGuideEngine`).
/// 여기서 새로 계산하는 것은 **오늘 권장 거리** 하나뿐이다.
///
/// 대기질(`AirQualityEngine`의 대표 등급)은 판정을 새로 만들지 않고 **상한**으로만 건다 (이슈 #183) —
/// 공기가 나쁜 날 배터리가 좋다고 "밀어붙이라"고 하면 틀린 인사이트가 된다.
/// 등급 문구는 에어코리아 공식 표현 그대로 쓴다 (KOGL 변경금지 — `AirGrade.label`).
///
/// 미노출 가드는 두 겹이다. 카드 전체는 러닝 기록이 하나도 없으면 내지 않고(nil),
/// 줄 단위로는 재료가 없으면 값 대신 유도 문구(`hint`)를 낸다 —
/// 값을 지어내지 않으면서도 "무엇을 하면 켜지는지"는 알려주기 위해서다.

data class TodayVerdict(
    /// 제목줄 판정 — 배터리가 없으면 판정하지 않는다(nil). 화면은 배지를 감춘다
    val tone: RRTone?,
    /// 배지 라벨 덮어쓰기 — 대기질이 판정을 정했을 때만 값이 있다 (이슈 #195).
    /// nil이면 화면은 톤 기본 라벨을 쓴다
    val badgeLabel: String?,
    val headline: String,
    val battery: Line,
    val weather: Line,
    val session: Line,
    val recovery: Line,
) {
    /// 판단 카드의 재료 한 줄 — 값이 있으면 `value`, 없으면 무엇을 하면 켜지는지 `hint`
    data class Line(
        val kind: Kind,
        val label: String,
        val content: Content,
        /// 값이 있고 상태 판정이 가능할 때만 — 유도 문구 줄은 항상 nil
        val tone: RRTone?,
        /// 값 아래 덧붙이는 짧은 한 줄 — 날씨 줄의 "6~9시 · 18~21시가 좋아요" (이슈 #173). 없으면 nil
        val caption: String? = null,
    ) {
        /// 줄의 종류 — 아이콘·이동 목적지 매핑은 화면 몫이다
        enum class Kind { battery, weather, session, recovery }

        /// (Android: Swift 연관값 enum → sealed interface)
        sealed interface Content {
            data class value(val text: String) : Content
            data class hint(val text: String) : Content
        }
    }

    val lines: List<Line> get() = listOf(battery, weather, session, recovery)
}

object TodayVerdictEngine {
    /// 날씨 재료 — 화면이 위치·네트워크 상태를 값으로 접어 넘긴다 (엔진은 네트워크를 모른다)
    /// (Android: Swift 연관값 enum → sealed interface)
    sealed interface WeatherInput {
        data object loading : WeatherInput
        data object denied : WeatherInput         // 위치 권한 거부 — 앱 안에서 다시 물을 수 없어 설정으로 보내야 한다
        data object unavailable : WeatherInput    // 위치·날씨 조회 실패
        /// windows: 오늘 달리기 좋은 시간 구간 전부(RunWindowEngine) — 추천이 없으면 빈 리스트 (이슈 #173, #219)
        data class current(val weather: CurrentWeather, val windows: List<RunWindow> = emptyList()) : WeatherInput
    }

    /// - Parameters:
    ///   - runs: 전체 러닝 이력
    ///   - battery: 체력 배터리. 표본이 부족하면 nil로 들어온다
    ///   - weather: 날씨 재료 (화면이 위치·네트워크 상태를 접어 넘긴다)
    ///   - guide: 주간 처방. 목표 레이스 미설정이거나 기록 3주 미만이면 nil
    ///   - hasRaceGoal: 목표 레이스 설정 여부 — guide가 nil인 이유를 갈라 유도 문구를 고른다
    ///   - weeklyGoal: 주간 목표 러닝 횟수
    ///   - level: 러너 레벨 — 오늘의 훈련(인터벌 스펙 등)을 정할 때 쓴다
    ///   - air: 현재 위치 대기질 대표 등급 (이슈 #183). 조회 전·실패면 nil — 판정에 관여하지 않는다
    ///   - zone: (Android) iOS `Calendar(.iso8601)` + `.current` 대신 주입받는 시간대
    fun verdict(runs: List<RunSummary>,
                battery: BatteryReport?,
                weather: WeatherInput,
                guide: TrainingGuide?,
                hasRaceGoal: Boolean,
                weeklyGoal: Int,
                level: RunnerLevel = RunnerLevel.intermediate,
                air: AirGrade? = null,
                now: Instant,
                zone: ZoneId): TodayVerdict? {
        // 기록이 하나도 없으면 네 줄이 전부 유도 문구가 된다 — 환영이 아니라 과제 목록으로
        // 읽히므로 카드 자체를 내지 않는다 (홈은 첫 러닝 안내만 남긴다)
        if (runs.isEmpty()) return null

        val (tone, headline) = headline(battery = battery, air = air)
        return TodayVerdict(tone = tone,
                            badgeLabel = badgeLabel(battery = battery, air = air),
                            headline = headline,
                            battery = batteryLine(battery),
                            weather = weatherLine(weather, air = air, now = now, zone = zone),
                            session = sessionLine(runs = runs, battery = battery, guide = guide,
                                                  hasRaceGoal = hasRaceGoal,
                                                  weeklyGoal = weeklyGoal, level = level,
                                                  air = air, now = now, zone = zone),
                            recovery = recoveryLine(runs = runs, now = now, zone = zone))
    }

    // MARK: - 제목줄

    /// 판정은 체력 배터리 톤 하나로 한다 — 오늘의 결정을 가르는 건 회복 상태다.
    /// 배터리가 없으면 판정하지 않고 중립 문구만 둔다 ("틀린 인사이트는 없느니만 못하다").
    /// 위젯 스냅샷도 같은 판정을 쓴다 — 홈 카드와 위젯 문구가 어긋나지 않게 (이슈 #181)
    fun headline(battery: BatteryReport?): Pair<RRTone?, String> =
        headline(battery = battery, air = null)

    /// 배터리 판정에 대기질 상한을 건다 (이슈 #183) — 공기는 회복 상태와 무관하게 바깥 달리기를 막는
    /// 조건이라, 배터리가 좋아도 판정을 끌어내리되 배터리가 이미 더 보수적이면 건드리지 않는다.
    /// - 매우나쁨: 배터리와 무관하게 overload — 실외 활동 자제 등급이다
    /// - 나쁨: 배터리 판정이 없거나 steady·improving이면 caution으로 낮춘다.
    ///   overload·caution이면 그쪽이 더 보수적이므로 배터리 판정 그대로
    /// - 좋음·보통·nil(조회 전·실패): 배터리 판정 그대로
    fun headline(battery: BatteryReport?, air: AirGrade?): Pair<RRTone?, String> {
        when (air) {
            AirGrade.veryBad ->
                return RRTone.overload to "오늘은 실내가 이깁니다"
            AirGrade.bad -> when (battery?.tone) {
                RRTone.overload, RRTone.caution -> {}
                RRTone.steady, RRTone.improving, null ->
                    return RRTone.caution to "공기가 나빠요, 가볍게만 다녀오세요"
            }
            AirGrade.good, AirGrade.moderate, null -> {}
        }
        if (battery == null) return null to "오늘은 어떻게 가실까요"
        return when (battery.tone) {
            RRTone.overload -> RRTone.overload to "오늘은 쉬시는 게 이깁니다"
            RRTone.caution -> RRTone.caution to "가볍게만 다녀오세요"
            RRTone.steady -> RRTone.steady to "평소대로 가셔도 돼요"
            RRTone.improving -> RRTone.improving to "몸이 좋습니다, 밀어붙여도 돼요"
        }
    }

    /// 배지 라벨 (이슈 #195) — 대기질 상한이 판정을 정했을 때만 이유를 배지에 적는다.
    /// 톤 기본 라벨("주의"·"과부하")만 보이면 배터리가 나쁜 것으로 읽히기 때문이다.
    /// 조건은 `headline(battery:air:)`의 상한 분기와 같다 — 배터리가 이미 더 보수적이면 배터리 판정이라 nil.
    /// 헤드라인 튜플은 위젯 팩토리가 그대로 쓰므로 건드리지 않고 따로 낸다
    fun badgeLabel(battery: BatteryReport?, air: AirGrade?): String? =
        when (air) {
            AirGrade.veryBad -> "실외 자제"
            AirGrade.bad -> when (battery?.tone) {
                RRTone.overload, RRTone.caution -> null
                RRTone.steady, RRTone.improving, null -> "공기 나쁨"
            }
            AirGrade.good, AirGrade.moderate, null -> null
        }

    // MARK: - 체력 배터리

    private fun batteryLine(battery: BatteryReport?): TodayVerdict.Line {
        if (battery == null) {
            // 배터리 가드는 활력징후 표본이다 — 며칠이 더 필요한지는 배터리가 nil인 시점에
            // 알 수 없으므로(요인별 기준선이 제각각) 숫자 없이 조건만 말한다
            return TodayVerdict.Line(kind = TodayVerdict.Line.Kind.battery, label = "체력 배터리",
                                     content = TodayVerdict.Line.Content.hint("워치를 차고 주무시면 켜져요"),
                                     tone = null)
        }
        return TodayVerdict.Line(kind = TodayVerdict.Line.Kind.battery, label = "체력 배터리",
                                 content = TodayVerdict.Line.Content.value("${battery.level} · ${battery.statusLabel}"),
                                 tone = battery.tone)
    }

    // MARK: - 날씨·복장

    private fun weatherLine(weather: WeatherInput, air: AirGrade?,
                            now: Instant, zone: ZoneId): TodayVerdict.Line {
        val label = "날씨"
        return when (weather) {
            WeatherInput.loading ->
                TodayVerdict.Line(kind = TodayVerdict.Line.Kind.weather, label = label,
                                  content = TodayVerdict.Line.Content.hint("날씨를 불러오는 중"), tone = null)
            WeatherInput.denied ->
                TodayVerdict.Line(kind = TodayVerdict.Line.Kind.weather, label = label,
                                  content = TodayVerdict.Line.Content.hint("설정에서 위치 허용하기"), tone = null)
            WeatherInput.unavailable ->
                TodayVerdict.Line(kind = TodayVerdict.Line.Kind.weather, label = label,
                                  content = TodayVerdict.Line.Content.hint("날씨를 불러오지 못했어요"), tone = null)
            is WeatherInput.current -> {
                // 추천 시간은 판정문에 이어 붙이지 않고 캡션으로 — 홈 타일은 문구 대신 그림으로 값을 그려서
                // 문구 끝에 붙이면 보이지 않는다.
                // 공기가 나쁨 이상이면 날씨 문구 뒤에 공식 등급을 덧붙인다 (이슈 #183) — 헤드라인을 끌어내린 이유가
                // 한 줄 문구에서도 읽히게 (홈 타일은 대기질 배지를 따로 그린다). 유도 문구 줄에는 붙이지 않는다
                val airSuffix = air?.let { if (it >= AirGrade.bad) " · 대기질 ${it.label}" else null } ?: ""
                TodayVerdict.Line(kind = TodayVerdict.Line.Kind.weather, label = label,
                                  content = TodayVerdict.Line.Content.value(
                                      weatherPhrase(weather.weather, now = now, zone = zone) + airSuffix),
                                  tone = null,
                                  caption = RunWindowEngine.rangesLabel(weather.windows)?.let { "${it}가 좋아요" })
            }
        }
    }

    /// 날씨 줄의 조각 — 홈 카드는 아이콘 옆에 조각으로 나눠 쓰고, 한 줄로 합치면 문구가 된다.
    /// 하늘 상태(WMO 코드)는 여기 없다. 복장이 이미 조건을 요약하고,
    /// 판단을 가르는 건 맑음/흐림이 아니라 강수 여부다 (아이콘은 화면 몫).
    data class WeatherParts(
        val temperature: String,     // "22°C 체감 21°" — 실제 기온이 주 숫자 (이슈 #220)
        val raining: Boolean,
        val outfit: String?,         // "반팔 티+반바지" — 상·하의를 낼 수 없으면 nil
    )

    fun weatherParts(current: CurrentWeather, now: Instant, zone: ZoneId): WeatherParts {
        val outfit = OutfitRules.outfit(temperatureC = current.temperatureC,
                                        humidityPct = current.humidityPct,
                                        windMs = current.windMs,
                                        precipitationMm = current.precipitationMm,
                                        weatherCode = current.weatherCode,
                                        uvIndex = current.uvIndex,
                                        now = now, zone = zone)
        return WeatherParts(temperature = "${current.temperatureC.swiftRoundedInt()}°C 체감 ${current.apparentC.swiftRoundedInt()}°",
                            raining = WeatherAdviceRules.isRaining(code = current.weatherCode,
                                                                   precipitationMm = current.precipitationMm),
                            outfit = outfitPhrase(outfit))
    }

    /// "22°C 체감 21° · 반팔 티+반바지" — 비가 오면 가운데에 "비"를 끼운다
    private fun weatherPhrase(current: CurrentWeather, now: Instant, zone: ZoneId): String {
        val parts = weatherParts(current, now = now, zone = zone)
        return listOfNotNull(parts.temperature, if (parts.raining) "비" else null, parts.outfit)
            .joinToString(" · ")
    }

    /// 상의·하의 한 벌만 뽑아 "반팔+반바지"로 —
    /// 나머지 소품(모자·장갑·선크림)은 오늘 시트에서 본다
    private fun outfitPhrase(items: List<OutfitItem>): String? {
        val tops = listOf(OutfitItem.singlet, OutfitItem.shortSleeve, OutfitItem.longSleeve, OutfitItem.thermalTop)
        val bottoms = listOf(OutfitItem.shorts, OutfitItem.tights, OutfitItem.thermalBottom)
        val picked = listOf(tops, bottoms).mapNotNull { group ->
            items.firstOrNull { it in group }?.label
        }
        return if (picked.isEmpty()) null else picked.joinToString("+")
    }

    // MARK: - 오늘 권장 세션

    /// 오늘의 훈련은 `TrainingGuideEngine.todayWorkout`이 정한다 (형태·거리·페이스).
    /// 여기서는 그 결과를 홈 카드 한 줄 문구로 접는다 — "이지런 5.0km · 5′20″" 꼴.
    /// 이지런 이름은 배터리 톤에 따라 가볍게/이지런/빌드업으로 갈라 쓴다 (기존 규칙 유지).
    ///
    /// 대기질은 헤드라인(#183)과 같은 상한을 이 줄에도 건다 (이슈 #195) — 제목이 "가볍게만"이라면서
    /// 권장 세션이 인터벌이면 한 카드 안에서 말이 어긋난다. 공기는 회복 상태와 무관하게 실외 강도를 막는다.
    /// - 매우나쁨: 처방·유도 문구와 무관하게 "오늘은 실내에서"(overload)
    /// - 나쁨: 배터리 톤이 steady·improving·nil이면 caution으로 낮춰 처방한다 (이지런·"가볍게").
    ///   overload·caution이면 그쪽이 더 보수적이므로 그대로
    /// - 좋음·보통·nil: 변화 없음
    private fun sessionLine(runs: List<RunSummary>, battery: BatteryReport?,
                            guide: TrainingGuide?, hasRaceGoal: Boolean,
                            weeklyGoal: Int, level: RunnerLevel,
                            air: AirGrade?, now: Instant, zone: ZoneId): TodayVerdict.Line {
        val label = "오늘 권장"
        fun line(content: TodayVerdict.Line.Content, tone: RRTone?): TodayVerdict.Line =
            TodayVerdict.Line(kind = TodayVerdict.Line.Kind.session, label = label, content = content, tone = tone)

        // 실외 활동 자제 등급 — 처방이 없어 유도 문구를 낼 자리여도 이 줄이 이긴다
        if (air == AirGrade.veryBad) {
            return line(TodayVerdict.Line.Content.value("오늘은 실내에서"), RRTone.overload)
        }

        if (guide == null) {
            // 처방이 없는 이유가 둘이라 유도 문구를 가른다 (목표 미설정 / 표본 부족)
            return line(TodayVerdict.Line.Content.hint(if (hasRaceGoal) "3주치 기록이 쌓이면 알려드려요"
                                                       else "목표 대회를 정해 보세요"), null)
        }

        // 처방·문구가 함께 쓰는 유효 톤 — 나쁨이면 배터리가 좋아도 caution으로 상한을 건다
        val batteryTone = battery?.tone
        val effectiveTone: RRTone? =
            if (air == AirGrade.bad &&
                (batteryTone == RRTone.steady || batteryTone == RRTone.improving || batteryTone == null)) RRTone.caution
            else batteryTone
        val today = TrainingGuideEngine(now = now, zone = zone, level = level)
            .todayWorkout(runs = runs, guide = guide,
                          batteryTone = effectiveTone, weeklyGoal = weeklyGoal)
        return when (today.kind) {
            TodayWorkout.Kind.rest ->
                // 방전 임박에는 거리를 내지 않는다 — 오늘의 처방은 쉬는 것이다
                line(TodayVerdict.Line.Content.value("오늘은 휴식"), RRTone.overload)
            TodayWorkout.Kind.doneCount ->
                line(TodayVerdict.Line.Content.value("이번 주 횟수를 다 채우셨어요"), RRTone.improving)
            TodayWorkout.Kind.doneKm ->
                line(TodayVerdict.Line.Content.value("이번 주 목표를 채우셨어요"), RRTone.improving)
            TodayWorkout.Kind.easy, TodayWorkout.Kind.lsd, TodayWorkout.Kind.tempo, is TodayWorkout.Kind.interval ->
                line(TodayVerdict.Line.Content.value(sessionPhrase(today, batteryTone = effectiveTone)),
                     effectiveTone ?: RRTone.steady)
        }
    }

    /// "이지런 5.0km · 5′20″" — 인터벌은 스펙이 이미 이름에 있어 거리를 겹쳐 쓰지 않고,
    /// 페이스는 존 구간의 중앙값 하나만 낸다 (구간 전체는 리포트 탭 카드에서 본다)
    private fun sessionPhrase(today: TodayWorkout, batteryTone: RRTone?): String {
        val name = if (today.kind == TodayWorkout.Kind.easy) intensityLabel(batteryTone) else today.kind.label
        val km: String? = when (today.kind) {
            is TodayWorkout.Kind.interval -> null
            else -> today.distanceKm?.let { "${Format.km(it)}km" }
        }
        val pace = today.paceSecPerKm?.let {
            Format.pace((it.start + it.endInclusive) / 2)
        }
        return listOfNotNull(name + (km?.let { " $it" } ?: ""), pace)
            .joinToString(" · ")
    }

    /// 강도 이름 — 배터리 톤 매핑. overload는 위에서 이미 갈라져 여기 오지 않는다
    private fun intensityLabel(tone: RRTone?): String =
        when (tone) {
            RRTone.caution -> "가볍게"
            RRTone.improving -> "빌드업"
            else -> "이지런"      // steady · 배터리 없음
        }

    // MARK: - 회복 경과

    private fun recoveryLine(runs: List<RunSummary>, now: Instant, zone: ZoneId): TodayVerdict.Line {
        // 호출부가 기록 유무를 이미 확인했다 — 여기 오면 마지막 러닝은 반드시 있다
        val last = runs.maxOfOrNull { it.start } ?: now
        // (Android: iOS `calendar`(ISO 8601 + .current)의 자정 간 일수 → zone의 LocalDate 차이)
        val days = ChronoUnit.DAYS.between(last.atZone(zone).toLocalDate(),
                                           now.atZone(zone).toLocalDate()).toInt()
        val text = when {
            days < 1 -> "오늘 다녀오셨어요"
            days == 1 -> "어제"
            else -> "${days}일 전"
        }
        return TodayVerdict.Line(kind = TodayVerdict.Line.Kind.recovery, label = "마지막 러닝",
                                 content = TodayVerdict.Line.Content.value(text), tone = null)
    }

    // MARK: - 달력 (ISO 8601, 월요일 시작 — 홈 목표 칩·TrainingGuideEngine과 같은 정의)
    // (Android: iOS `calendar` 프로퍼티는 주입받은 `zone`으로 대신한다)
}
