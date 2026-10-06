package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/// 홈 탭 브리핑 엔진 (기획서 v0.7 §6) — Foundation만 import하는 순수 로직.
///
/// 홈 화면 텍스트는 브리핑 1~2문장이 전부다. 지표 카드 나열은 리포트 탭의 일이므로
/// **엔진 결과 중 최우선 1건만** 골라 런미새 보이스로 한 문장(또는 두 문장)을 만든다.
/// 화면은 여기서 나온 문자열만 그린다 — 데이터 가공을 화면에 두지 않기 위해서다.
///
/// 우선순위 (기획서 §6 표):
/// 1. 과부하·부상 경고  — ACWR/주간 거리 톤이 overload·caution
/// 2. 컨디션 변화       — 체력 배터리 톤, 심박 효율 변화
/// 3. 꾸준함 칭찬       — 이번 주 러닝 횟수·주간 목표·시무룩(공백) 상태
/// 4. 날씨 한 줄        — 더위 경고 등 (호출부가 문장을 넘긴다)
///
/// **미노출 가드가 우선순위보다 위다.** 재료가 부족하면 어느 단계에서도 문장을 만들지 않고
/// 최종적으로 nil을 반환한다 — "틀린 인사이트는 없느니만 못하다."
object HomeBriefingEngine {
    /// 홈 브리핑 한 덩어리(1~2문장)를 만든다. 재료가 없으면 nil (첫 실행 안내로 대체).
    ///
    /// - Parameters:
    ///   - runs: 전체 러닝 이력 (오래된 것 포함 가능 — 창은 이 안에서 자른다)
    ///   - growth: 성장 상태 스냅샷 — 시무룩(7일 공백) 판정에만 쓴다
    ///   - report: 주간 리포트 (거리·ACWR·효율 카드). 없으면 부하·컨디션 문장을 건너뛴다
    ///   - battery: 체력 배터리 리포트. 표본이 부족하면 nil로 들어온다
    ///   - weeklyGoal: 주간 목표 러닝 횟수 (0이면 목표 미설정)
    ///   - weatherLine: 날씨 한 줄 (호출부의 날씨 엔진 결과). 최하위 우선순위
    ///   - now: 판정 기준 시각 (결정론을 위한 주입)
    ///   - zone: (Android) iOS `Calendar(.iso8601)` + `.current` 대신 주입받는 시간대
    fun briefing(runs: List<RunSummary>,
                 growth: GrowthState,
                 report: WeeklyReport?,
                 battery: BatteryReport?,
                 weeklyGoal: Int,
                 weatherLine: String? = null,
                 now: Instant,
                 zone: ZoneId): String? {
        // 미노출 가드 — 러닝이 한 번도 없으면 브리핑 자체가 성립하지 않는다 (시안 1g)
        if (runs.isEmpty()) return null

        // ① 과부하·부상 경고 — 가장 먼저, 가장 강하게
        overloadLine(report = report)?.let { return it }

        // ② 컨디션 변화 (배터리 → 심박 효율 순)
        conditionLine(battery = battery, report = report)?.let { return it }

        // ③ 꾸준함 — 공백(시무룩)이 먼저, 그다음 칭찬
        consistencyLine(runs = runs, growth = growth,
                        report = report, weeklyGoal = weeklyGoal, now = now, zone = zone)?.let {
            return it
        }

        // ④ 날씨 한 줄 — 위 재료가 전부 부족할 때만
        return weatherLine
    }

    // MARK: - ① 과부하·부상 경고

    /// 부하 지표가 위험·주의 톤이면 그 문장을 만든다.
    /// ACWR을 주간 거리보다 앞세운다 — 부상 위험을 직접 가리키는 지표라서다 (Gabbett 2016).
    private fun overloadLine(report: WeeklyReport?): String? {
        if (report == null) return null

        val acwr = report.acwr
        if (acwr != null && acwr.tone == RRTone.overload) {
            val ratio = fmt(acwr.ratio, 1)
            return "평소 감당하던 양의 ${ratio}배를 달렸어요. 이번 주는 한 번 쉬어 가는 게 이깁니다."
        }
        val distance = report.distance
        if (distance != null && distance.tone == RRTone.overload) {
            val pct = fmt(distance.changePct, 0, plus = true) + "%"
            val over = fmt(max(0.0, distance.overKm), 1)
            return "지난주보다 $pct 늘었어요 — 안전선을 ${over}km 넘겼습니다. 다음 주는 조금 접어 두세요."
        }
        // ACWR caution은 높은 쪽(1.3~1.5)과 낮은 쪽(<0.8) 두 갈래다 — 문장을 나눈다
        if (acwr != null && acwr.tone == RRTone.caution) {
            val ratio = fmt(acwr.ratio, 1)
            if (acwr.ratio >= 1.3) {
                return "평소보다 ${ratio}배 달리고 있어요. 아직 괜찮지만, 한 칸만 낮춰도 좋습니다."
            }
            return "평소보다 ${ratio}배로 쉬어 가는 중이에요. 다시 시작할 땐 가볍게 한 번부터."
        }
        return null
    }

