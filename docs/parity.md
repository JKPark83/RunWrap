# iOS ↔ Android 대응표

iOS(`ios/RunWrap`)가 사양 원본이고 Android(`android/`)는 이식본이다.
기본 규칙은 **파일 이름이 같다**(`Foo.swift` ↔ `Foo.kt`)이고, 이 문서에는 그 규칙에서 벗어나는 것만 적는다.
`tools/ci/parity_check.py`가 이 표를 읽어 CI와 세션 종료 훅에서 점검한다 — 표 형식(백틱·열 순서)을 바꾸지 않는다.

- 한쪽에 기능을 추가·수정하면 다른 쪽에도 반영한다. 반영할 것이 없으면 이 문서에 이유를 적는다.
- 새 iOS 테스트는 같은 표시 이름(`@DisplayName`)으로 Android에도 있어야 한다. 옮길 수 없는 것만 맨 아래 표에 적는다.

## 이름이 다른 대응

| iOS | Android | 메모 |
|---|---|---|
| `RaceListScreen.swift` | `RaceListScreen.kt`, `RaceFormat.kt` | 순수 표기부(`enum RaceFormat`)만 `:engine`으로 분리 |
| `OnboardingFlowScreen.swift` | `OnboardingFlowScreen.kt`, `OnboardingFlowModel.kt` | 화면 상태 모델(`OnboardingFlowModel`)만 `:engine`으로 분리해 테스트 |
| `RunWrapApp.swift` | `RunWrapApp.kt`, `MainActivity.kt`, `AppContainer.kt` | `@main App` + environmentObject → Application + 단일 액티비티 + 컨테이너 |
| `BirdIllustrations.swift` | `BirdIllustrations.kt`, `BirdTabIcon.kt` | 탭바용 새 아이콘(`BirdTabIcon`)만 파일을 나눴다 |

## ios-only 파일

| iOS | Android | 이유 |
|---|---|---|
| `WidgetSnapshot.swift` | ios-only | 홈 화면 위젯은 Android 1차 범위 밖 (2026-10-05 결정) |
| `WidgetSnapshotFactory.swift` | ios-only | 〃 |
| `WidgetSnapshotStore.swift` | ios-only | 〃 |
| `WidgetSnapshotTests.swift` | ios-only | 〃 |
| `RunWrapStatusWidget.swift` | ios-only | 〃 |
| `RunWrapWidgetBundle.swift` | ios-only | 〃 |
| `StatusWidgetView.swift` | ios-only | 〃 |

## android-only 파일

| iOS | Android | 이유 |
|---|---|---|
| — | `EngineSupport.kt`, `SwiftCompat.kt`, `SwiftCompatTests.kt`, `TestSupport.kt` | Swift와 같은 반올림·숫자 표기·기준 시각을 내기 위한 호환 헬퍼와 그 테스트 |
| — | `FormatTests.kt` | `Format.*` 출력을 실제 Swift 출력과 대조한 고정값 테스트 |
| — | `SettingsStore.kt`, `SettingsStoreTests.kt` | `UserDefaults`/`@AppStorage` 자리(SharedPreferences) |
| — | `RRIcons.kt`, `RRIconsTests.kt` | SF Symbols 자리의 벡터 아이콘 |
| — | `NumberWheel.kt` | SwiftUI 휠 `Picker` 자리 |
| — | `SessionSlices.kt`, `SessionSlicesTests.kt` | Health Connect에는 세션 통계가 없어, 기간 단위로 읽은 거리·심박·칼로리를 세션별로 나누는 로직 (iOS는 `HKWorkout` 통계를 그대로 쓴다) |
| — | `AirQualityClientTests.kt` | 대기질 요청 URL 인코딩 — iOS는 `URLComponents`가 하는 일을 직접 한다 |
| — | `Routes.kt` | type-safe 내비게이션 route와 엔진 모델 JSON 인자 — iOS는 `NavigationStack` 값·`sheet(item:)`이 하는 일 |
| — | `HealthSeedReceiver.kt` | 디버그 빌드 전용 Health Connect 시더(`src/debug/`) — 에뮬레이터에서 실제 읽기 경로를 돌려 볼 합성 데이터를 써 넣는다. iOS 시뮬레이터는 DemoData만 쓴다 |

