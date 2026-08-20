package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.Locale

/// 파생 지표 하나의 계산 결과 — 수치 나열이 아니라 해석된 문장을 담는다(기획서 §4.2 원칙)
data class Insight(
    val kind: Kind,
    val tone: Tone,
    val headline: String,
    val detail: String,
) {
    enum class Kind {
        WEEKLY_DISTANCE_CHANGE,  // 주간 거리 증가율 (10% 룰)
        ACWR,                    // 급성:만성 부하비 (부상 위험)
        HEART_RATE_EFFICIENCY,   // 심박 효율 (컨디션 프록시)
    }

    enum class Tone { POSITIVE, NEUTRAL, WARNING }
}

/// 문장 톤 3단계 (기획서 v0.7 §4 "문장 톤" 행) — 레벨 게이트의 문구 쪽 짝.
///
/// `ReportGate`가 "어떤 카드를 보여줄까"를 답한다면 이쪽은 "같은 카드를 어떤 말투로 쓸까"를
/// 답한다. 레벨을 문장마다 다시 분기하지 않고 한 번 톤으로 접어두는 이유는, 새 지표가
/// 늘어도 분기 축이 레벨 3개가 아니라 톤 3개로 고정되기 때문이다.
enum class ReportVoice {
    /// 런린이 — 용어를 풀어쓴다. 지표 약어(ACWR·EF)를 문장에 노출하지 않는다.
    PLAIN,

    /// 런잘알 — 표준. 지표명과 해석을 함께 쓴다.
    STANDARD,

    /// 런친놈 — 압축·수치 중심. 값을 앞세우고 구간명을 붙인다.
    COMPACT;

    companion object {
        fun of(level: RunnerLevel): ReportVoice = when (level) {
            RunnerLevel.BEGINNER -> PLAIN
            RunnerLevel.INTERMEDIATE -> STANDARD
            RunnerLevel.ADVANCED -> COMPACT
        }
    }
}

