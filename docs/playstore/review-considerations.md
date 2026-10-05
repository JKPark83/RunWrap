# Play 스토어 심사 고려사항 (런미새 Android 1.0)

**조사 기준일: 2026-10-05.** Play 정책과 Health Connect(HC) 요건은 자주 바뀐다. 제출 직전에 각 절 끝의 출처를 다시 열어 본다.

이 문서는 Play 개발자 계정을 처음 만드는 1인 개발자가 출시까지 할 일을 순서대로 정리한 것이다.
구현 범위는 [android/CLAUDE.md](../../android/CLAUDE.md)의 확정 결정 표를 따른다:
HC 단일 소스(읽기 전용), 백그라운드 읽기 없음, Google Maps(`maps-compose`), ML Kit 한국어 OCR(온디바이스),
Android Auto Backup, minSdk 34 · targetSdk 36.

표기 규칙:
- **미확인**: 조사에서 1차 출처로 확인하지 못한 것. 사실로 쓰지 않는다.
- **확인 필요**: 사람이 직접 판단하거나 외부에 물어봐야 하는 것.
- **해석**: 정책 문구에서 추론한 것. 결론이 바뀔 수 있다.

iOS 쪽 대응 문서: [review-notes.md](../appstore/review-notes.md), [store-listing.md](../appstore/store-listing.md), [privacy.html](../privacy.html).
Play에는 App Store Connect의 4,000자 "심사 노트" 칸 같은 단일 칸이 없다. review-notes.md에 있던 내용을
App access 지시문, Health apps 선언, Data safety 세 곳에 나눠 넣는다(§2, §3, §5).

---

## 1. 출시까지의 순서와 소요 시간

### 순서

| 단계 | 할 일 | 소요 시간 | 근거 |
|---|---|---|---|
| ① 계정 | Play Console 개인 계정 생성 + 본인 인증 | 미확인 | 인증 항목: 법적 이름·주소(결제 프로필), 정부 신분증, 이메일, 전화, Android 기기 인증 |
| ② 첫 업로드 | 비공개 테스트 트랙에 AAB 업로드 + App content 양식 제출(§2·§3·§5) | Health apps 선언 승인 기간 미확인 | |
| ③ 비공개 테스트 | 테스터 **12명이 14일 연속** opt-in | 최소 14일 | 2023-11-13 이후 만든 개인 계정에 적용. 14일 전에 빠진 테스터는 집계되지 않는다 |
| ④ 프로덕션 액세스 신청 | 신청서 3섹션(테스트 개요·앱 정보·출시 준비도) 제출 | 보통 7일 이내 | |
| ⑤ 프로덕션 심사 | 프로덕션 트랙 출시 → 심사 | 미확인 | |

③ 14일에 ④ 심사(보통 7일 이내, 더 걸릴 때도 있음)를 더하면 **대략 2~3주**다. ①·②·⑤의 기간은 공식 수치가 없다.

미확인으로 남은 것:
- 테스터 요건이 2024-12-11에 20명에서 12명으로 줄었다는 내용, "앱마다 새로 해야 한다"는 내용 — 2차 출처뿐이다.
- 조직 계정 면제 — 1차 출처에는 개인 계정 요건만 있고 조직 계정 이야기는 없다. 조직 계정의 D-U-N-S 요건도 이번 조사에서 1차 출처로 확인하지 않았다.
- 비공개 테스트 트랙에서도 Health apps 선언이 승인되지 않았으면 테스터의 HC 연결이 막히는지 — 공식 문서는 "모든 게시 요청"에 신청이 필요하다고만 하고 테스트 트랙을 따로 언급하지 않는다(미확인). 테스터가 실제 기록으로 써 보려면 ②에서 선언을 바로 제출해 두는 편이 안전하다.

### 병렬로 할 수 있는 일

계정 생성을 기다리는 동안, 또는 14일 테스트가 도는 동안 아래를 진행한다. 서로 의존하지 않는다.

- 테스터 12명 모집(Android 기기 보유자). 테스터는 데모 모드로도 앱을 둘러볼 수 있으므로 워치가 없어도 된다.
- Android용 개인정보처리방침 페이지 작성·배포(§4).
- Google Maps API 키 발급과 키 제한 설정(§6).
- 스토어 등록정보 문안과 그래픽 자산 제작(§5).
- 한국 법규 확인(§7).

출처:
- https://support.google.com/googleplay/android-developer/answer/14151465
- https://support.google.com/googleplay/android-developer/answer/10841920
- (2차) https://www.choicely.com/blog/google-play-12-tester-rule

---

## 2. Health Connect

### Health apps 선언 양식

- 위치: Play Console → App content → **Health apps**.
- 건강 기능을 체크하고(Activity and fitness, Sleep 등), 쓰는 HC 데이터 타입마다 사용자에게 주는 이득을 적는다.
- 신규 앱과 데이터 타입이 바뀐 업데이트 모두 필수다. 건강 기능이 없는 앱도 2024-08-31부터 이 양식을 내야 한다.
- 승인은 **패키지명 기준 허용 목록**이다. 버전마다 다시 신청하지 않지만, 새 버전을 올리면 선언이 다시 심사될 수 있다.
- **데이터 타입 접근을 신청하지 않고 공개하면** 사용자가 HC를 연결하려 할 때 "app can't access Health Connect" 다이얼로그가 뜨고 HC를 읽지 못한다.
- 승인 소요 기간: 미확인. `READ_HEALTH_DATA_HISTORY`를 따로 정당화하는 칸이 있는지: 미확인.

### 허용 용도와 금지 사항

