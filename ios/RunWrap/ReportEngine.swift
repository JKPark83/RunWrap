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

/// 문장 톤 3단계 (기획서 v0.7 §4 "문장 톤" 행) — 레벨 게이트의 문구 쪽 짝.
///
/// `ReportGate`가 "어떤 카드를 보여줄까"를 답한다면 이쪽은 "같은 카드를 어떤 말투로 쓸까"를
/// 답한다. 레벨을 문장마다 다시 분기하지 않고 한 번 톤으로 접어두는 이유는, 새 지표가
/// 늘어도 분기 축이 레벨 3개가 아니라 톤 3개로 고정되기 때문이다.
enum ReportVoice {
    /// 런린이 — 용어를 풀어쓴다. 지표 약어(ACWR·EF)를 문장에 노출하지 않는다.
    case plain
    /// 런잘알 — 표준. 지표명과 해석을 함께 쓴다.
    case standard
    /// 런친놈 — 압축·수치 중심. 값을 앞세우고 구간명을 붙인다.
    case compact

    init(level: RunnerLevel) {
        switch level {
        case .beginner: self = .plain
        case .intermediate: self = .standard
        case .advanced: self = .compact
        }
    }
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
    /// 문장 난이도 — 레벨 3단계가 `ReportVoice` 3단계로 접힌다 (기획서 v0.7 §4).
    /// 엔진은 순수 로직 유지: 프로필 저장소를 모르고 값 타입으로만 주입받는다.
    /// 기본값은 표준 톤(런잘알) — 레벨을 주지 않은 호출부가 용어를 숨기지도, 압축하지도 않게.
    var level: RunnerLevel = .intermediate

