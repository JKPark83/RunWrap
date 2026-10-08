# Google Play 스토어 등록정보 초안 (런미새 Android 1.0)

Play Console → 스토어 등록정보 / 앱 콘텐츠에 붙여 넣을 문안이다. iOS `docs/appstore/store-listing.md`·
`review-notes.md`를 바탕으로 "Apple Watch·아이폰·건강 앱" → "갤럭시 워치·휴대전화·헬스 커넥트"로 바꾸고,
Android에 없는 기능(위젯·대회 기록 자연어 입력·러닝 직후 자동 알림)은 뺐다. 정책 요건은
`review-considerations.md`와 출시 가이드(2부 10·11절)를 따른다.

| 항목 | 제한 | 현재 |
|---|---|---|
| 앱 이름 | 30자 | 3자 |
| 간단한 설명 | 80자 | 43자 |
| 자세한 설명 | 4,000자 | 약 1,900자 |

---

## 앱 이름

```
런미새
```

## 간단한 설명 (80자 이내)

```
갤럭시 워치 러닝 기록을 문장으로 해석합니다. 이번 주가 무리였는지 한 줄로.
```

**대안**
- `달린 기록, 대신 읽어 드립니다. 계정 없이, 건강 데이터는 기기 안에서만.`
- `이번 주 훈련, 무리였을까요? 러닝 기록을 말로 답하는 앱`

## 자세한 설명 (4,000자 이내)

키워드 반복은 정지 사유라 "러닝"을 억지로 늘리지 않았다. 마지막 두 문단이 의료 기기 아님 고지다.

```
달리기는 했는데, 이번 주가 잘한 건지 무리한 건지 모르겠다면.

런미새는 갤럭시 워치로 기록해 헬스 커넥트에 쌓인 러닝 기록을 대신 읽어 주는 앱입니다.
거리·페이스·심박을 다시 나열하지 않습니다. 대신 지금 몸이 어떤 상태인지,
오늘은 나가도 되는지를 말로 답합니다.

그리고 달리는 동안, 새 한 마리가 같이 자랍니다.


■ 알 하나에서 시작합니다

처음 열면 몇 가지를 여쭙고 러너 등급(런린이 · 런잘알 · 런친놈)을 진단한 뒤
알을 하나 드립니다. 여기서부터는 나가실 때마다 자랍니다.

• 알 → 금 간 알 → 부화 → 어린 새 → 날갯짓 → 나는 새, 6단계
• 멀리 뛴 것보다 꾸준히 나간 것을 더 보상합니다. 하루에 몰아 뛰어도
  그만큼 주지 않습니다 — 무릎이 먼저입니다
• 쉬었다고 깎지 않습니다. 벌점도 강등도 없고, 일주일 비면 새가 조금
  시무룩해질 뿐입니다. 한 번 나가면 바로 풀립니다
• 다 키운 새는 도감에 남고, 다음 목표와 함께 새 알을 받습니다.
  목표가 높을수록 큰 새가 나옵니다 — 참새에서 백조까지

첫 주 안에 알에 금이 갑니다. 나는 새까지는 한 시즌쯤 걸립니다.

■ 홈 카드 한 장 — 오늘 뛸까 말까

• 체력 배터리 — 심박 변이·안정 심박·수면·훈련 부하를 내 평소 기준선과
  비교해 오늘 남은 체력을 추정합니다
• 오늘 권장 거리와, 밖이 어떤지 — 체감 온도로 계산한 옷차림까지
• 더운 날에는 러닝 한 시간 전에 물 드시라고 알려드립니다

■ 이제 막 시작하셨다면

• 걷기 2분 · 뛰기 3분 × 5세트 — 주차에 맞춰 강도가 오르는 걷뛰 처방
• 아직 필요 없는 지표는 아예 띄우지 않습니다. 대신 이번 주에 무엇을
  하면 되는지만 남깁니다
• 못 나간 주가 있어도 처방은 뒤로 밀리지 않습니다. 이어서 하시면 됩니다

■ 목표한 대회가 있다면

• 부하 비율(ACWR) — 최근 7일 훈련량 ÷ 최근 4주 평균. 안전 구간을 벗어나면
  왜 위험한지까지 설명합니다
• 심박 효율(EF)·심장 드리프트 — 같은 심박에서 페이스가 빨라졌다면
  체력이 오르는 중입니다
• 주간 거리 10% 규칙, 케이던스·보폭 주법 리포트, 심폐 체력 추세
• 목표 레이스를 정하면 최근 기록으로 완주 시간을 예측하고(Riegel),
  이번 주에 무엇을 얼마나 달릴지 제안합니다

실력이 늘면 같은 화면에서 지표가 하나씩 열립니다. 화면을 새로 배울 일은 없습니다.

■ 어디서 달릴지, 어디서 물을 마실지

현재 위치에서 1km 안에 있는 급수대·화장실·편의점을 가까운 순으로
알려드립니다. 달릴 코스를 GPX로 올리면 몇 km 지점에 무엇이 있는지
미리 확인할 수 있습니다.

■ 다음 대회는 언제

국내 마라톤·러닝 대회 일정을 매일 갱신합니다. 접수 상태와 종목으로
걸러 볼 수 있습니다. 완주증을 찍으면 종목과 기록을 읽어 기록장에 넣어 드립니다.
오늘의 러닝은 탭 한 번이면 스토리에 올릴 수 있는 카드가 됩니다.


■ 이 앱이 지키는 것

• 표본이 부족하면 지표를 아예 내지 않습니다. 틀린 해석은 없느니만 못하니까요.
  (부하 비율은 4주치 기록이 모여야 계산됩니다)
• 건강 데이터는 헬스 커넥트에서 읽기 전용으로만 접근하고, 전부 휴대전화 안에서
  처리합니다. 서버로 보내지 않습니다. 계정도, 로그인도 없습니다.
• 광고와 추적이 없습니다.

■ 필요한 것

• Android 14 이상 휴대전화
• 헬스 커넥트에 저장된 러닝 기록 — 갤럭시 워치를 쓰신다면 삼성 헬스 설정에서
  헬스 커넥트 연동을 켜 주세요. 다른 워치·앱 기록도 헬스 커넥트에 들어와 있으면 읽습니다.
  아직 기록이 없다면 설정에서 샘플 데이터로 모든 화면을 미리 볼 수 있습니다.

런미새는 의료 기기가 아니며, 질병이나 건강 상태를 진단·치료·완치·예방하지 않습니다.
통증이나 이상이 있다면 반드시 의사 등 전문가와 상담하세요.
```

