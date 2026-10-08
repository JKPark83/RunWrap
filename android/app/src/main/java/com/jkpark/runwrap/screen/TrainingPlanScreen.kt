package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.RaceFormat
import com.jkpark.runwrap.engine.TrainingGuide
import com.jkpark.runwrap.engine.TrainingPlan
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.rrCard

/// 훈련 계획 캘린더 (이슈 #189) — 목표 대회에서 역산한 주차별 처방.
/// 리포트 홈 race 카드에서 push로 들어온다. 계산은 전부 TrainingPlanEngine이 하고
/// 여기서는 그리기만 한다. 지난 주·이번 주는 실제 거리를 계획 옆에 나란히 둔다 —
/// "계획표"가 아니라 "지금 어디쯤인지"가 보여야 계획을 따라갈 수 있어서다.
/// (Android: 내비게이션 바는 화면이 직접 그린다 — 뒤로 버튼 + 가운데 제목)
@Composable
fun TrainingPlanScreen(
    plan: TrainingPlan,
    onBack: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        NavBar("훈련 계획", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("훈련 계획")
                Text("훈련 계획", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = RR.text)
                Text(
                    "${if (plan.daysToRace == 0) "D-day" else "D-${plan.daysToRace}"} · ${plan.race.label} · ${RaceFormat.fullDate(plan.raceDate)}",
                    style = TextStyle(fontSize = 12.5.sp),
                    color = RR.text2,
                )
            }

            SummaryCard(plan)

            Column(Modifier.fillMaxWidth().rrCard().padding(horizontal = 18.dp)) {
                plan.weeks.forEachIndexed { index, week ->
                    if (index > 0) HorizontalDivider(thickness = Dp.Hairline, color = RR.line)
                    WeekRow(week)
                }
            }

            Text(
                "만성 부하(최근 4주 평균)에서 10% 룰로 늘려 피크 주간 거리에서 멈춥니다. 오늘 컨디션·대기질은 홈 판정을 따르세요.",
                style = TextStyle(fontSize = 12.sp).lineSpacing(3),
                color = RR.text3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
            )
        }
    }
}

/// 내비게이션 바 — 뒤로 버튼 + 인라인 제목 (iOS `.navigationBarTitleDisplayMode(.inline)`)
@Composable
private fun NavBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RRIcons.named("chevron.left"), contentDescription = "뒤로", tint = RR.brand, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = RR.text,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// MARK: 요약 — 단계 타임라인

private data class Segment(val phase: TrainingGuide.Phase, val weeks: Int, val isCurrent: Boolean)

/// 이번 주~대회 주간을 연속된 단계 구간으로 묶는다 (구간 = 단계 + 주 수 + 이번 주 포함 여부)
private fun segments(plan: TrainingPlan): List<Segment> {
    val result = mutableListOf<Segment>()
    for (week in plan.weeks) {
        val phase = week.phase ?: continue
        val last = result.lastOrNull()
        if (last != null && last.phase == phase) {
            result[result.size - 1] = last.copy(weeks = last.weeks + 1, isCurrent = last.isCurrent || week.isCurrent)
        } else {
            result += Segment(phase, 1, week.isCurrent)
        }
    }
    return result
}

