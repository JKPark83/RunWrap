package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// 홈 브리핑 엔진 검증 — 우선순위 사다리(①과부하 ②컨디션 ③꾸준함 ④날씨)와 미노출 가드.
/// now = 2026-08-13T09:00:00Z = KST 목 18:00 — ISO 주 시작은 08-10(월).
/// iOS `HomeBriefingEngineTests` 이식 (13개 전부).
class HomeBriefingEngineTest {
    private val now = Instant.parse("2026-08-13T09:00:00Z")

    private fun run(daysAgo: Double, km: Double, paceSec: Double = 360.0) = RunSummary(
        id = "run-$daysAgo-$km",
        start = now.minusMillis((daysAgo * 86_400_000).roundToLong()),
        durationSec = km * paceSec,
        distanceMeters = km * 1_000,
        avgHeartRate = 150.0,
    )

    private fun growth(runs: List<RunSummary>, weeklyGoal: Int = 3) =
        GrowthEngine.state(runs, Instant.EPOCH, 1, weeklyGoal, now)

    private fun report(runs: List<RunSummary>) = ReportEngine(now = now).weeklyReport(runs)

    private fun briefing(
        runs: List<RunSummary>,
        report: WeeklyReport? = null,
        battery: BatteryReport? = null,
        weeklyGoal: Int = 3,
        weatherLine: String? = null,
    ) = HomeBriefingEngine.briefing(
        runs, growth(runs, weeklyGoal), report, battery, weeklyGoal, weatherLine, now)

    // MARK: - 미노출 가드·④ 날씨

    @Test
    fun `미노출 가드 - 러닝이 한 번도 없으면 날씨 한 줄이 있어도 null`() {
        assertNull(briefing(emptyList(), weatherLine = "오늘은 덥습니다"))
    }

    @Test
    fun `날씨 한 줄 - 위 재료가 전부 부족하면 날씨 문장이 나온다`() {
        // 지난 주말 1회 — 이번 주 0회·시무룩 전·리포트 카드 전부 가드에 걸린다
        val runs = listOf(run(4.0, 5.0))
        assertEquals("오전 9시부터 체감 31°C를 넘어요.",
            briefing(runs, report = report(runs),
                     weatherLine = "오전 9시부터 체감 31°C를 넘어요."))
    }

    @Test
    fun `날씨 한 줄도 없으면 null - 억지로 문장을 만들지 않는다`() {
        val runs = listOf(run(4.0, 5.0))
        assertNull(briefing(runs, report = report(runs)))
    }

    @Test
    fun `날씨 한 줄 - 이번 주 기록이 없고 시무룩 전이면 날씨로 넘어간다`() {
        val runs = listOf(run(5.0, 5.0), run(6.0, 5.0))
        assertFalse(growth(runs).isSulky)
        assertEquals("날씨 한 줄",
            briefing(runs, report = report(runs), weatherLine = "날씨 한 줄"))
    }

    // MARK: - ① 과부하

    @Test
    fun `과부하 - ACWR 위험이면 쉬어 가라는 문장이 최우선이다`() {
        // 5주간 주 1회 5km 리듬 → 이번 주 갑자기 15km × 3회
        val runs = (1..5).map { run(it * 7 + 1.0, 5.0) } +
            listOf(run(0.0, 15.0), run(1.0, 15.0), run(2.0, 15.0))
        val r = report(runs)
        assertTrue(r.acwr?.tone == RRTone.OVERLOAD || r.distance?.tone == RRTone.OVERLOAD)

        val line = briefing(runs, report = r)!!
        assertFalse(line.contains("멋있습니다"))   // 칭찬으로 새면 안 된다
        assertTrue(line.contains("쉬어") || line.contains("접어"))
    }

    @Test
    fun `과부하 - 주간 거리 급증이면 안전선 초과 문장`() {
        // 지난주 10km → 이번 주 15km (+50%), 안전선 11km를 4km 초과. ACWR은 표본 부족
        val runs = listOf(run(8.0, 10.0), run(0.5, 5.0), run(1.0, 5.0), run(3.0, 5.0))
        val r = report(runs)
        assertEquals(RRTone.OVERLOAD, r.distance?.tone)
        assertNull(r.acwr)
        assertEquals("지난주보다 +50% 늘었어요 — 안전선을 4.0km 넘겼습니다. 다음 주는 조금 접어 두세요.",
            briefing(runs, report = r))
    }

    // MARK: - ② 컨디션

    @Test
    fun `컨디션 - 배터리 주의면 배터리 헤드라인을 그대로 잇는다`() {
        val runs = listOf(run(0.0, 5.0), run(2.0, 5.0), run(8.0, 5.0))
        val battery = BatteryReport(level = 32, tone = RRTone.CAUTION, statusLabel = "주의",
                                    headline = "회복이 덜 됐어요.", factors = emptyList())
        assertEquals("체력 배터리 32% — 회복이 덜 됐어요.", briefing(runs, battery = battery))
    }

    @Test
    fun `컨디션 - 좋음이라도 75 미만이면 배터리를 앞세우지 않는다`() {
        val runs = listOf(run(0.0, 5.0), run(2.0, 5.0))
        val battery = BatteryReport(level = 62, tone = RRTone.IMPROVING, statusLabel = "양호",
                                    headline = "괜찮아요.", factors = emptyList())
        val line = briefing(runs, battery = battery)!!
        assertFalse(line.contains("체력 배터리"))
        assertTrue(line.contains("2번째 러닝"))
    }

    // MARK: - ③ 꾸준함

    @Test
    fun `시무룩 - 7일 공백이면 일주일 만이에요 문장`() {
        val runs = listOf(run(7.0, 5.2))
        assertTrue(growth(runs).isSulky)
        assertEquals("일주일 만이에요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.",
            briefing(runs, report = report(runs)))
    }

    @Test
    fun `시무룩 - 한 달 공백이면 앞머리가 바뀐다`() {
        val runs = listOf(run(30.0, 5.0))
        assertEquals("한 달 가까이 못 뵀어요. 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다.",
            briefing(runs, report = report(runs)))
    }

    @Test
    fun `꾸준함 - 주간 목표를 채웠으면 흡족 문장`() {
        val runs = listOf(run(0.0, 5.0), run(1.0, 5.0), run(3.0, 5.0))
        assertEquals("이번 주 목표 3회, 벌써 채우셨어요. 새가 아주 흡족해합니다.", briefing(runs))
    }

    @Test
    fun `꾸준함 - 목표 미달이면 남은 횟수를 알려준다`() {
        val runs = listOf(run(0.0, 5.0), run(2.0, 5.0))
        assertEquals("이번 주 2번째 러닝. 목표 3회까지 1번 남았어요.", briefing(runs))
    }

    @Test
    fun `꾸준함 - 지난주 대비 증가가 10퍼센트 안이면 칭찬 문장`() {
        // 지난주 10km → 이번 주 3.6km × 3 = 10.8km (+8%)
        val runs = listOf(run(8.0, 10.0), run(0.5, 3.6), run(1.0, 3.6), run(3.0, 3.6))
        val r = report(runs)
        assertTrue(r.distance?.tone != RRTone.OVERLOAD)
        assertEquals("이번 주 3번째 러닝. 지난주보다 8% 늘었어요 — 딱 좋은 증가폭입니다. 정상은 아니지만 멋있습니다.",
            briefing(runs, report = r))
    }
}
