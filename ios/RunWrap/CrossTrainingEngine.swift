import Foundation

/// 비러닝 운동(자전거·근력·수영 등) 주간 요약 엔진 — 러닝 리포트에 얹는 "보조 문장" 재료
/// (기획서 "HealthKit 미활용 데이터 활용 제안 A3"). 크로스 트레이닝 시간은 러닝 거리 부하
/// (ACWR)에는 절대 섞지 않는다 — 어디까지나 보조 정보다. 순수 로직: Foundation만 쓰고
/// now를 주입받아 결정론적이다.

/// 비러닝 운동 1회 세션 — HealthStore가 HKWorkout에서 변환해 넘긴다
struct CrossTraining: Equatable {
    enum Kind: Equatable, CaseIterable {
        case cycling, strength, swimming, hiking, walking, yoga, hiit, other

        var label: String {
            switch self {
            case .cycling: return "자전거"
            case .strength: return "근력"
            case .swimming: return "수영"
            case .hiking: return "등산"
            case .walking: return "걷기"
            case .yoga: return "요가"
            case .hiit: return "인터벌 트레이닝"
            case .other: return "기타 운동"
            }
        }
    }

    let start: Date
    let durationSec: Double
    let kind: Kind
    let kcal: Double?
}

enum CrossTrainingEngine {
    struct Summary: Equatable {
        struct Item: Equatable {
            let label: String   // Kind.label
            let minutes: Int
            let count: Int      // 세션 수
        }
        let sessionCount: Int
        let totalMinutes: Int
        let breakdown: [Item]   // 시간 많은 순 정렬
        let headline: String    // 런미새 한 줄 (사실 먼저, 위트는 뒤)
        let detail: String      // 보조 설명 — ACWR 비반영을 반드시 명시
    }

    /// [now-7일, now] 창의 크로스 세션을 종목별로 묶어 주간 보조 문장을 만든다.
    /// - 걷기(walking)는 30분 미만 세션을 일상 걷기 소음으로 보고 집계에서 뺀다.
    /// - 창 안에 세션이 하나도 없거나, 걷기 가드를 거친 뒤 총 시간이 20분 미만이면
    ///   한 줄 얹을 가치가 없다고 보고 nil을 반환한다.
    static func weekly(cross: [CrossTraining], runs: [RunSummary], now: Date) -> Summary? {
        let from = now.addingTimeInterval(-7 * 86_400)
        let sessions = cross
            .filter { $0.start >= from && $0.start <= now }
            .filter { $0.kind != .walking || $0.durationSec >= 30 * 60 }
        guard !sessions.isEmpty else { return nil }

        let totalMinutes = Int((sessions.reduce(0.0) { $0 + $1.durationSec } / 60).rounded())
        guard totalMinutes >= 20 else { return nil }

        let breakdown = Self.breakdown(sessions)
        let thisWeekKm = totalKm(runs, from: now.addingTimeInterval(-7 * 86_400), to: now)
        let lastWeekKm = totalKm(runs, from: now.addingTimeInterval(-14 * 86_400),
                                 to: now.addingTimeInterval(-7 * 86_400))

        return Summary(sessionCount: sessions.count,
                       totalMinutes: totalMinutes,
                       breakdown: breakdown,
                       headline: headline(breakdown: breakdown, totalMinutes: totalMinutes,
                                         thisWeekKm: thisWeekKm, lastWeekKm: lastWeekKm),
                       detail: "크로스 트레이닝 시간은 러닝 거리 부하(ACWR)에는 넣지 않아요. "
                             + "몸의 피로는 따로 쌓이니 회복은 같이 챙기세요.")
    }

    // MARK: - 종목별 집계

    /// 종목별로 묶어 시간 합·세션 수를 구하고 시간 많은 순으로 정렬한다.
    /// 동률이면 Kind.allCases 순서(먼저 등장하는 종목)를 유지한다.
    private static func breakdown(_ sessions: [CrossTraining]) -> [Summary.Item] {
        CrossTraining.Kind.allCases
            .map { kind -> Summary.Item? in
                let matched = sessions.filter { $0.kind == kind }
                guard !matched.isEmpty else { return nil }
                let minutes = Int((matched.reduce(0.0) { $0 + $1.durationSec } / 60).rounded())
                return Summary.Item(label: kind.label, minutes: minutes, count: matched.count)
            }
            .compactMap { $0 }
            .sorted { $0.minutes > $1.minutes }
    }

    // MARK: - headline (결정론적 — 난수 금지)

    private static func headline(breakdown: [Summary.Item], totalMinutes: Int,
                                 thisWeekKm: Double, lastWeekKm: Double) -> String {
        let top = breakdown[0]  // sessions가 비어있지 않으므로 breakdown도 비어있지 않다
        let topTime = timeLabel(minutes: top.minutes)
        let totalTime = timeLabel(minutes: totalMinutes)

        // 러닝이 지난주 대비 20% 이상 줄었을 때만 "부하는 이어가셨네요" 계열을 쓴다.
        // lastWeekKm이 0이면 비교 기준 자체가 없어 "줄었다"고 말할 수 없다.
        let decreased = lastWeekKm > 0 && thisWeekKm <= lastWeekKm * 0.8
        if decreased {
            return "이번 주 러닝은 줄었지만 \(top.label) \(topTime) — 다리는 쉬어도 심박은 출근했네요."
        }

        let kmLabel = String(format: "%.0f", thisWeekKm)
        return "러닝 \(kmLabel)km에 크로스 트레이닝 \(totalTime)까지, "
             + "그중 \(top.label)이 \(topTime)로 제일 많았어요 — 부지런히도 움직이셨어요."
    }

    /// 90 → "1시간 30분", 60 → "1시간", 45 → "45분"
    private static func timeLabel(minutes: Int) -> String {
        let h = minutes / 60
        let m = minutes % 60
        if h == 0 { return "\(m)분" }
        if m == 0 { return "\(h)시간" }
        return "\(h)시간 \(m)분"
    }

    // MARK: - 러닝 주간 비교

    /// [from, to) 창에 시작된 러닝의 거리 합 (km) — ReportEngine.totalKm과 같은 반열린 구간
    private static func totalKm(_ runs: [RunSummary], from: Date, to: Date) -> Double {
        runs.filter { $0.start >= from && $0.start < to }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }
}
