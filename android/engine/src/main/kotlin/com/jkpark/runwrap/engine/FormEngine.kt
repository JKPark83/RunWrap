package com.jkpark.runwrap.engine

import java.time.Instant
import kotlin.math.abs

/// 주법 리포트 엔진 (기획서 §4.8, 계획서 M4) — 러닝 다이내믹스를 본인 기준선과 비교해
/// 조언 문장을 만든다. 순수 로직: Foundation만 쓰고 now를 주입받아 결정론적이다.
///
/// 조언은 절대 권장치가 아니라 "최근 28일 내 평균 대비"로만 낸다 — 러너마다 체형·주법이
/// 달라 절대값 비교는 오답이 많다 (Garmin Running Dynamics 가이드). 절대 밴드는
/// 측정 오류(헐거운 착용 등) 감지에만 쓴다. 실내 러닝은 다이내믹스가 기록되지 않아
/// (애플 공식) 스냅샷 수집 단계에서 걸러진다.
/// (Android: Health Connect에는 러닝 다이내믹스(진폭·접촉 시간)가 없어 스토어가 nil을 준다 —
///  아래 지표별 표본 가드로 해당 지표가 빠지고, 비교할 지표가 없으면 기준선·카드가 사라진다. 산식은 그대로다)

/// 세션 1회의 주법 스냅샷 — 판정에 필요한 값만 추린다
/// (Android: id는 iOS UUID 대신 String — RunSummary.id와 같다)
data class FormSnapshot(
    val id: String,
    val start: Instant,
    val cadenceSpm: Double?,
    val verticalOscillationCm: Double?,
    val groundContactMs: Double?,
)

/// 최근 28일 야외 러닝의 지표별 평균 — 조언의 비교 기준선
data class FormBaseline(
    val cadenceSpm: Double?,
    val verticalOscillationCm: Double?,
    val groundContactMs: Double?,
    val sessionCount: Int,
)

/// 조언 한 건 — 문장은 엔진이 만들고 화면은 그대로 그린다
data class FormAdvice(
    val kind: Kind,
    val message: String,
) {
    enum class Kind {
        sensor,       // 측정 오류 가능성 (절대 밴드 이탈)
        cadence,      // 보폭 조언
        oscillation,  // 진폭 조언
        contact,      // 접촉 시간 조언
    }
}

