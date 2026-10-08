package com.jkpark.runwrap.engine

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 데모 합성 결정성 (이슈 #102) — 같은 세션을 다시 열면 합성 상세(지도·수치)가 같아야 한다.
/// 시드가 run.id.hashValue(프로세스마다 다름)가 아니라 uuid·시작 시각의 FNV-1a라는 것과,
/// DemoData.runs가 접근마다 새 UUID·시각을 만들지 않는다는 것을 확인한다.
class DemoSyntheticTests {
    /// 고정 시각 — 합성 시드는 시작 시각 비트를 섞으므로 now를 주입해 결정론적으로 만든다
    private val now = iso("2026-09-30T07:00:00+09:00")

    private val profile = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.fallback,
                                           restingHR = null, zoneMethod = HeartRateZoneMethod.percentMax)

    private fun run(id: String = DemoData.demoID(7), indoor: Boolean = false): RunSummary =
        RunSummary(id = id, start = now, durationSec = 3_000.0, distanceMeters = 8_000.0,
                   avgHeartRate = 150.0, maxHeartRate = 176.0, isIndoor = indoor)

    @Test
    @DisplayName("합성 시드 — 같은 uuid·시작 시각이면 같은 값, 하나라도 다르면 다른 값")
    fun seedIsDeterministic() {
        val seed = WorkoutDetailStore.syntheticSeed(run())
        assertEquals(seed, WorkoutDetailStore.syntheticSeed(run()))
        assertNotEquals(seed, WorkoutDetailStore.syntheticSeed(run(id = DemoData.demoID(8))))
        val later = RunSummary(id = DemoData.demoID(7), start = now.plusSeconds(1),
                               durationSec = 3_000.0, distanceMeters = 8_000.0, avgHeartRate = 150.0)
        assertNotEquals(seed, WorkoutDetailStore.syntheticSeed(later))
    }

    @Test
    @DisplayName("합성 시드 — FNV-1a 64비트 고정값 (hashValue처럼 실행마다 바뀌지 않는다)")
    fun seedMatchesReferenceFNV1a() {
        // 입력: uuid 00…07 16바이트 + 1_790_719_200.0(2026-09-29T22:00Z)의 Double 비트 LE 8바이트.
        // 표준 FNV-1a 64비트(basis 0xcbf29ce484222325, prime 0x100000001b3)를 파이썬으로 따로 계산한 값
        assertEquals(0x4599_1F7D_1BE8_9A8DuL, WorkoutDetailStore.syntheticSeed(run()))
    }

    @Test
    @DisplayName("같은 세션으로 합성 상세를 두 번 만들면 경로·스플릿·존이 같다")
    fun syntheticDetailIsStable() {
        val first = WorkoutDetailStore.synthetic(run(), heartRate = profile)
        val second = WorkoutDetailStore.synthetic(run(), heartRate = profile)
        assertFalse(first.route.isEmpty())
        assertEquals(second.route.map { it.lat }, first.route.map { it.lat })
        assertEquals(second.route.map { it.lon }, first.route.map { it.lon })
        // 경로 시각은 시작 시각부터 일정 간격 (#222 선행 — GPX 재료)
        assertEquals(now, first.route.first().time)
        assertEquals(second.route.map { it.time }, first.route.map { it.time })
        assertEquals(second.splits.map { it.paceSecPerKm }, first.splits.map { it.paceSecPerKm })
        assertEquals(second.zones, first.zones)
        assertEquals(second.cadenceSpm, first.cadenceSpm)
    }

    @Test
    @DisplayName("고도 프로필 — 합성 경로의 오르내림 폭이 상승 고도와 맞는다")
    fun syntheticElevationMatchesAscent() {
        // 한 번 오르고 내리는 언덕이라 프로필 최고 − 최저 ≈ 상승 고도 (60등분 표본이라 꼭대기 근처 오차 1m 안)
        val detail = WorkoutDetailStore.synthetic(run(), heartRate = profile)
        val elevations = assertNotNull(RoutePaceEngine.elevationProfile(detail.route)).map { it.elevationM }
        val ascent = assertNotNull(detail.elevationM)
        assertTrue(abs((elevations.max() - elevations.min()) - ascent) < 1)
        // 실내 세션은 경로가 없어 프로필도 없다
        assertNull(RoutePaceEngine.elevationProfile(
            WorkoutDetailStore.synthetic(run(indoor = true), heartRate = profile).route))
    }

    @Test
    @DisplayName("DemoData.runs — 두 번 읽어도 같은 목록(id·시작 시각), id는 인덱스 기반이며 중복 없다")
    fun demoRunsAreStable() {
        // (Android: iOS static let 대신 같은 now를 두 번 넘긴다)
        val a = DemoData.runs(now)
        val b = DemoData.runs(now)
        assertEquals(b, a)
        assertEquals(a.size, a.map { it.id }.toSet().size)
        assertTrue(a.any { it.id == DemoData.pausedRunID })
        assertEquals("00000000-0000-0000-0000-000000000002", DemoData.demoID(2))
        assertTrue(a.any { it.id == DemoData.demoID(2) })
    }
}
