package com.jkpark.runwrap.engine

import com.jkpark.runwrap.engine.ActiveTimeline.Marker
import com.jkpark.runwrap.engine.ActiveTimeline.MarkerEvent
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 활동 타임라인 엔진 — 정지 구간 짝맞춤, 활동 초·벽시계 변환, 정지를 뺀 km 스플릿을 검증한다 (이슈 #47).
class ActiveTimelineTests {
    /// 고정 시각 — 모든 시각은 여기서부터의 상대 초로 만든다
    private val start = iso("2026-08-10T07:00:00+09:00")

    private fun at(sec: Double): Instant = instantSince1970(start.timeIntervalSince1970 + sec)

    private fun interval(from: Double, to: Double): ClosedRange<Instant> = at(from)..at(to)

    /// from초부터 1km/300초 등속으로 meters만큼 10초 간격 거리 샘플을 깐다 (샘플당 1000/30 ≈ 33.33m)
    private fun constantSamples(from: Double, meters: Double): List<DistanceSample> {
        val perSample = 1_000.0 / 30
        val count = (meters / perSample).swiftRoundedInt()
        return (0 until count).map { i ->
            DistanceSample(start = at(from + i * 10.0), end = at(from + (i + 1) * 10.0), meters = perSample)
        }
    }

    @Test
    @DisplayName("사용자 정지와 모션 정지 표식이 각각 짝지어 정지 구간이 된다")
    fun pairsUserAndMotionMarkers() {
        val markers = listOf(
            MarkerEvent(date = at(1_560.0), kind = Marker.motionResume),  // 순서가 섞여 들어와도 날짜순으로 정렬한다
            MarkerEvent(date = at(600.0), kind = Marker.pause),
            MarkerEvent(date = at(1_500.0), kind = Marker.motionPause),
            MarkerEvent(date = at(690.0), kind = Marker.resume),
        )

        val pauses = ActiveTimeline.pauses(markers = markers, start = start, end = at(3_600.0))

        // [600–690] 90초 + [1500–1560] 60초 = 정지 합 150초
        assertEquals(listOf(interval(600.0, 690.0), interval(1_500.0, 1_560.0)), pauses)
        assertEquals(150.0, pauses.fold(0.0) { sum, p ->
            sum + (p.endInclusive.timeIntervalSince1970 - p.start.timeIntervalSince1970)
        })
    }

    @Test
    @DisplayName("사용자 정지 중 모션 정지가 끼어도 사용자 재개 때까지 정지로 본다")
    fun overlappingUserAndMotionPauseStaysPaused() {
        val markers = listOf(
            MarkerEvent(date = at(100.0), kind = Marker.pause),
            MarkerEvent(date = at(110.0), kind = Marker.motionPause),
            MarkerEvent(date = at(150.0), kind = Marker.motionResume),  // 아직 사용자 정지 중 — 여기서 닫히면 50초를 덜 뺀다
            MarkerEvent(date = at(200.0), kind = Marker.resume),
        )

        val pauses = ActiveTimeline.pauses(markers = markers, start = start, end = at(3_600.0))

        assertEquals(listOf(interval(100.0, 200.0)), pauses)
    }

    @Test
    @DisplayName("짝 없는 재개·중복 정지는 무시하고, 닫히지 않은 정지는 종료 시각에서 닫는다")
    fun irregularMarkers() {
        val markers = listOf(
            MarkerEvent(date = at(100.0), kind = Marker.resume),   // 짝 없음 → 무시
            MarkerEvent(date = at(1_000.0), kind = Marker.pause),
            MarkerEvent(date = at(1_050.0), kind = Marker.pause),  // 이미 정지 중 → 무시
            MarkerEvent(date = at(1_100.0), kind = Marker.resume),
            MarkerEvent(date = at(3_500.0), kind = Marker.pause),  // 재개 없음 → end(3600)에서 닫는다
        )

        val pauses = ActiveTimeline.pauses(markers = markers, start = start, end = at(3_600.0))

        // [1000–1100] 100초 + [3500–3600] 100초
        assertEquals(listOf(interval(1_000.0, 1_100.0), interval(3_500.0, 3_600.0)), pauses)
    }

    @Test
    @DisplayName("활동 초는 정지 구간과 겹친 부분만 뺀다")
    fun activeSecondsSubtractsPartialOverlap() {
        // 벽시계 0–1000 중 정지 900–1100과 겹친 900–1000(100초)만 뺀다 → 1000 − 100 = 900
        val active = ActiveTimeline.activeSeconds(from = start, to = at(1_000.0), pauses = listOf(interval(900.0, 1_100.0)))
        assertEquals(900.0, active)
    }

    @Test
    @DisplayName("활동 경과 시간은 앞선 정지 길이만큼 벽시계로 밀린다")
    fun wallTimeSkipsPrecedingPause() {
        // 활동 1800초 = 정지 전 600초 + 정지(90초) 뒤 1200초 → 벽시계 600 + 90 + 1200 = 1890
        val wall = ActiveTimeline.wallTime(afterActive = 1_800.0, from = start, pauses = listOf(interval(600.0, 690.0)))
        assertEquals(at(1_890.0), wall)
    }

    @Test
    @DisplayName("정지가 없으면 스플릿은 경계 사이 벽시계 시간 그대로다")
    fun splitsWithoutPausesMatchWallClock() {
        val samples = constantSamples(from = 0.0, meters = 3_000.0)  // 1km/300초 × 3km

        val splits = ActiveTimeline.splits(distanceSamples = samples, pauses = emptyList())

        // 33.33m 누적의 부동소수점 오차로 경계가 이웃 샘플로 밀릴 수 있어 허용오차로 비교한다
        assertEquals(listOf(1, 2, 3), splits.map { it.index })
        for (split in splits) {
            assertTrue(abs(split.paceSecPerKm - 300) < 0.01)
        }
    }

    @Test
    @DisplayName("스플릿에서 정지 구간을 빼면 신호 대기가 있던 km도 제 페이스로 나온다")
    fun splitsSubtractPause() {
        // 1km/300초 등속, 벽시계 450초(1.5km 지점)부터 90초 정지 — 정지 중에는 샘플이 없다
        val samples = constantSamples(from = 0.0, meters = 1_500.0) + constantSamples(from = 540.0, meters = 1_500.0)
        val pause = interval(450.0, 540.0)

        val splits = ActiveTimeline.splits(distanceSamples = samples, pauses = listOf(pause))

        // 2km 통과 벽시계 = 540 + 500m×0.3초/m = 690. 1km 통과는 300.
        // 벽시계 차이 690 − 300 = 390에서 정지 90초를 빼 300
        assertEquals(listOf(1, 2, 3), splits.map { it.index })
        for (split in splits) {
            assertTrue(abs(split.paceSecPerKm - 300) < 0.01)
        }

        // 비교: 정지 구간을 모르면(수정 전 동작) 2km가 390초로 튄다
        val naive = ActiveTimeline.splits(distanceSamples = samples, pauses = emptyList())
        assertTrue(abs(naive[1].paceSecPerKm - 390) < 0.01)
    }

    @Test
    @DisplayName("60초/km 미만 구간은 데이터 오류로 빼되 km 번호는 밀리지 않는다")
    fun splitsDropImplausibleKmKeepingIndex() {
        val samples = listOf(
            DistanceSample(start = at(0.0), end = at(50.0), meters = 1_000.0),     // 50초에 1km — 데이터 오류
            DistanceSample(start = at(50.0), end = at(350.0), meters = 1_000.0),   // 300초에 1km
        )

        val splits = ActiveTimeline.splits(distanceSamples = samples, pauses = emptyList())

        // 1km는 50초 ≤ 60초라 제외, 2km는 350 − 50 = 300초
        assertEquals(listOf(2), splits.map { it.index })
        assertTrue(abs(splits[0].paceSecPerKm - 300) < 0.01)
    }

    @Test
    @DisplayName("이벤트가 없을 때는 10초 이상 벌어진 거리 샘플 공백만 정지로 본다")
    fun gapPausesFallback() {
        val samples = listOf(
            DistanceSample(start = at(0.0), end = at(10.0), meters = 30.0),
            DistanceSample(start = at(12.0), end = at(22.0), meters = 30.0),     // 공백 2초 → 정상
            DistanceSample(start = at(112.0), end = at(122.0), meters = 30.0),   // 공백 90초 → 정지
        )

        assertEquals(listOf(interval(22.0, 112.0)), ActiveTimeline.gapPauses(samples))
    }
}
