import Foundation

/// 달리기 좋은 시간대 — 기준 점수를 넘는 칸이 이어진 구간 하나. end는 마지막 칸의 끝 시각(18~20시면 20:00)
struct RunWindow: Equatable {
    let start: Date
    let end: Date
    /// 구간에 든 칸 점수(score)의 평균, 반올림
    let avgScore: Int
    /// 구간 평균 실제 기온 — 화면의 주 숫자 (이슈 #220)
    let temperatureC: Double
    /// 구간 평균 체감온도 — 주 숫자 옆 보조 표기
    let apparentC: Double
    /// 구간에서 가장 높은 강수확률 — 비 걱정은 최악 칸 기준으로 말한다
    let precipitationProbabilityPct: Int
    /// 오늘 남은 구간이 없어 내일에서 고른 구간 — 라벨에 "내일"을 붙인다
    var isTomorrow = false
}

/// 달리기 좋은 시간 추천 엔진 (이슈 #173, #219) — open-meteo 시간대별 예보를 칸마다 점수 매기고
/// 오늘 남은 시간 중 기준 점수 이상인 칸이 이어진 구간을 **모두** 돌려준다 (오늘 구간이 없으면 내일에서).
/// Foundation만 쓰고 `now`를 주입받아 결정론적이다.
///
/// 기온 구간은 **실제 기온** 기준이다 (이슈 #219) — 체감 16~24°C는 산책 날씨라 달리면 덥다.
/// El Helou et al. 2012 (PLoS ONE, 6대 메이저 마라톤 10년치 약 179만 명): 성적이 가장 좋은 구간은
/// 약 5~15°C, 최적점 7~10°C 부근이고 최적보다 더울 때의 손해가 추울 때보다 훨씬 크다(비대칭).
/// 그래서 고온다습(WeatherAdviceRules.isHumidHeat)을 따로 더 깎는다.
/// 러닝 이름·조언(WeatherAdviceRules)의 23/19/15/7/0°C 구간과 맞춰 카드끼리 모순된 말을 하지 않게 한다.
/// 감점 폭은 가정 — 사용 피드백으로 조정.
/// 기준 점수를 넘는 칸이 없으면 추천을 내지 않는다(빈 배열) — "틀린 인사이트는 없느니만 못하다".
enum RunWindowEngine {
    /// 한국 달력 — 한국 사용자 전용 앱이라 추천 시간대(05~22시)를 KST로 고정한다
    static let kst: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return c
    }()

    /// 추천 기준 점수 — 기온이 만점 구간을 한 단계 벗어난 칸(70)까지, 또는 만점 칸에 가벼운 감점
    /// (바람 5m/s −10, 강수확률 30% −20) 하나까지만 허용하는 선
    static let goodScore = 70

    /// 한 칸 점수 0~100.
    /// 기온 7~15°C 100 · 4~7/15~19°C 70 · 0~4/19~23°C 40 · 그 밖 10,
    /// 고온다습(습도 80%↑·19°C↑) −20, 강수확률 60%↑ −40 / 30%↑ −20, 강수량 > 0mm −20,
    /// 바람 8m/s↑ −20 / 5m/s↑ −10, 비 판정(WeatherAdviceRules.isRaining — 이슬비 코드 포함) −30.
    /// 0 아래로는 내려가지 않는다
    static func score(_ h: HourlyWeather) -> Int {
        var score = switch h.temperatureC {
        case 7..<15: 100
        case 4..<7, 15..<19: 70
        case 0..<4, 19..<23: 40
        default: 10
        }
        if WeatherAdviceRules.isHumidHeat(temperatureC: h.temperatureC, humidityPct: h.humidityPct) {
            score -= 20
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

    /// 오늘 남은 시간의 추천 구간 전부, 없으면(늦은 저녁·종일 비) 내일의 구간 전부. 시각 순.
    /// 예보는 지금부터 24칸뿐이라 내일은 지금 시각 전까지만 본다
    static func windows(hourly: [HourlyWeather], now: Date,
                        calendar: Calendar = RunWindowEngine.kst) -> [RunWindow] {
        let today = windows(hourly: hourly, now: now, day: now, calendar: calendar)
        guard today.isEmpty else { return today }
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: now) else { return [] }
        return windows(hourly: hourly, now: now, day: tomorrow, calendar: calendar).map {
            var window = $0
            window.isTomorrow = true
            return window
        }
    }

    /// day 하루의 추천 구간 — 후보 칸은 now 이후 시작·day와 같은 날(KST)·05~21시 칸(끝 ≤ 22시).
    /// 점수가 goodScore 이상인 칸을 한 시간 간격으로 이어 붙여 구간을 만든다.
    /// 값이 빠진 칸(한 시간 넘게 벌어진 칸)에서는 구간을 끊는다
    private static func windows(hourly: [HourlyWeather], now: Date, day: Date,
                                calendar: Calendar) -> [RunWindow] {
        let good = hourly
            .filter { slot in
                slot.time >= now && calendar.isDate(slot.time, inSameDayAs: day)
                    && (5...21).contains(calendar.component(.hour, from: slot.time))
                    && score(slot) >= goodScore
            }
            .sorted { $0.time < $1.time }

        var runs: [[HourlyWeather]] = []
        for slot in good {
            if let last = runs.last?.last, slot.time.timeIntervalSince(last.time) == 3_600 {
                runs[runs.count - 1].append(slot)
            } else {
                runs.append([slot])
            }
        }
        return runs.map { run in
            let count = Double(run.count)
            return RunWindow(start: run[0].time,
                             end: run[run.count - 1].time.addingTimeInterval(3_600),
                             avgScore: Int((Double(run.map(score).reduce(0, +)) / count).rounded()),
                             temperatureC: run.map(\.temperatureC).reduce(0, +) / count,
                             apparentC: run.map(\.apparentC).reduce(0, +) / count,
                             precipitationProbabilityPct: run.map(\.precipitationProbabilityPct).max() ?? 0)
        }
    }

    /// "18~20시" — 시작·끝 시각의 시(hour)만
    static func rangeLabel(_ window: RunWindow, calendar: Calendar = RunWindowEngine.kst) -> String {
        let start = calendar.component(.hour, from: window.start)
        let end = calendar.component(.hour, from: window.end)
        return "\(start)~\(end)시"
    }

    /// "6~9시 · 18~21시" — 구간을 모두 나열한다. 내일 구간이면 앞에 한 번만 "내일 "을 붙인다.
    /// 구간이 없으면 nil (미노출)
    static func rangesLabel(_ windows: [RunWindow], calendar: Calendar = RunWindowEngine.kst) -> String? {
        guard let first = windows.first else { return nil }
        let ranges = windows.map { rangeLabel($0, calendar: calendar) }.joined(separator: " · ")
        return (first.isTomorrow ? "내일 " : "") + ranges
    }
}
