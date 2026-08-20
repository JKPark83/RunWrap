import Foundation

/// 홈 판단 카드 엔진 — "오늘 뛸까 말까, 뛰면 얼마나"를 한 카드로 답한다 (기획서 v0.8 §6).
/// Foundation만 쓰고 `now`를 주입받아 결정론적이다. UI를 모른다 — 색이 아니라 RRTone까지만 정한다.
///
/// 재료는 대부분 이미 있는 엔진의 결과를 조립할 뿐이다:
/// 배터리(`BatteryEngine`) · 날씨/복장(`WeatherClient`·`OutfitRules`) · 주간 처방(`TrainingGuideEngine`).
/// 여기서 새로 계산하는 것은 **오늘 권장 거리** 하나뿐이다.
///
/// 미노출 가드는 두 겹이다. 카드 전체는 러닝 기록이 하나도 없으면 내지 않고(nil),
/// 줄 단위로는 재료가 없으면 값 대신 유도 문구(`hint`)를 낸다 —
/// 값을 지어내지 않으면서도 "무엇을 하면 켜지는지"는 알려주기 위해서다.

struct TodayVerdict: Equatable {
    /// 판단 카드의 재료 한 줄 — 값이 있으면 `value`, 없으면 무엇을 하면 켜지는지 `hint`
    struct Line: Equatable {
        /// 줄의 종류 — 아이콘·이동 목적지 매핑은 화면 몫이다
        enum Kind { case battery, weather, session, recovery }

        enum Content: Equatable {
            case value(String)
            case hint(String)
        }

        let kind: Kind
        let label: String
        let content: Content
        /// 값이 있고 상태 판정이 가능할 때만 — 유도 문구 줄은 항상 nil
        let tone: RRTone?
    }

    /// 제목줄 판정 — 배터리가 없으면 판정하지 않는다(nil). 화면은 배지를 감춘다
    let tone: RRTone?
    let headline: String
    let battery: Line
    let weather: Line
    let session: Line
    let recovery: Line

    var lines: [Line] { [battery, weather, session, recovery] }
}

enum TodayVerdictEngine {
    /// 날씨 재료 — 화면이 위치·네트워크 상태를 값으로 접어 넘긴다 (엔진은 네트워크를 모른다)
    enum WeatherInput: Equatable {
        case loading
        case denied         // 위치 권한 거부 — 앱 안에서 다시 물을 수 없어 설정으로 보내야 한다
        case unavailable    // 위치·날씨 조회 실패
        case current(CurrentWeather)
    }

    /// - Parameters:
    ///   - runs: 전체 러닝 이력
    ///   - battery: 체력 배터리. 표본이 부족하면 nil로 들어온다
    ///   - weather: 날씨 재료 (화면이 위치·네트워크 상태를 접어 넘긴다)
    ///   - guide: 주간 처방. 목표 레이스 미설정이거나 기록 3주 미만이면 nil
    ///   - hasRaceGoal: 목표 레이스 설정 여부 — guide가 nil인 이유를 갈라 유도 문구를 고른다
    ///   - weeklyGoal: 주간 목표 러닝 횟수
    ///   - level: 러너 레벨 — 오늘의 훈련(인터벌 스펙 등)을 정할 때 쓴다
    static func verdict(runs: [RunSummary],
                        battery: BatteryReport?,
                        weather: WeatherInput,
                        guide: TrainingGuide?,
                        hasRaceGoal: Bool,
                        weeklyGoal: Int,
                        level: RunnerLevel = .intermediate,
                        now: Date) -> TodayVerdict? {
        // 기록이 하나도 없으면 네 줄이 전부 유도 문구가 된다 — 환영이 아니라 과제 목록으로
        // 읽히므로 카드 자체를 내지 않는다 (홈은 첫 러닝 안내만 남긴다)
        guard !runs.isEmpty else { return nil }

        let (tone, headline) = verdictHeadline(battery: battery)
        return TodayVerdict(tone: tone,
                            headline: headline,
                            battery: batteryLine(battery),
                            weather: weatherLine(weather, now: now),
                            session: sessionLine(runs: runs, battery: battery, guide: guide,
                                                 hasRaceGoal: hasRaceGoal,
                                                 weeklyGoal: weeklyGoal, level: level, now: now),
                            recovery: recoveryLine(runs: runs, now: now))
    }

