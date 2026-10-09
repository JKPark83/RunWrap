# App Review 심사 노트 초안 (런미새 1.3 이상)

App Store Connect → 앱 심사 정보 → **비고(App Review Notes)** 칸에 붙여 넣을 원문입니다.
ASC 칸은 **4,000자 제한**이라 아래 영문은 그 안에 맞춘 판본이다(1.4 제출본, 2026-10-09).
Guideline 2.1 리젝(2026-08-14) 때 Apple이 요구한 기능·대상 사용자·테스트 기기·지역 차이 항목을 반드시 유지한다.
심사자는 대부분 영어권이므로 **영문을 본문으로 넣고 국문은 참고용**으로 둡니다.

- 로그인 계정: **불필요** (계정 개념이 없는 앱 — 데모 계정 칸은 비워 둡니다)
- 연락처: 박진곤 / sanaigon@gmail.com / 전화번호는 App Store Connect 심사 정보 칸에 입력

---

## 붙여 넣을 영문 (App Review Notes)

```
No account or sign-in is required.

ABOUT THE APP (Guideline 2.1)
- What it does: interprets the user's own Apple Watch running workouts into plain-Korean reports (training load, recovery, pace, race prediction, course replay) and suggests today's training. Audience: Korean recreational runners who own an Apple Watch. No accounts, purchases, or user-generated content.
- Tested on: iPhone 17 Pro Max (iOS 27.0.1) paired with an Apple Watch, plus the iPhone 17 Pro simulator.
- Regions: features are identical in all regions; the UI and race data are Korean.

HOW TO SEE THE FULL APP WITHOUT AN APPLE WATCH
The app interprets running workouts recorded by Apple Watch and read from Apple Health. A device with no running history shows empty states by design, so a built-in demo mode is provided:
1. Launch the app and complete onboarding (tap-only survey; tap "다음" (Next), then allow or deny the Health prompt; either choice continues).
2. On the "홈" tab (1st of 4 tabs: 홈 / 리포트 / 코스 / 대회) tap the gear icon at the top right.
3. In "데모 모드" (Demo mode), turn on "샘플 데이터로 둘러보기" (Browse with sample data).
4. Go back. Every tab now shows ~6 months of synthetic running data.
Shortcut: on the empty report screen, tap "샘플 리포트 둘러보기" to open a sample weekly report.
Demo data is generated locally; while it is on, HealthKit is not queried.

WHY SCREENS CAN LOOK EMPTY (intentional)
A metric is never shown when the sample is too small to compute it honestly (e.g. ACWR needs 4 weeks). We prefer nothing over a wrong insight.

HEALTH DATA (5.1.3)
- HealthKit is READ-ONLY (toShare: []). The app never writes to Health.
- All read types are requested together at onboarding: running workouts, route, heart rate and running-form metrics, workout effort score (iOS 18+), sleep, HRV, resting HR, HR recovery, respiratory rate, wrist temperature, and date of birth (max-HR estimate only). Body mass is never requested.
- The widget extension has no HealthKit entitlement; it only reads a derived snapshot (battery level, today's verdict, weekly distance) the app writes to the App Group container.
- All health data is processed on device and NEVER transmitted. Network hosts (none receives health data):
1. api.open-meteo.com: weather and 24-hour forecast (coordinates rounded to 2 decimals, nothing stored).
2. apis.data.go.kr: Korean air-quality API. Only the nearest station name is sent, no coordinates.
3. raw.githubusercontent.com: public JSON of upcoming Korean races.
4. Race poster images from the external URLs in that JSON.
5. iCloud (CloudKit private DB): one progress snapshot (level, goals, bird collection, hand-entered race results) to survive reinstall. No health data; skipped without iCloud sign-in.
6. Apple Maps (MapKit) tiles, incl. the 3D course replay.
- GPX export: only on user request, one run's route is written to a GPX file and handed to the iOS share sheet; the user picks the destination.
- No analytics, no ads, no third-party dependencies.

MEDICAL DISCLAIMER (1.4.1)
Every interpretive card states that the app does not diagnose or give medical advice and that users should consult a professional for pain or anything abnormal. Also in section 7 of the privacy policy.

PRIVACY POLICY (5.1.1(i))
https://runmisae-privacy.vercel.app/privacy.html
In app: 홈 tab, gear icon, "개인정보" section, "개인정보 처리방침".

OTHER PERMISSIONS (all optional; declining only hides the related card)
- Location (when in use): weather/air quality on "홈"; nearby fountains, restrooms, stores on "코스".
- Photo library (add only): save a running story card.
- Notifications: hydration on hot days, weekly report.
- Calendars (write-only): only when tapping "캘린더에 추가" on a race detail. Cannot read events.
- Camera: only when tapping "촬영" in "대회 기록 추가" to read a finisher certificate on device (Vision). Photo is not stored or uploaded.

Korean-only by design (Korean runners and races). iPhone only, portrait, iOS 17.0+. Apple Watch not required to review (use demo mode), but required for real reports.
```

---

## 국문 참고본

계정·로그인은 없습니다. 데모 계정 칸은 비워 둡니다.

**애플워치 없이 전체 화면을 보는 방법 (가장 중요)**

이 앱은 애플워치로 기록되어 건강 앱에 저장된 러닝 기록을 해석합니다. 심사 기기에는 러닝 기록이
없으므로 설계상 화면이 비어 보입니다. 그래서 앱에 데모 모드를 넣어 두었고, 숨긴 기능이
아니라 설정 화면에 그대로 노출되는 정식 기능입니다.

1. 앱 실행 → 온보딩 설문 진행 → 결과 카드에서 "다음" → 건강 권한 허용/거부(어느 쪽이든 진입)
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
읽기 권한은 첫 연결(온보딩)에 한 번에 요청하며(러닝 기록 포함)
체중은 요청하지 않습니다.
홈·잠금화면 위젯(RunWrapWidget)은 HealthKit 권한이 없고, 앱이 App Group 컨테이너에 써 둔 파생 요약(배터리 수치, 오늘 판정 문장, 주간 거리)만 읽습니다. 기기 밖으로 나가는 것은 없습니다.
외부 통신은 날씨(api.open-meteo.com, 약 1km 좌표 — 같은 요청으로 24시간 시간대별 예보까지), 대기질(apis.data.go.kr, 측정소 이름만),
대회 목록(raw.githubusercontent.com), 대회 이미지(대회 JSON의 외부 이미지 URL — 주최측·언론사·포털 CDN), iCloud 진행도
백업(CloudKit 개인 DB, 건강 데이터 없음), Apple 지도 타일이 전부이고, 어느 것도 건강 데이터를
싣지 않습니다. 분석 SDK·광고·외부 의존성 없음.

**GPX 내보내기** — 세션 상세의 "GPX 파일로 내보내기"를 누를 때만(사용자가 요청할 때만 GPX 파일로 내보냄) 그 러닝 1건의
경로·시각·고도·심박을 GPX 파일로 만들어 iOS 공유 시트에 넘깁니다. 앱이 직접 전송하지 않고, 받을 앱은 사용자가 고릅니다.

**캘린더(쓰기 전용)** — 대회 상세의 "캘린더에 추가"를 누를 때만 요청하며, 그 대회 1건을 이벤트로 저장합니다.
기존 일정은 읽지 않고, 거부하면 설정에서 허용하라는 안내만 뜹니다.

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
