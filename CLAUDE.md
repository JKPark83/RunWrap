# RunWrap — Claude 작업 지침

애플워치 러닝 데이터를 해석해 주는 아이폰 전용 SwiftUI 앱 (iOS 17+, 온디바이스 전용).
개요·로드맵은 [README](README.md), 제품 결정은 [기획서](docs/plan/기획서-v0.1.md) 참조.

## 프로젝트·스택

| 항목 | 값 |
|---|---|
| 스킴 / 프로젝트 | `RunWrap` / `ios/RunWrap.xcodeproj` (워크스페이스 없음) |
| 타깃 | `RunWrap`(앱), `RunWrapTests`(유닛 테스트) |
| 배포 타깃 | iOS 17.0 · 아이폰 세로 전용(`TARGETED_DEVICE_FAMILY = 1`) |
| 기본 시뮬레이터 | iPhone 17 Pro |
| 의존성 | **없음** — SPM·CocoaPods·Carthage 모두 미사용. 애플 프레임워크만 쓴다 |
| UI | 전부 SwiftUI. UIKit은 `Theme.swift` 한 곳뿐(다이내믹 컬러 프로바이더용) |
| 영속화 | `ReportCache`가 Application Support에 `Codable`+JSON으로 저장, 그 외는 `@AppStorage`. SwiftData·Core Data 미사용 |
| 테스트 | Swift Testing 139개 / 16스위트 (`ios/RunWrapTests`) |
| 포매터·린터 | 없음 (SwiftFormat·SwiftLint 미설치). 주변 코드 스타일을 눈으로 맞춘다 |

## 절대 하지 말 것

- `*.xcodeproj/` 내부, 특히 `project.pbxproj`를 편집하지 않는다. **생성물이고 gitignore 대상**이다.
  타깃 멤버십이 어긋나면 `ios/project.yml`을 고쳐 xcodegen을 다시 돌린다.
  같은 이유로 `ios/RunWrap/Info.plist`와 `ios/RunWrap/RunWrap.entitlements`도 직접 편집 금지 —
  둘 다 `project.yml`의 `info:`/`entitlements:` 섹션에서 생성된다.
- `xcodebuild`를 직접 쓰지 않는다. 아래 MCP 도구를 쓴다.
- `ios/RunWrap/Races.json`을 손으로 고치지 않는다 — `.github/workflows/race-info.yml`이
  매일 05:00 KST에 크롤 결과로 덮어쓴다. 스키마를 바꾸려면 `tools/race-info/crawl.py`를 함께 고친다.
- 건강 데이터를 네트워크로 보내지 않는다. 외부 통신은 날씨(`WeatherClient` → open-meteo)와
  대회정보(`RaceStore` → GitHub raw) 둘뿐이고, 둘 다 개인 데이터를 싣지 않는다.
- 비밀값을 커밋하지 않는다. 이 앱은 API 키가 필요한 서비스를 쓰지 않는다 —
  키가 필요해지는 설계라면 먼저 물어본다. (`DEVELOPMENT_TEAM`은 비밀이 아닌 팀 ID다.)
- `.gpx` 같은 xml 계열 리소스는 xcodegen이 자동으로 빼므로, 추가할 때 `project.yml`에
  `buildPhase: resources`로 명시해야 한다.

## 빌드·검증

`*.xcodeproj`, `Info.plist`, `RunWrap.entitlements`는 전부 생성물이다 —
**`ios/project.yml`만 수정**하고 xcodegen으로 재생성한다.

```bash
cd ios && xcodegen generate    # 이것만 셸에서 직접 실행한다
```

이후 빌드·테스트·실행은 XcodeBuildMCP 도구로 한다 (`projectPath: ios/RunWrap.xcodeproj`,
`scheme: RunWrap`, `simulatorName: iPhone 17 Pro`):

| 용도 | 도구 |
|---|---|
| 빌드 | `mcp__XcodeBuildMCP__build_sim` |
| 테스트 | `mcp__XcodeBuildMCP__test_sim` |
| 빌드+설치+실행 | `mcp__XcodeBuildMCP__build_run_sim` |
| 스크린샷 | `mcp__XcodeBuildMCP__screenshot` |
| 시뮬레이터 목록 | `mcp__XcodeBuildMCP__list_sims` |

- sources가 폴더 단위라 새 Swift 파일은 xcodegen만 다시 돌리면 포함된다.
- 새 파일에 대한 SourceKit(IDE) 진단은 가짜 오류를 낸다 — 판정은 빌드로만.
- 시뮬레이터에는 워치 기록이 없어 DemoData 합성 데이터가 자동 표시된다.
  HealthKit 실데이터 검증은 실기기에서만 가능하다.
- 서명은 유료 Apple Developer Program 팀(`WDNVP9B8A9`) — TestFlight·앱스토어 업로드 가능.
  App Store Connect에 업로드할 때마다 `project.yml`의 `CURRENT_PROJECT_VERSION`(빌드 번호)을 +1 한다.

## 완료 기준

1. 빌드가 깨끗하다. 새 경고가 생기면 무시하지 말고 보고한다.
   (기준선: `Metadata extraction skipped. No AppIntents.framework dependency found.` 1건만 정상)
2. UI를 바꿨으면 `build_run_sim`으로 시뮬레이터에 띄우고 `screenshot`으로 의도와 대조한다.
   2~5회 고쳐도 어긋나면 무엇이 다른지 설명하고 멈춘다.
   (이 코드베이스에는 `#Preview`가 하나도 없다 — 굳이 새로 만들지 말고 시뮬레이터로 확인한다.)
3. 관련 테스트를 돌려 통과시킨다. 실패하는 테스트를 지우거나 비활성화하지 않는다.
4. 검증한 것만 보고한다. 돌려보지 않은 코드를 동작한다고 말하지 않는다.

