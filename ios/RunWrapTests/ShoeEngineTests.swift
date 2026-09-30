import Foundation
import Testing
@testable import RunWrap

/// 러닝화 마일리지 엔진·캐시 검증 (이슈 #171) — 누적 합산, 자동 배정, 교체 경계, 진행 비율,
/// 홈 교체 카드 판정, shoes.json 왕복·격리. now = 2026-09-30T09:00:00Z 고정.
struct ShoeEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-09-30T09:00:00Z")!

    private func shoe(startKm: Double = 0, replaceKm: Double = 600,
                      isRetired: Bool = false, daysAgo: Double = 90) -> Shoe {
        Shoe(name: "페가수스 41", startKm: startKm, replaceKm: replaceKm,
             isRetired: isRetired, createdAt: now.addingTimeInterval(-daysAgo * 86_400))
    }

    private func run(km: Double?, daysAgo: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: 3_600, distanceMeters: km.map { $0 * 1_000 }, avgHeartRate: 150)
    }

    // MARK: - 누적 거리

    @Test("누적 합산 — 등록 전 480km + 배정 3세션 12.5+8+10.2 = 510.7km, 다른 신발·미배정은 빠진다")
    func mileageSumsAssignedRuns() {
        let target = shoe(startKm: 480)
        let other = shoe()
        let runs = [run(km: 12.5, daysAgo: 1), run(km: 8, daysAgo: 3), run(km: 10.2, daysAgo: 5),
                    run(km: 20, daysAgo: 7), run(km: 15, daysAgo: 9)]
        let assignments = [runs[0].id.uuidString: target.id,
                           runs[1].id.uuidString: target.id,
                           runs[2].id.uuidString: target.id,
                           runs[3].id.uuidString: other.id]   // 20km는 다른 신발, 15km는 미배정
        let mileage = ShoeEngine.mileageKm(shoe: target, runs: runs, assignments: assignments)
        #expect(abs(mileage - 510.7) < 0.000_1)
    }

    @Test("거리 없는 세션·없음 표식은 0으로 센다")
    func mileageIgnoresMissingDistanceAndNoShoe() {
        let target = shoe(startKm: 100)
        let runs = [run(km: nil, daysAgo: 1), run(km: 5, daysAgo: 2)]
        let assignments = [runs[0].id.uuidString: target.id,
                           runs[1].id.uuidString: ShoeEngine.noShoeID]
        #expect(ShoeEngine.mileageKm(shoe: target, runs: runs, assignments: assignments) == 100)
    }

    // MARK: - 자동 배정

    @Test("자동 배정은 빈 세션만 채운다 — 다른 신발·없음 표식 배정을 덮어쓰지 않는다")
    func autoAssignKeepsExisting() {
        let defaultID = UUID()
        let otherID = UUID()
        let runs = [run(km: 5, daysAgo: 1), run(km: 6, daysAgo: 2), run(km: 7, daysAgo: 3)]
        let existing = [runs[0].id.uuidString: otherID,
                        runs[1].id.uuidString: ShoeEngine.noShoeID]
        let result = ShoeEngine.autoAssign(runs: runs, defaultShoeID: defaultID, assignments: existing)
        #expect(result[runs[0].id.uuidString] == otherID)
        #expect(result[runs[1].id.uuidString] == ShoeEngine.noShoeID)
        #expect(result[runs[2].id.uuidString] == defaultID)
        #expect(result.count == 3)
    }

    @Test("기본 신발이 없으면 배정표를 그대로 돌려준다")
    func autoAssignWithoutDefaultIsNoop() {
        let runs = [run(km: 5, daysAgo: 1), run(km: 6, daysAgo: 2)]
        let existing = [runs[0].id.uuidString: UUID()]
        #expect(ShoeEngine.autoAssign(runs: runs, defaultShoeID: nil, assignments: existing) == existing)
        #expect(ShoeEngine.autoAssign(runs: runs, defaultShoeID: nil, assignments: [:]).isEmpty)
    }

    @Test("since 이전 세션은 채우지 않는다 — 등록 전 거리는 startKm가 이미 담고 있다")
    func autoAssignRespectsSince() {
        let defaultID = UUID()
        let before = run(km: 10, daysAgo: 10)
        let after = run(km: 5, daysAgo: 2)
        let result = ShoeEngine.autoAssign(runs: [before, after], defaultShoeID: defaultID,
                                           assignments: [:],
                                           since: now.addingTimeInterval(-5 * 86_400))
        #expect(result == [after.id.uuidString: defaultID])
    }

    // MARK: - 교체 판정·진행 비율

    @Test("교체 경계 — 600 ≥ 600은 교체, 599.9는 아직")
    func needsReplacementBoundary() {
        let target = shoe(replaceKm: 600)
        #expect(ShoeEngine.needsReplacement(shoe: target, mileageKm: 600))
        #expect(!ShoeEngine.needsReplacement(shoe: target, mileageKm: 599.9))
    }

    @Test("진행 비율은 0…1로 자른다 — 300/600 = 0.5, 900/600 → 1, 음수 → 0, 기준 0 → 0")
    func progressClamps() {
        #expect(ShoeEngine.progress(mileageKm: 300, replaceKm: 600) == 0.5)
        #expect(ShoeEngine.progress(mileageKm: 900, replaceKm: 600) == 1)
        #expect(ShoeEngine.progress(mileageKm: -10, replaceKm: 600) == 0)
        #expect(ShoeEngine.progress(mileageKm: 100, replaceKm: 0) == 0)
    }

    @Test("진행 바 톤 — 90% 이상이면 주의, 미만이면 유지")
    func progressTone() {
        #expect(ShoeEngine.tone(progress: 0.9) == .caution)
        #expect(ShoeEngine.tone(progress: 0.89) == .steady)
    }

    // MARK: - 홈 교체 카드

    @Test("교체 카드 — 기본 신발이 기준 이상이면 뜨고, 같은 신발·같은 기준으로 닫으면 안 뜬다")
    func replacementAlertRespectsDismissKey() throws {
        let target = shoe(startKm: 590)
        let runs = [run(km: 12, daysAgo: 1)]
        let assignments = [runs[0].id.uuidString: target.id]
        // 590 + 12 = 602 ≥ 600
        let alert = try #require(ShoeEngine.replacementAlert(shoes: [target], defaultShoeID: target.id,
                                                             runs: runs, assignments: assignments,
                                                             dismissedKey: ""))
        #expect(alert.mileageKm == 602)
        let key = ShoeEngine.alertKey(for: target)
        #expect(key == "\(target.id.uuidString)@600")
        #expect(ShoeEngine.replacementAlert(shoes: [target], defaultShoeID: target.id, runs: runs,
                                            assignments: assignments, dismissedKey: key) == nil)
        // 기준을 바꾸면(600 → 550) 키가 달라져 다시 안내한다
        var raised = target
        raised.replaceKm = 550
        #expect(ShoeEngine.replacementAlert(shoes: [raised], defaultShoeID: raised.id, runs: runs,
                                            assignments: assignments, dismissedKey: key) != nil)
    }

    @Test("교체 카드 미노출 — 기본 신발 없음·은퇴·기준 미만")
    func replacementAlertGuards() {
        let over = shoe(startKm: 700)
        #expect(ShoeEngine.replacementAlert(shoes: [over], defaultShoeID: nil, runs: [],
                                            assignments: [:], dismissedKey: "") == nil)
        let retired = shoe(startKm: 700, isRetired: true)
        #expect(ShoeEngine.replacementAlert(shoes: [retired], defaultShoeID: retired.id, runs: [],
                                            assignments: [:], dismissedKey: "") == nil)
        let under = shoe(startKm: 599.9)
        #expect(ShoeEngine.replacementAlert(shoes: [under], defaultShoeID: under.id, runs: [],
                                            assignments: [:], dismissedKey: "") == nil)
    }

    // MARK: - 캐시

    private func makeTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("shoe-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("캐시 왕복 — 신발·기본 신발·배정표를 그대로 복원한다")
    func cacheRoundTrip() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = shoe(startKm: 480)
        let b = shoe(replaceKm: 800, isRetired: true)
        let file = ShoeFile(schemaVersion: ShoeFile.currentSchemaVersion, shoes: [a, b],
                            defaultShoeID: a.id,
                            assignments: [UUID().uuidString: a.id, UUID().uuidString: ShoeEngine.noShoeID])
        ShoeCache.save(file, in: dir)
        #expect(ShoeCache.load(from: dir, now: now) == file)
    }

    @Test("파일이 없으면 nil")
    func missingFileReturnsNil() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(ShoeCache.load(from: dir, now: now) == nil)
    }

    @Test("깨진 JSON이면 nil — 원본은 shoes.corrupt-<now>.json으로 격리")
    func corruptFileReturnsNilAndQuarantines() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = Data("{ not json".utf8)
        try original.write(to: dir.appendingPathComponent(ShoeCache.filename))

        #expect(ShoeCache.load(from: dir, now: now) == nil)
        let corrupt = dir.appendingPathComponent("shoes.corrupt-2026-09-30T09:00:00Z.json")
        #expect(try Data(contentsOf: corrupt) == original)
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent(ShoeCache.filename).path))
    }
}
