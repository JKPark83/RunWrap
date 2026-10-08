# RunWrap Android — Claude 작업 지침

런미새 Android 앱. **iOS(`ios/RunWrap`)가 사양 원본**이고, 이 디렉터리는 그 이식본이다.
공통 원칙(표본 부족 시 nil, 건강 데이터 외부 전송 금지, 한국어 주석·문자열, 커밋 규칙)은
루트 [CLAUDE.md](../CLAUDE.md)를 그대로 따른다. 여기에는 Android에서 달라지는 것만 적는다.

## 확정 결정 (2026-10-05)

| 항목 | 값 |
|---|---|
| 데이터 소스 | **Health Connect 단일**. 삼성헬스가 HC에 동기화한 데이터만 읽는다. 읽기 전용. Samsung Health Data SDK는 쓰지 않는다(파트너 승인 필요) — 소스는 `health/` 뒤에 가둬 나중에 붙일 수 있게만 한다 |
| 권한 | HC 읽기 + `READ_HEALTH_DATA_HISTORY`(30일 이전 기록) + `READ_EXERCISE_ROUTES`. **백그라운드 읽기 권한은 요청하지 않는다** — 동기화는 앱이 열릴 때만 |
| 범위 | iOS 기능 전체 이식(4탭 + 온보딩·성장/도감·러닝화·리캡·훈련계획·공유 카드·알림·완주증 OCR·진행도 백업) |
| Android에 없는 것 | 홈 화면 위젯, 대회 기록 자연어 입력, 러닝 직후 자동 알림, 잠금화면 위젯 — `docs/parity.md`에 `ios-only`로 적는다 |
| 대응 데이터가 없는 지표 | 러닝 다이내믹스·심박 회복·노력도·운동 당시 날씨 등은 스토어가 nil을 주고 **엔진의 기존 가드로 카드가 사라진다**. 대체 산식을 만들지 않는다 |
| HRV | HC는 RMSSD만 준다(iOS는 SDNN). 개인 기준선 대비 상대 변화로만 쓴다 — 절대값을 iOS와 비교하지 않는다 |
| 지도 | Google Maps(`maps-compose`). 키는 gitignore된 `android/local.properties`의 `MAPS_API_KEY`. 키가 없어도 빌드되고 지도 자리는 빈 상태로 보인다. Static Maps API 금지(경로 좌표가 밖으로 나간다) — 공유 카드는 로컬 캡처 |
| 완주증 OCR | ML Kit 한국어 텍스트 인식(온디바이스, 번들 모델) |
| 진행도 백업 | Android Auto Backup. 건강 데이터 캐시는 백업 규칙에서 제외한다 |
| UI | 시각(색·폰트·카드·차트·일러스트·탭바·세그먼트)은 iOS 시안 그대로, 시스템 동작(뒤로가기·리플·다이얼로그·날짜 피커)은 Android 관례 |
| SDK | minSdk 34 · targetSdk 36 · compileSdk 36. 세로 전용 |
| 스택 | AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.2.21 · Compose BOM 2026.06.01 · JDK 17 |
| applicationId | `com.jkpark.runwrap` (표시 이름 "런미새") |

## 빌드·검증

```bash
cd android
export ANDROID_HOME=$HOME/Library/Android/sdk     # 환경변수가 없다 — 매번 필요
./gradlew :engine:test                            # 엔진 테스트 (순수 JVM — 빠르다, zone=UTC)
./gradlew :engine:test -PtestZone=Asia/Seoul      # 같은 테스트를 KST로 한 번 더
./gradlew :app:testDebugUnitTest :app:assembleDebug   # 앱 유닛 테스트(net·store) + 빌드
```

