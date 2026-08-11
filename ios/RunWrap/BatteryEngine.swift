import Foundation

/// 활력징후 스냅샷 — 오늘 값과 최근 28일 개인 기준선
///
/// Apple Watch가 주로 수면 중에 기록하는 값들이다. 기준선은 오늘을 제외한
/// 일평균의 평균으로, Apple 활력징후 앱과 같은 "내 평소 범위" 개념이다.
struct VitalsSnapshot: Equatable {
    struct Reading: Equatable {
        let today: Double
        let baseline: Double      // 오늘을 제외한 최근 28일 일평균의 평균
        let baselineDays: Int     // 기준선 계산에 쓰인 날짜 수
    }

    var hrvMs: Reading? = nil            // 심박 변이도 SDNN (ms) — 높을수록 회복
    var restingHR: Reading? = nil        // 안정 심박 (bpm) — 낮을수록 회복
    var respiratoryRate: Reading? = nil  // 수면 중 호흡수 (회/분) — 이탈 시 감점
    var wristTempC: Reading? = nil       // 수면 중 손목 온도 (°C) — 상승 시 감점
    var sleepHours: Double? = nil        // 지난밤 수면 시간
}

/// 체력 배터리 결과 — 남은 체력 추정치(0–100)와 요인별 기여
struct BatteryReport: Equatable {
    struct Factor: Equatable {
        let name: String
        let detail: String        // "55 ms · 평소 62 ms"
        let points: Int           // 기여 포인트 (충전 +, 소모 −)
        let systemImage: String
    }

    let level: Int                // 0–100
    let tone: RRTone              // 색상 매핑용
    let statusLabel: String       // "충전 충분" / "양호" / "주의" / "방전 임박"
    let headline: String
    let factors: [Factor]
}

/// 체력 배터리 엔진 — 활력징후(회복)와 훈련 부하(소모)를 합산한다
///
/// 모델: 중립 50에서 시작해 요인별 포인트를 더한다.
/// - 심박 변이(HRV): 기준선 대비 ±25% 편차가 ±20pt
/// - 안정 심박: 기준선 대비 ∓10% 편차가 ±15pt (낮을수록 +)
/// - 수면: 7시간 기준, ±2시간이 ±15pt
/// - 호흡수·손목 온도: 평소 범위를 벗어나면 각각 −6pt (감점 전용)
/// - 오늘 훈련: 오늘 뛴 거리 km × 2pt 소모 (최대 −25)
/// - 훈련 부하: ACWR > 1.3이면 초과분만큼 소모 (최대 −15)
///
/// 가드: 핵심 신호(HRV·안정 심박·수면) 중 2개 이상이 있어야 계산한다.
/// 기준선은 최소 7일 — 부족하면 그 요인은 없는 것으로 친다.
/// "틀린 인사이트는 없느니만 못하다."
enum BatteryEngine {
    static let minBaselineDays = 7   // Apple 활력징후 앱과 같은 최소 기준선

