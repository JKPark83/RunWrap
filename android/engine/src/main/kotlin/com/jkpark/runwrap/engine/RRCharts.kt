package com.jkpark.runwrap.engine

/// RRCharts의 순수 문자열 조립만 옮긴 것 — 차트 뷰 자체는 :app(Compose)이 그린다.
/// (Android: iOS는 View의 static 함수다. 엔진은 UI를 모르므로 `~ChartText` 객체로 뺐다)

/// TrendLineChart의 VoiceOver 요약
object TrendLineChartText {
    /// VoiceOver 요약 — "8월 1째주 12.3에서 8월 4째주 14.1". 점 하나면 그 점만, 없으면 빈 문자열.
    /// 시기·수치 문자열은 탭 콜아웃과 같은 `pointLabels`·`valueText`를 쓴다.
    fun accessibilitySummary(points: List<Double>, labels: List<String>?,
                             valueText: (Double) -> String): String {
        fun describe(index: Int): String {
            val value = valueText(points[index])
            if (labels == null || index !in labels.indices) return value
            return "${labels[index]} $value"
        }
        val last = points.indices.lastOrNull() ?: return ""
        if (!(last > 0)) return describe(0)
        return "${describe(0)}에서 ${describe(last)}"
    }
}

/// ZoneStackedBarsChart의 콜아웃·VoiceOver 수치
object ZoneStackedBarsChartText {
    /// 콜아웃·VoiceOver 수치 — "1:23:40 · 이지 72%", 달리지 않은 주는 "기록 없음"
    fun valueText(week: ZoneDistribution.WeekBar): String {
        val total = week.zoneSeconds.sum()
        if (!(total > 0)) return "기록 없음"
        val easy = ((week.zoneSeconds[0] + week.zoneSeconds[1]) / total * 100).swiftRoundedInt()
        return "${Format.duration(total)} · 이지 ${easy}%"
    }
}