- 에뮬레이터는 AVD `syd_api36`만 부팅된다. 삼성헬스 동기화는 에뮬레이터로 검증할 수 없다 —
  합성 데이터는 디버그 빌드의 HC 시더로 넣는다.
  (`app/src/debug/…/debug/HealthSeedReceiver.kt` — 쓰기 권한 `pm grant` 뒤 `adb shell am broadcast -n com.jkpark.runwrap/.debug.HealthSeedReceiver`,
  자세한 순서는 그 파일 주석. 쓰기 권한은 디버그 매니페스트에만 있다 — 릴리스에 넣지 않는다.)
- 완료 기준은 루트 CLAUDE.md와 같다: 빌드 경고 0, 관련 테스트 통과, UI는 에뮬레이터 스크린샷으로
  iOS 화면과 대조, 돌려 본 것만 보고.

## 구조 — iOS 3계층 미러

Gradle 모듈이 둘이다. 패키지 루트는 모두 `com.jkpark.runwrap`.

| 모듈 · 패키지 | 내용 | 규칙 |
|---|---|---|
| `:engine` · `engine/` | 엔진·규칙·모델·파서·`Format` | **순수 Kotlin/JVM 모듈** — Android 의존성이 아예 없어서 `android.*`를 import하면 컴파일이 안 된다. 테스트는 여기서만 돈다 |
| `:app` · `health/` | Health Connect 접근 | HC 타입은 이 패키지 밖으로 나가지 않는다. `RunSummary` 등 엔진 모델로 변환해 넘긴다 |
| `:app` · `net/` | 날씨·대기질·대회정보 | `HttpURLConnection` + kotlinx.serialization |
| `:app` · `store/` | 캐시·설정·영속화 | filesDir JSON + SharedPreferences(iOS `UserDefaults`와 같은 키 이름) — 엔진에는 `KeyValueStore` 구현으로 넘긴다 |
| `:app` · `ui/` | `RR` 토큰·톤 색 매핑·차트·일러스트·공용 컴포넌트 | |
| `:app` · `screen/` | Compose 화면 | 데이터 가공 없이 엔진 결과를 그린다 |

소스 경로는 `engine/src/main/kotlin/com/jkpark/runwrap/engine/`, `engine/src/test/kotlin/com/jkpark/runwrap/engine/`,
`app/src/main/java/com/jkpark/runwrap/<패키지>/`.

**파일 이름은 iOS와 같게 짓는다** — `ios/RunWrap/BatteryEngine.swift` ↔ `BatteryEngine.kt`,
`ios/RunWrapTests/BatteryEngineTests.swift` ↔ `BatteryEngineTests.kt`.
`tools/ci/parity_check.py`가 이 이름 대응으로 한쪽만 바뀐 변경을 잡는다. 이름을 다르게 지어야 하면
`docs/parity.md`에 대응을 적는다.

## iOS와 결과를 똑같이 맞추는 규칙

엔진은 iOS와 **글자 단위까지 같은 결과**를 내야 한다. 테스트 기대값을 iOS에서 그대로 옮기기 때문이다.

- **소수 포맷**: `String.format`·`"%.1f".format()`을 쓰지 않는다(반올림이 다르다: 0.25 → iOS "0.2", Java "0.3").
  `engine/SwiftCompat.kt`의 `fmt(value, decimals)`(`%+.1f`는 `plus = true`)를 쓴다. 정수 지정자(`%d`, `%02d`)는 `String.format`을 써도 된다.
- **정수 반올림**: `roundToInt()`·`Math.round()`·`kotlin.math.round()`를 쓰지 않는다(-2.5 → iOS -3).
  Swift `.rounded()`는 `swiftRounded()`, `.rounded(.down)`은 `floor()`로 옮긴다.
- **시각**: `Date` → `java.time.Instant`. 엔진은 `now: Instant`와 `zone: ZoneId`를 주입받는다.
  엔진 안에서 `Instant.now()`·`ZoneId.systemDefault()`를 부르지 않는다.
  iOS가 `Calendar.current`를 쓰는 곳은 주입된 `zone`, KST로 고정한 곳은 `ZoneId.of("Asia/Seoul")`.
