package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.ui.Format
import com.jkpark.runwrap.ui.theme.Eyebrow
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.RRTone
import com.jkpark.runwrap.ui.theme.rrCard
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/// 리포트 요약 — 주간 지표를 문장 + 근거 수치 + 용어 설명으로 풀어낸다.
/// iOS `ReportDetailScreen.swift` 이식. 내비게이션 바 대신 상단에 뒤로가기 행을 직접 그린다.
@Composable
fun ReportDetailScreen(
    report: WeeklyReport,
    guide: TrainingGuide?,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(RR.bg)) {
        // 상단 바 — "주간 요약" (iOS navigationTitle inline 대응)
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로",
                tint = RR.brand,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(onClick = onBack)
                    .padding(10.dp)
                    .size(22.dp),
            )
            Text(
                "주간 요약", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(bottom = 10.dp),
                   verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("${report.weekLabel} · ${report.dateRange}")
                Text(report.headline, fontSize = 26.sp, lineHeight = 36.sp,
                     fontWeight = FontWeight.Bold, color = RR.text)
            }

            report.distance?.let { DistanceSection(it) }
            report.acwr?.let { AcwrSection(it) }
            report.efficiency?.let { EfficiencySection(it) }

            guide?.let { g ->
                g.prediction?.let { PredictionSection(it) }
                g.zones?.let { ZonesSection(it) }
                PrescriptionSection(g.prescription)
                g.balance?.let { BalanceSection(it) }
            }

            report.suggestion?.let { suggestion ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(RR.surface2, RoundedCornerShape(20.dp))
                        .border(1.dp, RR.line, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("다음 주 제안", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                         color = RR.text)
                    Text(suggestion, fontSize = 13.5.sp, lineHeight = 20.sp, color = RR.text2)
                }
            }

            Text(
                "본 리포트는 참고용이며 의학적 조언이 아닙니다. 통증이나 이상이 있다면 전문가와 상담하세요.",
                Modifier.padding(horizontal = 4.dp).padding(top = 2.dp),
                fontSize = 11.5.sp, lineHeight = 16.sp, color = RR.text3,
            )
        }
    }
}

// MARK: - 섹션

@Composable
private fun DistanceSection(card: WeeklyReport.DistanceCard) {
    val sentence = if (card.overKm > 0) {
        "권장 상한 %.1f km를 %.1f km 넘겼습니다.".format(Locale.ROOT, card.capKm, card.overKm)
    } else {
        "권장 상한 %.1f km 안에서 달렸습니다.".format(Locale.ROOT, card.capKm)
    }
    val changeText = "%s%.1f%%".format(
        Locale.ROOT, if (card.changePct >= 0) "+" else "−", abs(card.changePct),
    )
    Section(
        color = if (card.tone == RRTone.STEADY) RR.brand else card.tone.color,
        title = "주간 거리 · 10% 규칙",
        sentence = sentence,
        metrics = listOf(
            Format.km(card.recent7Km) + " km" to RR.text2,
            changeText to RR.text2,
            "상한 %.1f km".format(Locale.ROOT, card.capKm) to RR.text2,
        ),
        explainTitle = "10% 규칙이란",
        explainBody = "주간 거리를 직전 주 대비 10% 이내로 늘려야 몸이 적응할 시간이 생긴다는 경험칙.",
    )
}

@Composable
private fun AcwrSection(card: WeeklyReport.AcwrCard) {
    val sentence = when (card.tone) {
        RRTone.OVERLOAD, RRTone.CAUTION ->
            if (card.ratio > 1.3) "안전 구간 0.8–1.3을 벗어났습니다. 1.5를 넘으면 부상 위험이 커집니다."
            else "훈련량이 평소의 8할 밑으로 내려갔습니다. 급감도 리듬을 흔들 수 있어요."
        else -> "안전 구간 0.8–1.3 안에 있습니다. 몸이 감당할 수 있는 부하예요."
    }
    Section(
        color = if (card.tone == RRTone.STEADY) RR.pos else card.tone.color,
        title = "부하 비율 · ACWR",
        sentence = sentence,
        metrics = listOf(
            "급성 %.1f".format(Locale.ROOT, card.acute) to RR.text2,
            "만성 %.1f".format(Locale.ROOT, card.chronic) to RR.text2,
            "ACWR %.2f".format(Locale.ROOT, card.ratio) to
                (if (card.tone == RRTone.STEADY) RR.text2 else card.tone.color),
        ),
        explainTitle = "ACWR이란",
        explainBody = "최근 7일 훈련량 ÷ 최근 4주 주간 평균. 1에 가까울수록 평소만큼 달렸다는 뜻입니다.",
    )
}

