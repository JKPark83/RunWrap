package com.jkpark.runwrap.screen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jkpark.runwrap.BuildConfig
import com.jkpark.runwrap.LocalAppContainer
import com.jkpark.runwrap.engine.FeedbackMail
import com.jkpark.runwrap.engine.FinisherCertificateReader
import com.jkpark.runwrap.engine.Format
import com.jkpark.runwrap.engine.GrowthEngine
import com.jkpark.runwrap.engine.HeartRateProfile
import com.jkpark.runwrap.engine.HeartRateZoneMethod
import com.jkpark.runwrap.engine.NotificationScheduler
import com.jkpark.runwrap.engine.NotifyKey
import com.jkpark.runwrap.engine.ProfileKey
import com.jkpark.runwrap.engine.ProfileReset
import com.jkpark.runwrap.engine.ProgressSnapshot
import com.jkpark.runwrap.engine.RRTone
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.engine.RaceRecord
import com.jkpark.runwrap.engine.RaceResultParser
import com.jkpark.runwrap.engine.ReportCache
import com.jkpark.runwrap.engine.RunPurpose
import com.jkpark.runwrap.engine.RunnerLevel
import com.jkpark.runwrap.engine.Shoe
import com.jkpark.runwrap.engine.ShoeEngine
import com.jkpark.runwrap.engine.ShoeKey
import com.jkpark.runwrap.engine.TrainingGuideEngine
import com.jkpark.runwrap.engine.WeeklyGoalChangeLog
import com.jkpark.runwrap.engine.fmt
import com.jkpark.runwrap.engine.instantSince1970
import com.jkpark.runwrap.engine.swiftRoundedInt
import com.jkpark.runwrap.engine.timeIntervalSince1970
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.store.DemoMode
import com.jkpark.runwrap.store.appSupportDir
import com.jkpark.runwrap.store.authorizationGranted
import com.jkpark.runwrap.store.read
import com.jkpark.runwrap.store.rescheduleHydration
import com.jkpark.runwrap.store.rescheduleWeekly
import com.jkpark.runwrap.ui.NumberWheel
import com.jkpark.runwrap.ui.RR
import com.jkpark.runwrap.ui.RRIcons
import com.jkpark.runwrap.ui.RRSegmented
import com.jkpark.runwrap.ui.RRStepper
import com.jkpark.runwrap.ui.ShoeEditSheet
import com.jkpark.runwrap.ui.ShoeImage
import com.jkpark.runwrap.ui.color
import com.jkpark.runwrap.ui.rrCard
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.launch

/// 설정 — 프로필(목적·레벨) 변경. 진입: 홈 헤더의 기어 아이콘 (계획서 M2).
/// 이후 마일스톤의 알림 토글·목표 입력도 이 화면에 추가된다.
/// (Android: 내비게이션 바 → 상단 행(뒤로·제목), 재진단 시트 → `onOpenRediagnosis` route, 메뉴 Picker → DropdownMenu,
///  DatePicker → DatePickerDialog(고른 날의 KST 자정 저장), 카메라 → TakePicture.
///  운동 직후 인사이트 토글·대회 기록 자연어 입력은 ios-only라 뺐고, iCloud 백업 시각 행은 Google 계정 백업 안내로 바꿨다)

/// 앱 내 개인정보 처리방침 — 스토어 심사가 요구하는 앱 내 접근 경로.
/// (Android: 원본은 docs/privacy-android.html, iOS 방침과 같은 Vercel 프로젝트로 배포한다 — review-considerations §4)
private const val privacyPolicyURL = "https://runmisae-privacy.vercel.app/privacy-android.html"

/// 날짜 표시·저장 기준 — 한국 사용자 전용 앱이라 KST로 고정한다 (P3 계약: DatePicker는 KST 자정 저장)
private val kst: ZoneId = ZoneId.of("Asia/Seoul")

