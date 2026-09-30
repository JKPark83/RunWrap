# App Review 심사 노트 초안 (런미새 1.3 이상)

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
  1. Launch the app and complete onboarding (answer the short tap-only
     survey, then tap "다음" (Next) on the result card -> allow or deny the
     Health prompt; either choice continues into the app).
  2. Open the "리포트" tab (2nd of the 4 tabs at the bottom: 홈 / 리포트 /
     코스 / 대회) and tap the gear icon at the top right.
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
- All read types are requested together at the first Health connection
  (onboarding): running workouts (plus non-running workouts from the last
  14 days for a cross-training note), route, heart rate and running-form metrics,
  workout effort score (iOS 18+, shown on the session detail screen only),
  together with recovery signals such as sleep, HRV, resting heart rate,
  heart rate recovery, respiratory rate and wrist temperature; date of birth
  for max-HR estimation only. Body mass is never requested.
- The widget extension (RunWrapWidget) has no HealthKit entitlement. It only
  reads a derived snapshot (battery level, today's verdict sentence, weekly
  distance) that the app writes to the App Group container. Nothing leaves
  the device.
- All health data is processed on device. It is NEVER transmitted off the
  device. The app talks to the following hosts only; none of them receives
  health data:
    1. api.open-meteo.com — weather for the running-outfit suggestion and
       the 24-hour hourly forecast for the best-time-to-run suggestion, in
       the same request (coordinates rounded to 2 decimals, ~1 km; nothing stored).
    2. apis.data.go.kr — Korean public air-quality API (AirKorea). Only the
       name of the nearest monitoring station, picked on device from a
       bundled list, is sent; no coordinates.
    3. raw.githubusercontent.com — a public JSON file listing upcoming Korean
       running races.
    4. Race poster/thumbnail images, loaded directly from the external image
       URLs listed in the race JSON — the organizer's website or its image
       CDN, or a news/portal image host (e.g. Naver, Kakao) that covered the
       race. Hosts vary per race.
    5. iCloud (CloudKit private database) — one progress snapshot per user
       (level, goals, growth stage, bird collection, and past race results the
       user typed in by hand — distance, finish time, date) so progress survives a
       reinstall. No health data; the developer cannot read it; skipped when
       the user is not signed in to iCloud.
    6. Apple Maps (MapKit) — map tiles for the course/session maps and the
       share card.
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

- Location (when in use): to fetch weather and air quality for the outfit
  suggestion on the "홈" tab, and to list nearby water fountains / restrooms /
  convenience stores on the "코스" tab. Declining it hides those cards; nothing else
  breaks.
- Photo library (add only): to save a generated running story card. Optional.
- Notifications: optional reminders (hydration on hot days, weekly report).
- Calendars (write-only): asked only when the user taps "캘린더에 추가" on a race
  detail screen, to save that one race as a calendar event. The app cannot read
  existing events. Declining it only shows a hint to allow it in Settings.

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
읽기 권한은 첫 연결(온보딩)에 한 번에 요청하며(러닝 기록, 크로스 트레이닝 문장용 최근 2주 비러닝 운동 포함)
체중은 요청하지 않습니다.
홈·잠금화면 위젯(RunWrapWidget)은 HealthKit 권한이 없고, 앱이 App Group 컨테이너에 써 둔 파생 요약(배터리 수치, 오늘 판정 문장, 주간 거리)만 읽습니다. 기기 밖으로 나가는 것은 없습니다.
외부 통신은 날씨(api.open-meteo.com, 약 1km 좌표 — 같은 요청으로 24시간 시간대별 예보까지), 대기질(apis.data.go.kr, 측정소 이름만),
대회 목록(raw.githubusercontent.com), 대회 이미지(대회 JSON의 외부 이미지 URL — 주최측·언론사·포털 CDN), iCloud 진행도
백업(CloudKit 개인 DB, 건강 데이터 없음), Apple 지도 타일이 전부이고, 어느 것도 건강 데이터를
싣지 않습니다. 분석 SDK·광고·외부 의존성 없음.

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
