package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 홈 브리핑 엔진(HomeBriefingEngine) 검증 — 우선순위 사다리와 미노출 가드.
/// now는 2026-08-13(목) — ISO 주 기준 이번 주 시작은 2026-08-10(월)이라
/// "이번 주"에 들어가는 러닝은 daysAgo 0~3이다.
class HomeBriefingEngineTests {
    private val now = iso("2026-08-13T09:00:00Z")
    /// Swift `Date.distantPast`(0001-01-01T00:00:00Z)
    private val distantPast = iso("0001-01-01T00:00:00Z")

    private fun run(daysAgo: Double, km: Double, paceSec: Double = 360.0): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = km * paceSec,
                   distanceMeters = km * 1000,
                   avgHeartRate = 150.0)

    /// 러닝 이력에서 실제 GrowthState를 뽑아 쓴다 — 시무룩 판정을 손으로 흉내 내지 않기 위해
    private fun growth(runs: List<RunSummary>, weeklyGoal: Int = 3): GrowthState =
        GrowthEngine.state(runs = runs, cycleStartedAt = distantPast,
                           maxStage = 1, weeklyGoal = weeklyGoal, now = now, zone = testZone)

    private fun report(runs: List<RunSummary>): WeeklyReport =
        ReportEngine(now = now, level = RunnerLevel.intermediate).weeklyReport(from = runs, zone = testZone)

    // MARK: - 미노출 가드

    @Test
    @DisplayName("미노출 가드 — 러닝 기록이 하나도 없으면 브리핑을 내지 않는다")
    fun noRunsNoBriefing() {
        val line = HomeBriefingEngine.briefing(runs = emptyList(), growth = growth(emptyList()), report = null,
                                               battery = null, weeklyGoal = 3,
                                               weatherLine = "오늘은 덥습니다", now = now, zone = testZone)
        assertNull(line)
    }

    @Test
    @DisplayName("미노출 가드 — 재료가 전부 부족하면 날씨 한 줄로 떨어진다")
    fun fallsBackToWeather() {
        // 이번 주에 아직 안 뛰었고(4일 전 1회), 지난주 비교 표본도 없고, 배터리도 없다
        val runs = listOf(run(daysAgo = 4.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = null, weeklyGoal = 3,
                                               weatherLine = "오전 9시부터 체감 31°C를 넘어요.", now = now, zone = testZone)
        assertEquals("오전 9시부터 체감 31°C를 넘어요.", line)
    }

    @Test
    @DisplayName("미노출 가드 — 재료도 날씨도 없으면 nil")
    fun noMaterialNoLine() {
        val runs = listOf(run(daysAgo = 4.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertNull(line)
    }

    // MARK: - ① 과부하·부상 경고가 최우선

    @Test
    @DisplayName("우선순위 ① — 부하 과부하면 꾸준함 칭찬 대신 경고 문장이 나온다")
    fun overloadWinsOverPraise() {
        // 4주 이상 이력 + 최근 7일 급증 → ACWR overload 구간을 만든다
        val runs = mutableListOf<RunSummary>()
        for (week in 1..5) {
            runs.add(run(daysAgo = week.toDouble() * 7 + 1, km = 5.0))
        }
        // 이번 주에 몰아서 뛴다 (급성 부하 폭증)
        runs += listOf(run(daysAgo = 0.0, km = 15.0), run(daysAgo = 1.0, km = 15.0), run(daysAgo = 2.0, km = 15.0))

        val r = report(runs)
        // 전제 확인 — 이 시나리오가 실제로 과부하 톤이어야 테스트가 의미를 가진다
        assertTrue(r.acwr?.tone == RRTone.overload || r.distance?.tone == RRTone.overload)

        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = r,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertNotNull(line)
        // 칭찬 문장("멋있습니다")이 아니라 감량 권고 문장이어야 한다
        assertEquals(false, line.contains("멋있습니다"))
        assertTrue(line.contains("쉬어") || line.contains("접어"))
    }

    @Test
    @DisplayName("우선순위 ① — ACWR이 낮은 쪽 caution(<0.8)이면 감량이 아니라 복귀 독려 문장")
    fun lowAcwrCautionLine() {
        // 30·26·19·12일 전 10km, 2일 전 5km.
        // 급성 = 5km, 만성 = 28일 창(26·19·12·2일 전) 35km ÷ 4 = 8.75 → 5 ÷ 8.75 ≈ 0.57 → caution(낮은 쪽)
        // 거리 카드: 이전 7일(12일 전) 10km → 최근 5km = −50% → caution이지만 overload는 아님
        val runs = listOf(run(daysAgo = 30.0, km = 10.0), run(daysAgo = 26.0, km = 10.0), run(daysAgo = 19.0, km = 10.0),
                          run(daysAgo = 12.0, km = 10.0), run(daysAgo = 2.0, km = 5.0))
        val r = report(runs)
        assertEquals(RRTone.caution, r.acwr?.tone)            // 전제 확인
        assertTrue((r.acwr?.ratio ?: 1.0) < 0.8)
        assertTrue(r.distance?.tone != RRTone.overload)

        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = r,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("평소보다 0.6배로 쉬어 가는 중이에요. 다시 시작할 땐 가볍게 한 번부터.", line)
    }

    // MARK: - ② 컨디션 변화

    @Test
    @DisplayName("우선순위 ② — 부하가 정상이고 배터리가 주의면 배터리 문장이 나온다")
    fun batteryBeatsConsistency() {
        val runs = listOf(run(daysAgo = 0.0, km = 5.0), run(daysAgo = 2.0, km = 5.0), run(daysAgo = 8.0, km = 5.0))
        val battery = BatteryReport(level = 32, tone = RRTone.caution, statusLabel = "주의",
                                    headline = "회복이 덜 됐어요.", factors = emptyList())
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = battery, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("체력 배터리 32% — 회복이 덜 됐어요.", line)
    }

    @Test
    @DisplayName("배터리가 좋아도 75% 미만이면 굳이 말하지 않는다 — 꾸준함으로 넘어간다")
    fun mildBatteryIsSilent() {
        val runs = listOf(run(daysAgo = 0.0, km = 5.0), run(daysAgo = 2.0, km = 5.0))
        val battery = BatteryReport(level = 62, tone = RRTone.improving, statusLabel = "양호",
                                    headline = "괜찮아요.", factors = emptyList())
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = battery, weeklyGoal = 3, now = now, zone = testZone)
        // 배터리 문장이 아니라 주간 목표 진행 문장이 나온다
        assertEquals(false, line?.contains("체력 배터리"))
        assertEquals(true, line?.contains("2번째 러닝"))
    }

    // MARK: - ③ 꾸준함

    @Test
    @DisplayName("시무룩 — 7일 이상 공백이면 시안 1f(brfS) 문장을 그대로 낸다")
    fun sulkyLine() {
        val runs = listOf(run(daysAgo = 7.0, km = 5.2))
        val state = growth(runs)
        assertTrue(state.isSulky)  // 전제 확인

        val line = HomeBriefingEngine.briefing(runs = runs, growth = state, report = report(runs),
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("일주일 만이에요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.", line)
    }

    @Test
    @DisplayName("공백이 길어지면 앞머리만 바뀐다 — 뒷문장은 유지")
    fun longerGapPhrase() {
        val runs = listOf(run(daysAgo = 30.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = report(runs),
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("한 달 가까이 못 뵀어요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.", line)
    }

    @Test
    @DisplayName("주간 목표만 있을 때 — 목표를 채우면 칭찬 문장")
    fun weeklyGoalAchieved() {
        // 이번 주(daysAgo 0~3) 3회 — report 없이 목표 진행만으로 판단
        val runs = listOf(run(daysAgo = 0.0, km = 5.0), run(daysAgo = 1.0, km = 5.0), run(daysAgo = 3.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("이번 주 목표 3회, 벌써 채우셨어요. 새가 아주 흡족해합니다.", line)
    }

    @Test
    @DisplayName("주간 목표만 있을 때 — 미달이면 남은 횟수를 알려준다")
    fun weeklyGoalRemaining() {
        val runs = listOf(run(daysAgo = 0.0, km = 5.0), run(daysAgo = 2.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("이번 주 2번째 러닝. 목표 3회까지 1번 남았어요.", line)
    }

    @Test
    @DisplayName("주간 목표 횟수는 1km 이상만 센다 — 0.5km 3회로는 목표 달성 문장을 내지 않는다")
    fun subKmRunsDoNotCountThisWeek() {
        // 이번 주 0.5km 3회 + 5km 1회 → 1km 기준 1회
        val runs = listOf(run(daysAgo = 0.5, km = 0.5), run(daysAgo = 1.0, km = 0.5), run(daysAgo = 2.0, km = 0.5),
                          run(daysAgo = 3.0, km = 5.0))
        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = null,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("이번 주 1번째 러닝. 목표 3회까지 2번 남았어요.", line)
    }

    @Test
    @DisplayName("지난주 대비 증가 — 10% 룰 안(+8%)이면 시안 1f(brfN)의 '딱 좋은 증가폭' 문장")
    fun growthPraiseLine() {
        // 이전 7일(daysAgo 7~14) 10km, 최근 7일(daysAgo 0~7) 10.8km → +8% (10% 룰 안이라 과부하 아님)
        // ※ 최근 창은 [6일 전 자정, now) 반열림이라 daysAgo: 0(= now 정각)은 제외된다 (이슈 #75).
        //   경계에 걸치지 않도록 0.5일 전으로 둔다.
        val runs = listOf(run(daysAgo = 8.0, km = 10.0),
                          run(daysAgo = 0.5, km = 3.6), run(daysAgo = 1.0, km = 3.6), run(daysAgo = 3.0, km = 3.6))
        val r = report(runs)
        assertTrue(r.distance?.tone != RRTone.overload)  // 전제: 과부하 문장으로 새지 않는다

        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = r,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        assertEquals("이번 주 3번째 러닝. 지난주보다 8% 늘었어요 — 딱 좋은 증가폭입니다. 정상은 아니지만 멋있습니다.", line)
    }

    @Test
    @DisplayName("우선순위 ① — 증가폭이 10% 룰을 넘으면 칭찬 대신 감량 권고가 나온다")
    fun distanceOverloadLine() {
        // 이전 7일 10km, 최근 7일 15km → +50% (10% 룰 초과)
        // ※ 최근 창은 [6일 전 자정, now) 반열림 — daysAgo: 0은 제외되므로 0.5일 전에 둔다.
        val runs = listOf(run(daysAgo = 8.0, km = 10.0),
                          run(daysAgo = 0.5, km = 5.0), run(daysAgo = 1.0, km = 5.0), run(daysAgo = 3.0, km = 5.0))
        val r = report(runs)
        assertEquals(RRTone.overload, r.distance?.tone)  // 전제 확인
        assertNull(r.acwr)                               // 이력 3주 미만이라 ACWR은 계산되지 않는다

        val line = HomeBriefingEngine.briefing(runs = runs, growth = growth(runs), report = r,
                                               battery = null, weeklyGoal = 3, now = now, zone = testZone)
        // 안전선(이전 7일 × 1.1 = 11.0km)을 4.0km 넘겼다
        assertEquals("지난주보다 +50% 늘었어요 — 안전선을 4.0km 넘겼습니다. 다음 주는 조금 접어 두세요.", line)
    }

    @Test
    @DisplayName("이번 주 기록이 없으면 꾸준함 칭찬을 만들지 않는다")
    fun noRunThisWeekNoPraise() {
        // 5일 전 = 2026-08-08(토) → 지난주. 시무룩(7일)에는 못 미친다
        val runs = listOf(run(daysAgo = 5.0, km = 5.0), run(daysAgo = 6.0, km = 5.0))
        val state = growth(runs)
        assertFalse(state.isSulky)  // 전제 확인

        val line = HomeBriefingEngine.briefing(runs = runs, growth = state, report = report(runs),
                                               battery = null, weeklyGoal = 3,
                                               weatherLine = "날씨 한 줄", now = now, zone = testZone)
        assertEquals("날씨 한 줄", line)
    }
}
