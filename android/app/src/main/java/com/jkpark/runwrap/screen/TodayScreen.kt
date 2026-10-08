package com.jkpark.runwrap.screen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.containerViewModel
import com.jkpark.runwrap.engine.AirGrade
import com.jkpark.runwrap.engine.AirQuality
import com.jkpark.runwrap.engine.AirQualityEngine
import com.jkpark.runwrap.engine.CurrentWeather
import com.jkpark.runwrap.engine.HourlyWeather
import com.jkpark.runwrap.engine.OutfitItem
import com.jkpark.runwrap.engine.OutfitRules
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RunName
import com.jkpark.runwrap.engine.RunWindow
import com.jkpark.runwrap.engine.RunWindowEngine
import com.jkpark.runwrap.engine.WeatherAdviceRules
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.store.AirQualityStore
import com.jkpark.runwrap.store.WeatherStore
import com.jkpark.runwrap.ui.Eyebrow
import com.jkpark.runwrap.ui.FitText
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.ToneBadge
import com.jkpark.runwrap.ui.WeatherCondition
import com.jkpark.runwrap.ui.color
import com.jkpark.runwrap.ui.mono
import com.jkpark.runwrap.ui.rrCard
import com.jkpark.runwrap.ui.softColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/// '오늘' 화면 — 현재 위치의 날씨 수치와 조건에 맞는 러닝 복장 추천 (기획서 §4.10, 계획서 M6).
/// 복장은 SF 심볼 + 라벨 칩으로 표현한다 — 전용 일러스트는 후속 제작 항목.
/// 날씨·위치는 앱 전역 WeatherStore의 값을 그대로 그린다 — 홈 타일과 같은 값(이슈 #159).
/// (Android: iOS 시트 → 전체 화면 route. 상단 '닫기'는 iOS 시트의 툴바 버튼, 시스템 뒤로가기도 같다.
/// 위치 권한은 스토어가 묻지 않으므로 이 화면이 요청한다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onBack: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val weather = container.weather
    val model = containerViewModel { TodayViewModel(it.weather, it.context) }
    val context = LocalContext.current
    val state by weather.state.collectAsStateWithLifecycle()
    val servicesDisabled by weather.servicesDisabled.collectAsStateWithLifecycle()
    val airState by model.airQuality.state.collectAsStateWithLifecycle()
    val isReloading by model.isReloading.collectAsStateWithLifecycle()
    val now = Instant.now()
    val zone = ZoneId.systemDefault()

    // 권한 결과와 무관하게 결론을 다시 낸다 — 거부면 스토어가 Denied로 끝난다.
    // 기동 조회가 이미 거부로 결론 났으면 load()는 아무것도 안 하므로 refresh()로 다시 잡는다
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        container.scope.launch { if (weather.isSettled) weather.refresh() else weather.load() }
    }
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            container.scope.launch { weather.load() }
        } else {
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    // WeatherStore는 기동 때 이미 조회를 마쳤다 — 날씨는 다시 부르지 않고 같은 좌표로 대기질만 채운다.
    // 날씨 결론 전에 화면이 열리면 좌표가 아직 없다 — 결론이 나는 순간 다시 돌아 대기질을 채운다
    val settled = state !is WeatherStore.State.Idle && state !is WeatherStore.State.Loading
    LaunchedEffect(settled) {
        val coordinate = weather.coordinate.value ?: return@LaunchedEffect
        model.airQuality.refresh(coordinate.lat, coordinate.lon)
    }

    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack) { Text("닫기", style = TextStyle(fontSize = 17.sp)) }
        }
        PullToRefreshBox(isRefreshing = isReloading, onRefresh = model::reload, modifier = Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Eyebrow(todayEyebrow(now.atZone(zone)))
                    Text("오늘, 달리기 좋을까", style = RR.display(33.sp), color = RR.text)
                }

                // 새로고침 중 일시 실패로 이미 떠 있는 카드를 지우지 않는 규칙은 WeatherStore.refresh()가 쥔다
                when (val s = state) {
                    WeatherStore.State.Denied -> DeniedCard(servicesDisabled, context)
                    WeatherStore.State.Unavailable -> NoticeCard(
                        "날씨를 불러오지 못했어요",
                        "네트워크 상태를 확인하고 화면을 아래로 당겨 새로고침해 주세요.",
                        "icloud.slash",
                    )
                    WeatherStore.State.Idle, WeatherStore.State.Loading -> LoadingCard()
                    is WeatherStore.State.Loaded -> {
                        WeatherCard(s.weather)
                        // 시간대별 예보가 24칸이 안 되면 카드 전체를 내지 않는다 (미노출 가드, 이슈 #173)
                        if (s.weather.hourly.size >= 24) HourlyCard(s.weather.hourly, now)
                        // 대기질은 부가 정보 — 로딩·실패 상태는 자리조차 만들지 않는다 (미노출 가드)
                        (airState as? AirQualityStore.State.Loaded)?.let { AirQualityCard(it.quality) }
                        AdviceCard(s.weather)
                        OutfitCard(s.weather, now, zone)
                    }
                }

                Attribution(airState is AirQualityStore.State.Loaded, context)
            }
        }
    }
}

