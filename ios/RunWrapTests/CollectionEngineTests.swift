import Foundation
import Testing
@testable import RunWrap

/// 도감 수집 엔진 검증 — 종 매핑 경계와 사이클 전환값 (기획서 §5).
///
/// "서브3 = 백조"만 확정 축이고 나머지 경계는 초안이라(§12), 경계값이 바뀌면
/// 여기 기대값도 함께 바뀐다. 지금 고정하는 것은 **경계에서 어느 쪽으로 붙는지**다.
@Suite("도감 수집 엔진")
struct CollectionEngineTests {

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

    @Test("수집 — 종·목표 표기·소요 일수를 그 시점 값으로 굳힌다")
    func collectFreezesValues() throws {
        let start = try #require(ISO8601DateFormatter().date(from: "2026-05-01T09:00:00Z"))
        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z"))
        let bird = CollectionEngine.collect(distance: .full, goalSeconds: 3 * 3_600 + 30 * 60,
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
        let bird = CollectionEngine.collect(distance: nil, goalSeconds: 0,
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
        // 기록 미입력 → 우선 sub-4 제안
        let first = try #require(CollectionEngine.recommendedGoal(after: .full, goalSeconds: 0))
        #expect(first.distance == .full)
        #expect(first.seconds == 4 * 3_600)

        // 4:00:00 → 30분 당겨 3:30:00
        let tighter = try #require(CollectionEngine.recommendedGoal(after: .full,
                                                                    goalSeconds: 4 * 3_600))
        #expect(tighter.seconds == 3 * 3_600 + 30 * 60)
    }

    @Test("서브3 아래로는 더 당기지 않는다 — 추천 없음")
    func recommendationStopsAtSub3() {
        // 3:00:00에서 30분을 당기면 2:30:00 < 서브3 → 추천하지 않는다
        #expect(CollectionEngine.recommendedGoal(after: .full, goalSeconds: 3 * 3_600) == nil)
        // 이미 서브3인 경우도 마찬가지
        #expect(CollectionEngine.recommendedGoal(after: .full,
                                                 goalSeconds: 2 * 3_600 + 50 * 60) == nil)
        // 3:30:00 → 3:00:00은 경계에 딱 걸려 허용된다
        #expect(CollectionEngine.recommendedGoal(after: .full,
                                                 goalSeconds: 3 * 3_600 + 30 * 60)?.seconds
                == 3 * 3_600)
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
        let birds = [CollectionEngine.collect(distance: .half, goalSeconds: 0,
                                               cycleStartedAt: now.addingTimeInterval(-30 * 86_400),
                                               now: now)]
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
        let first = CollectionEngine.collect(distance: .fiveK, goalSeconds: 0,
                                              cycleStartedAt: now.addingTimeInterval(-60 * 86_400),
                                              now: now.addingTimeInterval(-30 * 86_400))
        let second = CollectionEngine.collect(distance: .tenK, goalSeconds: 0,
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
