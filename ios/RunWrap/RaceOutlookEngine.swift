import Foundation

/// 대회 목표 상태 엔진 (이슈 #21) — 목표 레이스·목표 기록·대회 날짜 설정 상태에 따라
/// 체력 배터리 카드 하단에 보여줄 내용을 결정한다. D-day와 열 보정 예상 완주 기록·
/// 평균 페이스가 재료다. 순수 로직: now를 주입받아 결정론적이다.
///
/// 산식:
/// - 완주 예측: TrainingGuideEngine.racePrediction (이슈 #34) — 실제 러닝 세션 기준
///   **구간**으로 낸다. 느린 끝은 Riegel("훈련 페이스 그대로"), 빠른 끝은 대회 노력도(EF)
///   환산("대회 강도로 뛴다면"). 표본 창은 대회 예측 전용(4주 우선 → 12주,
///   테이퍼 중엔 12주만)이고, 오래된 표본은 VO₂max 추세 비율로 현재 체력에 맞춘다.
/// - 열 보정은 **양방향**이다 (이슈 #33): ① 표본은 세션 당시 더위를 제거해 중립 조건으로
///   환산하고(racePrediction 안의 neutralTimeSec), ② 중립 기준 예상 페이스에 대회 월의
///   평년 더위(기상청 서울 1991–2020 평년값 상수표)를 **더한다**. ①이 없으면 한여름 훈련의
///   더위 페널티가 선선한 가을 대회 예측에 그대로 실린다.
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
        /// 구간의 느린 끝 — "훈련 페이스 그대로"의 Riegel 예측 (열 보정 포함)
        let predictedSec: Double
        let predictedPaceSecPerKm: Double
        /// 구간의 빠른 끝 — "대회 노력도로 뛴다면"의 EF 환산 (열 보정 포함).
        /// 심박·HRmax가 없으면 nil → 화면은 느린 끝 단일 값만 보여준다 (이슈 #34)
        let predictedFastSec: Double?
        let heatDeltaSecPerKm: Double     // 0이면 열 보정 없음 (평년 기준 선선한 달)
        /// 표본 세션의 더위를 제거하며 빠진 보정량(초/km) — 0이면 선선한 날·실내 세션 (이슈 #33)
        let sampleHeatDeltaSecPerKm: Double
        /// VO₂max 추세 보정 배율 — 1.0이면 보정 없음. 근거 문구용 (이슈 #34)
        let fitnessRatio: Double
        let raceMonth: Int                // 1~12 — 열 보정 근거 문구용
        let goalSec: Double
        let tone: RRTone                  // 목표 대비: 달성권 improving / 5% 이내 steady / 그 밖 caution
        /// 표본을 찾은 창(일) — 28·84. 화면이 근거 시점을 밝힌다 (이슈 #24·#34)
        let sampleWindowDays: Int
    }

    /// 서울 월별 평년값 (기상청 1991–2020, 평균기온 °C · 상대습도 %).
    /// 대회 장소를 모르는 채로 쓰는 근사치라 "대략"이라는 전제를 화면 문구에 남긴다.
    static let monthlyNormals: [(tempC: Double, humidityPct: Double)] = [
        (-1.9, 56), (0.7, 55), (6.1, 55), (12.6, 55), (18.2, 60), (22.7, 66),
        (25.3, 76), (26.1, 74), (21.6, 69), (15.0, 63), (7.5, 60), (0.2, 57),
    ]

    static func status(race: RaceDistance?, goalSec: Double, raceDate: Date?,
                       runs: [RunSummary], now: Date,
                       hrMaxBpm: Double? = nil,
                       vo2MaxSamples: [(date: Date, value: Double)] = []) -> Status {
        guard let race, goalSec > 0, let raceDate else { return .notConfigured }
        guard let days = days(from: now, to: raceDate), days >= 0 else {
            return .raceFinished(race: race)
        }
        guard let best = TrainingGuideEngine.racePrediction(
            for: race, runs: runs, now: now, daysToRace: days,
            hrMaxBpm: hrMaxBpm, vo2MaxSamples: vo2MaxSamples) else {
            return .awaitingRecords(race: race, daysToRace: days)
        }
        // riegelSec·effortSec는 이미 열 중립 환산 기준이다 (이슈 #33) — 여기서는 대회일 더위만 더한다
        let neutralPace = best.riegelSec / race.km
        let month = Calendar.current.component(.month, from: raceDate)
        let normal = monthlyNormals[month - 1]
        // 보정량은 중립 기준 목표 페이스로 계산한다 — 훈련 더위가 섞인 페이스를 넣으면
        // 보정량 자체가 잘못된 기준으로 계산된다 (이슈 #33).
        // 열 점수가 낮거나 보정량이 노이즈 바닥(3초/km) 미만이면 nil → 보정 0
        let delta = HeatEngine.adjustment(paceSecPerKm: neutralPace,
                                          tempC: normal.tempC,
                                          humidityPct: normal.humidityPct)?.deltaSecPerKm ?? 0
        let pace = neutralPace + delta
        let predicted = pace * race.km
        // 빠른 끝에도 같은 보정량(초/km)을 더한다 — HeatEngine 보정량은 열 점수 기반이라
        // 페이스와 무관하게 동일하다
        let predictedFast = best.effortSec.map { $0 + delta * race.km }

        // 톤 — 느린 끝이 목표 안이면 확실한 달성권(improving),
        // 빠른 끝만 목표 안이면 "대회 노력이면 가능"(steady) (이슈 #34)
        let tone: RRTone = predicted <= goalSec ? .improving
            : (predictedFast ?? predicted) <= goalSec || predicted <= goalSec * 1.05 ? .steady
            : .caution
        return .ready(Outlook(race: race, daysToRace: days,
                              predictedSec: predicted, predictedPaceSecPerKm: pace,
                              predictedFastSec: predictedFast,
                              heatDeltaSecPerKm: delta,
                              sampleHeatDeltaSecPerKm: best.sample.heatDeltaSecPerKm,
                              fitnessRatio: best.fitnessRatio,
                              raceMonth: month,
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