/// 화면 소유 상태 — 대기질 스토어(홈과 별도 인스턴스, iOS @StateObject 그대로)와 새로고침 중복 방지.
/// 새로고침은 viewModelScope에서 돌아 재구성으로 취소되지 않는다 (iOS의 비구조 Task 대응)
private class TodayViewModel(private val weather: WeatherStore, context: Context) : ViewModel() {
    val airQuality = AirQualityStore(context)
    /// 당겨서 새로고침이 겹치면 조회가 이중으로 나간다 — 한 번에 하나만
    private val _isReloading = MutableStateFlow(false)
    val isReloading = _isReloading.asStateFlow()

    /// 당겨서 새로고침 — 전역 WeatherStore가 위치부터 다시 잡아 날씨를 갱신하면(홈 타일도 같이 바뀐다)
    /// 그 새 좌표로 대기질을 조회한다. 대기질은 새 위치 결론이 재료라 날씨 뒤에 순서대로 (HomeScreen과 같은 순서)
    fun reload() {
        if (_isReloading.value) return
        _isReloading.value = true
        viewModelScope.launch {
            try {
                weather.refresh()
                val coordinate = weather.coordinate.value ?: return@launch
                airQuality.refresh(coordinate.lat, coordinate.lon)
            } finally {
                _isReloading.value = false
            }
        }
    }
}

/// "8.14 (목) · 오늘" — iOS ko_KR "M.d (E)"
private fun todayEyebrow(t: ZonedDateTime): String =
    "${t.monthValue}.${t.dayOfMonth} (${"월화수목금토일"[t.dayOfWeek.value - 1]}) · 오늘"

// MARK: 날씨 카드

@Composable
private fun WeatherCard(weather: CurrentWeather) {
    Column(Modifier.rrCard().padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column {
                Text("기온", style = TextStyle(fontSize = 11.sp), color = RR.text3)
                // 실제 기온이 주 숫자, 체감은 옆에 작게 (이슈 #220 — 날씨 앱에서 익숙한 값이 기온이다)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(fmt(weather.temperatureC, 0), Modifier.alignByBaseline(), style = RR.numeral(56.sp), color = RR.text)
                    Text("°C", Modifier.alignByBaseline(),
                         style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = RR.text2)
                    Text("체감 ${fmt(weather.apparentC, 0)}°", Modifier.alignByBaseline().padding(start = 4.dp),
                         style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = RR.text3)
                }
            }
            Spacer(Modifier.weight(1f))
            WeatherCondition.of(weather.weatherCode)?.let { condition ->
                Column(Modifier.padding(top = 8.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(RRIcons.named(condition.symbol), null, Modifier.size(38.dp), tint = condition.tint)
                    Text(condition.label, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                         color = RR.text2)
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 14.dp), thickness = Dp.Hairline, color = RR.line)

        Row(Modifier.padding(top = 2.dp)) {
            Metric("습도", fmt(weather.humidityPct, 0), "%")
            Metric("바람", fmt(weather.windMs, 1), "m/s")
            Metric("강수", fmt(weather.precipitationMm, 1), "mm")
            weather.uvIndex?.let { Metric("자외선", fmt(it, 0), uvLevel(it)) }
        }
    }
}

