import Foundation
import Testing
@testable import RunWrap

/// 도감 수집 엔진 검증 — 종 매핑 경계와 사이클 전환값 (기획서 §5).
///
/// "서브3 = 백조"만 확정 축이고 나머지 경계는 초안이라(§12), 경계값이 바뀌면
/// 여기 기대값도 함께 바뀐다. 지금 고정하는 것은 **경계에서 어느 쪽으로 붙는지**다.
@Suite("도감 수집 엔진")
struct CollectionEngineTests {

    /// 사이클 시작 다음 날의 러닝 한 건
    private static func run(km: Double, seconds: Double, daysAfter: Double = 1,
                            since: Date) -> RunSummary {
        RunSummary(id: UUID(), start: since.addingTimeInterval(daysAfter * 86_400),
                   durationSec: seconds, distanceMeters: km * 1_000, avgHeartRate: nil)
    }

    // MARK: - 실제 기록 → 종

    @Test("기록 기준 — 5km 미만만 달렸으면 참새, 5km부터 제비, 하프부터 매")
    func earnedByDistance() throws {
        let since = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        #expect(CollectionEngine.earned(runs: [], since: since).species == .sparrow)
        #expect(CollectionEngine.earned(runs: [Self.run(km: 4.99, seconds: 1_800, since: since)],
                                        since: since).species == .sparrow)
        let swallow = CollectionEngine.earned(runs: [Self.run(km: 5, seconds: 1_800, since: since),
                                                     Self.run(km: 12.34, seconds: 4_500, since: since)],
                                              since: since)
        #expect(swallow.species == .swallow)
        // 근거 표기는 가장 긴 러닝 — 12.34km → "12.3"
        #expect(swallow.label == "최장 12.3km")
        // 하프 공식 거리(21.0975km)에 못 미치면 아직 제비
        #expect(CollectionEngine.earned(runs: [Self.run(km: 21.09, seconds: 7_200, since: since)],
                                        since: since).species == .swallow)
        let falcon = CollectionEngine.earned(runs: [Self.run(km: 21.0975, seconds: 6_730, since: since)],
                                             since: since)
        #expect(falcon.species == .falcon)
        // 6,730초 = 1:52:10
        #expect(falcon.label == "하프 1:52:10")
    }

    @Test("기록 기준 — 풀코스는 환산 기록으로 기러기·두루미·백조를 가른다")
    func earnedFullByTime() throws {
        let since = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        func species(_ seconds: Double, km: Double = 42.195) -> BirdSpecies {
            CollectionEngine.earned(runs: [Self.run(km: km, seconds: seconds, since: since)],
                                    since: since).species
        }
        #expect(species(4 * 3_600) == .goose)        // 정확히 4:00:00은 sub-4가 아니다
        #expect(species(4 * 3_600 - 1) == .crane)
        #expect(species(3 * 3_600) == .crane)        // 정확히 3:00:00은 서브3가 아니다
        #expect(species(3 * 3_600 - 1) == .swan)
        // 43km를 4:02:00(14,520초)에 달렸다 → 42.195km 환산 14,520 × 42.195 / 43 ≈ 14,248초(3:57:28) → 두루미
        #expect(species(14_520, km: 43) == .crane)
    }

    @Test("기록 기준 — 사이클 시작 전 기록과 거리 없는 기록은 세지 않는다")
    func earnedIgnoresOldRuns() throws {
        let since = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        let before = Self.run(km: 42.195, seconds: 10_000, daysAfter: -1, since: since)
        let noDistance = RunSummary(id: UUID(), start: since.addingTimeInterval(86_400),
                                    durationSec: 20_000, distanceMeters: nil, avgHeartRate: nil)
        #expect(CollectionEngine.earned(runs: [before, noDistance], since: since).species == .sparrow)
    }

    @Test("기록 기준 — 여러 번 달렸으면 가장 좋은 기록이 종을 정한다")
    func earnedTakesBest() throws {
        let since = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        let runs = [Self.run(km: 42.195, seconds: 4.5 * 3_600, since: since),
                    Self.run(km: 42.195, seconds: 3.5 * 3_600, daysAfter: 60, since: since),
                    Self.run(km: 10, seconds: 3_000, daysAfter: 70, since: since)]
        let earned = CollectionEngine.earned(runs: runs, since: since)
        #expect(earned.species == .crane)
        #expect(earned.label == "풀코스 3:30:00")
    }

    @Test("한 칸 위 종 — 백조 위는 없다")
    func nextSpecies() {
        #expect(BirdSpecies.sparrow.next == .swallow)
        #expect(BirdSpecies.crane.next == .swan)
        #expect(BirdSpecies.swan.next == nil)
    }

    // MARK: - 종 매핑

    @Test("목표가 없으면 참새 — 완주 습관 사이클")
    func noGoalIsSparrow() {
        #expect(CollectionEngine.species(for: nil, goalSeconds: 0) == .sparrow)
        // 목표 종목이 없으면 기록이 적혀 있어도 참새다
        #expect(CollectionEngine.species(for: nil, goalSeconds: 3_600) == .sparrow)
    }

    @Test("5K·10K는 제비, 하프는 매")
    func shortDistances() {
        #expect(CollectionEngine.species(for: .fiveK, goalSeconds: 0) == .swallow)
        #expect(CollectionEngine.species(for: .tenK, goalSeconds: 0) == .swallow)
        #expect(CollectionEngine.species(for: .half, goalSeconds: 0) == .falcon)
    }

    @Test("풀코스는 목표 기록이 없으면 완주로 보고 기러기")
    func fullWithoutTargetIsGoose() {
        #expect(CollectionEngine.species(for: .full, goalSeconds: 0) == .goose)
        // 음수는 미입력과 같게 다룬다
        #expect(CollectionEngine.species(for: .full, goalSeconds: -1) == .goose)
    }

    @Test("풀코스 서브3 경계 — 3시간 미만만 백조")
    func sub3Boundary() {
        let threeHours = 3 * 3_600
        // 2:59:59 → 백조
        #expect(CollectionEngine.species(for: .full, goalSeconds: threeHours - 1) == .swan)
        // 정확히 3:00:00은 "서브3"가 아니다 — 두루미로 내려간다
        #expect(CollectionEngine.species(for: .full, goalSeconds: threeHours) == .crane)
    }

    @Test("풀코스 sub-4 경계 — 4시간 미만은 두루미, 그 이상은 기러기")
    func sub4Boundary() {
        let fourHours = 4 * 3_600
        #expect(CollectionEngine.species(for: .full, goalSeconds: fourHours - 1) == .crane)
        #expect(CollectionEngine.species(for: .full, goalSeconds: fourHours) == .goose)
        #expect(CollectionEngine.species(for: .full, goalSeconds: 5 * 3_600) == .goose)
    }

    // MARK: - 목표 표기

    @Test("목표 표기 — 기록이 있으면 종목과 함께, 없으면 종목만")
    func goalLabels() {
        #expect(CollectionEngine.goalLabel(for: .full, goalSeconds: 3 * 3_600 + 30 * 60)
                == "풀코스 3:30:00")
        #expect(CollectionEngine.goalLabel(for: .half, goalSeconds: 0) == "하프")
        #expect(CollectionEngine.goalLabel(for: nil, goalSeconds: 0) == "목표 없이 완주 습관")
    }

    // MARK: - 성조 판정

    @Test("성조 판정 — 나는 새만 true")
    func adultOnlyAtFlying() {
        #expect(CollectionEngine.hasReachedAdult(stage: .flying))
        for stage in GrowthStage.allCases where stage != .flying {
            #expect(!CollectionEngine.hasReachedAdult(stage: stage))
        }
    }

    // MARK: - 수집

    @Test("수집 — 종·기록 표기·소요 일수를 그 시점 값으로 굳힌다")
    func collectFreezesValues() throws {
        let start = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        // 사이클 안에 풀코스를 3:30:00(12,600초)에 달렸다 → 두루미
        let bird = CollectionEngine.collect(runs: [Self.run(km: 42.195, seconds: 12_600, since: start)],
                                             cycleStartedAt: start, now: now)
        #expect(bird.species == .crane)
        #expect(bird.goalLabel == "풀코스 3:30:00")
        #expect(bird.collectedAt == now)
        // 2026-05-01 → 2026-08-13 = 31(5월 잔여) + 30 + 31 + 13 = 104일
        #expect(bird.cycleDays == 104)
    }

    @Test("수집 — 사이클 시작이 미래여도 소요 일수는 음수가 되지 않는다")
    func collectClampsNegativeDays() throws {
        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        let future = now.addingTimeInterval(10 * 86_400)
        let bird = CollectionEngine.collect(runs: [],
                                             cycleStartedAt: future, now: now)
        #expect(bird.cycleDays == 0)
    }

    // MARK: - 다음 목표 추천

    @Test("다음 목표 추천 — 종목을 한 칸씩 올린다")
    func recommendationClimbs() throws {
        #expect(CollectionEngine.recommendedGoal(after: nil, goalSeconds: 0)?.distance == .fiveK)
        #expect(CollectionEngine.recommendedGoal(after: .fiveK, goalSeconds: 0)?.distance == .tenK)
        #expect(CollectionEngine.recommendedGoal(after: .tenK, goalSeconds: 0)?.distance == .half)
        #expect(CollectionEngine.recommendedGoal(after: .half, goalSeconds: 0)?.distance == .full)
    }

    @Test("풀코스 이후는 기록 단축으로 방향을 튼다")
    func recommendationTightensTime() throws {
        // 기록 미입력 → 우선 sub-4 제안. 종 경계가 배타(<)라 4:00:00이 아니라 3:59:00
        let first = try #require(CollectionEngine.recommendedGoal(after: .full, goalSeconds: 0))
        #expect(first.distance == .full)
        #expect(first.seconds == 4 * 3_600 - 60)
        #expect(CollectionEngine.species(for: .full, goalSeconds: first.seconds) == .crane)

        // 4:00:00 → 30분 당겨 3:30:00
        let tighter = try #require(CollectionEngine.recommendedGoal(after: .full,
                                                                    goalSeconds: 4 * 3_600))
        #expect(tighter.seconds == 3 * 3_600 + 30 * 60)
    }

    @Test("서브3 경계 이하로 당겨지면 서브3 바로 아래(2:59:00)로 맞추고, 이미 서브3이면 추천 없음")
    func recommendationStopsAtSub3() throws {
        // 3:00:00은 서브3(< 3:00:00)이 아니다 — 30분 당긴 2:30:00 대신 2:59:00으로 맞춘다
        #expect(CollectionEngine.recommendedGoal(after: .full, goalSeconds: 3 * 3_600)?.seconds
                == 3 * 3_600 - 60)
        // 3:30:00 → 3:00:00은 경계에 걸려 종이 오르지 않는다 → 2:59:00
        #expect(CollectionEngine.recommendedGoal(after: .full,
                                                 goalSeconds: 3 * 3_600 + 30 * 60)?.seconds
                == 3 * 3_600 - 60)
        // 이미 서브3(2:59:00 이하)이면 더 올릴 종이 없다
        #expect(CollectionEngine.recommendedGoal(after: .full, goalSeconds: 3 * 3_600 - 60) == nil)
        #expect(CollectionEngine.recommendedGoal(after: .full,
                                                 goalSeconds: 2 * 3_600 + 50 * 60) == nil)
    }

    @Test("3:10:00 목표 — 30분 당기면 서브3 아래라 nil이 아니라 2:59:00(백조)을 추천한다")
    func recommendationClampsToSub3() throws {
        // 3:10:00 − 30분 = 2:40:00 ≤ 3:00:00 → 서브3 바로 아래 2:59:00으로 clamp
        let next = try #require(CollectionEngine.recommendedGoal(after: .full,
                                                                 goalSeconds: 3 * 3_600 + 10 * 60))
        #expect(next.distance == .full)
        #expect(next.seconds == 2 * 3_600 + 59 * 60)
        #expect(CollectionEngine.species(for: .full, goalSeconds: next.seconds) == .swan)
    }

    // MARK: - 세러모니 다음 목표 초기 선택 (이슈 #127)

    @Test("초기 선택 — 현재 목표가 사이클 목표와 같으면 사이클 목표 기준 추천")
    func initialNextGoalSameAsCycle() {
        // 10K 사이클 → 한 칸 올린 하프
        let pick = CollectionEngine.initialNextGoal(cycleGoal: .tenK, cycleGoalSeconds: 0,
                                                    currentGoal: .tenK, currentSeconds: 0)
        #expect(pick.distance == .half)
        #expect(pick.seconds == 0)
        // 풀 4:00:00 사이클 → 30분 당긴 3:30:00 (recommendedGoal과 같은 결과)
        let full = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 4 * 3_600,
                                                    currentGoal: .full, currentSeconds: 4 * 3_600)
        #expect(full.distance == .full)
        #expect(full.seconds == 3 * 3_600 + 30 * 60)
        // 이미 서브3이면 추천이 없어 사이클 목표를 유지한다
        let sub3 = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 2 * 3_600 + 50 * 60,
                                                    currentGoal: .full, currentSeconds: 2 * 3_600 + 50 * 60)
        #expect(sub3.distance == .full)
        #expect(sub3.seconds == 2 * 3_600 + 50 * 60)
    }

    @Test("초기 선택 — 사이클 도중 더 먼 종목으로 바꿨으면 그 목표를 그대로 둔다")
    func initialNextGoalFartherCurrent() {
        // 5K 사이클 중 풀 3:50:00으로 변경 → 추천(10K) 대신 풀 3:50:00
        let pick = CollectionEngine.initialNextGoal(cycleGoal: .fiveK, cycleGoalSeconds: 0,
                                                    currentGoal: .full, currentSeconds: 3 * 3_600 + 50 * 60)
        #expect(pick.distance == .full)
        #expect(pick.seconds == 3 * 3_600 + 50 * 60)
        // 목표 없음 사이클 중 하프로 변경 → 추천(5K) 대신 하프
        let fromNone = CollectionEngine.initialNextGoal(cycleGoal: nil, cycleGoalSeconds: 0,
                                                        currentGoal: .half, currentSeconds: 0)
        #expect(fromNone.distance == .half)
    }

    @Test("초기 선택 — 같은 종목에 더 빠른 기록으로 바꿨으면 그 목표를 그대로 둔다")
    func initialNextGoalFasterCurrent() {
        // 풀 4:00:00 사이클 중 3:45:00으로 당김 → 추천(3:30:00) 대신 3:45:00
        let pick = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 4 * 3_600,
                                                    currentGoal: .full, currentSeconds: 3 * 3_600 + 45 * 60)
        #expect(pick.distance == .full)
        #expect(pick.seconds == 3 * 3_600 + 45 * 60)
        // 기록 없는 풀 완주 사이클 중 기록 4:10:00을 입력 → 추천(3:59:00) 대신 4:10:00
        let fromFinish = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 0,
                                                          currentGoal: .full, currentSeconds: 4 * 3_600 + 10 * 60)
        #expect(fromFinish.seconds == 4 * 3_600 + 10 * 60)
    }

    @Test("초기 선택 — 현재 목표가 더 낮으면 사이클 목표 기준 추천")
    func initialNextGoalLowerCurrent() {
        // 하프 사이클 중 5K로 낮춤 → 하프 기준 추천 풀코스
        let pick = CollectionEngine.initialNextGoal(cycleGoal: .half, cycleGoalSeconds: 0,
                                                    currentGoal: .fiveK, currentSeconds: 0)
        #expect(pick.distance == .full)
        #expect(pick.seconds == 0)
        // 풀 3:30:00 사이클 중 4:00:00으로 늦춤 → 3:30:00 기준 추천 2:59:00
        let slower = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 3 * 3_600 + 30 * 60,
                                                      currentGoal: .full, currentSeconds: 4 * 3_600)
        #expect(slower.seconds == 3 * 3_600 - 60)
        // 풀 사이클 중 목표를 지움 → 풀 기준 추천 3:59:00
        let cleared = CollectionEngine.initialNextGoal(cycleGoal: .full, cycleGoalSeconds: 0,
                                                       currentGoal: nil, currentSeconds: 0)
        #expect(cleared.distance == .full)
        #expect(cleared.seconds == 4 * 3_600 - 60)
    }
}