/// 알람 weekday와 같은 순서 (1 = 일요일)
private val weekdayNames = listOf("일", "월", "화", "수", "목", "금", "토")

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenRediagnosis: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val settings = container.settings
    val health = container.health
    val context = LocalContext.current
    val healthState by health.state.collectAsStateWithLifecycle()
    val hrMaxEstimate by health.hrMaxEstimate.collectAsStateWithLifecycle()
    val restingHRBpm by health.restingHRBpm.collectAsStateWithLifecycle()
    /// 직접 입력한 대회 기록 (이슈 #35) — 예측 표본. 컨테이너가 쥐고 여기서 추가·삭제한다
    val records by container.raceRecords.records.collectAsStateWithLifecycle()
    /// 러닝화 (이슈 #171) — 컨테이너가 쥐고 여기서 등록·편집한다
    val shoes by container.shoes.shoes.collectAsStateWithLifecycle()
    val defaultShoeID by container.shoes.defaultShoeID.collectAsStateWithLifecycle()
    val assignments by container.shoes.assignments.collectAsStateWithLifecycle()

    val levelRaw by settings.rememberSetting(ProfileKey.levelV2, RunnerLevel.beginner.rawValue)
    var purposesRaw by settings.rememberSetting(ProfileKey.purposes, "")
    var weeklyGoal by settings.rememberSetting(ProfileKey.weeklyGoal, 2)
    var raceGoalRaw by settings.rememberSetting(ProfileKey.raceGoal, "")
    var raceGoalSec by settings.rememberSetting(ProfileKey.raceGoalSec, 0)
    // 대회 날짜 — 0이면 미설정. timeIntervalSince1970로 둔다
    var raceDateRaw by settings.rememberSetting(ProfileKey.raceDate, 0.0)
    // 심박 기준 (이슈 #56) — 0/빈 문자열이면 미설정 → 추정·헬스 커넥트 값
    var hrMaxManual by settings.rememberSetting(ProfileKey.hrMaxManual, 0)
    var restingHRManual by settings.rememberSetting(ProfileKey.restingHRManual, 0)
    var hrZoneMethodRaw by settings.rememberSetting(ProfileKey.hrZoneMethod, "")
    /// 러닝 후 러닝화 묻기 (이슈 #206) — 저장은 '끔' 쪽이라 기본값이 켜짐이다
    var shoePromptOptOut by settings.rememberSetting(ShoeKey.promptOptOut, false)
    // 알림 (계획서 M8) — 기본값은 NotificationScheduler.rescheduleWeekly의 폴백과 같아야 한다
    val weeklyNotify = settings.rememberSetting(NotifyKey.weeklyEnabled, false)
    var weeklyWeekday by settings.rememberSetting(NotifyKey.weeklyWeekday, 1)
    var weeklyHour by settings.rememberSetting(NotifyKey.weeklyHour, 18)
    val hydrationNotify = settings.rememberSetting(NotifyKey.hydrationEnabled, false)
    var runHour by settings.rememberSetting(NotifyKey.runHour, 19)
    // 즐겨찾기 대회 접수 알림 (이슈 #172) — 예약 재료(대회 목록)는 컨테이너의 RaceStore가 쥔다
    val raceNotify = settings.rememberSetting(NotifyKey.raceEnabled, false)
    // 데모 모드 — 워치 기록이 없는 기기(심사자 포함)에서 합성 데이터로 화면을 보여준다 (DemoMode)
    var demoMode by settings.rememberSetting(DemoMode.key, false)

    var isAddingRecord by remember { mutableStateOf(false) }
    /// 러닝화 편집 시트 대상(신규면 아직 목록에 없는 신발)
    var editingShoe by remember { mutableStateOf<Shoe?>(null) }
    /// 시트를 열 때 정한다 — 저장 직후 목록에 들어가도 닫히는 동안 '편집'으로 바뀌지 않게
    var editingShoeIsNew by remember { mutableStateOf(false) }
    /// 알림 토글을 켰는데 시스템 권한이 없을 때의 안내 (이슈 #94)
    var showsNotificationDenied by remember { mutableStateOf(false) }
    /// 정보 섹션 (이슈 #185) — 메일 앱이 없어 주소를 복사했을 때의 안내, 초기화 확인
    var showsMailCopied by remember { mutableStateOf(false) }
    var confirmsProfileReset by remember { mutableStateOf(false) }
    var picksRaceDate by remember { mutableStateOf(false) }

    /// 실제 적용되는 심박 기준 — 존 방식 체크 표시·Karvonen 선택 가능 여부의 근거 (이슈 #56)
    val heartRate = TrainingGuideEngine.heartRateProfile(
        estimate = hrMaxEstimate, manualHrMax = hrMaxManual, manualRestingHR = restingHRManual,
        measuredRestingHR = restingHRBpm, zoneMethodRaw = hrZoneMethodRaw,
    )
    /// 러닝화 누적 거리의 재료 — 목록이 아직 없으면 등록 전 거리만 보인다
    val loadedRuns = (healthState as? HealthStore.State.Loaded)?.runs ?: emptyList()
    val mileages = remember(shoes, loadedRuns, assignments) {
        shoes.associate { it.id to container.shoes.mileage(it, loadedRuns) }
    }

    /// 알림 토글을 켤 때 권한 확인 (이슈 #94) — 처음이면 시스템 다이얼로그로 묻고,
    /// 이미 거부·해제했으면 토글을 되돌리고 설정으로 안내한다. 켜진 채 조용히 안 나가는 상태를 막는다
    var pendingPermission by remember { mutableStateOf<Pair<MutableState<Boolean>, () -> Unit>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (toggle, onGranted) = pendingPermission ?: return@rememberLauncherForActivityResult
        pendingPermission = null
        if (granted && NotificationScheduler.authorizationGranted(context)) {
            onGranted()
        } else {
            toggle.value = false
            showsNotificationDenied = true
        }
    }
    fun confirmNotificationPermission(toggle: MutableState<Boolean>, onGranted: () -> Unit) {
        if (NotificationScheduler.authorizationGranted(context)) return onGranted()
        pendingPermission = toggle to onGranted
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 스냅샷에 담기는 설정값 — 바뀌면 병합 기준 시각을 갱신한다 (이슈 #130).
    // 쓰기 지점(토글·스테퍼·휠·날짜)이 흩어져 있어 값 변화로 한 번에 잡는다
    val markLocalChanged = { ProgressSnapshot.markLocalChanged(settings, Instant.now()) }
    OnChange(purposesRaw) { _, _ -> markLocalChanged() }
    OnChange(raceGoalRaw) { _, _ -> markLocalChanged() }
    OnChange(raceGoalSec) { _, _ -> markLocalChanged() }
    OnChange(raceDateRaw) { _, _ -> markLocalChanged() }
    OnChange(hrMaxManual) { _, _ -> markLocalChanged() }
    OnChange(restingHRManual) { _, _ -> markLocalChanged() }
    OnChange(hrZoneMethodRaw) { _, _ -> markLocalChanged() }
    // 주간 목표 변경을 이력에 남긴다 (이슈 #108, #116) — 판정 규칙은 GrowthEngine.recordWeeklyGoalChange.
    // 바뀐 목표는 다음 주부터 보너스에 적용된다
    OnChange(weeklyGoal) { old, _ ->
        val history = WeeklyGoalChangeLog.load(settings)
        WeeklyGoalChangeLog.save(
            GrowthEngine.recordWeeklyGoalChange(history, old, Instant.now(), ZoneId.systemDefault()), settings,
        )
        markLocalChanged()
    }
    // 데모 모드를 켜면 합성 데이터로, 끄면 실제 헬스 커넥트 기록으로 다시 채운다.
    // 끌 때는 주간 알림 캐시를 비우고 다시 예약한다 — 데모 수치가 알림 본문에 남지 않게 (이슈 #44)
    OnChange(demoMode) { _, isOn ->
        if (!isOn) ReportCache.clear(appSupportDir(context))
        container.scope.launch {
            health.load()
            if (!isOn) NotificationScheduler.rescheduleWeekly(context)
        }
    }
    // 권한이 없어 되돌리면 false로 다시 불려 그쪽에서 예약을 거둔다
    OnChange(weeklyNotify.value) { _, isOn ->
        if (isOn) confirmNotificationPermission(weeklyNotify) { NotificationScheduler.rescheduleWeekly(context) }
        else NotificationScheduler.rescheduleWeekly(context)
    }
    OnChange(weeklyWeekday) { _, _ -> NotificationScheduler.rescheduleWeekly(context) }
    OnChange(weeklyHour) { _, _ -> NotificationScheduler.rescheduleWeekly(context) }
    OnChange(hydrationNotify.value) { _, isOn ->
        // 끄면 예약된 당일분을 거둔다 — 예보는 오늘 탭이 다시 조회할 때 확인
        if (isOn) confirmNotificationPermission(hydrationNotify) {}
        else NotificationScheduler.rescheduleHydration(context, null)
    }
    // 즐겨찾기 대회 접수 알림 (이슈 #172) — 대회 목록이 아직 없으면 받아 온 뒤 건다
    OnChange(raceNotify.value) { _, isOn ->
        if (isOn) {
            confirmNotificationPermission(raceNotify) {
                container.scope.launch {
                    container.raceStore.load()
                    container.raceStore.rescheduleRaceAlarms()
                }
            }
        } else {
            container.raceStore.rescheduleRaceAlarms()
        }
    }

    Column(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(RRIcons.named("chevron.left"), contentDescription = "뒤로", tint = RR.brand)
            }
            Text(
                "설정",
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = RR.text,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 레벨은 설문 결과라 여기서 직접 고르지 않는다 — 다시 진단받아야 바뀐다.
            // 손으로 올릴 수 있게 하면 게이트가 의미를 잃고, 감당 못 할 지표를 보게 된다 (기획서 §7)
            Section("내 레벨") {
                SettingRow(
                    RunnerLevel.fromRawValue(levelRaw)?.label ?: RunnerLevel.beginner.label,
                    "설문 결과로 정해져요. 실력이 늘면 앱이 먼저 승급을 제안합니다",
                    horizontal = 14.dp, vertical = 12.dp,
                ) {
                    // 다시 진단받기 — 설문을 처음부터 다시 받는다 (이전 답 프리필 없음, 기획서 §7)
                    TextButton(onClick = onOpenRediagnosis, colors = ButtonDefaults.textButtonColors(contentColor = RR.brand)) {
                        Text("다시 진단", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            Section("러닝 목적") {
                val selected = RunPurpose.decode(purposesRaw)
                for (purpose in RunPurpose.entries) {
                    OptionRow(purpose.label, if (purpose in selected) "선택됨" else " ", purpose in selected) {
                        // 목적 복수 선택 토글 — 최소 1개는 남긴다 (전부 끄면 문장 강조점을 정할 수 없다)
                        if (purpose in selected) {
                            if (selected.size > 1) purposesRaw = RunPurpose.encode(selected - purpose)
                        } else {
                            purposesRaw = RunPurpose.encode(selected + purpose)
                        }
                    }
                }
            }
            // 주간 목표 — 성장 XP의 주간 보너스 분모이자 홈 목표 칩의 기준 (기획서 §5)
            Section("주간 러닝 목표") {
                SettingRow("주 ${weeklyGoal}회", "채우면 새가 자라는 보너스를 받아요", horizontal = 14.dp, vertical = 12.dp) {
                    RRStepper(weeklyGoal, 1..7) { weeklyGoal = it }
                }
            }
            Text(
                "바뀐 목표는 다음 주부터 보너스에 적용돼요",
                style = TextStyle(fontSize = 12.5.sp),
                color = RR.text2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // 대회 목표 묶음 (이슈 #21) — 레이스·기록·날짜를 한 토글 아래 모은다.
            // 켜면 대회일 기본값으로 8주 뒤(일반적인 최소 준비 기간)를 넣고, 끄면 대회 날짜만 지운다 —
            // 레이스·기록은 훈련 가이드·도감이 계속 쓰고, 다음에 켤 때 그대로 복원된다
            Section("대회 목표") {
                ToggleRow("대회 목표 설정", "레이스·기록·날짜를 정하면 리포트에 D-day와 예상 완주 기록이 떠요", raceDateRaw > 0) { isOn ->
                    raceDateRaw = if (isOn) Instant.now().plusSeconds(8 * 7 * 86_400L).timeIntervalSince1970 else 0.0
                }
            }
            if (raceDateRaw > 0) {
                Section("목표 레이스") {
                    for (race in RaceDistance.entries) {
                        OptionRow(race.label, "${fmt(race.km, 1)} km", raceGoalRaw == race.rawValue) {
                            raceGoalRaw = race.rawValue
                        }
                    }
                    // 새 종류는 사이클 시작 때 고정한 목표로 정해진다 — 여기서 바꾼 목표는 다음 사이클부터 (이슈 #110)
                    Text(
                        "지금 키우는 새의 종류는 이번 사이클을 시작할 때 목표로 정해졌어요. 바꾼 목표는 다음 새부터 적용돼요.",
                        style = TextStyle(fontSize = 12.5.sp),
                        color = RR.text2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                // 목표 기록 — 시:분:초 휠. 0:00:00이면 미입력으로 취급해 예측만 보여준다
                Section("목표 기록") { TimeWheels(hours = 0..6, totalSec = raceGoalSec) { raceGoalSec = it } }
                // 대회 날짜 — 훈련 가이드의 D-day 주기화와 리포트 D-day의 기준 (§4.9)
                Section("대회 날짜") {
                    DateRow("대회일", instantSince1970(raceDateRaw)) { picksRaceDate = true }
                }
                // 지난 대회 기록 (이슈 #35) — 다른 워치·기록증에만 있어 헬스 커넥트에 없는 대회 기록을 예측 표본으로 쓴다.
                // 훈련 표본에 없는 "전력 노력"의 증거라 예측이 실제보다 느리게 나오는 문제의 핵심 재료다. 최근 2년까지만 받는다
                Section("지난 대회 기록") {
                    for (record in records) {
                        SettingRow("${record.race.label} ${Format.duration(record.timeSec)}", dateLabel(record.date)) {
                            IconButton(onClick = {
                                container.raceRecords.remove(record)
                                // 대회 기록은 진행도 백업에 포함된다 (이슈 #118)
                                container.backup.backupIfChanged()
                            }) {
                                Icon(
                                    RRIcons.named("xmark.circle.fill"),
                                    contentDescription = "삭제",
                                    tint = RR.text3.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    AddRow("대회 기록 추가", "다른 워치·기록증의 지난 대회 기록이 예상 완주 기록의 재료가 돼요") {
                        isAddingRecord = true
                    }
                }
            }
            // 러닝화 (이슈 #171) — 누적 거리로 교체 시점을 알린다. 은퇴한 신발은 흐리게 목록 끝
            Section("러닝화") {
                for (shoe in shoes.filter { !it.isRetired } + shoes.filter { it.isRetired }) {
                    ShoeRow(shoe, mileages[shoe.id] ?: 0.0, isDefault = defaultShoeID == shoe.id) {
                        editingShoeIsNew = false
                        editingShoe = shoe
                    }
                }
                AddRow("러닝화 추가", "기본 신발로 정하면 새 러닝이 자동으로 쌓이고, 교체할 때가 되면 알려드려요") {
                    editingShoeIsNew = true
                    editingShoe = Shoe(name = "", createdAt = Instant.now())
                }
                ToggleRow("러닝 후 러닝화 묻기", "새 러닝이 생기면 앱을 열 때 어떤 러닝화를 신었는지 물어봐요", !shoePromptOptOut) {
                    shoePromptOptOut = !it
                }
            }
            // 심박 기준 (이슈 #56) — 존·대회 노력도·세션 상세가 모두 이 값을 쓴다. 끄면 추정값으로 돌아간다.
            // 켜면 추정값·헬스 커넥트 최근값(없으면 60)을 범위로 클램프해 시작한다
            Section("심박 기준") {
                ToggleRow(
                    "최대 심박 직접 입력",
                    "끄면 추정값 ${hrMaxEstimate.bpm.swiftRoundedInt()} bpm(${hrMaxEstimate.source.label})을 써요",
                    hrMaxManual > 0,
                ) { isOn ->
                    hrMaxManual = if (isOn) hrMaxEstimate.bpm.swiftRoundedInt().coerceIn(HeartRateProfile.hrMaxRange) else 0
                }
                if (hrMaxManual > 0) {
                    SettingRow("최대 심박 $hrMaxManual bpm", "120~230 bpm") {
                        RRStepper(hrMaxManual, HeartRateProfile.hrMaxRange) { hrMaxManual = it }
                    }
                }
                ToggleRow(
                    "안정 심박 직접 입력",
                    restingHRBpm?.let { "끄면 헬스 커넥트 최근값 ${it.swiftRoundedInt()} bpm을 써요" }
                        ?: "헬스 커넥트에 최근 안정 심박 기록이 없어요",
                    restingHRManual > 0,
                ) { isOn ->
                    restingHRManual = if (isOn) (restingHRBpm ?: 60.0).swiftRoundedInt().coerceIn(HeartRateProfile.restingRange) else 0
                }
                if (restingHRManual > 0) {
                    SettingRow("안정 심박 $restingHRManual bpm", "30~100 bpm") {
                        RRStepper(restingHRManual, HeartRateProfile.restingRange) { restingHRManual = it }
                    }
                }
            }
            // 체크는 실제 적용된 방식을 따른다 — 저장값이 Karvonen이어도 안정 심박이 없으면
            // %HRmax에 체크가 가고, 저장값은 지우지 않아 안정 심박이 돌아오면 자동 복귀한다
            Section("심박 존 방식") {
                OptionRow(
                    HeartRateZoneMethod.percentMax.label,
                    "최대 심박의 60·70·80·90%로 다섯 구간을 나눠요",
                    heartRate.zoneMethod == HeartRateZoneMethod.percentMax,
                ) { hrZoneMethodRaw = HeartRateZoneMethod.percentMax.rawValue }
                OptionRow(
                    HeartRateZoneMethod.karvonen.label,
                    if (heartRate.restingHR == null) "안정 심박이 있어야 고를 수 있어요"
                    else "예비 심박(최대−안정)의 50~100%로 나눠 개인차를 반영해요",
                    heartRate.zoneMethod == HeartRateZoneMethod.karvonen,
                    enabled = heartRate.restingHR != null,
                ) { hrZoneMethodRaw = HeartRateZoneMethod.karvonen.rawValue }
            }
            // 알림 — 로컬 알림 (계획서 M8). 토글을 켤 때 시스템 권한을 요청한다
            Section("알림") {
                ToggleRow("주간 리포트", "매주 정한 시각에 한 주를 정리해 드려요", weeklyNotify.value) { weeklyNotify.value = it }
                if (weeklyNotify.value) {
                    // 주간 알림 시각 — 요일·시 메뉴 (기본 일 18:00)
                    MenuRow("받는 시각") {
                        MenuPicker((1..7).map { it to weekdayNames[it - 1] + "요일" }, weeklyWeekday) { weeklyWeekday = it }
                        MenuPicker((0..23).map { it to "${it}시" }, weeklyHour) { weeklyHour = it }
                    }
                }
                // 수분 알람 (계획서 M9) — 예보는 오늘 탭이 날씨를 조회할 때 확인한다
                ToggleRow("더운 날 수분 알람", "최고기온 25°C 이상이면 러닝 1시간 전에 알려드려요", hydrationNotify.value) {
                    hydrationNotify.value = it
                }
                if (hydrationNotify.value) {
                    // 주로 달리는 시각 — 수분 알람은 이 시각 1시간 전에 온다 (기본 19시)
                    MenuRow("주로 달리는 시각") {
                        MenuPicker((1..23).map { it to "${it}시" }, runHour) { runHour = it }
                    }
                }
                ToggleRow("즐겨찾기 대회 접수 알림", "접수 시작일과 마감 3일 전 오전 9시에 알려드려요", raceNotify.value) {
                    raceNotify.value = it
                }
            }
            // 데모 모드 (DemoMode) — 워치 기록이 없어도 화면을 둘러볼 수 있게 하는 경로.
            // 심사자용이자 신규 사용자용이며, 심사 노트에 켜는 방법을 그대로 밝힌다.
            Section("데모 모드") {
                ToggleRow("샘플 데이터로 둘러보기", "워치 기록 없이도 모든 카드를 미리 볼 수 있어요. 건강 데이터는 읽지 않습니다", demoMode) {
                    demoMode = it
                }
            }
            Section("개인정보") {
                val uriHandler = LocalUriHandler.current
                SettingRow(
                    "개인정보 처리방침", "건강 데이터는 기기 안에서만 처리합니다",
                    Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(privacyPolicyURL) } },
                ) { RowIcon("arrow.up.right.square") }
            }
            // 정보 (이슈 #185) — 버전·피드백 메일·프로필 설정 초기화. 데모 모드와 무관하게 보인다
            Section("정보") {
                SettingRow("버전", "런미새 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                // 피드백 메일 — 메일 앱을 못 열면(미설치·계정 없음) 주소를 복사하고 알린다
                SettingRow(
                    "피드백 보내기", "메일로 의견·버그를 알려주세요",
                    Modifier.clickable(role = Role.Button) {
                        val url = FeedbackMail.url(
                            appVersion = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE.toString(),
                            systemVersion = Build.VERSION.RELEASE, deviceModel = Build.MODEL, platform = "Android",
                        )
                        try {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url.toString())))
                        } catch (_: ActivityNotFoundException) {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("email", FeedbackMail.address))
                            showsMailCopied = true
                        }
                    },
                ) { RowIcon("envelope") }
                // 프로필 설정 초기화 — 확인 알림을 거친다
                SettingRow(
                    "프로필 설정 초기화", "목표·심박·알림 설정만 되돌립니다",
                    Modifier.clickable(role = Role.Button) { confirmsProfileReset = true },
                    titleColor = RR.dang,
                )
            }
            Text(
                "리포트 카드의 구성과 문장 톤이 프로필에 맞춰 바뀝니다. 러닝 기록 자체는 그대로예요.",
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                color = RR.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // 진행도 백업 (이슈 #129) — Android는 Auto Backup이 시스템 일정으로 돌아 마지막 시각이 없다.
            // 데모 모드는 백업 경로 자체를 막으므로 숨긴다
            if (!demoMode) {
                Text(
                    "Google 계정 백업 · 기기 백업이 켜져 있으면 설정·도감·대회 기록이 다시 설치할 때 복원돼요",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                    color = RR.text3,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    // 대회 기록 추가 (이슈 #35)
    if (isAddingRecord) {
        RaceRecordInputSheet(
            onSave = {
                container.raceRecords.add(it)
                container.backup.backupIfChanged()
            },
            onDismiss = { isAddingRecord = false },
        )
    }
    // 러닝화 등록·편집 (이슈 #171) — 신규와 편집이 같은 시트를 쓴다
    editingShoe?.let { shoe ->
        ShoeEditSheet(
            shoe = shoe,
            isNew = editingShoeIsNew,
            isDefault = if (editingShoeIsNew) defaultShoeID == null else defaultShoeID == shoe.id,
            onSave = { saved, isDefault -> container.shoes.save(saved, isDefault, loadedRuns) },
            onDelete = { container.shoes.remove(shoe) },
            onDismiss = { editingShoe = null },
        )
    }
    // 대회 날짜 선택 — 오늘부터 1년 안. 지난 날짜는 고를 수 없다 (주기화가 무의미해진다)
    if (picksRaceDate) {
        val today = LocalDate.now(kst)
        DateDialog(instantSince1970(raceDateRaw), today, today.plusDays(366), onDismiss = { picksRaceDate = false }) {
            raceDateRaw = it.timeIntervalSince1970
        }
    }
    if (showsNotificationDenied) {
        RRAlert(
            "알림이 꺼져 있어요", "설정 > 런미새 > 알림에서 허용해 주세요",
            confirm = "설정 열기",
            onConfirm = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
            dismiss = "닫기",
            onDismiss = { showsNotificationDenied = false },
        )
    }
    if (showsMailCopied) {
        RRAlert(
            "메일 주소를 복사했어요", "메일 앱에 ${FeedbackMail.address}로 보내 주세요",
            confirm = "확인", onConfirm = {}, onDismiss = { showsMailCopied = false },
        )
    }
    // 키를 지우면 rememberSetting이 기본값으로 갱신되고, 값 변화 OnChange가 병합 시각·주간 목표 이력을 남긴다.
    // 알림은 OnChange에 기대지 않고 여기서 직접 다시 예약해 꺼진 설정의 예약분을 거둔다
    if (confirmsProfileReset) {
        RRAlert(
            "프로필 설정을 초기화할까요?",
            "대회 목표·주간 목표·심박 기준·알림 설정이 처음 값으로 돌아가요. 레벨·새·도감·대회 기록·러닝화는 그대로예요.",
            confirm = "초기화", confirmColor = RR.dang,
            onConfirm = {
                ProfileReset.reset(settings)
                NotificationScheduler.rescheduleWeekly(context)
                NotificationScheduler.rescheduleHydration(context, null)
                container.raceStore.rescheduleRaceAlarms()
            },
            dismiss = "취소",
            onDismiss = { confirmsProfileReset = false },
        )
    }
}

/// iOS `.onChange(of:) { old, new in }` 대응 — 첫 표시에는 부르지 않고, 값이 바뀔 때만 부른다
/// (다른 화면·ProfileReset이 키를 지워 rememberSetting이 따라간 변화도 잡는다)
@Composable
private fun <T> OnChange(value: T, action: (old: T, new: T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (last != value) {
            val old = last
            last = value
            action(old, value)
        }
    }
}

/// "2025년 10월 12일" — 기기 로케일과 무관하게 한국어 고정 (사용자 문자열 규칙)
private fun dateLabel(date: Instant): String {
    val d = date.atZone(kst).toLocalDate()
    return "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}

/// 러닝화 한 켤레 — 이름·기본 배지, "누적 512 km / 600 km", 교체 기준 대비 진행 바 (이슈 #171)
@Composable
private fun ShoeRow(shoe: Shoe, mileage: Double, isDefault: Boolean, onClick: () -> Unit) {
    val progress = ShoeEngine.progress(mileage, shoe.replaceKm)
    val overshoot = ShoeEngine.overshoot(mileage, shoe.replaceKm)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (shoe.isRetired) 0.45f else 1f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 사진 또는 기본 일러스트 (이슈 #206)
        ShoeImage(shoe, Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shoe.name,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = RR.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Text(
                        "기본",
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = RR.brand,
                        modifier = Modifier
                            .background(RR.brandSoft, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.5.dp),
                    )
                }
                if (shoe.isRetired) {
                    Text("은퇴", style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = RR.text3)
                }
            }
            Text(
                "누적 ${mileage.swiftRoundedInt()} km / ${shoe.replaceKm.toInt()} km",
                style = TextStyle(fontSize = 12.5.sp),
                color = RR.text2,
            )
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(RR.barFill)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
                        .background(ShoeEngine.tone(progress).color, CircleShape),
                )
                // 기준을 넘긴 몫은 막대 끝에 과부하 색으로 덧칠한다 — 꽉 찬 막대만으로는 초과가 안 보인다
                Box(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(overshoot.toFloat().coerceIn(0f, 1f))
                        .background(RRTone.overload.color, CircleShape),
                )
            }
        }
        Icon(RRIcons.named("chevron.right"), contentDescription = null, tint = RR.text3, modifier = Modifier.size(16.dp))
    }
}

/// 목표·완주 기록 입력 — 시:분:초 휠. 가운데 선택 띠는 NumberWheel 규약대로 뒤에 깐다
@Composable
private fun TimeWheels(hours: IntRange, totalSec: Int, onChange: (Int) -> Unit) {
    val rowHeight = 36.dp   // 3행 = 108 (iOS 휠 높이)
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(rowHeight).background(RR.surface2, RoundedCornerShape(8.dp)))
        Row {
            NumberWheel("시간", hours, totalSec / 3_600, { onChange(it * 3_600 + totalSec % 3_600) },
                Modifier.weight(1f), rowHeight)
            NumberWheel("분", 0..59, totalSec % 3_600 / 60, { onChange(totalSec / 3_600 * 3_600 + it * 60 + totalSec % 60) },
                Modifier.weight(1f), rowHeight)
            NumberWheel("초", 0..59, totalSec % 60, { onChange(totalSec / 60 * 60 + it) },
                Modifier.weight(1f), rowHeight)
        }
    }
}

/// iOS `.datePickerStyle(.compact)` 행 대응 — 날짜 칩을 누르면 DatePickerDialog
@Composable
private fun DateRow(label: String, date: Instant, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = RR.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            dateLabel(date),
            style = TextStyle(fontSize = 15.sp),
            color = RR.text,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .background(RR.surface2)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

/// [from]..[through] 안의 날짜만 고르게 한다. 고른 날은 KST 자정으로 돌려준다 (P3 계약)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: Instant, from: LocalDate, through: LocalDate, onDismiss: () -> Unit, onPick: (Instant) -> Unit) {
    // DatePicker의 millis는 그 날짜의 UTC 자정이다
    fun dayOf(utcMillis: Long) = LocalDate.ofEpochDay(Math.floorDiv(utcMillis, 86_400_000L))
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atZone(kst).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = from.year..through.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = dayOf(utcTimeMillis)
                return !day.isBefore(from) && !day.isAfter(through)
            }
            override fun isSelectableYear(year: Int) = year in from.year..through.year
        },
    )
    val colors = DatePickerDefaults.colors(containerColor = RR.surface)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(dayOf(it).atStartOfDay(kst).toInstant()) }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = RR.brand),
            ) { Text("확인") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = RR.brand)) { Text("취소") }
        },
        colors = colors,
    ) {
        DatePicker(state, colors = colors)
    }
}