/// 자외선지수 등급 라벨 — WHO UV Index 구간 기준
private fun uvLevel(uv: Double): String = when {
    uv < 3 -> "낮음"
    uv < 6 -> "보통"
    uv < 8 -> "높음"
    uv < 11 -> "매우 높음"
    else -> "위험"
}

@Composable
private fun RowScope.Metric(label: String, value: String, unit: String) {
    Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = TextStyle(fontSize = 11.sp), color = RR.text3)
        FitText(value, mono(18.sp, FontWeight.Bold), RR.text, minScale = 0.6f)
        Text(unit, style = mono(10.5.sp), color = RR.text3)
    }
}

// MARK: 시간대별 카드 (이슈 #173)

/// 달리기 좋은 시간 추천 + 24시간 띠. 좋은 칸(RunWindowEngine.isGood)은 요약 구간에 들었는지와 상관없이
/// 모두 improving 톤으로 칠한다 (이슈 #219 §2) — 오늘 구간이 있어도 띠에 보이는 내일 좋은 칸까지
@Composable
private fun HourlyCard(hourly: List<HourlyWeather>, now: Instant) {
    val windows = RunWindowEngine.windows(hourly, now)
    val ranges = RunWindowEngine.rangesLabel(windows)
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Eyebrow("시간대별")
        if (ranges != null) {
            Text("달리기 좋은 시간 $ranges", Modifier.padding(top = 8.dp),
                 style = RR.display(22.sp), color = RR.text)
            // 구간마다 한 줄 — 구간이 여럿이면 어느 구간 값인지 앞에 시간대를 붙인다
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                windows.forEach { WindowSummary(it, showsRange = windows.size > 1) }
            }
        } else {
            Text("오늘은 딱 좋은 시간대가 없어요 — 실내도 괜찮아요", Modifier.padding(top = 8.dp),
                 style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = RR.text2)
        }

        Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hourly.forEach { hour ->
                HourCell(hour,
                         isNow = hour.time <= now && now < hour.time.plusSeconds(3_600),
                         isGood = RunWindowEngine.isGood(hour))
            }
        }
    }
}

/// 추천 구간 한 줄 — "6~9시 12° 체감 10° · 비 10%". 실제 기온이 주 숫자, 체감은 작게 (이슈 #220)
@Composable
private fun WindowSummary(window: RunWindow, showsRange: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showsRange) {
            Text(RunWindowEngine.rangeLabel(window), Modifier.alignByBaseline(),
                 style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = RRTone.improving.color)
        }
        Text("${window.temperatureC.swiftRoundedInt()}°", Modifier.alignByBaseline(),
             style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = RR.text2)
        Text("체감 ${window.apparentC.swiftRoundedInt()}°", Modifier.alignByBaseline(),
             style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium), color = RR.text3)
        Text("· 비 ${window.precipitationProbabilityPct}%", Modifier.alignByBaseline(),
             style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = RR.text2)
    }
}

/// 한 칸 — 시각·하늘 상태·기온·강수확률. 칸이 좁아 체감은 뺀다 (이슈 #220)
@Composable
private fun HourCell(hour: HourlyWeather, isNow: Boolean, isGood: Boolean) {
    Column(
        Modifier
            .background(if (isGood) RRTone.improving.softColor else Color.Transparent, RoundedCornerShape(10.dp))
            .width(46.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(if (isNow) "지금" else "${hour.time.atZone(RunWindowEngine.kst).hour}시",
             style = TextStyle(fontSize = 11.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal),
             color = if (isNow) RR.text else RR.text3)
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            WeatherCondition.of(hour.weatherCode)?.let {
                Icon(RRIcons.named(it.symbol), null, Modifier.size(18.dp), tint = it.tint)
            }
        }
        Text("${hour.temperatureC.swiftRoundedInt()}°", style = mono(14.sp, FontWeight.Bold), color = RR.text)
        Text("${hour.precipitationProbabilityPct}%", style = mono(10.5.sp),
             color = if (hour.precipitationProbabilityPct >= 30) RR.sky else RR.text3)
    }
}