@Composable
private fun SummaryCard(plan: TrainingPlan) {
    val taperWeeks = plan.weeks.count { it.phase == TrainingGuide.Phase.taper }
    val taperText = if (taperWeeks > 0) ", ${taperWeeks}주 테이퍼" else ""
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Eyebrow("훈련 단계")
        PhaseTimeline(segments(plan), Modifier.padding(top = 14.dp).height(30.dp))
        Text(
            "피크 주간 ${fmt(plan.peakWeeklyKm, 0)}km 기준 · 10% 룰 점증$taperText",
            style = TextStyle(fontSize = 11.5.sp).lineSpacing(3),
            color = RR.text3,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/// 단계 구간을 주 수 비례 너비의 캡슐로 — 짧은 구간(대회 주간 1주)도 라벨이 읽히게
/// 구간마다 최소 너비를 먼저 주고 남는 폭을 주 수 비례로 나눈다
@Composable
private fun PhaseTimeline(segments: List<Segment>, modifier: Modifier) {
    val spacing = 4.dp
    val minWidth = 40.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val total = maxOf(1, segments.sumOf { it.weeks })
        val usable = maxWidth - spacing * maxOf(0, segments.size - 1)
        val spare = maxOf(0.dp, usable - minWidth * segments.size)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
            for (segment in segments) {
                Column(
                    Modifier.width(minWidth + spare * segment.weeks / total),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(if (segment.isCurrent) RR.brand else RR.brandSoft, RoundedCornerShape(50)),
                    )
                    FitText(
                        segment.phase.label,
                        TextStyle(fontSize = 10.5.sp, fontWeight = if (segment.isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                        if (segment.isCurrent) RR.brand else RR.text2,
                        minScale = 0.7f,
                    )
                }
            }
        }
    }
}

// MARK: - 주차 행

@Composable
private fun WeekRow(week: TrainingPlan.Week) {
    Column(Modifier.padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                week.label,
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (week.phase == null) RR.text3 else RR.text,
            )
            if (week.isCurrent) Capsule("이번 주", foreground = RR.brand, background = RR.brandSoft)
            Spacer(Modifier.weight(1f))
            week.phase?.let { Capsule(it.label, foreground = RR.text2, background = RR.surface2) }
        }

        if (week.phase == null) {
            Text("실제 ${Format.km(week.actualKm ?: 0.0)}km", style = TextStyle(fontSize = 12.5.sp), color = RR.text3)
        } else {
            Text(planText(week), style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
            if (week.phase != TrainingGuide.Phase.raceWeek) {
                Text(qualityText(week), style = TextStyle(fontSize = 12.sp), color = RR.text3)
            }
        }

        val actual = week.actualKm
        val high = week.weeklyKmHigh
        if (week.isCurrent && actual != null && high != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "지금까지 ${Format.km(actual)}km",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = RR.brand,
                    softWrap = false,
                )
                ProgressCapsule(if (high > 0) minOf(1.0, actual / high) else 0.0, Modifier.weight(1f))
            }
        }
    }
}

/// "주 30.0~33.0km · 롱런 7.5~11.6km" / 대회 주간은 "대회 주간 — 가볍게 12.0~15.0km"
private fun planText(week: TrainingPlan.Week): String {
    val weekly = kmRangeText(week.weeklyKmLow ?: 0.0, week.weeklyKmHigh ?: 0.0)
    if (week.phase == TrainingGuide.Phase.raceWeek) return "대회 주간 — 가볍게 ${weekly}km"
    return "주 ${weekly}km · 롱런 ${kmRangeText(week.lsdKmLow ?: 0.0, week.lsdKmHigh ?: 0.0)}km"
}

/// "템포 1 · 인터벌 1" — 0회는 생략, 둘 다 0이면 가볍게
private fun qualityText(week: TrainingPlan.Week): String {
    val parts = mutableListOf<String>()
    if (week.tempoCount > 0) parts += "템포 ${week.tempoCount}"
    if (week.intervalCount > 0) parts += "인터벌 ${week.intervalCount}"
    return if (parts.isEmpty()) "퀄리티 없이 가볍게" else parts.joinToString(" · ")
}

/// "30.0~33.0" / 상·하한이 같으면 "65.0" 하나만
private fun kmRangeText(low: Double, high: Double): String =
    if (high - low < 0.05) Format.km(low) else "${Format.km(low)}~${Format.km(high)}"

@Composable
private fun Capsule(text: String, foreground: Color, background: Color) {
    Text(
        text,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/// 이번 주 계획 상한 대비 진행 막대 — 1을 넘으면 꽉 찬 채로 둔다
@Composable
private fun ProgressCapsule(ratio: Double, modifier: Modifier) {
    Box(modifier.height(5.dp).background(RR.brandSoft, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(ratio.toFloat()).height(5.dp).background(RR.brand, RoundedCornerShape(50)))
    }
}

/// SwiftUI `.lineSpacing(n)` — 줄 높이를 글자 크기 1.2배 + n으로 잡는다
private fun TextStyle.lineSpacing(spacing: Int): TextStyle = copy(lineHeight = (fontSize.value * 1.2f + spacing).sp)
