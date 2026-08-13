# 런미새 (RunWrap)

애플워치 러닝 데이터를 의미 있는 지표로 재가공해 알려주고, 탭 한 번으로
인스타그램 스토리용 카드 이미지를 만들어주는 아이폰 전용 앱.
서버 없이 전부 온디바이스로 동작한다 — 건강 데이터는 기기 밖으로 나가지 않는다.

- 표시 이름은 **런미새**(러닝에 미친 사람), 내부 프로젝트명은 **RunWrap**.
- 상세는 [기획서 v0.6](docs/plan/기획서-v0.1.md) 참조.

## 구조

- `docs/plan/` — [기획서](docs/plan/기획서-v0.1.md)와 개발 계획서
  ([M0~M9](docs/plan/runwrap-plan-v0.1.md) · [M12 코스 보급 가이드](docs/plan/코스-보급-가이드-plan-v0.1.md) ·
  [M13 대회 캘린더](docs/plan/대회-캘린더-plan-v0.1.md))
- `docs/기능-산식-정리.html` — 화면별 기능과 지표 산식 정리
- `docs/appstore/` — 앱스토어 제출 자료: 스토어 문안·심사 노트·TestFlight 테스트 정보·6.9인치 스크린샷 6장
- `docs/privacy.html` — 개인정보 처리방침 (https://runmisae-privacy.vercel.app/privacy.html 로 호스팅)
- `ios/` — SwiftUI 앱 (xcodegen 프로젝트, 외부 의존성 없음)
- `tools/` — 데이터 파이프라인: `race-info`(대회 크롤러) · `course-poi`(급수·화장실·편의점 POI 빌드)
- `.github/workflows/race-info.yml` — 매일 05:00 KST 대회 정보 크롤 → `ios/RunWrap/Races.json` 갱신

앱은 5개 탭 — **리포트 · 통계 · 오늘 · 코스 · 대회**.

## 빌드

```bash
cd ios
xcodegen generate
open RunWrap.xcodeproj
```

- iOS 17+, 아이폰 세로 전용. HealthKit 읽기 전용
- `*.xcodeproj` / `Info.plist` / `RunWrap.entitlements`는 전부 생성물이다 —
  `ios/project.yml`만 고치고 xcodegen을 다시 돌린다
- 유닛 테스트 139개 (Swift Testing, `ios/RunWrapTests`) — 엔진(순수 로직)만 대상
- 시뮬레이터에는 워치 러닝 기록이 없어 DemoData 합성 데이터가 뜬다.
  HealthKit 실데이터 검증은 실기기에서만 가능하다

## 진행 상태 (기획서 §8 로드맵)

- [x] 1단계 — HealthKit 연결: 권한 요청 → 최근 러닝 목록
- [x] 2단계 — 리포트 엔진: 주간 증가율(10% 룰)·ACWR·심박 효율(EF), 체력 배터리(활력징후 기반)
- [x] 3단계 — 트레드밀/야외 구분 (세션 배지, 통계는 통합 집계)
- [x] 4단계 — 사용자 프로필 (목적·레벨, 온보딩+설정, 다이어트 몸무게 추이)
- [x] 5단계 — 퍼포먼스 발전상 (월별 추이 + PR)
- [x] 6단계 — 주법 리포트 (러닝 다이내믹스 + 조언)
- [x] 7단계 — 공유 카드 생성 (미니멀 데이터형·사진 배경형 2종) — **퀄리티 게이트 판정 대기**
- [x] 8단계 — 스토리 공유: 공유 시트(`ShareLink`)로 확정. `instagram-stories://` 스킴은 미사용
- [x] 9단계 — '오늘' 탭: 날씨(Open-Meteo) + 복장 추천
- [x] 10단계 — 훈련 가이드 (Riegel 완주 예측 + 주간 진단·처방)
- [x] 11단계 — 백그라운드 감지(`HKObserverQuery`) + 알림
- [x] 12단계 — 수분 섭취 알람 (예보 온도 조건부)
- [ ] 13단계 — 런미새 보이스&톤 + 칭호 시스템 (기획서 §4.11·§4.12)
- [x] 14단계 — 코스 보급 가이드: 현재 위치 반경 1km 또는 GPX 업로드 → 급수·화장실·편의점
- [x] 15단계 — 대회 캘린더: 크롤 배치 → 목록·상세·접수 상태, 키워드 검색·필터

로드맵 외 추가: 세션 분석 엔진 4종 — 열 보정 페이스(`HeatEngine`)·심박 드리프트(`DriftEngine`)·
크로스 트레이닝 요약(`CrossTrainingEngine`)·날씨 조언(`WeatherAdviceRules`).

전체적으로 **실기기 자가 검증(유용성·카드 퀄리티 판단)이 남은 관문**이다.

## 앱스토어 제출 (진행 중)

- [x] 제출 요건 — 프라이버시 매니페스트 · 수출 규정(`ITSAppUsesNonExemptEncryption`) · 데모 모드 게이트 · 개인정보 처리방침 호스팅
- [x] 제출 자료 — 스토어 문안 · 심사 노트 · TestFlight 테스트 정보 · 스크린샷 6장 (`docs/appstore/`)
- [x] 빌드 업로드 — 1.0 (3) App Store Connect 업로드 완료
- [ ] TestFlight — 빌드 처리 확인 → 테스트 정보 입력 → 내부 테스터 배포
- [ ] 실기기 자가 검증 — TestFlight 설치본으로 유용성·카드 퀄리티 판단
- [ ] App Store 심사 제출