/// 도감 저장 검증 — 임시 디렉터리를 주입해 실제 Application Support를 건드리지 않는다.
@Suite("도감 저장")
struct CollectionCacheTests {

    /// 테스트마다 고유 디렉터리 — 병렬 실행에서 서로 덮어쓰지 않게 한다
    private func makeTempDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("collection-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("저장한 도감을 그대로 읽어 온다")
    func roundTrip() throws {
        let dir = try makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }

        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        let start = now.addingTimeInterval(-30 * 86_400)
        // 사이클 안의 하프 한 번 → 매
        let half = RunSummary(id: UUID(), start: start.addingTimeInterval(86_400),
                              durationSec: 7_200, distanceMeters: 21_100, avgHeartRate: nil)
        let birds = [CollectionEngine.collect(runs: [half], cycleStartedAt: start, now: now)]
        try CollectionCache.save(birds, in: dir)

        let loaded = CollectionCache.load(from: dir)
        #expect(loaded.count == 1)
        #expect(loaded.first?.species == .falcon)
        #expect(loaded.first?.cycleDays == 30)
    }

    @Test("파일이 없으면 빈 도감 — 오류가 아니다")
    func missingFileIsEmpty() throws {
        let dir = try makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(CollectionCache.load(from: dir).isEmpty)
    }

