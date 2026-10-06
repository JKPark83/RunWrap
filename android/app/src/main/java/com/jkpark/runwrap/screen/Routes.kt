package com.jkpark.runwrap.screen

import com.jkpark.runwrap.engine.EngineJson
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.TrainingPlan
import com.jkpark.runwrap.engine.WeeklyReport
import java.time.Instant
import kotlinx.serialization.Serializable

/// 화면 이동 경로 — iOS의 `NavigationLink`·`.navigationDestination`·`.sheet` 목적지를 한 NavHost의 route로 옮긴 것.
///
/// (Android: 이 파일은 iOS에 없다. 규칙은 다음과 같다)
/// - 화면은 NavController를 받지 않는다. 이동은 화면 파라미터의 콜백 람다로 요청하고, RootView가 navigate로 잇는다.
///   웨이브 3은 아래 콜백 계약(시그니처)을 그대로 지켜 스텁을 덮어쓴다 — 바꾸면 RootView도 같이 고친다.
/// - 탭 4개는 각자 중첩 그래프(…Graph → …Root)다. 상세 화면 route는 NavHost 최상위에 두어 어느 탭에서든 쌓인다.
///   탭을 바꿔도 탭별 스택은 saveState/restoreState로 보존된다 (iOS 탭마다 NavigationStack 대응).
/// - 엔진 모델 인자는 `EngineJson` JSON 문자열로 싣는다(String NavType). 커스텀 NavType 클래스는 두지 않는다.
///   시각 필드는 Swift Date처럼 Double 초로 직렬화돼 나노초 끝자리가 달라질 수 있다 — 같은 러닝을 찾을 때는 `id`로 비교한다.
///
/// ## 화면별 콜백 계약 (스텁 시그니처 = 웨이브 3 계약)
/// - `HomeScreen(onSelectReport, onOpenSession, onOpenToday, onOpenRecap, onOpenRace, onOpenCollection, onOpenSettings)`
///   - `onSelectReport()` — 판단 카드의 배터리·권장 세션 줄 → 리포트 탭 선택
///   - `onOpenSession(run, null)` — 마지막 러닝 칩·당겨서 새로고침 후 마지막 러닝 → `SessionDetail`
///   - `onOpenToday()` — 판단 카드 날씨 줄 → `Today` (iOS 시트)
///   - `onOpenRecap(period)` — 리캡 배너 → `Recap` (iOS 시트)
///   - `onOpenRace(entry)` — 목표 대회 카드 → `RaceDetail` (iOS 시트 + "닫기")
///   - `onOpenCollection()`·`onOpenSettings()` — 헤더의 도감·톱니 → `Collection`·`Settings`
///   - 세러모니(`CeremonyScreen`)·PB 축하·러닝화 팝업은 route가 아니다 — iOS처럼 홈이 직접 띄운다
///     (fullScreenCover는 탭바까지 덮어야 하므로 Dialog(usePlatformDefaultWidth = false) 등으로 띄운다)
/// - `ReportHomeScreen(onOpenSession, onOpenRecap, onOpenTrainingPlan)` — 리포트 탭 루트.
///   통계·성장 세그먼트로 `StatsScreen`·`GrowthScreen`을 품으며 같은 콜백을 내려준다
///   - `onOpenSession(run, weeklyContext)` — 세션 목록·PB 목록 행 → `SessionDetail`
///   - `onOpenTrainingPlan(plan)` — "주차별 훈련 계획 보기" → `TrainingPlan`
/// - `StatsScreen(segment, onOpenSession, onOpenRecap)`·`GrowthScreen(segment, onOpenSession)`
///   - `segment` — iOS `AnyView?` 세그먼트 피커 자리
/// - `CourseScreen()` — 코스 탭 루트. 이동 없음
/// - `RaceListScreen(onOpenRace)` — 대회 탭 루트. 행 → `RaceDetail`
/// - `SettingsScreen(onBack, onOpenRediagnosis)` — "다시 진단받기" → `Rediagnosis` (iOS 시트)
/// - `OnboardingFlowScreen(prefill, isRediagnosis, onFinish)` — 첫 실행은 RootView가 직접 그린다(route 아님).
///   재진단만 `Rediagnosis` route이고 `onFinish`가 닫는다. 프리필은 iOS처럼 앱 호출부가 넘기지 않는다(테스트용)
/// - `SessionDetailScreen(run, weeklyContext, onShoePromptOptOut, onBack)`·`TodayScreen(onBack)`·
///   `RecapScreen(period, onBack)`·`RaceDetailScreen(entry, onBack)`·`CollectionScreen(onBack)`·
///   `TrainingPlanScreen(plan, onBack)` — `onBack`은 상단 뒤로/닫기 버튼용. 시스템 뒤로가기는 NavHost가 처리한다
/// - `CeremonyScreen(species, goalLabel, cycleStartedAt, cycleGoal, cycleGoalSeconds, currentGoal, currentGoalSeconds, onLater, onFinish)`
///   — 홈 전용. `onFinish(newGoal, newGoalSeconds)`가 false면(도감 저장 실패) 닫지 않는다
object Routes {
    // 탭 4개 — 그래프와 루트
    @Serializable data object HomeGraph
    @Serializable data object HomeRoot
    @Serializable data object ReportGraph
    @Serializable data object ReportRoot
    @Serializable data object CourseGraph
    @Serializable data object CourseRoot
    @Serializable data object RacesGraph
    @Serializable data object RacesRoot

    // 상세 — iOS에서 push(NavigationLink)였던 것
    @Serializable data class SessionDetail(val runJson: String, val weeklyContextJson: String? = null)
    @Serializable data class RaceDetail(val entryJson: String)
    @Serializable data object Collection
    @Serializable data object Settings
    @Serializable data class TrainingPlanDetail(val planJson: String)

    // iOS에서 시트였던 것 — 탭바를 덮는다 (RootView `sheetRoutes`)
    @Serializable data object Today
    /// RecapPeriod는 sealed class라 직렬화 대신 종류 + 기준 시각(epoch ms)으로 싣는다
    @Serializable data class Recap(val kind: String, val epochMilli: Long)
    @Serializable data object Rediagnosis
}

fun sessionDetailRoute(run: RunSummary, weeklyContext: WeeklyReport.DistanceCard?) =
    Routes.SessionDetail(EngineJson.encodeToString(run), weeklyContext?.let { EngineJson.encodeToString(it) })

val Routes.SessionDetail.run: RunSummary get() = EngineJson.decodeFromString(runJson)
val Routes.SessionDetail.weeklyContext: WeeklyReport.DistanceCard?
    get() = weeklyContextJson?.let { EngineJson.decodeFromString(it) }

fun raceDetailRoute(entry: RaceEngine.Entry) = Routes.RaceDetail(EngineJson.encodeToString(entry))
val Routes.RaceDetail.entry: RaceEngine.Entry get() = EngineJson.decodeFromString(entryJson)

fun trainingPlanRoute(plan: TrainingPlan) = Routes.TrainingPlanDetail(EngineJson.encodeToString(plan))
val Routes.TrainingPlanDetail.plan: TrainingPlan get() = EngineJson.decodeFromString(planJson)

fun recapRoute(period: RecapPeriod) = when (period) {
    is RecapPeriod.month -> Routes.Recap("month", period.date.toEpochMilli())
    is RecapPeriod.year -> Routes.Recap("year", period.date.toEpochMilli())
}

val Routes.Recap.period: RecapPeriod
    get() = Instant.ofEpochMilli(epochMilli).let { if (kind == "year") RecapPeriod.year(it) else RecapPeriod.month(it) }