// MARK: 대기질 카드 (이슈 #8)

/// 에어코리아 원 수치·공식 등급을 가공 없이 그대로 보여준다 — KOGL 제3유형(변경금지) 준수.
/// 배지의 대표 등급도 공식 등급 중에서 고를 뿐(AirQualityEngine.representativeGrade) 새로 만들지 않는다
@Composable
private fun AirQualityCard(air: AirQuality) {
    Column(Modifier.fillMaxWidth().rrCard().padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 14.dp)) {
        Text("지금 공기질", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text)

        AirQualityEngine.representativeGrade(air)?.let { grade ->
            Box(Modifier.padding(top = 12.dp)) { ToneBadge(grade.tone, grade.label, "에어코리아") }
        }

        // 결측 항목(통신장애·점검)은 줄 자체를 내지 않는다 — 미노출 가드
        Column(Modifier.padding(top = 7.dp)) {
            AirRow("초미세먼지 PM2.5", air.pm25, "㎍/㎥", 0, air.pm25Grade)
            AirRow("미세먼지 PM10", air.pm10, "㎍/㎥", 0, air.pm10Grade)
            AirRow("오존 O₃", air.o3, "ppm", 3, air.o3Grade)
            AirRow("통합대기환경지수", air.khai, "CAI", 0, air.khaiGrade)
        }

        HorizontalDivider(thickness = Dp.Hairline, color = RR.line)

        // 측정 기준 표기 — 어느 측정소의 언제 값인지 밝힌다 (수치의 신뢰 근거)
        Text(listOfNotNull(air.stationName + " 측정소", air.dataTime?.let { "$it 기준" }).joinToString(" · "),
             Modifier.padding(top = 10.dp), style = TextStyle(fontSize = 10.5.sp), color = RR.text3)
    }
}

@Composable
private fun AirRow(name: String, value: Double?, unit: String, digits: Int, grade: AirGrade?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = TextStyle(fontSize = 12.sp), color = RR.text2)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Text(fmt(value, digits), style = mono(14.sp, FontWeight.Bold), color = RR.text)
        // 단위 폭 고정 — 수치 우측 정렬 유지
        Text(unit, Modifier.width(38.dp), style = mono(10.5.sp), color = RR.text3)
        Text(grade?.label ?: "—", Modifier.width(52.dp),
             style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
             color = grade?.tone?.color ?: RR.text3)
    }
}

// MARK: 조언 카드

@Composable
private fun AdviceCard(weather: CurrentWeather) {
    val name = WeatherAdviceRules.runName(weather.temperatureC, weather.precipitationMm, weather.weatherCode)
    val items = WeatherAdviceRules.advice(weather.temperatureC, weather.humidityPct, weather.windMs,
                                          weather.precipitationMm, weather.uvIndex, weather.weatherCode)
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("오늘의 러닝", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text)

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(name.tone.softColor, CircleShape), contentAlignment = Alignment.Center) {
                Icon(RRIcons.named(runSymbol(name.kind)), null, Modifier.size(26.dp), tint = name.tone.color)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name.title, style = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.ExtraBold), color = RR.text)
                Text(name.quip, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = RR.text2)
            }
        }

        HorizontalDivider(Modifier.padding(top = 15.dp), thickness = Dp.Hairline, color = RR.line)

        Column(Modifier.padding(top = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(5.dp).background(item.tone.color, CircleShape))
                    Text(item.text, style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp), color = RR.text3)
                }
            }
        }
    }
}

/// 러닝 이름 → SF 심볼 — 엔진(RunName)은 UI를 모르므로 매핑은 화면 몫
private fun runSymbol(kind: RunName.Kind): String = when (kind) {
    RunName.Kind.treadmill -> "house.fill"
    RunName.Kind.snow -> "snowflake"
    RunName.Kind.rain -> "umbrella.fill"
    RunName.Kind.sauna -> "flame.fill"
    RunName.Kind.dawn -> "sunrise.fill"
    RunName.Kind.shade -> "tree.fill"
    RunName.Kind.`fun` -> "party.popper.fill"
    RunName.Kind.crisp -> "leaf.fill"
    RunName.Kind.hotpack -> "thermometer.snowflake"
    RunName.Kind.penguin -> "snowflake.circle.fill"
}