- **구간**: `DateInterval.contains`는 끝 시각을 포함한다 — `instant in start..end`(닫힌 구간)로 옮긴다.
- **주**: ISO 주(월요일 시작). 주 번호는 화면에 내지 않는다 — `Format.weekLabel`.
- **요일 번호**: iOS 저장값은 1=일…7=토다. `DayOfWeek`(1=월…7=일)와 섞지 않는다.
- **식별자**: iOS의 `UUID`는 `String`으로 옮긴다(HC 레코드 id가 UUID 형식이 아니다).
- **직렬화**: `Codable` → `@Serializable` + `EngineJson`. JSON 키 이름을 iOS와 같게 유지한다(번들 JSON을 공유한다).
  파일에 쓰는 시각은 `ReferenceDateInstantSerializer`(2001 기준 초), 설정에 넣는 시각은 `timeIntervalSince1970` Double.
- **설정 접근**: 엔진에서 `UserDefaults`를 쓰던 곳은 `KeyValueStore`를 주입받는다(`engine/EngineSupport.kt`).
- **한국어 날짜 문자열**: 요일·오전/오후·"M월 d일"은 `DateTimeFormatter`의 로케일 패턴으로 만들지 않고 직접 조립한다 —
  JVM 테스트(CLDR)와 기기(ICU)의 결과가 달라 테스트는 통과하고 기기에서 깨질 수 있다.
- **문자열 보간**: Swift `"\(x)"`의 Double 표기(`1e-05`, `5.0`)는 Kotlin과 다를 수 있다 — 사용자 노출 문자열에 Double을 그대로 넣지 말고 `fmt`를 쓴다.

## 코딩 스타일

- 주석은 한국어. **iOS 원본의 주석(산식 출처 포함)을 그대로 옮긴다** — 요약하거나 빼지 않는다.
- 사용자 노출 문자열은 iOS와 한 글자도 다르지 않게 옮긴다. `strings.xml`로 빼지 않는다(현지화 없음).
- 상태 관리는 `ViewModel` + `StateFlow`, 상태는 `sealed interface State`(Idle/Loading/Loaded/Unavailable/Failed).
- 색은 `RR` 토큰만. 색상 리터럴과 `isSystemInDarkTheme()` 분기를 화면 코드에 쓰지 않는다.
- 글자 크기는 iOS 시안 수치를 `sp`로 쓴다. 시스템 글꼴 배율은 앱 전체에서 1.3배까지만 반영한다.
- 차트는 Compose Canvas로 직접 그린다(`ui/RRCharts.kt`) — 차트 라이브러리를 넣지 않는다.
- **의존성 허용 목록**: AndroidX/Compose, `connect-client`, `maps-compose`, ML Kit 한국어 텍스트 인식,
  kotlinx.serialization, kotlinx.coroutines, JUnit 5. 그 외는 넣기 전에 묻는다.

## 테스트

- JUnit 5 + `kotlin.test` 단언. `@DisplayName`에 **iOS `@Test("…")`의 이름을 그대로** 적는다 —
  `parity_check.py`가 이 이름으로 빠진 테스트를 찾는다.
- 고정 시각을 주입한다. zone은 iOS 테스트가 고정한 곳은 그대로, `Calendar.current`에 기대던 곳은 `testZone`(TestSupport.kt)을 주입한다.
  한 iOS 파일에 `@Suite`가 여럿이면 같은 Kotlin 파일 안에 `@Nested inner class`로 둔다. 기대값과 그 산출 주석을 iOS 테스트에서 그대로 옮긴다.
- 기대값이 iOS와 달라져야만 통과한다면 이식이 틀린 것이다 — 기대값을 고치지 말고 원인을 찾는다.
  데이터 의미가 정말 다른 경우(RMSSD 등)만 예외이고, 주석으로 이유를 남긴다.
