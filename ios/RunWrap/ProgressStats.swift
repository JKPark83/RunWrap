import Foundation

/// 발전상 (기획서 §4.7, 계획서 M3) — 통계 탭의 장기 추이·최고 기록 재료.
/// 순수 집계 레이어: Foundation만 쓰고 now를 주입받아 결정론적이다.
/// 판정이 아니라 집계라 RRTone을 내지 않는다 — 그리는 방법은 화면이 정한다.

/// 월별 페이스·EF·거리 시리즈 — MonthlyStats.compute를 월마다 반복 호출해 구성
struct MonthlySeries {
    struct Point {
        let month: Date          // 월 시작일
        let label: String        // "3월" — 축 라벨용
        let totalKm: Double
        let avgPaceSec: Double?  // 그 달 거리 표본이 없으면 nil
        let avgEF: Double?       // 심박 있는 세션 3회 미만이면 nil (efficiency 가드와 동일 기준)
    }

    let points: [Point]          // 오래된 → 최신

    /// 미노출 가드: 러닝이 있는 월이 2개 미만이면 추이라 부를 수 없다 → nil.
    /// 구간은 최근 12개월로 자른다 (가정 — 차트 가독성).
    static func compute(runs: [RunSummary], now: Date) -> MonthlySeries? {
        let calendar = Calendar.current
        let months = MonthlyStats.availableMonths(in: runs, now: now).prefix(12).reversed()

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M월"

        let points = months.map { month in
            let stats = MonthlyStats.compute(runs: runs, month: month, now: now)
            return Point(month: month,
                         label: formatter.string(from: month),
                         totalKm: stats.totalKm,
                         avgPaceSec: stats.avgPaceSec,
                         avgEF: monthlyEF(runs: runs, month: month, calendar: calendar))
        }
        guard points.filter({ $0.totalKm > 0 }).count >= 2 else { return nil }
        return MonthlySeries(points: points)
    }

    /// 월평균 EF = 세션별 (분속 m/min ÷ 평균 심박)의 단순 평균 (TrainingPeaks EF).
    /// 페이스·심박이 모두 있는 세션이 3회 미만인 달은 잡음이 커 점을 내지 않는다.
    private static func monthlyEF(runs: [RunSummary], month: Date,
                                  calendar: Calendar) -> Double? {
        guard let interval = calendar.dateInterval(of: .month, for: month) else { return nil }
        let efs = runs.filter { interval.contains($0.start) }
            .compactMap { run -> Double? in
                guard let pace = run.paceSecPerKm,
                      let hr = run.avgHeartRate, hr > 0 else { return nil }
                return (60_000 / pace) / hr
            }
        guard efs.count >= 3 else { return nil }
        return efs.reduce(0, +) / Double(efs.count)
    }
}

/// 거리별 최고 기록 — 5K/10K/하프/풀 (기획서 §4.7).
/// PR 판정 (가정 — 계획서 M3): 완주 거리 ∈ [D, D×1.10]인 세션 중 (페이스 × D)의 최소값.
/// 워치 GPS는 공인 거리보다 조금 길게 찍히는 게 보통이라 10% 상단 여유를 둔다.
/// 해당 거리 기록이 없으면 항목 자체를 내지 않는다.
struct PersonalRecords {
    struct Entry {
        let label: String        // "5K" · "10K" · "하프" · "풀"
        let distanceKm: Double   // 공인 거리
        let timeSec: Double      // 공인 거리 환산 기록 (페이스 × D)
        let date: Date           // 달성일
    }

    /// 공인 거리 4종 (km)
    static let targets: [(label: String, km: Double)] = [
        ("5K", 5.0), ("10K", 10.0), ("하프", 21.0975), ("풀", 42.195),
    ]

    static func compute(runs: [RunSummary]) -> [Entry] {
        targets.compactMap { target in
            let candidates = runs.compactMap { run -> (time: Double, date: Date)? in
                guard let km = run.distanceKm, let pace = run.paceSecPerKm,
                      km >= target.km, km <= target.km * 1.10 else { return nil }
                return (time: pace * target.km, date: run.start)
            }
            guard let best = candidates.min(by: { $0.time < $1.time }) else { return nil }
            return Entry(label: target.label, distanceKm: target.km,
                         timeSec: best.time, date: best.date)
        }
    }
}