## 그 밖의 필드

| 필드 | 값 |
|---|---|
| 기본 언어 | 한국어 (ko-KR) |
| 앱 / 게임 | 앱 |
| 카테고리 | 건강/운동 (Health & Fitness) |
| 태그 | 콘솔에서 고를 수 있으면: 러닝, 피트니스 트래커 |
| 이메일 | sanaigon@gmail.com |
| 전화번호 | 한국 사용자용 필수 — 콘솔 개발자 프로필에 입력 |
| 웹사이트 | 비워 둠 (선택) |
| 개인정보처리방침 URL | https://runmisae-privacy.vercel.app/privacy-android.html |
| 가격 | 무료 (유료로 되돌릴 수 없음) |
| 국가 | 대한민국 |

개인정보처리방침 URL은 앱의 `privacyPolicyURL`(SettingsScreen.kt) 및 Health apps 선언의 링크와
**글자 하나까지 같아야** 한다.

## 그래픽 자산 (새로 만든다 — iOS 스크린샷 재사용 불가)

| 자산 | 규격 | 상태 |
|---|---|---|
| 앱 아이콘 | 512×512 PNG(알파 포함), ≤1MB | `graphics/icon-512.png` — iOS AppIcon-Light 축소 |
| 그래픽 이미지 | 1024×500 JPEG/PNG(알파 없음), **필수** | `graphics/feature-graphic-1024x500.png` |
| 휴대전화 스크린샷 | 2~8장, 긴 변 ≤ 짧은 변×2, 짧은 변 ≥320px | `screenshots/01~05` — 1080×1920, 홈·리포트·도감·코스·대회 |