// MARK: 복장 카드

@Composable
private fun OutfitCard(weather: CurrentWeather, now: Instant, zone: ZoneId) {
    val items = OutfitRules.outfit(weather.temperatureC, weather.humidityPct, weather.windMs, weather.precipitationMm,
                                   weather.weatherCode, weather.uvIndex, now, zone)
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp)) {
        Text("오늘의 러닝 복장", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = RR.text)
        OutfitGrid(items, Modifier.padding(top = 14.dp))

        // +10°C로 고른 복장이라 출발 직후는 쌀쌀하다는 걸 미리 말해 둔다 (이슈 #219)
        OutfitRules.startChillNote(weather.temperatureC)?.let { note ->
            Text(note, Modifier.padding(top = 12.dp),
                 style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp), color = RR.text3)
        }
    }
}

/// 복장 아이템 타일 그리드 — 아이콘·라벨 매핑은 화면 몫 (OutfitRules는 UI를 모른다).
/// 칩 나열 대신 큰 아이콘 타일로 보여준다 (확장 요구, 2026-08-12).
/// (Android: iOS LazyVGrid(.adaptive(minimum: 68), spacing 9)와 같은 열 수 계산을 고정 그리드로 — 스크롤 안 Lazy 중첩 금지)
@Composable
private fun OutfitGrid(items: List<OutfitItem>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxOf(1, ((maxWidth + 9.dp) / 77.dp).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { OutfitTile(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OutfitTile(item: OutfitItem, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().height(62.dp).background(RR.brandSoft, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            val brand = RR.brand
            // 타일 아이콘 — SF Symbols에 없는 복장(싱글렛·반바지)만 시안 SVG 경로를 옮긴 커스텀 셰이프로 그린다
            when (item) {
                OutfitItem.singlet, OutfitItem.shorts -> Canvas(Modifier.size(30.dp)) {
                    drawPath(garmentPath(item, size.width / 26), brand,
                             style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                else -> Icon(RRIcons.named(outfitSymbol(item)), null, Modifier.size(30.dp), tint = brand)
            }
        }
        FitText(item.label, TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), RR.text, minScale = 0.8f)
    }
}

/// 같은 조합에 함께 나오는 아이템끼리는 심볼이 겹치지 않게 배정했다
/// (타이츠·방한 하의처럼 동시에 안 나오는 쌍만 심볼을 공유).
/// 라벨은 `OutfitItem.label`에 있다 — 홈 판단 카드와 같은 표기를 쓰기 위해서다
/// (Android: iOS 18 심볼 hat.cap.fill·jacket.fill 대신 iOS 17 폴백을 항상 쓴다 — RRIcons에 없다)
private fun outfitSymbol(item: OutfitItem): String = when (item) {
    OutfitItem.shortSleeve -> "tshirt"
    OutfitItem.longSleeve -> "tshirt.fill"
    OutfitItem.tights -> "figure.run"
    OutfitItem.jacket -> "wind"
    OutfitItem.gloves -> "hand.raised.fill"
    OutfitItem.windbreaker -> "wind"
    OutfitItem.waterproofCap -> "umbrella"
    OutfitItem.waterproofJacket -> "cloud.rain"
    OutfitItem.thermalTop -> "tshirt.fill"
    OutfitItem.thermalBottom -> "figure.run"
    OutfitItem.beanie -> "snowflake"
    OutfitItem.neckWarmer -> "thermometer.snowflake"
    OutfitItem.sunCap -> "sun.max.fill"
    OutfitItem.sunglasses -> "sunglasses.fill"
    OutfitItem.sunscreen -> "drop.fill"
    OutfitItem.singlet, OutfitItem.shorts -> error("셰이프로 그린다")
}

/// 시안 '오늘' 화면의 복장 아이콘 SVG(26×26 viewBox) 경로를 그대로 옮긴 셰이프 —
/// SF Symbols에는 민소매·반바지에 해당하는 심볼이 없다 (iOS 18 기준). [u]는 viewBox 한 칸의 픽셀 크기
private fun garmentPath(item: OutfitItem, u: Float): Path = Path().apply {
    if (item == OutfitItem.singlet) {
        // 어깨끈 사이 목선이 파인 민소매 실루엣
        moveTo(9 * u, 4.5f * u)
        cubicTo(10.6f * u, 6.7f * u, 15.4f * u, 6.7f * u, 17 * u, 4.5f * u)
        lineTo(17 * u, 21.5f * u)
        lineTo(9 * u, 21.5f * u)
        close()
    } else {
        // 허리에서 양쪽 밑단으로 벌어지는 반바지 실루엣 + 허리 밴드 선
        moveTo(7.5f * u, 6.5f * u)
        lineTo(18.5f * u, 6.5f * u)
        lineTo(20.3f * u, 17.5f * u)
        lineTo(14.7f * u, 17.5f * u)
        lineTo(13 * u, 11 * u)
        lineTo(11.3f * u, 17.5f * u)
        lineTo(5.7f * u, 17.5f * u)
        close()
        moveTo(7.5f * u, 9.2f * u)
        lineTo(18.5f * u, 9.2f * u)
    }
}

// MARK: 안내·로딩

@Composable
private fun NoticeCard(title: String, message: String, symbol: String) {
    Column(Modifier.fillMaxWidth().rrCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RRIcons.named(symbol), null, Modifier.size(16.dp), tint = RR.text3)
            Text(title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = RR.text)
        }
        Text(message, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp), color = RR.text3)
    }
}

/// 위치 거부 안내 + 설정 바로가기 (이슈 #94) — 앱 안에서는 다시 물을 수 없어 설정으로 보낸다.
/// 휴대전화 전체 위치 서비스가 꺼진 경우도 Denied로 오므로 문구와 이동처를 시스템 스위치 쪽으로 바꾼다
@Composable
private fun DeniedCard(servicesDisabled: Boolean, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (servicesDisabled) {
            NoticeCard("휴대전화의 위치 서비스가 꺼져 있어요",
                       "설정 > 위치를 켜면 현재 위치의 날씨와 복장 추천을 볼 수 있어요.",
                       "location.slash")
        } else {
            NoticeCard("위치 권한이 꺼져 있어요",
                       "설정 > 런미새에서 위치 접근을 허용하면 현재 위치의 날씨와 복장 추천을 볼 수 있어요.",
                       "location.slash")
        }
        // iOS .bordered 버튼 — 옅은 틴트 채움 + 브랜드 글자
        Row(
            Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()   // 터치 영역 48dp (iOS .bordered는 자체 여백이 더해져 더 높다)
                .background(RR.brandSoft, RoundedCornerShape(10.dp))
                .clickable {
                    context.startActivity(
                        if (servicesDisabled) Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null))
                    )
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RRIcons.named("gearshape"), null, Modifier.size(16.dp), tint = RR.brand)
            Text("설정 열기", style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = RR.brand)
        }
    }
}

@Composable
private fun LoadingCard() {
    Row(Modifier.fillMaxWidth().rrCard().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = RR.text3, strokeWidth = 2.dp)
        Text("현재 위치의 날씨를 불러오는 중", style = TextStyle(fontSize = 12.5.sp), color = RR.text3)
    }
}

/// 출처 표기 — Open-Meteo는 CC BY 4.0 조건 (계획서 M6),
/// 대기질은 KOGL 제3유형의 출처표시 의무 (이슈 #8) — 카드가 보일 때만 함께 표기한다
@Composable
private fun Attribution(showsAirQuality: Boolean, context: Context) {
    @Composable
    fun link(text: String, url: String) = Text(
        text,
        Modifier.clickable {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                // 열 앱이 없으면 아무 일도 없다 (iOS Link와 같다)
            }
        },
        style = TextStyle(fontSize = 10.5.sp, textDecoration = TextDecoration.Underline),
        color = RR.text3,
    )
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        link("Weather data by Open-Meteo.com (CC BY 4.0)", "https://open-meteo.com")
        if (showsAirQuality) link("대기질 자료: 한국환경공단 에어코리아 (공공누리 제3유형)", "https://www.airkorea.or.kr")
    }
}