"fitness & wellness" 용도는 허용된다. 금지: 광고 목적 사용, 제3자 판매·이전, 신용·보험·고용 판단,
명시적 동의 없는 이전, UI 없는 headless 앱. 데이터 사용은 UI에 보이는 기능을 제공·개선하는 데로 한정되고,
**필요한 최소 타입만 요청해야 한다.** 이 앱은 온디바이스 처리이고 광고·분석이 없어 정책과 맞는다.

### 선언할 타입 목록

아래 표는 `android/app/src/main/AndroidManifest.xml`의 HC `uses-permission` 15개와 1:1로 맞춘 것이다(2026-10-05 기준).
Health apps 양식에 적는 타입과 매니페스트의 `uses-permission`은 1:1이어야 한다. 매니페스트를 고치면 이 표도 같이 고친다.

| HC 권한 (`android.permission.health.`) | HC 레코드 | iOS 대응 | 앱에서 쓰는 곳 | 삼성헬스 → HC 공식 동기화 표 |
|---|---|---|---|---|
| `READ_EXERCISE` | ExerciseSessionRecord | workoutType | 러닝 목록, 주간 거리·횟수, ACWR, 세션 상세 | 있음 |
| `READ_EXERCISE_ROUTES` | ExerciseRoute | workoutRoute | 세션 상세 코스 지도·고도, 공유 카드 | **없음**(미확인, 실기기 검증) |
| `READ_DISTANCE` | DistanceRecord | distanceWalkingRunning | 거리 보정 | 있음 |
| `READ_HEART_RATE` | HeartRateRecord | heartRate | 심박 효율, 심박 존, 드리프트 | 있음 |
| `READ_STEPS` | StepsRecord, StepsCadenceRecord | stepCount | 케이던스 | Steps 있음 · Cadence **없음** |
| `READ_ACTIVE_CALORIES_BURNED` | ActiveCaloriesBurnedRecord | activeEnergyBurned | 세션 소모 칼로리, 공유 카드 | 없음 |
| `READ_TOTAL_CALORIES_BURNED` | TotalCaloriesBurnedRecord | activeEnergyBurned | 위와 같음. 기기에 따라 활동·총 칼로리 중 한쪽만 기록되므로 둘 다 선언한다(매니페스트 주석) | 있음 |
| `READ_VO2_MAX` | Vo2MaxRecord | vo2Max | 심폐 체력 추이 | 있음 |
| `READ_POWER` | PowerRecord | runningPower | 주법 리포트: 러닝 파워 | 있음 |
| `READ_SLEEP` | SleepSessionRecord | sleepAnalysis | 수면 시간·단계, 체력 배터리 | 있음(단계 포함) |
| `READ_HEART_RATE_VARIABILITY` | HeartRateVariabilityRmssdRecord | heartRateVariabilitySDNN | 체력 배터리 회복 신호(RMSSD, 개인 기준선 대비) | **없음** |
| `READ_RESTING_HEART_RATE` | RestingHeartRateRecord | restingHeartRate | 체력 배터리 회복 신호 | **없음** |
| `READ_RESPIRATORY_RATE` | RespiratoryRateRecord | respiratoryRate | 체력 배터리 회복 신호 | 없음 |
| `READ_SKIN_TEMPERATURE` | SkinTemperatureRecord | appleSleepingWristTemperature | 체력 배터리 회복 신호 | 없음 |
| `READ_HEALTH_DATA_HISTORY` | (30일 이전 기록 전체) | — | 주간 추세 차트(최소 8주), 완주 예측·훈련 페이스(표본 창 최대 8주) 등 30일보다 긴 기록이 필요한 지표 | — |

HC 외에 매니페스트에 있는 권한: `INTERNET`, `ACCESS_COARSE_LOCATION`·`ACCESS_FINE_LOCATION`(날씨, 코스 탭 주변 급수대 — §3·§6),
`POST_NOTIFICATIONS`(§6). Health apps 양식 대상은 아니다.

선언하지 않는 것:
- `READ_HEALTH_DATA_IN_BACKGROUND` — 확정 결정에 따라 요청하지 않는다. 동기화는 앱을 열 때만 한다.
- HC에 대응 타입이 없는 iOS 항목: 생년월일, 1분 심박 회복, 운동 노력도, 보폭·상하 진동·접지 시간.
  해당 카드는 엔진 가드로 사라진다.
- `READ_SPEED`·`READ_ELEVATION_GAINED` 등 iOS 목록에 없는 타입은 구현이 실제로 읽을 때만 넣는다.

주의:
- "삼성헬스 공식 표에 없음"은 삼성헬스가 그 타입을 HC에 쓴다는 공식 근거가 없다는 뜻이다. 앱이 못 읽는다는 뜻은 아니다.
  표는 2025-02 자료라 그 뒤 범위가 늘었을 수 있고, 다른 앱이 HC에 쓴 값은 읽힌다. HRV·안정 심박이 HC로 오지 않는다는 근거는 서드파티 이슈의 사용자 보고뿐이다(§8).
- `READ_SKIN_TEMPERATURE`는 HC의 feature flag 대상 타입이다. 가용성을 확인하고 읽는다.

### 타입별 사용 목적 문구 초안

Health apps 양식에 붙여 넣을 문구다. 영어를 양식에 넣고 한국어는 참고용으로 둔다(iOS review-notes.md와 같은 방식).
현재 매니페스트에 선언된 타입 전부에 대해 썼다. 매니페스트에서 타입을 빼면 문구도 뺀다.

- **READ_EXERCISE**
  - 한: 러닝 기록으로 주간 거리·횟수, 훈련 부하 비율(ACWR), 세션별 상세 리포트를 만든다.
  - EN: Reads running sessions to build weekly distance and run counts, the acute:chronic training-load ratio, and per-session detail reports shown in the app.