iOS 6.9인치 스크린샷(9:19.5)은 2:1 규칙에 걸린다. 에뮬레이터 `syd_api36`(1080×2400)도 그대로는
안 되므로 `adb shell wm size 1080x1920`으로 비율을 바꿔 찍었다(앱 데모 모드 + SystemUI 데모 상태바 9:41).
에뮬레이터 fused 위치는 `geo fix`(GPS)를 받지 않아 날씨가 실패한다 — 홈 컷은 LocationProvider를
GPS_PROVIDER로 임시 바꾼 빌드로 찍고 되돌렸다(실기기는 무관).
세션 상세는 넣지 않았다 — 데모 합성 데이터가 Android에서 실제로는 안 나오는 노력도("Apple 추정")·열 보정 페이스를 보여 준다.

---

## 앱 콘텐츠 → App access (심사자용 지시문)

로그인은 없다. "모든 기능을 특별한 액세스 없이 이용 가능"이 아니라 **"일부 기능 제한"**을 고르고
아래 지시문을 넣는다 — 심사 기기에는 헬스 커넥트 러닝 기록이 없어 설계상 화면이 비기 때문이다.

### 붙여 넣을 영문

```
No login or account. All screens can be reviewed without a Galaxy Watch or any
Health Connect data by using the built-in demo mode (a regular, visible setting —
not a hidden switch).

How to see every screen with sample data:
1. Launch the app → complete the short onboarding quiz → tap "다음" on the result
   card → on the Health Connect permission sheet, Allow or Deny (either works).
2. Open the 리포트 (Report) tab → tap the gear icon at the top right.
3. In 설정 (Settings) → "데모 모드" → turn on "샘플 데이터로 둘러보기".
4. Go back: all four tabs are now filled with about six months of synthetic runs.

Shortcut: on the empty Report screen, tap "샘플 리포트 둘러보기" to open a full
sample weekly report as a sheet without visiting Settings.

Why screens are empty without demo mode (intended behavior): the app never shows a
metric from too small a sample (e.g. training-load ratio needs 4 weeks of runs).
It hides the card rather than show a wrong interpretation.

Health Connect: read-only, 15 read permissions requested once during onboarding
(including "allow reading past data"). Everything is computed on the device; no
health data leaves the phone. No background read permission is requested.

Other permissions: location (while in use) for weather and nearby water fountains
only; notifications for weekly report / hydration reminders. Camera and photo
permissions are not requested — the system camera app and photo picker are used
to read a finisher certificate on device (ML Kit, bundled Korean model; the photo
is not stored or uploaded).

Korean-only by design (Korean runners and races). Phones only, portrait,
Android 14+. Galaxy Watch not required to review (use demo mode).
```

### 국문 참고본

1. 앱 실행 → 온보딩 설문 → 결과 카드 "다음" → 헬스 커넥트 권한 허용/거부(어느 쪽이든 진입)
2. 리포트 탭 → 오른쪽 위 톱니
3. 설정 → **데모 모드 → "샘플 데이터로 둘러보기"** 켜기
4. 뒤로 나오면 약 6개월치 합성 러닝으로 네 탭이 채워진다

지름길: 빈 리포트 화면의 **"샘플 리포트 둘러보기"** 버튼.

---

## 앱 콘텐츠 → Data safety 답안

가이드 2부 9절의 표를 그대로 옮긴다. **위치 정밀도 결정이 남아 있다**(기본안: 날씨 좌표 소수 2자리 유지 →
"정확한 위치" 수집 예). 결정이 바뀌면 iOS 코드도 같이 바꾸고 양쪽 방침을 고친다.