    // MARK: - ② 컨디션 변화

    /// 체력 배터리가 낮거나(방전 경고) 심박 효율이 뚜렷하게 바뀌었을 때의 문장.
    /// 배터리는 자체 헤드라인 문장을 이미 갖고 있어 그대로 쓴다 — 두 벌 관리하지 않는다.
    private fun conditionLine(battery: BatteryReport?, report: WeeklyReport?): String? {
        if (battery != null && (battery.tone == RRTone.overload || battery.tone == RRTone.caution)) {
            return "체력 배터리 ${battery.level}% — ${battery.headline}"
        }
        val efficiency = report?.efficiency
        if (efficiency != null && efficiency.tone == RRTone.improving &&
            efficiency.changePct >= 2) {
            val pct = fmt(efficiency.changePct, 1, plus = true) + "%"
            return "같은 심박으로 더 빨리 달리고 있어요 — 심박 효율 $pct. 몸이 조용히 좋아지는 중입니다."
        }
        if (battery != null && battery.tone == RRTone.improving && battery.level >= 75) {
            return "체력 배터리 ${battery.level}% — ${battery.headline}"
        }
        return null
    }

    // MARK: - ③ 꾸준함

    /// 공백(시무룩)·이번 주 진행 상황을 문장으로. 시안 1f의 두 문장이 이 갈래의 기준 톤이다.
    private fun consistencyLine(runs: List<RunSummary>, growth: GrowthState,
                                report: WeeklyReport?, weeklyGoal: Int,
                                now: Instant, zone: ZoneId): String? {
        // 시무룩 — 시안 1f(brfS) verbatim 기준. 경과일에 따라 앞머리만 바꾼다
        val days = growth.daysSinceLastRun
        if (growth.isSulky && days != null) {
            return "${gapPhrase(days = days)} 새가 살짝 시무룩하지만, 한 번만 나가면 바로 풀립니다."
        }

        val weekCount = weekRunCount(runs = runs, now = now, zone = zone)
        if (weekCount <= 0) return null  // 이번 주 기록이 없으면 칭찬할 게 없다

        // 시안 1f(brfN) verbatim 기준 문장 — "이번 주 N번째 러닝. 지난주보다 X% 늘었어요 — …"
        // 여기까지 왔다는 건 ①에서 과부하로 걸리지 않았다는 뜻이라, 증가폭은 10% 룰 안이다.
        // 그래서 "딱 좋은 증가폭"이라고 말해도 안전하다.
        val distance = report?.distance
        if (distance != null && distance.previous7Km >= 3) {
            val change = distance.changePct
            val pct = fmt(abs(change), 0) + "%"
            if (change >= 3) {
                return "이번 주 ${weekCount}번째 러닝. 지난주보다 $pct 늘었어요 — 딱 좋은 증가폭입니다. 정상은 아니지만 멋있습니다."
            }
            if (change <= -20) {
                return "이번 주 ${weekCount}번째 러닝. 지난주보다 $pct 줄었어요 — 쉬어 가는 것도 훈련입니다."
            }
            return "이번 주 ${weekCount}번째 러닝. 지난주와 비슷한 리듬이에요 — 이 꾸준함이 제일 어렵습니다."
        }

        // 지난주 비교가 불가능(표본 부족)하면 주간 목표 진행으로만 말한다
        if (weeklyGoal > 0) {
            if (weekCount >= weeklyGoal) {
                return "이번 주 목표 ${weeklyGoal}회, 벌써 채우셨어요. 새가 아주 흡족해합니다."
            }
            return "이번 주 ${weekCount}번째 러닝. 목표 ${weeklyGoal}회까지 ${weeklyGoal - weekCount}번 남았어요."
        }
        return "이번 주 ${weekCount}번째 러닝. 잘 쌓이고 있어요."
    }

    /// 공백 기간을 한국어 표현으로 — 시안 1f는 7일 공백을 "일주일 만이에요."로 쓴다
    private fun gapPhrase(days: Int): String = when {
        days < 10 -> "일주일 만이에요."
        days in 10 until 21 -> "2주 가까이 쉬었네요."
        days in 21 until 45 -> "한 달 가까이 못 뵀어요."
        else -> "오랜만이에요."
    }

    /// 이번 달력 주(ISO 8601, 월요일 시작) 러닝 횟수 — 주간 목표와 같은 1km 이상 기준
    private fun weekRunCount(runs: List<RunSummary>, now: Instant, zone: ZoneId): Int {
        val weekStart = isoWeekStart(now, zone).atStartOfDay(zone).toInstant()
        return runs.count {
            it.start >= weekStart && it.start <= now && GrowthEngine.countsAsCompletedRun(it)
        }
    }
}
