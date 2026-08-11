import Foundation

/// 파생 지표 하나의 계산 결과 — 수치 나열이 아니라 해석된 문장을 담는다(기획서 §4.2 원칙)
struct Insight: Identifiable, Equatable {
    enum Kind: CaseIterable {
        case weeklyDistanceChange  // 주간 거리 증가율 (10% 룰)
        case acwr                  // 급성:만성 부하비 (부상 위험)
        case heartRateEfficiency   // 심박 효율 (컨디션 프록시)
    }

    enum Tone {
        case positive, neutral, warning
    }

    let kind: Kind
    let tone: Tone
    let headline: String
    let detail: String

    var id: Kind { kind }
}

/// 리포트 엔진 (MVP 2단계) — 러닝 요약 목록에서 파생 지표를 계산한다.
///
/// 산식 출처(기획서 §9: 출처 명시 + 참고용 고지):
/// - 주간 거리 증가율: 10% 룰. 달력 주가 아닌 롤링 7일 창을 쓴다 —
///   달력 주는 주 초반 리포트가 "급감"으로 오독된다.
/// - ACWR: 급성 부하(최근 7일 거리) ÷ 만성 부하(최근 28일의 주 평균 거리).
///   적정 0.8~1.3, 1.5 초과는 부상 위험 신호 (Gabbett, 2016).
/// - 심박 효율(EF): 분속(m/min) ÷ 평균 심박 (TrainingPeaks Efficiency Factor).
///   최근 2주 평균을 직전 2주와 비교 — 상승이면 같은 심박으로 더 빨리 달린다는 뜻.
///
/// 표본이 부족해 비율이 과장될 상황(기준 주가 거의 비어 있음, 기록 3주 미만 등)에는
/// 해당 지표를 아예 내지 않는다 — 틀린 인사이트는 없느니만 못하다.
struct ReportEngine {
    var now = Date()

    func insights(from runs: [RunSummary]) -> [Insight] {
        [weeklyDistanceChange(runs), acwr(runs), heartRateEfficiency(runs)]
            .compactMap { $0 }
    }

    // MARK: - 주간 거리 증가율 (10% 룰)

    private func weeklyDistanceChange(_ runs: [RunSummary]) -> Insight? {
        let recent = totalKm(runs, fromDaysAgo: 7, toDaysAgo: 0)
        let previous = totalKm(runs, fromDaysAgo: 14, toDaysAgo: 7)
        guard previous >= 3 else { return nil }  // 기준 주가 3km 미만이면 증가율이 과장된다
        let change = (recent - previous) / previous * 100
        let pct = String(format: "%+.0f%%", change)
        let detail = String(format: "최근 7일 %.1fkm · 이전 7일 %.1fkm.", recent, previous)
        switch change {
        case 10...:
            return Insight(kind: .weeklyDistanceChange, tone: .warning,
                           headline: "지난주 대비 \(pct) — 과부하 구간입니다",
                           detail: detail + " 주간 증가 폭은 10% 이내가 안전합니다.")
        case ..<(-30):
            return Insight(kind: .weeklyDistanceChange, tone: .neutral,
                           headline: "지난주 대비 \(pct) — 훈련량이 크게 줄었습니다",
                           detail: detail)
        default:
            return Insight(kind: .weeklyDistanceChange, tone: .positive,
                           headline: "지난주 대비 \(pct) — 안정적인 훈련량입니다",
                           detail: detail)
        }
    }

    // MARK: - ACWR (급성:만성 부하비)

    private func acwr(_ runs: [RunSummary]) -> Insight? {
        // 기록이 3주 미만이면 만성 부하(분모)가 작아 비율이 과장된다
        guard let oldest = runs.map(\.start).min(),
              oldest <= date(daysAgo: 21) else { return nil }
        let acute = totalKm(runs, fromDaysAgo: 7, toDaysAgo: 0)
        let chronic = totalKm(runs, fromDaysAgo: 28, toDaysAgo: 0) / 4
        guard chronic >= 3 else { return nil }  // 주 평균 3km 미만이면 지표가 무의미하다
        let ratio = acute / chronic
        let value = String(format: "%.1f", ratio)
        let detail = String(format: "최근 7일 %.1fkm ÷ 4주 주평균 %.1fkm. 적정 범위는 0.8~1.3.",
                            acute, chronic)
        switch ratio {
        case 1.5...:
            return Insight(kind: .acwr, tone: .warning,
                           headline: "이번 주 부하가 4주 평균의 \(value)배 — 부상 위험 구간입니다",
                           detail: detail)
        case 1.3..<1.5:
            return Insight(kind: .acwr, tone: .neutral,
                           headline: "이번 주 부하가 4주 평균의 \(value)배 — 다소 높습니다",
                           detail: detail)
        case 0.8..<1.3:
            return Insight(kind: .acwr, tone: .positive,
                           headline: "이번 주 부하가 4주 평균의 \(value)배 — 적정 범위입니다",
                           detail: detail)
        default:
            return Insight(kind: .acwr, tone: .neutral,
                           headline: "이번 주 부하가 4주 평균의 \(value)배 — 회복 주간 수준입니다",
                           detail: detail)
        }
    }

    // MARK: - 심박 효율 (컨디션 프록시)

    private func heartRateEfficiency(_ runs: [RunSummary]) -> Insight? {
        let recent = runs.filter { $0.start >= date(daysAgo: 14) }
            .compactMap(Self.efficiency)
        let previous = runs.filter { $0.start >= date(daysAgo: 28) && $0.start < date(daysAgo: 14) }
            .compactMap(Self.efficiency)
        guard recent.count >= 3, previous.count >= 3 else { return nil }  // 표본이 적으면 잡음이 크다
        let change = (average(recent) - average(previous)) / average(previous) * 100
        let pct = String(format: "%+.1f%%", change)
        let detail = "심박당 속도(EF)의 최근 2주 평균을 직전 2주와 비교한 값입니다."
        switch change {
        case 3...:
            return Insight(kind: .heartRateEfficiency, tone: .positive,
                           headline: "같은 심박으로 더 빨리 달리고 있습니다 (\(pct)) — 체력이 오르는 중",
                           detail: detail)
        case ..<(-3):
            return Insight(kind: .heartRateEfficiency, tone: .neutral,
                           headline: "심박 효율 \(pct) — 피로 누적이나 더위 영향일 수 있습니다",
                           detail: detail)
        default:
            return Insight(kind: .heartRateEfficiency, tone: .neutral,
                           headline: "심박 효율이 지난 2주와 비슷합니다 — 컨디션 유지 중",
                           detail: detail)
        }
    }

    /// EF = 분속(m/min) ÷ 평균 심박. 페이스나 심박이 없는 러닝은 표본에서 제외.
    private static func efficiency(_ run: RunSummary) -> Double? {
        guard let pace = run.paceSecPerKm, let hr = run.avgHeartRate, hr > 0 else { return nil }
        return (60_000 / pace) / hr
    }

    // MARK: - 공통

    private func date(daysAgo: Int) -> Date {
        now.addingTimeInterval(TimeInterval(-daysAgo * 86_400))
    }

    /// [now-fromDaysAgo, now-toDaysAgo) 창에 시작된 러닝의 거리 합 (km)
    private func totalKm(_ runs: [RunSummary], fromDaysAgo: Int, toDaysAgo: Int) -> Double {
        let from = date(daysAgo: fromDaysAgo)
        let to = date(daysAgo: toDaysAgo)
        return runs.filter { $0.start >= from && $0.start < to }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }

    private func average(_ values: [Double]) -> Double {
        values.reduce(0, +) / Double(values.count)
    }
}
