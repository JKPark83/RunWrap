# RunWrap 개발 계획서 (v0.1)

작성일: 2026-08-11 | 버전: v0.2 | 베이스 커밋: main @ ad45d7c

> v0.2 개정: 다이어트 모드에 몸무게 추이 추가(M2), 실내/야외 통계 분리 집계 철회 — 세션 배지만 유지(M1). 기획서 v0.3 대응.

**한 줄 요약:** 기획서 v0.2 로드맵의 남은 3~12단계(실내/야외 구분 → 수분 알람)를 M0~M9 마일스톤으로 구체화한다 — 1·2단계(HealthKit 연결, 리포트 엔진)는 구현 완료라 범위에서 제외.

관련 문서: [러너 리포트 앱 기획서 v0.2](기획서-v0.1.md)

---

## 목차

- [0. 전제 — 기존 코드 재활용 맵](#0-전제--기존-코드-재활용-맵)
- [1. 확정 결정 모음](#1-확정-결정-모음)
- [2. 아키텍처](#2-아키텍처)
- [3. 마일스톤 M0~M9](#3-마일스톤)
- [4. 의존성 그래프 · 병렬화 지점](#4-의존성-그래프--병렬화-지점)
- [5. 위험 요소와 완화책](#5-위험-요소와-완화책)
- [6. 신규 파일 목록](#6-신규-파일-목록-전체)
- [7. 완료 체크리스트](#7-완료-체크리스트)
- [8. 오픈 이슈](#8-오픈-이슈)

---

## 0. 전제 — 기존 코드 재활용 맵

코드베이스 스캔으로 실제 확인한 경로만 적는다. 기준: `ios/RunWrap/`.

| 구분 | 재활용 (그대로/확장) | 신설 (새로 만든다) |
|---|---|---|
| 데이터 모델 | `RunSummary.swift` — `isIndoor`·`calories` 필드 추가 | `UserProfile.swift` — 목적·레벨·목표 기록 |
| HealthKit | `HealthStore.swift` — `readTypes` 확장(activeEnergyBurned·bodyMass, 러닝 다이내믹스 4종), `summary(of:)`에 메타데이터 읽기 추가, `withCheckedThrowingContinuation` 래핑 패턴 그대로 | HealthStore 확장으로 `HKObserverQuery` + `enableBackgroundDelivery` (M8) |
| 세션 상세 | `WorkoutDetailStore.swift` — `fetchQuantitySamples` 링크/폴백 패턴을 다이내믹스 4종에 그대로 적용, `HKMetadataKeyElevationAscended` 읽던 자리에 `HKMetadataKeyIndoorWorkout` 동일 패턴 | — |
| 엔진 | `ReportEngine.swift`·`ReportMetrics.swift` — 미노출 가드 패턴(`acwr`의 21일·3km 가드)을 신규 엔진의 표본 가드 템플릿으로. `MonthlyStats.compute`를 다개월 시리즈로 반복 호출. `BatteryEngine.swift` — `BatteryReport.tone`을 훈련 처방 하향 보정 입력으로 | `FormEngine.swift`(주법 룰), `ProgressStats.swift`(월별 시리즈+PR), `TrainingGuideEngine.swift`(Riegel·처방), `OutfitRules.swift`(복장 룰) |
| 화면 | `StatsScreen.swift` 세션 목록(배지·발전상 섹션), `SessionDetailScreen.swift`(배지·주법 섹션·공유 진입 — `shareTeaser` 자리), `ReportHomeScreen.swift`(프로필별 카드 전환·훈련 가이드 카드), `OnboardingScreen.swift`(프로필 스텝 추가), `RootView.swift`(3번째 탭) | `SettingsScreen.swift`, `TodayScreen.swift`, `ShareCard.swift` |
| 차트/토큰 | `RRCharts.swift` — `TrendLineChart`(월별 추이), `WeeklyBarsChart`, `SparkLine`. `Theme.swift` — RR 토큰·`RRTone`·`.rrCard()`·`ToneBadge`·`Eyebrow`·`Format` 전부 그대로 | — |
| 저장 | `RootView.swift:6` `@AppStorage("didConnectHealth")` — 프로필·토글의 저장 패턴 | `ReportCache.swift` — Codable JSON 파일 (Application Support) |
| 알림/날씨/위치 | (기존 코드 없음 — 스캔 확인: UNUserNotificationCenter·URLSession·CLLocationManager 사용처 0건) | `NotificationScheduler.swift`, `WeatherClient.swift`, `LocationProvider.swift` |
| 합성 데이터 | `DemoData.swift`·`WorkoutDetailStore.synthetic(for:)` — 각 마일스톤에서 신규 필드 동반 확장 | — |
| 빌드 설정 | `ios/project.yml` — Info.plist 키·entitlements 추가는 전부 이 파일에서 (xcodegen 재생성 원칙) | — |
| 삭제 | — | `WorkoutListScreen.swift`·`ReportSection.swift` — RootView에 연결되지 않은 1단계 레거시, M1에서 제거 (사용자 확정) |

---

## 1. 확정 결정 모음

| 항목 | 확정값 | 근거 |
|---|---|---|
| 공유 카드 디자인 | 미니멀 데이터형 기본 + 사진 배경형 간이 버전, 둘 다 시제작 후 게이트에서 실물 비교 판정 | 사용자 답변 |
| 인스타 연동 | **공유 시트(ShareLink)만** — `instagram-stories://` 스킴 미사용 | 사용자 답변. 스킴은 Meta App ID 등록이 필수로 확인됨([Meta 2022-10 공지](https://developers.facebook.com/blog/post/2022/10/10/introducing-important-update-to-Instagram-sharing-to-stories/)) — 오픈 이슈 #3 |
| 수익 모델 (v1) | 완전 무료 — 광고·구독 없음 | 사용자 답변. Open-Meteo 비상업 요건과 연동 |
| 날씨 API | Open-Meteo — 키 없이 무료, 비상업(광고·구독 없는 앱) 요건 충족. 출처 표기 "Weather data by Open-Meteo.com (CC BY 4.0)" + 링크를 오늘 탭 하단에 | [Open-Meteo Terms](https://open-meteo.com/en/terms) · [Pricing](https://open-meteo.com/en/pricing) — 무료 티어 1만 호출/일 |
| 복장 일러스트 | claude.design "Runner Report" 시안 확장으로 SVG/PNG 세트 제작 → Assets 내장 | 사용자 답변 |
| 다이어트 카드 구성 | 소모 중심 — 주간 소모 합계 + 4주 추이 + 연속 훈련 주 streak + **몸무게 추이**(bodyMass 주 단위 평균). 사용자 입력 없음 | 사용자 답변 (몸무게 추이는 2026-08-11 수정 요청) |
| 저장 계층 | 리포트 캐시 = Codable JSON 파일(Application Support), 프로필·토글 = `@AppStorage`. **SwiftData 미도입** | 사용자 답변 — 영속 데이터가 "캐시 1건+설정 몇 개"로 작음 |
| '오늘' 탭 위치 | 현재 위치만 (CoreLocation WhenInUse). 지정 위치는 v1 제외 | 사용자 답변 — 오픈 이슈 #2 |
| 알림 범위 (v1) | 운동 직후 + 매주 2종. '매일'은 v1 제외 | 사용자 답변 — 오픈 이슈 #1 |
| 레거시 정리 | `WorkoutListScreen.swift`·`ReportSection.swift`를 M1에서 삭제 | 사용자 답변. 스캔으로 RootView 미연결 확인 |
| 실내/야외 구분 범위 | 세션 표시(목록·상세 배지)만 — **통계·리포트는 통합 집계** (분리 집계 철회) | 사용자 수정 요청 (기획서 v0.3 반영) |
| 백그라운드 아키텍처 | 포그라운드 진입(잠금 해제) 시 재계산이 1차 트리거, observer는 앞당김 보조. `BGAppRefreshTask` 미사용 (가정) | 리서치 — HK DB는 잠금 ~10분 후 접근 불가([Apple 보안 가이드](https://support.apple.com/guide/security/sec88be9900f/web)), observer 빈도 기기별 편차 큼([포럼](https://developer.apple.com/forums/thread/814914)), BGTask는 기회주의적 실행 — 오픈 이슈 #4 |
| 러닝 다이내믹스 | `.runningVerticalOscillation`(cm)·`.runningGroundContactTime`(ms)·`.runningStrideLength`(m)·`.runningPower`(W) — iOS 16+/watchOS 9+, SE·S6 이상, **실외 전용**(애플 공식 확인). 없으면 섹션 미노출 | 리서치 — [outdoor-only 공식 답변](https://developer.apple.com/forums/thread/714484) |
| 케이던스 도출 | `runningSpeed ÷ runningStrideLength` 우선, `stepCount` 버킷 폴백(실내·구형 기기) | 리서치 — [케이던스 스레드](https://developer.apple.com/forums/thread/708208) |
| 주법 조언 룰 | 절대 임계값 대신 **최근 4주 야외 개인 기준선 대비 변화율**. 절대 참고 밴드(VO 4~10cm, GCT 150~300ms)는 극단 이탈 감지에만 (가정) | 기획서 §4.8 + 리서치 — [Heiderscheit 2011](https://pubmed.ncbi.nlm.nih.gov/20581720/)(케이던스 +5~10% 개입 근거), Garmin도 백분위/비율 정규화 사용 |
| 지도 스냅샷 | `MKMapSnapshotter` (라이브 `Map`은 ImageRenderer에서 렌더 안 됨) | 스캔 + SwiftUI 제약 |
| PR 판정 룰 | 목표 거리 D에 대해 완주 거리 ∈ [D, D×1.10]인 세션 중 `평균페이스 × D` 최소값 + 달성일 (가정) | 세션 평균 기반 추정 — 오픈 이슈 #5 |
| 수분 알람 기준 | 일 최고기온 예보 ≥ 25°C → 러닝 시간대 1시간 전 알림 (가정) | 기획서 §4.10 예시값 채택 |
| 주간 알림 시각 | 일요일 18:00 기본, 설정에서 변경 (가정) | 기본값 제안 |

---

## 2. 아키텍처

```
[애플워치] ──동기화──> [iPhone HealthKit]
                            │
              ┌─────────────┼──────────────────┐
              │ (포그라운드 재계산 = 1차)        │ (observer 웨이크 = 보조)
              ▼                                ▼
        [HealthStore]                HKObserverQuery(.workoutType)
              │                       └─ 계산 가능 시 즉시 알림 발송
              ▼
   [엔진 계층 — Foundation 순수]
    ReportEngine / ReportMetrics / BatteryEngine
    + ProgressStats + FormEngine + TrainingGuideEngine   ← 신설
              │
              ├──> [ReportCache — JSON 파일] ──> [NotificationScheduler]
              │                                   ├ 운동 직후 인사이트 알림
              │                                   ├ 주간 리포트 정시 알림 (캐시 본문)
              │                                   └ 수분 알람 (예보 조건부)
              ▼
   [화면 — 탭 3개]
    리포트(ReportHome) · 통계(Stats) · 오늘(Today←신설)
              │ 세션 상세 "공유하기"
              ▼
    [ShareCard → ImageRenderer + MKMapSnapshotter]
              ▼
    ShareLink 공유 시트 / 사진 저장           [오늘 탭]
                                             WeatherClient(Open-Meteo, 좌표만 전송)
                                             + LocationProvider + OutfitRules
```

이 계획이 건드리는 부분만:

| 영역 | 선택 | 비고 |
|---|---|---|
| 저장 | Codable JSON 파일 + `@AppStorage` | SwiftData 미도입 (§1 확정) |
| 알림 | UserNotifications + HK background delivery | entitlement `com.apple.developer.healthkit.background-delivery` — project.yml에 추가 |
| 날씨 | Open-Meteo `/v1/forecast` | 키 불필요. 전송 데이터는 좌표뿐 — 건강 데이터 미전송 원칙 유지 |
| 위치 | CoreLocation WhenInUse | `NSLocationWhenInUseUsageDescription` — project.yml에 추가 |
| 카드 이미지 | ImageRenderer + MKMapSnapshotter | 1080×1920(9:16). 사진 저장은 add-only 권한 |
| 공유 | ShareLink(공유 시트) | 인스타 스킴 미사용 (§1 확정) |

---

## 3. 마일스톤

기획서 8장 로드맵과의 대응: M1=3단계, M2=4단계, M3=5단계, M4=6단계, M5=7·8단계(카드+공유 통합, 게이트), M6=9단계, M7=10단계, M8=11단계, M9=12단계.

---

### M0 — 준비: 합성 데이터 6개월 확장

**예상 규모: 반나절**

#### 목표
이후 모든 마일스톤(특히 M3 발전상, M7 훈련 가이드)을 시뮬레이터에서 검증할 수 있도록 DemoData 이력을 6개월로 늘리되, 기존 앱·테스트는 정상 동작을 유지한다.

#### 산출물
- `ios/RunWrap/DemoData.swift` — 재활용·수정: 고정 배열 11개 → 시드 고정 생성 루프 약 6개월(60여 회), 월이 지날수록 페이스가 완만히 향상되는 패턴(M3 추이 차트 검증용)

#### 핵심 작업
기존 `run(daysAgo:km:minPerKm:hr:)` 헬퍼를 유지하고, `WorkoutDetailStore.synthetic`이 쓰는 `SplitMix64` 시드 고정 방식과 동일하게 결정론적으로 생성한다:

```swift
// 주 2~3회 × 26주. 월별 -2초/km 페이스 향상, 거리 6~18km 변주.
static var runs: [RunSummary] {
    var rng = SplitMix64(seed: 0xC0FFEE)
    return (0..<26).flatMap { week in /* 주당 2~3회 생성 */ }
}
```

#### 완료 기준
- `xcodebuild test` 전체 통과 (기존 테스트 무수정)
- 시뮬레이터: 통계 탭 월 선택기에서 6개 이상의 월 이동 가능, 리포트 홈 정상 표시

---

### M1 — 실내/야외 구분 (기획서 §4.6)

**예상 규모: 반나절**

#### 목표
모든 러닝이 실내 여부 플래그를 갖고 목록·상세에 배지가 뜬다. 통계·리포트는 실내/야외 구분 없이 통합 집계를 유지한다(§1 확정). 레거시 화면이 제거된다.

#### 산출물
- `ios/RunWrap/RunSummary.swift` — 수정: `let isIndoor: Bool` 추가
- `ios/RunWrap/HealthStore.swift` — 수정: `summary(of:)`에서 메타데이터 읽기
- `ios/RunWrap/StatsScreen.swift` — 수정: 세션 행 "실내" 배지
- `ios/RunWrap/SessionDetailScreen.swift` — 수정: 헤더 배지, 실내면 지도·고도 섹션 미노출
- `ios/RunWrap/WorkoutDetailStore.swift` — 수정: 실내면 경로 쿼리 생략, `synthetic`도 실내면 route/elevation nil
- `ios/RunWrap/DemoData.swift` — 수정: 주 1회꼴 실내 세션 섞기
- 삭제: `ios/RunWrap/WorkoutListScreen.swift`, `ios/RunWrap/ReportSection.swift`

#### 핵심 작업
```swift
// HealthStore.summary(of:) — 부재 시 야외 취급 (애플 문서: 미기록 = 야외 가정)
let isIndoor = (workout.metadata?[HKMetadataKeyIndoorWorkout] as? Bool) ?? false
```
배지는 기존 `ToneBadge`가 아닌 소형 텍스트 배지(`Eyebrow` 스타일)로 — 상태(톤)가 아니라 종류 표시이므로 RRTone 매핑을 쓰지 않는다.

#### 완료 기준
- 시뮬레이터: 통계 목록에 실내 세션 배지 표시, 실내 세션 상세에 지도 없음, 월간 집계 수치는 실내 포함 총합 그대로
- `xcodebuild test` — 전체 통과 (엔진 수정 없음 — 삭제 파일로 인한 빌드 오류 없음 확인 포함)

---

### M2 — 사용자 프로필 + 다이어트 카드 (기획서 §4.5)

**예상 규모: 2일**

#### 목표
온보딩·설정에서 목적(다이어트/훈련)·레벨(초보/숙련)을 설정할 수 있고, 리포트 홈의 카드 구성과 문장 톤이 프로필에 따라 전환된다. 다이어트 모드에는 칼로리 카드와 함께 몸무게 추이 카드가 뜬다.

#### 산출물
- `ios/RunWrap/UserProfile.swift` — 신설: `enum RunGoal: String, CaseIterable { case diet, training }`, `enum RunLevel: String { case beginner, experienced }` + `@AppStorage` 키 상수
- `ios/RunWrap/SettingsScreen.swift` — 신설: 프로필 변경 (이후 마일스톤의 알림 토글·목표 입력도 이 화면에 추가됨). 진입점: 리포트 홈 툴바 기어 아이콘
- `ios/RunWrap/OnboardingScreen.swift` — 수정: 권한 요청 후 프로필 선택 스텝
- `ios/RunWrap/RunSummary.swift` — 수정: `let calories: Double?` 추가
- `ios/RunWrap/HealthStore.swift` — 수정: `readTypes += HKQuantityType(.activeEnergyBurned)  // 다이어트 카드: 세션 소모 칼로리` + `HKQuantityType(.bodyMass)  // 다이어트 카드: 몸무게 추이`, `summary(of:)`에서 `statistics(for:)` 읽기, `fetchBodyMass(now:)` 신규 쿼리(최근 8주 샘플 — 기존 `quantitySamples` 헬퍼 재활용)
- `ios/RunWrap/ReportMetrics.swift` — 수정: `DietCard`(주간 kcal 합계 + 4주 추이 배열) + `streakWeeks(runs:now:)` (주 1회 이상 달린 ISO 주의 연속 개수) + `WeightTrend`(bodyMass 주 단위 평균 시리즈 + 4주 변화량)
- `ios/RunWrap/ReportHomeScreen.swift` — 수정: `goal == .diet`면 칼로리·몸무게 추이(`TrendLineChart` 재활용)·streak 카드를 앞세우고 ACWR 카드는 뒤로, `.training`이면 현행 유지. `level == .beginner`면 Insight 문장을 쉬운 대체 문구로
- `ios/RunWrap/ReportEngine.swift` — 수정: `Insight`에 초보자용 대체 문장 필드 또는 `ReportEngine(now:level:)` 주입 (엔진은 순수 로직 유지 — level은 값 타입 입력)
- `ios/RunWrap/DemoData.swift` — 수정: kcal + 몸무게 시리즈(완만한 감량 추세) 합성
- `ios/RunWrapTests/ReportMetricsTests.swift` — 수정: streak 계산·주간 kcal 합계·몸무게 주 단위 평균/표본 가드 케이스

#### 핵심 작업
```swift
// streak: now가 속한 ISO 주부터 거꾸로, 러닝 1회 이상인 주가 이어지는 개수
static func streakWeeks(runs: [RunSummary], now: Date) -> Int

// 몸무게 추이: bodyMass 샘플 → ISO 주 단위 평균 → 4주 전 대비 변화량 문장
// 미노출 가드(가정): 최근 8주 측정 3회 미만이면 카드 자체 미노출 — 오픈 이슈 #9
static func weightTrend(samples: [(date: Date, kg: Double)], now: Date) -> WeightTrend?
```
카드 전환은 데이터 가공이 아니라 **표시 순서·포함 여부**만 화면에서 분기한다 (엔진은 프로필을 모른다 — 문장 변형만 level 입력으로).

#### 완료 기준
- 시뮬레이터: 온보딩에서 다이어트+초보 선택 → 홈 최상단에 칼로리·몸무게 추이·streak 카드 표시, 설정에서 훈련으로 전환 → 기존 구성 복귀
- `xcodebuild test` — streak(연속/단절/빈 데이터)·주간 kcal·몸무게(주 평균 산출, 8주 3회 미만 가드) 신규 케이스 통과

---

### M3 — 퍼포먼스 발전상 (기획서 §4.7)

**예상 규모: 1~2일**

#### 목표
통계 탭에서 월별 페이스·EF·거리의 장기 추이와 5K/10K/하프/풀 PR을 볼 수 있다.

#### 산출물
- `ios/RunWrap/ProgressStats.swift` — 신설: `MonthlySeries`(월별 avgPace·avgEF·totalKm 배열 — `MonthlyStats.compute` 반복 호출로 구성) + `PersonalRecords`(거리별 최고 기록·달성일)
- `ios/RunWrap/StatsScreen.swift` — 수정: 월 선택기 아래 "발전상" 섹션 — `TrendLineChart` 재활용(페이스/EF/거리 세그먼트 전환) + PR 하이라이트 카드
- `ios/RunWrapTests/ProgressStatsTests.swift` — 신설

#### 핵심 작업
```swift
// PR 판정 (가정 — §1): D ∈ {5.0, 10.0, 21.0975, 42.195}
// 완주 거리 ∈ [D, D×1.10]인 세션 중 (paceSecPerKm × D) 최소값
struct PersonalRecords {
    struct Entry { let distanceKm: Double; let timeSec: Double; let date: Date }
    static func compute(runs: [RunSummary]) -> [Entry]  // 해당 거리 기록 없으면 항목 자체 미포함
}
```
미노출 가드: 데이터 있는 월이 2개 미만이면 추이 차트 섹션 미노출. EF는 심박 있는 세션만으로 월평균 — 해당 월에 3회 미만이면 그 월은 EF 점 없음 (기존 `efficiency` 가드와 동일 기준).

#### 완료 기준
- 시뮬레이터: 발전상 섹션에 6개월 추이 차트(M0 데이터의 페이스 향상 패턴이 우하향 선으로 보임) + 5K/10K PR 표시
- `xcodebuild test` — PR 판정(범위 매칭·미해당 거리 미노출)·월 시리즈 신규 케이스 통과

---

### M4 — 주법 리포트 (기획서 §4.8)

**예상 규모: 2~3일**

#### 목표
야외 러닝 상세에 주법 섹션(수직 진폭·지면 접촉·보폭·파워·케이던스)과 개인 기준선 대비 조언 문장이 뜨고, 데이터 없는 세션(실내 포함)엔 아무것도 뜨지 않는다.

#### 산출물
- `ios/RunWrap/HealthStore.swift` — 수정: `readTypes += .runningVerticalOscillation, .runningGroundContactTime, .runningStrideLength, .runningPower  // 주법 리포트 (§4.8, 실외 전용)`
- `ios/RunWrap/WorkoutDetailStore.swift` — 수정: `WorkoutDetail`에 `verticalOscillationCm`·`groundContactMs`·`strideLengthM`·`runningPowerW` 추가 (기존 `fetchQuantitySamples` 링크/폴백 패턴 재사용, 세션 평균), 케이던스는 speed÷stride 우선 + stepCount 폴백, `synthetic` 확장(야외만)
- `ios/RunWrap/FormEngine.swift` — 신설: 룰 엔진 (Foundation만, `now` 주입)
- `ios/RunWrap/SessionDetailScreen.swift` — 수정: 주법 카드 (기존 `statsGrid` LazyVGrid 패턴) + 조언 문장
- `ios/RunWrap/ReportHomeScreen.swift` — 수정: 주간 주법 추이 인사이트 1개 (예: 케이던스 4주 추이)
- `ios/RunWrapTests/FormEngineTests.swift` — 신설

#### 핵심 작업
```swift
/// 주법 조언 — 절대 기준이 아니라 최근 4주 야외 개인 기준선 대비.
/// 출처: Heiderscheit 2011(케이던스 +5~10% 개입), Garmin Running Dynamics(비율 정규화)
struct FormEngine {
    var now: Date
    // 기준선: 해당 세션 제외, 최근 28일 야외 러닝 5회 이상 없으면 nil (미노출 가드)
    static func baseline(of: [WorkoutDetail], excluding: UUID) -> FormBaseline?
    // 룰(가정): cadence < 기준선×0.95 → 보폭 조언 / VO > ×1.10 → 진폭 조언 / GCT > ×1.10 → 접촉시간 조언
    // 절대 밴드(VO 4~10cm, GCT 150~300ms)는 극단 이탈("측정 오류 가능성") 감지에만
    func advice(session: FormSnapshot, baseline: FormBaseline) -> [FormAdvice]
}
```
케이던스 폴백 순서: ① `runningSpeed ÷ runningStrideLength × 60` ② 기존 stepCount 방식. 실내 세션은 다이내믹스가 애초에 기록되지 않으므로(애플 공식) 쿼리 결과가 비면 섹션 자체를 미노출 — `isIndoor` 분기와 이중 가드.

#### 완료 기준
- 시뮬레이터: 야외 세션 상세에 주법 카드 + 조언 문장 1개 이상, 실내 세션엔 주법 섹션 없음
- `xcodebuild test` — FormEngine 신규 케이스(기준선 표본 가드로 침묵 / 케이던스 -5% 시 발화 / 정상 범위 침묵, 기대값 산출 주석 포함) 통과

---

### M5 — 공유 카드 + 공유 시트 (기획서 §4.4, 7·8단계 통합) — **게이트**

**예상 규모: 수일**

#### 목표
세션 상세에서 9:16 카드(미니멀형·사진형)를 만들어 미리보고, 사진 저장·공유 시트로 내보낼 수 있다. 끝나면 "내가 올리고 싶은 퀄리티인가" 게이트를 판정한다.

#### 산출물
- `ios/RunWrap/ShareCard.swift` — 신설: `ShareCardView`(미니멀 데이터형 — RR 토큰, 날짜·거리·페이스·심박 존·경로·주간 요약), `PhotoCardView`(간이 사진 배경형 — PhotosPicker + 어둡기 오버레이 + 수치), `RouteSnapshot`(MKMapSnapshotter 래퍼), 렌더 함수
- `ios/RunWrap/SessionDetailScreen.swift` — 수정: `shareTeaser` 플레이스홀더 → 실제 공유 시트 (스타일 토글 + 미리보기 + `ShareLink` + "사진에 저장")
- `ios/project.yml` — 수정: `NSPhotoLibraryAddUsageDescription` 추가

#### 핵심 작업
```swift
// 라이브 Map은 ImageRenderer가 렌더하지 못한다 → MKMapSnapshotter로 정적 이미지 생성
let options = MKMapSnapshotter.Options()
options.region = region(for: route)          // SessionDetailScreen.region(for:) 재활용
options.size = CGSize(width: 1080, height: 720)
// snapshot 위에 polyline을 CGContext로 직접 그린다 (snapshotter는 오버레이 미지원)

// 카드 렌더: 1080×1920 고정
let renderer = ImageRenderer(content: ShareCardView(...))
renderer.proposedSize = .init(width: 360, height: 640); renderer.scale = 3
```
사진 저장은 add-only: `PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAsset(from: image) }`.

#### 완료 기준
- 시뮬레이터: 두 스타일 카드 미리보기 표시(실내 세션은 지도 대신 수치 레이아웃), 사진 앱에 저장 확인, 공유 시트 열림
- **게이트 판정**: 실기기 실데이터 카드로 "인스타에 올리고 싶은가" 자가 판정을 이 문서 체크리스트에 기록. 불합격 시 카드 디자인 회귀, M6 이후는 게이트와 무관하게 진행 가능 (기획서 §8)

---

### M6 — '오늘' 탭: 날씨 + 복장 (기획서 §4.10)

**예상 규모: 3~5일 (일러스트 제작 포함)**

#### 목표
3번째 탭에서 현재 위치의 온도·습도·풍속과 조건에 맞는 복장 일러스트를 볼 수 있다.

#### 산출물
- `ios/RunWrap/WeatherClient.swift` — 신설: Open-Meteo 호출 + Codable 디코드 (앱 유일의 네트워크 코드 — 전송은 좌표뿐)
- `ios/RunWrap/LocationProvider.swift` — 신설: CLLocationManager WhenInUse 래퍼 (HealthStore의 `enum State` 패턴)
- `ios/RunWrap/OutfitRules.swift` — 신설: 순수 룰 — 체감온도 구간 × 강수 × 바람 → 복장 조합
- `ios/RunWrap/TodayScreen.swift` — 신설: 날씨 수치 + 복장 일러스트 + 하단 출처 표기
- `ios/RunWrap/RootView.swift` — 수정: `MainTabs`에 3번째 탭 "오늘" (`sun.max`)
- `ios/RunWrap/Assets.xcassets` — 수정: 복장 일러스트 세트 (claude.design 시안 확장 → PNG 3x)
- `ios/project.yml` — 수정: `NSLocationWhenInUseUsageDescription` 추가
- `ios/RunWrapTests/OutfitRulesTests.swift` — 신설 (fixture JSON 디코드 테스트 포함)

#### 핵심 작업
```
GET https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}
  &current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,precipitation
  &daily=temperature_2m_max&timezone=auto
```
복장 룰 (가정 — 체감온도 기준, 시안 제작 후 미세 조정):

| 체감온도 | 기본 복장 | 조건 가산 |
|---|---|---|
| ≥ 24°C | 싱글렛/반팔 + 반바지 | 강수 → 방수 캡 |
| 16~23°C | 반팔 + 반바지 | 바람 ≥ 8m/s → 바람막이 |
| 8~15°C | 긴팔 + 타이츠 | 강수 → 방수 자켓 |
| 0~7°C | 자켓 + 장갑 | — |
| < 0°C | 방한 상하 + 모자·장갑 | — |

권한 거부 시: 날씨 카드 대신 안내 문구 (`ContentUnavailableView` 패턴). 출처 표기: "Weather data by [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0)".

#### 완료 기준
- 시뮬레이터(위치 시뮬레이션 Apple): 오늘 탭에 온도·습도·풍속 수치 + 조건에 맞는 일러스트 표시, 위치 거부 시 안내 표시
- `xcodebuild test` — OutfitRules 경계값(23.9/24.0°C 등)·응답 fixture 디코드 케이스 통과
- 코드 리뷰 체크: WeatherClient 요청에 좌표 외 데이터 없음

---

### M7 — 훈련 가이드 (기획서 §4.9)

**예상 규모: 1주+**

#### 목표
목표(종목+기록)를 설정하면 매주 진단(Riegel 예상 기록 vs 목표 갭)과 처방(권장 주간 마일리지·LSD 목표·스피드 세션 수)을 받는다. 기록 3주 미만이면 아무것도 처방하지 않는다.

#### 산출물
- `ios/RunWrap/TrainingGuideEngine.swift` — 신설: 진단+처방 엔진 (Foundation만, `now` 주입)
- `ios/RunWrap/UserProfile.swift` — 수정: 목표 종목(`RaceDistance: String enum`)·목표 기록(초) `@AppStorage` 키
- `ios/RunWrap/SettingsScreen.swift` — 수정: 목표 입력 섹션 (종목 피커 + 시:분:초 입력)
- `ios/RunWrap/ReportHomeScreen.swift` — 수정: 훈련 가이드 카드 (`goal == .training` && 목표 설정 시)
- `ios/RunWrap/ReportDetailScreen.swift` — 수정: 진단·처방 상세 섹션 + 산식 출처 표기
- `ios/RunWrapTests/TrainingGuideEngineTests.swift` — 신설

#### 핵심 작업
```swift
/// 진단: Riegel 공식 T2 = T1 × (D2/D1)^1.06 (Riegel 1981)
/// T1 = 실제 RunSummary 중 5km 이상·목표 거리/세션 거리 ≤ 3배인 세션.
/// 최근 1주를 우선하고, 유효 표본이 없을 때만 4주→8주로 넓혀 첫 창의 최적 기록을 쓴다.
static func predictedTime(for goal: RaceDistance, runs: [RunSummary], now: Date) -> Double?

/// 처방 (매주 갱신):
/// - 권장 주간 km = chronic(4주 평균) × 1.0~1.1 — 10% 룰 상한 (ReportEngine.acwr의 chronic 재활용)
/// - LSD 목표 = 권장 주간 × 0.25~0.35, BatteryReport.tone이 overload/caution이면 0.25로 하향 (기획서 §4.9)
/// - 스피드 세션: 주 0~2회 (level == .beginner면 0~1회)
/// 세션 분류 (가정): LSD = 주간 최장 && 거리 ≥ 주간 총거리×0.35 / 스피드 = 페이스가 4주 평균보다 10% 이상 빠름 / 나머지 easy
/// 밸런스: (easy+LSD) : 스피드 비율을 80/20 원칙과 비교 (Seiler 80/20)
```
가드: `ReportEngine.acwr`와 동일 기준 — 기록 3주 미만 또는 chronic < 3km/주면 전체 nil (진단·처방 모두 미노출).

#### 완료 기준
- `xcodebuild test` — Riegel 기대값(예: 5K 25:00 → 10K 52:07, 산출 주석), 세션 분류, 배터리 하향 보정, 3주 미만 가드 케이스 통과
- 시뮬레이터: 설정에서 10K 목표 입력 → 리포트 홈에 진단+처방 카드, 목표 미설정 시 카드 없음

---

### M8 — 백그라운드 + 알림 (기획서 §4.3, 11단계)

**예상 규모: 수일 (가장 까다로움)**

#### 목표
워치 러닝 종료 후 (기기 여건이 허락하는 한 빠르게) 인사이트 알림이 오고, 매주 정해진 시각에 주간 리포트 알림이 온다. 리포트는 사전 계산·캐시된다.

#### 산출물
- `ios/RunWrap/ReportCache.swift` — 신설: 주간 리포트 스냅샷 Codable → Application Support JSON (atomic write, 생성 시각 포함)
- `ios/RunWrap/NotificationScheduler.swift` — 신설: 권한 요청 + 알림 2종 예약/발송. 본문 빌더는 순수 함수로 분리
- `ios/RunWrap/HealthStore.swift` — 수정: `HKObserverQuery` + `enableBackgroundDelivery(.workoutType, frequency: .immediate)` 등록·해제
- `ios/RunWrap/RunWrapApp.swift` — 수정: 앱 기동 시 observer 재등록, 포그라운드 진입 시 재계산→캐시 갱신→주간 알림 재예약
- `ios/RunWrap/SettingsScreen.swift` — 수정: 알림 토글 2개(운동 직후/주간) + 주간 시각 피커 (기본 일 18:00)
- `ios/project.yml` — 수정: entitlements에 `com.apple.developer.healthkit.background-delivery: true`
- `ios/RunWrapTests/NotificationContentTests.swift` — 신설 (본문 빌더·캐시 왕복 테스트)

#### 핵심 작업
아키텍처 (§1 확정 — 리서치 근거): **포그라운드 재계산이 1차, observer는 보조.**

```swift
// observer 콜백 계약: 작업 성패와 무관하게 completionHandler() 반드시 호출
// (3회 미호출 시 background delivery 자체가 중단됨 — 리서치 확인)
let query = HKObserverQuery(sampleType: .workoutType(), predicate: nil) { _, done, _ in
    Task {
        // 잠금 후 ~10분 지나면 HK 읽기 불가 → 읽기 실패는 조용히 skip (다음 포그라운드에서 처리)
        if let insights = try? await recompute() {
            NotificationScheduler.sendWorkoutInsight(insights)  // 즉시 로컬 알림
            ReportCache.save(insights)
        }
        done()
    }
}
```
주간 알림: `UNCalendarNotificationTrigger(dateMatching: DateComponents(weekday: 1, hour: 18), repeats: false)` — 본문은 캐시 최신본. 포그라운드 진입마다 재계산 후 **재예약**해 본문 신선도를 유지한다 (반복 예약이 아니라 매번 갱신 예약).

#### 완료 기준
- `xcodebuild test` — 본문 빌더(인사이트 → 알림 문구)·ReportCache 저장/복원 신규 케이스 통과
- 시뮬레이터: 토글·시각 설정 동작, 설정 반영 시 `pendingNotificationRequests`에 주간 알림 1건 확인
- **실기기(observer는 시뮬레이터 검증 불가)**: 워치 러닝 종료 → 알림 수신(수분~수십분 편차는 정상 — 리서치 근거), 주간 알림 정시 수신

---

### M9 — 수분 섭취 알람 (기획서 §4.10, 12단계)

**예상 규모: 1~2일** | 의존: M6(예보), M8(알림 인프라)

#### 목표
더운 날(예보 최고기온 ≥ 25°C), 설정한 러닝 시간대 1시간 전에 수분 섭취 알림이 오고, 설정에서 끌 수 있다.

#### 산출물
- `ios/RunWrap/NotificationScheduler.swift` — 수정: 수분 알람 규칙 + 예약 (순수 판정 함수 분리)
- `ios/RunWrap/SettingsScreen.swift` — 수정: 수분 알람 토글 + 러닝 시간대 피커 (`@AppStorage`)
- `ios/RunWrapTests/NotificationContentTests.swift` — 수정: 판정 함수 케이스 추가

#### 핵심 작업
```swift
/// 판정(순수 함수): 예보 temperature_2m_max ≥ 25.0(가정, §1) && 토글 on && 러닝 시각 설정됨
/// → 러닝 시각 - 1시간에 UNCalendarNotificationTrigger 예약
/// 예: "오늘 30°C 예보 — 러닝 1시간 전 500ml 마셔두세요"
static func hydrationAlarm(forecastMaxC: Double, runHour: Int, enabled: Bool, now: Date) -> DateComponents?
```
트리거 시점: 앱 진입 또는 오늘 탭 조회로 예보를 받은 때 당일분을 예약한다 (백그라운드 예보 폴링은 하지 않는다 — M8 아키텍처와 동일한 이유).

#### 완료 기준
- `xcodebuild test` — 판정 함수(온도 미달/토글 off/시각 미설정 → nil, 충족 → 러닝 1시간 전) 케이스 통과
- 시뮬레이터: fixture 예보(30°C)로 토글 on → `pendingNotificationRequests`에 수분 알람 확인, off → 제거 확인

---

## 4. 의존성 그래프 · 병렬화 지점

```
M0 ─→ M1 ─→ M2 ─┬→ M3 ─┐
                ├→ M4 ─┼→ M5 (게이트) ─→ M6 ─→ M9
                │      │                       ↑
                └──────┴→ M7            M8 ────┘
```

- M2·M3·M4는 M1 이후 서로 병렬 가능 — 각각 ReportHome/Stats/SessionDetail로 주 수정 화면이 갈려 충돌이 작다.
- M7은 M3(PersonalRecords 재활용)과 M2(SettingsScreen·프로필) 이후.
- M8은 엔진이 이미 있으므로 M2(SettingsScreen) 이후면 착수 가능 — M5~M7과 병렬 가능.
- M9만 M6+M8 둘 다 필요 (기획서 명시 의존).
- 순서는 기획서 8장 배치 원칙(가벼운 확장 → 게이트 → 무거운 기능)을 따르되, 위 병렬 지점은 앞당겨도 안전하다.

---

## 5. 위험 요소와 완화책

| 위험 | 확률 | 영향 | 완화책 |
|---|---|---|---|
| observer 알림 지연·미발화 (기기별 편차 공식 SLA 없음) | 높음 | 중 | 아키텍처 자체를 "포그라운드 재계산 1차"로 설계 (§1 확정). 완료 기준에 편차 허용 명시 |
| 러닝 다이내믹스 기기·OS별 기록 여부 미문서화 | 중 | 중 | 쿼리 결과 비면 미노출 — 기존 가드 원칙이 그대로 방어. 개발자 워치(S9+)로 자가 검증 |
| MKMapSnapshotter 경로 오버레이 품질 | 중 | 중 | polyline 직접 드로잉은 검증된 패턴. M5 첫 작업으로 스파이크 후 카드 레이아웃 진행 |
| claude.design 일러스트 품질 미달 | 중 | 낮음 | 폴백: SF Symbols 조합 (인터뷰 차선안). M6 초반에 시안 1~2장 먼저 뽑아 판정 |
| Open-Meteo 비상업 해석 변경 또는 수익화 전환 | 낮음 | 중 | v1 완전 무료 확정으로 현재 요건 충족. 전환 시 교체 — 오픈 이슈 #6 |
| DemoData만으로 검증한 기능의 실데이터 편차 | 중 | 중 | 각 마일스톤 완료 기준에 실기기 확인 항목을 분리 명시 (M4·M5·M8) |

---

## 6. 신규 파일 목록 (전체)

```
ios/RunWrap/
├── UserProfile.swift            # M2 — 목적·레벨·목표 enum + AppStorage 키
├── SettingsScreen.swift         # M2 — 프로필/목표/알림 토글 (M7·M8·M9에서 확장)
├── ProgressStats.swift          # M3 — 월별 시리즈 + PR 엔진
├── FormEngine.swift             # M4 — 주법 룰 엔진
├── ShareCard.swift              # M5 — 카드 뷰 2종 + MKMapSnapshotter + 렌더
├── WeatherClient.swift          # M6 — Open-Meteo 클라이언트 (유일한 네트워크 코드)
├── LocationProvider.swift       # M6 — WhenInUse 위치 래퍼
├── OutfitRules.swift            # M6 — 복장 룰 (순수)
├── TodayScreen.swift            # M6 — 3번째 탭
├── TrainingGuideEngine.swift    # M7 — Riegel 진단 + 주간 처방
├── ReportCache.swift            # M8 — JSON 파일 캐시
└── NotificationScheduler.swift  # M8 — 알림 3종 (M9에서 확장)

ios/RunWrapTests/
├── ProgressStatsTests.swift         # M3
├── FormEngineTests.swift            # M4
├── OutfitRulesTests.swift           # M6
├── TrainingGuideEngineTests.swift   # M7
└── NotificationContentTests.swift   # M8·M9

삭제: ios/RunWrap/WorkoutListScreen.swift, ios/RunWrap/ReportSection.swift (M1)
```

새 Swift 파일은 sources가 폴더 단위라 `xcodegen generate`만 다시 돌리면 포함된다 (CLAUDE.md).

---

## 7. 완료 체크리스트

- [ ] M0: 6개월 DemoData — 기존 테스트 전체 통과 + 월 선택기 6개월
- [ ] M1: 실내 배지·레거시 삭제 — 배지 표시 확인 + 기존 테스트 전체 통과
- [ ] M2: 프로필 전환 — 다이어트 선택 시 칼로리·몸무게 추이 카드, streak·몸무게 테스트 통과
- [ ] M3: 발전상 — 6개월 추이 차트 + PR 표시, PR 판정 테스트 통과
- [ ] M4: 주법 — 야외 상세에 조언 문장, 실내 미노출, FormEngine 테스트 통과
- [ ] M5: 카드 2종 + 공유 시트 + 사진 저장 — **게이트 판정 기록: (          )**
- [ ] M6: 오늘 탭 — 날씨 수치 + 복장 일러스트 + 출처 표기, OutfitRules 테스트 통과
- [ ] M7: 훈련 가이드 — 진단+처방 카드, Riegel·분류·가드 테스트 통과
- [ ] M8: 알림 — 실기기에서 운동 직후 + 주간 알림 수신
- [ ] M9: 수분 알람 — 조건 판정 테스트 통과 + 예약 확인
- [ ] 전체: 실기기에서 "러닝 종료 → 알림 → 리포트 확인 → 카드 공유" E2E 완주 + `xcodebuild test` 전체 통과

---

## 8. 오픈 이슈

| # | 이슈 | 채택한 기본값 | 다르게 결정되면 |
|---|---|---|---|
| 1 | '매일' 알림 주기 (기획서 §4.3의 3종 중 v1 제외) | 운동 직후+매주만 | 휴식일 처방 본문(배터리 기반) 추가 — M8에 +1일 |
| 2 | '오늘' 탭 지정 위치 | 현재 위치만 | MKLocalSearch 도시 검색 추가 — M6에 +반나절~1일 |
| 3 | `instagram-stories://` 스킴 (Meta App ID 등록 필요) | 공유 시트만 | Meta 개발자 앱 등록 + 스킴 구현 별도 마일스톤 (~반나절, `LSApplicationQueriesSchemes` 추가) |
| 4 | `BGAppRefreshTask` 도입 (가정: 미사용) | observer+포그라운드로 충분 | 주간 알림 본문 신선도 보강용으로만 — M8에 +반나절 |
| 5 | PR 판정 정밀화 (가정: 세션 평균 페이스 × 목표 거리) | 완주 거리 [D, D×1.10] 범위 매칭 | 거리 샘플 기반 구간 최고(rolling best split) 계산 — M3에 +1일 |
| 6 | 수익화 전환 시 날씨 API | Open-Meteo 무료 (v1 완전 무료 전제) | Open-Meteo 유료 플랜 또는 KMA 단기예보(API 키+격자 변환 필요)로 교체 |
| 7 | 주법 룰 임계값 (가정: 기준선 ±5~10%) | §1 확정값 | 실데이터 4주 축적 후 발화 빈도 보고 조정 — 상수만 수정 |
| 8 | 수분 알람 기준 온도 (가정: 25°C) | 기획서 예시값 | 설정 노출로 전환 시 SettingsScreen에 슬라이더 추가 |
| 9 | 몸무게 추이 카드 표본 가드 | 최근 8주 측정 3회 미만이면 미노출 (가정) | 기준 완화/강화는 상수 수정만 — 구조 영향 없음 |