- **READ_EXERCISE_ROUTES**
  - 한: 사용자가 세션 상세를 열 때 그 러닝의 코스를 지도와 고도로 보여 주고, 공유 카드에 경로를 그린다. 기기 안에서만 그린다.
  - EN: When the user opens a session, shows that run's route and elevation on a map and draws it on a share card. Rendered on device only; route coordinates are never sent off the device.
  - 주의: 지도 타일을 받을 때 화면에 보이는 영역은 Google에 전달된다(§3). 경로 좌표 자체는 보내지 않는다는 뜻이다.
- **READ_DISTANCE**
  - 한: 러닝 거리를 보정해 주간 거리와 페이스를 계산한다.
  - EN: Reads distance to compute weekly mileage and pace for running reports.
- **READ_HEART_RATE**
  - 한: 운동 중 심박으로 심박 효율, 심박 존 분포, 심박 드리프트를 계산한다.
  - EN: Reads heart rate during runs to compute aerobic efficiency, heart-rate zone distribution, and cardiac drift.
- **READ_STEPS**
  - 한: 걸음 수와 케이던스로 러닝 주법 리포트를 만든다.
  - EN: Reads steps and cadence to show the running-form (cadence) report.
- **READ_ACTIVE_CALORIES_BURNED / READ_TOTAL_CALORIES_BURNED** (둘 다 선언, 같은 문구)
  - 한: 세션별 소모 칼로리를 세션 상세와 공유 카드에 표시한다.
  - EN: Shows calories burned per run on the session detail screen and share card.
- **READ_VO2_MAX**
  - 한: 심폐 체력(VO₂max) 추이를 리포트에 표시한다.
  - EN: Shows the user's VO2 max trend as a cardio-fitness indicator in reports.
- **READ_POWER**
  - 한: 러닝 파워를 주법 리포트에 표시한다.
  - EN: Shows running power in the running-form report.
