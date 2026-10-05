package com.jkpark.runwrap.engine

/// 날씨 조언 한 줄 — 엔진은 톤과 문장까지만 정하고 색·아이콘은 화면이 매핑한다
data class WeatherAdvice(
    val tone: RRTone,
    val text: String,
)

/// 오늘의 러닝 이름 — 날씨를 러너 은어 헤드라인으로 번역한다 (펀런·우중런·찜런 …).
/// 심볼 매핑은 화면 몫이라 kind만 넘긴다. quip은 재미용 한 줄 — 주의 문구는 advice()가 담당.
data class RunName(
    val kind: Kind,
    val tone: RRTone,
    val title: String,
    val quip: String,
) {
    enum class Kind {
        treadmill, snow, rain, sauna, dawn, shade, `fun`, crisp, hotpack, penguin
    }
}

/// 날씨 조언 룰 — 러닝 이름 헤드라인(runName)과 주의 문구 목록(advice)을 만들어
/// 오늘 탭 제목 "오늘, 달리기 좋을까"에 대한 답을 만든다 (확장 요구, 2026-08-12).
/// 온도 구간·풍속 하한은 복장 룰(OutfitRules)의 24/16/8/0°C·8m/s와 맞춰
/// 두 카드가 모순된 말을 하지 않게 한다. 구간별 문장은 가정 — 사용 피드백으로 조정.
object WeatherAdviceRules {
    /// 비 판정 단일 기준 — 러닝 이름·조언·복장·홈 판단 카드가 모두 이 헬퍼를 쓴다 (이슈 #109).
    /// open-meteo는 이슬비·약한 소나기 코드인데 강수량이 0.0mm인 경우가 흔해 강수량만 보면 놓친다.
    /// WMO 4677 코드표: 51~57 이슬비(어는 이슬비 포함), 61~67 비(어는 비 포함),
    /// 80~82 소나기, 95~99 뇌우 — 뇌우는 비를 동반하므로 복장·노면 조언도 비 기준으로 본다.
    fun isRaining(code: Int?, precipitationMm: Double): Boolean {
        val code = code ?: 0
        return precipitationMm > 0 || code in 51..67 || code in 80..82 ||
            code in 95..99
    }

    /// 우선순위: 뇌우 > 눈 > 비 > 체감온도 구간 — 강수는 온도보다 그날의 러닝 성격을 더 크게 바꾼다.
    /// 톤은 advice()의 같은 조건 항목과 맞춰 카드 안에서 색이 모순되지 않게 한다.
    fun runName(apparentC: Double, precipitationMm: Double,
                weatherCode: Int?): RunName {
        // 강수 판정은 현재 관측(WMO 코드·강수량)만 사용 — 예보 강수는 아직 조회하지 않는다
        val code = weatherCode ?: 0
        if (code in 95..99) {
            return RunName(kind = RunName.Kind.treadmill, tone = RRTone.overload, title = "트밀런",
                           quip = "뇌우엔 밖은 금물 — 오늘은 러닝머신과 데이트")
        }
        if (code in 71..77 || code in 85..86) {
            return RunName(kind = RunName.Kind.snow, tone = RRTone.caution, title = "설중런",
                           quip = "뽀드득뽀드득, 설원을 달리는 날")
        }
        // 뇌우도 비로 판정되지만 위에서 트밀런으로 먼저 잡힌다
        if (isRaining(code = weatherCode, precipitationMm = precipitationMm)) {
            return RunName(kind = RunName.Kind.rain, tone = RRTone.caution, title = "우중런",
                           quip = "빗소리를 BGM 삼아 달리는 낭만")
        }
        return when {
            apparentC >= 33 ->
                RunName(kind = RunName.Kind.sauna, tone = RRTone.overload, title = "찜런",
                        quip = "도로가 통째로 찜질방인 날")
            apparentC >= 28 && apparentC < 33 ->
                RunName(kind = RunName.Kind.dawn, tone = RRTone.caution, title = "새벽런",
                        quip = "한낮은 양보, 해 뜨기 전이 골든타임")
            apparentC >= 24 && apparentC < 28 ->
                RunName(kind = RunName.Kind.shade, tone = RRTone.caution, title = "그늘런",
                        quip = "그늘만 골라 밟는 여름 코스")
            apparentC >= 16 && apparentC < 24 ->
                RunName(kind = RunName.Kind.`fun`, tone = RRTone.improving, title = "펀런",
                        quip = "핑계 없는 날씨 — 오늘 안 뛰면 손해")
            apparentC >= 8 && apparentC < 16 ->
                RunName(kind = RunName.Kind.crisp, tone = RRTone.steady, title = "청량런",
                        quip = "청량한 공기가 페이스를 끌어줘요")
            apparentC >= 0 && apparentC < 8 ->
                RunName(kind = RunName.Kind.hotpack, tone = RRTone.caution, title = "핫팩런",
                        quip = "주머니엔 핫팩, 워밍업은 두 배")
            else ->  // 영하
                RunName(kind = RunName.Kind.penguin, tone = RRTone.overload, title = "펭귄런",
                        quip = "펭귄도 실내를 찾는 혹한")
        }
    }

