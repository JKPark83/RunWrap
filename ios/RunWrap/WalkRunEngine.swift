import Foundation

/// 걷뛰(걷기-뛰기) 처방 엔진 — 런린이 전용 입문 카드 (기획서 v0.7 §4 "더하는 차별화").
///
/// **왜 필요한가.** 레벨 게이트는 런린이에게서 ACWR·EF·VO₂max·주법을 걷어낸다.
/// 그 자리를 비워 두면 "볼 게 없는 화면"이 되므로, 대신 지금 당장 따라 할 수 있는
/// 한 세션의 처방을 놓는다. 숨기기만 하는 차별화가 아니라 더하는 차별화다 (§4).
///
/// **왜 주차로 점증하는가.** 걷뛰는 성과가 아니라 경과로 올린다 — 이번 주에 몇 번 뛰었는지가
/// 아니라 시작한 지 몇 주가 지났는지로 강도를 정한다. 못 나간 주가 있어도 처방이 뒤로
/// 밀리지 않아야 "성장은 되돌리지 않는다"(§5)와 어긋나지 않는다.
enum WalkRunEngine {
    /// 한 세션 처방 — 카드가 그대로 그릴 수 있는 완성된 값만 담는다
    struct Plan {
        /// 사이클 시작 이후 경과 주차 (1부터)
        let week: Int
        let walkMinutes: Double
        let runMinutes: Double
        let sets: Int
        /// 이번 달력 주 러닝 횟수
        let doneThisWeek: Int
        let weeklyGoal: Int

        /// "걷기 2분 · 뛰기 3분 × 5세트".
        /// 분 표기(정수/반 분)는 카드의 metric과 같아야 해서 `Format.walkRunMinutes`를 공유한다
        var headline: String {
            "걷기 \(Format.walkRunMinutes(walkMinutes))분 · 뛰기 \(Format.walkRunMinutes(runMinutes))분 × \(sets)세트"
        }

        /// "걷뛰 3주차"
        var weekBadge: String { "걷뛰 \(week)주차" }

        /// "이번 주 1 / 3회 했어요"
        var progressLine: String { "이번 주 \(doneThisWeek) / \(weeklyGoal)회 했어요" }

        /// 한 세션 총 소요 시간(분) — 사다리가 25분 고정이라 항상 25다
        var totalMinutes: Double { (walkMinutes + runMinutes) * Double(sets) }
    }

    /// 8주 사다리 — 총 25분을 유지한 채 걷기를 뛰기로 바꿔 나간다.
    /// 한 세트 길이(걷기+뛰기)를 5분으로 고정해 세트 수를 5로 유지하면
    /// "몇 세트인지"가 매주 바뀌지 않아 따라 하기 쉽다.
    /// 8주차(뛰기 5분 연속 × 5세트)에서 멈추고 더 밀어붙이지 않는다 —
    /// 그다음은 걷뛰가 아니라 연속 러닝의 영역이고, 승급 판정이 가져간다 (§3).
    private static let ladder: [(walk: Double, run: Double)] = [
        (4, 1),    // 1주차
        (3, 2),    // 2주차
        (2, 3),    // 3주차 — 시안 기준값
        (2, 3),    // 4주차 (같은 강도로 한 주 더 굳힌다)
        (1.5, 3.5),// 5주차
        (1, 4),    // 6주차
        (0.5, 4.5),// 7주차
        (0, 5),    // 8주차 — 연속 5분 × 5세트
    ]

    /// 걷뛰 처방을 만든다. 표본이 아니라 **경과 주차**가 입력이라
    /// 러닝 기록이 하나도 없어도 처방은 나온다 (그게 입문 카드의 목적이다).
    ///
    /// 미노출 가드:
    /// - `cycleStartedAt`이 없으면 몇 주차인지 셀 수 없다 → nil
    /// - `weeklyGoal`이 0 이하면 진행률의 분모가 없다 → nil
    static func plan(cycleStartedAt: Date?, weeklyGoal: Int,
                     runs: [RunSummary], now: Date) -> Plan? {
        guard let cycleStartedAt, weeklyGoal > 0 else { return nil }

        let elapsed = now.timeIntervalSince(cycleStartedAt)
        // 시작 당일도 1주차다 — 음수 경과(미래 시작)도 1주차로 접는다
        let week = max(1, Int(elapsed / (7 * 86_400)) + 1)
        // 사다리를 넘어서면 마지막 단계에서 평평해진다
        let step = ladder[min(week, ladder.count) - 1]

        var calendar = Calendar(identifier: .iso8601)  // 월요일 시작
        calendar.timeZone = .current
        let thisWeek = calendar.dateInterval(of: .weekOfYear, for: now)
        let done = thisWeek.map { interval in
            runs.filter { interval.contains($0.start) }.count
        } ?? 0

        return Plan(week: week,
                    walkMinutes: step.walk,
                    runMinutes: step.run,
                    sets: 5,
                    doneThisWeek: done,
                    weeklyGoal: weeklyGoal)
    }
}
