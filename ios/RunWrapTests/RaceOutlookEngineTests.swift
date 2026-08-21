import Foundation
import Testing
@testable import RunWrap

/// 대회 목표 상태 엔진 검증 (이슈 #21, 표본 규칙 개편은 이슈 #24). now = 2026-08-10T09:00:00Z 고정.
/// 예측 재료: 7일 전 5km 22:00(1,320초) 세션 → Riegel 10K 예측 1320×(10/5)^1.06 ≈ 2752.1초.
struct RaceOutlookEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    /// 5km 22:00(1,320초) 세션 — daysAgo로 1주·4주·8주 창 안팎을 오간다
    private func run5K(daysAgo: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: 1_320, distanceMeters: 5_000, avgHeartRate: 165)
    }

    /// 거리별 외삽 상한의 통합 경로를 검증하기 위한 임의 거리 세션
    private func run(km: Double, timeSec: Double, daysAgo: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: timeSec, distanceMeters: km * 1_000, avgHeartRate: 165)
    }

    /// ready 케이스 언래핑 헬퍼 — 다른 상태면 nil이라 #require가 실패를 찍는다
    private func ready(_ status: RaceOutlookEngine.Status) -> RaceOutlookEngine.Outlook? {
        if case .ready(let outlook) = status { outlook } else { nil }
    }

    @Test("미설정 가드 — 레이스·기록·날짜 중 하나라도 없으면 notConfigured")
    func notConfigured() {
        let date = now.addingTimeInterval(20 * 86_400)
        #expect(RaceOutlookEngine.status(race: nil, goalSec: 2_700, raceDate: date,
                                         runs: [], now: now) == .notConfigured)
        #expect(RaceOutlookEngine.status(race: .tenK, goalSec: 0, raceDate: date,
                                         runs: [], now: now) == .notConfigured)
        #expect(RaceOutlookEngine.status(race: .tenK, goalSec: 2_700, raceDate: nil,
                                         runs: [], now: now) == .notConfigured)
    }

    @Test("대회 종료 — 어제 대회는 raceFinished, 대회 당일은 아직 유효(D-day 0)")
    func raceFinished() {
        #expect(RaceOutlookEngine.status(race: .tenK, goalSec: 2_700,
                                         raceDate: now.addingTimeInterval(-86_400),
                                         runs: [], now: now)
            == .raceFinished(race: .tenK))
        // 자정 경계 기준이라 같은 날은 시각과 무관하게 0일 — 종료가 아니다
        #expect(RaceOutlookEngine.status(race: .tenK, goalSec: 2_700, raceDate: now,
                                         runs: [], now: now)
            == .awaitingRecords(race: .tenK, daysToRace: 0))
    }

    @Test("기록 대기 — 최근 8주 세션이 없으면 예측 없이 D-day만 낸다")
    func awaitingRecords() {
        // 60일 전 세션은 가장 넓은 창(56일) 밖 → Riegel 재료가 아니다
        let status = RaceOutlookEngine.status(race: .tenK, goalSec: 2_700,
                                              raceDate: now.addingTimeInterval(20 * 86_400),
                                              runs: [run5K(daysAgo: 60)], now: now)
        #expect(status == .awaitingRecords(race: .tenK, daysToRace: 20))
    }

    @Test("외삽 상한 통합 경로 — 풀코스는 5km로 대기, 14.1km로 예측 가능")
    func fullMarathonExtrapolationBoundary() throws {
        let raceDate = now.addingTimeInterval(20 * 86_400)
        let short = RaceOutlookEngine.status(
            race: .full, goalSec: 14_400, raceDate: raceDate,
            runs: [run(km: 5.0, timeSec: 1_500, daysAgo: 3)], now: now)
        #expect(short == .awaitingRecords(race: .full, daysToRace: 20))

        // 42.195/14.1 = 2.99배 — 3배 상한 안쪽이라 ready가 된다
        let long = RaceOutlookEngine.status(
            race: .full, goalSec: 14_400, raceDate: raceDate,
            runs: [run(km: 14.1, timeSec: 5_076, daysAgo: 3)], now: now)
        let outlook = try #require(ready(long))
        #expect(outlook.sampleWindowDays == 7)
    }

    @Test("예측 — 선선한 달(1월)은 열 보정 없이 Riegel 그대로")
    func readyCoolMonth() throws {
        // 1월 평년(−1.9°C·56%)은 열 점수가 38 미만 → 보정 0.
        // Riegel 2752.1초는 목표 45:00(2,700초) 초과·+5%(2,835초) 이내 → steady
        let raceDate = ISO8601DateFormatter().date(from: "2027-01-15T09:00:00Z")!
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 2_700, raceDate: raceDate,
            runs: [run5K(daysAgo: 7)], now: now)))
        #expect(outlook.heatDeltaSecPerKm == 0)
        #expect(abs(outlook.predictedSec - 2_752.1) < 1)
        #expect(outlook.raceMonth == 1)
        #expect(outlook.tone == .steady)
    }

    @Test("예측 — 더운 달(8월)은 평년 열 보정을 페이스에 더한다")
    func readyHotMonth() throws {
        // 8월 평년 26.1°C·74% → 이슬점 21.1, 열 점수 47.2 → 보정 8×1.5 + 1.2×3.0 ≈ 15.6초/km.
        // 페이스 275.2+15.6 = 290.8초/km, 완주 2,908.2초. D-day: 8/10 → 8/30 = 20일
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 3_000, raceDate: now.addingTimeInterval(20 * 86_400),
            runs: [run5K(daysAgo: 7)], now: now)))
        #expect(outlook.daysToRace == 20)
        #expect(outlook.raceMonth == 8)
        #expect(abs(outlook.heatDeltaSecPerKm - 15.6) < 0.1)
        #expect(abs(outlook.predictedPaceSecPerKm - 290.8) < 0.1)
        #expect(abs(outlook.predictedSec - 2_908.2) < 1)
    }

    @Test("톤 — 목표 대비 달성권 improving / 5% 이내 steady / 그 밖 caution")
    func tones() throws {
        // 8월 10K 예상 2,908.2초 고정 — 목표만 바꿔 경계를 확인한다
        func tone(goal: Double) throws -> RRTone {
            try #require(ready(RaceOutlookEngine.status(
                race: .tenK, goalSec: goal, raceDate: now.addingTimeInterval(20 * 86_400),
                runs: [run5K(daysAgo: 7)], now: now))).tone
        }
        #expect(try tone(goal: 3_000) == .improving)  // 2908 ≤ 3000
        #expect(try tone(goal: 2_800) == .steady)     // 2800 < 2908 ≤ 2940 (+5%)
        #expect(try tone(goal: 2_700) == .caution)    // 2908 > 2835 (+5%)
    }

    // MARK: - 표본 창 노출 (이슈 #24)

    @Test("표본 창 — 1주 안에 세션이 있으면 sampleWindowDays는 7")
    func sampleWindowOneWeek() throws {
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 2_700, raceDate: now.addingTimeInterval(20 * 86_400),
            runs: [run5K(daysAgo: 3)], now: now)))
        #expect(outlook.sampleWindowDays == 7)
    }

    @Test("표본 창 — 1주에 없고 4주 안에만 있으면 28로 폴백")
    func sampleWindowFallback() throws {
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 2_700, raceDate: now.addingTimeInterval(20 * 86_400),
            runs: [run5K(daysAgo: 20)], now: now)))
        #expect(outlook.sampleWindowDays == 28)
    }
}
