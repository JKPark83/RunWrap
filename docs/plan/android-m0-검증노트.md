# Android M0 검증노트 — 삼성헬스 → Health Connect 실기기 판정

> [android-port-plan-v0.1.md](android-port-plan-v0.1.md) §2.2의 ⚠️ 항목을 갤럭시
> 실기기에서 판정해 기록한다. 이 결과가 M2~M5의 실제 범위를 확정한다.

- 검증일: (미기록)
- 기기 / One UI 버전: (미기록)
- 삼성헬스 버전 / HC 연동 상태: (미기록)

## 검증 절차

1. 삼성헬스로 **폰 GPS 러닝 1회** 기록 (워치 없이 폰만 들고)
2. 삼성헬스 설정 → Health Connect 연동 켜기 (전체 항목 공유 허용)
3. 런미새 디버그 빌드 설치: `cd android && ./gradlew :app:installDebug` (폰 USB 연결)
4. 앱 실행 → "권한 요청" → 시트에서 전부 허용
5. "최근 30일 덤프 실행" → 아래 표 채우기
6. 운동 세션 행이 "경로=동의 필요"면 "최신 세션 경로 열람 동의 요청" 버튼으로 동의 후 재확인

## ⚠️ 판정 대상 (계획서 §2.2)

판정 값: **온다**(삼성헬스 유래 레코드 확인) / **안 온다**(연동을 켜도 0건) /
**판정 불가(워치 필요)**(삼성헬스에 원천 데이터 자체가 없음)

| # | 항목 | HC 레코드 | 판정 | 근거(덤프 결과 그대로) |
|---|---|---|---|---|
| 1 | GPS 경로 | ExerciseSessionRecord.exerciseRouteResult | 미판정 | |
| 2 | 케이던스 시계열 | StepsCadenceRecord | 미판정 | |
| 3 | HRV | HeartRateVariabilityRmssdRecord | 미판정 | |
| 4 | 안정 심박 | RestingHeartRateRecord | 미판정 | |
| 5 | 호흡수 / 피부온 | RespiratoryRateRecord / SkinTemperatureRecord | 미판정 | |

## 참고 판정 — ✅ 예상 항목도 실제로 오는지 확인

| 항목 | HC 레코드 | 결과 |
|---|---|---|
| 운동 세션 | ExerciseSessionRecord | |
| 심박 시계열 | HeartRateRecord | |
| 거리 | DistanceRecord | |
| 걸음수 | StepsRecord | |
| 칼로리 | TotalCaloriesBurnedRecord | |
| VO₂max | Vo2MaxRecord | |
| 파워 | PowerRecord | |
| 속도 | SpeedRecord | |
| 수면 | SleepSessionRecord | |

## 결론 — v1 범위 영향

- (판정 후 기록: 안 오는 항목이 걸린 엔진과 §2.2 미노출 가드 적용 여부)