    static func compute(vitals: VitalsSnapshot,
                        runs: [RunSummary],
                        now: Date = .now) -> BatteryReport? {
        var factors: [BatteryReport.Factor] = []
        var coreSignals = 0

        if let r = valid(vitals.hrvMs) {
            let pts = points((r.today / r.baseline - 1) / 0.25, scale: 20)
            factors.append(.init(name: "심박 변이",
                                 detail: String(format: "%.0f ms · 평소 %.0f ms", r.today, r.baseline),
                                 points: pts,
                                 systemImage: "waveform.path.ecg"))
            coreSignals += 1
        }

        if let r = valid(vitals.restingHR) {
            let pts = points((1 - r.today / r.baseline) / 0.10, scale: 15)
            factors.append(.init(name: "안정 심박",
                                 detail: String(format: "%.0f bpm · 평소 %.0f bpm", r.today, r.baseline),
                                 points: pts,
                                 systemImage: "heart.fill"))
            coreSignals += 1
        }

        if let hours = vitals.sleepHours {
            let pts = points((hours - 7) / 2, scale: 15)
            factors.append(.init(name: "수면",
                                 detail: sleepText(hours),
                                 points: pts,
                                 systemImage: "moon.zzz.fill"))
            coreSignals += 1
        }

        guard coreSignals >= 2 else { return nil }

        // 감점 전용 보조 신호 — 평소 범위 안이면 표시하지 않는다
        if let r = valid(vitals.respiratoryRate), abs(r.today / r.baseline - 1) > 0.12 {
            factors.append(.init(name: "호흡수",
                                 detail: String(format: "분당 %.1f회 · 평소 %.1f회", r.today, r.baseline),
                                 points: -6,
                                 systemImage: "lungs.fill"))
        }
        if let r = valid(vitals.wristTempC), r.today - r.baseline >= 0.4 {
            factors.append(.init(name: "손목 온도",
                                 detail: String(format: "평소보다 +%.1f°C", r.today - r.baseline),
                                 points: -6,
                                 systemImage: "thermometer.medium"))
        }

        let todayKm = kmToday(runs, now: now)
        if todayKm > 0.1 {
            factors.append(.init(name: "오늘 훈련",
                                 detail: String(format: "%.1f km", todayKm),
                                 points: -min(25, Int((todayKm * 2).rounded())),
                                 systemImage: "figure.run"))
        }

        if let ratio = acwr(runs, now: now), ratio > 1.3 {
            factors.append(.init(name: "훈련 부하",
                                 detail: String(format: "부하 비율 %.2f", ratio),
                                 points: -min(15, Int(((ratio - 1.3) * 25).rounded())),
                                 systemImage: "speedometer"))
        }

        let level = max(0, min(100, 50 + factors.map(\.points).reduce(0, +)))
        let (tone, label, headline) = status(for: level)
        return BatteryReport(level: level, tone: tone, statusLabel: label,
                             headline: headline, factors: factors)
    }

    // MARK: - 내부

    /// 기준선이 7일 이상 쌓인 정상 측정값만 통과시킨다
    private static func valid(_ reading: VitalsSnapshot.Reading?) -> VitalsSnapshot.Reading? {
        guard let reading, reading.baselineDays >= minBaselineDays, reading.baseline > 0 else {
            return nil
        }
        return reading
    }

    private static func points(_ normalized: Double, scale: Double) -> Int {
        Int((max(-1, min(1, normalized)) * scale).rounded())
    }

    private static func status(for level: Int) -> (RRTone, String, String) {
        switch level {
        case 75...:   (.improving, "충전 충분", "몸이 충분히 충전됐어요")
        case 50..<75: (.steady, "양호", "무리하지 않으면 충분한 상태예요")
        case 25..<50: (.caution, "주의", "회복이 덜 됐어요, 오늘은 가볍게 가세요")
        default:      (.overload, "방전 임박", "오늘은 훈련보다 충전이 먼저예요")
        }
    }

    private static func sleepText(_ hours: Double) -> String {
        let totalMin = Int((hours * 60).rounded())
        return "\(totalMin / 60)시간 \(totalMin % 60)분"
    }

    /// 오늘 0시 이후 뛴 거리 — 어제까지의 훈련은 밤사이 활력징후에 이미 반영돼 있다
    private static func kmToday(_ runs: [RunSummary], now: Date) -> Double {
        let dayStart = Calendar.current.startOfDay(for: now)
        return runs.filter { $0.start >= dayStart && $0.start <= now }
            .compactMap(\.distanceKm)
            .reduce(0, +)
    }

    /// ReportEngine과 같은 가드의 ACWR — 3주 미만 기록이거나 주평균 3km 미만이면 nil
    private static func acwr(_ runs: [RunSummary], now: Date) -> Double? {
        guard let oldest = runs.map(\.start).min(),
              oldest <= now.addingTimeInterval(-21 * 86_400) else { return nil }
        func windowKm(_ days: Double) -> Double {
            let from = now.addingTimeInterval(-days * 86_400)
            return runs.filter { $0.start >= from && $0.start <= now }
                .compactMap(\.distanceKm)
                .reduce(0, +)
        }
        let acute = windowKm(7)
        let chronic = windowKm(28) / 4
        guard chronic >= 3 else { return nil }
        return acute / chronic
    }
}
