import Foundation
import Testing
@testable import RunWrap

/// 밤 수면·낮잠 분리, 기상일 기준 밤 묶기 검증 (이슈 #99, #107)
struct SleepBlocksTests {
    /// 2026-08-09 23:00 KST — 밤 수면 시작 기준 시각
    private let bedtime = ISO8601DateFormatter().date(from: "2026-08-09T14:00:00Z")!

    /// 기준 시각으로부터 시(hour) 단위 오프셋 구간
    private func interval(_ fromHour: Double, _ toHour: Double) -> SleepBlocks.Interval {
        (start: bedtime.addingTimeInterval(fromHour * 3_600),
         end: bedtime.addingTimeInterval(toHour * 3_600))
    }

    @Test("낮잠 분리 — 밤 6시간 + 같은 날 오후 낮잠 90분이면 밤 6시간만 인정")
    func napIsExcluded() {
        // 밤 23:00–05:00(6시간), 낮잠 14:00–15:30(+15h–+16.5h) → 간격 9시간 > 2시간이라 별도 블록
        // 가장 긴 블록 = 밤 6시간
        let main = SleepBlocks.mainBlock([interval(15, 16.5), interval(0, 6)])
        #expect(main.count == 1)
        #expect(main.first?.start == bedtime)
        #expect(SleepBlocks.asleepSec(main) == 6 * 3_600)
    }

    @Test("새벽 각성 — 20분 깼다 다시 잔 밤은 한 블록, 깬 시간은 합계에서 빠진다")
    func shortWakeStaysInOneBlock() {
        // 23:00–03:00(4시간) + 03:20–06:00(2시간 40분) → 간격 20분 ≤ 2시간이라 한 블록
        // 합계 4h + 2h40m = 6h40m = 24_000초
        let resumed = (start: bedtime.addingTimeInterval(15_600), end: bedtime.addingTimeInterval(25_200))
        let main = SleepBlocks.mainBlock([interval(0, 4), resumed])
        #expect(main.count == 2)
        #expect(SleepBlocks.asleepSec(main) == 24_000)
    }

    @Test("이중 기록 — 워치·아이폰이 겹쳐 기록한 구간은 한 번만 센다")
    func overlappingSourcesMerge() {
        // 워치 23:00–05:00, 아이폰 22:30–04:00(−0.5h–+5h) → 병합 22:30–05:00 = 6.5시간
        let main = SleepBlocks.mainBlock([interval(0, 6), interval(-0.5, 5)])
        #expect(main.count == 1)
        #expect(SleepBlocks.asleepSec(main) == 6.5 * 3_600)
    }

    @Test("빈 입력 — 구간이 없으면 빈 블록")
    func emptyInput() {
        #expect(SleepBlocks.mainBlock([]).isEmpty)
        #expect(SleepBlocks.nightBlocks([], calendar: seoul).isEmpty)
    }

    // MARK: - 기상일 기준 밤 묶기 (이슈 #107)

    /// 기상일 계산용 달력 — 기기 시간대와 무관하게 KST 고정
    private var seoul: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }

    /// KST 벽시계 문자열("2026-08-09T22:00") → Date
    private func kst(_ text: String) -> Date {
        ISO8601DateFormatter().date(from: text + ":00+09:00")!
    }

    private func span(_ from: String, _ to: String) -> SleepBlocks.Interval {
        (start: kst(from), end: kst(to))
    }

    @Test("자정 넘김 — 22:00–06:00을 15분 표본 32개로 쪼갠 밤은 한 블록으로 06:00의 기상일에 들어간다")
    func stageSamplesAcrossMidnightStayTogether() throws {
        // 워치 스테이지 표본처럼 15분씩 32개 → 자정 전에 끝난 8개(22:00–24:00)도 전날로 떨어지지 않는다
        let start = kst("2026-08-09T22:00")
        let samples: [SleepBlocks.Interval] = (0..<32).map { index in
            (start: start.addingTimeInterval(Double(index) * 900),
             end: start.addingTimeInterval(Double(index + 1) * 900))
        }
        let nights = SleepBlocks.nightBlocks(samples, calendar: seoul)
        #expect(nights.count == 1)
        let night = try #require(nights.first)
        #expect(night.wakeDay == kst("2026-08-10T00:00"))
        #expect(night.block.first?.start == kst("2026-08-09T22:00"))  // 취침 시각이 00:00으로 밀리지 않는다
        #expect(SleepBlocks.asleepSec(night.block) == 8 * 3_600)  // 32 × 15분 = 8시간
    }

    @Test("같은 기상일 낮잠 — 밤 7시간 + 오후 낮잠 1시간(간격 2시간 초과)이면 밤만 남는다")
    func napOnSameWakeDayIsDropped() {
        // 밤 23:00–06:00(7h), 낮잠 14:00–15:00(1h) → 간격 8시간이라 별도 블록, 둘 다 기상일 8/10
        let nights = SleepBlocks.nightBlocks([span("2026-08-10T14:00", "2026-08-10T15:00"),
                                              span("2026-08-09T23:00", "2026-08-10T06:00")],
                                             calendar: seoul)
        #expect(nights.count == 1)
        #expect(nights.first?.wakeDay == kst("2026-08-10T00:00"))
        #expect(SleepBlocks.asleepSec(nights.first?.block ?? []) == 7 * 3_600)
    }

    @Test("이틀 연속 밤 — 기상일 2개가 오름차순으로 나온다")
    func consecutiveNightsAreSortedByWakeDay() {
        // 입력은 일부러 역순 — 8/10 23:00–8/11 06:00, 8/9 23:00–8/10 06:00
        let nights = SleepBlocks.nightBlocks([span("2026-08-10T23:00", "2026-08-11T06:00"),
                                              span("2026-08-09T23:00", "2026-08-10T06:00")],
                                             calendar: seoul)
        #expect(nights.map(\.wakeDay) == [kst("2026-08-10T00:00"), kst("2026-08-11T00:00")])
        #expect(nights.map { SleepBlocks.asleepSec($0.block) } == [7 * 3_600, 7 * 3_600])
    }

    @Test("초저녁 잠 분리 — 자정 전에 끝난 잠은 자정 넘긴 밤과 다른 블록, 기상일이 같으면 긴 쪽만")
    func eveningSleepSplitsFromNight() {
        // 8/9 19:00–21:00(2h, 기상일 8/9) → 간격 2.5시간 > 2시간이라 8/9 23:30–8/10 06:30 밤(7h)과 분리
        // 8/10 20:00–22:00(2h, 기상일 8/10) → 같은 기상일의 밤(7h)보다 짧아 제외
        let nights = SleepBlocks.nightBlocks([span("2026-08-09T19:00", "2026-08-09T21:00"),
                                              span("2026-08-09T23:30", "2026-08-10T06:30"),
                                              span("2026-08-10T20:00", "2026-08-10T22:00")],
                                             calendar: seoul)
        #expect(nights.map(\.wakeDay) == [kst("2026-08-09T00:00"), kst("2026-08-10T00:00")])
        #expect(nights.map { SleepBlocks.asleepSec($0.block) } == [2 * 3_600, 7 * 3_600])
        // 밤 블록은 초저녁 잠과 합쳐지지 않아 취침 시각이 23:30으로 남는다
        #expect(nights.last?.block.first?.start == kst("2026-08-09T23:30"))
    }
}