    // MARK: - 제목줄

    /// 판정은 체력 배터리 톤 하나로 한다 — 오늘의 결정을 가르는 건 회복 상태다.
    /// 배터리가 없으면 판정하지 않고 중립 문구만 둔다 ("틀린 인사이트는 없느니만 못하다").
    private static func verdictHeadline(battery: BatteryReport?) -> (RRTone?, String) {
        guard let battery else { return (nil, "오늘은 어떻게 가실까요") }
        return switch battery.tone {
        case .overload: (.overload, "오늘은 쉬시는 게 이깁니다")
        case .caution: (.caution, "가볍게만 다녀오세요")
        case .steady: (.steady, "평소대로 가셔도 돼요")
        case .improving: (.improving, "몸이 좋습니다, 밀어붙여도 돼요")
        }
    }

    // MARK: - 체력 배터리

    private static func batteryLine(_ battery: BatteryReport?) -> TodayVerdict.Line {
        guard let battery else {
            // 배터리 가드는 활력징후 표본이다 — 며칠이 더 필요한지는 배터리가 nil인 시점에
            // 알 수 없으므로(요인별 기준선이 제각각) 숫자 없이 조건만 말한다
            return .init(kind: .battery, label: "체력 배터리",
                         content: .hint("워치를 차고 주무시면 켜져요"), tone: nil)
        }
        return .init(kind: .battery, label: "체력 배터리",
                     content: .value("\(battery.level) · \(battery.statusLabel)"),
                     tone: battery.tone)
    }

    // MARK: - 날씨·복장

    private static func weatherLine(_ weather: WeatherInput, now: Date) -> TodayVerdict.Line {
        let label = "날씨"
        switch weather {
        case .loading:
            return .init(kind: .weather, label: label,
                         content: .hint("날씨를 불러오는 중"), tone: nil)
        case .denied:
            return .init(kind: .weather, label: label,
                         content: .hint("설정에서 위치 허용하기"), tone: nil)
        case .unavailable:
            return .init(kind: .weather, label: label,
                         content: .hint("날씨를 불러오지 못했어요"), tone: nil)
        case .current(let current):
            return .init(kind: .weather, label: label,
                         content: .value(weatherPhrase(current, now: now)), tone: nil)
        }
    }

    /// 날씨 줄의 조각 — 홈 카드는 아이콘 옆에 조각으로 나눠 쓰고, 한 줄로 합치면 문구가 된다.
    /// 하늘 상태(WMO 코드)는 여기 없다. 복장이 이미 조건을 요약하고,
    /// 판단을 가르는 건 맑음/흐림이 아니라 강수 여부다 (아이콘은 화면 몫).
    struct WeatherParts: Equatable {
        let temperature: String     // "체감 22°C"
        let raining: Bool
        let outfit: String?         // "반팔 티+반바지" — 상·하의를 낼 수 없으면 nil
    }

    static func weatherParts(_ current: CurrentWeather, now: Date) -> WeatherParts {
        let outfit = OutfitRules.outfit(apparentC: current.apparentC,
                                        humidityPct: current.humidityPct,
                                        windMs: current.windMs,
                                        precipitationMm: current.precipitationMm,
                                        uvIndex: current.uvIndex,
                                        now: now)
        return WeatherParts(temperature: "체감 \(Int(current.apparentC.rounded()))°C",
                            raining: current.precipitationMm > 0,
                            outfit: outfitPhrase(outfit))
    }

    /// "체감 22°C · 반팔 티+반바지" — 비가 오면 가운데에 "비"를 끼운다
    private static func weatherPhrase(_ current: CurrentWeather, now: Date) -> String {
        let parts = weatherParts(current, now: now)
        return ([parts.temperature, parts.raining ? "비" : nil, parts.outfit]
            .compactMap { $0 })
            .joined(separator: " · ")
    }

    /// 상의·하의 한 벌만 뽑아 "반팔+반바지"로 —
    /// 나머지 소품(모자·장갑·선크림)은 오늘 시트에서 본다
    private static func outfitPhrase(_ items: [OutfitItem]) -> String? {
        let tops: [OutfitItem] = [.singlet, .shortSleeve, .longSleeve, .thermalTop]
        let bottoms: [OutfitItem] = [.shorts, .tights, .thermalBottom]
        let picked = [tops, bottoms].compactMap { group in
            items.first { group.contains($0) }?.label
        }
        return picked.isEmpty ? nil : picked.joined(separator: "+")
    }