    @Test("같은 종을 여러 사이클에서 모으면 이력이 쌓인다")
    func repeatedSpeciesAccumulate() throws {
        let dir = try makeTempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }

        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        // 첫 사이클엔 5km, 다음 사이클엔 10km — 둘 다 제비
        func run(_ meters: Double, daysAgo: Double) -> RunSummary {
            RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                       durationSec: 3_000, distanceMeters: meters, avgHeartRate: nil)
        }
        let first = CollectionEngine.collect(runs: [run(5_000, daysAgo: 45)],
                                              cycleStartedAt: now.addingTimeInterval(-60 * 86_400),
                                              now: now.addingTimeInterval(-30 * 86_400))
        let second = CollectionEngine.collect(runs: [run(5_000, daysAgo: 45), run(10_000, daysAgo: 10)],
                                               cycleStartedAt: now.addingTimeInterval(-30 * 86_400),
                                               now: now)
        try CollectionCache.save([first, second], in: dir)

        let loaded = CollectionCache.load(from: dir)
        // 둘 다 제비지만 별개 이력으로 남는다 — 도감 칸에서 ×2로 표시된다
        #expect(loaded.count == 2)
        #expect(loaded.allSatisfy { $0.species == .swallow })
        #expect(Set(loaded.map(\.id)).count == 2)
    }
}

