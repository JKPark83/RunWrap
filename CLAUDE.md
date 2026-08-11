# RunWrap — Claude 작업 지침

애플워치 러닝 데이터를 해석해 주는 아이폰 전용 SwiftUI 앱 (iOS 17+, 온디바이스 전용).
개요·로드맵은 [README](README.md), 제품 결정은 [기획서](docs/plan/기획서-v0.1.md) 참조.

## 빌드·검증

`*.xcodeproj`, `Info.plist`, `RunWrap.entitlements`는 전부 생성물이다 —
**`ios/project.yml`만 수정**하고 xcodegen으로 재생성한다.

```bash
cd ios && xcodegen generate
xcodebuild -project RunWrap.xcodeproj -scheme RunWrap \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

- sources가 폴더 단위라 새 Swift 파일은 xcodegen만 다시 돌리면 포함된다.
- 새 파일에 대한 SourceKit(IDE) 진단은 가짜 오류를 낸다 — 판정은 빌드로만.
- 시뮬레이터에는 워치 기록이 없어 DemoData 합성 데이터가 자동 표시된다.
  HealthKit 실데이터 검증은 실기기에서만 가능하다.

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
- 색은 반드시 `RR` 디자인 토큰만 사용 — 색상 리터럴 금지. 라이트/다크는 UIColor
  다이내믹 프로바이더가 처리하므로 화면 코드에서 `colorScheme` 분기하지 않는다.
- 상태 표현은 `RRTone` 4단계(overload/caution/steady/improving)로 하고
  색·라벨 매핑은 Theme.swift에만 둔다.
- UI 단일 원본은 claude.design "Runner Report" 시안 — 폰트는 시안 수치를
  `.system(size:)`로 고정한다 (Dynamic Type 미적용은 의도된 트레이드오프).
- 기존 컴포넌트를 재사용한다: `.rrCard()`, `ToneBadge`, `Eyebrow`, `Format.*`, RRCharts.
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
