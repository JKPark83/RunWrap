package com.jkpark.runwrap.engine

import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/// iOS ReportGateTests.swift의 세 @Suite를 @Nested로 옮긴다.
class ReportGateTests {

    /// 리포트 레벨 게이트 검증 (기획서 v0.7 §4) — 노출 매트릭스와 가드 우선순위.
    ///
    /// 핵심은 두 가지다: ① 매트릭스대로 레벨이 카드를 여닫는가 ②
    /// **미노출 가드(엔진 nil)가 레벨 게이트보다 항상 위인가**. ②는 게이트가 통과해도
    /// 엔진이 nil이면 그릴 게 없다는 뜻이라, 엔진 결과가 nil임을 함께 확인한다.
    ///
    /// (Android: 아래 6개는 아직 이식되지 않은 엔진에 기대므로 여기서 옮기지 않았다 — 해당 그룹 이식 때 함께 옮긴다.
    ///  `ReportEngine.weeklyReport`·`WeeklyReport.visibleCards`(ReportMetrics.swift, W2-B),
    ///  `FormTrend.compute`(FormEngine.swift, W2-D).
    ///  - sampleGuardBeatsLevelGateForAcwr — "미노출 가드 우선 — 런잘알이어도 기록이 4주 미만이면 ACWR이 나오지 않는다"
    ///  - sampleGuardBeatsLevelGateForEfficiency — "미노출 가드 우선 — 런친놈이어도 심박 표본이 부족하면 심박 효율이 나오지 않는다"
    ///  - sampleGuardBeatsLevelGateForForm — "미노출 가드 우선 — 주법 표본이 부족하면 레벨과 무관하게 nil"
    ///  - intermediateReceivesMetricsWhenSampleIsEnough — "표본이 충분하면 런잘알은 실제로 ACWR·EF를 받아본다 — 게이트만 막고 있지 않다"
    ///  - visibleCardsRespectLevelGate — "보이는 카드 — 런린이는 거리 판정 없이 ACWR만 있으면 빈 배열(표본 부족 안내), 런잘알은 ACWR"
    ///  - visibleCardsWithRichSample — "보이는 카드 — 표본이 충분하면 런린이는 거리만, 런잘알은 거리·ACWR·EF"
    ///  같은 이유로 그 테스트들만 쓰던 now·run·richRuns 헬퍼도 그때 옮긴다.)
    @Nested
    @DisplayName("리포트 레벨 게이트")
    inner class LevelGateTests {
        // MARK: 매트릭스 — 런린이

        @Test
        @DisplayName("런린이는 부하 비율·심박 효율·심폐 체력·주법을 보지 않는다")
        fun beginnerHidesAdvancedMetrics() {
            for (card in listOf(ReportCard.acwr, ReportCard.efficiency, ReportCard.vo2Max, ReportCard.form)) {
                assertEquals(false, ReportGate.shows(card, level = RunnerLevel.beginner))
            }
        }

        @Test
        @DisplayName("런린이도 거리·체력 배터리·훈련 가이드는 본다")
        fun beginnerKeepsBasicCards() {
            for (card in listOf(ReportCard.distance, ReportCard.battery, ReportCard.trainingGuide)) {
                assertTrue(ReportGate.shows(card, level = RunnerLevel.beginner))
            }
        }

        @Test
        @DisplayName("런린이의 주간 거리는 카드는 나오되 수치는 감춘다 — 문장만")
        fun beginnerDistanceIsSentenceOnly() {
            assertTrue(ReportGate.shows(ReportCard.distance, level = RunnerLevel.beginner))
            assertEquals(false, ReportGate.showsNumbers(ReportCard.distance, level = RunnerLevel.beginner))
        }

        // MARK: 매트릭스 — 런잘알 · 런친놈

        @Test
        @DisplayName("런잘알은 부하 비율·심박 효율·심폐 체력·주법을 전부 본다")
        fun intermediateSeesAdvancedMetrics() {
            for (card in listOf(ReportCard.acwr, ReportCard.efficiency, ReportCard.vo2Max, ReportCard.form)) {
                assertTrue(ReportGate.shows(card, level = RunnerLevel.intermediate))
            }
        }

        @Test
        @DisplayName("런친놈도 런잘알이 보는 카드를 전부 본다 — 승급으로 잃는 카드는 없다")
        fun advancedIsSupersetOfIntermediate() {
            for (card in ReportCard.entries.filter { ReportGate.shows(it, level = RunnerLevel.intermediate) }) {
                assertTrue(ReportGate.shows(card, level = RunnerLevel.advanced))
            }
        }

        @Test
        @DisplayName("런잘알·런친놈은 주간 거리 수치를 본다")
        fun upperLevelsSeeDistanceNumbers() {
            assertTrue(ReportGate.showsNumbers(ReportCard.distance, level = RunnerLevel.intermediate))
            assertTrue(ReportGate.showsNumbers(ReportCard.distance, level = RunnerLevel.advanced))
        }

        // MARK: 걷뛰 카드 — 런린이 전용

        @Test
        @DisplayName("걷뛰 카드는 런린이만 본다")
        fun walkRunIsBeginnerOnly() {
            assertTrue(ReportGate.shows(ReportCard.walkRun, level = RunnerLevel.beginner))
            assertEquals(false, ReportGate.shows(ReportCard.walkRun, level = RunnerLevel.intermediate))
            assertEquals(false, ReportGate.shows(ReportCard.walkRun, level = RunnerLevel.advanced))
        }
    }