@Composable
private fun RRAlert(
    title: String, message: String,
    confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    confirmColor: Color = RR.brand, dismiss: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = confirmColor),
            ) { Text(confirm) }
        },
        dismissButton = dismiss?.let {
            { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = RR.brand)) { Text(it) } }
        },
        containerColor = RR.surface,
        titleContentColor = RR.text,
        textContentColor = RR.text2,
    )
}

/// iOS section·field — 제목 + 카드
@Composable
private fun Section(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = RR.text2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(Modifier.fillMaxWidth().rrCard(), content = rows)
    }
}

/// 공통 행 — 제목·설명 + 오른쪽 컨트롤 (optionRow·toggleRow·stepperRow·infoRow·linkRow가 같은 레이아웃)
@Composable
private fun SettingRow(
    title: String, caption: String, modifier: Modifier = Modifier,
    titleColor: Color = RR.text, horizontal: Dp = 16.dp, vertical: Dp = 14.dp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = vertical),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = titleColor)
            Text(caption, style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun RowIcon(name: String, tint: Color = RR.text3, size: Dp = 20.dp) {
    Icon(RRIcons.named(name), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun OptionRow(label: String, caption: String, isSelected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    SettingRow(
        label, caption,
        Modifier
            .selectable(isSelected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        RowIcon(
            if (isSelected) "checkmark.circle.fill" else "circle",
            tint = if (isSelected) RR.brand else RR.text3.copy(alpha = 0.5f),
            size = 22.dp,
        )
    }
}

/// (Android: 행 전체를 토글 대상으로 둔다 — 스위치 단독보다 터치 영역이 넓고, 접근성 라벨이 행의 글자가 된다)
@Composable
private fun ToggleRow(label: String, caption: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label, caption, Modifier.toggleable(isOn, role = Role.Switch, onValueChange = onChange)) {
        Switch(
            checked = isOn,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RR.onBrand,
                checkedTrackColor = RR.brand,
                uncheckedThumbColor = RR.surface,
                uncheckedTrackColor = RR.barFill,
                uncheckedBorderColor = RR.line,
            ),
        )
    }
}

/// 추가 버튼 행 — 예측 표본·자동 누적이 되는 이유를 캡션으로 밝힌다
@Composable
private fun AddRow(label: String, caption: String, onClick: () -> Unit) {
    SettingRow(label, caption, Modifier.clickable(role = Role.Button, onClick = onClick), titleColor = RR.brand) {
        RowIcon("plus.circle.fill", tint = RR.brand, size = 22.dp)
    }
}

/// 메뉴 피커 행 (iOS `.pickerStyle(.menu)`)
@Composable
private fun MenuRow(title: String, pickers: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = RR.text,
            modifier = Modifier.weight(1f),
        )
        pickers()
    }
}