## ios-only 테스트

| 테스트 | 이유 |
|---|---|
| `사진 왕복 — 1200×800을 긴 변 600(600×400) JPEG로 줄여 <id>.jpg에 두고, 지우면 사라진다` | UIKit 이미지 처리 — Android는 `:app`의 Bitmap 경로(JVM 유닛 테스트 불가) |
| `이미지가 아닌 데이터는 저장하지 않는다` | 〃 |
| `권한 묶음 — iOS 18 이상이면 기본 요청에 두 노력도 타입이 들어간다` | HealthKit 타입 — Health Connect에 노력도 없음 |
| `Vision 합성 완주증 이미지 인식` | Vision 프레임워크 실제 인식 — Android는 ML Kit(기기 테스트 필요) |
| `운동 직후 본문 — 거리·페이스가 있으면 한 줄 요약으로 만든다` | 운동 직후 알림은 HealthKit 백그라운드 전달 기반 — Android 1차는 백그라운드 읽기 제외 (2026-10-05 결정) |
| `운동 직후 본문 — 거리 없는 세션(수동 기록 등)은 기본 문구로 낸다` | 운동 직후 알림은 HealthKit 백그라운드 전달 기반 — Android 1차는 백그라운드 읽기 제외 (2026-10-05 결정) |
| `알림 권한 판정 — 허용·임시·앱 클립만 발송 가능, 거부·미결정은 불가 (이슈 #94)` | `UNAuthorizationStatus` 판정 — Android는 `areNotificationsEnabled()` 하나 |
| `iOS 버전 표기 — patch가 0이면 빼고, 있으면 붙인다` | `OperatingSystemVersion` 표기 — Android는 `Build.VERSION.RELEASE`를 그대로 쓴다 |
| `같은 사이클 병합 — 서버가 오래됐어도 maxStage는 낮아지지 않는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `같은 사이클 병합 — 최신 updatedAt 쪽의 스칼라 값이 이긴다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `다른 사이클 병합 — 사이클 전환은 원자적이라 최신 쪽이 통째로 이긴다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `도감 병합 — id 기준 중복 제거 후 수집일 오름차순 합집합` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `대회 기록 합집합 — id 기준 중복 제거 후 대회 날짜 최신순` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `대회 기록 병합 — 어느 쪽이 최신이든 양쪽 기록이 모두 남고, 옛 서버 본(nil)도 로컬 기록을 지우지 않는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `대회 기록 삭제 표식 — 한 기기에서 지운 기록은 서버 본에 남아 있어도 병합에서 되살아나지 않는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `미래 스키마 서버 본 — 덮어쓰지 않고 keepServer로 보류한다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `revision 단조 증가 — 병합본은 양쪽 최댓값보다 크다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 판정 — 미래 스키마·빈 레벨은 거부, 현재 스키마는 허용` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `오래된 백업 복원 기기의 뒤늦은 업로드 — 로컬 변경이 9/1이면 9/20 서버 본(다른 사이클)을 이기지 못한다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `목표 변경 이력 합집합 — 서로 다른 주의 이력은 모두 남고 시각 오름차순, 완전히 같은 항목은 하나로` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `목표 변경 이력 합집합 — 같은 ISO 주에 두 기기가 각각 바꿨으면 먼저 바꾼 1건만 남긴다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `목표 변경 이력 병합 — 다른 사이클의 서버 본이 통째로 이겨도 로컬 이력이 합쳐져 남고, 양쪽 모두 없으면 nil` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `목표 변경 이력 병합 반영 — 기다리는 사이 로컬에서 바꾼 이력과 병합본 이력이 합집합으로 남는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 선택 — 동기화 이력 없는 설치가 서버의 다른 사이클 본을 만나면 묻는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 선택 — 서버 본이 없으면 묻지 않는다 (진짜 신규)` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 선택 — 같은 사이클이면 묻지 않는다 (maxStage 최댓값 병합이 지켜 준다)` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 선택 — 미래 스키마 서버 본은 불러올 수 없으니 묻지 않고, 병합이 keepServer로 보호한다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `복원 선택 — 이미 서버와 동기화한 설치는 묻지 않는다 (사이클 전환은 기존 병합 규칙)` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `업로드 보류 판정 — 서버 스키마가 더 크면 디코드 전에 보류하고, 같거나 작거나 필드가 없으면 보류하지 않는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `병합 반영 — 기다리는 사이 바뀐 로컬 필드는 유지하고, 안 바뀐 필드·도감·maxStage는 병합 결과를 쓴다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |
| `병합 반영 — 기다리는 사이 사이클이 바뀌면 새 사이클과 그 단계를 지키고, 지운 대회 기록은 되살리지 않는다` | CloudKit 기기 간 병합(`ProgressMergeEngine`) — Android는 Auto Backup 통째 복원이라 병합이 없다 |

## 반영하지 않은 iOS 변경

iOS 파일을 고쳤는데 Android에 옮길 것이 없을 때(iOS 전용 API·레이아웃 수정 등) 한 줄씩 적는다.
`parity_check.py --diff`는 이 문서에 **새로 추가된 줄**에 백틱으로 감싼 파일 이름(`` `Foo.swift` ``)이 있어야 통과한다 — 변경마다 새 줄을 적는다.

| 날짜 | iOS 파일 | 이유 |
|---|---|---|

## 알려진 동작 차이

기능은 같지만 플랫폼 때문에 결과가 달라질 수 있는 곳. 새로 생기면 여기에 적는다.

### 건강 데이터 (HealthKit ↔ Health Connect)

- **데이터가 없는 지표**: 러닝 다이내믹스(수직 진폭·지면 접촉·보폭), 심박 회복, 노력도, 운동 당시 기온·습도, 고도 상승, 생년월일은 Health Connect에 없거나 권한을 받지 않는다. 스토어가 nil을 주고 엔진 가드로 해당 카드가 사라진다.
- **HRV**: iOS는 SDNN, Android는 RMSSD다. 절대값이 다르지만 엔진은 개인 기준선 대비 변화만 본다.
- **최대심박 추정**: 생년이 없어 Tanaka 공식 폴백 대신 관찰 최대 → 190 → 수동 입력 순이다.
- **목록의 거리·심박·칼로리·케이던스**: 세션 통계가 없어 기간 단위로 읽은 표본을 세션 구간으로 잘라 계산한다(`SessionSlices`). 세션을 기록한 앱의 표본만 쓴다. 케이던스는 걸음 수 ÷ 분이고 목록에서는 최근 28일만 채운다.
- **경로**: Android 14는 다른 앱이 기록한 경로를 세션마다 동의받는다. 상세 화면에 "경로 보기 동의" 단계가 하나 더 있다.
- **동기화 시점**: 백그라운드 읽기 권한을 받지 않아 앱을 열 때만 읽는다. iOS의 운동 직후 자동 알림이 없다.
- **권한 확인**: Health Connect는 허용 여부를 조회할 수 있어 `HealthStore.hasPermissions()`가 있다(iOS는 빈 결과로만 안다).

### 알림

- `AlarmManager`의 부정확 알람이라 예약 시각보다 최대 10분(절전 중에는 그 이상) 늦을 수 있다. 정확 알람 권한은 선언하지 않는다.
- 예약 시각을 절대 시각으로 굳히므로 시간대를 옮기면 다음 재예약 때까지 옛 시각에 울린다.
- 재부팅하면 주간·대회 알림은 다시 걸리지만 수분 보충 알림은 다음 날씨 조회 때 걸린다.
- 알림 채널 이름(주간 러닝 리포트·수분 보충·대회 접수 알림)은 Android에만 있는 문자열이다.

### 백업

- CloudKit 대신 Android Auto Backup(사용자 Google 계정)이다. 기기 간 병합과 복원 선택 화면이 없고, 새 기기에서는 통째로 복원된다.
- 백업 범위는 설정 전체 + 도감 + 대회 기록이다. iOS CloudKit 스냅숏보다 넓어 알림 설정 등도 같이 복원된다. 건강 데이터 캐시와 러닝화(사진 포함)는 백업하지 않는다.

### 앱 셸 (RootView)

- **시트 → 전체 화면**: iOS가 sheet로 띄우는 오늘의 러닝 지수·리캡·재진단은 탭바를 가린 전체 화면 route다. 닫기는 시스템 뒤로가기.
- **탭바**: 시안 수치대로 직접 그린 탭바다(iOS 26 Liquid Glass 모양은 따라가지 않는다). 홈이 아닌 탭의 루트에서 뒤로가기를 누르면 홈 탭으로 간다.
- **루트 전환**: 온보딩·스플래시·탭 사이를 350ms 크로스페이드로 바꾼다.
- **복원 선택 없음**: `RestoreChoiceSheet`, 복원 대기 스플래시(`isCheckingRestore`), CloudKit 병합 반영·안내가 없다(Auto Backup은 앱 설치 때 시스템이 복원한다). 위젯 스냅숏 갱신·`HKObserverQuery` 훅, 옛 온보딩 답변 파일 정리도 없다.
- **미지원 기기 문구**: 'HealthKit을 지원하는 iPhone이 필요합니다.' → 'Health Connect를 지원하는 기기가 필요합니다.'
- **데모 모드의 러닝화 배정**: 데모 모드에서는 `ShoeStore.syncAssignments`를 항상 건너뛴다.
- **Android에만 있는 테스트**: `RaceEngineTests.kt`의 'route JSON 왕복 — …'(route 인자 직렬화 확인).

### 화면 동작 (시각은 iOS, 동작은 Android — 2026-10-05 결정)

- **아이콘**: SF Symbols 자리는 직접 그린 벡터(`RRIcons`)다. 날씨 아이콘은 단색이고, 애플워치 힌트 아이콘은 시계 아이콘이다.
  복장 추천 아이콘은 iOS 17 폴백 심볼 쪽을 따른다.
- **뒤로 버튼**: 브랜드 색 chevron이다(iOS 26 유리 버튼 모양은 따라가지 않는다). 홈 탭에서 시스템 뒤로가기는 앱을 나간다.
- **피커**: 주로 달리는 시각은 Material 드롭다운, 대회일은 Material 날짜 다이얼로그(시스템 언어로 표시, 지난 날짜 비활성), 숫자 휠은 `NumberWheel`.
- **시트**: 러닝화 선택·공유 카드·도감 상세는 Material 바텀 시트(공유 카드의 토글은 Material 스위치)다.
- **세리머니**: 전체 화면 다이얼로그이고 시스템 뒤로가기는 '나중에'와 같다.
- **터치 영역**: iOS의 `rrTapTarget` 대신 Compose 기본 최소 48dp를 쓴다.
- **당겨서 새로고침**: Material 인디케이터가 내용 위에 겹쳐 보인다.
- **접근성 알림**: VoiceOver 공지 자리는 `liveRegion(Polite)`다. TalkBack으로 실제 읽히는지는 확인하지 않았다.
- **홈의 권한 흐름**: 프로세스당 한 번 Health Connect 읽기 권한 → 대략적 위치 권한 → 날씨 갱신 순으로 요청한다.
  위치가 꺼져 있거나 거부된 상태에서 날씨 타일을 누르면 시스템 위치 설정·앱 정보 화면을 연다.
- **코스 탭**: 정확한 위치는 Android 12+의 '대략적 → 정확한' 업그레이드 다이얼로그로 받는다. 지도 키가 없으면 지도 자리가 빈다.
  GPX 선택은 시스템 문서 피커(GPX의 MIME이 제각각이라 넓게 받는다)이고, 올리기 버튼 아이콘은 공유 아이콘이다.
- **알림 토글**: 켜면 `POST_NOTIFICATIONS` 시스템 다이얼로그가 뜨고, 거부하면 토글이 꺼진다(아직 묻지 않은 상태도 거부로 본다).
- **의견 보내기**: `mailto:` 인텐트로 메일 앱을 연다. 본문의 플랫폼 표기는 "Android"(`FeedbackMail`의 `platform` 인자).
- **로드 실패 문구**: 첫 로드가 실패하면 Health Connect 예외 문구가 그대로 보인다.

### 문구 (iOS와 달라야 하는 곳만)

사용자 노출 문구는 iOS와 같게 옮기는 것이 원칙이고, 아래는 기기·시스템 이름 때문에 바꾼 곳이다.

- 오늘의 러닝 지수: '휴대전화의 위치 서비스가 꺼져 있어요', '설정 > 위치를 켜면 현재 위치의 날씨와 복장 추천을 볼 수 있어요.'
- 코스: '설정 > 위치에서 위치 사용을 켜 주시면 …', '설정 앱 → 애플리케이션 → 런미새 → 권한에서 위치를 허용해 주시면 …'
- 리포트: '갤럭시 워치가 잰 지난밤 활력징후를 최근 4주의 내 기준선과 비교한 추정치예요'
- 설정: '끄면 헬스 커넥트 최근값 NN bpm을 써요', 백업 줄 'Google 계정 백업 · 기기 백업이 켜져 있으면 설정·도감·대회 기록이 다시 설치할 때 복원돼요'
- 리캡·공유 카드 저장 실패: '저장하지 못했어요 — 기기 저장 공간을 확인해 주세요' (사진 권한이 없어 권한 안내 문구가 필요 없다)
- **'Apple 추정' 노력도 라벨**: 엔진 문자열이 iOS 테스트 기대값으로 고정돼 있어 데모 모드에서는 그대로 보인다.
  실제 Health Connect 데이터에는 노력도가 없어 이 줄 자체가 나오지 않는다.

### 검사 도구의 한계

- `--diff`는 파일 이름만 본다. `:engine`과 `:app`에 같은 이름의 `.kt`가 있는 경우(`RaceStore.kt`·`ShoeStore.kt` 등)
  둘 중 하나만 바뀌어도 통과하고, 내용이 실제로 같은지는 보지 않는다 — 리뷰에서 본다.
- Android에서 먼저 바꾼 변경과 Swift가 아닌 리소스(JSON·에셋) 변경은 자동으로 잡지 않는다.

### 그 밖

- **대회 기록 자연어 입력**: 없다(2026-10-05 결정). 파서(`RaceResultParser`)는 완주증 인식에 쓰므로 그대로 있다.
- **데모 상세 값**: 합성 상세의 시드가 러닝 id와 시작 시각 해시라, 같은 데모 러닝이라도 스플릿·심박 값이 iOS와 다르다.

- **캘린더**: 권한 없이 캘린더 앱의 일정 추가 화면을 연다. 저장 여부를 앱이 알 수 없어 버튼 상태가 바뀌지 않고, 대회 홈페이지는 메모 둘째 줄에 들어간다.
- **공유 카드**: 지도 타일 없이 경로 선만 그린다(Static Maps API 금지). 저장은 권한 없이 `Pictures/런미새`에 한다.
- **완주증 인식**: Vision 대신 ML Kit(온디바이스)다. ML Kit의 사용 통계 전송은 매니페스트에서 꺼 두었다.
- **위치**: 권한 요청은 화면이 한다. 정확한 위치 임시 허용(iOS) 대신 정확한 위치 권한을 다시 요청한다.
- **데모 데이터**: 에뮬레이터에서 자동으로 켜지지 않는다(설정의 데모 모드로만). iOS 시뮬레이터 전용인 데모 러닝화·데모 대기질·샘플 코스도 없다.
- **부동소수점**: `pow`/`exp` 구현 차이로 일부 산식이 마지막 1~2 ULP 다를 수 있다. 화면 표기(반올림 후)는 같다.