/// 리포트 엔진 (MVP 2단계) — 러닝 요약 목록에서 파생 지표를 계산한다. iOS `ReportEngine.swift` 이식.
///
/// 산식 출처(기획서 §9: 출처 명시 + 참고용 고지):
/// - 주간 거리 증가율: 10% 룰. 달력 주가 아닌 롤링 7일 창을 쓴다 —
///   달력 주는 주 초반 리포트가 "급감"으로 오독된다.
/// - ACWR: 급성 부하(최근 7일 거리) ÷ 만성 부하(최근 28일의 주 평균 거리).
///   적정 0.8~1.3, 1.5 초과는 부상 위험 신호 (Gabbett, 2016).
/// - 심박 효율(EF): 분속(m/min) ÷ 평균 심박 (TrainingPeaks Efficiency Factor).
///   최근 2주 평균을 직전 2주와 비교 — 상승이면 같은 심박으로 더 빨리 달린다는 뜻.
///
/// 표본이 부족해 비율이 과장될 상황(기준 주가 거의 비어 있음, 기록 3주 미만 등)에는
/// 해당 지표를 아예 내지 않는다 — 틀린 인사이트는 없느니만 못하다.
class ReportEngine(
    val now: Instant = Instant.now(),
    /// 문장 난이도 — 레벨 3단계가 `ReportVoice` 3단계로 접힌다 (기획서 v0.7 §4).
    /// 엔진은 순수 로직 유지: 프로필 저장소를 모르고 값 타입으로만 주입받는다.
    /// 기본값은 표준 톤(런잘알) — 레벨을 주지 않은 호출부가 용어를 숨기지도, 압축하지도 않게.
    val level: RunnerLevel = RunnerLevel.INTERMEDIATE,
) {
    /// 이 엔진이 쓸 문장 톤 — 레벨을 문장마다 다시 분기하지 않기 위한 단일 진입점
    private val voice: ReportVoice get() = ReportVoice.of(level)

    fun insights(runs: List<RunSummary>): List<Insight> = listOfNotNull(
        weeklyDistanceChange(runs), acwr(runs), heartRateEfficiency(runs),
    )

    // MARK: - 주간 거리 증가율 (10% 룰)

    private fun weeklyDistanceChange(runs: List<RunSummary>): Insight? {
        val recent = totalKm(runs, fromDaysAgo = 7, toDaysAgo = 0)
        val previous = totalKm(runs, fromDaysAgo = 14, toDaysAgo = 7)
        if (previous < 3) return null  // 기준 주가 3km 미만이면 증가율이 과장된다
        val change = (recent - previous) / previous * 100
        val pct = "%+.0f%%".format(Locale.ROOT, change)
        val km = "%.1fkm".format(Locale.ROOT, recent)
        val detail = "최근 7일 %.1fkm · 이전 7일 %.1fkm.".format(Locale.ROOT, recent, previous)
        // 런린이는 수치를 문장에 싣지 않는다 (§4 "문장만") — 얼마나가 아니라 어떤 상태인지만 말한다
        return when {
            change >= 10 -> Insight(
                Insight.Kind.WEEKLY_DISTANCE_CHANGE, Insight.Tone.WARNING,
                headline(plain = "이번 주 갑자기 많이 늘었어요 — 몸이 따라오기 벅찰 수 있어요",
                         standard = "지난주 대비 $pct — 과부하 구간입니다",
                         compact = "주간 $km $pct · 과부하 구간"),
                detail + if (voice == ReportVoice.PLAIN) " 거리는 한 주에 조금씩만 늘리는 게 안전해요."
                         else " 주간 증가 폭은 10% 이내가 안전합니다.")
            change < -30 -> Insight(
                Insight.Kind.WEEKLY_DISTANCE_CHANGE, Insight.Tone.NEUTRAL,
                headline(plain = "이번 주는 많이 쉬어갔어요",
                         standard = "지난주 대비 $pct — 훈련량이 크게 줄었습니다",
                         compact = "주간 $km $pct · 감량 구간"),
                detail)
            else -> Insight(
                Insight.Kind.WEEKLY_DISTANCE_CHANGE, Insight.Tone.POSITIVE,
                headline(plain = "딱 좋은 만큼 달리고 있어요",
                         standard = "지난주 대비 $pct — 안정적인 훈련량입니다",
                         compact = "주간 $km $pct · 안정 구간"),
                detail)
        }
    }

    // MARK: - ACWR (급성:만성 부하비)

    private fun acwr(runs: List<RunSummary>): Insight? {
        // 기록이 3주 미만이면 만성 부하(분모)가 작아 비율이 과장된다
        val oldest = runs.minOfOrNull { it.start } ?: return null
        if (oldest > date(daysAgo = 21)) return null
        val acute = totalKm(runs, fromDaysAgo = 7, toDaysAgo = 0)
        val chronic = totalKm(runs, fromDaysAgo = 28, toDaysAgo = 0) / 4
        if (chronic < 3) return null  // 주 평균 3km 미만이면 지표가 무의미하다
        val ratio = acute / chronic
        val value = "%.1f".format(Locale.ROOT, ratio)
        val detail = if (voice == ReportVoice.PLAIN)
            "최근 7일 %.1fkm를 지난 4주 주평균 %.1fkm와 비교한 값이에요.".format(Locale.ROOT, acute, chronic)
        else
            "최근 7일 %.1fkm ÷ 4주 주평균 %.1fkm. 적정 범위는 0.8~1.3.".format(Locale.ROOT, acute, chronic)
        // 런친놈은 "ACWR 1.0 · 적정 구간" 꼴 — 지표명·값·구간명을 한 줄에 압축한다 (§4 "수치+구간").
        // 런린이 문장에는 ACWR이라는 약어가 절대 나오지 않는다 — 용어 풀어쓰기.
        return when {
            ratio >= 1.5 -> Insight(
                Insight.Kind.ACWR, Insight.Tone.WARNING,
                headline(plain = "몸이 익숙한 양보다 훨씬 많이 달렸어요 — 부상을 조심할 때예요",
                         standard = "이번 주 부하가 4주 평균의 ${value}배 — 부상 위험 구간입니다",
                         compact = "ACWR $value · 부상 위험 구간"),
                detail)
            ratio >= 1.3 -> Insight(
                Insight.Kind.ACWR, Insight.Tone.NEUTRAL,
                headline(plain = "평소보다 조금 많이 달리고 있어요",
                         standard = "이번 주 부하가 4주 평균의 ${value}배 — 다소 높습니다",
                         compact = "ACWR $value · 주의 구간"),
                detail)
            ratio >= 0.8 -> Insight(
                Insight.Kind.ACWR, Insight.Tone.POSITIVE,
                headline(plain = "몸이 감당할 수 있는 만큼 달리고 있어요",
                         standard = "이번 주 부하가 4주 평균의 ${value}배 — 적정 범위입니다",
                         compact = "ACWR $value · 적정 구간"),
                detail)
            else -> Insight(
                Insight.Kind.ACWR, Insight.Tone.NEUTRAL,
                headline(plain = "이번 주는 평소보다 많이 쉬었어요",
                         standard = "이번 주 부하가 4주 평균의 ${value}배 — 회복 주간 수준입니다",
                         compact = "ACWR $value · 회복 구간"),
                detail)
        }
    }

    // MARK: - 심박 효율 (컨디션 프록시)

    private fun heartRateEfficiency(runs: List<RunSummary>): Insight? {
        val recent = runs.filter { it.start >= date(daysAgo = 14) }
            .mapNotNull(::efficiency)
        val previous = runs.filter { it.start >= date(daysAgo = 28) && it.start < date(daysAgo = 14) }
            .mapNotNull(::efficiency)
        if (recent.size < 3 || previous.size < 3) return null  // 표본이 적으면 잡음이 크다
        val change = (recent.average() - previous.average()) / previous.average() * 100
        val pct = "%+.1f%%".format(Locale.ROOT, change)
        val detail = if (voice == ReportVoice.PLAIN)
            "심장이 뛰는 것에 비해 얼마나 빨리 달리는지를 2주 단위로 비교한 값이에요."
        else
            "심박당 속도(EF)의 최근 2주 평균을 직전 2주와 비교한 값입니다."
        // 런친놈은 "EF +5.2% · …" 꼴로 값을 앞세운다 — 해석보다 수치가 먼저다 (§4 "압축·수치 중심")
        return when {
            change >= 3 -> Insight(
                Insight.Kind.HEART_RATE_EFFICIENCY, Insight.Tone.POSITIVE,
                headline(plain = "같은 힘으로 더 빨리 달리고 있어요 — 체력이 늘고 있다는 뜻이에요",
                         standard = "같은 심박으로 더 빨리 달리고 있습니다 ($pct) — 체력이 오르는 중",
                         compact = "EF $pct · 체력 상승"),
                detail)
            change < -3 -> Insight(
                Insight.Kind.HEART_RATE_EFFICIENCY, Insight.Tone.NEUTRAL,
                headline(plain = "요즘 페이스가 조금 처졌어요 — 피곤하거나 더위 탓일 수 있어요",
                         standard = "심박 효율 $pct — 피로 누적이나 더위 영향일 수 있습니다",
                         compact = "EF $pct · 피로·더위 영향 가능"),
                detail)
            else -> Insight(
                Insight.Kind.HEART_RATE_EFFICIENCY, Insight.Tone.NEUTRAL,
                headline(plain = "체력이 지난 2주와 비슷하게 유지되고 있어요",
                         standard = "심박 효율이 지난 2주와 비슷합니다 — 컨디션 유지 중",
                         compact = "EF $pct · 컨디션 유지"),
                detail)
        }
    }

    // MARK: - 공통

    /// 톤 3단계 중 하나를 고른다 — 지표마다 `when (voice)`를 반복하지 않으려고 둔 헬퍼.
    /// 세 문장을 호출부에 나란히 두면 톤 차이를 눈으로 대조할 수 있다.
    private fun headline(plain: String, standard: String, compact: String): String = when (voice) {
        ReportVoice.PLAIN -> plain
        ReportVoice.STANDARD -> standard
        ReportVoice.COMPACT -> compact
    }

    private fun date(daysAgo: Int): Instant = now.minusSeconds(daysAgo * 86_400L)

    /// [now-fromDaysAgo, now-toDaysAgo) 창에 시작된 러닝의 거리 합 (km)
    private fun totalKm(runs: List<RunSummary>, fromDaysAgo: Int, toDaysAgo: Int): Double {
        val from = date(fromDaysAgo)
        val to = date(toDaysAgo)
        return runs.filter { it.start >= from && it.start < to }
            .mapNotNull { it.distanceKm }
            .sum()
    }

    companion object {
        /// EF = 분속(m/min) ÷ 평균 심박. 페이스나 심박이 없는 러닝은 표본에서 제외.
        internal fun efficiency(run: RunSummary): Double? {
            val pace = run.paceSecPerKm ?: return null
            val hr = run.avgHeartRate ?: return null
            if (hr <= 0) return null
            return (60_000 / pace) / hr
        }
    }
}
