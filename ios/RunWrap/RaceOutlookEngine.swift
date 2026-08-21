import Foundation

/// 대회 목표 상태 엔진 (이슈 #21) — 목표 레이스·목표 기록·대회 날짜 설정 상태에 따라
/// 체력 배터리 카드 하단에 보여줄 내용을 결정한다. D-day와 열 보정 예상 완주 기록·
/// 평균 페이스가 재료다. 순수 로직: now를 주입받아 결정론적이다.
///
/// 산식:
/// - 완주 예측: Riegel 공식 (TrainingGuideEngine.predictedTime) — 실제 러닝 세션 기준.
///   표본 창은 최근 1주 우선, 없을 때만 4주→8주로 넓힌다 (이슈 #24).
/// - 열 보정: 대회 월의 평년기온·습도(기상청 서울 1991–2020 평년값 상수표)를
///   HeatEngine에 넣어 보정량(초/km)을 구하고 예상 페이스에 **더한다**.
///   HeatEngine.adjustment는 더운 날 기록을 시원한 날 기준으로 환산(빼기)하지만,
///   여기는 반대로 현재 실력을 더운 대회일 기준으로 환산해야 하기 때문이다.
///
/// 미노출 가드는 nil 대신 Status 케이스로 표현한다 — 화면이 각 상태에 맞는
/// 안내 문구를 보여줘야 하기 때문이다("틀린 인사이트는 없느니만 못하다"는 그대로:
/// 예측은 ready일 때만 낸다).
enum RaceOutlookEngine {
    /// 배터리 카드 하단 섹션의 상태 — 화면은 이 값을 switch로 그리기만 한다
    enum Status: Equatable {
        /// 레이스·기록·날짜 중 하나라도 미설정 → 설정 안내 문구만
        case notConfigured
        /// 대회 날짜가 이미 지났다 → 다음 목표 설정 안내
        case raceFinished(race: RaceDistance)
        /// 최소 거리·목표별 3배 외삽 가드를 통과한 표본이 없어 예측 불가 → D-day만 보여준다
        case awaitingRecords(race: RaceDistance, daysToRace: Int)
        /// 예측 가능 → D-day + 예상 완주 기록·페이스
        case ready(Outlook)
    }

    struct Outlook: Equatable {
        let race: RaceDistance
        let daysToRace: Int               // 대회 당일 = 0
        let predictedSec: Double          // 열 보정 포함 예상 완주 기록
        let predictedPaceSecPerKm: Double
        let heatDeltaSecPerKm: Double     // 0이면 열 보정 없음 (평년 기준 선선한 달)
        let raceMonth: Int                // 1~12 — 열 보정 근거 문구용
        let goalSec: Double
        let tone: RRTone                  // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
        /// 표본을 찾은 창(일) — 7·28·56. 1주가 아니면 화면이 근거 시점을 밝힌다 (이슈 #24)
        let sampleWindowDays: Int
    }

    /// 서울 월별 평년값 (기상청 1991–2020, 평균기온 °C · 상대습도 %).
    /// 대회 장소를 모르는 채로 쓰는 근사치라 "대략"이라는 전제를 화면 문구에 남긴다.
    static let monthlyNormals: [(tempC: Double, humidityPct: Double)] = [
        (-1.9, 56), (0.7, 55), (6.1, 55), (12.6, 55), (18.2, 60), (22.7, 66),
        (25.3, 76), (26.1, 74), (21.6, 69), (15.0, 63), (7.5, 60), (0.2, 57),
    ]

    static func status(race: RaceDistance?, goalSec: Double, raceDate: Date?,
                       runs: [RunSummary], now: Date) -> Status {
        guard let race, goalSec > 0, let raceDate else { return .notConfigured }
        guard let days = days(from: now, to: raceDate), days >= 0 else {
            return .raceFinished(race: race)
        }
        guard let best = TrainingGuideEngine.bestPrediction(for: race, runs: runs, now: now) else {
            return .awaitingRecords(race: race, daysToRace: days)
        }
        let baseSec = best.sec

        let basePace = baseSec / race.km
        let month = Calendar.current.component(.month, from: raceDate)
        let normal = monthlyNormals[month - 1]
        // 열 점수가 낮거나 보정량이 노이즈 바닥(3초/km) 미만이면 nil → 보정 0
        let delta = HeatEngine.adjustment(paceSecPerKm: basePace,
                                          tempC: normal.tempC,
                                          humidityPct: normal.humidityPct)?.deltaSecPerKm ?? 0
        let pace = basePace + delta
        let predicted = pace * race.km

        let tone: RRTone = predicted <= goalSec ? .improving
            : predicted <= goalSec * 1.05 ? .steady
            : .caution
        return .ready(Outlook(race: race, daysToRace: days,
                              predictedSec: predicted, predictedPaceSecPerKm: pace,
                              heatDeltaSecPerKm: delta, raceMonth: month,
                              goalSec: goalSec, tone: tone,
                              sampleWindowDays: best.windowDays))
    }

    /// 날짜 차이(일) — 자정 경계 기준이라 시각과 무관하다 (TrainingGuideEngine.days와 동일 정의)
    private static func days(from: Date, to: Date) -> Int? {
        let calendar = Calendar.current
        return calendar.dateComponents([.day],
                                       from: calendar.startOfDay(for: from),
                                       to: calendar.startOfDay(for: to)).day
    }
}
