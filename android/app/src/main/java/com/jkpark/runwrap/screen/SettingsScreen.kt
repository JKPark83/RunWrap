package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RunPurpose
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.SEOUL
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.ui.NumberWheel
import com.jkpark.runwrap.ui.theme.RR
import com.jkpark.runwrap.ui.theme.rrCard
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/// 설정 — 프로필(목적·레벨)·목표·생년월일 변경. 진입: 홈 헤더의 기어 아이콘.
/// iOS `SettingsScreen.swift` 이식 (계획서 M5).
///
/// iOS와의 차이:
/// - 알림 섹션 없음 — 로컬 알림은 M5 범위 밖 (iOS도 M8에서 붙였다).
/// - 데모 모드 토글 없음 — Android `DemoMode`는 에뮬레이터 자동 판정이라 스위치가 없다.
/// - **생년월일 섹션 추가** — HC에는 생년월일 프로필이 없어 여기서 고칠 수 있게 한다
///   (온보딩에서 스킵한 사용자의 입력 경로, 계획서 §2.2).
@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val levelRaw by settings.levelV2.collectAsState(initial = "")
    val purposesRaw by settings.purposes.collectAsState(initial = "")
    val weeklyGoal by settings.weeklyGoal.collectAsState(initial = 2)
    val raceGoalRaw by settings.raceGoal.collectAsState(initial = "")
    val raceGoalSec by settings.raceGoalSec.collectAsState(initial = 0)
    val raceDate by settings.raceDate.collectAsState(initial = 0L)
    val birthYear by settings.birthYear.collectAsState(initial = 0)

    /// 다시 진단받기 — 설문을 처음부터 다시 받는다. 이전 답을 프리필하지 않는 건 의도다:
    /// 원답은 저장하지 않고 판정 결과만 남기며, 다시 진단하는 이유는 그때와 지금이
    /// 달라졌기 때문이다 (기획서 §7). iOS는 시트, 여기서는 화면 전체를 바꿔 끼운다.
    var isRediagnosing by remember { mutableStateOf(false) }
    if (isRediagnosing) {
        OnboardingFlowScreen(settings, isRediagnosis = true, onFinish = { isRediagnosing = false })
        return
    }

    Column(Modifier.fillMaxSize().background(RR.bg)) {
        // 상단 바 — "설정" (iOS navigationTitle inline 대응)
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
                "설정", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
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
            // 레벨은 설문 결과라 여기서 직접 고르지 않는다 — 다시 진단받아야 바뀐다.
            // 손으로 올릴 수 있게 하면 게이트가 의미를 잃고, 감당 못 할 지표를 보게 된다 (기획서 §7)
            Section("내 레벨") {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            (RunnerLevel.fromStorage(levelRaw) ?: RunnerLevel.BEGINNER).label,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                        )
                        Text(
                            "설문 결과로 정해져요. 실력이 늘면 앱이 먼저 승급을 제안합니다",
                            fontSize = 12.5.sp, color = RR.text2,
                        )
                    }
                    Text(
                        "다시 진단",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RR.brand,
                        modifier = Modifier.clickable { isRediagnosing = true },
                    )
                }
            }

            Section("러닝 목적") {
                val selected = RunPurpose.decode(purposesRaw)
                RunPurpose.entries.forEach { purpose ->
                    OptionRow(
                        label = purpose.label,
                        caption = if (purpose in selected) "선택됨" else " ",
                        isSelected = purpose in selected,
                    ) {
                        // 최소 1개는 남긴다 — 전부 끄면 문장 강조점을 정할 수 없다
                        val updated = when {
                            purpose !in selected -> selected + purpose
                            selected.size > 1 -> selected - purpose
                            else -> return@OptionRow
                        }
                        scope.launch { settings.setPurposes(RunPurpose.encode(updated)) }
                    }
                }
            }

            // 주간 목표 — 성장 XP의 주간 보너스 분모이자 홈 목표 칩의 기준 (기획서 §5)
            Section("주간 러닝 목표") {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "주 ${weeklyGoal}회",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
                        )
                        Text("채우면 새가 자라는 보너스를 받아요", fontSize = 12.5.sp, color = RR.text2)
                    }
                    // 1~7회 — iOS Stepper 대응
                    StepperButton("−", enabled = weeklyGoal > 1) {
                        scope.launch { settings.setWeeklyGoal(weeklyGoal - 1) }
                    }
                    StepperButton("+", enabled = weeklyGoal < 7) {
                        scope.launch { settings.setWeeklyGoal(weeklyGoal + 1) }
                    }
                }
            }

            // 목표 레이스 — 훈련 가이드의 진단 대상. 없음이면 카드 자체가 꺼진다
            Section("목표 레이스") {
                OptionRow("없음", "훈련 가이드 카드를 끕니다", raceGoalRaw.isEmpty()) {
                    scope.launch {
                        settings.setRaceGoal("")
                        settings.setRaceDate(0)
                    }
                }
                RaceDistance.entries.forEach { race ->
                    OptionRow(
                        race.label,
                        "%.1f km".format(Locale.ROOT, race.km),
                        raceGoalRaw == race.storageValue,
                    ) {
                        scope.launch { settings.setRaceGoal(race.storageValue) }
                    }
                }
            }

            if (raceGoalRaw.isNotEmpty()) {
                // 목표 기록 — 시:분:초 휠. 0:00:00이면 미입력으로 취급해 예측만 보여준다
                Section("목표 기록") {
                    WheelRow {
                        NumberWheel(
                            "시간", 0..6, raceGoalSec / 3_600,
                            { scope.launch { settings.setRaceGoalSec(it * 3_600 + raceGoalSec % 3_600) } },
                            Modifier.weight(1f), rowHeight = 36.dp,
                        )
                        NumberWheel(
                            "분", 0..59, raceGoalSec % 3_600 / 60,
                            { scope.launch { settings.setRaceGoalSec(raceGoalSec / 3_600 * 3_600 + it * 60 + raceGoalSec % 60) } },
                            Modifier.weight(1f), rowHeight = 36.dp,
                        )
                        NumberWheel(
                            "초", 0..59, raceGoalSec % 60,
                            { scope.launch { settings.setRaceGoalSec(raceGoalSec / 60 * 60 + it) } },
                            Modifier.weight(1f), rowHeight = 36.dp,
                        )
                    }
                }

                // 대회 날짜 — 있으면 훈련 가이드가 D-day 주기화로 처방을 조절한다 (§4.9)
                Section("대회 날짜") {
                    ToggleRow(
                        label = "대회 날짜로 맞추기",
                        caption = "대회일까지 기초→강화→테이퍼 단계로 처방해요",
                        isOn = raceDate > 0,
                    ) { isOn ->
                        scope.launch {
                            // 켜면 8주 뒤(일반적인 최소 준비 기간)를 기본값으로 넣는다
                            settings.setRaceDate(
                                if (isOn) Instant.now().epochSecond + 8 * 7 * 86_400L else 0
                            )
                        }
                    }
                    if (raceDate > 0) {
                        RaceDateRow(raceDate) { picked ->
                            scope.launch { settings.setRaceDate(picked) }
                        }
                    }
                }
            }

            // 생년월일 (Android 추가) — HRmax(Tanaka) 재료. 온보딩에서 스킵했으면 여기서 넣는다
            Section("생년월일") {
                ToggleRow(
                    label = if (birthYear > 0) "${birthYear}년생" else "생년월일 입력",
                    caption = "최대 심박(HRmax) 추정에 써요 — 세션의 심박 존이 더 정확해져요",
                    isOn = birthYear > 0,
                ) { isOn ->
                    scope.launch { settings.setBirthYear(if (isOn) 1990 else 0) }
                }
                if (birthYear > 0) {
                    val maxYear = remember { LocalDate.now(SEOUL).year - 10 }
                    WheelRow {
                        NumberWheel(
                            "년", 1930..maxYear, birthYear,
                            { scope.launch { settings.setBirthYear(it) } },
                            Modifier.weight(1f), rowHeight = 36.dp,
                        )
                    }
                }
            }

            Section("개인정보") {
                LinkRow(
                    label = "개인정보 처리방침",
                    caption = "건강 데이터는 기기 안에서만 처리합니다",
                    url = PRIVACY_POLICY_URL,
                )
            }

            Text(
                "리포트 카드의 구성과 문장 톤이 프로필에 맞춰 바뀝니다. 러닝 기록 자체는 그대로예요.",
                fontSize = 11.5.sp, lineHeight = 17.sp, color = RR.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/// 앱 내 개인정보 처리방침 — iOS와 같은 Vercel 정적 배포 주소.
/// 원본은 저장소의 docs/privacy.html — 방침을 고치면 원본과 배포본을 함께 갱신한다.
private const val PRIVACY_POLICY_URL = "https://runmisae-privacy.vercel.app/privacy.html"

// MARK: - 공통 행

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RR.text2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(Modifier.fillMaxWidth().rrCard(), content = content)
    }
}