    fun advice(apparentC: Double, humidityPct: Double, windMs: Double,
               precipitationMm: Double, uvIndex: Double?,
               weatherCode: Int?): List<WeatherAdvice> {
        val items = mutableListOf<WeatherAdvice>()

        // 뇌우는 다른 모든 조언에 앞서는 중단 권고 (WMO 95~99)
        if (weatherCode != null && weatherCode in 95..99) {
            items.add(WeatherAdvice(tone = RRTone.overload, text = "뇌우가 있어요 — 야외 러닝은 미루는 게 안전해요"))
        }

        // 체감온도 구간별 기본 조언 — 항상 1건은 나온다
        when {
            apparentC >= 33 ->
                items.add(WeatherAdvice(tone = RRTone.overload, text = "폭염 수준이에요 — 한낮은 피하고 이른 아침이나 밤에 짧게 달리세요"))
            apparentC >= 28 && apparentC < 33 ->
                items.add(WeatherAdvice(tone = RRTone.caution, text = "많이 더워요 — 페이스를 평소보다 늦추고 거리를 줄이세요"))
            apparentC >= 24 && apparentC < 28 ->
                items.add(WeatherAdvice(tone = RRTone.caution, text = "더운 편이에요 — 물을 자주 마시고 그늘이 있는 코스를 고르세요"))
            apparentC >= 16 && apparentC < 24 ->
                items.add(WeatherAdvice(tone = RRTone.improving, text = "달리기 좋은 온도예요 — 기록을 노려볼 만한 날이에요"))
            apparentC >= 8 && apparentC < 16 ->
                items.add(WeatherAdvice(tone = RRTone.steady, text = "선선해요 — 가볍게 몸을 풀고 나가면 딱 좋아요"))
            apparentC >= 0 && apparentC < 8 ->
                items.add(WeatherAdvice(tone = RRTone.caution, text = "쌀쌀해요 — 부상 예방을 위해 워밍업을 평소보다 길게 하세요"))
            else ->  // 영하
                items.add(WeatherAdvice(tone = RRTone.overload, text = "영하 추위예요 — 빙판을 조심하고 숨이 차면 강도를 낮추세요"))
        }

        // 습도 조언 — 더위와 겹치면 땀이 증발하지 못해 위험도가 한 단계 올라간다
        if (humidityPct >= 80 && apparentC >= 24) {
            items.add(WeatherAdvice(tone = RRTone.overload, text = "고온다습이에요 — 땀이 마르지 않아 체온이 계속 올라요. 오늘은 무리하지 마세요"))
        } else if (humidityPct >= 80) {
            items.add(WeatherAdvice(tone = RRTone.caution, text = "습도가 높아요 — 평소보다 빨리 지칠 수 있어요"))
        } else if (humidityPct <= 30) {
            items.add(WeatherAdvice(tone = RRTone.caution, text = "건조해요 — 목마르기 전에 미리 수분을 챙기세요"))
        }

        if (windMs >= OutfitRules.windbreakerMs) {
            items.add(WeatherAdvice(tone = RRTone.caution, text = "바람이 강해요 — 맞바람 구간에서는 페이스 욕심을 버리세요"))
        }
        if (isRaining(code = weatherCode, precipitationMm = precipitationMm)) {
            items.add(WeatherAdvice(tone = RRTone.caution, text = "비가 와요 — 노면이 미끄러우니 보폭을 줄이세요"))
        }
        if (uvIndex != null && uvIndex >= 6) {
            items.add(WeatherAdvice(tone = RRTone.caution, text = "자외선이 강해요 — 선크림과 모자를 챙기세요"))
        }

        // 심한 것부터: overload → caution → steady → improving. 같은 톤은 추가된 순서 유지
        return items.withIndex()
            .sortedWith(compareBy({ severity(it.value.tone) }, { it.index }))
            .map { it.value }
    }

    private fun severity(tone: RRTone): Int = when (tone) {
        RRTone.overload -> 0
        RRTone.caution -> 1
        RRTone.steady -> 2
        RRTone.improving -> 3
    }
}