| 데이터 유형 | 수집 | 공유 | 근거 |
|---|---|---|---|
| 건강·피트니스 (HC 전체) | 아니요 | 아니요 | 온디바이스 처리만 |
| 위치 — 정확한 위치 | **예** (일시적 처리, 저장 안 함, 앱 기능에 필수 아님) | 아니요 | 날씨 요청에 소수 2자리 좌표(약 1km²). 3km² 미만이라 "정확한 위치" |
| 위치 — 대략 위치 | 아니요 | 아니요 | 대기질은 측정소 이름만 보낸다 |
| 사진 | 아니요 | 아니요 | ML Kit 온디바이스, 저장·전송 없음 |
| 개인 정보(이름·이메일 등) | 아니요 | 아니요 | 계정 없음 |
| 앱 정보 및 성능 — 비정상 종료 로그 | **예** (Maps SDK) | 아니요 | SDK 코드 안 크래시 스택·비정상 종료 지표. 목적: 분석 |
| 앱 정보 및 성능 — 진단 | **예** (Maps SDK, ML Kit) | 아니요 | 기기 메타데이터(OS·모델)·SDK 버전·지연 시간. 목적: 분석 |
| 기기 또는 기타 ID | **예** (Maps SDK) | 아니요 | Maps SDK 전용 가명 식별자(일일 활성 사용자 집계용). 목적: 분석 |
| 앱 활동 — 앱 상호작용 | **예** (Maps SDK) | 아니요 | 카메라 API를 쓰면 지도 이동·확대 이벤트를 보낸다 — 이 앱은 코스·세션 지도에서 카메라를 옮긴다. 목적: 분석 |

SDK 항목 근거(2026-10-06 확인): [Maps SDK for Android data disclosure](https://developers.google.com/maps/documentation/android-sdk/play-data-disclosure) ·
[ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). 두 문서 모두 표 매핑은 없고
"응답은 개발자 책임"이라 적혀 있어, 위 분류는 *해석*이다.
- Play 기준으로 SDK가 기기 밖으로 보내는 것도 앱의 "수집"이다. Google이 SDK 제공자로서 받는 것은 "공유"로 보지 않았다(*해석*).
- 모두 **필수**(사용자가 끌 수 없음)로 답한다. 일시적 처리 여부는 문서가 요청 메타데이터·IP만 비영속이라 하므로 나머지는 "일시적 아님".
- IP 주소는 Maps 문서가 "SDK 사용 파악용"이라 할 뿐 위치 추정을 말하지 않아 "대략 위치"로 신고하지 않는다(*해석*).
- ML Kit: 매니페스트에서 `CctBackendFactory`를 빼 사용 통계 전송을 껐지만, 기기 네트워크 캡처로 확인한 적은 없다.
  어차피 ML Kit 항목(진단·ID)은 Maps SDK 때문에 이미 "예"라서 답은 달라지지 않는다.
- 방침(`privacy-android.html` §3 (6))에 Maps SDK 전송 항목을 2026-10-06에 덧붙여 Data safety와 맞췄다.

- 암호화 전송: 예 (모든 외부 통신 HTTPS)
- 삭제 요청 방법: 개발자가 보관하는 사용자 데이터가 없어 해당 없음 (방침 §5 안내). SDK가 보낸 정보는 Google 방침을 따른다
- Auto Backup은 사용자 본인 Google 계정으로 가고 개발자가 접근하지 않으므로 수집으로 신고하지 않는다

## 앱 콘텐츠 → 나머지

| 항목 | 답 |
|---|---|
| 광고 | 없음 |
| 콘텐츠 등급(IARC) | 유틸리티·생산성·커뮤니케이션 등 계열 → 폭력·성적 내용·약물·도박 전부 아니오 |
| 타깃 연령 | 18세 이상 (아동 대상 아님) |
| 뉴스 앱 / 코로나19 접촉 추적 / 금융 기능 / 정부 앱 | 모두 아니오 |
| Health apps | `review-considerations.md` §2 문구로 15개 타입 1:1 선언 |

## 제출 전 확인

- [ ] 간단한 설명 최종 선택
- [ ] 그래픽 이미지 1024×500, 스크린샷 2장 이상(2:1 규칙) 업로드
- [ ] App access 영문 지시문 붙여 넣기
- [ ] Data safety 위치 답과 실제 좌표 정밀도·권한(FINE/COARSE) 일치 확인
- [ ] 방침 URL 세 곳 일치: 스토어 등록정보 = Health apps 선언 = `privacyPolicyURL`