    // MARK: - 오늘 권장 세션

    /// 오늘의 훈련은 `TrainingGuideEngine.todayWorkout`이 정한다 (형태·거리·페이스).
    /// 여기서는 그 결과를 홈 카드 한 줄 문구로 접는다 — "이지런 5.0km · 5′20″" 꼴.
    /// 이지런 이름은 배터리 톤에 따라 가볍게/이지런/빌드업으로 갈라 쓴다 (기존 규칙 유지).
    private static func sessionLine(runs: [RunSummary], battery: BatteryReport?,
                                    guide: TrainingGuide?, hasRaceGoal: Bool,
                                    weeklyGoal: Int, level: RunnerLevel,
                                    now: Date) -> TodayVerdict.Line {
        let label = "오늘 권장"
        func line(_ content: TodayVerdict.Line.Content, _ tone: RRTone?) -> TodayVerdict.Line {
            .init(kind: .session, label: label, content: content, tone: tone)
        }

        guard let guide else {
            // 처방이 없는 이유가 둘이라 유도 문구를 가른다 (목표 미설정 / 표본 부족)
            return line(.hint(hasRaceGoal ? "3주치 기록이 쌓이면 알려드려요"
                                          : "목표 대회를 정해 보세요"), nil)
        }

        let today = TrainingGuideEngine(now: now, level: level)
            .todayWorkout(runs: runs, guide: guide,
                          batteryTone: battery?.tone, weeklyGoal: weeklyGoal)
        switch today.kind {
        case .rest:
            // 방전 임박에는 거리를 내지 않는다 — 오늘의 처방은 쉬는 것이다
            return line(.value("오늘은 휴식"), .overload)
        case .doneCount:
            return line(.value("이번 주 횟수를 다 채우셨어요"), .improving)
        case .doneKm:
            return line(.value("이번 주 목표를 채우셨어요"), .improving)
        case .easy, .lsd, .tempo, .interval:
            return line(.value(sessionPhrase(today, batteryTone: battery?.tone)),
                        battery?.tone ?? .steady)
        }
    }

    /// "이지런 5.0km · 5′20″" — 인터벌은 스펙이 이미 이름에 있어 거리를 겹쳐 쓰지 않고,
    /// 페이스는 존 구간의 중앙값 하나만 낸다 (구간 전체는 리포트 탭 카드에서 본다)
    private static func sessionPhrase(_ today: TodayWorkout, batteryTone: RRTone?) -> String {
        let name = today.kind == .easy ? intensityLabel(batteryTone) : today.kind.label
        let km: String? = switch today.kind {
        case .interval: nil
        default: today.distanceKm.map { "\(Format.km($0))km" }
        }
        let pace = today.paceSecPerKm.map {
            Format.pace(($0.lowerBound + $0.upperBound) / 2)
        }
        return [name + (km.map { " \($0)" } ?? ""), pace].compactMap { $0 }
            .joined(separator: " · ")
    }

    /// 강도 이름 — 배터리 톤 매핑. overload는 위에서 이미 갈라져 여기 오지 않는다
    private static func intensityLabel(_ tone: RRTone?) -> String {
        switch tone {
        case .caution: "가볍게"
        case .improving: "빌드업"
        default: "이지런"      // steady · 배터리 없음
        }
    }

    // MARK: - 회복 경과

    private static func recoveryLine(runs: [RunSummary], now: Date) -> TodayVerdict.Line {
        // 호출부가 기록 유무를 이미 확인했다 — 여기 오면 마지막 러닝은 반드시 있다
        let last = runs.map(\.start).max() ?? now
        let days = calendar.dateComponents([.day],
                                           from: calendar.startOfDay(for: last),
                                           to: calendar.startOfDay(for: now)).day ?? 0
        let text = switch days {
        case ..<1: "오늘 다녀오셨어요"
        case 1: "어제"
        default: "\(days)일 전"
        }
        return .init(kind: .recovery, label: "마지막 러닝", content: .value(text), tone: nil)
    }

    // MARK: - 달력 (ISO 8601, 월요일 시작 — 홈 목표 칩·TrainingGuideEngine과 같은 정의)

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        return calendar
    }
}
