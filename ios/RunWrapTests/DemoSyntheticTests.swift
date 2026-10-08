import Foundation
import Testing
@testable import RunWrap

/// 데모 합성 결정성 (이슈 #102) — 같은 세션을 다시 열면 합성 상세(지도·수치)가 같아야 한다.
/// 시드가 run.id.hashValue(프로세스마다 다름)가 아니라 uuid·시작 시각의 FNV-1a라는 것과,
/// DemoData.runs가 접근마다 새 UUID·시각을 만들지 않는다는 것을 확인한다.
@MainActor
struct DemoSyntheticTests {
    /// 고정 시각 — 합성 시드는 시작 시각 비트를 섞으므로 now를 주입해 결정론적으로 만든다
    private let now = ISO8601DateFormatter().date(from: "2026-09-30T07:00:00+09:00")!

    private let profile = HeartRateProfile(hrMax: 190, hrMaxSource: .fallback,
                                           restingHR: nil, zoneMethod: .percentMax)

    private func run(id: UUID = DemoData.demoID(7), indoor: Bool = false) -> RunSummary {
        RunSummary(id: id, start: now, durationSec: 3_000, distanceMeters: 8_000,
                   avgHeartRate: 150, maxHeartRate: 176, isIndoor: indoor)
    }

    @Test("합성 시드 — 같은 uuid·시작 시각이면 같은 값, 하나라도 다르면 다른 값")
    func seedIsDeterministic() {
        let seed = WorkoutDetailStore.syntheticSeed(for: run())
        #expect(seed == WorkoutDetailStore.syntheticSeed(for: run()))
        #expect(seed != WorkoutDetailStore.syntheticSeed(for: run(id: DemoData.demoID(8))))
        let later = RunSummary(id: DemoData.demoID(7), start: now.addingTimeInterval(1),
                               durationSec: 3_000, distanceMeters: 8_000, avgHeartRate: 150)
        #expect(seed != WorkoutDetailStore.syntheticSeed(for: later))
    }

    @Test("합성 시드 — FNV-1a 64비트 고정값 (hashValue처럼 실행마다 바뀌지 않는다)")
    func seedMatchesReferenceFNV1a() {
        // 입력: uuid 00…07 16바이트 + 1_790_719_200.0(2026-09-29T22:00Z)의 Double 비트 LE 8바이트.
        // 표준 FNV-1a 64비트(basis 0xcbf29ce484222325, prime 0x100000001b3)를 파이썬으로 따로 계산한 값
        #expect(WorkoutDetailStore.syntheticSeed(for: run()) == 0x4599_1F7D_1BE8_9A8D)
    }

    @Test("같은 세션으로 합성 상세를 두 번 만들면 경로·스플릿·존이 같다")
    func syntheticDetailIsStable() {
        let first = WorkoutDetailStore.synthetic(for: run(), heartRate: profile)
        let second = WorkoutDetailStore.synthetic(for: run(), heartRate: profile)
        #expect(!first.route.isEmpty)
        #expect(first.route.map(\.lat) == second.route.map(\.lat))
        #expect(first.route.map(\.lon) == second.route.map(\.lon))
        // 경로 시각은 시작 시각부터 일정 간격 (#222 선행 — GPX 재료)
        #expect(first.route.first?.time == now)
        #expect(first.route.map(\.time) == second.route.map(\.time))
        #expect(first.splits.map(\.paceSecPerKm) == second.splits.map(\.paceSecPerKm))
        #expect(first.zones == second.zones)
        #expect(first.cadenceSpm == second.cadenceSpm)
    }

    @Test("고도 프로필 — 합성 경로의 오르내림 폭이 상승 고도와 맞는다")
    func syntheticElevationMatchesAscent() throws {
        // 한 번 오르고 내리는 언덕이라 프로필 최고 − 최저 ≈ 상승 고도 (60등분 표본이라 꼭대기 근처 오차 1m 안)
        let detail = WorkoutDetailStore.synthetic(for: run(), heartRate: profile)
        let elevations = try #require(RoutePaceEngine.elevationProfile(detail.route)).map(\.elevationM)
        let ascent = try #require(detail.elevationM)
        #expect(abs((elevations.max()! - elevations.min()!) - ascent) < 1)
        // 실내 세션은 경로가 없어 프로필도 없다
        #expect(RoutePaceEngine.elevationProfile(
            WorkoutDetailStore.synthetic(for: run(indoor: true), heartRate: profile).route) == nil)
    }

    @Test("DemoData.runs — 두 번 읽어도 같은 목록(id·시작 시각), id는 인덱스 기반이며 중복 없다")
    func demoRunsAreStable() {
        let a = DemoData.runs
        let b = DemoData.runs
        #expect(a == b)
        #expect(Set(a.map(\.id)).count == a.count)
        #expect(a.contains { $0.id == DemoData.pausedRunID })
        #expect(DemoData.demoID(2).uuidString == "00000000-0000-0000-0000-000000000002")
        #expect(a.contains { $0.id == DemoData.demoID(2) })
    }
}
