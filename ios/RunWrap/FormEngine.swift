import Foundation

/// 주법 리포트 엔진 (기획서 §4.8, 계획서 M4) — 러닝 다이내믹스를 본인 기준선과 비교해
/// 조언 문장을 만든다. 순수 로직: Foundation만 쓰고 now를 주입받아 결정론적이다.
///
/// 조언은 절대 권장치가 아니라 "최근 28일 내 평균 대비"로만 낸다 — 러너마다 체형·주법이
/// 달라 절대값 비교는 오답이 많다 (Garmin Running Dynamics 가이드). 절대 밴드는
/// 측정 오류(헐거운 착용 등) 감지에만 쓴다. 실내 러닝은 다이내믹스가 기록되지 않아
/// (애플 공식) 스냅샷 수집 단계에서 걸러진다.

/// 세션 1회의 주법 스냅샷 — 판정에 필요한 값만 추린다
struct FormSnapshot {
    let id: UUID
    let start: Date
    let cadenceSpm: Double?
    let verticalOscillationCm: Double?
    let groundContactMs: Double?
}

/// 최근 28일 야외 러닝의 지표별 평균 — 조언의 비교 기준선
struct FormBaseline {
    let cadenceSpm: Double?
    let verticalOscillationCm: Double?
    let groundContactMs: Double?
    let sessionCount: Int
}

/// 조언 한 건 — 문장은 엔진이 만들고 화면은 그대로 그린다
struct FormAdvice: Equatable {
    enum Kind: Equatable {
        case sensor       // 측정 오류 가능성 (절대 밴드 이탈)
        case cadence      // 보폭 조언
        case oscillation  // 진폭 조언
        case contact      // 접촉 시간 조언
    }
    let kind: Kind
    let message: String
}

struct FormEngine {
    var now: Date = .now

    /// 기준선 창과 최소 표본 — 창 안 야외 5회 미만이면 기준선을 아예 내지 않는다 (계획서 M4)
    static let windowDays = 28.0
    static let minSessions = 5

    /// 측정 오류 감지용 절대 밴드 (가정 — Garmin Running Dynamics 통상 분포의 바깥)
    static let oscillationBandCm = 4.0...10.0
    static let contactBandMs = 150.0...300.0

    /// 기준선 = 창 안(28일) 스냅샷의 지표별 평균. 본인 세션은 제외한다 —
    /// 이번 세션이 평균을 끌어당기면 이탈이 묽어져 조언이 둔해진다.
    func baseline(of snapshots: [FormSnapshot], excluding id: UUID) -> FormBaseline? {
        let cutoff = now.addingTimeInterval(-Self.windowDays * 86_400)
        let window = snapshots.filter { $0.id != id && $0.start >= cutoff && $0.start <= now }
        guard window.count >= Self.minSessions else { return nil }
        func mean(_ values: [Double]) -> Double? {
            values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
        }
        return FormBaseline(cadenceSpm: mean(window.compactMap(\.cadenceSpm)),
                            verticalOscillationCm: mean(window.compactMap(\.verticalOscillationCm)),
                            groundContactMs: mean(window.compactMap(\.groundContactMs)),
                            sessionCount: window.count)
    }

    /// 조언 규칙 (가정 — 계획서 M4): 케이던스 < 기준선×0.95 → 보폭 /
    /// 진폭 > ×1.10 → 진폭 / 접촉 > ×1.10 → 접촉 시간.
    /// 절대 밴드를 벗어난 지표는 비교 대신 측정 오류 안내로 대체한다.
    func advice(session: FormSnapshot, baseline: FormBaseline) -> [FormAdvice] {
        var result: [FormAdvice] = []

        let voOutOfBand = session.verticalOscillationCm
            .map { !Self.oscillationBandCm.contains($0) } ?? false
        let gctOutOfBand = session.groundContactMs
            .map { !Self.contactBandMs.contains($0) } ?? false
        if voOutOfBand || gctOutOfBand {
            result.append(FormAdvice(
                kind: .sensor,
                message: "일부 측정값이 통상 범위를 크게 벗어났어요. 워치가 헐겁게 착용되면 주법 측정이 흔들릴 수 있어요."))
        }

        // 케이던스를 5~10% 올리면(=보폭 축소) 무릎·고관절 부하가 준다 — Heiderscheit 2011
        if let cadence = session.cadenceSpm, let base = baseline.cadenceSpm,
           cadence < base * 0.95 {
            result.append(FormAdvice(
                kind: .cadence,
                message: String(format: "케이던스가 평소 평균(%.0f spm)보다 %.0f%% 낮았어요. 보폭을 조금 줄이고 발걸음을 자주 가져가면 무릎 부담이 줄어요.",
                                base, (1 - cadence / base) * 100)))
        }
        if !voOutOfBand, let vo = session.verticalOscillationCm,
           let base = baseline.verticalOscillationCm, vo > base * 1.10 {
            result.append(FormAdvice(
                kind: .oscillation,
                message: String(format: "수직 진폭이 평소(%.1f cm)보다 컸어요. 위로 튀는 힘을 앞으로 보내는 느낌으로 달려보세요.", base)))
        }
        if !gctOutOfBand, let gct = session.groundContactMs,
           let base = baseline.groundContactMs, gct > base * 1.10 {
            result.append(FormAdvice(
                kind: .contact,
                message: String(format: "지면 접촉 시간이 평소(%.0f ms)보다 길었어요. 피로가 남았거나 페이스가 처진 신호일 수 있어요 — 가볍게 튀듯 디뎌보세요.", base)))
        }
        return result
    }
}

/// 주간 주법 인사이트 (홈) — 최근 2주 vs 이전 2주 평균 케이던스 비교 (계획서 M4)
struct FormTrend {
    let recentSpm: Double
    let previousSpm: Double
    let tone: RRTone
    var deltaSpm: Double { recentSpm - previousSpm }

    /// 미노출 가드: 두 창 각각 케이던스 표본 3회 미만이면 추이를 내지 않는다.
    /// ±2 spm 미만 변화는 측정 요동으로 보고 유지로 판정한다 (가정).
    static func compute(runs: [RunSummary], now: Date) -> FormTrend? {
        let mid = now.addingTimeInterval(-14 * 86_400)
        let cutoff = now.addingTimeInterval(-28 * 86_400)
        let recent = runs.filter { $0.start > mid && $0.start <= now }.compactMap(\.cadenceSpm)
        let previous = runs.filter { $0.start > cutoff && $0.start <= mid }.compactMap(\.cadenceSpm)
        guard recent.count >= 3, previous.count >= 3 else { return nil }
        let recentAvg = recent.reduce(0, +) / Double(recent.count)
        let previousAvg = previous.reduce(0, +) / Double(previous.count)
        let delta = recentAvg - previousAvg
        let tone: RRTone = abs(delta) < 2 ? .steady : delta > 0 ? .improving : .caution
        return FormTrend(recentSpm: recentAvg, previousSpm: previousAvg, tone: tone)
    }
}
