package com.jkpark.runwrap.engine

import com.jkpark.runwrap.ui.theme.RRTone
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// 심박 드리프트(디커플링) 엔진 — 정속 60분 세션 위주로 EF 비교와 미노출 가드를 검증한다.
/// iOS `DriftEngineTests` 이식 (7개 전부).
class DriftEngineTest {
    /// 고정 시각 — 이후 모든 세션은 여기서부터 상대 시간으로 구성한다
    /// (Instant.parse는 +09:00 오프셋을 못 읽어 OffsetDateTime을 거친다)
    private val start = OffsetDateTime.parse("2026-08-10T07:00:00+09:00").toInstant()

    /// 구간에 10초 간격 심박 샘플을 깐다(bpm 고정값). step을 늘리면 샘플 수를 줄일 수 있다
    private fun hrSamples(from: Instant, to: Instant, bpm: Double,
                          step: Double = 10.0): List<DriftEngine.HrSample> {
        val samples = mutableListOf<DriftEngine.HrSample>()
        var t = from
        while (t < to) {
            samples.add(DriftEngine.HrSample(time = t, bpm = bpm))
            t = t.plusMillis((step * 1_000).roundToLong())
        }
        return samples
    }

    /// 구간에 총 거리를 100m 단위 샘플로 등속 배분한다
    private fun distanceSamples(from: Instant, to: Instant, totalMeters: Double,
                                chunk: Double = 100.0): List<DriftEngine.DistanceSample> {
        val totalDur = (to.toEpochMilli() - from.toEpochMilli()) / 1000.0
        val samples = mutableListOf<DriftEngine.DistanceSample>()
        var covered = 0.0
        var t = from
        while (covered < totalMeters) {
            val thisChunk = minOf(chunk, totalMeters - covered)
            val thisDur = totalDur * (thisChunk / totalMeters)
            val segEnd = t.plusMillis((thisDur * 1_000).roundToLong())
            samples.add(DriftEngine.DistanceSample(start = t, end = segEnd, meters = thisChunk))
            covered += thisChunk
            t = segEnd
        }
        return samples
    }

    @Test
    fun `정속 60분·후반 심박 +8퍼센트 - 디커플링 +8퍼센트 caution`() {
        val mid = start.plusSeconds(1_800)
        val end = start.plusSeconds(3_600)
        val hr = hrSamples(start, mid, bpm = 140.0) + hrSamples(mid, end, bpm = 151.2)
        val dist = distanceSamples(start, mid, totalMeters = 5_000.0) +
            distanceSamples(mid, end, totalMeters = 5_000.0)

        val result = DriftEngine.compute(hr, dist, start, durationSec = 3_600.0)!!

        // 거리·시간이 전/후반 동일하므로 EF1/EF2 = secondBPM/firstBPM = 151.2/140 = 1.08
        // decouplingPct = (1.08 − 1) × 100 = 8.0
        assertEquals(8.0, result.decouplingPct, 0.01)
        assertEquals(RRTone.CAUTION, result.tone)
    }

    @Test
    fun `전·후반 심박이 같으면 디커플링 0 steady`() {
        val mid = start.plusSeconds(1_200)
        val end = start.plusSeconds(2_400)
        val hr = hrSamples(start, mid, bpm = 150.0) + hrSamples(mid, end, bpm = 150.0)
        val dist = distanceSamples(start, mid, totalMeters = 4_000.0) +
            distanceSamples(mid, end, totalMeters = 4_000.0)

        val result = DriftEngine.compute(hr, dist, start, durationSec = 2_400.0)!!

        assertEquals(0.0, result.decouplingPct, 0.01)
        assertEquals(RRTone.STEADY, result.tone)
    }

    @Test
    fun `후반 심박이 3퍼센트 낮으면 디커플링 -3퍼센트 improving`() {
        val mid = start.plusSeconds(1_800)
        val end = start.plusSeconds(3_600)
        val hr = hrSamples(start, mid, bpm = 150.0) + hrSamples(mid, end, bpm = 145.5)
        val dist = distanceSamples(start, mid, totalMeters = 5_000.0) +
            distanceSamples(mid, end, totalMeters = 5_000.0)

        val result = DriftEngine.compute(hr, dist, start, durationSec = 3_600.0)!!

        // EF1/EF2 = secondBPM/firstBPM = 145.5/150 = 0.97 → decouplingPct = (0.97 − 1) × 100 = −3.0
        assertEquals(-3.0, result.decouplingPct, 0.01)
        assertEquals(RRTone.IMPROVING, result.tone)
    }

