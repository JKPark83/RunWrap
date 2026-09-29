import Foundation
import Testing
@testable import RunWrap

/// 밤 수면·낮잠 분리 검증 (이슈 #99)
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
    }
}
