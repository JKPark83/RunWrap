---
name: port-feature
description: iOS에서 바뀐 기능·수정을 Android 이식본에 같은 결과로 옮긴다. iOS(ios/RunWrap)를 고친 뒤, parity_check가 "iOS만 바뀜"을 알렸을 때, 또는 "안드로이드에도 반영해줘"라는 요청에 쓴다.
---

# iOS 변경을 Android에 반영하기

iOS가 사양 원본이고 Android는 이식본이다. 규칙은 `android/CLAUDE.md`, 대응표는 `docs/parity.md`.

## 순서

1. **무엇이 바뀌었는지 모은다**
   `python3 tools/ci/parity_check.py --diff $(git merge-base HEAD origin/dev)` — iOS만 바뀐 파일 목록.
   인자로 PR 번호나 커밋 범위를 받았으면 `git diff <범위> -- ios/`로 본다.
2. **대응 파일을 찾는다** — 이름이 같다(`Foo.swift` ↔ `Foo.kt`). 다르면 `docs/parity.md`의 파일 대응 표.
   `ios-only`로 적힌 파일의 변경은 옮기지 않는다.
3. **엔진부터 옮긴다** (`android/engine`) — iOS diff를 줄 단위로 같은 위치에 옮긴다. 주석·문자열은 그대로.
   바뀐/추가된 iOS 테스트를 같은 `@DisplayName`과 같은 기대값으로 옮긴다. 기대값을 고쳐서 통과시키지 않는다.
4. **스토어·화면을 옮긴다** (`android/app`) — 수치(색·크기·여백)와 문구는 iOS 그대로, 시스템 동작만 Android 관례.
   Health Connect에 대응 데이터가 없으면 스토어가 null을 주고 엔진 가드에 맡긴다(대체 산식 금지).
5. **새 외부 통신·권한·의존성**이 생기면 멈추고 묻는다. 진행하게 되면 `docs/privacy.html`,
   `docs/appstore/review-notes.md`, `docs/playstore/review-considerations.md`를 함께 고친다.
6. **검증**
   ```bash
   cd android && export ANDROID_HOME=$HOME/Library/Android/sdk
   ./gradlew :engine:test :app:assembleDebug && ./gradlew :engine:test -PtestZone=Asia/Seoul
   cd .. && python3 tools/ci/parity_check.py
   ```
   UI를 바꿨으면 에뮬레이터(AVD `syd_api36`) 스크린샷을 iOS 시뮬레이터 화면과 대조한다.
7. **옮기지 않은 것을 적는다** — Android에 반영할 것이 없거나(iOS 전용 API·레이아웃 버그 등) 미루는 변경은
   `docs/parity.md`의 "반영하지 않은 iOS 변경" 표에 파일 이름·이유를 적는다. 적지 않으면 CI가 실패한다.

반대 방향(Android에서 먼저 만든 기능)도 같다: iOS에 같은 이름의 파일·테스트로 옮기거나 `docs/parity.md`에 `android-only`로 적는다.
