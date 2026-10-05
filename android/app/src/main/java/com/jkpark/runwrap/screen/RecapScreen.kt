package com.jkpark.runwrap.screen

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.PersonalRecords
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.Recap
import com.jkpark.runwrap.engine.RecapEngine
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.ShareCardRenderer
import com.jkpark.runwrap.ui.ToneBadge
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/// 월간·연간 결산 리캡 (이슈 #167) — 홈 결산 카드·리포트 '이번달'의 결산 버튼이 연다.
///
/// 세로 페이지 넘김이 아니라 스크롤 카드 나열이다. 내용은 전부 `RecapEngine`이 정하고
/// 여기서는 그린다 — 표본이 없는 카드는 엔진이 null·빈 목록으로 내므로 자리조차 만들지 않는다.
/// 하단 "이미지로 저장"은 세션 공유 카드와 같은 렌더·저장 경로(ShareCardRenderer)를 쓴다.
/// (Android: iOS 시트 → 전체 화면 route. 공유 이미지는 화면과 같은 라이트/다크로 그려진다 — RR 토큰이 시스템 모드를 따른다)
@Composable
fun RecapScreen(
    period: RecapPeriod,
    onBack: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val health = container.health
    val settings = container.settings
    val levelRaw by settings.rememberSetting(ProfileKey.levelV2, RunnerLevel.beginner.rawValue)
    // 심박 기준 (이슈 #56) — 강도 배분 카드의 존 경계. 해석은 리포트 탭과 같은 엔진 한 곳
    val hrMaxManual by settings.rememberSetting(ProfileKey.hrMaxManual, 0)
    val restingHRManual by settings.rememberSetting(ProfileKey.restingHRManual, 0)
    val hrZoneMethodRaw by settings.rememberSetting(ProfileKey.hrZoneMethod, "")
    val healthState by health.state.collectAsStateWithLifecycle()
    val hrMaxEstimate by health.hrMaxEstimate.collectAsStateWithLifecycle()
    val restingHRBpm by health.restingHRBpm.collectAsStateWithLifecycle()
    val bestEfforts by health.bestEfforts.collectAsStateWithLifecycle()
    val zoneHistograms by health.zoneHistograms.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    val recap = remember(period, healthState, hrMaxEstimate, restingHRBpm, bestEfforts, zoneHistograms,
                         levelRaw, hrMaxManual, restingHRManual, hrZoneMethodRaw) {
        val runs = (healthState as? HealthStore.State.Loaded)?.runs ?: return@remember null
        val heartRate = TrainingGuideEngine.heartRateProfile(hrMaxEstimate, hrMaxManual, restingHRManual,
                                                             restingHRBpm, hrZoneMethodRaw)
        RecapEngine.compute(period, runs, bestEfforts, zoneHistograms, heartRate,
                            RunnerLevel.fromRawValue(levelRaw) ?: RunnerLevel.beginner, Instant.now(), zone)
    }
    val isMonth = period is RecapPeriod.month

    // 시스템 '애니메이션 삭제'면 Compose 애니메이션이 즉시 끝난다 — iOS 모션 줄이기 분기 대응 (이슈 #212)
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        // 화면에 그리지 않고 공유 카드를 기록해 둔다 — 저장 버튼이 이 레이어를 이미지로 뽑는다.
        // 카드 나열(Column) 밖에 둬야 0 크기 자식이 간격(12dp)을 먹지 않는다
        recap?.let { ShareCardRenderer.Source(layer) { RecapShareCardView(it, zone) } }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(isMonth, recap?.title ?: (RecapEngine.periodLabel(period, zone) + " 결산"), onBack)
            if (recap != null) {
                Content(recap, isMonth, zone, appeared, layer)
            } else {
                Text("기록 3회 이상이면 결산이 열립니다",
                     Modifier.fillMaxWidth().rrCard().padding(vertical = 28.dp),
                     style = TextStyle(fontSize = 13.5.sp, textAlign = TextAlign.Center),
                     color = RR.text3)
            }
        }
    }
}

// MARK: 헤더

@Composable
private fun Header(isMonth: Boolean, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow(if (isMonth) "월간 결산" else "연간 결산")
            Text(title, style = RR.display(30.sp), color = RR.text)
        }
        Box(
            Modifier
                .minimumInteractiveComponentSize()   // iOS rrTapTarget — 탭 영역 48dp (이슈 #212)
                .size(34.dp)
                .clip(CircleShape)
                .background(RR.surface)
                .border(1.dp, RR.line, CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "닫기" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RRIcons.named("xmark"), null, Modifier.size(16.dp), tint = RR.text2)
        }
    }
}

// MARK: 카드 나열 — 순서 고정 (총량 → 하이라이트 → 새 기록 → 강도 배분 → 마무리)

