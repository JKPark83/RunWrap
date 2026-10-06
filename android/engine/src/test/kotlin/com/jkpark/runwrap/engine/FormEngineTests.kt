package com.jkpark.runwrap.engine

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 주법 엔진 검증 — 기준선 표본 가드, 지표별 평균, 조언 규칙, 케이던스 추이.
/// now = 2026-08-10T09:00:00Z (월 18:00 KST) 고정 — 창 경계: 14일 전 = 7.27, 28일 전 = 7.13.
class FormEngineTests {
    private val now = iso("2026-08-10T09:00:00Z")
    private val engine: FormEngine get() = FormEngine(now = now)

    private fun newID(): String = UUID.randomUUID().toString().uppercase()

    private fun snap(daysAgo: Double, cadence: Double? = 170.0, vo: Double? = 7.0,
                     gct: Double? = 240.0, id: String = newID()): FormSnapshot =
        FormSnapshot(id = id, start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                     cadenceSpm = cadence, verticalOscillationCm = vo, groundContactMs = gct)

    /// 조언 테스트 공용 기준선 — 케이던스 170 spm, 진폭 7.0 cm, 접촉 240 ms
    private val base = FormBaseline(cadenceSpm = 170.0, verticalOscillationCm = 7.0,
                                    groundContactMs = 240.0, sessionCount = 6)

    private fun run(daysAgo: Double, cadence: Double?): RunSummary =
        RunSummary(id = newID(), start = instantSince1970(now.timeIntervalSince1970 - daysAgo * 86_400),
                   durationSec = 3_600.0, distanceMeters = 10_000.0, avgHeartRate = 150.0,
                   cadenceSpm = cadence)

    @Test
    @DisplayName("기준선 가드 — 창 안 표본 5회 미만이면 침묵(nil)")
    fun baselineGuard() {
        val current = newID()
        val snaps = mutableListOf(snap(3.0), snap(6.0), snap(9.0), snap(12.0))
        assertNull(engine.baseline(of = snaps, excluding = current))

        // 창 밖(28일 초과) 표본은 세지 않는다
        snaps.add(snap(30.0))
        assertNull(engine.baseline(of = snaps, excluding = current))

        // 본인 세션은 기준선에서 제외한다
        snaps.add(snap(1.0, id = current))
        assertNull(engine.baseline(of = snaps, excluding = current))

        // 창 안 타인 표본이 5회가 되는 순간 기준선이 나온다
        snaps.add(snap(20.0))
        assertNotNull(engine.baseline(of = snaps, excluding = current))
    }

    @Test
    @DisplayName("기준선 평균 — 지표별로 nil 표본은 빼고 평균낸다")
    fun baselinePerMetricMean() {
        // 지표별 표본 가드(이슈 #92) 이후에도 평균이 나오도록 진폭·접촉 표본을 각 5개로 맞춘다
        val snaps = listOf(
            snap(2.0, cadence = 160.0, vo = 6.0, gct = 230.0),
            snap(5.0, cadence = 165.0, vo = 7.0, gct = 250.0),
            snap(9.0, cadence = 170.0, vo = 8.0, gct = null),
            snap(14.0, cadence = 175.0, vo = null, gct = 240.0),
            snap(20.0, cadence = 180.0, vo = 7.0, gct = 230.0),
            snap(27.0, cadence = 170.0, vo = 7.0, gct = 250.0),
        )
        val baseline = assertNotNull(engine.baseline(of = snaps, excluding = newID()))
        assertEquals(6, baseline.sessionCount)
        assertEquals(170.0, baseline.cadenceSpm)             // (160+165+170+175+180+170)/6
        assertEquals(7.0, baseline.verticalOscillationCm)    // (6+7+8+7+7)/5
        assertEquals(240.0, baseline.groundContactMs)        // (230+250+240+230+250)/5
    }

    @Test
    @DisplayName("기준선 지표별 가드 — 세션은 5회여도 지표 표본이 3개면 그 지표는 nil (이슈 #92)")
    fun baselinePerMetricGuard() {
        // 케이던스 5개, 진폭·접촉은 3개뿐 (다이내믹스 미기록 세션 2회)
        val snaps = listOf(
            snap(2.0, cadence = 168.0, vo = 6.0, gct = 230.0),
            snap(5.0, cadence = 169.0, vo = 7.0, gct = 240.0),
            snap(9.0, cadence = 170.0, vo = 8.0, gct = 250.0),
            snap(14.0, cadence = 171.0, vo = null, gct = null),
            snap(20.0, cadence = 172.0, vo = null, gct = null),
        )
        val baseline = assertNotNull(engine.baseline(of = snaps, excluding = newID()))
        assertEquals(5, baseline.sessionCount)               // 세션 수 정의는 그대로
        assertEquals(170.0, baseline.cadenceSpm)             // (168+169+170+171+172)/5
        assertNull(baseline.verticalOscillationCm)
        assertNull(baseline.groundContactMs)

        // 비교할 지표가 하나도 없으면 기준선 자체를 내지 않는다 ('평소대로 유지' 오판정 방지)
        val noCadence = snaps.map {
            FormSnapshot(id = it.id, start = it.start, cadenceSpm = null,
                         verticalOscillationCm = it.verticalOscillationCm,
                         groundContactMs = it.groundContactMs)
        }
        assertNull(engine.baseline(of = noCadence, excluding = newID()))
    }