/// 도감 스토어 수집 — 저장 성공 여부를 호출부에 돌려주는지 (이슈 #67).
/// 실패를 삼키면 홈이 사이클을 초기화해 수집한 새를 잃는다.
@Suite("도감 스토어 수집")
@MainActor
struct CollectionStoreAddTests {

    private func makeBird() throws -> CollectedBird {
        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        return CollectionEngine.collect(runs: [],
                                        cycleStartedAt: now.addingTimeInterval(-30 * 86_400),
                                        now: now)
    }

    @Test("저장에 성공하면 true — 메모리와 파일에 모두 남는다")
    func addSucceeds() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("collection-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let bird = try makeBird()
        let store = CollectionStore(directory: dir)
        #expect(store.add(bird))
        #expect(store.birds.count == 1)
        #expect(CollectionCache.load(from: dir).count == 1)
    }

    @Test("저장에 실패하면 false — 메모리에도 넣지 않아 재시도 때 중복되지 않는다")
    func addFailsWhenDirectoryIsFile() throws {
        // 디렉터리 자리에 일반 파일을 둬서 file/collection.json 쓰기가 반드시 실패하게 한다
        let notADirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("collection-store-file-\(UUID().uuidString)")
        try Data("x".utf8).write(to: notADirectory)
        defer { try? FileManager.default.removeItem(at: notADirectory) }

        let bird = try makeBird()
        let store = CollectionStore(directory: notADirectory)
        #expect(!store.add(bird))
        #expect(store.birds.isEmpty)
    }
}