@Composable
private fun Content(recap: Recap, isMonth: Boolean, zone: ZoneId, shown: Boolean, layer: GraphicsLayer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveMessage by remember { mutableStateOf<String?>(null) }
    TotalsCard(recap, isMonth, Modifier.reveal(0, shown))
    recap.highlights?.let { HighlightsCard(it, zone, Modifier.reveal(1, shown)) }
    if (recap.records.isNotEmpty()) RecordsCard(recap.records, zone, Modifier.reveal(2, shown))
    recap.intensity?.let { IntensityCard(it, Modifier.reveal(3, shown)) }
    Text(recap.closingLine,
         Modifier.reveal(4, shown).fillMaxWidth().rrCard().padding(18.dp),
         style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.5.sp), color = RR.text)

    // 이미지 저장 — SessionDetail 공유 시트와 같은 저장 안내(아래 캡션)
    Column(Modifier.reveal(5, shown).padding(top = 8.dp).fillMaxWidth(),
           horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(RR.brand, RoundedCornerShape(14.dp))
                .clickable {
                    scope.launch {
                        val image = ShareCardRenderer.render(layer)
                        saveMessage = if (image == null) {
                            "이미지를 만들지 못했어요 — 잠시 후 다시 시도해 주세요"
                        } else try {
                            ShareCardRenderer.saveToPhotos(context, image)
                            "사진 앱에 저장했어요"
                        } catch (e: Exception) {
                            "저장하지 못했어요 — 기기 저장 공간을 확인해 주세요"
                        }
                    }
                }
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RRIcons.named("square.and.arrow.down"), null, Modifier.size(17.dp), tint = RR.onBrand)
            Text("이미지로 저장", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = RR.onBrand)
        }
        saveMessage?.let { Text(it, style = TextStyle(fontSize = 12.sp), color = RR.text3) }
    }
}

/// 카드 등장 — 순서대로 살짝 늦게 fade + slide up
@Composable
private fun Modifier.reveal(index: Int, shown: Boolean): Modifier {
    val p by animateFloatAsState(if (shown) 1f else 0f,
                                 tween(350, delayMillis = index * 60, easing = EaseOut), label = "reveal")
    return graphicsLayer { alpha = p; translationY = (1 - p) * 14.dp.toPx() }
}

@Composable
private fun CardLabel(text: String) = Text(text, style = TextStyle(fontSize = 12.sp), color = RR.text3)

@Composable
private fun TotalsCard(recap: Recap, isMonth: Boolean, modifier: Modifier) {
    val totals = recap.totals
    Column(modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CardLabel("총량")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // 런린이는 거리 대신 횟수를 크게 (ReportGate §4 "문장만")
            if (totals.showsDistance) BigNumber(Format.km(totals.distanceKm), "km")
            else BigNumber("${totals.count}", "회")
            Spacer(Modifier.weight(1f))
            val delta = recap.deltaPct
            if (totals.showsDistance && delta != null) {
                Text("${if (isMonth) "지난달 대비" else "지난해 대비"} ${if (delta >= 0) "+" else "−"}${fmt(abs(delta), 0)}%",
                     style = mono(11.sp), color = if (delta >= 0) RR.pos else RR.text2)
            }
        }
        Row {
            if (totals.showsDistance) Stat("러닝 횟수", "${totals.count}", "회")
            Stat("총 시간", Format.duration(totals.durationSec), "h:m:s")
        }
    }
}

@Composable
private fun BigNumber(value: String, unit: String) {
    Text(buildAnnotatedString {
        withStyle(RR.numeral(52.sp).toSpanStyle().copy(color = RR.text)) { append(value) }
        withStyle(SpanStyle(fontSize = 15.sp, color = RR.text3)) { append(" $unit") }
    })
}

@Composable
private fun RowScope.Stat(label: String, value: String, unit: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
        // 단위는 em이라 자동 축소(minScale 0.7)에 값과 같이 줄어든다 (11/20)
        BasicText(
            buildAnnotatedString {
                append(value)
                withStyle(SpanStyle(fontSize = 0.55.em, fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.Normal, color = RR.text3)) { append(" $unit") }
            },
            style = mono(20.sp, FontWeight.Bold).copy(color = RR.text), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp),
        )
    }
}

@Composable
private fun HighlightsCard(highlights: Recap.Highlights, zone: ZoneId, modifier: Modifier) {
    Column(modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardLabel("하이라이트")
        highlights.longest?.let { longest ->
            longest.distanceKm?.let { km ->
                RecapRow("road.lanes", RR.brand, "최장 런", recapDay(longest.start, zone), "${Format.km(km)} km",
                         TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), RR.text)
            }
        }
        highlights.fastest?.let { fastest ->
            fastest.paceSecPerKm?.let { pace ->
                RecapRow("bolt.fill", RR.brand, "가장 빠른 세션", recapDay(fastest.start, zone), Format.paceKm(pace),
                         TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), RR.text)
            }
        }
    }
}

@Composable
private fun RecordsCard(records: List<PersonalRecords.Entry>, zone: ZoneId, modifier: Modifier) {
    Column(modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardLabel("새 기록")
        records.forEach { entry ->
            RecapRow("medal.fill", RR.medalColor(forPB = entry.label), entry.label, recapDay(entry.date, zone),
                     Format.duration(entry.timeSec), mono(13.sp, FontWeight.Bold), RR.brand, iconSize = 19.dp)
        }
    }
}