    @Test
    @DisplayName("기준선 밴드 — 절대 밴드 밖 진폭·접촉 값은 평균에서 빠진다 (이슈 #92)")
    fun baselineExcludesOutOfBand() {
        // 밴드: 진폭 4~10cm, 접촉 150~300ms — 6회째 값(15cm, 400ms)은 측정 오류로 본다
        val snaps = listOf(
            snap(2.0, vo = 6.0, gct = 230.0),
            snap(5.0, vo = 7.0, gct = 250.0),
            snap(9.0, vo = 8.0, gct = 240.0),
            snap(14.0, vo = 7.0, gct = 230.0),
            snap(20.0, vo = 7.0, gct = 250.0),
            snap(27.0, vo = 15.0, gct = 400.0),
        )
        val baseline = assertNotNull(engine.baseline(of = snaps, excluding = newID()))
        assertEquals(6, baseline.sessionCount)
        assertEquals(7.0, baseline.verticalOscillationCm)    // (6+7+8+7+7)/5 — 15 제외 (포함 시 8.33)
        assertEquals(240.0, baseline.groundContactMs)        // (230+250+240+230+250)/5 — 400 제외 (포함 시 266.7)

        // 밴드 밖 값을 빼서 표본이 4개가 되면 그 지표는 nil
        val fewer = snaps.drop(1)
        val trimmed = assertNotNull(engine.baseline(of = fewer, excluding = newID()))
        assertNull(trimmed.verticalOscillationCm)
        assertNull(trimmed.groundContactMs)
        assertEquals(170.0, trimmed.cadenceSpm)              // 케이던스는 밴드가 없어 5개 그대로
    }

    @Test
    @DisplayName("조언 — 케이던스가 기준선보다 5% 넘게 낮으면 보폭 조언")
    fun adviceCadence() {
        // 160 < 170 × 0.95 = 161.5 → 발화
        val session = snap(0.1, cadence = 160.0)
        val advice = engine.advice(session = session, baseline = base)
        assertEquals(listOf(FormAdvice.Kind.cadence), advice.map { it.kind })
    }

    @Test
    @DisplayName("조언 — 진폭·접촉시간이 기준선의 110%를 넘으면 각각 발화")
    fun adviceOscillationAndContact() {
        // 진폭 7.8 > 7.0 × 1.10 = 7.7, 접촉 270 > 240 × 1.10 = 264 → 둘 다 발화
        val session = snap(0.1, cadence = 170.0, vo = 7.8, gct = 270.0)
        val advice = engine.advice(session = session, baseline = base)
        assertEquals(listOf(FormAdvice.Kind.oscillation, FormAdvice.Kind.contact), advice.map { it.kind })
    }

    @Test
    @DisplayName("조언 — 전 지표가 정상 범위면 침묵")
    fun adviceSilence() {
        val session = snap(0.1, cadence = 168.0, vo = 7.5, gct = 250.0)
        assertTrue(engine.advice(session = session, baseline = base).isEmpty())
    }

    @Test
    @DisplayName("조언 — 절대 밴드(진폭 4~10cm) 이탈은 비교 대신 측정 오류 안내")
    fun adviceSensorBand() {
        // 12 cm는 기준선 110%(7.7)도 넘지만 절대 밴드 밖 → 진폭 조언 억제, 센서 안내만
        val session = snap(0.1, cadence = 170.0, vo = 12.0, gct = 250.0)
        val advice = engine.advice(session = session, baseline = base)
        assertEquals(listOf(FormAdvice.Kind.sensor), advice.map { it.kind })
    }

    @Test
    @DisplayName("케이던스 추이 — 최근 2주 vs 이전 2주 평균, +2 spm 이상이면 개선 톤")
    fun trendImproving() {
        val runs = listOf(
            run(1.0, cadence = 172.0), run(3.0, cadence = 170.0),
            run(5.0, cadence = 168.0),   // 최근 2주 평균 170
            run(15.0, cadence = 164.0), run(17.0, cadence = 166.0),
            run(19.0, cadence = 165.0),  // 이전 2주 평균 165
        )
        val trend = assertNotNull(FormTrend.compute(runs = runs, now = now))
        assertEquals(170.0, trend.recentSpm)
        assertEquals(165.0, trend.previousSpm)
        assertEquals(RRTone.improving, trend.tone)  // delta +5 ≥ +2
    }

    @Test
    @DisplayName("케이던스 추이 가드 — 창마다 표본 3회 미만이면 nil")
    fun trendGuard() {
        // 이전 2주 창의 케이던스 표본이 2개뿐 → 침묵
        val runs = listOf(
            run(1.0, cadence = 172.0), run(3.0, cadence = 170.0),
            run(5.0, cadence = 168.0),
            run(15.0, cadence = 164.0), run(17.0, cadence = 166.0),
            run(19.0, cadence = null),
        )
        assertNull(FormTrend.compute(runs = runs, now = now))
    }
}
