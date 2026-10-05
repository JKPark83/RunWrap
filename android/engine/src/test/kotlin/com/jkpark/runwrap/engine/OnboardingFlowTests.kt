package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/// iOS OnboardingFlowTests.swift의 세 @Suite를 @Nested로 옮긴다.
class OnboardingFlowTests {

    /// 온보딩 카드 설문 분기 검증 — 기획서 §2 분기 트리(문항 수 5~9)와 스킵 규칙.
    /// 문항 시퀀스는 답변 상태로부터 계산하는 순수 함수라 화면 없이 검증할 수 있다.
    @Nested
    @DisplayName("온보딩 설문 분기")
    inner class BranchTests {
        /// 미지정 필드는 nil로 두는 기본 답변 빌더 — 각 테스트가 필요한 필드만 채운다
        private fun answers(q1: OnboardingAnswers.Q1,
                            q2a: OnboardingAnswers.Q2A? = null,
                            q2: OnboardingAnswers.Q2Longest? = null,
                            q3: OnboardingAnswers.Q3Record? = null,
                            q4: OnboardingAnswers.Q4Monthly? = null,
                            q5: OnboardingAnswers.Q5Frequency? = null,
                            q6: OnboardingAnswers.Q6Race? = null,
                            q7: RaceDistance? = null,
                            q8: Int? = null,
                            q9: List<RunPurpose> = emptyList()): OnboardingAnswers =
            OnboardingAnswers(q1Experience = q1, q2aActivity = q2a, q2Longest = q2, q3Record = q3,
                              q4Monthly = q4, q5Frequency = q5, q6Race = q6,
                              q7Target = q7, q8GoalSec = q8, q9Purposes = q9)

        @Test
        @DisplayName("무경험 경로는 5문항 — Q1·Q2a·Q7·Q8·Q9")
        fun noviceHasFiveSteps() {
            // 목표 거리를 골랐으므로 Q8이 붙는다 → Q1, Q2a, Q7, Q8, Q9 = 5문항 (기획서 §2)
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.novice,
                                                          q2a = OnboardingAnswers.Q2A.walking,
                                                          q7 = RaceDistance.fiveK))
            assertEquals(listOf(OnboardingStep.experience, OnboardingStep.activity, OnboardingStep.target,
                                OnboardingStep.goalTime, OnboardingStep.purposes), steps)
        }

        @Test
        @DisplayName("무경험자가 목표 거리를 안 정하면 Q8을 건너뛰어 4문항")
        fun noviceWithoutTargetSkipsGoalTime() {
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.novice,
                                                          q2a = OnboardingAnswers.Q2A.sedentary))
            assertEquals(listOf(OnboardingStep.experience, OnboardingStep.activity, OnboardingStep.target,
                                OnboardingStep.purposes), steps)
        }

        @Test
        @DisplayName("유경험 최대 경로는 9문항 — Q3·Q8이 모두 붙는 경우")
        fun experiencedFullPathHasNineSteps() {
            // 5~10km는 Q3(10km 기록) 대상이고 목표 거리도 골랐다 → 9문항 (기획서 §2 "최대 9문항")
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.experienced,
                                                          q2 = OnboardingAnswers.Q2Longest.fiveToTen,
                                                          q7 = RaceDistance.half))
            assertEquals(9, steps.size)
            assertEquals(listOf(OnboardingStep.experience, OnboardingStep.longest, OnboardingStep.record,
                                OnboardingStep.monthly, OnboardingStep.frequency, OnboardingStep.raceExperience,
                                OnboardingStep.target, OnboardingStep.goalTime, OnboardingStep.purposes), steps)
        }

        @Test
        @DisplayName("Q2가 5km 미만이면 Q3를 건너뛴다 — 10km 기록을 물을 근거가 없다")
        fun under5SkipsRecord() {
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.experienced,
                                                          q2 = OnboardingAnswers.Q2Longest.under5,
                                                          q7 = RaceDistance.fiveK))
            assertFalse(OnboardingStep.record in steps)
            assertEquals(8, steps.size)
        }

        @Test
        @DisplayName("Q2가 하프~풀이면 Q3를 건너뛴다 — 하프 완주는 그 자체로 최소 런잘알")
        fun halfToFullSkipsRecord() {
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.experienced,
                                                          q2 = OnboardingAnswers.Q2Longest.halfToFull,
                                                          q7 = RaceDistance.full))
            assertFalse(OnboardingStep.record in steps)
        }

        @Test
        @DisplayName("풀코스 완주자는 Q3를 묻는다 — 런친놈 조건① 판정에 풀 기록이 필요하다")
        fun fullFinisherKeepsRecord() {
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.experienced,
                                                          q2 = OnboardingAnswers.Q2Longest.fullFinisher,
                                                          q7 = RaceDistance.full))
            assertTrue(OnboardingStep.record in steps)
        }

        @Test
        @DisplayName("Q7이 아직 없어요면 Q8을 건너뛴다")
        fun noTargetSkipsGoalTime() {
            val steps = OnboardingFlowModel.steps(answers(q1 = OnboardingAnswers.Q1.experienced,
                                                          q2 = OnboardingAnswers.Q2Longest.tenToHalf))
            assertFalse(OnboardingStep.goalTime in steps)
            assertEquals(8, steps.size)
        }

        @Test
        @DisplayName("모든 분기에서 문항 수는 5~9 사이 — 기획서 §2 한도")
        fun stepCountStaysInRange() {
            val combinations = mutableListOf<OnboardingAnswers>()
            for (q2 in OnboardingAnswers.Q2Longest.entries) {
                for (q7 in RaceDistance.entries + listOf(null)) {
                    combinations.add(answers(q1 = OnboardingAnswers.Q1.experienced, q2 = q2, q7 = q7))
                }
            }
            for (q7 in RaceDistance.entries + listOf(null)) {
                combinations.add(answers(q1 = OnboardingAnswers.Q1.novice, q2a = OnboardingAnswers.Q2A.walking, q7 = q7))
            }
            for (combination in combinations) {
                val count = OnboardingFlowModel.steps(combination).size
                assertTrue(count >= 4 && count <= 9)
            }
        }

        @Test
        @DisplayName("목표 기록 프리셋 — 종목별 개수와 값 (기획서 §2)")
        fun presetsMatchSpec() {
            assertEquals(listOf(30 * 60, 25 * 60), GoalPreset.presets(RaceDistance.fiveK).map { it.seconds })
            assertEquals(listOf(60 * 60, 50 * 60), GoalPreset.presets(RaceDistance.tenK).map { it.seconds })
            assertEquals(listOf(7_200, 6_600), GoalPreset.presets(RaceDistance.half).map { it.seconds })
            // 풀은 시안 1c의 칩 4개 — "완주"(5:00)를 포함해 라벨이 겹치지 않는다
            val full = GoalPreset.presets(RaceDistance.full)
            assertEquals(listOf("완주", "sub-5", "sub-4:30", "sub-4"), full.map { it.label })
            assertEquals(5 * 3_600, full[0].seconds)
            assertEquals(full.size, full.map { it.seconds }.toSet().size)
        }
    }

    /// 온보딩 저장 — 재진단이 성장 사이클을 보존하는지 (이슈 #44).
    /// 설정의 "다시 진단받기"가 사이클을 새로 열어 XP가 0이 되던 회귀를 막는다.
    /// now = 2026-09-29T09:00:00Z 고정, 저장소는 테스트마다 새로 만든다.
    @Nested
    @DisplayName("온보딩 저장 — 재진단 사이클 보존")
    inner class PersistTests {
        private val now = iso("2026-09-29T09:00:00Z")
        /// 재진단 전부터 키우던 사이클 — 8/1 시작, 4단계(fledgling), 고정 식별자, 사이클 목표 풀 4:00:00
        private val cycleStartedAt = iso("2026-08-01T00:00:00Z")
        private val onboardedAt = iso("2026-07-01T00:00:00Z")
        private val cycleID = "AAAAAAAA-0000-0000-0000-000000000001"

        /// 기존 사이클 값을 미리 넣어 둔 격리 저장소
        private fun seededDefaults(): KeyValueStore {
            val defaults = InMemoryKeyValueStore()
            defaults.set(GrowthKey.cycleStartedAt, cycleStartedAt.timeIntervalSince1970)
            defaults.set(GrowthKey.maxStage, GrowthStage.fledgling.rawValue)
            defaults.set(GrowthKey.cycleID, cycleID)
            defaults.set(GrowthKey.cycleGoal, RaceDistance.full.rawValue)
            defaults.set(GrowthKey.cycleGoalSec, 4 * 3_600)
            defaults.set(ProfileKey.onboardedAt, onboardedAt.timeIntervalSince1970)
            return defaults
        }

        /// 재진단 답 — 주 4회 이상(Q5 fourPlus → 주간 목표 4회), 하프 목표 1:45:00(6_300초)
        private fun model(): OnboardingFlowModel {
            val model = OnboardingFlowModel()
            model.prefillIfNeeded(OnboardingAnswers(
                q1Experience = OnboardingAnswers.Q1.experienced, q2aActivity = null,
                q2Longest = OnboardingAnswers.Q2Longest.halfToFull, q3Record = null,
                q4Monthly = OnboardingAnswers.Q4Monthly.hundredTo200,
                q5Frequency = OnboardingAnswers.Q5Frequency.fourPlus,
                q6Race = OnboardingAnswers.Q6Race.finished,
                q7Target = RaceDistance.half, q8GoalSec = 6_300, q9Purposes = listOf(RunPurpose.record)))
            return model
        }

        @Test
        @DisplayName("재진단 저장 — 사이클 시작·최고 단계·사이클 식별자·온보딩 시각을 보존하고 설문 답만 갱신한다")
        fun rediagnosisKeepsCycle() {
            val defaults = seededDefaults()
            val model = model()

            model.persist(isRediagnosis = true, now = now, zone = testZone, defaults = defaults)

            // 사이클 키 3종 + 온보딩 시각: 미리 넣은 값 그대로
            assertEquals(cycleStartedAt.timeIntervalSince1970, defaults.double(GrowthKey.cycleStartedAt))
            assertEquals(GrowthStage.fledgling.rawValue, defaults.int(GrowthKey.maxStage))
            assertEquals(cycleID, defaults.string(GrowthKey.cycleID))
            assertEquals(onboardedAt.timeIntervalSince1970, defaults.double(ProfileKey.onboardedAt))
            // 사이클 목표(이슈 #110): 재진단의 하프 목표로 바뀌지 않고 풀 4:00:00 그대로 — 새 종류는 다음 사이클부터
            assertEquals(RaceDistance.full.rawValue, defaults.string(GrowthKey.cycleGoal))
            assertEquals(4 * 3_600, defaults.int(GrowthKey.cycleGoalSec))
            // 설문 답: 레벨은 LevelEngine 판정, 주간 목표는 Q5 fourPlus → 4회, 대회 목표는 Q7·Q8
            assertEquals(LevelEngine.decide(model.answers).rawValue, defaults.string(ProfileKey.levelV2))
            assertEquals(4, defaults.int(ProfileKey.weeklyGoal))
            assertEquals(RaceDistance.half.rawValue, defaults.string(ProfileKey.raceGoal))
            assertEquals(6_300, defaults.int(ProfileKey.raceGoalSec))
        }

        @Test
        @DisplayName("재진단 저장 — 주간 목표가 바뀌면 변경 시각과 이전 목표를 이력에 남긴다 (이슈 #108)")
        fun rediagnosisRecordsWeeklyGoalChange() {
            val defaults = seededDefaults()
            defaults.set(ProfileKey.weeklyGoal, 2)  // 재진단 전 주 2회

            model().persist(isRediagnosis = true, now = now, zone = testZone, defaults = defaults)  // Q5 fourPlus → 4회

            assertEquals(4, defaults.int(ProfileKey.weeklyGoal))
            assertEquals(listOf(WeeklyGoalChange(at = now, before = 2)), WeeklyGoalChangeLog.load(defaults))
        }

        @Test
        @DisplayName("재진단 저장 — #108의 옛 기록(다른 주)이 있으면 이관한 뒤 지우지 않고 새 변경을 뒤에 붙인다 (이슈 #116)")
        fun rediagnosisAppendsToMigratedHistory() {
            val defaults = seededDefaults()
            defaults.set(ProfileKey.weeklyGoal, 2)  // 재진단 전 주 2회 — 8/12에 3→2로 바꾼 옛 기록
            val earlier = iso("2026-08-12T00:00:00Z")
            defaults.set(WeeklyGoalChangeLog.legacyChangedAtKey, earlier.timeIntervalSince1970)
            defaults.set(WeeklyGoalChangeLog.legacyBeforeKey, 3)

            model().persist(isRediagnosis = true, now = now, zone = testZone, defaults = defaults)  // Q5 fourPlus → 4회

            assertEquals(listOf(WeeklyGoalChange(at = earlier, before = 3),
                                WeeklyGoalChange(at = now, before = 2)),
                         WeeklyGoalChangeLog.load(defaults))
            assertFalse(defaults.contains(WeeklyGoalChangeLog.legacyChangedAtKey))
        }

        @Test
        @DisplayName("재진단 저장 — 주간 목표가 그대로면 변경 기록을 남기지 않는다")
        fun rediagnosisSameGoalLeavesNoRecord() {
            val defaults = seededDefaults()
            defaults.set(ProfileKey.weeklyGoal, 4)

            model().persist(isRediagnosis = true, now = now, zone = testZone, defaults = defaults)

            assertTrue(WeeklyGoalChangeLog.load(defaults).isEmpty())
        }

        @Test
        @DisplayName("첫 온보딩 저장 — 새 사이클을 연다: 시작=지금, 단계=알, 새 식별자, 온보딩 시각=지금, 사이클 목표=Q7·Q8")
        fun firstOnboardingOpensNewCycle() {
            val defaults = seededDefaults()

            model().persist(isRediagnosis = false, now = now, zone = testZone, defaults = defaults)

            assertEquals(now.timeIntervalSince1970, defaults.double(GrowthKey.cycleStartedAt))
            assertEquals(GrowthStage.egg.rawValue, defaults.int(GrowthKey.maxStage))
            val newID = assertNotNull(defaults.string(GrowthKey.cycleID))
            assertNotEquals(cycleID, newID)
            assertNotNull(runCatching { UUID.fromString(newID) }.getOrNull())
            assertEquals(now.timeIntervalSince1970, defaults.double(ProfileKey.onboardedAt))
            // 사이클 목표(이슈 #110): Q7 하프·Q8 1:45:00(6_300초)으로 고정
            assertEquals(RaceDistance.half.rawValue, defaults.string(GrowthKey.cycleGoal))
            assertEquals(6_300, defaults.int(GrowthKey.cycleGoalSec))
        }
    }

    /// 온보딩 원답 파일 정리 (이슈 #156) — 원답은 더 이상 저장하지 않고, 이전 버전이 남긴 파일은 지운다.
    @Nested
    @DisplayName("온보딩 원답 파일 정리")
    inner class AnswersStoreTests {
        @Test
        @DisplayName("이전 버전이 남긴 원답 파일을 지우고, 파일이 없어도 조용히 넘어간다")
        fun removesLegacyFile() {
            val dir = Files.createTempDirectory("onboarding-answers-test-${UUID.randomUUID()}").toFile()
            try {
                val file = File(dir, OnboardingAnswersStore.filename)
                file.writeText("{}")
                assertTrue(file.exists())

                OnboardingAnswersStore.removeLegacyFile(dir)
                assertFalse(file.exists())

                // 두 번째 호출 — 파일이 없어도 오류 없이 끝나고 디렉터리도 그대로다
                OnboardingAnswersStore.removeLegacyFile(dir)
                assertTrue(dir.exists())
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
