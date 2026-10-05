package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/// iOS CollectionEngineTests.swift의 세 @Suite를 @Nested로 옮긴다.
class CollectionEngineTests {

    private fun newID(): String = UUID.randomUUID().toString().uppercase()

    private fun later(date: Instant, seconds: Double): Instant =
        instantSince1970(date.timeIntervalSince1970 + seconds)

    /// 도감 수집 엔진 검증 — 종 매핑 경계와 사이클 전환값 (기획서 §5).
    ///
    /// "서브3 = 백조"만 확정 축이고 나머지 경계는 초안이라(§12), 경계값이 바뀌면
    /// 여기 기대값도 함께 바뀐다. 지금 고정하는 것은 **경계에서 어느 쪽으로 붙는지**다.
    @Nested
    @DisplayName("도감 수집 엔진")
    inner class EngineTests {

        /// 사이클 시작 다음 날의 러닝 한 건
        private fun run(km: Double, seconds: Double, daysAfter: Double = 1.0,
                        since: Instant): RunSummary =
            RunSummary(id = newID(), start = later(since, daysAfter * 86_400),
                       durationSec = seconds, distanceMeters = km * 1_000, avgHeartRate = null)

        // MARK: - 실제 기록 → 종

        @Test
        @DisplayName("기록 기준 — 5km 미만만 달렸으면 참새, 5km부터 제비, 하프부터 매")
        fun earnedByDistance() {
            val since = iso("2026-05-01T09:00:00Z")
            assertEquals(BirdSpecies.sparrow, CollectionEngine.earned(runs = emptyList(), since = since).species)
            assertEquals(BirdSpecies.sparrow,
                         CollectionEngine.earned(runs = listOf(run(km = 4.99, seconds = 1_800.0, since = since)),
                                                 since = since).species)
            val swallow = CollectionEngine.earned(runs = listOf(run(km = 5.0, seconds = 1_800.0, since = since),
                                                                run(km = 12.34, seconds = 4_500.0, since = since)),
                                                  since = since)
            assertEquals(BirdSpecies.swallow, swallow.species)
            // 근거 표기는 가장 긴 러닝 — 12.34km → "12.3"
            assertEquals("최장 12.3km", swallow.label)
            // 하프 공식 거리(21.0975km)에 못 미치면 아직 제비
            assertEquals(BirdSpecies.swallow,
                         CollectionEngine.earned(runs = listOf(run(km = 21.09, seconds = 7_200.0, since = since)),
                                                 since = since).species)
            val falcon = CollectionEngine.earned(runs = listOf(run(km = 21.0975, seconds = 6_730.0, since = since)),
                                                 since = since)
            assertEquals(BirdSpecies.falcon, falcon.species)
            // 6,730초 = 1:52:10
            assertEquals("하프 1:52:10", falcon.label)
        }

        @Test
        @DisplayName("기록 기준 — 풀코스는 환산 기록으로 기러기·두루미·백조를 가른다")
        fun earnedFullByTime() {
            val since = iso("2026-05-01T09:00:00Z")
            fun species(seconds: Double, km: Double = 42.195): BirdSpecies =
                CollectionEngine.earned(runs = listOf(run(km = km, seconds = seconds, since = since)),
                                        since = since).species
            assertEquals(BirdSpecies.goose, species(4.0 * 3_600))        // 정확히 4:00:00은 sub-4가 아니다
            assertEquals(BirdSpecies.crane, species(4.0 * 3_600 - 1))
            assertEquals(BirdSpecies.crane, species(3.0 * 3_600))        // 정확히 3:00:00은 서브3가 아니다
            assertEquals(BirdSpecies.swan, species(3.0 * 3_600 - 1))
            // 43km를 4:02:00(14,520초)에 달렸다 → 42.195km 환산 14,520 × 42.195 / 43 ≈ 14,248초(3:57:28) → 두루미
            assertEquals(BirdSpecies.crane, species(14_520.0, km = 43.0))
        }

        @Test
        @DisplayName("기록 기준 — 사이클 시작 전 기록과 거리 없는 기록은 세지 않는다")
        fun earnedIgnoresOldRuns() {
            val since = iso("2026-05-01T09:00:00Z")
            val before = run(km = 42.195, seconds = 10_000.0, daysAfter = -1.0, since = since)
            val noDistance = RunSummary(id = newID(), start = later(since, 86_400.0),
                                        durationSec = 20_000.0, distanceMeters = null, avgHeartRate = null)
            assertEquals(BirdSpecies.sparrow,
                         CollectionEngine.earned(runs = listOf(before, noDistance), since = since).species)
        }

        @Test
        @DisplayName("기록 기준 — 여러 번 달렸으면 가장 좋은 기록이 종을 정한다")
        fun earnedTakesBest() {
            val since = iso("2026-05-01T09:00:00Z")
            val runs = listOf(run(km = 42.195, seconds = 4.5 * 3_600, since = since),
                              run(km = 42.195, seconds = 3.5 * 3_600, daysAfter = 60.0, since = since),
                              run(km = 10.0, seconds = 3_000.0, daysAfter = 70.0, since = since))
            val earned = CollectionEngine.earned(runs = runs, since = since)
            assertEquals(BirdSpecies.crane, earned.species)
            assertEquals("풀코스 3:30:00", earned.label)
        }

        @Test
        @DisplayName("한 칸 위 종 — 백조 위는 없다")
        fun nextSpecies() {
            assertEquals(BirdSpecies.swallow, BirdSpecies.sparrow.next)
            assertEquals(BirdSpecies.swan, BirdSpecies.crane.next)
            assertNull(BirdSpecies.swan.next)
        }

        // MARK: - 종 매핑

        @Test
        @DisplayName("목표가 없으면 참새 — 완주 습관 사이클")
        fun noGoalIsSparrow() {
            assertEquals(BirdSpecies.sparrow, CollectionEngine.species(distance = null, goalSeconds = 0))
            // 목표 종목이 없으면 기록이 적혀 있어도 참새다
            assertEquals(BirdSpecies.sparrow, CollectionEngine.species(distance = null, goalSeconds = 3_600))
        }

        @Test
        @DisplayName("5K·10K는 제비, 하프는 매")
        fun shortDistances() {
            assertEquals(BirdSpecies.swallow, CollectionEngine.species(distance = RaceDistance.fiveK, goalSeconds = 0))
            assertEquals(BirdSpecies.swallow, CollectionEngine.species(distance = RaceDistance.tenK, goalSeconds = 0))
            assertEquals(BirdSpecies.falcon, CollectionEngine.species(distance = RaceDistance.half, goalSeconds = 0))
        }

        @Test
        @DisplayName("풀코스는 목표 기록이 없으면 완주로 보고 기러기")
        fun fullWithoutTargetIsGoose() {
            assertEquals(BirdSpecies.goose, CollectionEngine.species(distance = RaceDistance.full, goalSeconds = 0))
            // 음수는 미입력과 같게 다룬다
            assertEquals(BirdSpecies.goose, CollectionEngine.species(distance = RaceDistance.full, goalSeconds = -1))
        }

        @Test
        @DisplayName("풀코스 서브3 경계 — 3시간 미만만 백조")
        fun sub3Boundary() {
            val threeHours = 3 * 3_600
            // 2:59:59 → 백조
            assertEquals(BirdSpecies.swan,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = threeHours - 1))
            // 정확히 3:00:00은 "서브3"가 아니다 — 두루미로 내려간다
            assertEquals(BirdSpecies.crane,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = threeHours))
        }

        @Test
        @DisplayName("풀코스 sub-4 경계 — 4시간 미만은 두루미, 그 이상은 기러기")
        fun sub4Boundary() {
            val fourHours = 4 * 3_600
            assertEquals(BirdSpecies.crane,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = fourHours - 1))
            assertEquals(BirdSpecies.goose,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = fourHours))
            assertEquals(BirdSpecies.goose,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = 5 * 3_600))
        }

        // MARK: - 목표 표기

        @Test
        @DisplayName("목표 표기 — 기록이 있으면 종목과 함께, 없으면 종목만")
        fun goalLabels() {
            assertEquals("풀코스 3:30:00",
                         CollectionEngine.goalLabel(distance = RaceDistance.full, goalSeconds = 3 * 3_600 + 30 * 60))
            assertEquals("하프", CollectionEngine.goalLabel(distance = RaceDistance.half, goalSeconds = 0))
            assertEquals("목표 없이 완주 습관", CollectionEngine.goalLabel(distance = null, goalSeconds = 0))
        }

        // MARK: - 성조 판정

        @Test
        @DisplayName("성조 판정 — 나는 새만 true")
        fun adultOnlyAtFlying() {
            assertTrue(CollectionEngine.hasReachedAdult(stage = GrowthStage.flying))
            for (stage in GrowthStage.entries.filter { it != GrowthStage.flying }) {
                assertFalse(CollectionEngine.hasReachedAdult(stage = stage))
            }
        }

        // MARK: - 수집

        @Test
        @DisplayName("수집 — 종·기록 표기·소요 일수를 그 시점 값으로 굳힌다")
        fun collectFreezesValues() {
            val start = iso("2026-05-01T09:00:00Z")
            val now = iso("2026-08-13T09:00:00Z")
            // 사이클 안에 풀코스를 3:30:00(12,600초)에 달렸다 → 두루미
            val bird = CollectionEngine.collect(runs = listOf(run(km = 42.195, seconds = 12_600.0, since = start)),
                                                cycleStartedAt = start, now = now, zone = testZone, id = newID())
            assertEquals(BirdSpecies.crane, bird.species)
            assertEquals("풀코스 3:30:00", bird.goalLabel)
            assertEquals(now, bird.collectedAt)
            // 2026-05-01 → 2026-08-13 = 31(5월 잔여) + 30 + 31 + 13 = 104일
            assertEquals(104, bird.cycleDays)
        }

        @Test
        @DisplayName("수집 — 사이클 시작이 미래여도 소요 일수는 음수가 되지 않는다")
        fun collectClampsNegativeDays() {
            val now = iso("2026-08-13T09:00:00Z")
            val future = later(now, 10.0 * 86_400)
            val bird = CollectionEngine.collect(runs = emptyList(),
                                                cycleStartedAt = future, now = now, zone = testZone, id = newID())
            assertEquals(0, bird.cycleDays)
        }

        // MARK: - 다음 목표 추천

        @Test
        @DisplayName("다음 목표 추천 — 종목을 한 칸씩 올린다")
        fun recommendationClimbs() {
            assertEquals(RaceDistance.fiveK, CollectionEngine.recommendedGoal(after = null, goalSeconds = 0)?.distance)
            assertEquals(RaceDistance.tenK,
                         CollectionEngine.recommendedGoal(after = RaceDistance.fiveK, goalSeconds = 0)?.distance)
            assertEquals(RaceDistance.half,
                         CollectionEngine.recommendedGoal(after = RaceDistance.tenK, goalSeconds = 0)?.distance)
            assertEquals(RaceDistance.full,
                         CollectionEngine.recommendedGoal(after = RaceDistance.half, goalSeconds = 0)?.distance)
        }

        @Test
        @DisplayName("풀코스 이후는 기록 단축으로 방향을 튼다")
        fun recommendationTightensTime() {
            // 기록 미입력 → 우선 sub-4 제안. 종 경계가 배타(<)라 4:00:00이 아니라 3:59:00
            val first = assertNotNull(CollectionEngine.recommendedGoal(after = RaceDistance.full, goalSeconds = 0))
            assertEquals(RaceDistance.full, first.distance)
            assertEquals(4 * 3_600 - 60, first.seconds)
            assertEquals(BirdSpecies.crane,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = first.seconds))

            // 4:00:00 → 30분 당겨 3:30:00
            val tighter = assertNotNull(CollectionEngine.recommendedGoal(after = RaceDistance.full,
                                                                         goalSeconds = 4 * 3_600))
            assertEquals(3 * 3_600 + 30 * 60, tighter.seconds)
        }

        @Test
        @DisplayName("서브3 경계 이하로 당겨지면 서브3 바로 아래(2:59:00)로 맞추고, 이미 서브3이면 추천 없음")
        fun recommendationStopsAtSub3() {
            // 3:00:00은 서브3(< 3:00:00)이 아니다 — 30분 당긴 2:30:00 대신 2:59:00으로 맞춘다
            assertEquals(3 * 3_600 - 60,
                         CollectionEngine.recommendedGoal(after = RaceDistance.full, goalSeconds = 3 * 3_600)?.seconds)
            // 3:30:00 → 3:00:00은 경계에 걸려 종이 오르지 않는다 → 2:59:00
            assertEquals(3 * 3_600 - 60,
                         CollectionEngine.recommendedGoal(after = RaceDistance.full,
                                                          goalSeconds = 3 * 3_600 + 30 * 60)?.seconds)
            // 이미 서브3(2:59:00 이하)이면 더 올릴 종이 없다
            assertNull(CollectionEngine.recommendedGoal(after = RaceDistance.full, goalSeconds = 3 * 3_600 - 60))
            assertNull(CollectionEngine.recommendedGoal(after = RaceDistance.full,
                                                        goalSeconds = 2 * 3_600 + 50 * 60))
        }

        @Test
        @DisplayName("3:10:00 목표 — 30분 당기면 서브3 아래라 nil이 아니라 2:59:00(백조)을 추천한다")
        fun recommendationClampsToSub3() {
            // 3:10:00 − 30분 = 2:40:00 ≤ 3:00:00 → 서브3 바로 아래 2:59:00으로 clamp
            val next = assertNotNull(CollectionEngine.recommendedGoal(after = RaceDistance.full,
                                                                      goalSeconds = 3 * 3_600 + 10 * 60))
            assertEquals(RaceDistance.full, next.distance)
            assertEquals(2 * 3_600 + 59 * 60, next.seconds)
            assertEquals(BirdSpecies.swan,
                         CollectionEngine.species(distance = RaceDistance.full, goalSeconds = next.seconds))
        }

        // MARK: - 세러모니 다음 목표 초기 선택 (이슈 #127)

        @Test
        @DisplayName("초기 선택 — 현재 목표가 사이클 목표와 같으면 사이클 목표 기준 추천")
        fun initialNextGoalSameAsCycle() {
            // 10K 사이클 → 한 칸 올린 하프
            val pick = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.tenK, cycleGoalSeconds = 0,
                                                        currentGoal = RaceDistance.tenK, currentSeconds = 0)
            assertEquals(RaceDistance.half, pick.distance)
            assertEquals(0, pick.seconds)
            // 풀 4:00:00 사이클 → 30분 당긴 3:30:00 (recommendedGoal과 같은 결과)
            val full = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full, cycleGoalSeconds = 4 * 3_600,
                                                        currentGoal = RaceDistance.full, currentSeconds = 4 * 3_600)
            assertEquals(RaceDistance.full, full.distance)
            assertEquals(3 * 3_600 + 30 * 60, full.seconds)
            // 이미 서브3이면 추천이 없어 사이클 목표를 유지한다
            val sub3 = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full,
                                                        cycleGoalSeconds = 2 * 3_600 + 50 * 60,
                                                        currentGoal = RaceDistance.full,
                                                        currentSeconds = 2 * 3_600 + 50 * 60)
            assertEquals(RaceDistance.full, sub3.distance)
            assertEquals(2 * 3_600 + 50 * 60, sub3.seconds)
        }

        @Test
        @DisplayName("초기 선택 — 사이클 도중 더 먼 종목으로 바꿨으면 그 목표를 그대로 둔다")
        fun initialNextGoalFartherCurrent() {
            // 5K 사이클 중 풀 3:50:00으로 변경 → 추천(10K) 대신 풀 3:50:00
            val pick = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.fiveK, cycleGoalSeconds = 0,
                                                        currentGoal = RaceDistance.full,
                                                        currentSeconds = 3 * 3_600 + 50 * 60)
            assertEquals(RaceDistance.full, pick.distance)
            assertEquals(3 * 3_600 + 50 * 60, pick.seconds)
            // 목표 없음 사이클 중 하프로 변경 → 추천(5K) 대신 하프
            val fromNone = CollectionEngine.initialNextGoal(cycleGoal = null, cycleGoalSeconds = 0,
                                                            currentGoal = RaceDistance.half, currentSeconds = 0)
            assertEquals(RaceDistance.half, fromNone.distance)
        }

        @Test
        @DisplayName("초기 선택 — 같은 종목에 더 빠른 기록으로 바꿨으면 그 목표를 그대로 둔다")
        fun initialNextGoalFasterCurrent() {
            // 풀 4:00:00 사이클 중 3:45:00으로 당김 → 추천(3:30:00) 대신 3:45:00
            val pick = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full, cycleGoalSeconds = 4 * 3_600,
                                                        currentGoal = RaceDistance.full,
                                                        currentSeconds = 3 * 3_600 + 45 * 60)
            assertEquals(RaceDistance.full, pick.distance)
            assertEquals(3 * 3_600 + 45 * 60, pick.seconds)
            // 기록 없는 풀 완주 사이클 중 기록 4:10:00을 입력 → 추천(3:59:00) 대신 4:10:00
            val fromFinish = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full, cycleGoalSeconds = 0,
                                                              currentGoal = RaceDistance.full,
                                                              currentSeconds = 4 * 3_600 + 10 * 60)
            assertEquals(4 * 3_600 + 10 * 60, fromFinish.seconds)
        }

        @Test
        @DisplayName("초기 선택 — 현재 목표가 더 낮으면 사이클 목표 기준 추천")
        fun initialNextGoalLowerCurrent() {
            // 하프 사이클 중 5K로 낮춤 → 하프 기준 추천 풀코스
            val pick = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.half, cycleGoalSeconds = 0,
                                                        currentGoal = RaceDistance.fiveK, currentSeconds = 0)
            assertEquals(RaceDistance.full, pick.distance)
            assertEquals(0, pick.seconds)
            // 풀 3:30:00 사이클 중 4:00:00으로 늦춤 → 3:30:00 기준 추천 2:59:00
            val slower = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full,
                                                          cycleGoalSeconds = 3 * 3_600 + 30 * 60,
                                                          currentGoal = RaceDistance.full,
                                                          currentSeconds = 4 * 3_600)
            assertEquals(3 * 3_600 - 60, slower.seconds)
            // 풀 사이클 중 목표를 지움 → 풀 기준 추천 3:59:00
            val cleared = CollectionEngine.initialNextGoal(cycleGoal = RaceDistance.full, cycleGoalSeconds = 0,
                                                           currentGoal = null, currentSeconds = 0)
            assertEquals(RaceDistance.full, cleared.distance)
            assertEquals(4 * 3_600 - 60, cleared.seconds)
        }
    }

    /// 도감 저장 검증 — 임시 디렉터리를 주입해 실제 Application Support를 건드리지 않는다.
    @Nested
    @DisplayName("도감 저장")
    inner class CollectionCacheTests {

        /// 테스트마다 고유 디렉터리 — 병렬 실행에서 서로 덮어쓰지 않게 한다
        private fun makeTempDirectory(): File =
            Files.createTempDirectory("collection-test-${UUID.randomUUID()}").toFile()

        @Test
        @DisplayName("저장한 도감을 그대로 읽어 온다")
        fun roundTrip() {
            val dir = makeTempDirectory()
            try {
                val now = iso("2026-08-13T09:00:00Z")
                val start = later(now, -30.0 * 86_400)
                // 사이클 안의 하프 한 번 → 매
                val half = RunSummary(id = newID(), start = later(start, 86_400.0),
                                      durationSec = 7_200.0, distanceMeters = 21_100.0, avgHeartRate = null)
                val birds = listOf(CollectionEngine.collect(runs = listOf(half), cycleStartedAt = start, now = now,
                                                            zone = testZone, id = newID()))
                CollectionCache.save(birds, dir)

                val loaded = CollectionCache.load(dir)
                assertEquals(1, loaded.size)
                assertEquals(BirdSpecies.falcon, loaded.firstOrNull()?.species)
                assertEquals(30, loaded.firstOrNull()?.cycleDays)
            } finally {
                dir.deleteRecursively()
            }
        }

        @Test
        @DisplayName("파일이 없으면 빈 도감 — 오류가 아니다")
        fun missingFileIsEmpty() {
            val dir = makeTempDirectory()
            try {
                assertTrue(CollectionCache.load(dir).isEmpty())
            } finally {
                dir.deleteRecursively()
            }
        }

        @Test
        @DisplayName("같은 종을 여러 사이클에서 모으면 이력이 쌓인다")
        fun repeatedSpeciesAccumulate() {
            val dir = makeTempDirectory()
            try {
                val now = iso("2026-08-13T09:00:00Z")
                // 첫 사이클엔 5km, 다음 사이클엔 10km — 둘 다 제비
                fun run(meters: Double, daysAgo: Double): RunSummary =
                    RunSummary(id = newID(), start = later(now, -daysAgo * 86_400),
                               durationSec = 3_000.0, distanceMeters = meters, avgHeartRate = null)
                val first = CollectionEngine.collect(runs = listOf(run(5_000.0, daysAgo = 45.0)),
                                                     cycleStartedAt = later(now, -60.0 * 86_400),
                                                     now = later(now, -30.0 * 86_400),
                                                     zone = testZone, id = newID())
                val second = CollectionEngine.collect(runs = listOf(run(5_000.0, daysAgo = 45.0),
                                                                    run(10_000.0, daysAgo = 10.0)),
                                                      cycleStartedAt = later(now, -30.0 * 86_400),
                                                      now = now, zone = testZone, id = newID())
                CollectionCache.save(listOf(first, second), dir)

                val loaded = CollectionCache.load(dir)
                // 둘 다 제비지만 별개 이력으로 남는다 — 도감 칸에서 ×2로 표시된다
                assertEquals(2, loaded.size)
                assertTrue(loaded.all { it.species == BirdSpecies.swallow })
                assertEquals(2, loaded.map { it.id }.toSet().size)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /// 도감 스토어 수집 — 저장 성공 여부를 호출부에 돌려주는지 (이슈 #67).
    /// 실패를 삼키면 홈이 사이클을 초기화해 수집한 새를 잃는다.
    /// (Android: 상태를 들고 있는 스토어는 :app 몫이라 저장 규칙인 `CollectionCache.add`를 검증한다 —
    /// 반환 목록이 곧 스토어가 메모리에 둘 새 목록이고, null이면 메모리를 바꾸지 않는다)
    @Nested
    @DisplayName("도감 스토어 수집")
    inner class CollectionStoreAddTests {

        private fun makeBird(): CollectedBird {
            val now = iso("2026-08-13T09:00:00Z")
            return CollectionEngine.collect(runs = emptyList(),
                                            cycleStartedAt = later(now, -30.0 * 86_400),
                                            now = now, zone = testZone, id = newID())
        }

        @Test
        @DisplayName("저장에 성공하면 true — 메모리와 파일에 모두 남는다")
        fun addSucceeds() {
            val dir = Files.createTempDirectory("collection-store-${UUID.randomUUID()}").toFile()
            try {
                val bird = makeBird()
                val birds = assertNotNull(CollectionCache.add(bird, birds = emptyList(), directory = dir,
                                                              defaults = InMemoryKeyValueStore(),
                                                              now = bird.collectedAt))
                assertEquals(1, birds.size)
                assertEquals(1, CollectionCache.load(dir).size)
            } finally {
                dir.deleteRecursively()
            }
        }

        @Test
        @DisplayName("저장에 실패하면 false — 메모리에도 넣지 않아 재시도 때 중복되지 않는다")
        fun addFailsWhenDirectoryIsFile() {
            // 디렉터리 자리에 일반 파일을 둬서 file/collection.json 쓰기가 반드시 실패하게 한다
            val notADirectory = File(System.getProperty("java.io.tmpdir"),
                                     "collection-store-file-${UUID.randomUUID()}")
            notADirectory.writeText("x")
            try {
                val bird = makeBird()
                val memory = emptyList<CollectedBird>()
                assertNull(CollectionCache.add(bird, birds = memory, directory = notADirectory,
                                               defaults = InMemoryKeyValueStore(), now = bird.collectedAt))
                assertTrue(memory.isEmpty())
            } finally {
                notADirectory.delete()
            }
        }
    }
}
