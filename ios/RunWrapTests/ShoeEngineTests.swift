import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import RunWrap

/// 러닝화 마일리지 엔진·캐시 검증 (이슈 #171) — 누적 합산, 자동 배정, 교체 경계, 진행 비율,
/// 러닝화 묻기 대상(이슈 #206), shoes.json 왕복·격리·옛 스키마, 사진 저장. now = 2026-09-30T09:00:00Z 고정.
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

    // MARK: - 러닝화 묻기 대상 (이슈 #206)

    @Test("기준 시각이 없으면(첫 실행) 빈 배열 — 지난 기록을 묻지 않는다")
    func pendingRunsWithoutBaselineIsEmpty() {
        let runs = [run(km: 5, daysAgo: 1), run(km: 6, daysAgo: 2)]
        #expect(ShoeEngine.pendingRuns(runs: runs, promptedThrough: nil, now: now).isEmpty)
    }

    @Test("기준 시각보다 늦게 시작한 러닝만 — 기준과 같은 시각은 이미 물어본 러닝이라 뺀다")
    func pendingRunsOnlyAfterBaseline() {
        let old = run(km: 5, daysAgo: 5)
        let asked = run(km: 6, daysAgo: 3)
        let new = run(km: 7, daysAgo: 1)
        let result = ShoeEngine.pendingRuns(runs: [old, asked, new], promptedThrough: asked.start, now: now)
        #expect(result.map(\.id) == [new.id])
    }

    @Test("14일 창 — 14일 전 경계는 포함, 15일 전은 기준 이후여도 뺀다")
    func pendingRunsRespectsWindow() {
        let outside = run(km: 5, daysAgo: 15)
        let edge = run(km: 6, daysAgo: 14)   // now - 14일 정각 = 창 시작(>=)
        let inside = run(km: 7, daysAgo: 2)
        let result = ShoeEngine.pendingRuns(runs: [outside, edge, inside],
                                            promptedThrough: now.addingTimeInterval(-30 * 86_400), now: now)
        #expect(result.map(\.id) == [edge.id, inside.id])
    }

    @Test("12개면 최근 10개만, 오래된 순 — 1~12일 전 중 10~1일 전이 남는다")
    func pendingRunsCapsToMostRecentOldestFirst() {
        // 입력은 최근 순(1일 전 → 12일 전)으로 섞어 넣어 정렬을 함께 확인한다
        let runs = (1...12).map { run(km: Double($0), daysAgo: Double($0)) }
        let result = ShoeEngine.pendingRuns(runs: runs,
                                            promptedThrough: now.addingTimeInterval(-13 * 86_400), now: now)
        #expect(result.count == ShoeEngine.promptMaxCount)
        // 11·12일 전 2개가 잘리고 10일 전부터 1일 전까지 시작 시각 오름차순
        #expect(result.map(\.id) == runs[0..<10].reversed().map(\.id))
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

    @Test("옛 shoes.json(imageFile 없음)도 읽힌다 — 스키마 규칙 #66, imageFile은 nil")
    func decodesLegacyFileWithoutImage() throws {
        // 이슈 #206 이전 앱이 쓴 모양. createdAt은 JSONEncoder 기본(2001-01-01 기준 초)
        let json = """
        {"schemaVersion":1,"defaultShoeID":"6F1C3A52-0D3B-4C4B-9E58-2B7A1F0C9D11",
         "shoes":[{"id":"6F1C3A52-0D3B-4C4B-9E58-2B7A1F0C9D11","name":"페가수스 41",
                   "startKm":480,"replaceKm":600,"isRetired":false,"createdAt":0}],
         "assignments":{}}
        """
        let file = try JSONDecoder().decode(ShoeFile.self, from: Data(json.utf8))
        let shoe = try #require(file.shoes.first)
        #expect(shoe.name == "페가수스 41")
        #expect(shoe.startKm == 480)
        #expect(shoe.imageFile == nil)
    }

    // MARK: - 사진 저장 (이슈 #206)

    @Test("사진 왕복 — 1200×800을 긴 변 600(600×400) JPEG로 줄여 <id>.jpg에 두고, 지우면 사라진다")
    func imageStoreRoundTrip() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = UUID()
        let file = try #require(ShoeImageStore.save(try pngData(width: 1_200, height: 800), for: id, in: dir))
        #expect(file.hasPrefix(id.uuidString) && file.hasSuffix(".jpg"))
        let url = try #require(ShoeImageStore.url(for: file, in: dir))
        let source = try #require(CGImageSourceCreateWithURL(url as CFURL, nil))
        let props = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        #expect(props[kCGImagePropertyPixelWidth] as? Int == 600)
        #expect(props[kCGImagePropertyPixelHeight] as? Int == 400)

        ShoeImageStore.remove(file, in: dir)
        #expect(ShoeImageStore.url(for: file, in: dir) == nil)
    }

    @Test("이미지가 아닌 데이터는 저장하지 않는다")
    func imageStoreRejectsNonImage() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(ShoeImageStore.save(Data("not an image".utf8), for: UUID(), in: dir) == nil)
    }

    /// UIKit 없이 CoreGraphics로 단색 PNG를 만든다
    private func pngData(width: Int, height: Int) throws -> Data {
        let context = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                             bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(red: 1, green: 0.3, blue: 0.18, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try #require(context.makeImage())
        let data = NSMutableData()
        let dest = try #require(CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(dest, image, nil)
        #expect(CGImageDestinationFinalize(dest))
        return data as Data
    }
}
