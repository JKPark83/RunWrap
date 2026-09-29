# 릴리스 체크리스트

`dev` → `main` 머지(= TestFlight 자동 업로드) 전후에 확인할 항목. 빌드 번호·마케팅 버전은
`.github/workflows/testflight.yml`이 정하므로 여기서 다루지 않는다.

## 1. CloudKit 운영 스키마 (이슈 #129)

진행도 백업(`ProgressBackupStore`)은 CloudKit private DB에 `ProgressSnapshot` 레코드 1건을 저장한다.
개발 빌드는 Development 환경에 레코드 타입을 자동 생성하지만, **TestFlight·App Store 빌드는
Production 환경을 쓰고 스키마를 자동 생성하지 않는다.** Production에 타입이 없으면 백업·복원이
전부 실패한다 (앱은 로컬 진행도를 지키며 조용히 넘어가므로 화면에서는 티가 나지 않는다).

- [ ] [CloudKit Dashboard](https://icloud.developer.apple.com/dashboard) → 컨테이너 `iCloud.com.jkpark.runwrap`
- [ ] Development 스키마에 `ProgressSnapshot` 레코드 타입과 필드 4개가 있는지 확인
  - `payload` (Bytes) · `revision` (Int64) · `schemaVersion` (Int64) · `updatedAt` (Date/Time)
- [ ] 같은 타입·필드가 **Production**에도 배포됐는지 확인
- [ ] 없거나 필드가 다르면 Development에서 **Deploy Schema Changes** 로 Production에 배포
- [ ] 레코드 필드를 추가·변경한 릴리스라면 배포 후에 머지한다 (Production 스키마는 필드 삭제가 안 되니 신중히)

## 2. TestFlight 빌드 확인

- [ ] 실기기(iCloud 로그인 상태)에서 TestFlight 빌드 설치 → 앱을 쓰고 백그라운드로 보낸 뒤
      설정 화면 하단의 **"iCloud 백업 · 9월 30일 오후 3:12"** 형식의 마지막 백업 시각이 갱신되는지 확인
  - "아직 없음"에서 바뀌지 않으면 1번(Production 스키마)부터 의심한다
  - 데모 모드에서는 백업을 하지 않아 이 줄이 숨겨진다 — 데모 모드를 끄고 확인한다
- [ ] 원인 확인이 필요하면 Mac의 콘솔 앱에서 기기를 선택하고 subsystem `com.jkpark.runwrap`,
      category `cloud-backup`으로 거른다. 실패 로그는 단계(`fetch`/`save`/`restore`)와 `CKError` 코드 숫자만 남긴다

## 3. 개인정보 문서

- [ ] 새 외부 통신을 추가했다면 `docs/privacy.html`과 `docs/appstore/review-notes.md`를 함께 고쳤는지 확인