    /// 이 엔진이 쓸 문장 톤 — 레벨을 문장마다 다시 분기하지 않기 위한 단일 진입점
    private var voice: ReportVoice { ReportVoice(level: level) }

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
        let km = String(format: "%.1fkm", recent)
        let detail = String(format: "최근 7일 %.1fkm · 이전 7일 %.1fkm.", recent, previous)
        // 런린이는 수치를 문장에 싣지 않는다 (§4 "문장만") — 얼마나가 아니라 어떤 상태인지만 말한다
        switch change {
        case 10...:
            return Insight(kind: .weeklyDistanceChange, tone: .warning,
                           headline: headline(plain: "이번 주 갑자기 많이 늘었어요 — 몸이 따라오기 벅찰 수 있어요",
                                              standard: "지난주 대비 \(pct) — 과부하 구간입니다",
                                              compact: "주간 \(km) \(pct) · 과부하 구간"),
                           detail: detail + (voice == .plain ? " 거리는 한 주에 조금씩만 늘리는 게 안전해요."
                                                             : " 주간 증가 폭은 10% 이내가 안전합니다."))
        case ..<(-30):
            return Insight(kind: .weeklyDistanceChange, tone: .neutral,
                           headline: headline(plain: "이번 주는 많이 쉬어갔어요",
                                              standard: "지난주 대비 \(pct) — 훈련량이 크게 줄었습니다",
                                              compact: "주간 \(km) \(pct) · 감량 구간"),
                           detail: detail)
        default:
            return Insight(kind: .weeklyDistanceChange, tone: .positive,
                           headline: headline(plain: "딱 좋은 만큼 달리고 있어요",
                                              standard: "지난주 대비 \(pct) — 안정적인 훈련량입니다",
                                              compact: "주간 \(km) \(pct) · 안정 구간"),
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
        let detail = voice == .plain
            ? String(format: "최근 7일 %.1fkm를 지난 4주 주평균 %.1fkm와 비교한 값이에요.",
                     acute, chronic)
            : String(format: "최근 7일 %.1fkm ÷ 4주 주평균 %.1fkm. 적정 범위는 0.8~1.3.",
                     acute, chronic)
        // 런친놈은 "ACWR 1.0 · 적정 구간" 꼴 — 지표명·값·구간명을 한 줄에 압축한다 (§4 "수치+구간").
        // 런린이 문장에는 ACWR이라는 약어가 절대 나오지 않는다 — 용어 풀어쓰기.
        switch ratio {
        case 1.5...:
            return Insight(kind: .acwr, tone: .warning,
                           headline: headline(plain: "몸이 익숙한 양보다 훨씬 많이 달렸어요 — 부상을 조심할 때예요",
                                              standard: "이번 주 부하가 4주 평균의 \(value)배 — 부상 위험 구간입니다",
                                              compact: "ACWR \(value) · 부상 위험 구간"),
                           detail: detail)
        case 1.3..<1.5:
            return Insight(kind: .acwr, tone: .neutral,
                           headline: headline(plain: "평소보다 조금 많이 달리고 있어요",
                                              standard: "이번 주 부하가 4주 평균의 \(value)배 — 다소 높습니다",
                                              compact: "ACWR \(value) · 주의 구간"),
                           detail: detail)
        case 0.8..<1.3:
            return Insight(kind: .acwr, tone: .positive,
                           headline: headline(plain: "몸이 감당할 수 있는 만큼 달리고 있어요",
                                              standard: "이번 주 부하가 4주 평균의 \(value)배 — 적정 범위입니다",
                                              compact: "ACWR \(value) · 적정 구간"),
                           detail: detail)
        default:
            return Insight(kind: .acwr, tone: .neutral,
                           headline: headline(plain: "이번 주는 평소보다 많이 쉬었어요",
                                              standard: "이번 주 부하가 4주 평균의 \(value)배 — 회복 주간 수준입니다",
                                              compact: "ACWR \(value) · 회복 구간"),
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
        let detail = voice == .plain
            ? "심장이 뛰는 것에 비해 얼마나 빨리 달리는지를 2주 단위로 비교한 값이에요."
            : "심박당 속도(EF)의 최근 2주 평균을 직전 2주와 비교한 값입니다."
        // 런친놈은 "EF +5.2% · …" 꼴로 값을 앞세운다 — 해석보다 수치가 먼저다 (§4 "압축·수치 중심")
        switch change {
        case 3...:
            return Insight(kind: .heartRateEfficiency, tone: .positive,
                           headline: headline(plain: "같은 힘으로 더 빨리 달리고 있어요 — 체력이 늘고 있다는 뜻이에요",
                                              standard: "같은 심박으로 더 빨리 달리고 있습니다 (\(pct)) — 체력이 오르는 중",
                                              compact: "EF \(pct) · 체력 상승"),
                           detail: detail)
        case ..<(-3):
            return Insight(kind: .heartRateEfficiency, tone: .neutral,
                           headline: headline(plain: "요즘 페이스가 조금 처졌어요 — 피곤하거나 더위 탓일 수 있어요",
                                              standard: "심박 효율 \(pct) — 피로 누적이나 더위 영향일 수 있습니다",
                                              compact: "EF \(pct) · 피로·더위 영향 가능"),
                           detail: detail)
        default:
            return Insight(kind: .heartRateEfficiency, tone: .neutral,
                           headline: headline(plain: "체력이 지난 2주와 비슷하게 유지되고 있어요",
                                              standard: "심박 효율이 지난 2주와 비슷합니다 — 컨디션 유지 중",
                                              compact: "EF \(pct) · 컨디션 유지"),
                           detail: detail)
        }
    }

    /// EF = 분속(m/min) ÷ 평균 심박. 페이스나 심박이 없는 러닝은 표본에서 제외.
    private static func efficiency(_ run: RunSummary) -> Double? {
        guard let pace = run.paceSecPerKm, let hr = run.avgHeartRate, hr > 0 else { return nil }
        return (60_000 / pace) / hr
    }

    // MARK: - 공통

    /// 톤 3단계 중 하나를 고른다 — 지표마다 `switch voice`를 반복하지 않으려고 둔 헬퍼.
    /// 세 문장을 호출부에 나란히 두면 톤 차이를 눈으로 대조할 수 있다.
    private func headline(plain: String, standard: String, compact: String) -> String {
        switch voice {
        case .plain: plain
        case .standard: standard
        case .compact: compact
        }
    }

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
