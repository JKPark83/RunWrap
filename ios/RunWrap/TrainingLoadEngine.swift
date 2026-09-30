import Foundation

/// 훈련 부하 추세 — TRIMP 기반 체력(CTL)·피로(ATL)·폼(TSB) (이슈 #177, 기획서 §4 훈련 부하).
///
/// **왜 ACWR과 따로 두는가.** ACWR(ReportEngine.acwrLoad)은 거리만 보므로 같은 10km라도
/// 조깅과 템포런을 같은 부하로 친다. 여기서는 세션마다 심박 강도 × 시간(TRIMP, Banister 1991)을
/// 훈련 자극으로 삼고, 매일 누적한 값을 두 시상수로 가중 평균한다 —
/// 42일 = 체력(CTL), 7일 = 피로(ATL), 폼(TSB) = 체력 − 피로 (Coggan PMC 42/7 관례).
///
/// 미노출 가드: 지수 가중 평균은 초기값 0에서 출발하므로 이력이 42일보다 짧으면 CTL이 실제보다
/// 낮게 나온다(acwrLoad의 28일 가드와 같은 논리). 최근 42일 심박 세션이 8회 미만이어도 내지 않는다.
struct TrainingLoad: Equatable {
    struct Point: Equatable {
        /// 그날 자정 (calendar.startOfDay)
        let day: Date
        let ctl: Double
        let atl: Double
        /// 그날 기준 전날 잔고 — 전날 CTL − 전날 ATL
        let tsb: Double
    }

    /// 폼 구간 — 경계는 TrainingLoadEngine의 상수 (Coggan/TrainingPeaks 관례)
    enum Band {
        case overload, productive, maintain, fresh, detraining
    }

    /// 최근 28일 + 오늘 = 29개, 오래된 → 최신, day는 자정
    let points: [Point]
    /// 오늘 체력
    let ctl: Double
    /// 오늘 피로
    let atl: Double
    /// 전날 CTL − 전날 ATL (Coggan 관례: 오늘의 폼은 어제까지의 잔고)
    let tsb: Double
    let band: Band
    let tone: RRTone
    /// 최근 42일 TRIMP 산출 세션 수
    let sessionCount: Int
}

enum TrainingLoadEngine {
    /// 미노출 가드 — 최근 42일 창에 TRIMP 세션이 이보다 적으면 카드를 내지 않는다 (주 1~2회 × 6주)
    static let minSessions = 8
    /// 체력(CTL)·피로(ATL) 시상수(일) — Coggan PMC 42/7
    static let ctlDays = 42.0
    static let atlDays = 7.0
    /// 추세 차트 길이 — 오늘 이전 28일
    static let trendDays = 28
    /// 안정 심박 폴백 — 안정 심박이 없으면 성인 평균 60bpm으로 HRr을 만든다
    static let fallbackRestingHR = 60.0

    /// TSB 구간 경계 (Coggan/TrainingPeaks 관례):
    /// −30 미만 과부하 · −30~−10 미만 체력 쌓는 중 · −10~+5 유지 · +5 초과~+25 가벼움 · +25 초과 훈련 부족
    static let overloadTSB = -30.0
    static let productiveTSB = -10.0
    static let freshTSB = 5.0
    static let detrainingTSB = 25.0

    /// 세션 TRIMP (Banister 1991) — 분 × HRr × 0.64 × e^(1.92 × HRr).
    /// HRr = (평균 심박 − 안정) / (HRmax − 안정), 0…1로 자른다(안정 심박 이하면 0 → TRIMP 0).
    /// 성별 상수는 남성(0.64·1.92) 하나만 쓴다 — 성별을 묻지 않는 프라이버시 원칙.
    /// HRmax가 190 폴백이면 근거가 없어 nil (대회 노력도와 같은 `reliableHrMax`)
    static func trimp(run: RunSummary, profile: HeartRateProfile) -> Double? {
        guard let avg = run.avgHeartRate, run.durationSec > 0,
              let hrMax = profile.reliableHrMax else { return nil }
        let rest = profile.restingHR ?? fallbackRestingHR
        let hrr = min(max((avg - rest) / (hrMax - rest), 0), 1)
        return run.durationSec / 60 * hrr * 0.64 * exp(1.92 * hrr)
    }

    /// 체력·피로·폼 — 세션 TRIMP를 달력 일 단위로 합산해 첫 세션 날부터 오늘까지 하루씩
    /// 지수 가중 평균한다: `ctl += (t − ctl) / 42`, `atl += (t − atl) / 7` (세션 없는 날 t = 0, 초기값 0).
    /// 가드: TRIMP 세션 최고령이 42일 이상 전 AND 최근 42일 창 [now−42일, now)에 8회 이상.
    static func compute(runs: [RunSummary],
                        profile: HeartRateProfile,
                        now: Date,
                        calendar: Calendar = .current) -> TrainingLoad? {
        let sessions = runs
            .filter { $0.start <= now }
            .compactMap { run -> (start: Date, trimp: Double)? in
                trimp(run: run, profile: profile).map { (run.start, $0) }
            }
        guard let oldest = sessions.map(\.start).min(),
              oldest <= now.addingTimeInterval(-ctlDays * 86_400) else { return nil }
        let windowStart = now.addingTimeInterval(-ctlDays * 86_400)
        let sessionCount = sessions.filter { $0.start >= windowStart && $0.start < now }.count
        guard sessionCount >= minSessions else { return nil }

        var daily: [Date: Double] = [:]
        for session in sessions {
            daily[calendar.startOfDay(for: session.start), default: 0] += session.trimp
        }

        // 하루씩 EWMA — 최고령이 42일 이상 전이라 상태는 항상 43일 이상 쌓인다
        let today = calendar.startOfDay(for: now)
        var states: [(day: Date, ctl: Double, atl: Double)] = []
        var ctl = 0.0, atl = 0.0
        var day = calendar.startOfDay(for: oldest)
        while day <= today {
            let t = daily[day] ?? 0
            ctl += (t - ctl) / ctlDays
            atl += (t - atl) / atlDays
            states.append((day, ctl, atl))
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            day = calendar.startOfDay(for: next)
        }

        // 각 점의 TSB는 전날 잔고 (Coggan 관례)
        let points = states.indices.suffix(trendDays + 1).map { i in
            TrainingLoad.Point(day: states[i].day, ctl: states[i].ctl, atl: states[i].atl,
                               tsb: states[i - 1].ctl - states[i - 1].atl)
        }
        guard let latest = points.last else { return nil }
        let band = band(tsb: latest.tsb)
        return TrainingLoad(points: points, ctl: latest.ctl, atl: latest.atl, tsb: latest.tsb,
                            band: band, tone: tone(band), sessionCount: sessionCount)
    }

    /// TSB → 폼 구간 (경계 상수 참고)
    static func band(tsb: Double) -> TrainingLoad.Band {
        if tsb < overloadTSB { return .overload }
        if tsb < productiveTSB { return .productive }
        if tsb <= freshTSB { return .maintain }
        if tsb <= detrainingTSB { return .fresh }
        return .detraining
    }

    /// 폼 구간 → 톤 — 체력 쌓는 피로는 좋아지는 중, 가벼움은 유지, 훈련 부족은 주의
    static func tone(_ band: TrainingLoad.Band) -> RRTone {
        switch band {
        case .overload: .overload
        case .productive: .improving
        case .maintain, .fresh: .steady
        case .detraining: .caution
        }
    }
}