@Composable
private fun MenuPicker(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            options.firstOrNull { it.first == selected }?.second ?: "",
            style = TextStyle(fontSize = 15.sp),
            color = RR.brand,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = RR.surface) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label, color = RR.text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    trailingIcon = if (value == selected) {
                        { RowIcon("checkmark", tint = RR.brand) }
                    } else null,
                )
            }
        }
    }
}

/// iOS `.buttonStyle(.bordered).tint(…)` 대응 — 옅은 tint 배경 + tint 글자
@Composable
private fun BorderedButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = RR.brand,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .background(RR.brand.copy(alpha = 0.15f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

// MARK: - 대회 기록 입력 시트 (이슈 #35)

/// 종목·기록·날짜 수동 입력. 완주증 사진(사진 선택·카메라)으로 채우는 지름길은 온디바이스 OCR이라 모든 기기에 노출한다 (이슈 #192).
/// 결과는 폼을 채울 뿐 바로 저장하지 않는다 — 오독은 사용자가 저장 전에 잡는다.
/// (Android: 자연어 입력(Apple Intelligence)은 ios-only라 섹션이 없다. 카메라는 TakePicture가 cacheDir/camera/에 찍고,
///  읽은 뒤 지운다 — 매니페스트에 CAMERA 권한이 없어 권한 요청 없이 시스템 카메라 앱이 찍는다)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceRecordInputSheet(onSave: (RaceRecord) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var race by remember { mutableStateOf(RaceDistance.half) }
    var timeSec by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(Instant.now()) }
    var picksDate by remember { mutableStateOf(false) }
    // 완주증 OCR (이슈 #192) — 사진은 읽기만 하고 어디에도 저장하지 않는다
    var isReadingCertificate by remember { mutableStateOf(false) }
    var certificateFailed by remember { mutableStateOf(false) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    /// 입력 가능한 날짜 범위 — 엔진의 최대 나이 가드(2년)와 같은 하한
    val dateFloor = remember { Instant.now().minusSeconds(TrainingGuideEngine.maxRaceRecordAgeDays * 86_400L) }
    fun clamped(d: Instant): Instant = minOf(maxOf(d, dateFloor), Instant.now())

    /// 입력한 기록이 종목 거리에 비해 비현실적인지 — 0:00:00은 아직 입력 전이라 안내하지 않는다
    val isImplausible = timeSec > 0 && !RaceRecord.isPlausible(timeSec.toDouble(), race.km)

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    /// 파싱 결과로 폼을 채운다 — 확신 없는 필드(null)는 기존 값을 유지한다
    fun apply(parsed: RaceResultParser.Parsed) {
        parsed.race?.let { race = it }
        parsed.timeSec?.let { timeSec = it.toInt() }
        parsed.date?.let { date = clamped(it) }
    }

    /// 완주증 이미지 → OCR → 폼 프리필. 못 읽으면 실패 안내만 한다
    fun fillFromCertificate(image: Uri, cleanup: () -> Unit = {}) {
        isReadingCertificate = true
        certificateFailed = false
        scope.launch {
            val parsed = FinisherCertificateReader.read(context, image)
            cleanup()
            isReadingCertificate = false
            if (parsed == null) certificateFailed = true else apply(parsed)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { fillFromCertificate(it) }
    }
    // 촬영본 자리 — res/xml/file_paths.xml의 cache-path "camera/"에 맞춘다
    val cameraFile = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "certificate.jpg") }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) {
            fillFromCertificate(cameraUri) { cameraFile.delete() }
        } else {
            cameraFile.delete()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RR.bg,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(
                    onClick = ::dismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.brand),
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Text("닫기", style = TextStyle(fontSize = 17.sp))
                }
                Text(
                    "대회 기록 추가",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = RR.text,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(
                    onClick = {
                        onSave(RaceRecord(id = UUID.randomUUID().toString().uppercase(), race = race,
                                          timeSec = timeSec.toDouble(), date = date))
                        dismiss()
                    },
                    // 0:00:00은 기록이 아니고, 비현실 페이스는 예측을 오염시킨다 (이슈 #93)
                    enabled = RaceRecord.isPlausible(timeSec.toDouble(), race.km),
                    colors = ButtonDefaults.textButtonColors(contentColor = RR.brand, disabledContentColor = RR.text3),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text("저장", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 완주증 지름길 (이슈 #192) — 사진 선택은 권한이 필요 없고, 촬영은 카메라가 있는 기기에서만 보인다
                Section("완주증 사진으로 채우기") {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BorderedButton("사진 선택", enabled = !isReadingCertificate) {
                                photoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            }
                            if (hasCamera) {
                                BorderedButton("촬영", enabled = !isReadingCertificate) {
                                    camera.launch(cameraUri)
                                }
                            }
                            if (isReadingCertificate) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = RR.text3, strokeWidth = 2.dp)
                            }
                        }
                        if (certificateFailed) {
                            Text("완주증을 읽지 못했어요 — 아래에서 직접 입력해 주세요", style = TextStyle(fontSize = 12.5.sp), color = RR.text2)
                        }
                        Text("사진은 기기에서만 읽고 저장하지 않아요", style = TextStyle(fontSize = 11.5.sp), color = RR.text3)
                    }
                }
                Section("종목") {
                    RRSegmented(
                        RaceDistance.entries.map { it.label },
                        RaceDistance.entries.indexOf(race),
                        { race = RaceDistance.entries[it] },
                        Modifier.padding(12.dp),
                    )
                }
                Section("완주 기록") { TimeWheels(hours = 0..7, totalSec = timeSec) { timeSec = it } }
                if (isImplausible) {
                    Text(
                        "기록이 종목 거리에 비해 너무 빠르거나 느려요",
                        style = TextStyle(fontSize = 12.5.sp),
                        color = RR.warn,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Section("대회 날짜") { DateRow("대회 날짜", date) { picksDate = true } }
                Text(
                    "최근 2년 안의 기록만 예측에 쓸 수 있어요. 대회 기록은 전력 기준이라 훈련 기록보다 정확한 예측 재료가 됩니다.",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                    color = RR.text3,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (picksDate) {
        DateDialog(date, dateFloor.atZone(kst).toLocalDate(), LocalDate.now(kst), onDismiss = { picksDate = false }) {
            date = clamped(it)
        }
    }
}