    @Test
    fun `30분 미만 세션은 판정하지 않는다`() {
        val mid = start.plusSeconds(750)
        val end = start.plusSeconds(1_500)
        val hr = hrSamples(start, mid, bpm = 150.0) + hrSamples(mid, end, bpm = 150.0)
        val dist = distanceSamples(start, mid, totalMeters = 2_000.0) +
            distanceSamples(mid, end, totalMeters = 2_000.0)

        assertNull(DriftEngine.compute(hr, dist, start, durationSec = 1_500.0))
    }

    @Test
    fun `전반 심박 샘플이 10개뿐이면 판정하지 않는다`() {
        val mid = start.plusSeconds(1_800)
        val end = start.plusSeconds(3_600)
        // step 180초 × 10개 = 전반 30분에 정확히 10개(최소 20개 미달)
        val hr = hrSamples(start, mid, bpm = 140.0, step = 180.0) + hrSamples(mid, end, bpm = 140.0)
        val dist = distanceSamples(start, mid, totalMeters = 5_000.0) +
            distanceSamples(mid, end, totalMeters = 5_000.0)

        assertNull(DriftEngine.compute(hr, dist, start, durationSec = 3_600.0))
    }

    @Test
    fun `후반 페이스가 15퍼센트 느리면(빌드다운) 판정하지 않는다`() {
        val mid = start.plusSeconds(1_800)
        val end = start.plusSeconds(3_600)
        val hr = hrSamples(start, mid, bpm = 150.0) + hrSamples(mid, end, bpm = 150.0)
        // 전반 4,600m·후반 4,000m, 같은 30분 → 후반 페이스가 4,600/4,000 = 1.15배(15%) 느리다
        val dist = distanceSamples(start, mid, totalMeters = 4_600.0) +
            distanceSamples(mid, end, totalMeters = 4_000.0)

        assertNull(DriftEngine.compute(hr, dist, start, durationSec = 3_600.0))
    }

    @Test
    fun `중앙을 걸치는 거리 샘플은 시간 비례로 전-후반에 배분된다`() {
        val mid = start.plusSeconds(1_800)
        val end = start.plusSeconds(3_600)
        val hr = hrSamples(start, mid, bpm = 140.0) + hrSamples(mid, end, bpm = 150.0)
        // 전반 전용 1,000m + 중앙을 걸치는 3,000m(15:00~40:00, 900초 구간)
        // 중앙(30:00)은 걸치는 구간 시작(25:00)에서 300/900 = 1/3 지점
        // → 전반 배분 3,000 × 1/3 = 1,000m, 후반 배분 3,000 × 2/3 = 2,000m
        // 합산: 전반 1,000+1,000 = 2,000m, 후반 2,000m (전/후반 거리·시간 동일 → 페이스 가드 통과)
        val dist = listOf(
            DriftEngine.DistanceSample(start = start, end = start.plusSeconds(500), meters = 1_000.0),
            DriftEngine.DistanceSample(start = start.plusSeconds(1_500),
                                       end = start.plusSeconds(2_400), meters = 3_000.0),
        )

        val result = DriftEngine.compute(hr, dist, start, durationSec = 3_600.0)!!

        // EF1 = 2,000/(140×30) = 0.47619..., EF2 = 2,000/(150×30) = 0.44444...
        assertEquals(2_000.0 / (140 * 30), result.firstHalfEF, 0.0001)
        assertEquals(2_000.0 / (150 * 30), result.secondHalfEF, 0.0001)
        // decouplingPct = (150/140 − 1) × 100 ≈ 7.142857
        assertEquals(7.142857, result.decouplingPct, 0.01)
        assertEquals(RRTone.CAUTION, result.tone)
    }
}