/// 하이라이트·새 기록 한 줄 — 아이콘 · 제목/날짜 · 값 (iOS highlightRow와 recordsCard 행이 같은 골격)
@Composable
private fun RecapRow(icon: String, tint: Color, title: String, date: String,
                     value: String, titleStyle: TextStyle, valueColor: Color,
                     iconSize: Dp = 17.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(RRIcons.named(icon), null, Modifier.size(iconSize), tint = tint)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle, color = RR.text)
            Text(date, style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Text(value, style = mono(16.sp, FontWeight.Bold), color = valueColor)
    }
}

@Composable
private fun IntensityCard(intensity: Recap.Intensity, modifier: Modifier) {
    Column(modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToneBadge(intensity.tone)
        CardLabel("강도 배분 · 이지(Z1~Z2) 비율")
        Text(buildAnnotatedString {
            withStyle(RR.numeral(40.sp).toSpanStyle().copy(color = RR.text)) {
                append("${(intensity.easyShare * 100).swiftRoundedInt()}")
            }
            withStyle(SpanStyle(fontSize = 14.sp, color = RR.text3)) { append(" %") }
        })
        Text("심박 기록 ${intensity.sessions}회 기준 · 80% 이상이면 80/20 원칙에 맞아요",
             style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
    }
}

/// "8월 14일 (목)" — 결산 화면·공유 카드의 세션 날짜 (iOS ko_KR "M월 d일 (E)")
private fun recapDay(date: Instant, zone: ZoneId): String {
    val t = date.atZone(zone)
    return "${t.monthValue}월 ${t.dayOfMonth}일 (${"월화수목금토일"[t.dayOfWeek.value - 1]})"
}

// MARK: - 공유 카드

/// 결산 스토리 카드 — ShareCardView와 같은 골격(360×640, 아이브로 → 큰 숫자 → 수치 줄 → 하단 브랜드 줄)
@Composable
private fun RecapShareCardView(recap: Recap, zone: ZoneId) {
    val isMonth = recap.period is RecapPeriod.month
    val totals = recap.totals
    Column(Modifier.size(360.dp, 640.dp).background(RR.bg).padding(28.dp)) {
        Eyebrow("런미새 · " + recap.periodLabel)
        Text(recap.title, Modifier.padding(top = 10.dp), style = RR.display(26.sp), color = RR.text)

        // 런린이는 거리 대신 횟수를 크게 — 결산 화면과 같은 규칙
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (totals.showsDistance) Format.km(totals.distanceKm) else "${totals.count}",
                 Modifier.alignByBaseline(), style = mono(62.sp, FontWeight.ExtraBold), color = RR.text)
            Text(if (totals.showsDistance) "km" else "회",
                 Modifier.alignByBaseline(), style = mono(20.sp, FontWeight.Bold), color = RR.text3)
        }

        Row(Modifier.padding(top = 18.dp)) {
            if (totals.showsDistance) CardStat("러닝 횟수", "${totals.count}", "회")
            CardStat("총 시간", Format.duration(totals.durationSec), "h:m:s")
        }

        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            recap.highlights?.longest?.let { longest ->
                longest.distanceKm?.let { CardLine("최장 런", "${Format.km(it)} km · ${recapDay(longest.start, zone)}") }
            }
            recap.highlights?.fastest?.paceSecPerKm?.let { CardLine("가장 빠른 세션", Format.paceKm(it)) }
            // 연간은 기록이 4~5개까지 쌓일 수 있어 한 줄에 넣지 않고 기록마다 한 줄씩
            recap.records.forEachIndexed { i, record ->
                CardLine(if (i == 0) "새 기록" else "", "${record.label} ${Format.duration(record.timeSec)}")
            }
        }

        Text(recap.closingLine,
             Modifier.padding(top = 24.dp).fillMaxWidth().background(RR.surface2, RoundedCornerShape(14.dp)).padding(16.dp),
             style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp), color = RR.text)

        Spacer(Modifier.weight(1f))

        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isMonth) "MONTHLY RECAP" else "YEARLY RECAP", style = mono(12.sp, FontWeight.SemiBold), color = RR.text2)
            Spacer(Modifier.weight(1f))
            Text("런미새", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = RR.brand)
        }
    }
}

@Composable
private fun RowScope.CardStat(label: String, value: String, unit: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = TextStyle(fontSize = 11.sp), color = RR.text3)
        FitText(value, mono(22.sp, FontWeight.Bold), RR.text, minScale = 0.6f)
        Text(unit, style = mono(10.5.sp), color = RR.text3)
    }
}

@Composable
private fun CardLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.alignByBaseline(), style = TextStyle(fontSize = 12.sp), color = RR.text3)
        Spacer(Modifier.weight(1f).widthIn(min = 10.dp))
        FitText(value, mono(13.sp, FontWeight.Bold), RR.text, minScale = 0.6f, Modifier.alignByBaseline())
    }
}
