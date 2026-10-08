package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 기간별 심박존 분포 + 80/20 강도 배분 검증 (이슈 #165) — 미노출 가드·이지 비율 톤 경계·
/// 주 막대·심박 기준별 존 재적용·히스토그램 가중·캐시 왕복.
@DisplayName("기간별 심박존 분포")
class ZoneDistributionEngineTests {
    /// 목요일 — 이번 달력 주(ISO, 월요일 시작)는 8월 10일부터
    private val now = iso("2026-08-13T09:00:00Z")

    /// HRmax 200 %HRmax — 110→0.55(Z1) · 130→0.65(Z2) · 150→0.75(Z3) · 170→0.85(Z4) · 190→0.95(Z5)
    private val percentMax200 = HeartRateProfile(hrMax = 200.0, hrMaxSource = HeartRateProfile.Source.manual,
                                                 restingHR = null, zoneMethod = HeartRateZoneMethod.percentMax)

    private fun run(daysAgo: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(),
                   start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = 1_800.0, distanceMeters = 5_000.0, avgHeartRate = 145.0)

    private class Sessions(val runs: MutableList<RunSummary>, val histograms: MutableMap<String, ZoneHistogram>)

    /// daysAgo마다 러닝 하나 + 같은 히스토그램
    private fun sessions(days: List<Double>, histogram: Map<Int, Double>): Sessions {
        val runs = days.map { run(daysAgo = it) }
        val histograms = runs.associate { it.id to ZoneHistogram(secondsByBpm = histogram) }
        return Sessions(runs.toMutableList(), histograms.toMutableMap())
    }

    // MARK: 미노출 가드

    @Test
    @DisplayName("표본 가드 — 심박 기록 세션 7회면 nil, 8회면 값")
    fun minimumSessions() {
        val seven = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), histogram = mapOf(130 to 600.0))
        assertNull(ZoneDistributionEngine.compute(histograms = seven.histograms, runs = seven.runs,
                                                  profile = percentMax200, now = now, zone = testZone))

        val eight = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), histogram = mapOf(130 to 600.0))
        val result = assertNotNull(ZoneDistributionEngine.compute(histograms = eight.histograms,
                                                                  runs = eight.runs,
                                                                  profile = percentMax200, now = now,
                                                                  zone = testZone))
        assertEquals(8, result.sessionCount)
    }

    @Test
    @DisplayName("표본 가드 — 빈 히스토그램(샘플 없는 세션)과 28일 창 밖 세션은 세지 않는다")
    fun emptyAndOutOfWindowDoNotCount() {
        val s = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), histogram = mapOf(130 to 600.0))
        // 8번째: 샘플 없는 세션 → 빈 히스토그램
        val empty = run(daysAgo = 8.0)
        s.runs.add(empty)
        s.histograms[empty.id] = ZoneHistogram(secondsByBpm = emptyMap())
        // 9번째: 30일 전 — 창(28일 전 자정 ~ now) 밖
        val old = run(daysAgo = 30.0)
        s.runs.add(old)
        s.histograms[old.id] = ZoneHistogram(secondsByBpm = mapOf(130 to 600.0))
        assertNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                  profile = percentMax200, now = now, zone = testZone))
    }

    // MARK: 80/20 톤 경계

    @Test
    @DisplayName("이지 비율 경계 — 0.80 이상 유지, 0.70 이상 주의, 그 밑은 과부하")
    fun easyShareToneBoundaries() {
        // 세션마다 130bpm(Z2) x초 + 150bpm(Z3) (100−x)초 × 8회 → 이지 비율 = x / 100
        fun tone(easy: Double): Pair<RRTone, Double> {
            val s = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                             histogram = mapOf(130 to easy, 150 to 100 - easy))
            val r = assertNotNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                                 profile = percentMax200, now = now,
                                                                 zone = testZone))
            return r.tone to r.easyShare
        }
        // 640 / 800 = 0.80 정확히 → 유지
        val steady = tone(easy = 80.0)
        assertEquals(RRTone.steady, steady.first)
        assertTrue(abs(steady.second - 0.80) < 1e-9)
        assertEquals(RRTone.caution, tone(easy = 79.0).first)
        // 560 / 800 = 0.70 정확히 → 주의
        assertEquals(RRTone.caution, tone(easy = 70.0).first)
        assertEquals(RRTone.overload, tone(easy = 69.0).first)
        assertEquals(RRTone.steady, tone(easy = 100.0).first)
    }

    @Test
    @DisplayName("누적 비율 — Z1~Z5 비율 합 1, Z1+Z2가 이지 비율")
    fun zoneShareSumsToOne() {
        // 세션마다 110(Z1) 20초 · 130(Z2) 50초 · 150(Z3) 10초 · 170(Z4) 10초 · 190(Z5) 10초 = 100초
        val s = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
                         histogram = mapOf(110 to 20.0, 130 to 50.0, 150 to 10.0, 170 to 10.0, 190 to 10.0))
        val r = assertNotNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                             profile = percentMax200, now = now, zone = testZone))
        val expected = listOf(0.2, 0.5, 0.1, 0.1, 0.1)
        for ((actual, want) in r.zoneShare.zip(expected)) assertTrue(abs(actual - want) < 1e-9)
        assertTrue(abs(r.zoneShare.fold(0.0) { a, b -> a + b } - 1) < 1e-9)
        assertTrue(abs(r.easyShare - 0.7) < 1e-9)   // 0.2 + 0.5
    }

    // MARK: 주 막대

    @Test
    @DisplayName("주 막대 — 최근 4개 달력 주, 오래된 → 최신, 세션 없는 주는 0")
    fun weekBars() {
        // 이번 주(8/10~): 0.2·1·2일 전 3회 / 지난주(8/3~9): 없음 / 2주 전(7/27~8/2): 14·15·16일 전 3회 /
        // 3주 전(7/20~26): 21·22일 전 2회 → 합 8회, 세션당 130bpm 600초
        // (주 경계에서 하루 이상 떨어진 날만 골라 시뮬레이터 시간대가 달라도 같은 주에 든다)
        val s = sessions(listOf(0.2, 1.0, 2.0, 14.0, 15.0, 16.0, 21.0, 22.0), histogram = mapOf(130 to 600.0))
        val r = assertNotNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                             profile = percentMax200, now = now, zone = testZone))
        assertEquals(4, r.weeks.size)
        assertEquals(listOf(1_200.0, 1_800.0, 0.0, 1_800.0), r.weeks.map { w -> w.zoneSeconds.fold(0.0) { a, b -> a + b } })
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), r.weeks[2].zoneSeconds)
        assertEquals(listOf(0.0, 1_800.0, 0.0, 0.0, 0.0), r.weeks[3].zoneSeconds)   // 130bpm = Z2
        // 주 시작일이 7일씩 오르고, 라벨은 Format.weekLabel과 같다
        for ((a, b) in r.weeks.zip(r.weeks.drop(1))) {
            assertEquals(7.0 * 86_400, b.weekStart.timeIntervalSince1970 - a.weekStart.timeIntervalSince1970)
        }
        for (week in r.weeks) assertEquals(Format.weekLabel(week.weekStart, testZone), week.label)
        assertEquals("8월 2째주", r.weeks[3].label)   // 8/10 주의 목요일 8/13 → 2째주
    }

    // MARK: 심박 기준 재적용

    @Test
    @DisplayName("같은 히스토그램도 심박 기준에 따라 존이 달라진다 — Karvonen vs %HRmax")
    fun profileChangesZones() {
        val s = sessions(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), histogram = mapOf(140 to 600.0))
        // %HRmax(190): 140 / 190 = 0.737 → Z3 → 이지 0% 과부하
        val percentMax = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.manual,
                                          restingHR = 50.0, zoneMethod = HeartRateZoneMethod.percentMax)
        val byMax = assertNotNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                                 profile = percentMax, now = now, zone = testZone))
        assertEquals(listOf(0.0, 0.0, 1.0, 0.0, 0.0), byMax.zoneShare)
        assertEquals(RRTone.overload, byMax.tone)
        // Karvonen(190·안정 50): (140 − 50) / 140 = 0.643 → Z2 → 이지 100% 유지
        val karvonen = HeartRateProfile(hrMax = 190.0, hrMaxSource = HeartRateProfile.Source.manual,
                                        restingHR = 50.0, zoneMethod = HeartRateZoneMethod.karvonen)
        val byHRR = assertNotNull(ZoneDistributionEngine.compute(histograms = s.histograms, runs = s.runs,
                                                                 profile = karvonen, now = now, zone = testZone))
        assertEquals(listOf(0.0, 1.0, 0.0, 0.0, 0.0), byHRR.zoneShare)
        assertEquals(RRTone.steady, byHRR.tone)
    }

    // MARK: 히스토그램 생성

    @Test
    @DisplayName("히스토그램 가중 — 다음 샘플까지 간격(15초 캡), 마지막 5초, 230 초과 제외, bpm 반올림")
    fun histogramWeighting() {
        fun at(offsetSec: Double) = instantSince1970(now.timeIntervalSince1970 + offsetSec)
        val samples = listOf(
            TrainingGuideEngine.HeartRateSample(now, 150.0),        // 다음까지 10초 → 150: 10
            TrainingGuideEngine.HeartRateSample(at(10.0), 150.4),   // 다음까지 30초 → 15초 캡, 반올림 150 → 150: +15
            TrainingGuideEngine.HeartRateSample(at(40.0), 160.0),   // 다음까지 5초 → 160: 5
            TrainingGuideEngine.HeartRateSample(at(45.0), 240.0),   // 230 초과 스파이크 → 버린다
            TrainingGuideEngine.HeartRateSample(at(50.0), 170.6),   // 마지막 → 5초, 반올림 171
        )
        assertEquals(mapOf(150 to 25.0, 160 to 5.0, 171 to 5.0), ZoneHistogram.make(samples = samples).secondsByBpm)
        assertTrue(ZoneHistogram.make(samples = emptyList()).secondsByBpm.isEmpty())
    }

    // MARK: 캐시

    @Test
    @DisplayName("캐시 왕복 — 저장·복원·가지치기, JSON 키는 UUID 문자열, 파일이 없으면 빈 딕셔너리")
    fun cacheRoundTrip() {
        val dir = Files.createTempDirectory("runwrap-zone-test-").toFile()
        try {
            assertTrue(ZoneTimeCache.load(dir).isEmpty())

            val keep = UUID.randomUUID().toString().uppercase()
            val drop = UUID.randomUUID().toString().uppercase()
            val histograms = mapOf(keep to ZoneHistogram(secondsByBpm = mapOf(130 to 600.0, 150 to 120.0)),
                                   drop to ZoneHistogram(secondsByBpm = emptyMap()))
            ZoneTimeCache.save(histograms, dir)
            assertEquals(histograms, ZoneTimeCache.load(dir))

            // 저장 형식은 [UUID 문자열: ZoneHistogram]
            val raw = File(dir, ZoneTimeCache.filename).readText()
            val stored = EngineJson.decodeFromString(
                MapSerializer(String.serializer(), ZoneHistogram.serializer()), raw)
            assertEquals(setOf(keep, drop), stored.keys)

            // 28일 창 밖(drop)을 버린다
            val pruned = ZoneTimeCache.prune(histograms, keepingIDs = setOf(keep))
            assertEquals(mapOf(keep to histograms.getValue(keep)), pruned)
            ZoneTimeCache.save(pruned, dir)
            assertEquals(pruned, ZoneTimeCache.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // MARK: 차트 문자열

    @Test
    @DisplayName("주 막대 콜아웃·VoiceOver 수치 — 총 시간 · 이지 비율, 달리지 않은 주는 '기록 없음'")
    fun chartValueText() {
        val start = now
        // 600 + 1,800 + 600 = 3,000초(50:00), 이지 (600 + 1,800) / 3,000 = 80%
        val week = ZoneDistribution.WeekBar(weekStart = start, label = "8월 2째주",
                                            zoneSeconds = listOf(600.0, 1_800.0, 600.0, 0.0, 0.0))
        assertEquals("50:00 · 이지 80%", ZoneStackedBarsChartText.valueText(week))
        val empty = ZoneDistribution.WeekBar(weekStart = start, label = "8월 1째주",
                                             zoneSeconds = listOf(0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals("기록 없음", ZoneStackedBarsChartText.valueText(empty))
    }
}
