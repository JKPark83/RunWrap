# App Review 심사 노트 초안 (런미새 1.0)

App Store Connect → 앱 심사 정보 → **비고(App Review Notes)** 칸에 붙여 넣을 원문입니다.
심사자는 대부분 영어권이므로 **영문을 본문으로 넣고 국문은 참고용**으로 둡니다.

- 로그인 계정: **불필요** (계정 개념이 없는 앱 — 데모 계정 칸은 비워 둡니다)
- 연락처: 박진곤 / sanaigon@gmail.com / 전화번호는 App Store Connect 심사 정보 칸에 입력

---

## 붙여 넣을 영문 (App Review Notes)

```
No account or sign-in is required to use this app.

IMPORTANT — HOW TO SEE THE FULL APP WITHOUT AN APPLE WATCH

This app interprets running workouts recorded by Apple Watch and read from
Apple Health. A review device with no running history in Health will show
empty states by design (see "Why screens can look empty" below), so we ship a
built-in demo mode. It is a normal, visible feature — not hidden or
conditional.

To turn it on:
  1. Launch the app and complete the two onboarding steps
     ("건강 데이터 연결" -> allow or deny the Health prompt, then choose a
     goal/level and tap "시작하기").
  2. On the first tab ("리포트"), tap the gear icon at the top right.
  3. In Settings, scroll to the "데모 모드" (Demo mode) section and turn on
     "샘플 데이터로 둘러보기" (Browse with sample data).
  4. Go back. Every tab is now populated with ~6 months of synthetic running
     data, so all reports, charts and cards can be reviewed.

A shortcut is also available without visiting Settings: on the empty report
screen, tap "샘플 리포트 둘러보기" (Browse a sample report) to open a full
sample weekly report in a sheet.

Demo mode uses locally generated synthetic data only. When it is on, the app
does not query HealthKit at all.

WHY SCREENS CAN LOOK EMPTY (this is intentional)

A core design rule of this app is that a metric is never shown when the
sample size is too small to compute it honestly (e.g. ACWR needs 4 weeks of
history). We would rather show nothing than show a wrong training insight.
Demo mode exists precisely so this rule does not make the app look incomplete
during review.

HEALTH DATA (Guideline 5.1.3)

- HealthKit access is READ-ONLY. requestAuthorization is always called with
  toShare: [] — the app never writes to Health.
- Read types are requested per feature (workouts/heart rate always; body mass
  only if the user picks a weight-related goal).
- All health data is processed on device. It is NEVER transmitted off the
  device. The app has exactly two network calls, neither of which carries any
  personal or health data:
    1. open-meteo.com — weather for the running-outfit suggestion (coarse
       coordinates only, nothing stored).
    2. raw.githubusercontent.com — a public JSON file listing upcoming Korean
       running races.
- The app has no analytics SDK, no ads, no third-party dependencies at all.

MEDICAL DISCLAIMER (Guideline 1.4.1)

Every interpretive card carries an in-app disclaimer stating that the app does
not diagnose and does not give medical advice, and that the user should
consult a professional if they feel pain or anything abnormal. The same notice
appears in section 7 of the privacy policy.

PRIVACY POLICY (Guideline 5.1.1(i))

https://runmisae-privacy.vercel.app/privacy.html
Also reachable inside the app: 리포트 tab -> gear icon -> "개인정보" section ->
"개인정보 처리방침".

OTHER PERMISSIONS

- Location (when in use): only to fetch weather for the outfit suggestion on
  the "오늘" tab. Declining it simply hides the weather card.
- Photo library (add only): to save a generated running story card. Optional.
- Notifications: optional reminders (hydration on hot days, weekly report).

LOCALIZATION

The app is Korean-only by design (Korean running community, Korean race data).
All UI strings are Korean. Screenshots and the descriptions are Korean.

DEVICE SUPPORT

iPhone only, portrait only, iOS 17.0+. An Apple Watch is not required to
install or review the app (see demo mode above), but is required to generate
real reports.
```

---

## 국문 참고본

계정·로그인은 없습니다. 데모 계정 칸은 비워 둡니다.

**애플워치 없이 전체 화면을 보는 방법 (가장 중요)**

이 앱은 애플워치로 기록되어 건강 앱에 저장된 러닝 기록을 해석합니다. 심사 기기에는 러닝 기록이
없으므로 설계상 화면이 비어 보입니다. 그래서 앱에 데모 모드를 넣어 두었고, 숨긴 기능이
아니라 설정 화면에 그대로 노출되는 정식 기능입니다.

1. 앱 실행 → 온보딩 2단계 진행(건강 데이터 연결 → 목표·레벨 선택 → 시작하기)
2. 리포트 탭 우측 상단 톱니 아이콘 탭
3. 설정에서 **데모 모드 → "샘플 데이터로 둘러보기"** 켜기
4. 뒤로 나오면 약 6개월치 합성 러닝 데이터로 모든 탭이 채워집니다

설정에 들어가지 않는 지름길도 있습니다 — 비어 있는 리포트 화면의
**"샘플 리포트 둘러보기"** 버튼을 누르면 주간 리포트 전체를 시트로 볼 수 있습니다.

**빈 화면이 나오는 이유(의도된 동작)**

표본이 부족하면 지표를 아예 내지 않는 것이 이 앱의 핵심 원칙입니다(ACWR은 4주치 필요).
틀린 훈련 해석을 보여 주느니 아무것도 보여 주지 않습니다. 데모 모드는 이 원칙 때문에
심사 중 앱이 미완성으로 보이지 않게 하려고 만들었습니다.

**건강 데이터** — 읽기 전용(`toShare: []`), 전부 온디바이스 처리, 외부 전송 없음.
네트워크 호출은 날씨(open-meteo)와 대회 목록(GitHub raw) 둘뿐이고 개인 데이터를 싣지
않습니다. 분석 SDK·광고·외부 의존성 없음.

**면책 고지(1.4.1)** — 해석 카드마다 "의학적 조언이 아니며 통증·이상 시 전문가 상담"
문구가 붙습니다. 개인정보 처리방침 7항에도 같은 내용이 있습니다.

**개인정보 처리방침(5.1.1(i))** — https://runmisae-privacy.vercel.app/privacy.html ·
앱 내 경로는 리포트 탭 → 톱니 → 개인정보 → 개인정보 처리방침.

---

## 제출 전 확인

- [ ] 심사 정보의 **로그인 필요 없음** 체크
- [ ] 개인정보 처리방침 URL을 App Store Connect 앱 정보에 입력 (위와 동일 주소)
- [ ] 연락처 이름·이메일·전화번호 입력
- [ ] 스크린샷 6장 업로드 (`docs/appstore/screenshots-6.9/`)
