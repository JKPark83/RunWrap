import Foundation

/// 달리기 좋은 시간대 — 추천 창 하나. end는 마지막 칸의 끝 시각(18~20시면 20:00)
struct RunWindow: Equatable {
    let start: Date
    let end: Date
    /// 창에 든 칸 점수(score)의 평균, 반올림
    let avgScore: Int
    /// 창 평균 체감온도
    let apparentC: Double
    /// 창에서 가장 높은 강수확률 — 비 걱정은 최악 칸 기준으로 말한다
    let precipitationProbabilityPct: Int
    /// 오늘 남은 창이 없어 내일에서 고른 창 — 라벨에 "내일"을 붙인다
    var isTomorrow = false
}

/// 달리기 좋은 시간 추천 엔진 (이슈 #173) — open-meteo 시간대별 예보를 칸마다 점수 매기고
/// 오늘 남은 시간 중 2~3시간 창 하나를 고른다 (오늘 창이 없으면 내일에서). Foundation만 쓰고 `now`를 주입받아 결정론적이다.
///
/// 체감온도 구간은 러닝 이름·조언(WeatherAdviceRules)·복장(OutfitRules)의 24/16/8/0°C와 맞춰
/// 카드끼리 모순된 말을 하지 않게 한다. 감점 폭은 가정 — 사용 피드백으로 조정.
/// 좋은 창이 없으면(평균 50점 미만) 추천을 내지 않는다 — "틀린 인사이트는 없느니만 못하다".
enum RunWindowEngine {
    /// 한국 달력 — 한국 사용자 전용 앱이라 추천 시간대(05~22시)를 KST로 고정한다
    static let kst: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return c
    }()

    /// 한 칸 점수 0~100.
    /// 체감 16~24°C 100 · 8~16/24~28°C 70 · 0~8/28~33°C 40 · 그 밖 10,
    /// 강수확률 60%↑ −40 / 30%↑ −20, 강수량 > 0mm −20, 바람 8m/s↑ −20 / 5m/s↑ −10,
    /// 비 판정(WeatherAdviceRules.isRaining — 이슬비 코드 포함) −30. 0 아래로는 내려가지 않는다
    static func score(_ h: HourlyWeather) -> Int {
        var score = switch h.apparentC {
        case 16..<24: 100
        case 8..<16, 24..<28: 70
        case 0..<8, 28..<33: 40
        default: 10
        }
        if h.precipitationProbabilityPct >= 60 {
            score -= 40
        } else if h.precipitationProbabilityPct >= 30 {
            score -= 20
        }
        if h.precipitationMm > 0 { score -= 20 }
        // 바람 8m/s는 복장 룰의 바람막이 기준(OutfitRules.windbreakerMs)과 같은 선
        if h.windMs >= 8 {
            score -= 20
        } else if h.windMs >= 5 {
            score -= 10
        }
        if WeatherAdviceRules.isRaining(code: h.weatherCode, precipitationMm: h.precipitationMm) {
            score -= 30
        }
        return max(0, score)
    }

    /// 오늘 남은 시간 중 최고 창, 없으면(늦은 저녁·종일 비) 내일의 최고 창.
    /// 예보는 지금부터 24칸뿐이라 내일은 지금 시각 전까지만 본다
    static func bestWindow(hourly: [HourlyWeather], now: Date,
                           calendar: Calendar = RunWindowEngine.kst) -> RunWindow? {
        if let today = bestWindow(hourly: hourly, now: now, day: now, calendar: calendar) { return today }
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: now),
              var window = bestWindow(hourly: hourly, now: now, day: tomorrow, calendar: calendar)
        else { return nil }
        window.isTomorrow = true
        return window
    }

    /// day 하루의 최고 창 — 후보 칸은 now 이후 시작·day와 같은 날(KST)·05~22시 안.
    /// 2시간 창을 모두 평가해 평균이 가장 높은 창(동점이면 더 이른 창)을 고르고,
    /// 바로 다음 칸 점수도 그 평균 이상이면 3시간으로 늘린다 (창 시작 ≤ 19시, 끝 ≤ 22시).
    /// 후보가 2시간이 안 되거나 최고 평균이 50점 미만이면 nil (미노출).
    /// 50점은 체감 구간이 한 단계 벗어난 칸(70)에 감점이 한 번 겹친 수준까지만 허용하는 선
    private static func bestWindow(hourly: [HourlyWeather], now: Date, day: Date,
                                   calendar: Calendar) -> RunWindow? {
        let slots = hourly
            .filter { slot in
                slot.time >= now && calendar.isDate(slot.time, inSameDayAs: day)
                    && (5...21).contains(calendar.component(.hour, from: slot.time))
            }
            .sorted { $0.time < $1.time }
        guard slots.count >= 2 else { return nil }

        /// 두 칸이 한 시간 간격으로 이어지는가 — 값이 빠진 칸을 건너뛴 창을 막는다
        func adjacent(_ i: Int) -> Bool {
            slots[i + 1].time.timeIntervalSince(slots[i].time) == 3_600
        }

        var best: (start: Int, average: Double)?
        for i in 0..<(slots.count - 1) {
            guard calendar.component(.hour, from: slots[i].time) <= 19, adjacent(i) else { continue }
            let average = Double(score(slots[i]) + score(slots[i + 1])) / 2
            // 엄격한 >라 동점이면 먼저 본(이른) 창이 남는다
            if best.map({ average > $0.average }) ?? true {
                best = (i, average)
            }
        }
        guard let best, best.average >= 50 else { return nil }

        var window = Array(slots[best.start...best.start + 1])
        let next = best.start + 2
        // 다음 칸도 후보(≤ 21시 칸 → 끝 ≤ 22시)이고 이어져 있으며 점수가 평균 이상이면 3시간으로
        if next < slots.count, adjacent(next - 1), Double(score(slots[next])) >= best.average {
            window.append(slots[next])
        }

        let scores = window.map(score)
        let count = Double(window.count)
        return RunWindow(start: window[0].time,
                         end: window[window.count - 1].time.addingTimeInterval(3_600),
                         avgScore: Int((Double(scores.reduce(0, +)) / count).rounded()),
                         apparentC: window.map(\.apparentC).reduce(0, +) / count,
                         precipitationProbabilityPct: window.map(\.precipitationProbabilityPct).max() ?? 0)
    }

    /// "18~20시" — 시작·끝 시각의 시(hour)만. 내일 창이면 "내일 6~8시"
    static func rangeLabel(_ window: RunWindow, calendar: Calendar = RunWindowEngine.kst) -> String {
        let start = calendar.component(.hour, from: window.start)
        let end = calendar.component(.hour, from: window.end)
        return "\(window.isTomorrow ? "내일 " : "")\(start)~\(end)시"
    }
}