@Composable
private fun EfficiencySection(card: WeeklyReport.EfficiencyCard) {
    val delta = abs(card.paceDeltaSec).roundToInt()
    val sentence = when (card.tone) {
        RRTone.IMPROVING -> "같은 심박에서 페이스가 ${delta}초 빨라졌습니다. 체력은 오르는 중입니다."
        RRTone.CAUTION -> "같은 심박에서 페이스가 ${delta}초 느려졌습니다. 피로가 쌓였다는 신호일 수 있어요."
        else -> "같은 심박에서 페이스가 비슷하게 유지되고 있습니다."
    }
    val changeText = "%s%.1f%%".format(
        Locale.ROOT, if (card.changePct >= 0) "+" else "−", abs(card.changePct),
    )
    Section(
        color = if (card.tone == RRTone.STEADY) RR.brand else card.tone.color,
        title = "심박 효율 · Efficiency Factor",
        sentence = sentence,
        metrics = listOf(
            "EF %.2f → %.2f".format(Locale.ROOT, card.previousEF, card.recentEF) to RR.text2,
            changeText to (if (card.tone == RRTone.IMPROVING) RR.pos else RR.text2),
        ),
        explainTitle = "효율 지수란",
        explainBody = "속도 ÷ 평균 심박. 값이 커지면 같은 노력으로 더 빨리 달린다는 뜻입니다.",
    )
}

// MARK: - 훈련 가이드 섹션 (진단·처방·밸런스)

@Composable
private fun PredictionSection(prediction: TrainingGuide.Prediction) {
    val goal = prediction.goalSec
    val sentence = if (goal != null) {
        when (prediction.tone) {
            RRTone.IMPROVING -> "목표 ${Format.duration(goal)}는 지금 기록으로도 달성권입니다."
            RRTone.CAUTION ->
                "목표 ${Format.duration(goal)}까지는 아직 갭이 있습니다. 주간 거리부터 차근히 올려보세요."
            else -> "목표 ${Format.duration(goal)}에 근접해 있습니다. 페이스 유지가 관건이에요."
        }
    } else {
        "최근 기록으로 보면 ${prediction.race.label}${prediction.race.objectParticle} " +
            "${Format.duration(prediction.predictedSec)}에 완주할 수 있습니다."
    }
    Section(
        color = if (prediction.tone == RRTone.STEADY) RR.brand else prediction.tone.color,
        title = "목표 진단 · Riegel 예측",
        sentence = sentence,
        metrics = listOf(
            "예상 ${Format.duration(prediction.predictedSec)}" to RR.text2,
            "${prediction.baseLabel} ${Format.duration(prediction.baseTimeSec)} 기준" to RR.text2,
        ),
        explainTitle = "Riegel 공식이란",
        explainBody = "T2 = T1 × (D2/D1)^1.06 — 기록 하나로 다른 거리의 완주 시간을 추정하는 " +
            "경험식입니다 (Riegel 1981). 최근 8주 안의 최고 기록을 기준으로 씁니다.",
    )
}

/// 훈련 페이스 존 — 목표가 아니라 "현재 실력"(최근 PR의 VDOT)에서 산출한다는 것이 핵심
@Composable
private fun ZonesSection(zones: TrainingGuide.PaceZones) {
    Section(
        color = RR.brand,
        title = "훈련 페이스 · Daniels VDOT",
        sentence = ("현재 실력은 VDOT %.0f 수준입니다. 훈련 페이스는 목표 기록이 아니라 " +
            "이 값에서 나옵니다.").format(Locale.ROOT, zones.vdot),
        // 인터벌 페이스는 리포트 카드의 페이스 표에 이미 있다 — 세 개를 다 넣으면
        // 13sp monospace 기준 한 줄 폭을 넘겨 줄바꿈되므로 두 개만 둔다 (iOS 동일)
        metrics = listOf(
            "이지 ${Format.pace(zones.easySecPerKm.start)}~" +
                Format.pace(zones.easySecPerKm.endInclusive) to RR.text2,
            "템포 ${Format.pace(zones.tempoSecPerKm)}" to RR.text2,
        ),
        explainTitle = "VDOT이란",
        explainBody = "최근 8주 최고 기록에서 역산한 유효 최대산소섭취량입니다 " +
            "(Daniels & Gilbert 1979). 이지런은 그 62~74%, 템포런은 88%, 인터벌은 97.5% 강도에 " +
            "해당하는 페이스예요. 목표가 더 빨라도 훈련 페이스를 앞당기면 부상 위험만 커집니다 — " +
            "기록이 좋아지면 페이스가 따라 올라갑니다.",
    )
}