    /// 걷뛰 처방 엔진 검증 — 주차 점증과 미노출 가드
    @Nested
    @DisplayName("걷뛰 처방 엔진")
    inner class WalkRunEngineTests {
        private val now = iso("2026-08-13T09:00:00Z")

        private fun start(weeksAgo: Double): Instant =
            instantSince1970(now.timeIntervalSince1970 - weeksAgo * 7 * 86_400)

        @Test
        @DisplayName("1주차는 걷기 4분 · 뛰기 1분 × 5세트 — 총 25분 고정")
        fun firstWeekIsGentle() {
            val plan = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 0.0),
                                                        weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
            assertEquals(1, plan.week)
            assertEquals(4.0, plan.walkMinutes)
            assertEquals(1.0, plan.runMinutes)
            assertEquals(5, plan.sets)   // 25분 ÷ (4+1)분
        }

        @Test
        @DisplayName("3주차는 시안과 같은 걷기 2분 · 뛰기 3분 × 5세트")
        fun thirdWeekMatchesDesign() {
            val plan = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 2.0),
                                                        weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
            assertEquals(3, plan.week)
            assertEquals("걷기 2분 · 뛰기 3분 × 5세트", plan.headline)
            assertEquals("걷뛰 3주차", plan.weekBadge)
        }

        @Test
        @DisplayName("주차가 오를수록 뛰기 시간이 늘고 걷기 시간은 줄어든다")
        fun runTimeIncreasesMonotonically() {
            var previousRun = 0.0
            var previousWalk = Double.POSITIVE_INFINITY
            // 1 → 8주차 순으로 훑는다 (weeksAgo가 클수록 주차가 크다)
            for (weeksAgo in 0 until 8) {
                val plan = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = weeksAgo.toDouble()),
                                                            weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
                assertEquals(weeksAgo + 1, plan.week)
                if (weeksAgo > 0) {
                    assertTrue(plan.runMinutes >= previousRun)
                    assertTrue(plan.walkMinutes <= previousWalk)
                }
                previousRun = plan.runMinutes
                previousWalk = plan.walkMinutes
            }
        }

        @Test
        @DisplayName("8주차를 넘어가도 마지막 단계를 유지한다 — 더 밀어붙이지 않는다")
        fun ladderPlateausAfterLastStep() {
            val eighth = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 7.0),
                                                          weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
            val twentieth = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 19.0),
                                                             weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
            assertEquals(20, twentieth.week)
            assertEquals(eighth.walkMinutes, twentieth.walkMinutes)
            assertEquals(eighth.runMinutes, twentieth.runMinutes)
        }

        @Test
        @DisplayName("이번 주 횟수는 1km 이상만 센다 — 0.5km 3회는 0회 (홈 칩과 동일 기준)")
        fun subKmRunsDoNotCountAsDone() {
            // now(목요일) 기준 같은 ISO 주 안의 러닝 3개: 0.5km 두 개 + 1.2km 하나 → 1회
            val runs = listOf(0.0 to 0.5, 1.0 to 0.5, 2.0 to 1.2).map { (daysAgo, km) ->
                RunSummary(id = UUID.randomUUID().toString().uppercase(),
                           start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                           durationSec = km * 360, distanceMeters = km * 1000, avgHeartRate = 150.0)
            }
            val plan = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 1.0),
                                                        weeklyGoal = 3, runs = runs, now = now, zone = testZone))
            assertEquals(1, plan.doneThisWeek)
        }

        @Test
        @DisplayName("미노출 가드 — 사이클 시작 시각을 모르면 처방을 내지 않는다")
        fun missingCycleStartYieldsNil() {
            assertNull(WalkRunEngine.plan(cycleStartedAt = null, weeklyGoal = 3, runs = emptyList(), now = now, zone = testZone))
        }

        @Test
        @DisplayName("미노출 가드 — 주간 목표가 0이면 진행률 분모가 없어 처방을 내지 않는다")
        fun zeroWeeklyGoalYieldsNil() {
            assertNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 2.0), weeklyGoal = 0,
                                          runs = emptyList(), now = now, zone = testZone))
        }

        @Test
        @DisplayName("이번 달력 주의 러닝만 진행 횟수로 센다")
        fun countsOnlyThisCalendarWeek() {
            // now = 2026-08-13(목) → ISO 주는 8/10(월)~8/16(일)
            val thisWeek = RunSummary(id = UUID.randomUUID().toString().uppercase(), start = now.minusSeconds(86_400),
                                      durationSec = 1_800.0, distanceMeters = 5_000.0, avgHeartRate = null)
            val lastWeek = RunSummary(id = UUID.randomUUID().toString().uppercase(), start = now.minusSeconds(8L * 86_400),
                                      durationSec = 1_800.0, distanceMeters = 5_000.0, avgHeartRate = null)
            val plan = assertNotNull(WalkRunEngine.plan(cycleStartedAt = start(weeksAgo = 2.0),
                                                        weeklyGoal = 3,
                                                        runs = listOf(thisWeek, lastWeek), now = now, zone = testZone))
            assertEquals(1, plan.doneThisWeek)
            assertEquals("이번 주 1 / 3회 했어요", plan.progressLine)
        }
    }

    /// 문장 톤 3단계 검증 (기획서 §4 "문장 톤" 행) — 레벨 게이트의 문구 쪽 짝
    @Nested
    @DisplayName("리포트 문장 톤 3단계")
    inner class ReportVoiceTests {
        private val now = iso("2026-08-13T09:00:00Z")

        private fun run(daysAgo: Double, km: Double, hr: Double? = 150.0): RunSummary =
            RunSummary(id = UUID.randomUUID().toString().uppercase(),
                       start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                       durationSec = km * 6 * 60,
                       distanceMeters = km * 1000,
                       avgHeartRate = hr)

        private fun headline(kind: Insight.Kind, level: RunnerLevel,
                             runs: List<RunSummary>): String? =
            ReportEngine(now = now, level = level).insights(runs)
                .firstOrNull { it.kind == kind }?.headline

        @Test
        @DisplayName("레벨은 문장 톤 3단계로 매핑된다")
        fun levelMapsToVoice() {
            assertEquals(ReportVoice.plain, ReportVoice(level = RunnerLevel.beginner))
            assertEquals(ReportVoice.standard, ReportVoice(level = RunnerLevel.intermediate))
            assertEquals(ReportVoice.compact, ReportVoice(level = RunnerLevel.advanced))
        }

        @Test
        @DisplayName("주간 거리 문장이 레벨 3단계로 전부 갈린다")
        fun weeklyDistanceHeadlineDiffersByLevel() {
            // 최근 7일 10.5km · 이전 7일 10km → +5%, 안정 구간
            val runs = listOf(run(daysAgo = 1.0, km = 10.5), run(daysAgo = 8.0, km = 10.0))
            val plain = assertNotNull(headline(Insight.Kind.weeklyDistanceChange, level = RunnerLevel.beginner, runs = runs))
            val standard = assertNotNull(headline(Insight.Kind.weeklyDistanceChange, level = RunnerLevel.intermediate, runs = runs))
            val compact = assertNotNull(headline(Insight.Kind.weeklyDistanceChange, level = RunnerLevel.advanced, runs = runs))
            assertNotEquals(standard, plain)
            assertNotEquals(compact, standard)
            assertNotEquals(compact, plain)
            // 런친놈은 수치 중심 — 지표명과 값이 함께 나온다
            assertTrue(compact.contains("10.5"))
        }

        @Test
        @DisplayName("런친놈의 ACWR 문장에는 구간명이 함께 붙는다 — §4 '수치+구간'")
        fun advancedAcwrShowsBand() {
            // 4주 이상 이력 + 주 10km 유지 → ACWR 1.0(적정 구간)
            // 이슈 #49: 가드 28일이라 0...28로 늘린다. 0일(=now 정각) 기록은 반개구간이라 제외 —
            // acute 1~7일 17.5, chronic 1~28일 70/4=17.5 → 1.0
            val runs = (0..28).map { run(daysAgo = it.toDouble(), km = 2.5) }
            val compact = assertNotNull(headline(Insight.Kind.acwr, level = RunnerLevel.advanced, runs = runs))
            assertTrue(compact.startsWith("ACWR "))
            assertTrue(compact.contains("적정 구간"))
        }

        @Test
        @DisplayName("런린이의 ACWR 문장에는 지표 약어(ACWR)가 나오지 않는다 — 용어 풀어쓰기")
        fun beginnerAcwrAvoidsJargon() {
            // 이슈 #49: 가드 28일 — 위 테스트와 같은 0...28 이력 (ACWR 1.0)
            val runs = (0..28).map { run(daysAgo = it.toDouble(), km = 2.5) }
            val plain = assertNotNull(headline(Insight.Kind.acwr, level = RunnerLevel.beginner, runs = runs))
            assertFalse(plain.contains("ACWR"))
        }

        @Test
        @DisplayName("런친놈의 심박 효율 문장은 EF 값을 앞세운다")
        fun advancedEfficiencyLeadsWithEF() {
            // 최근 2주는 심박이 낮아 EF가 오른다 — 각 창에 표본 3개 이상
            val recent = (0 until 5).map { run(daysAgo = it.toDouble() * 2 + 1, km = 5.0, hr = 140.0) }
            val previous = (0 until 5).map { run(daysAgo = it.toDouble() * 2 + 15, km = 5.0, hr = 155.0) }
            val compact = assertNotNull(headline(Insight.Kind.heartRateEfficiency, level = RunnerLevel.advanced,
                                                 runs = recent + previous))
            assertTrue(compact.startsWith("EF "))
        }
    }
}