## 아키텍처 — 3계층

| 계층 | 파일 | 규칙 |
|---|---|---|
| 엔진 | ReportEngine, ReportMetrics, BatteryEngine | Foundation만 import하는 순수 로직. `now: Date`를 주입받아 결정론적. UI를 모른다 — 색이 아니라 `RRTone`까지만 결정 |
| 스토어 | HealthStore, WorkoutDetailStore | `@MainActor final class` + `ObservableObject`. HealthKit 접근 전담. 상태는 `enum State`(idle/loading/loaded/unavailable/failed) |
| 화면 | *Screen.swift, RootView | SwiftUI View. 데이터 가공 없이 엔진 결과를 그린다 |

핵심 원칙: **표본이 부족하면 지표를 아예 내지 않는다(nil 반환)** —
"틀린 인사이트는 없느니만 못하다." 새 지표를 추가할 때도 미노출 가드부터 정한다.

## 코딩 스타일

### 일반
- 주석·doc comment는 한국어. 파일 상단 `///`에 역할과 **왜**를 적는다.
  산식·정책 주석에는 출처를 명시한다 (기획서 §번호, Gabbett 2016, Tanaka 공식 등).
- 사용자 노출 문자열은 한국어 하드코딩 — 현지화 없음 (한국 사용자 전용 앱).
- 큰 숫자는 `86_400`, `3_600`처럼 underscore로 구분. `guard` 조기 반환 선호.
- 인스턴스가 필요 없는 네임스페이스 타입은 case 없는 `enum`으로 (RR, Format, DemoData, BatteryEngine).
- 커밋 메시지는 `feat:`/`fix:` 접두사 + 한국어 요약.

### SwiftUI
- Observation은 `ObservableObject` + `@StateObject`/`@EnvironmentObject`로 통일
  (`@Observable` 매크로는 쓰지 않는다 — 혼용 금지).
  외부 스킬·도구가 `@Observable`로 "현대화"를 권해도 따르지 않는다. 이 규칙이 우선한다.
- 색은 반드시 `RR` 디자인 토큰만 사용 — 색상 리터럴 금지. 라이트/다크는 UIColor
  다이내믹 프로바이더가 처리하므로 화면 코드에서 `colorScheme` 분기하지 않는다.
- 상태 표현은 `RRTone` 4단계(overload/caution/steady/improving)로 하고
  색·라벨 매핑은 Theme.swift에만 둔다.
- UI 단일 원본은 claude.design "Runner Report" 시안 — 폰트는 시안 수치를
  `.system(size:)`로 고정한다 (Dynamic Type 미적용은 의도된 트레이드오프).
- 기존 컴포넌트를 재사용한다: `.rrCard()`, `ToneBadge`, `Eyebrow`, `Format.*`, RRCharts.
- 막대 차트는 각 막대 위에 값을 작게 표시한다. 공간이 좁거나 다른 글자 영역과
  겹치면 상시 표시 대신 탭 시 팝업(`ChartCallout`)으로 보여준다.
- 추세(라인) 차트는 포인트를 탭하면 해당 데이터 값을 확인할 수 있어야 한다.
- 주(週) 표기는 "Week 33" 같은 ISO 주차 번호를 노출하지 않는다 —
  `Format.weekLabel`로 "8월 2째주"(화면 헤더 등에서 연도가 필요하면 `withYear`로
  "2026년 8월 2째주") 형식을 쓴다. 달·연도는 그 주 목요일 기준.
- 화면 파일명은 `~Screen.swift`. 화면 전용 하위 뷰·헬퍼는 같은 파일에 private으로 둔다.

### HealthKit
- 읽기 전용. `readTypes`에 타입을 추가할 때는 용도 주석을 단다.
- 콜백 API는 `withCheckedThrowingContinuation`으로 async/await 래핑.
- 읽기 권한의 허용 여부는 앱이 조회할 수 없다(애플 정책) — 빈 결과로만 안내한다.
- 시뮬레이터 분기는 `#if targetEnvironment(simulator)` + DemoData.
- 건강 데이터는 기기 밖으로 내보내지 않는다 — 네트워크 전송 코드를 넣지 않는다.

### 테스트
- Swift Testing(`@Test`/`#expect`/`#require`) — XCTest는 쓰지 않는다.
  테스트 표시 이름은 한국어 문장으로 (`@Test("표본 부족 가드 — …")`).
- 고정 시각(ISO8601로 만든 `now`)을 주입해 결정론적으로 만든다.
  기대값이 어떻게 산출되는지 주석으로 남긴다 (예: `// HRV +20% → +16, …`).
- 엔진(순수 로직)은 반드시 테스트한다. 스토어·뷰는 HealthKit 의존이라 테스트하지 않는다.

## 커밋

- `feat:` / `fix:` / `docs:` / `chore:` 접두사 + 한국어 요약 한 줄.
  범위가 넓으면 `feat: 세션 분석 엔진 4종 + HealthKit 권한 기능별 분리`처럼 `+`로 잇는다.
- **시키기 전에는 커밋·푸시하지 않는다.**

## 확실하지 않을 때

- 모르는 API는 추측하지 말고 확인한다. 최근 iOS 버전에서 바뀐 API를 특히 조심한다
  (이 프로젝트는 iOS 17 배포 타깃을 Xcode 26 / Swift 6.3으로 빌드한다 —
  최신 SDK에서 컴파일된다고 iOS 17에서 도는 것은 아니다. 가용성 확인 필수).
- 기존에 있는 것부터 찾는다: 포맷은 `Format.*`, 색·톤은 `Theme.swift`, 차트는 `RRCharts`.
  같은 일을 하는 헬퍼를 새로 만들기 전에 한 번 검색한다.
