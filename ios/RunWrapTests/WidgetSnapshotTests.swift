import Foundation
import Testing
@testable import RunWrap

/// 위젯 스냅샷(이슈 #181) — 순수 생성 함수·신선도·App Group 저장소 왕복 검증.
/// now = 2026-08-13T09:00:00Z 고정. 주간 창은 `Calendar.current` 기준 6일 전 자정 ~ now라
/// 기기 시간대에 따라 경계 시각이 달라지므로, 경계는 같은 식으로 계산해 기대값을 만든다.
@Suite("위젯 스냅샷")
struct WidgetSnapshotTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    private func run(at start: Date, km: Double?) -> RunSummary {
        RunSummary(id: UUID(), start: start, durationSec: 1_800,
                   distanceMeters: km.map { $0 * 1_000 }, avgHeartRate: 150)
    }

    private func battery(_ tone: RRTone, level: Int, statusLabel: String) -> BatteryReport {
        BatteryReport(level: level, tone: tone, statusLabel: statusLabel, headline: "", factors: [])
    }

    private var sampleSnapshot: WidgetSnapshot {
        WidgetSnapshot(generatedAt: now, batteryLevel: 72, batteryTone: .steady,
                       batteryLabel: "충전 충분", headline: "평소대로 가셔도 돼요",
                       weekKm: 12.4, runCount: 3, showsDistanceNumbers: true)
    }

    // MARK: make

    @Test("배터리 있음 — 수준·톤·상태 라벨을 싣고 헤드라인은 홈 판단 카드와 같은 문장")
    func makeWithBattery() {
        let report = battery(.caution, level: 38, statusLabel: "주의")
        let snapshot = WidgetSnapshot.make(battery: report, runs: [], level: .intermediate, now: now)
        let (tone, headline) = TodayVerdictEngine.headline(battery: report)

        #expect(snapshot.generatedAt == now)
        #expect(snapshot.batteryLevel == 38)
        #expect(snapshot.batteryTone == .caution)
        #expect(snapshot.batteryTone == tone)
        #expect(snapshot.batteryLabel == "주의")
        #expect(snapshot.headline == headline)
        #expect(snapshot.headline == "가볍게만 다녀오세요")
    }

    @Test("배터리 없음 — 배터리 세 필드는 nil, 헤드라인은 판정 없는 중립 문구")
    func makeWithoutBattery() {
        let snapshot = WidgetSnapshot.make(battery: nil, runs: [], level: .intermediate, now: now)
        #expect(snapshot.batteryLevel == nil)
        #expect(snapshot.batteryTone == nil)
        #expect(snapshot.batteryLabel == nil)
        #expect(snapshot.headline == "오늘은 어떻게 가실까요")
    }

    @Test("대기질 — 헤드라인에 홈 판단 카드와 같은 대기질 상한이 걸린다 (이슈 #183)")
    func makeWithAir() {
        let report = battery(.improving, level: 84, statusLabel: "충전 충분")
        let snapshot = WidgetSnapshot.make(battery: report, runs: [], level: .intermediate,
                                           air: .bad, now: now)
        #expect(snapshot.headline == "공기가 나빠요, 가볍게만 다녀오세요")
        // 배터리 필드는 대기질과 무관하게 배터리 값 그대로
        #expect(snapshot.batteryTone == .improving)
    }

    @Test("주간 창 — 6일 전 자정 직후는 포함, 7일 전·now 이후는 제외 (ReportSnapshot과 같은 창)")
    func weekWindow() {
        let windowStart = Calendar.current.startOfDay(for: now.addingTimeInterval(-6 * 86_400))
        let runs = [
            run(at: windowStart.addingTimeInterval(60), km: 5),     // 창 시작 1분 뒤 → 포함
            run(at: now.addingTimeInterval(-3_600), km: 7.4),       // 1시간 전 → 포함
            run(at: now.addingTimeInterval(-86_400), km: nil),      // 어제, 거리 없음 → 횟수만 포함
            run(at: now.addingTimeInterval(-7 * 86_400), km: 10),   // 7일 전 → 창 시작보다 앞이라 제외
            run(at: now.addingTimeInterval(3_600), km: 20),         // now 이후 → 제외
        ]
        let snapshot = WidgetSnapshot.make(battery: nil, runs: runs, level: .intermediate, now: now)
        // 거리 5 + 7.4 = 12.4km (거리 없는 세션은 합에서 빠진다), 횟수 3회
        #expect(abs(snapshot.weekKm - 12.4) < 1e-9)
        #expect(snapshot.runCount == 3)
    }

    @Test("레벨 게이트 — 런린이는 거리 수치를 숨기고 런잘알은 보인다 (ReportGate.showsNumbers)")
    func levelGate() {
        #expect(!WidgetSnapshot.make(battery: nil, runs: [], level: .beginner, now: now).showsDistanceNumbers)
        #expect(WidgetSnapshot.make(battery: nil, runs: [], level: .intermediate, now: now).showsDistanceNumbers)
    }

    // MARK: 신선도

    @Test("신선도 — 24시간 이내는 신선, 24시간을 넘기면 오래됨")
    func staleness() {
        let snapshot = sampleSnapshot
        // 23h59m = 86_340초 뒤 → 24h(86_400초) 이내
        #expect(!snapshot.isStale(at: now.addingTimeInterval(23 * 3_600 + 59 * 60)))
        // 24h + 1초 = 86_401초 뒤 → 초과
        #expect(snapshot.isStale(at: now.addingTimeInterval(24 * 3_600 + 1)))
    }

    // MARK: 저장소

    private func makeTempDirectory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-widget-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("저장소 왕복 — 저장한 스냅샷을 그대로 읽고, 비우면 nil")
    func storeRoundTripAndClear() throws {
        let dir = try makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(WidgetSnapshotStore.load(from: dir) == nil)
        WidgetSnapshotStore.save(sampleSnapshot, in: dir)
        #expect(WidgetSnapshotStore.load(from: dir) == sampleSnapshot)

        // 배터리 nil 스냅샷도 왕복한다 (옵셔널 톤 인코딩)
        let noBattery = WidgetSnapshot.make(battery: nil, runs: [], level: .beginner, now: now)
        WidgetSnapshotStore.save(noBattery, in: dir)
        #expect(WidgetSnapshotStore.load(from: dir) == noBattery)

        WidgetSnapshotStore.clear(in: dir)
        #expect(WidgetSnapshotStore.load(from: dir) == nil)
        // 파일이 없을 때 다시 지워도 조용히 넘어간다
        WidgetSnapshotStore.clear(in: dir)
    }

    @Test("저장소 — 깨진 파일은 nil로 읽는다 (위젯은 안내 문구로 떨어진다)")
    func storeCorruptFile() throws {
        let dir = try makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }

        try Data("{\"generatedAt\": ".utf8)
            .write(to: dir.appendingPathComponent(WidgetSnapshotStore.filename))
        #expect(WidgetSnapshotStore.load(from: dir) == nil)
    }
}