@Composable
private fun PrescriptionSection(prescription: TrainingGuide.Prescription) {
    val phase = prescription.phase
    val days = prescription.daysToRace
    val sentence = when {
        prescription.batteryLimited -> "체력 배터리가 낮은 주입니다. LSD는 하한으로 줄이고 인터벌은 뺐어요."
        phase != null && days != null ->
            "대회까지 ${if (days == 0) "D-day" else "D-$days"}, 지금은 ${phase.label}입니다. " +
                "단계에 맞춘 이번 주 처방이에요."
        else -> "지난 4주 부하에 10% 룰을 적용한 이번 주 처방입니다."
    }
    val lsdText = if (prescription.lsdKmHigh < 1) "LSD —"
        else "LSD " + kmRangeText(prescription.lsdKmLow, prescription.lsdKmHigh)
    Section(
        color = RR.brand,
        title = "주간 처방 · 10% 룰",
        sentence = sentence,
        metrics = listOf(
            kmRangeText(prescription.weeklyKmLow, prescription.weeklyKmHigh) + " km" to RR.text2,
            lsdText to RR.text2,
            "퀄리티 ${prescription.qualityCount}회" to RR.text2,
        ),
        explainTitle = "처방 기준",
        explainBody = "권장 주간 거리는 4주 평균의 100~110%(10% 룰 상한)이고, 종목·레벨별 피크 " +
            "주간 거리에서 멈춥니다. LSD는 그중 25~35%, 퀄리티(템포런·인터벌)는 단계·레벨에 따라 " +
            "주 0~2회입니다. 대회 날짜를 정하면 기초→강화→피크→테이퍼 단계로 볼륨을 조절하고, " +
            "체력 배터리가 주의 이하면 LSD 하한·인터벌 제외로 내립니다 (기획서 §4.9).",
    )
}

@Composable
private fun BalanceSection(balance: TrainingGuide.Balance) {
    val sentence = if (balance.tone == RRTone.CAUTION) {
        ("이번 주 스피드 비중이 %.0f%%로 높습니다. 낮은 강도 8 : 높은 강도 2가 기준이에요.")
            .format(Locale.ROOT, balance.speedSharePct)
    } else {
        "낮은 강도와 높은 강도의 비율이 80/20 원칙 안에 있습니다."
    }
    Section(
        color = if (balance.tone == RRTone.STEADY) RR.pos else balance.tone.color,
        title = "강도 밸런스 · 80/20",
        sentence = sentence,
        metrics = listOf(
            "easy ${balance.easyCount}" to RR.text2,
            "LSD ${balance.lsdCount}" to RR.text2,
            "스피드 ${balance.speedCount}" to
                (if (balance.tone == RRTone.CAUTION) balance.tone.color else RR.text2),
        ),
        explainTitle = "80/20 원칙이란",
        explainBody = "훈련의 8할은 낮은 강도로, 2할만 높은 강도로 채우는 것이 지구력 향상에 " +
            "효과적이라는 원칙입니다 (Seiler 80/20).",
    )
}

@Composable
private fun Section(
    color: Color,
    title: String,
    sentence: String,
    metrics: List<Pair<String, Color>>,
    explainTitle: String,
    explainBody: String,
) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Text(sentence, Modifier.padding(top = 11.dp),
             fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
             color = RR.text)
        Row(
            Modifier.padding(top = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            metrics.forEachIndexed { index, (text, metricColor) ->
                if (index > 0) Text("·", color = RR.text3)
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                     fontFamily = FontFamily.Monospace, color = metricColor)
            }
        }
        Column(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(RR.surface2, RoundedCornerShape(13.dp))
                .border(1.dp, RR.line, RoundedCornerShape(13.dp))
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(explainTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RR.text2)
            Text(explainBody, fontSize = 13.sp, lineHeight = 19.sp, color = RR.text2)
        }
    }
}
