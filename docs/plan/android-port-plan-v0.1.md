# RunWrap Android(AOS) 포팅 개발 계획서 (v0.1)

작성일: 2026-08-20 | 버전: v0.1 | 베이스 커밋: feat/android @ d3b884e

**한 줄 요약:** iOS 전용 런미새를 갤럭시폰 기준 Android 앱으로 포팅한다 — 데이터 소스는
삼성헬스 → Health Connect 동기화 경유, 범위는 **리포트 코어**(온보딩·홈·리포트·세션
상세·설정)까지만. 오늘/코스/대회 탭과 Play Store 출시는 후속 계획으로 분리한다.

관련 문서: [기획서 v0.6](기획서-v0.1.md) · [iOS 전체 계획](runwrap-plan-v0.1.md)

---

## 목차

- [0. 전제 — 기존 코드 재활용 맵 · 신규 디렉터리 구조](#0-전제--기존-코드-재활용-맵--신규-디렉터리-구조)
- [1. 확정 결정 모음](#1-확정-결정-모음)
- [2. 아키텍처 · 데이터 가용성](#2-아키텍처--데이터-가용성)
- [3. 마일스톤 M0~M6](#3-마일스톤)
- [4. 의존성 그래프 · 병렬화 지점](#4-의존성-그래프--병렬화-지점)
- [5. 위험 요소와 완화책](#5-위험-요소와-완화책)
- [6. 신규 파일 목록](#6-신규-파일-목록)
- [7. 완료 체크리스트](#7-완료-체크리스트)
- [8. 오픈 이슈](#8-오픈-이슈)

---

## 0. 전제 — 기존 코드 재활용 맵 · 신규 디렉터리 구조

Android 코드는 전부 신설이지만, **iOS 원본이 이식 사양서 역할**을 한다. 엔진은 순수
로직(Foundation만 import, `now` 주입, 결정론적)이라 Kotlin으로 기계적 이식이 가능하고,
테스트 기대값도 그대로 옮긴다.

| 구분 | 재활용 (이식 원본) | 신설 (Android 대응) |
|---|---|---|
| 엔진 14종 | `ios/RunWrap/ReportMetrics.swift`(480줄), `ReportEngine`(222), `BatteryEngine`(234), `TrainingGuideEngine`(546), `TodayVerdictEngine`(255), `GrowthEngine`(201), `LevelEngine`(58), `CollectionEngine`(147), `CrossTrainingEngine`(128), `WalkRunEngine`(86), `DriftEngine`(99), `HeatEngine`(69), `HomeBriefingEngine`(152), `AirQualityEngine`(156) — 산식·가드·주석(출처 포함) 그대로 이식 | `android/app/src/main/java/com/jkpark/runwrap/engine/*.kt` — 파일 단위 1:1 |
| 엔진 테스트 | `ios/RunWrapTests/*Tests.swift` 중 위 엔진 대응 스위트 — 고정 시각·기대값·한국어 표시 이름 그대로 | `android/app/src/test/java/com/jkpark/runwrap/engine/*Test.kt` |
| 건강 데이터 계층 | `ios/RunWrap/HealthStore.swift`, `WorkoutDetailStore.swift`, `HealthPermissions.swift` — 상태 enum·조회 구조만 참고 (API는 전면 교체) | `health/HealthConnectStore.kt`, `health/WorkoutDetailStore.kt`, `health/HealthPermissions.kt` |
| 합성 데이터 | `ios/RunWrap/DemoData.swift` — 시나리오 그대로 | `health/DemoData.kt` + 디버그 전용 `seeder/HealthConnectSeeder.kt` (HC에 실제 레코드 주입 — iOS에 없던 신규 개념) |
| 외부 통신 | `ios/RunWrap/WeatherClient.swift`(open-meteo), `AirQualityClient.swift`(에어코리아 프록시 없음 — 코드 확인 후 동일 엔드포인트) — 플랫폼 무관 | `net/WeatherClient.kt`, `net/AirQualityClient.kt` |
| 번들 데이터 | `ios/RunWrap/AirStations.json` — 그대로 복사 | `android/app/src/main/assets/AirStations.json` |
| UI 토큰·포맷 | `ios/RunWrap/Theme.swift`(RR 토큰·RRTone 매핑), `Format.*`, RRCharts — 색·수치·포맷 규칙 그대로 | `ui/theme/Theme.kt`, `ui/Format.kt`, `ui/charts/RRCharts.kt` |
| 화면 5종 | `OnboardingFlowScreen`, `HomeScreen`, `ReportHomeScreen`(+`ReportDetailScreen`), `SessionDetailScreen`, `SettingsScreen` — 레이아웃·문안 그대로 | `screen/*.kt` (Compose) |
| 영속화 | `ReportCache`(Application Support JSON), `@AppStorage` 키 | `store/ReportCache.kt`(filesDir JSON), `store/SettingsStore.kt`(DataStore Preferences) |
| 사용자 노출 문자열 | 한국어 하드코딩 원문 전부 (런미새 톤) | 각 화면/엔진에 동일 문자열 |

**v1 범위에서 제외 (이식하지 않음):** `FormEngine`(주법 — HC에 수직진폭·접촉시간·보폭
레코드 타입이 없음), `RaceEngine`·`RaceStore`·대회 탭, `CourseSupplyEngine`·
`NearbySupplyEngine`·코스 탭, `TodayScreen`(오늘 탭), `WeatherStore`/`AirQualityStore`의
오늘 탭 전용 부분(홈 브리핑에 필요한 만큼만 이식), 공유 카드, 백그라운드·알림.

### 신규 디렉터리 구조

```
android/
├── settings.gradle.kts
├── build.gradle.kts                    # 루트 — 플러그인 버전만
├── gradle/libs.versions.toml           # 버전 카탈로그
├── gradle.properties
├── local.properties                    # (gitignore) sdk.dir + MAPS_API_KEY
└── app/
    ├── build.gradle.kts                # minSdk 34, compose, HC client, Maps
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # HC 권한 선언 + Maps 키 주입
        │   ├── assets/AirStations.json
        │   └── java/com/jkpark/runwrap/
        │       ├── RunWrapApp.kt       # Application
        │       ├── MainActivity.kt     # 단일 액티비티 + NavHost
        │       ├── engine/             # 순수 로직 14종 — android.* import 금지
        │       ├── health/             # HC 접근 전담 (스토어 계층)
        │       ├── net/                # Weather·AirQuality 클라이언트
        │       ├── store/              # ReportCache, SettingsStore
        │       ├── ui/                 # Theme, Format, charts/
        │       └── screen/             # Compose 화면 5종
        ├── debug/java/com/jkpark/runwrap/seeder/
        │   └── HealthConnectSeeder.kt  # 합성 러닝 데이터를 HC에 주입 (디버그 빌드 전용)
        └── test/java/com/jkpark/runwrap/engine/   # 엔진 테스트 (JUnit)
```

---

## 1. 확정 결정 모음

| 항목 | 확정값 | 근거 |
|---|---|---|
| 기술 스택 | Kotlin + Jetpack Compose 네이티브, iOS 3계층(엔진/스토어/화면) 미러링 | 사용자 확정 |
| 데이터 소스 | **Health Connect 단일 경로**. 삼성헬스 Data SDK 직접 연동은 배제 | 파트너십 승인 필요 — [Samsung Developer](https://developer.samsung.com/health/android/overview.html) (구 SDK 2025.7 deprecated) |
| 계획 범위 | 리포트 코어: 온보딩+홈+리포트+세션 상세+설정 | 사용자 확정 — 오늘/코스/대회 탭은 후속 |
| minSdk | 34 (Android 14) — HC 내장이라 별도 설치 안내 불필요 | 사용자 확정 |
| 배포 목표 | 개인 테스트(사이드로드/내부 테스트)까지. Play 신고·승인·출시는 후속 계획 | 사용자 확정 |
| 지도 | Google Maps SDK (Compose 지원). API 키는 `local.properties` 주입, **커밋 금지** | 사용자 확정 |
| 검증 기기 | 갤럭시폰만(워치 없음) → 실데이터는 폰 GPS 러닝으로, 워치 유래 데이터는 **HC 시더**로 검증 | 사용자 확정 |
| 히스토리 권한 | `PERMISSION_READ_HEALTH_DATA_HISTORY` 요청 (기본 30일 제한 해제 — ACWR 4주·PR 8주에 필수) | [Android Developers](https://developer.android.com/health-and-fitness/health-connect/read-data) |
| 주법 리포트 | v1 제외 — 세션 상세에서 섹션 미노출 (기존 가드 원칙 그대로) | HC에 러닝 다이내믹스 레코드 부재 — §2.2 |
| 생년월일 | 온보딩에서 직접 입력 (HRmax Tanaka용) — HC에 프로필 데이터 없음 | §2.2 |
| 의존성 원칙 | AndroidX/Jetpack + HC client + Maps SDK + kotlinx.serialization만. 서드파티 차트·네트워킹·DI 금지 (네트워크는 `HttpURLConnection`) | iOS "의존성 0" 철학을 Android에 적용 (가정) |
| 레포 구조 | 같은 레포 `android/` 디렉터리, feat/android 브랜치 | 사용자 지시(별도 브랜치) + (가정) |
| applicationId | `com.jkpark.runwrap` (표시 이름 "런미새") | (가정) |
| 영속화 | DataStore Preferences(@AppStorage 대응) + filesDir JSON(ReportCache 대응) | (가정) — iOS와 동일 구조 |
| 테스트 | JUnit 5 + kotlin.test assertion, 고정 시각(`Instant` 주입) 결정론 유지 | (가정) |
| JSON | kotlinx.serialization (Codable 대응) | (가정) |
| HRV 산식 | iOS는 SDNN, HC는 **RMSSD** 레코드 — BatteryEngine이 개인 기준선 대비 상대 변화만 쓰므로 지표 교체 허용, 주석에 명시 | 오픈 이슈 #4 |

---

## 2. 아키텍처 · 데이터 가용성

### 2.1 런타임 구조

```
[갤럭시워치] ─(삼성헬스 자체 동기화)─> [폰 삼성헬스 앱]
                                          │ 사용자가 설정에서 HC 연동 켜야 함 (온보딩 안내)
                                          ▼
                                   [Health Connect]
                                          │ readRecords (30일 초과분은 HISTORY 권한)
                                          ▼
                              [HealthConnectStore (@MainActor 대응 ViewModel)]
                                          │ WorkoutSummary 등 iOS와 동일한 모델로 변환
                                          ▼
                              [엔진 14종 — iOS와 동일한 순수 로직]
                                          │ RRTone까지만 결정 (UI 모름)
                                          ▼
                              [Compose 화면 — RR 토큰·Format 재사용]
```

| 영역 | 선택 | 비고 |
|---|---|---|
| UI | Jetpack Compose (BOM 최신) | 단일 Activity + Navigation |
| 건강 데이터 | `androidx.health.connect:connect-client` | 읽기 전용 |
| 상태 관리 | ViewModel + StateFlow, 상태는 iOS와 동일한 `sealed interface State`(idle/loading/loaded/unavailable/failed) | @Observable 같은 별도 프레임워크 없음 |
| 차트 | Compose Canvas 자체 구현 (RRCharts 이식) | 막대 위 값 표시·탭 콜아웃 규칙 유지 |
| 지도 | Google Maps SDK + maps-compose | 경로 동기화 M0 판정에 따라 미노출 가드 |
| 네트워크 | `HttpURLConnection` + kotlinx.serialization | open-meteo·대기질 — 좌표 외 개인 데이터 전송 없음 |

### 2.2 데이터 가용성 매핑 (실현 가능성 판정)

iOS `HealthPermissions.swift`의 요청 타입 전수를 HC로 매핑한 결과.
근거: [삼성 공식 동기화 매핑](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect),
[HC 데이터 타입](https://developer.android.com/health-and-fitness/health-connect/data-types).

| iOS (HealthKit) | HC 레코드 | 판정 | 소비처 |
|---|---|---|---|
| workoutType | `ExerciseSessionRecord` (RUNNING) | ✅ 동기화 확인 | 전 화면 |
| workoutRoute | `ExerciseRoute` (세션 내포, 레코드별 권한) | ⚠️ **M0 검증** — 삼성 매핑표에 없음 | 세션 상세 지도 |
| distanceWalkingRunning | `DistanceRecord` | ✅ | 리포트·ACWR |
| heartRate (시리즈) | `HeartRateRecord` | ✅ (운동 중 시리즈 동기화 명시) | 존·드리프트 |
| stepCount | `StepsRecord` | ✅ | 케이던스 근사 |
| — 케이던스 시리즈 | `StepsCadenceRecord` | ⚠️ M0 검증 | 세션 상세 |
| activeEnergyBurned | `TotalCaloriesBurnedRecord` | ✅ | 세션 칼로리 |
| vo2Max | `Vo2MaxRecord` | ✅ | 심폐 체력 추이 |
| runningPower | `PowerRecord` | ✅ (매핑표 명시) | 세션 상세 |
| 주법 3종 (진폭·접촉·보폭) | **레코드 타입 없음** | ❌ | 주법 섹션 → 미노출 |
| heartRateVariabilitySDNN | `HeartRateVariabilityRmssdRecord` | ⚠️ M0 검증 (+SDNN→RMSSD 교체) | 체력 배터리 |
| restingHeartRate | `RestingHeartRateRecord` | ⚠️ M0 검증 | 체력 배터리 |
| respiratoryRate | `RespiratoryRateRecord` | ⚠️ M0 검증 | 체력 배터리 |
| appleSleepingWristTemperature | `SkinTemperatureRecord` | ⚠️ M0 검증 | 체력 배터리 |
| heartRateRecoveryOneMinute | **레코드 타입 없음** | ❌ | 배터리 → 해당 입력 제외 |
| sleepAnalysis | `SleepSessionRecord` (+stages) | ✅ | 체력 배터리 |
| dateOfBirth | **없음** → 온보딩 입력 | ❌→대체 | HRmax(Tanaka) |

**설계 귀결:** 엔진의 "표본 부족 시 미노출(nil)" 원칙이 그대로 데이터 결손을 흡수한다 —
⚠️/❌ 재료는 엔진 입력을 옵셔널로 유지하고, 없으면 해당 지표만 조용히 빠진다.
iOS 코드를 고칠 일은 없다.

---

## 3. 마일스톤

## M0 — 스캐폴드 + 실기기 데이터 검증 스파이크

### 목표
`android/` Gradle 프로젝트가 빌드·실행되고, 갤럭시 실기기에서 삼성헬스→HC로 실제
어떤 레코드가 오는지 §2.2의 ⚠️ 전 항목이 판정된다.

### 산출물
- `android/` 프로젝트 전체 스캐폴드 (신설 — §0 트리의 빌드 파일 + 빈 앱)
- `app/src/main/java/com/jkpark/runwrap/health/HealthPermissions.kt` — 신설: 요청 권한 셋 (iOS 기능별 분리 철학 유지)
- `app/src/debug/java/com/jkpark/runwrap/debug/RecordDumpScreen.kt` — 신설: 디버그 전용, 최근 30일 레코드 타입별 건수·샘플 덤프
- `docs/plan/android-m0-검증노트.md` — 신설: ⚠️ 항목별 판정 결과 기록
- `.gitignore` — 수정: `android/local.properties`, `android/.gradle`, `android/app/build` 추가

### 핵심 작업
1. Gradle 스캐폴드 (버전 카탈로그, minSdk 34, Compose, HC client).
2. HC 권한 요청 흐름:
```kotlin
val permissions = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    // …§2.2 ✅/⚠️ 타입 전부
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
)
val launcher = registerForActivityResult(
    PermissionController.createRequestPermissionResultContract()) { granted -> … }
```
3. RecordDumpScreen: 타입별 `readRecords` → 건수·최신 샘플·데이터 오리진(`metadata.dataOrigin`) 표시. ExerciseRoute는 세션에서 `ExerciseRouteResult`로 확인.
4. 갤럭시폰 삼성헬스에서 **폰 GPS 러닝 1회 기록** → HC 연동 켜고 덤프 확인. 워치 유래 타입(VO₂max·수면·HRV 등)은 삼성헬스에 기존 데이터가 있으면 그것으로, 없으면 "판정 불가(워치 필요)"로 기록.

### 완료 기준
- `cd android && ./gradlew :app:assembleDebug` 성공 (경고 0 기준선 기록)
- 실기기에서 권한 시트 표시 → 허용 → 러닝 세션 1건 이상 덤프 화면에 표시
- `android-m0-검증노트.md`에 §2.2 ⚠️ 5항목(경로·케이던스·HRV·안정심박·호흡수/피부온) 판정 기재
- iOS 쪽 빌드·테스트 무영향 (Swift 파일 변경 0)

## M1 — 공통 기반: 테마·포맷·영속화

### 목표
RR 디자인 토큰·Format·저장 계층이 Compose에서 동작하고 단위 테스트가 돈다.

### 산출물
- `ui/theme/Theme.kt` — 신설: RR 색 토큰(라이트/다크), RRTone 4단계 → 색·라벨 매핑 (iOS `Theme.swift` 수치 그대로)
- `ui/Format.kt` — 신설: `Format.pace/distance/duration/weekLabel` 등 (ISO 주차 미노출, "8월 2째주" 규칙 포함)
- `store/SettingsStore.kt` — 신설: DataStore Preferences (`didConnectHealth` 등 iOS @AppStorage 키 대응)
- `store/ReportCache.kt` — 신설: filesDir JSON 캐시 (kotlinx.serialization)
- `app/src/test/…/FormatTest.kt` — 신설: iOS Format 테스트 기대값 이식

### 핵심 작업
`RRTone` enum + Theme 매핑을 한 파일에 고정(화면에서 색 리터럴 금지 — iOS 규칙 유지).
Format은 `java.time`(KST 고정, 목요일 기준 주 판정) 사용.

### 완료 기준
- `./gradlew :app:testDebugUnitTest` — FormatTest 전체 통과 (weekLabel 경계 케이스 포함)

## M2 — 엔진 이식 1차 (리포트 축)

### 목표
ReportMetrics·ReportEngine·BatteryEngine·DriftEngine이 Kotlin에서 iOS와 동일한
기대값으로 테스트를 통과한다.

### 산출물
- `engine/ReportMetrics.kt`, `engine/ReportEngine.kt`, `engine/BatteryEngine.kt`, `engine/DriftEngine.kt` — 신설 (산식·출처 주석 유지)
- `app/src/test/…/engine/` 대응 테스트 4스위트 — 신설: iOS 기대값·고정 시각 그대로

### 핵심 작업
- 이식 규칙 고정: `Date`→`Instant`, `Calendar`(KST)→`ZoneId.of("Asia/Seoul")`, 구조체→`data class`, case 없는 enum→`object`. `android.*` import 금지 (엔진 순수성 — 코드리뷰 기준).
- BatteryEngine: HRR 입력 제거·HRV를 RMSSD 기준으로 받도록 입력 모델만 조정 (산식은 상대 변화라 유지). 입력 전부 옵셔널 — 없으면 해당 신호 제외 (iOS 가드 동일).
```kotlin
data class RecoverySignals(          // iOS BatteryEngine.Inputs 대응
    val hrvRmssd: List<DatedSample>?,   // HC HeartRateVariabilityRmssd — 없으면 신호 제외
    val restingHR: List<DatedSample>?,
    val respiratoryRate: List<DatedSample>?,
    val skinTemperature: List<DatedSample>?,
    val sleep: List<SleepNight>?,
)
```

### 완료 기준
- 엔진 4종 테스트 전체 통과 (iOS 대응 케이스 수와 동일 — 이식 시 케이스 수를 노트에 기록)
- `grep -r "import android" app/src/main/java/com/jkpark/runwrap/engine/` 결과 0건

## M3 — 엔진 이식 2차 (홈·가이드 축)

### 목표
나머지 엔진 10종(TrainingGuide·TodayVerdict·Growth·Level·Collection·CrossTraining·
WalkRun·Heat·HomeBriefing·AirQuality)이 동일 기대값으로 통과한다.

### 산출물
- `engine/*.kt` 10종 — 신설
- 대응 테스트 스위트 — 신설 (TrainingGuideEngineTests 279줄·AirQualityEngineTests 198줄 포함 전부 이식)

### 핵심 작업
M2와 동일한 이식 규칙. TrainingGuideEngine(546줄)이 최대 단위 — VDOT 역산·주기화·
오늘의 훈련 판정 ①~⑧ 분기 순서를 흐트러뜨리지 않는다 (기획서 §4.9).

### 완료 기준
- `./gradlew :app:testDebugUnitTest` 전체 통과 — 엔진 14종 + Format
- 테스트 수가 iOS 대응 스위트 합계와 일치 (검증노트에 대조표)

## M4 — HC 데이터 계층 + 시더

### 목표
HealthConnectStore가 iOS HealthStore와 동일한 화면용 모델을 내놓고, 시더로 주입한
합성 데이터가 엔진까지 흘러간다.

### 산출물
- `health/HealthConnectStore.kt` — 신설: 세션 목록·주간 집계 로드, `State` sealed interface, HC 미설치/권한 거부 → `unavailable` 분기
- `health/WorkoutDetailStore.kt` — 신설: 세션 1건의 심박 시리즈·페이스 시리즈·(가능 시) 경로·케이던스 로드
- `health/DemoData.kt` — 신설: iOS DemoData 시나리오 이식 (에뮬레이터 분기용)
- `app/src/debug/…/seeder/HealthConnectSeeder.kt` — 신설: DemoData 시나리오를 **HC에 실제 레코드로 insert** (워치 없이 전체 파이프라인 검증 — 쓰기 권한은 debug 빌드에서만 요청)
- `net/WeatherClient.kt`, `net/AirQualityClient.kt` — 신설: iOS와 동일 엔드포인트·응답 모델

### 핵심 작업
```kotlin
suspend fun loadRecentRuns(weeks: Int): List<WorkoutSummary> {
    val sessions = client.readRecords(ReadRecordsRequest(
        ExerciseSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.after(now.minus(weeks * 7L, DAYS)),
    )).records.filter { it.exerciseType == EXERCISE_TYPE_RUNNING }
    // 세션별 distance/calories/HR은 시간 겹침으로 aggregate — iOS statistics 쿼리 대응
}
```
시더는 `client.insertRecords(...)`로 세션+심박 시리즈+거리+VO₂max+수면을 시나리오대로
주입. 삼성헬스 유래가 아니어도 읽기 경로는 동일하므로 파이프라인 검증으로 유효
(오리진 차이는 검증노트에 명시).

### 완료 기준
- 에뮬레이터: 시더 실행 → 세션 목록 화면(임시 리스트)에 합성 러닝 표시
- 권한 미허용 시 크래시 없이 `unavailable` 상태 노출
- 스토어 계층은 HC 의존이라 단위 테스트 제외 (iOS와 동일 정책)

## M5 — 화면 5종 (Compose)

### 목표
온보딩→홈→리포트→세션 상세→설정 흐름이 iOS와 동일한 정보 구조·문안으로 동작한다.

### 산출물
- `screen/OnboardingFlowScreen.kt` — 신설: HC 연결 안내(삼성헬스 연동 켜는 법 포함) + 목적·레벨 + **생년월일 입력**(iOS에 없는 단계) + 권한 직전 CTA "다음" (App Review 대응과 동일 문안)
- `screen/HomeScreen.kt` — 신설: 브리핑·성장(레벨/컬렉션)·오늘의 판정
- `screen/ReportHomeScreen.kt` + `ReportDetailScreen.kt` — 신설: 주간 리포트·체력 배터리·훈련 가이드
- `screen/SessionDetailScreen.kt` — 신설: 세션 지표·심박 존·드리프트·(경로 확인 시) Google Maps 경로 카드. 주법 섹션 없음
- `screen/SettingsScreen.kt` — 신설: 프로필·목표·연결 상태
- `ui/charts/RRCharts.kt` — 신설: 막대(값 상시 표시/탭 콜아웃)·라인(포인트 탭 값 표시)
- `MainActivity.kt`, `RunWrapApp.kt` — 신설: NavHost·탭 2개(홈/리포트)

### 핵심 작업
iOS 화면을 시각 사양서로 삼아 카드 단위로 이식 — `.rrCard()` 대응 `Modifier.rrCard()`,
`ToneBadge`·`Eyebrow` composable부터 만들고 화면 조립. 지도는 M0 판정이 ❌면
카드 자체를 만들지 않는다(가드).

### 완료 기준
- 에뮬레이터(시더 데이터): 5화면 전 흐름 도달, 각 화면 스크린샷을 iOS 시뮬레이터
  스크린샷과 나란히 대조 — 정보 구조·톤 문안 일치
- 데이터 0건 상태: 모든 지표 카드가 미노출 가드로 비고 빈 화면 안내 문구 표시

## M6 — 통합 검증 (실기기)

### 목표
갤럭시 실기기에서 삼성헬스 실데이터로 리포트 코어가 완주된다.

### 산출물
- `docs/plan/android-m0-검증노트.md` — 수정: 실기기 E2E 결과 추가 (최종 판정표)

### 핵심 작업
1. 실기기 사이드로드 → 온보딩 → 삼성헬스 HC 연동 → 폰 GPS 러닝 최소 3회(주간 지표
   표본) 누적 후 홈·리포트·세션 상세 확인.
2. 히스토리 권한 동작 확인: 설치 30일 이전 데이터 조회 여부 (삼성헬스 기존 기록 활용).
3. 워치 유래 지표(VO₂max·수면·HRV·심박 시리즈)는 시더 검증으로 갈음하고 미검증
   항목을 오픈 이슈 #1로 남긴다.

### 완료 기준
- 실기기에서 실데이터 세션 목록·주간 거리·ACWR(4주 표본 시)·세션 상세 표시
- 검증노트 최종 판정표 완성 — ⚠️ 항목 전부 ✅/❌/워치필요 셋 중 하나로 확정

---

## 4. 의존성 그래프 · 병렬화 지점

```
M0 ─→ M1 ─→ M2 ─→ M3 ─┬→ M4 ─→ M5 ─→ M6
                      └────────↗ (M4는 M3와 병렬 가능)
```

- M4(HC 데이터 계층)는 엔진이 아니라 모델 정의(M2)에만 의존 — M3(엔진 2차)와 병렬 가능.
- M2·M3 엔진 이식은 파일 단위로 독립 — 내부에서 얼마든지 쪼개 진행 가능.
- M5는 M3(홈 화면 엔진)+M4(데이터) 완료가 전제. M0 판정은 M5의 지도 카드 유무만 바꾼다.

## 5. 위험 요소와 완화책

| 위험 | 확률 | 영향 | 완화책 |
|---|---|---|---|
| 삼성헬스→HC 동기화 범위가 문서와 다름 (버전·기기 편차) | 중 | 높음 | M0 스파이크를 최우선 배치 — 판정 후 화면 계획(지도·배터리 카드)만 조정, 엔진은 가드가 흡수 |
| 워치 없이 워치 유래 데이터 미검증 | 확실 | 중 | HC 시더로 파이프라인 검증 분리, 실데이터 검증은 오픈 이슈로 명시 이월 |
| HC 심박 시리즈 입도가 드리프트·존 계산에 부족 | 중 | 중 | M0 덤프에서 샘플 간격 실측 → 드리프트 최소 표본 가드 임계값 조정 |
| 30일 히스토리 권한을 사용자가 거부 | 중 | 중 | ACWR·8주 PR만 미노출(가드), 권한 필요 이유를 온보딩 문안에 명시 |
| Maps API 키 유출 | 낮음 | 중 | `local.properties` 주입 + gitignore를 M0에서 선제 처리, 키 없으면 지도 카드만 빈 상태 |
| Kotlin 이식 중 산식 미세 편차 (반올림·달력) | 중 | 높음 | iOS 테스트 기대값을 소수점까지 그대로 이식 — 편차는 테스트가 즉시 잡는다 |

## 6. 신규 파일 목록

```
android/  (빌드 파일 5종: settings/build/libs.versions.toml/gradle.properties/app/build)
android/app/src/main/AndroidManifest.xml
android/app/src/main/assets/AirStations.json          (iOS에서 복사)
android/app/src/main/java/com/jkpark/runwrap/
  RunWrapApp.kt  MainActivity.kt
  engine/  ReportMetrics.kt ReportEngine.kt BatteryEngine.kt DriftEngine.kt
           TrainingGuideEngine.kt TodayVerdictEngine.kt GrowthEngine.kt LevelEngine.kt
           CollectionEngine.kt CrossTrainingEngine.kt WalkRunEngine.kt HeatEngine.kt
           HomeBriefingEngine.kt AirQualityEngine.kt
  health/  HealthPermissions.kt HealthConnectStore.kt WorkoutDetailStore.kt DemoData.kt
  net/     WeatherClient.kt AirQualityClient.kt
  store/   SettingsStore.kt ReportCache.kt
  ui/      Theme.kt Format.kt charts/RRCharts.kt
  screen/  OnboardingFlowScreen.kt HomeScreen.kt ReportHomeScreen.kt
           ReportDetailScreen.kt SessionDetailScreen.kt SettingsScreen.kt
android/app/src/debug/java/com/jkpark/runwrap/
  debug/RecordDumpScreen.kt  seeder/HealthConnectSeeder.kt
android/app/src/test/java/com/jkpark/runwrap/
  FormatTest.kt  engine/<엔진 14종 대응 테스트>
docs/plan/android-m0-검증노트.md
(.gitignore 수정 1건)
```

## 7. 완료 체크리스트

- [ ] M0: 실기기 HC 덤프 + ⚠️ 5항목 판정 노트 + 스캐폴드 빌드 성공
- [ ] M1: Theme·Format·저장 계층 + FormatTest 통과
- [ ] M2: 리포트 축 엔진 4종 테스트 통과 (android import 0건)
- [ ] M3: 엔진 14종 전체 테스트 통과 (iOS 케이스 수 대조 일치)
- [ ] M4: 시더 합성 데이터가 세션 목록까지 표시, 권한 거부 시 unavailable
- [ ] M5: 5화면 흐름 완주 + iOS 스크린샷 대조 + 데이터 0건 가드 확인
- [ ] M6: 실기기 실데이터 리포트 코어 완주 + 최종 판정표
- [ ] 전체: 갤럭시 실기기에서 온보딩→러닝 기록→홈·리포트·세션 상세 확인이 실데이터로 완주

## 8. 오픈 이슈

| # | 이슈 | 채택한 기본값 | 다르게 결정되면 |
|---|---|---|---|
| 1 | 갤럭시워치 실데이터 검증 불가 (기기 없음) | 시더 검증으로 갈음, 미검증 항목 명시 | 워치 확보 시 M6 재실행으로 판정표 갱신 |
| 2 | GPS 경로·케이던스·HRV·안정심박·피부온 동기화 여부 | M0에서 판정, 그때까지 화면은 가드 전제 | ❌ 판정 시 지도 카드·해당 지표 영구 미노출 |
| 3 | Play Store 출시 (건강 데이터 신고·승인·CI) | 이번 범위 제외 | 출시 결정 시 별도 계획서 (신고 절차가 마일스톤급) |
| 4 | HRV SDNN→RMSSD 교체의 산식 타당성 | 상대 변화 기반이라 유지 (가정) | 실데이터에서 변동폭 이질적이면 배터리 가중치 재조정 |
| 5 | 오늘·코스·대회 탭 + 공유 카드 + 알림 포팅 | 후속 계획서로 분리 | — |
| 6 | applicationId·패키지명 `com.jkpark.runwrap` | (가정) | Play 등록 전이면 변경 비용 0 |
| 7 | 앱 아이콘·스플래시 에셋 | iOS 에셋 재활용해 Android 리소스로 변환 (가정) | 별도 제작 시 M5에 반나절 추가 |
