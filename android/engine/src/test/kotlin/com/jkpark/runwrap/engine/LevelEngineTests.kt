package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 레벨 판정 엔진 검증 — 기획서 §3 결정표 5행 + 경계 케이스 + 실데이터 승급 후보 판정
@DisplayName("레벨 판정 엔진")
class LevelEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")

    /// 미지정 필드는 nil로 두는 기본 답변 빌더 — 각 테스트가 필요한 필드만 채운다
    private fun answers(q1: OnboardingAnswers.Q1,
                        q2: OnboardingAnswers.Q2Longest? = null,
                        q3: OnboardingAnswers.Q3Record? = null,
                        q4: OnboardingAnswers.Q4Monthly? = null): OnboardingAnswers =
        OnboardingAnswers(q1Experience = q1, q2aActivity = null, q2Longest = q2, q3Record = q3,
                          q4Monthly = q4, q5Frequency = null, q6Race = null, q7Target = null,
                          q8GoalSec = null, q9Purposes = emptyList())

    private fun run(daysAgo: Double, km: Double, minPerKm: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * minPerKm * 60,
                   distanceMeters = km * 1000,
                   avgHeartRate = null)

    /// (Android: 위 run과 파라미터 타입(Double×3)이 같아 Kotlin은 라벨로 오버로드를 못 가른다 — 이름만 나눈다)
    private fun runWithDuration(daysAgo: Double, km: Double, durationSec: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = durationSec,
                   distanceMeters = km * 1000,
                   avgHeartRate = null)

    /// 런친놈 근거용 최근 4주 기록 — 3일 전 풀(42.195km, `fullSec`) + 5~20일 전 6'00"/km 조깅 16회.
    /// 풀도 누적 거리에 들어가므로 28일 누적 = `totalKm`이 되도록 나머지를 16회로 나눈다.
    private fun mileageRuns(totalKm: Double, fullSec: Double): List<RunSummary> {
        val full = runWithDuration(daysAgo = 3.0, km = 42.195, durationSec = fullSec)
        val eachKm = (totalKm - 42.195) / 16
        return listOf(full) + (1..16).map { run(daysAgo = it.toDouble() + 4, km = eachKm, minPerKm = 6.0) }
    }

    // MARK: 판정 결정표 — 순서 1

    @Test
    @DisplayName("결정표 1행 — Q1 무경험이면 나머지 답과 무관하게 런린이")
    fun rule1NoviceIsBeginner() {
        // Q2·Q3·Q4가 최상위 조건이어도 Q1이 무경험이면 무조건 런린이
        val a = answers(OnboardingAnswers.Q1.novice, q2 = OnboardingAnswers.Q2Longest.fullFinisher,
                        q3 = OnboardingAnswers.Q3Record.fullUnder430, q4 = OnboardingAnswers.Q4Monthly.over200)
        assertEquals(RunnerLevel.beginner, LevelEngine.decide(a))
    }

    // MARK: 판정 결정표 — 순서 2

    @Test
    @DisplayName("결정표 2행 — 풀코스 완주 AND 풀 4:30 이내 AND 월 200km 이상은 런친놈")
    fun rule2AllThreeConditionsIsAdvanced() {
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.fullFinisher,
                        q3 = OnboardingAnswers.Q3Record.fullUnder430, q4 = OnboardingAnswers.Q4Monthly.over200)
        assertEquals(RunnerLevel.advanced, LevelEngine.decide(a))
    }

    @Test
    @DisplayName("2행 미충족(월 마일리지 부족)이면 3행으로 내려가 런잘알")
    fun rule2FailsFallsThroughToRule3() {
        // 풀 완주 + 4:30 이내지만 월 200km 미만 — 2행 조건 3개 중 하나가 빠짐
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.fullFinisher,
                        q3 = OnboardingAnswers.Q3Record.fullUnder430, q4 = OnboardingAnswers.Q4Monthly.hundredTo200)
        assertEquals(RunnerLevel.intermediate, LevelEngine.decide(a))
    }

    // MARK: 판정 결정표 — 순서 3

    @Test
    @DisplayName("결정표 3행 — 하프~풀 완주(2행 미충족)는 런잘알")
    fun rule3HalfToFullIsIntermediate() {
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.halfToFull)
        assertEquals(RunnerLevel.intermediate, LevelEngine.decide(a))
    }

    @Test
    @DisplayName("결정표 3행 — 풀코스 완주만으로도(기록 정보 없어도) 런잘알")
    fun rule3FullFinisherWithoutRecordIsIntermediate() {
        // Q3가 nil(하프~풀 완주자는 Q3 건너뜀)이어도 2행 조건 미충족이면 3행이 적용된다
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.fullFinisher,
                        q3 = null, q4 = null)
        assertEquals(RunnerLevel.intermediate, LevelEngine.decide(a))
    }

    // MARK: 판정 결정표 — 순서 4

    @Test
    @DisplayName("결정표 4행 — 10km 1시간 이내 기록은 런잘알")
    fun rule4TenKUnderHourIsIntermediate() {
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.fiveToTen,
                        q3 = OnboardingAnswers.Q3Record.tenUnder60)
        assertEquals(RunnerLevel.intermediate, LevelEngine.decide(a))
    }

    // MARK: 판정 결정표 — 순서 5 (기본값)

    @Test
    @DisplayName("결정표 5행 — 나머지 전부는 런린이")
    fun rule5EverythingElseIsBeginner() {
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.under5, q3 = null)
        assertEquals(RunnerLevel.beginner, LevelEngine.decide(a))
    }

    @Test
    @DisplayName("경계 — 10km를 1시간 조금 넘게(60분 미충족)면 런린이로 하향")
    fun boundaryTenKJustOverHourIsBeginner() {
        // "1시간 이내"가 아니므로 4행 미충족 — 애매하면 아래 레벨(미노출 가드와 같은 철학, §3)
        val a = answers(OnboardingAnswers.Q1.experienced, q2 = OnboardingAnswers.Q2Longest.fiveToTen,
                        q3 = OnboardingAnswers.Q3Record.tenOver60)
        assertEquals(RunnerLevel.beginner, LevelEngine.decide(a))
    }

    // MARK: 실데이터 승급 후보

    @Test
    @DisplayName("최근 4주 내 10km를 60분 이내로 달렸으면 런린이 → 런잘알 후보")
    fun promotionCandidateWhenRecentFastTenK() {
        // 8일 전 10km를 55분(분당 5.5분 페이스)에 완주 — 4주 이내, 60분 이내 조건 충족
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 5.5))
        assertEquals(PromotionEvidence.tenKmPace(runs[0]),
                     LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now))
    }

    @Test
    @DisplayName("15km를 75분에 달린 런린이 → 10km 환산 50분이라 런잘알 후보")
    fun promotionCandidateWhenFifteenKTenKmPace() {
        // 15km × 5'00"/km = 4_500초 → 10km 환산 4_500 / 15 × 10 = 3_000초(50분) ≤ 3_600
        val runs = listOf(run(daysAgo = 5.0, km = 15.0, minPerKm = 5.0))
        val result = LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now)
        assertEquals(PromotionEvidence.tenKmPace(runs[0]), result)
        assertEquals(RunnerLevel.intermediate, result?.target)
    }

    @Test
    @DisplayName("하프(21.1km)를 완주한 런린이 → 10km 환산이 1시간을 넘어도 런잘알 후보")
    fun promotionCandidateWhenHalfFinished() {
        // 21.1km × 7'30"/km = 9_495초 → 10km 환산 4_500초(75분) > 3_600이라 10km 근거는 아니지만
        // 21.1km ≥ 21.0975km라 하프 완주 근거
        val runs = listOf(run(daysAgo = 10.0, km = 21.1, minPerKm = 7.5))
        val result = LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now)
        assertEquals(PromotionEvidence.halfFinish(runs[0]), result)
        assertEquals(RunnerLevel.intermediate, result?.target)
    }

    @Test
    @DisplayName("런린이가 런친놈 근거(월환산 200km + 풀 4:20)를 갖춰도 한 단계(런잘알)만 제안")
    fun beginnerWithAdvancedEvidencePromotesOneStep() {
        // 28일 187km × 30/28 = 200.4km, 풀 4:20(15_600초) — 그래도 런린이는 런잘알까지만
        val runs = mileageRuns(totalKm = 187.0, fullSec = 15_600.0)
        assertEquals(RunnerLevel.intermediate,
                     LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now)?.target)
    }

    @Test
    @DisplayName("10km를 60분 넘게 달렸으면 승급 후보 아님")
    fun noPromotionWhenTenKOverHour() {
        // 10km를 65분(분당 6.5분 페이스)에 완주 — 60분 조건 미충족
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 6.5))
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now))
    }

    @Test
    @DisplayName("0초·비현실 페이스 10km 기록은 승급 후보 아님 — 정상 10km 55분은 후보 (이슈 #78)")
    fun noPromotionFromZeroDurationTenK() {
        // 8일 전 0초 10km 임포트 — durationSec 0 ≤ 3_600이지만 paceSecPerKm == nil이라 근거가 아니다
        val zeroDuration = RunSummary(id = UUID.randomUUID().toString().uppercase(),
                                      start = now.minusSeconds(8L * 86_400),
                                      durationSec = 0.0, distanceMeters = 10_000.0, avgHeartRate = null)
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = listOf(zeroDuration), now = now))
        // 같은 창의 정상 10km 55분(330초/km)이 있으면 후보
        val runs = listOf(zeroDuration, run(daysAgo = 8.0, km = 10.0, minPerKm = 5.5))
        assertEquals(PromotionEvidence.tenKmPace(runs[1]),
                     LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now))
    }

    @Test
    @DisplayName("빠른 10km 기록이 4주보다 오래됐으면 승급 후보 아님")
    fun noPromotionWhenFastRunTooOld() {
        // 30일 전 — 4주(28일) 관측 창을 벗어난다
        val runs = listOf(run(daysAgo = 30.0, km = 10.0, minPerKm = 5.5))
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.beginner, runs = runs, now = now))
    }

    @Test
    @DisplayName("이미 런친놈(최상위)이면 승급 후보를 내지 않는다")
    fun noPromotionWhenAlreadyAdvanced() {
        val runs = listOf(run(daysAgo = 8.0, km = 10.0, minPerKm = 5.5))
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.advanced, runs = runs, now = now))
        // 런친놈 근거(28일 187km × 30/28 = 200.4km + 풀 4:20)가 있어도 마찬가지
        val advancedRuns = mileageRuns(totalKm = 187.0, fullSec = 15_600.0)
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.advanced, runs = advancedRuns, now = now))
    }

    // MARK: 실데이터 승급 후보 — 런잘알 → 런친놈

    @Test
    @DisplayName("런잘알: 월환산 200km 이상 + 풀 4:30 이내 둘 다 충족 → 런친놈 후보")
    fun intermediatePromotesWhenMileageAndFullUnder430() {
        // 28일 187km × 30/28 = 200.36km ≥ 200, 풀 4:20(15_600초) ≤ 16_200초
        val runs = mileageRuns(totalKm = 187.0, fullSec = 15_600.0)
        val result = LevelEngine.promotionCandidate(current = RunnerLevel.intermediate, runs = runs, now = now)
        assertEquals(RunnerLevel.advanced, result?.target)
        val evidence = assertIs<PromotionEvidence.fullUnder430>(result, "풀 4:30 근거가 아님: $result")
        assertEquals(runs[0], evidence.run)
        assertTrue(abs(evidence.monthlyKm - 187.0 * 30 / 28) < 0.001)
    }

    @Test
    @DisplayName("런잘알: 풀 4:20이어도 월환산 200km 미만이면 승급 후보 아님")
    fun intermediateNoPromotionWhenMileageShort() {
        // 28일 186km × 30/28 = 199.3km < 200 — 마일리지 부족(경계 바로 아래)
        val runs = mileageRuns(totalKm = 186.0, fullSec = 15_600.0)
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.intermediate, runs = runs, now = now))
    }

    @Test
    @DisplayName("런잘알: 월환산 200km 이상이어도 풀이 4:30을 넘으면 승급 후보 아님")
    fun intermediateNoPromotionWhenFullOver430() {
        // 28일 187km × 30/28 = 200.4km ≥ 200이지만 풀 4:40(16_800초) > 16_200초
        val runs = mileageRuns(totalKm = 187.0, fullSec = 16_800.0)
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.intermediate, runs = runs, now = now))
    }

    @Test
    @DisplayName("런잘알: 4주 창 밖 기록은 월환산 마일리지에 넣지 않는다")
    fun intermediateMileageIgnoresRunsOutsideWindow() {
        // 창 안 187km(200.4km 환산)에서 조깅 1회((187 − 42.195) / 16 = 9.05km)를 30일 전으로 옮기면
        // 창 안 누적 177.95km × 30/28 = 190.7km < 200 → 후보 아님
        val runs = mileageRuns(totalKm = 187.0, fullSec = 15_600.0).toMutableList()
        val moved = runs.removeAt(runs.lastIndex)
        runs.add(run(daysAgo = 30.0, km = moved.distanceKm ?: 0.0, minPerKm = 6.0))
        assertNull(LevelEngine.promotionCandidate(current = RunnerLevel.intermediate, runs = runs, now = now))
    }
}
