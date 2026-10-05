import Foundation
import Testing
@testable import RunWrap

/// 월간·연간 결산 리캡 검증 (이슈 #167) — 표본 가드·총량·하이라이트·PR 필터·런린이 거리 숨김·
/// 강도 배분 가드·마무리 문장 분기·홈 카드 노출 날짜 경계. 달력은 Asia/Seoul 고정.
@Suite("월간·연간 결산 리캡")
struct RecapEngineTests {
    let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }()

    /// 9월 3일 — 8월 결산을 보는 시점 (8월은 끝난 달)
    let now = ISO8601DateFormatter().date(from: "2026-09-03T09:00:00+09:00")!

    /// HRmax 200 %HRmax — 130→0.65(Z2) · 150→0.75(Z3)
    let profile = HeartRateProfile(hrMax: 200, hrMaxSource: .manual,
                                   restingHR: nil, zoneMethod: .percentMax)

    private func date(_ iso: String) -> Date {
        ISO8601DateFormatter().date(from: iso)!
    }

    private var august: RecapPeriod { .month(date("2026-08-01T00:00:00+09:00")) }

    /// "2026-08-03" 07:00 KST 러닝 — 시간 = km × 분/km × 60
    private func run(_ day: String, km: Double, minPerKm: Double) -> RunSummary {
        RunSummary(id: UUID(), start: date("\(day)T07:00:00+09:00"),
                   durationSec: km * minPerKm * 60, distanceMeters: km * 1_000, avgHeartRate: 145)
    }

    /// 8월 4회 — 총 23km · 8,160초
    /// 8/3 5km 6:00(1,800초) · 8/10 12km 6:30(4,680초) · 8/20 2km 4:00(480초) · 8/25 4km 5:00(1,200초)
    private var augustRuns: [RunSummary] {
        [run("2026-08-03", km: 5, minPerKm: 6),
         run("2026-08-10", km: 12, minPerKm: 6.5),
         run("2026-08-20", km: 2, minPerKm: 4),
         run("2026-08-25", km: 4, minPerKm: 5)]
    }

    private func compute(_ period: RecapPeriod, runs: [RunSummary],
                         efforts: BestEffortTable = [:],
                         histograms: [UUID: ZoneHistogram] = [:],
                         level: RunnerLevel = .intermediate,
                         now: Date? = nil) -> Recap? {
        RecapEngine.compute(period: period, runs: runs, efforts: efforts, histograms: histograms,
                            profile: profile, level: level, now: now ?? self.now, calendar: calendar)
    }

    // MARK: (a) 표본 가드

    @Test("표본 가드 — 그 달 러닝 2회면 nil, 3회면 값 (다음 달 1일 0시 세션은 세지 않는다)")
    func minimumRuns() throws {
        let two = [run("2026-08-03", km: 5, minPerKm: 6), run("2026-08-10", km: 5, minPerKm: 6),
                   // 9월 1일 0시 정각 — 8월 구간 [8/1, 9/1)의 끝이라 빠진다
                   RunSummary(id: UUID(), start: date("2026-09-01T00:00:00+09:00"),
                              durationSec: 1_800, distanceMeters: 5_000, avgHeartRate: 145),
                   run("2026-07-20", km: 5, minPerKm: 6)]
        #expect(compute(august, runs: two) == nil)
        #expect(!RecapEngine.hasEnoughRuns(august, runs: two, calendar: calendar))

        let three = two + [run("2026-08-31", km: 5, minPerKm: 6)]
        let recap = try #require(compute(august, runs: three))
        #expect(recap.totals.count == 3)
        #expect(RecapEngine.hasEnoughRuns(august, runs: three, calendar: calendar))
        #expect(recap.title == "2026년 8월 결산")
        #expect(recap.periodLabel == "2026년 8월")
    }

    // MARK: (b) 총량 · 하이라이트

    @Test("총량·최장 런·가장 빠른 세션 — 3km 미만은 가장 빠른 세션 후보에서 뺀다")
    func totalsAndHighlights() throws {
        let runs = augustRuns
        let recap = try #require(compute(august, runs: runs))
        #expect(abs(recap.totals.distanceKm - 23) < 1e-9)          // 5 + 12 + 2 + 4
        #expect(recap.totals.count == 4)
        #expect(abs(recap.totals.durationSec - 8_160) < 1e-9)      // 1,800 + 4,680 + 480 + 1,200
        let highlights = try #require(recap.highlights)
        #expect(highlights.longest?.id == runs[1].id)              // 12km
        // 2km 4:00이 가장 빠르지만 3km 미만 → 4km 5:00(8/25)이 가장 빠른 세션
        #expect(highlights.fastest?.id == runs[3].id)
    }

    @Test("연간 결산 — 달력 연 전체를 모으고 강도 배분은 내지 않는다")
    func yearly() throws {
        let runs = augustRuns + [run("2026-01-05", km: 10, minPerKm: 6),
                                 run("2025-12-31", km: 30, minPerKm: 6)]   // 지난해 — 빠진다
        let recap = try #require(compute(.year(date("2026-01-01T00:00:00+09:00")), runs: runs))
        #expect(recap.totals.count == 5)
        #expect(abs(recap.totals.distanceKm - 33) < 1e-9)          // 23 + 10
        #expect(recap.title == "2026년 결산")
        #expect(recap.intensity == nil)
    }

    // MARK: (c) 새 기록

    @Test("새 기록 — 기간 끝까지의 PR 중 그 기간 세션이 세운 것만, 다음 달 기록은 비교에서 뺀다")
    func recordsFilter() throws {
        let july = run("2026-07-15", km: 6, minPerKm: 5)
        let september = run("2026-09-02", km: 6, minPerKm: 4.5)
        let runs = augustRuns + [july, september]
        let efforts: BestEffortTable = [
            july.id: [1_000: 300, 5_000: 1_500],       // 5K PR은 7월 — 8월 결산에서 빠진다
            runs[0].id: [1_000: 330, 5_000: 1_600],    // 8/3 5K 1,600 > 7월 1,500 → PR 아님
            runs[1].id: [10_000: 3_500],               // 8/10 10K — 유일한 후보라 PR
            runs[3].id: [1_000: 280],                  // 8/25 1K — PB 종목이 아니라 기록에 안 잡힌다
            september.id: [1_000: 250],                // 9월 — 8월 끝 이후라 비교 대상이 아니다
        ]
        let recap = try #require(compute(august, runs: runs, efforts: efforts))
        #expect(recap.records.map(\.label) == ["10K"])
        #expect(recap.records.first?.run.id == runs[1].id)
        #expect(recap.records.first?.timeSec == 3_500)
    }

    // MARK: (d) 런린이

    @Test("런린이 — 거리 수치 숨김 플래그, 강도 배분 미노출 (ReportGate §4)")
    func beginnerHidesDistance() throws {
        let (runs, histograms) = histogramRuns(count: 8, histogram: [130: 600])
        let beginner = try #require(compute(august, runs: runs, histograms: histograms, level: .beginner))
        #expect(!beginner.totals.showsDistance)
        #expect(beginner.intensity == nil)
        let intermediate = try #require(compute(august, runs: runs, histograms: histograms))
        #expect(intermediate.totals.showsDistance)
        #expect(intermediate.intensity != nil)
    }

    // MARK: 강도 배분

    /// 8월 1일부터 하루 간격 count회 + 같은 히스토그램
    private func histogramRuns(count: Int, histogram: [Int: Double])
        -> ([RunSummary], [UUID: ZoneHistogram]) {
        let runs = (1...count).map { run(String(format: "2026-08-%02d", $0), km: 5, minPerKm: 6) }
        return (runs, Dictionary(uniqueKeysWithValues: runs.map {
            ($0.id, ZoneHistogram(secondsByBpm: histogram))
        }))
    }

    @Test("강도 배분 — 그 달 히스토그램 세션 7회면 생략, 8회면 이지 비율·톤 (ZoneDistributionEngine 경계)")
    func intensityGuardAndTone() throws {
        let (seven, sevenHist) = histogramRuns(count: 7, histogram: [130: 600])
        #expect(try #require(compute(august, runs: seven, histograms: sevenHist)).intensity == nil)

        // 130bpm(Z2) 70초 + 150bpm(Z3) 30초 × 8회 → 이지 560 / 800 = 0.70 → 주의
        let (eight, eightHist) = histogramRuns(count: 8, histogram: [130: 70, 150: 30])
        let intensity = try #require(compute(august, runs: eight, histograms: eightHist)?.intensity)
        #expect(abs(intensity.easyShare - 0.70) < 1e-9)
        #expect(intensity.tone == .caution)
        #expect(intensity.sessions == 8)
    }

    // MARK: (e) 마무리 문장

    @Test("마무리 문장 — 증가(+10% 이상)·감소(−10% 이하)·첫 결산·꾸준함 분기")
    func closingLineBranches() throws {
        // 7월 10km → 8월 23km: (23 − 10) / 10 = +130%
        let up = try #require(compute(august, runs: augustRuns + [run("2026-07-10", km: 10, minPerKm: 6)]))
        #expect(up.deltaPct.map { abs($0 - 130) < 1e-9 } == true)
        #expect(up.closingLine == "지난달보다 130% 더 달리셨습니다. 새가 살찌는 소리가 들립니다.")

        // 7월 40km → 8월 23km: −42.5%
        let down = try #require(compute(august, runs: augustRuns + [run("2026-07-10", km: 40, minPerKm: 6)]))
        #expect(down.closingLine == "쉬어 가는 달도 훈련입니다. 몸이 고마워하고 있을 겁니다.")

        // 8월 이전 기록 없음 → 첫 결산 (증감 비교 불가)
        let first = try #require(compute(august, runs: augustRuns))
        #expect(first.deltaPct == nil)
        #expect(first.closingLine == "첫 결산입니다. 시작이 반이라는 말, 오늘은 믿어 보겠습니다.")

        // 7월 22km → 8월 23km: +4.5% — ±10% 안이면 횟수로 꾸준함
        let steady = try #require(compute(august, runs: augustRuns + [run("2026-07-10", km: 22, minPerKm: 6)]))
        #expect(steady.closingLine == "4번을 달리셨습니다. 꾸준함은 배신하지 않습니다 — 새도 마찬가지고요.")

        // 연간 문구는 "지난해"·"해"
        let year = RecapPeriod.year(date("2026-01-01T00:00:00+09:00"))
        #expect(RecapEngine.closingLine(period: year, isFirst: false, deltaPct: 25, count: 90)
            == "지난해보다 25% 더 달리셨습니다. 새가 살찌는 소리가 들립니다.")
        #expect(RecapEngine.closingLine(period: year, isFirst: false, deltaPct: -30, count: 40)
            == "쉬어 가는 해도 훈련입니다. 몸이 고마워하고 있을 겁니다.")
    }

    @Test("진행 중인 달 — 지난달의 같은 경과 일수까지만 견준다")
    func inProgressComparison() throws {
        // 9월 10일 09시 = 경과 10일 → 비교 구간 8/1 ~ 8/11 0시
        let midSeptember = date("2026-09-10T09:00:00+09:00")
        let runs = [run("2026-08-05", km: 10, minPerKm: 6),
                    run("2026-08-25", km: 30, minPerKm: 6),    // 비교 구간 밖
                    run("2026-09-02", km: 4, minPerKm: 6),
                    run("2026-09-04", km: 4, minPerKm: 6),
                    run("2026-09-06", km: 4, minPerKm: 6)]
        let recap = try #require(compute(.month(date("2026-09-01T00:00:00+09:00")), runs: runs,
                                         now: midSeptember))
        // (12 − 10) / 10 = +20% (지난달 전체 40km와 견줬다면 −70%)
        #expect(recap.deltaPct.map { abs($0 - 20) < 1e-9 } == true)
    }

    // MARK: (f) 홈 카드 노출

    private func prompts(_ iso: String, month: String = "", year: String = "") -> [RecapPeriod] {
        RecapEngine.promptKinds(now: date(iso), dismissedMonth: month, dismissedYear: year,
                                calendar: calendar)
    }

    @Test("홈 카드 — 월간은 1~7일만, 연간은 12/25~1/7 (1월이면 지난해)")
    func promptDateBoundaries() {
        let lastMonth = RecapPeriod.month(date("2026-08-01T00:00:00+09:00"))
        #expect(prompts("2026-09-01T00:00:00+09:00") == [lastMonth])
        #expect(prompts("2026-09-07T23:59:00+09:00") == [lastMonth])
        #expect(prompts("2026-09-08T00:00:00+09:00").isEmpty)

        let thisYear = RecapPeriod.year(date("2026-01-01T00:00:00+09:00"))
        #expect(prompts("2026-12-24T12:00:00+09:00").isEmpty)
        #expect(prompts("2026-12-25T08:00:00+09:00") == [thisYear])
        // 1월 7일 — 지난달(12월)과 지난해(2026) 둘 다, 월간 먼저
        #expect(prompts("2027-01-07T20:00:00+09:00")
            == [.month(date("2026-12-01T00:00:00+09:00")), thisYear])
        #expect(prompts("2027-01-08T00:00:00+09:00").isEmpty)
    }

    @Test("홈 카드 — 닫은(열어 본) 기간 키와 같으면 빼고, 예전 기간 키는 무시한다")
    func promptDismissed() {
        #expect(prompts("2026-09-03T09:00:00+09:00", month: "2026-08").isEmpty)
        #expect(prompts("2026-09-03T09:00:00+09:00", month: "2026-07")
            == [.month(date("2026-08-01T00:00:00+09:00"))])
        // 1월 3일 — 지난해만 닫았으면 12월 월간은 남는다
        #expect(prompts("2027-01-03T09:00:00+09:00", year: "2026")
            == [.month(date("2026-12-01T00:00:00+09:00"))])
        #expect(prompts("2027-01-03T09:00:00+09:00", month: "2026-12", year: "2026").isEmpty)
    }

    @Test("닫힘 키 형식 — 월간 yyyy-MM, 연간 yyyy")
    func dismissKeyFormat() {
        #expect(RecapEngine.dismissKey(for: .month(date("2026-08-15T12:00:00+09:00")), calendar: calendar)
            == "2026-08")
        #expect(RecapEngine.dismissKey(for: .year(date("2026-08-15T12:00:00+09:00")), calendar: calendar)
            == "2026")
    }
}