data class FormEngine(
    val now: Instant,
) {
    companion object {
        /// 기준선 창과 최소 표본 — 창 안 야외 5회 미만이면 기준선을 아예 내지 않는다 (계획서 M4)
        const val windowDays = 28.0
        const val minSessions = 5

        /// 측정 오류 감지용 절대 밴드 (가정 — Garmin Running Dynamics 통상 분포의 바깥)
        val oscillationBandCm = 4.0..10.0
        val contactBandMs = 150.0..300.0
    }

    /// 기준선 = 창 안(28일) 스냅샷의 지표별 평균. 본인 세션은 제외한다 —
    /// 이번 세션이 평균을 끌어당기면 이탈이 묽어져 조언이 둔해진다.
    /// 지표별 가드 (이슈 #92): 진폭·접촉시간은 다이내믹스 미기록 세션에서 nil이라 세션 수만 보면
    /// '5회 기준선'이 1~2회 평균일 수 있다 — 지표마다 표본이 minSessions 이상일 때만 평균낸다.
    /// 절대 밴드 밖 값은 측정 오류로 보고 표본에서 뺀다 (케이던스는 밴드가 없어 표본 수만 본다).
    fun baseline(of: List<FormSnapshot>, excluding: String): FormBaseline? {
        val cutoff = now.minusSeconds((windowDays * 86_400).toLong())
        val window = of.filter { it.id != excluding && it.start >= cutoff && it.start <= now }
        if (!(window.size >= minSessions)) return null
        fun mean(values: List<Double>, within: ClosedFloatingPointRange<Double>? = null): Double? {
            val samples = if (within != null) values.filter { it in within } else values
            if (!(samples.size >= minSessions)) return null
            return samples.fold(0.0) { sum, v -> sum + v } / samples.size
        }
        val cadence = mean(window.mapNotNull { it.cadenceSpm })
        val oscillation = mean(window.mapNotNull { it.verticalOscillationCm }, within = oscillationBandCm)
        val contact = mean(window.mapNotNull { it.groundContactMs }, within = contactBandMs)
        // 비교할 지표가 하나도 없으면 기준선도 없다 — 빈 기준선은 '평소대로 유지' 오판정을 낸다
        if (cadence == null && oscillation == null && contact == null) return null
        return FormBaseline(cadenceSpm = cadence,
                            verticalOscillationCm = oscillation,
                            groundContactMs = contact,
                            sessionCount = window.size)
    }

    /// 조언 규칙 (가정 — 계획서 M4): 케이던스 < 기준선×0.95 → 보폭 /
    /// 진폭 > ×1.10 → 진폭 / 접촉 > ×1.10 → 접촉 시간.
    /// 절대 밴드를 벗어난 지표는 비교 대신 측정 오류 안내로 대체한다.
    fun advice(session: FormSnapshot, baseline: FormBaseline): List<FormAdvice> {
        val result = mutableListOf<FormAdvice>()

        val voOutOfBand = session.verticalOscillationCm?.let { it !in oscillationBandCm } ?: false
        val gctOutOfBand = session.groundContactMs?.let { it !in contactBandMs } ?: false
        if (voOutOfBand || gctOutOfBand) {
            result.add(FormAdvice(
                kind = FormAdvice.Kind.sensor,
                message = "일부 측정값이 통상 범위를 크게 벗어났어요. 워치가 헐겁게 착용되면 주법 측정이 흔들릴 수 있어요."))
        }

        // 케이던스를 5~10% 올리면(=보폭 축소) 무릎·고관절 부하가 준다 — Heiderscheit 2011
        val cadence = session.cadenceSpm
        val cadenceBase = baseline.cadenceSpm
        if (cadence != null && cadenceBase != null && cadence < cadenceBase * 0.95) {
            result.add(FormAdvice(
                kind = FormAdvice.Kind.cadence,
                message = "케이던스가 평소 평균(${fmt(cadenceBase, 0)} spm)보다 ${fmt((1 - cadence / cadenceBase) * 100, 0)}% 낮았어요. 보폭을 조금 줄이고 발걸음을 자주 가져가면 무릎 부담이 줄어요."))
        }
        val vo = session.verticalOscillationCm
        val voBase = baseline.verticalOscillationCm
        if (!voOutOfBand && vo != null && voBase != null && vo > voBase * 1.10) {
            result.add(FormAdvice(
                kind = FormAdvice.Kind.oscillation,
                message = "수직 진폭이 평소(${fmt(voBase, 1)} cm)보다 컸어요. 위로 튀는 힘을 앞으로 보내는 느낌으로 달려보세요."))
        }
        val gct = session.groundContactMs
        val gctBase = baseline.groundContactMs
        if (!gctOutOfBand && gct != null && gctBase != null && gct > gctBase * 1.10) {
            result.add(FormAdvice(
                kind = FormAdvice.Kind.contact,
                message = "지면 접촉 시간이 평소(${fmt(gctBase, 0)} ms)보다 길었어요. 피로가 남았거나 페이스가 처진 신호일 수 있어요 — 가볍게 튀듯 디뎌보세요."))
        }
        return result
    }
}

/// 주간 주법 인사이트 (홈) — 최근 2주 vs 이전 2주 평균 케이던스 비교 (계획서 M4)
data class FormTrend(
    val recentSpm: Double,
    val previousSpm: Double,
    val tone: RRTone,
) {
    val deltaSpm: Double get() = recentSpm - previousSpm

    companion object {
        /// 미노출 가드: 두 창 각각 케이던스 표본 3회 미만이면 추이를 내지 않는다.
        /// ±2 spm 미만 변화는 측정 요동으로 보고 유지로 판정한다 (가정).
        fun compute(runs: List<RunSummary>, now: Instant): FormTrend? {
            val mid = now.minusSeconds(14L * 86_400)
            val cutoff = now.minusSeconds(28L * 86_400)
            val recent = runs.filter { it.start > mid && it.start <= now }.mapNotNull { it.cadenceSpm }
            val previous = runs.filter { it.start > cutoff && it.start <= mid }.mapNotNull { it.cadenceSpm }
            if (!(recent.size >= 3 && previous.size >= 3)) return null
            val recentAvg = recent.fold(0.0) { sum, v -> sum + v } / recent.size
            val previousAvg = previous.fold(0.0) { sum, v -> sum + v } / previous.size
            val delta = recentAvg - previousAvg
            val tone = if (abs(delta) < 2) RRTone.steady else if (delta > 0) RRTone.improving else RRTone.caution
            return FormTrend(recentSpm = recentAvg, previousSpm = previousAvg, tone = tone)
        }
    }
}
