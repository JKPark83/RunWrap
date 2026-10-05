---
name: app-store-submit
description: 런미새(RunWrap)를 App Store 심사에 제출하거나 제출 전에 점검할 때 쓴다. 과거 리젝 사유(2.1 정보 필요, 5.1.1(iv) 권한 권유 문구)를 되풀이하지 않도록 코드·메모·메타데이터를 점검하고, ASC API로 버전 생성·빌드 연결·새로운 기능·심사 메모·제출까지 진행한다. "상용배포", "심사 제출", "앱스토어 출시", "리젝 대응" 요청에 쓴다.
---

# App Store 심사 제출 (런미새)

TestFlight 업로드는 `main` 머지 때 CI가 끝낸다. 이 스킬은 그 **다음 단계**,
이미 처리된 빌드를 App Store 심사에 올리는 일을 다룬다.

## 1. 과거 리젝 이력 — 반드시 다시 확인한다

| 날짜 | 버전 | 가이드라인 | 지적 | 대응 |
|---|---|---|---|---|
| 2026-08-14 | 1.0 (5) | **2.1 Information Needed** | 앱 이해에 필요한 정보 부족. 실기기 화면 녹화, 테스트 기기·OS, 기능·대상 사용자, 사용 방법, 외부 서비스, 지역 차이를 요청 | 심사 메모 보강 + 답장. Apple은 "앞으로 제출할 때 이 정보를 심사 메모에 넣으라"고 했다 |
| 2026-08-15 | 1.0 (5) | **5.1.1(iv) 권한 권유** | 건강 권한 요청 직전 버튼이 "건강 데이터 연결하기" — 허용을 권유함. "Continue"/"Next" 같은 중립 표현을 쓰라 | 버튼을 "다음"으로, 요청 이유는 버튼 아래 캡션으로 (이슈 #6, `OnboardingFlowScreen.swift`) |

원문은 App Store Connect → 앱 → 배포 → 앱 심사 → 제출 상세의 메시지에 있다.
ASC API로는 리젝 메시지를 읽을 수 없다 — 브라우저로 확인한다. 심사 메일은 사용자의 iCloud 메일로 온다.
새 리젝을 받으면 이 표에 한 줄 추가한다.

## 2. 제출 전 점검표

### 권한 요청 문구 (5.1.1(iv))
- 시스템 권한 시트 **직전** 커스텀 화면의 버튼은 "다음"/"계속"만 쓴다.
  "연결하기", "허용하기", "켜기", "시작하기"처럼 허용을 권유하는 동사는 금지.
- 요청 이유는 버튼이 아니라 설명 문구로, "허용 여부는 직접 정하시면 됩니다"처럼 결정권을 밝힌다.
- 거부해도 앱이 계속 진행돼야 한다(빈 상태 안내 + 설정 바로가기).
- 권한 호출 지점을 다시 훑는다:
  ```bash
  grep -rn -E "requestAuthorization|requestWhenInUse|requestWriteOnlyAccess|requestAccess\(for|health.connect\(\)" ios/RunWrap
  ```
  새 권한 요청이 생겼으면 그 직전 버튼 문구를 확인하고, `project.yml`의 사용 목적 문구(purpose string)가
  "왜 필요한지 + 예시"를 담는지도 본다.

### 심사 메모 (2.1) — 4,000자 이하
원본은 `docs/appstore/review-notes.md`의 영문 블록이다. 다음 항목이 **모두** 있어야 한다.
1. 계정 불필요 명시
2. 앱 기능·대상 사용자·해결하는 문제 (ABOUT THE APP)
3. 테스트한 실기기 모델·OS 버전 — `xcrun devicectl list devices`, `xcrun devicectl device info details --device <UDID>`로 확인한 값만 쓴다
4. 지역 차이 없음 (한국어 전용은 의도된 것)
5. Apple Watch 없이 보는 방법 = 데모 모드 진입 절차 (온보딩 버튼 이름이 바뀌면 같이 고친다)
6. 표본 부족 시 화면이 비는 이유
7. 건강 데이터: 읽기 전용, 기기 밖 전송 없음, 요청하는 읽기 타입 목록
8. 외부 통신 호스트 전체 목록 — CLAUDE.md "절대 하지 말 것"의 목록과 일치해야 한다
9. 의료 면책(1.4.1), 개인정보 처리방침 URL과 앱 내 경로
10. 그 밖의 권한(위치·사진·알림·캘린더·카메라)과 용도

ASC 메모 칸은 **4,000자 제한**이다. 저장소 원본이 더 길면 줄인 판본을 넣고, 저장소 문서도 같은 판본으로 맞출지 사용자에게 알린다.
ASC에 남은 메모는 이전 버전 값이 그대로 이어지므로 **오래된 메모가 남아 있지 않은지 diff로 확인**한다.

### 화면 녹화
2.1 재요청이 오면 실기기(최신 iOS)에서 **앱 실행부터** 온보딩 → 건강 권한 시트 → 각 탭 핵심 흐름 →
위치·카메라·캘린더 권한 요청까지 녹화해 답장에 첨부한다. 시뮬레이터 녹화는 안 된다.

### 메타데이터
- 새로운 기능(whatsNew): 직전 출시 버전 이후 `main`에 들어간 사용자 체감 변경만, 한국어, 런미새 톤.
  직전 버전에 없던 기능을 추가했다가 뺀 경우는 적지 않는다.
  ```bash
  git log --oneline --no-merges --since=<직전 버전 빌드 업로드일> origin/main | grep -v "대회정보 자동"
  ```
- 앱 개인정보 라벨은 API로 바꿀 수 없다. 새 외부 통신·새 데이터 수집이 있으면 사용자에게 웹에서 확인을 요청한다.
- 수출 규정: 빌드의 `usesNonExemptEncryption`이 false인지 확인한다.

## 3. 제출 절차 (ASC API)

`scripts/asc.py`가 JWT 인증 헬퍼다. 환경 변수 `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_PATH`가 필요하다
(값은 저장소에 두지 않는다 — 사용자 로컬의 `.p8` 키 경로와 ID를 쓴다). 앱 ID는 `6800630601`.

```bash
python3 .claude/skills/app-store-submit/scripts/asc.py status          # 버전·최근 빌드·열린 제출
python3 .claude/skills/app-store-submit/scripts/asc.py prepare 1.3 18 whats_new.txt notes.txt
python3 .claude/skills/app-store-submit/scripts/asc.py submit 1.3      # 되돌리기 어렵다 — 사용자 확인 후
```

- `prepare`는 버전이 없으면 만들고(`releaseType=AFTER_APPROVAL`), 빌드를 연결하고, 새로운 기능과 심사 메모를 넣는다.
  여러 번 돌려도 안전하다.
- `submit`은 심사 제출이다. **사용자가 제출을 명시적으로 요청했을 때만** 실행한다.
  출시 방식(승인 즉시/수동)도 사용자에게 정해 받는다.
- 심사 대기(`WAITING_FOR_REVIEW`) 중에도 심사 메모는 수정된다.

## 4. 보고
제출 후 상태(`WAITING_FOR_REVIEW`), 연결된 빌드, 출시 방식, 확인하지 못한 항목(개인정보 라벨 등)을 한국어로 보고한다.