@Composable
private fun OptionRow(label: String, caption: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text)
            Text(caption, fontSize = 12.5.sp, color = RR.text2)
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = "선택됨",
                tint = RR.brand, modifier = Modifier.size(20.dp),
            )
        } else {
            Box(
                Modifier.size(20.dp)
                    .border(1.5.dp, RR.text3.copy(alpha = 0.5f), CircleShape)
            )
        }
    }
}

/// 토글 행 — OptionRow와 같은 레이아웃, 우측만 스위치
@Composable
private fun ToggleRow(label: String, caption: String, isOn: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text)
            Text(caption, fontSize = 12.5.sp, color = RR.text2)
        }
        Switch(
            checked = isOn, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = RR.brand),
        )
    }
}

/// 외부 링크 행 — OptionRow와 같은 레이아웃, 우측만 링크 표시
@Composable
private fun LinkRow(label: String, caption: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text)
            Text(caption, fontSize = 12.5.sp, color = RR.text2)
        }
        Text("↗", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = RR.text3)
    }
}

/// 주간 목표 스테퍼 버튼 — iOS Stepper의 −/+ 대응
@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp)
            .background(RR.surface2, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) RR.text else RR.text3,
        )
    }
}

/// 휠 행 — 가운데 선택 띠(surface2)를 행 전체에 깔고 휠을 얹는다
@Composable
private fun WheelRow(wheels: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .background(RR.surface2, RoundedCornerShape(8.dp))
        )
        Row { wheels() }
    }
}

/// 대회 날짜 행 — 탭하면 달력 다이얼로그. 오늘부터 1년 안 (지난 날짜는 주기화가 무의미하다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceDateRow(raceDateSec: Long, onPick: (Long) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val date = remember(raceDateSec) {
        LocalDate.ofInstant(Instant.ofEpochSecond(raceDateSec), SEOUL)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "대회일", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            date.format(raceDateFormat),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RR.brand,
        )
    }

    if (showPicker) {
        // DatePicker의 millis는 "UTC 자정" 좌표계다 — 저장된 순간(epoch)을 그대로 주면
        // 시간대에 따라 하루 어긋나므로, 서울 기준 날짜의 epochDay로 변환해 넘긴다
        val todayUtcMillis = LocalDate.now(SEOUL).toEpochDay() * 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.toEpochDay() * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis in todayUtcMillis..(todayUtcMillis + 366L * 86_400_000)
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(it / 1_000) }
                    showPicker = false
                }) { Text("확인", color = RR.brand) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("취소", color = RR.text3) }
            },
        ) {
            DatePicker(state)
        }
    }
}

private val raceDateFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)