- **READ_SLEEP**
  - 한: 수면 시간과 단계를 체력 배터리(오늘 남은 체력 추정)의 회복 신호로 쓰고 수면 카드에 표시한다.
  - EN: Uses sleep duration and stages as a recovery signal for the in-app "energy battery" (today's readiness estimate) and shows them on the sleep card.
- **READ_HEART_RATE_VARIABILITY**
  - 한: 심박 변이를 사용자 본인의 평소 기준선과 비교해 회복 상태를 추정한다.
  - EN: Compares heart-rate variability with the user's own baseline to estimate recovery for the energy battery.
- **READ_RESTING_HEART_RATE**
  - 한: 안정 시 심박을 평소 기준선과 비교해 회복 상태를 추정한다.
  - EN: Compares resting heart rate with the user's own baseline to estimate recovery for the energy battery.
- **READ_RESPIRATORY_RATE**
  - 한: 호흡수를 평소 기준선과 비교해 회복 상태를 추정한다.
  - EN: Compares respiratory rate with the user's own baseline as a recovery signal for the energy battery.
- **READ_SKIN_TEMPERATURE**
  - 한: 수면 중 피부 온도 변화를 평소 기준선과 비교해 회복 상태를 추정한다.
  - EN: Compares skin-temperature change during sleep with the user's baseline as a recovery signal for the energy battery.
- **READ_HEALTH_DATA_HISTORY**
  - 한: 주간 추세 차트(최소 8주)와 레이스 완주 예측·훈련 페이스(최근 기록이 없으면 8주까지 넓혀 표본을 찾는다)는 30일보다 긴 기록이 있어야 제대로 계산된다. 이 권한이 없으면 HC는 처음 권한을 허용한 시점부터 30일 전까지만 읽게 해 주므로, 설치 직후에는 이 지표들이 표본 부족으로 표시되지 않는다.
  - EN: Some reports need more than 30 days of running history: the weekly trend chart (at least 8 weeks) and race-time prediction and training paces (the sample window widens up to 8 weeks). Without this permission only the 30 days before the first grant are readable, so right after install these reports stay hidden, because the app never shows a metric from too small a sample.

모든 문구 공통 덧붙임(양식에 공통 설명 칸이 있으면): "All Health Connect data is read-only and processed on the device. It is never transmitted, sold, or used for advertising."

### 권한 근거 화면 (매니페스트 필수)

- Android 14 이상: `activity-alias`에 action `android.intent.action.VIEW_PERMISSION_USAGE`,
  category `android.intent.category.HEALTH_PERMISSIONS`, permission `android.permission.START_VIEW_PERMISSION_USAGE`.
- Android 13 이하: `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`. minSdk 34라 이 앱에서는 쓰지 않는다.
- 이 화면은 Play Console에 등록한 것과 **같은 개인정보처리방침**을 보여 줘야 한다(§4).
  현재 매니페스트의 `ViewPermissionUsageActivity` alias는 `.MainActivity`를 가리키므로, MainActivity가 이 인텐트로 열렸을 때 방침을 보여 주도록 구현해야 한다.
- `<queries>`에 `com.google.android.apps.healthdata`를 선언하고 `getSdkStatus`로 가용성을 확인한다.

### READ_HEALTH_DATA_HISTORY · READ_EXERCISE_ROUTES의 심사상 의미

- **HISTORY**: 기본 조회 범위는 처음 권한을 받은 시점 이전 30일이다. 더 오래된 기록을 읽으면 오류가 난다.
  재설치하면 history 권한도 철회되고 30일 창이 다시 시작된다. 권한이 하나 늘어나는 만큼 양식에서 사유를
  설득해야 한다. 위 문구처럼 "어떤 화면이 몇 주치 기록을 요구하는지"를 구체적으로 쓴다.
  참고로 ACWR(만성 부하 28일)은 30일 안에 들어오므로 사유로 내세우지 않았다.
- **ROUTES**: 읽기 권한 이름은 복수형 `READ_EXERCISE_ROUTES`다(쓰기는 단수형). 다른 앱이 기록한 경로는
  세션마다 1회 동의가 필요하고(`ConsentRequired` → 동의 다이얼로그), 백그라운드에서는 "항상 허용"이어도
  항상 `ConsentRequired`가 온다. 따라서 세션 상세 화면에서 사용자가 동작할 때만 요청한다.
  경로 좌표는 위치 정보이므로 기기 밖으로 내보내지 않는 점(Static Maps 금지, 공유 카드는 로컬 캡처)을 문구에 밝힌다.

출처:
- https://developer.android.com/health-and-fitness/guides/health-connect/publish/request-access
- https://developer.android.com/health-and-fitness/health-connect/publish
- https://support.google.com/googleplay/android-developer/answer/14738291
- https://support.google.com/googleplay/android-developer/answer/12991134
- https://support.google.com/googleplay/android-developer/answer/16558241
- https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data
- https://developer.android.com/health-and-fitness/guides/health-connect/develop/exercise-routes
- https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started
- https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types
- https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect

---

## 3. 데이터 안전(Data safety) 양식

데이터를 수집하지 않아도 모든 앱이 작성해야 한다. Play 기준으로 **기기 밖으로 전송하면 "수집"**이고,
온디바이스 처리만 하면 신고 대상이 아니다. 위치는 3km² 이상이면 대략 위치, 3km² 미만이면 정확한 위치다.

### 답안 초안

| 데이터 유형 | 수집 | 공유 | 근거 |
|---|---|---|---|
| 건강 및 피트니스(HC 데이터 전체) | 아니요 | 아니요 | 온디바이스 처리만 한다 |
| 위치 — 정확한 위치 | **예**(일시적 처리, 저장 안 함) | 아니요 | 날씨 요청에 소수 2자리 좌표를 보낸다. 아래 설명 참고 |
| 위치 — 대략 위치 | 아니요 | 아니요 | 아래 "대기질" 참고 |
| 사진·동영상(완주증 촬영) | 아니요 | 아니요 | ML Kit 온디바이스 인식. 사진을 저장하거나 올리지 않는다 |
| 개인 정보(이름·이메일 등) | 아니요 | 아니요 | 계정이 없다 |
| 앱 활동·기기 ID·비정상 종료 로그 | 아니요 | 아니요 | 분석·크래시 SDK가 없다 |
| Google Maps SDK가 수집하는 항목 | **미확인** | **미확인** | 아래 참고 |
| ML Kit가 수집하는 항목 | **미확인** | **미확인** | 아래 참고 |

**날씨 좌표**: 소수 2자리(위도 37°에서 약 1.1km × 0.9km, 약 1km²)는 3km² 미만이라 "정확한 위치"에 해당한다.
1km² 계산은 정책 문구가 아니라 추론이다(**해석**). 조사의 권고는 iOS와 같은 정밀도를 유지하고
"정확한 위치 수집(일시적, 공유 없음)"으로 보수적으로 신고하는 것이다. 다른 선택지:
- COARSE만 요청하고 "대략 위치"로 신고한다. 코스 탭의 주변 급수대 정확도가 떨어진다.
- 날씨 전송을 소수 1자리로 줄인다. iOS도 같이 바꿔야 한다.

어느 쪽으로 할지는 제품 결정이고, 위 표의 "예"는 결정 전 기본안이다. 현재 매니페스트는 FINE과 COARSE를 함께 선언한다
(FINE은 코스 탭에서 300m 밖 급수대를 짚기 위해서다). 급수대 탐색은 기기 안에서만 하므로 FINE 자체는 수집 신고 대상이 아니고,
신고 여부는 날씨 요청으로 보내는 좌표의 정밀도로 정해진다. **확인 필요**: open-meteo로 보내는 것을 "공유"가 아니라고 볼지는 조사의 권고를 따랐으며, Play의 공유 예외 조건 원문은 이번 조사에서 확인하지 않았다.

**대기질**: 좌표 없이 측정소 이름만 보낸다. 측정소는 기기 위치로 고른 값이므로 대략 위치로 볼 여지가 있다.
이 판단은 조사에 없던 것이다(**해석**, 확인 필요).

**대회 목록·대회 이미지**: 개인 데이터를 보내지 않는다. 요청에는 일반적인 웹 요청 정보(IP 등)만 실린다(privacy.html §3(3)(4)와 같음).

**Google Maps SDK·ML Kit**: 각 SDK가 무엇을 수집한다고 공개하는지는 이번 조사 범위 밖이다(**미확인**).
제출 전에 Google Maps Platform과 ML Kit의 공식 Data safety 안내를 읽고 이 표를 채운다.
지도 타일을 받으면 보이는 영역이 Google에 전달된다는 점은 iOS의 Apple 지도(§3(6))와 같은 구조다.

**Auto Backup**: Play는 사용자가 본인 클라우드로 직접 백업하고 앱이 접근하지 않는 경우를 수집으로 보지 않는다.
Android Auto Backup이 이 예외에 해당한다는 것은 그 문구에서 추론한 것이다(**해석**). 확정 결정대로 건강 데이터 캐시는
백업 규칙에서 빼고, 백업되는 것은 진행도·설정뿐이어야 이 답이 성립한다.
**현재 상태**: `android/app/src/main/res/xml/data_extraction_rules.xml`의 `cloud-backup`·`device-transfer`에는 제외 규칙이 아직 없다
(TODO 주석만 있다). 이대로 출시하면 리포트 캐시 같은 건강 데이터 파생물이 사용자 Google 계정 백업으로 나간다. 제출 전에 반드시 채운다(§9).

출처:
- https://support.google.com/googleplay/android-developer/answer/10787469
- https://developer.android.com/guide/topics/location/permissions

---

## 4. 개인정보처리방침

### 요건

- 방침에 접근하는 HC 데이터 종류, 사용·저장·공유 방식, 보존·삭제, 보안을 적는다.
- **Play 등록정보의 방침 URL, HC 권한 화면 링크, 앱의 근거 화면(§2)이 같은 방침을 보여 줘야 한다.**
- 공개되어 있고 지역 제한이 없는 URL이어야 한다. PDF는 안 된다.

### 분리 권고

**Android 전용 페이지를 따로 둔다**(예: 같은 Vercel 프로젝트의 `privacy-android.html`). 이유:
- Play·HC·근거 화면 세 곳이 한 URL을 가리켜야 하는데, HealthKit과 HC는 타입 목록과 철회 경로가 많이 다르다.
- 심사자가 자기 플랫폼에 맞는 문서를 본다.

대신 새 기능을 넣을 때마다 두 문서를 같이 고쳐야 한다. iOS 방침(`privacy.html`)은 건드리지 않는다.

### privacy.html에서 그대로 가져올 절

머리말, §2 온디바이스 원칙, §3(1)~(4) 외부 통신(날씨·대기질·대회 일정·대회 이미지), §4 제3자 제공 없음,
§6 아동, §7 의학적 조언 아님, §8 변경, §9 문의.

### 다시 써야 할 절

| 절 | iOS | Android에서 바꿀 내용 |
|---|---|---|
| 머리말·§1 | 애플 건강(HealthKit) 읽기 항목 표 | Health Connect로 교체. 삼성헬스가 HC에 동기화한 기록을 읽는다고 쓴다. HRV는 RMSSD, 손목 온도는 피부 온도. 생년월일·노력도·러닝 폼(보폭·상하 진동·접지 시간)·1분 심박 회복 행 삭제. 30일 이전 기록(HISTORY)을 읽는 이유와, 경로는 세션마다 1회 동의를 받는다는 설명 추가 |
| §1 끝 | "애플 정책상 허용 여부를 확인할 수 없으므로" | 삭제. HC에서는 권한 상태를 조회할 수 있다 |
| §2 | 알림 요약 파일을 iCloud·아이튠즈 백업에서 제외 | Android 백업 규칙에서 제외한다고 바꾼다 |
| §2 | 캘린더 쓰기 전용 | Android 구현 방식이 정해지면 그에 맞게 고친다 |
| §3(5) | iCloud CloudKit 진행도 백업 | Android Auto Backup으로 교체. 무엇이 백업되고 무엇이 빠지는지(건강 데이터 캐시 제외), 사용자 Google 계정 백업에 저장되고 개발자는 볼 수 없다는 점, 끄거나 지우는 방법 |
| §3(6) | Apple 지도(MapKit) | Google Maps SDK로 교체. 보이는 영역의 타일을 Google에 요청하고 Google 개인정보처리방침이 적용된다는 점. 경로 좌표는 보내지 않는다는 점(공유 카드는 기기에서 그린다) |
| 신규 | — | 완주증 인식: ML Kit 한국어 텍스트 인식이 기기 안에서 동작하고 사진을 저장·전송하지 않는다 |
| §4 | "애플이 제공하는 프레임워크만으로 만들어졌다" | Google Maps SDK·ML Kit를 쓴다고 사실대로 고친다. 분석·광고 SDK가 없다는 문장은 유지 |
| §5 | 권한 철회: 설정 → 개인정보 보호 및 보안 → 건강 | HC 설정의 앱 권한 경로로 교체(실기기 메뉴명으로 확인), 백업 삭제 방법 |

출처:
- https://support.google.com/googleplay/android-developer/answer/12991134
- https://developer.android.com/health-and-fitness/guides/health-connect/publish/request-access
- https://support.google.com/googleplay/android-developer/answer/16679511

---

## 5. 스토어 등록정보와 App content

### 의료 기기 아님 고지 (필수)

의료 기기가 아닌 건강 앱은 다음 취지의 고지를 넣어야 한다:
"not a medical device and does not diagnose, treat, cure, or prevent any medical condition".
iOS는 카드 안 고지와 방침 §7로 처리했지만 Play는 등록정보에 새로 넣는다.
조사에서 고지 문구는 확인했지만 "앱 설명란"이라는 위치와 "전문가 상담 권고 문구 필수"는 원문에서 명시적으로 확인하지 못했다.
**제출 전에 정책 원문을 다시 확인한다.** 그 전까지는 설명 끝에 아래 두 문장을 모두 둔다.

```
런미새는 의료 기기가 아니며, 질병이나 건강 상태를 진단·치료·완치·예방하지 않습니다.
통증이나 이상이 있다면 반드시 의사 등 전문가와 상담하세요.
```

### 등록정보 항목

| 항목 | 값 |
|---|---|
| 앱 이름 | 30자 이내 |
| 간단한 설명 | 80자 이내 |
| 자세한 설명 | 4,000자 이내. 키워드 반복은 정지 사유다 |
| 연락처 이메일 | 필수 |
| 한국 개발자 연락처 | 개인은 문의 전화번호만. 한국 사용자에게 설명 하단에 표시된다 |
| 사업자등록번호·통신판매업 신고번호 | 해당 없음(유료 앱·인앱결제가 있는 조직에만 요구) |
| 가격·인앱결제 | 무료 · 없음 |
| 앱 카테고리 | 이번 조사에서 Play 카테고리 목록은 확인하지 않았다. iOS는 "건강 및 피트니스" |

iOS 설명(store-listing.md)의 "Apple Watch", "아이폰", "건강 앱" 표현은 갤럭시워치·삼성헬스·Health Connect 기준으로 바꾼다.
Android에 없는 기능(홈 화면 위젯, 대회 기록 자연어 입력, 러닝 직후 자동 알림, 잠금화면 위젯)은 설명에서 뺀다.

### App content 항목

- **App access**: 로그인이나 기능 제한이 있으면 심사자용 지시문을 최대 5세트 넣는다. iOS review-notes.md의 데모 모드 4단계와
  "샘플 리포트 둘러보기" 지름길, "빈 화면은 의도된 동작" 설명을 Android 화면 이름에 맞춰 옮긴다. 테스트 기기 표기도 Android 기기로 바꾼다.
- **광고**: 없음.
- **콘텐츠 등급**: IARC 설문 필수. 등급이 없으면 앱이 삭제된다. IARC 결과가 한국 GRAC 등급에 어떻게 반영되는지는 미확인.
- **타깃 연령**: 아동 아님.
- **Health apps**: §2.
- **Data safety**: §3.

### 그래픽 자산 규격

| 자산 | 규격 |
|---|---|
| 아이콘 | 512×512, 32비트 PNG(알파 포함), 최대 1024KB |
| 그래픽 이미지(feature graphic) | 1024×500, JPEG 또는 24비트 PNG(알파 없음), 필수 |
| 스크린샷 | JPEG 또는 24비트 PNG. 짧은 변 320px 이상, 긴 변 3840px 이하, 긴 변 ≤ 짧은 변 × 2. 최소 2장, 기기 유형별 최대 8장 |
| 추천 노출 자격 | 9:16 기준 1080×1920 이상 4장 이상 |

iOS 6.9인치 스크린샷(`docs/appstore/screenshots-6.9/`)은 비율이 약 9:19.5라 "긴 변 ≤ 짧은 변 × 2" 규칙에 걸린다. 그대로 쓰지 못하고 새로 찍는다.

출처:
- https://support.google.com/googleplay/android-developer/answer/16679511
- https://support.google.com/googleplay/android-developer/answer/9866151
- https://support.google.com/googleplay/android-developer/answer/9859152
- https://support.google.com/googleplay/android-developer/answer/9859455
- https://support.google.com/googleplay/android-developer/answer/9898843
- https://support.google.com/googleplay/android-developer/answer/3255733?hl=ko

---

## 6. 기술 요건

### targetSdk 36

- 2026-08-31부터 신규 앱과 업데이트는 API 36 이상을 타깃해야 한다(2026-11-01까지 연장 신청 가능). 이 앱은 처음부터 36이다.
- **edge-to-edge**: Android 15 이상 기기에서 강제되고, targetSdk 36에서는 끌 수 없다(`windowOptOutEdgeToEdgeEnforcement` 비활성).
  minSdk 34라 Android 14 기기에서는 강제되지 않으므로, 두 버전에서 화면이 같도록 앱에서 edge-to-edge를 켜고 시스템 바 인셋을 화면마다 처리한다.
- **예측형 뒤로가기**: Android 16 이상 기기에서 기본으로 켜지고 `onBackPressed`·`KEYCODE_BACK`이 전달되지 않는다.
  Compose의 `BackHandler`류로 처리한다. 임시 opt-out은 `enableOnBackInvokedCallback=false`.
- **세로 전용**: sw600dp 이상 기기(태블릿, 폴드 펼친 상태)에서는 매니페스트의 `screenOrientation="portrait"` 같은 방향·비율 제한이 무시된다.
  targetSdk 36에서는 `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`로 임시 opt-out할 수 있고, API 37 타깃부터는 불가하다.

### 16KB 페이지

2027-02-01부터 16KB 페이지를 지원하지 않는 업데이트는 출시할 수 없다. 대상은 API 35 이상 타깃이면서 네이티브 코드가 있는 앱이다.
순수 Java/Kotlin 앱은 이미 지원하는 것으로 본다. 이 앱은 Google Maps SDK와 ML Kit를 넣으므로, 두 라이브러리가
네이티브 `.so`를 포함하는지와 16KB 정렬 여부를 빌드 결과로 확인한다(**확인 필요**).

### 알림과 정확한 알람

- `POST_NOTIFICATIONS`는 런타임 권한이고 Play 선언 양식은 없다. 알림 설정을 켤 때 요청하고, 보내기 전에 `areNotificationsEnabled()`를 확인한다.
- `USE_EXACT_ALARM`은 알람·타이머 앱과 일정 알림 캘린더 앱에만 허용되고, 해당하지 않으면 출시가 막힌다.
  `SCHEDULE_EXACT_ALARM`은 Android 14 이상에서 기본 거부된다.
- 수분 섭취, 주간 리포트, 대회 접수 알림은 분 단위 정확도가 필요 없다. **두 권한 모두 선언하지 않고** WorkManager나 비정확 알람으로 처리한다.

### 위치 권한

- 전경 위치만 쓴다. 백그라운드 위치는 요청하지 않는다(요청하면 별도 선언 양식, 30초 이내 영상, 사전 고지가 필요하다).
- 사용자가 FINE을 요청받아도 "대략"으로 낮출 수 있으므로 COARSE만으로도 동작해야 한다.
- 2027-01-27부터 정확한 위치에는 location button이 권장 최소 범위가 된다.

### 앱 서명 (Play App Signing)

- 신규 앱은 Google이 만든 앱 서명 키로 자동 등록된다. 개발자는 업로드 키(RSA 2048 이상 `.jks`)로 AAB에 서명해 올린다.
- 업로드 키를 잃으면 Play Console의 "Request upload key reset"으로 복구한다. 업로드 키 파일과 비밀번호는 저장소 밖에 보관한다.
- 외부 API에 앱을 등록할 때는 **Play 앱 서명 키**의 인증서 지문을 쓴다(업로드 키가 아니다). Google Maps API 키 제한과 나중의 삼성 파트너 등록(§8)이 여기에 해당한다.
  Maps 키 제한 화면이 받는 지문 형식은 이번 조사 범위 밖이므로 Google Cloud 콘솔에서 확인한다. 로컬 디버그 빌드용 지문도 따로 등록해야 개발 중 지도가 뜬다.
- 신규 앱의 AAB 의무: 이번 조사에서 명시를 확인하지 못했다(미확인, 2021-08부터라고 알려져 있음).

### 개발자 인증

- 2026-09-30부터 브라질·인도네시아·싱가포르·태국의 인증 기기에서 보호 조치가 시작된다. 전 세계 확대는 "2027 and beyond"로만 적혀 있어 **한국 적용 시점은 특정되지 않았다.**
- Play 앱의 99%는 자동 등록된다. Policy deadlines 페이지에는 2026-09-30까지 "Play Console에서 앱 등록 필수"라고 적혀 있다. Play로만 배포하면 계정 본인 인증(§1)으로 충족되는 것으로 보이나 확인 필요.

출처:
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/develop/ui/views/layout/edge-to-edge
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://support.google.com/googleplay/android-developer/answer/9799150
- https://support.google.com/googleplay/android-developer/answer/9842756
- https://developer.android.com/developer-verification
- https://support.google.com/googleplay/android-developer/answer/12253906

---

## 7. 한국 법규 확인 항목

Google 1차 출처로는 확인할 수 없는 국내법 사항이다. iOS 앱이 같은 구조(소수 2자리 좌표를 날씨 서버로 전송)로 이미 출시돼 있으므로 **두 플랫폼의 판단이 같아야 한다.**

| 항목 | 상태 | 확인할 곳 |
|---|---|---|
| 위치정보법상 위치기반서비스사업 신고 | 확인 필요. 2차 출처에는 서버로 좌표만 보내도 신고 대상이라는 해석이 있다 | iOS 출시 때 검토한 적이 있으면 그 판단을 따른다. 없으면 방송미디어통신위원회 기준을 확인하거나 법률 전문가에게 묻는다 |
| 개인정보 보호법 §30(처리방침 공개) | 확인 필요 | 개인정보보호위원회 안내 또는 법률 전문가. iOS 처리방침은 공개 중이고, Android용은 §4에서 새로 만든다 |
| IARC 등급의 한국 GRAC 반영 | 미확인 | 콘텐츠 등급 설문 제출 후 Play Console 결과 화면 |

출처:
- https://support.google.com/googleplay/android-developer/answer/3255733?hl=ko
- (2차) https://www.catchsecu.com/archives/11350

---

## 8. 삼성 관련

### 삼성헬스 → Health Connect 동기화 범위

삼성 공식 표(2025-02-18 블로그, Samsung Health 6.22.5 이상) 기준이다. 표가 나온 뒤 범위가 늘었을 수 있다.

| 구분 | 타입 |
|---|---|
| 공식 표에 있음 | Steps, ExerciseSession, TotalCaloriesBurned, Distance, HeartRate(운동 중·일반), Power, Speed, Vo2Max, SleepSession(단계 포함), BloodGlucose, OxygenSaturation, BloodPressure, Nutrition, Weight, BodyFat, BasalMetabolicRate, Height |
| 공식 표에 없음 | ExerciseRoute, StepsCadence, HRV(RMSSD), RestingHeartRate, SkinTemperature |
| 실기기 미검증 | 경로·케이던스가 실제로 HC에 오는지(공식 근거 없음). HRV·안정 심박이 오지 않는다는 것(서드파티 이슈의 사용자 보고뿐, 2026-09-25) |

- 활동 트래커 데이터는 동기화하지 않는다고 명시돼 있다.
- 폰의 삼성헬스는 데이터가 생기거나 바뀌면 즉시 HC에 쓴다. 워치 → 폰 동기화는 배터리 정책을 따르며, 워치 재연결·삼성헬스 홈 진입·당겨서 새로고침 때 주로 일어난다. 지연 시간 수치는 미확인.
- HC의 `dataOrigin` 패키지명이 `com.sec.android.app.shealth`인지는 미확인.
- HC 경로로는 수면 점수·에너지 스코어를 읽을 수 없다.

### Samsung Health Data SDK 파트너 프로그램 (나중에 신청할 때)

- **이점**: HC만으로는 확실하지 않은 경로, 운동 중 케이던스 시계열, 수면 점수, 에너지 스코어, 피부 온도를 읽을 수 있다.
  HRV·안정 심박·러닝 다이내믹스는 SDK에도 타입이 없어 얻을 수 없다.
- **조건**: 일반 사용자 기기에서 쓰려면 파트너 승인을 받아 패키지명과 **Play 앱 서명 키 기준 SHA-256**을 등록해야 한다. 등록하지 않으면 `AuthorizationException`이 난다.
  요건은 Samsung Health 6.30.2 이상, Android 10 이상, Java 17. 에뮬레이터 미지원.
- **개발·테스트**: 읽기만 할 때는 개발자 모드(삼성헬스 설정 → 정보에서 버전 10회 탭 → "Developer Mode for Data Read")로 승인 없이 시험할 수 있다. 공식 문구상 테스트 전용이며 사용자 배포에는 쓸 수 없다.
- **개인 개발자 승인 가능 여부: 미확인.** Data SDK 프로세스 페이지에는 자격 조건도 신청 중단 공지도 없다. 신청 중단 공지는 구 "Samsung Health SDK for Android" 페이지에만 있다. 실제로 폼을 제출해 봐야 안다. 심사 기간도 명시돼 있지 않다.
- **비용**: 권한 UI가 둘이 되고(HC + 삼성헬스 팝업), 같은 운동이 양쪽에 있어 중복 제거가 필요하다.
  Samsung SDK 사용에 대한 Play 측 별도 요건은 미확인.
- SDK는 "fitness and wellness purposes only, not for diagnosis or treatment" 용도로 제한된다. 설명과 문구에 진단 뉘앙스를 넣지 않는다.
- 이번 출시는 확정 결정대로 HC 단일로 가고 Samsung Health Data SDK는 넣지 않는다. 데이터 소스를 `health/` 패키지 뒤에 가둬, 승인되면 나중에 붙일 수 있게만 해 둔다.

### 워치 없이 검증하는 한계

- 에뮬레이터(AVD `syd_api36`)로는 삼성헬스 동기화를 검증할 수 없다. 합성 데이터는 디버그 빌드의 HC 시더로 넣는다.
- 갤럭시폰만 있고 워치가 없으면 운동 중 심박·VO2max·수면 단계처럼 워치 센서가 만드는 데이터는 생기지 않는다고 보는 것이 자연스럽다(**해석**).
  폰 단독으로 기록한 삼성헬스 운동이 어떤 레코드로 HC에 오는지는 이번 조사에서 확인하지 않았다.
- 따라서 "경로가 오는가", "HRV·안정 심박이 정말 비는가"는 워치가 있는 테스터의 실기기에서만 답이 나온다. 비공개 테스트 테스터 중 갤럭시워치 사용자를 최소 1명 포함한다.

출처:
- https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect
- https://developer.samsung.com/health/health-connect-faq.html
- https://github.com/the-momentum/open-wearables/issues/1723
- https://us.community.samsung.com/t5/Galaxy-Watch/HRV-breathing-rate-and-resting-HR-won-t-write-to-the-health/td-p/3351258
- https://us.community.samsung.com/t5/Samsung-Apps-and-Services/Samsung-Health-won-t-sync-exercise-since-update/m-p/3614440
- https://developer.samsung.com/health/data/overview.html
- https://developer.samsung.com/health/data/process.html
- https://developer.samsung.com/health/data/guide/developer-mode.html
- https://developer.samsung.com/health/data/guide/app-verification.html
- https://developer.samsung.com/health/android/data/guide/process.html
- https://developer.samsung.com/health/data/release-note.html

---

## 9. 제출 직전 체크리스트

### 반려 위험

- [ ] 매니페스트의 HC `uses-permission`과 Health apps 양식의 타입 목록이 1:1이다. 코드가 읽지 않는 타입은 선언하지 않았다
- [ ] `READ_HEALTH_DATA_IN_BACKGROUND`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `ACCESS_BACKGROUND_LOCATION`이 매니페스트에 없다(병합된 매니페스트까지 확인 — 라이브러리가 끌어올 수 있다)
- [ ] `data_extraction_rules.xml`의 `cloud-backup`·`device-transfer`가 건강 데이터 캐시를 제외한다(현재 TODO 상태, §3)
- [ ] `VIEW_PERMISSION_USAGE` activity-alias가 있고, 그 화면이 Play 등록 방침과 같은 URL을 보여 준다
- [ ] 개인정보처리방침이 공개 HTML이고(PDF 아님) 지역 제한이 없다. HC 타입·보존·삭제·보안을 적었다
- [ ] Play 등록정보 방침 URL = HC 권한 화면 링크 = 앱 근거 화면
- [ ] 의료 기기 아님 고지가 설명에 있다(위치는 정책 원문으로 재확인)
- [ ] Data safety의 위치 답이 실제 좌표 정밀도·권한(FINE/COARSE)과 맞는다
- [ ] Data safety에 Google Maps SDK·ML Kit의 공개 수집 항목을 반영했다
- [ ] 설명에 키워드 반복이 없고, Android에 없는 기능(위젯 등)을 적지 않았다
- [ ] App access에 데모 모드 진입 절차를 적었다
- [ ] targetSdk 36에서 edge-to-edge 인셋과 예측형 뒤로가기를 Android 16 기기(또는 에뮬레이터)로 확인했다
- [ ] Maps·ML Kit의 네이티브 라이브러리 16KB 정렬을 확인했다
- [ ] 콘텐츠 등급(IARC)·타깃 연령·광고 선언을 제출했다
- [ ] 스크린샷이 2:1 비율 규칙 안이다(iOS 스크린샷 재사용 아님)
- [ ] Static Maps API를 부르는 코드가 없다(경로 좌표 외부 전송 금지)

### 사용자가 직접 해야 하는 일

- [ ] Play Console 개인 계정 생성과 본인 인증(신분증, 주소, 전화, Android 기기)
- [ ] 비공개 테스트 테스터 12명 모집 — 14일 연속 유지 안내, 갤럭시워치 사용자 최소 1명
- [ ] Google Cloud에서 Maps SDK for Android API 키 발급 → 키 제한(Android 앱: 패키지명 `com.jkpark.runwrap` + Play 앱 서명 키 지문 + 디버그 키 지문, API 제한: Maps SDK for Android만) → `android/local.properties`의 `MAPS_API_KEY`에 저장(커밋 금지)
- [ ] 업로드 키(.jks) 생성과 저장소 밖 보관
- [ ] 날씨 좌표 정밀도와 위치 권한 범위 결정(§3)
- [ ] Android 전용 개인정보처리방침 작성·배포(§4)
- [ ] 위치기반서비스 신고 여부 등 국내법 확인(§7)
- [ ] Google Maps SDK·ML Kit의 Data safety 공개 항목 확인(§3)
- [ ] 의료 기기 아님 고지 위치를 정책 원문으로 확인(§5)
- [ ] 한국 개발자 연락처 전화번호 입력
- [ ] 스토어 그래픽 자산(아이콘 512, feature graphic 1024×500, 스크린샷) 제작
- [ ] (선택, 나중) Samsung Health Data SDK 파트너 요청 폼 제출
