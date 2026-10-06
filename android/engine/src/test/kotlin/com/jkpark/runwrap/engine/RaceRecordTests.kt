package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 직접 입력한 대회 기록의 예측 통합 검증 (이슈 #35) — 엔진 표본 경쟁·가드,
/// RaceOutlookEngine 통합, 캐시 왕복, 자연어 해석(순수 로직)까지.
/// now = 2026-08-26T09:00:00Z 고정.
class RaceRecordTests {
    private val now = iso("2026-08-26T09:00:00Z")

    private fun at(offsetSec: Double) = instantSince1970(now.timeIntervalSince1970 + offsetSec)

    private fun record(race: RaceDistance, timeSec: Double, daysAgo: Double): RaceRecord =
        RaceRecord(id = UUID.randomUUID().toString().uppercase(), race = race, timeSec = timeSec,
                   date = at(-daysAgo * 86_400))

    private fun run(km: Double, timeSec: Double, daysAgo: Double): RunSummary =
        RunSummary(id = UUID.randomUUID().toString().uppercase(), start = at(-daysAgo * 86_400),
                   durationSec = timeSec, distanceMeters = km * 1_000, avgHeartRate = 165.0)

    private fun ready(status: RaceOutlookEngine.Status): RaceOutlookEngine.Outlook? =
        (status as? RaceOutlookEngine.Status.ready)?.outlook

    // MARK: - 엔진 — racePrediction 표본 경쟁

    @Test
    @DisplayName("러닝 표본이 없어도 대회 기록만으로 예측한다 — 표본 창(84일) 밖 180일 전이라도")
    fun recordAloneEnablesPrediction() {
        val best = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.half, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 180.0))))
        // 같은 종목이라 Riegel 배율 1 → 기록 그대로. 대회 기록은 전력이라 EF 환산(빠른 끝) 없음
        assertEquals(6_330.0, best.riegelSec)
        assertNull(best.effortSec)
        assertTrue(best.isRaceRecord)
        assertEquals(0, best.windowDays)
    }

    @Test
    @DisplayName("대회 기록의 타 종목 외삽 — 하프 1:45:30으로 풀코스 Riegel")
    fun recordExtrapolatesToOtherDistance() {
        val best = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 180.0))))
        // 6,330 × (42.195/21.0975)^1.06 = 6,330 × 2^1.06 ≈ 13,197.6
        assertTrue(abs(best.riegelSec - 13_197.6) < 1)
    }

    @Test
    @DisplayName("외삽 상한 가드 — 5K 기록으로 풀코스(8.4배)는 예측하지 않는다")
    fun recordRespectsExtrapolationCap() {
        assertNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.full, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.fiveK, timeSec = 1_200.0, daysAgo = 30.0))))
    }

    @Test
    @DisplayName("최대 나이 가드 — 2년(730일) 넘은 기록은 표본이 아니다")
    fun recordAgeGuard() {
        assertNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.half, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 800.0))))
    }

    @Test
    @DisplayName("훈련 표본과 경쟁 — 느린 끝(Riegel)이 빠른 쪽이 이긴다")
    fun recordCompetesWithTrainingSamples() {
        // 훈련: 3일 전 10km 3,000초(5:00/km) → 10K Riegel 3,000초
        val runs = listOf(run(km = 10.0, timeSec = 3_000.0, daysAgo = 3.0))
        // 100일 전 10K 대회 2,700초가 더 빠르다 → 기록이 근거
        val recordWins = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.tenK, runs = runs, now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.tenK, timeSec = 2_700.0, daysAgo = 100.0))))
        assertTrue(recordWins.isRaceRecord)
        assertEquals(2_700.0, recordWins.riegelSec)
        // 3,300초 대회 기록은 훈련 표본에 진다 → 훈련 근거(4주 창) 유지
        val trainingWins = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.tenK, runs = runs, now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.tenK, timeSec = 3_300.0, daysAgo = 100.0))))
        assertFalse(trainingWins.isRaceRecord)
        assertEquals(28, trainingWins.windowDays)
    }

    @Test
    @DisplayName("VO₂max 추세 배율이 대회 기록에도 적용된다")
    fun recordGetsFitnessAdjustment() {
        // 기록 시점(180일 전) 평균 44.0, 현재 46.0 → 배율 44/46 ≈ 0.9565
        val recordOffset = -180.0 * 86_400
        val vo2 = listOf(
            TrainingGuideEngine.Vo2MaxSample(at(recordOffset - 5 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(recordOffset + 5 * 86_400), 44.0),
            TrainingGuideEngine.Vo2MaxSample(at(-7.0 * 86_400), 46.0),
            TrainingGuideEngine.Vo2MaxSample(at(-2.0 * 86_400), 46.0),
        )
        val best = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.half, runs = emptyList(), now = now, zone = testZone, vo2MaxSamples = vo2,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 180.0))))
        // 6,330 × 44/46 ≈ 6,054.8 — 그때보다 좋아졌으니 예측이 빨라진다
        assertTrue(abs(best.riegelSec - 6_054.8) < 0.5)
    }

    @Test
    @DisplayName("타당성 가드 — 비현실 페이스 기록은 후보에서 빠지고 정상 기록이 근거가 된다 (이슈 #93)")
    fun implausibleRecordExcluded() {
        // '1시간 45분'을 1분 45초로 오독한 하프 105초 = 약 5초/km — 150초/km 미만이라 제외
        assertNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.half, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 105.0, daysAgo = 30.0))))
        // 휠 실수 5K 7:59:59(28,799초) = 5,759.8초/km — 1,200초/km 초과라 제외
        assertNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.fiveK, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.fiveK, timeSec = 28_799.0, daysAgo = 30.0))))
        // 오독 기록이 섞여 있어도 Riegel 최솟값으로 이기지 못하고 정상 기록(6,330초)이 근거
        val best = assertNotNull(TrainingGuideEngine.racePrediction(
            goal = RaceDistance.half, runs = emptyList(), now = now, zone = testZone,
            raceRecords = listOf(record(RaceDistance.half, timeSec = 105.0, daysAgo = 30.0),
                                 record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 180.0))))
        assertEquals(6_330.0, best.riegelSec)
    }

    @Test
    @DisplayName("타당성 판정 — 페이스 150~1,200초/km 안만 통과, 0초·0km는 실패 (이슈 #93)")
    fun plausibilityBounds() {
        // 5K: 750초(2′30″/km)·6,000초(20′00″/km)가 양 끝
        assertTrue(RaceRecord.isPlausible(timeSec = 750.0, km = 5.0))
        assertFalse(RaceRecord.isPlausible(timeSec = 749.0, km = 5.0))
        assertTrue(RaceRecord.isPlausible(timeSec = 6_000.0, km = 5.0))
        assertFalse(RaceRecord.isPlausible(timeSec = 6_001.0, km = 5.0))
        assertTrue(RaceRecord.isPlausible(timeSec = 6_330.0, km = RaceDistance.half.km))
        assertFalse(RaceRecord.isPlausible(timeSec = 0.0, km = 5.0))
        assertFalse(RaceRecord.isPlausible(timeSec = 1_500.0, km = 0.0))
    }

    // MARK: - RaceOutlookEngine 통합

    @Test
    @DisplayName("기록만으로 ready — 근거가 대회 기록임을 Outlook이 밝힌다")
    fun outlookFromRecordOnly() {
        // 10월 대회 — 평년 15.0°C/63%는 열 점수 23 < 38이라 보정 없음 (더위 변수 제거)
        val raceDate = iso("2026-10-18T00:00:00Z")
        val status = RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = raceDate, runs = emptyList(), now = now,
            zone = testZone,
            raceRecords = listOf(record(RaceDistance.tenK, timeSec = 2_700.0, daysAgo = 100.0)))
        val outlook = assertNotNull(ready(status))
        assertEquals(2_700.0, outlook.predictedSec)
        assertNull(outlook.predictedFastSec)
        assertTrue(outlook.isRaceRecord)
        assertEquals(RRTone.improving, outlook.tone)   // 느린 끝이 목표 안 → 달성권
    }

    // MARK: - 대회 기록 열 중립 환산 (이슈 #100)

    /// 7월 17일 10K 3,000초(5:00/km) 대회 기록 — 7월 평년 25.3°C/76%.
    /// 이슬점(Magnus) ≈ 20.76°C → 열 점수 ≈ 46.06 → 보정량 8×1.5 + 0.06×3 ≈ 12.19초/km
    /// → 중립 환산 3,000 − 12.19×10 ≈ 2,878.1초
    private val julyTenKRecord: RaceRecord
        get() = RaceRecord(id = UUID.randomUUID().toString().uppercase(), race = RaceDistance.tenK,
                           timeSec = 3_000.0, date = iso("2026-07-17T09:00:00Z"))

    @Test
    @DisplayName("7월 대회 기록 → 10월 대회 — 기록 월 더위를 빼 예측이 원본보다 빨라진다")
    fun julyRecordNeutralizedForOctoberRace() {
        // 10월 평년 15.0°C/63%는 열 점수 ≈ 23 < 38 → 대회 월 보정 0
        val raceDate = iso("2026-10-18T00:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 2_700.0, raceDate = raceDate, runs = emptyList(), now = now,
            zone = testZone, raceRecords = listOf(julyTenKRecord))))
        assertTrue(outlook.isRaceRecord)
        assertTrue(abs(outlook.sampleHeatDeltaSecPerKm - 12.19) < 0.05)
        assertEquals(0.0, outlook.heatDeltaSecPerKm)
        // 중립 2,878.1초 + 대회 월 보정 0 = 2,878.1초 (수정 전: 원본 3,000초 그대로)
        assertTrue(abs(outlook.predictedSec - 2_878.1) < 0.5)
    }

    @Test
    @DisplayName("7월 대회 기록 → 8월 대회 — 중립화 뒤 대회 월 더위가 다시 붙어 원본에 가깝다")
    fun julyRecordNeutralizedThenAugustHeatAdded() {
        // 8월 평년 26.1°C/74% → 이슬점 ≈ 21.10°C → 열 점수 ≈ 47.20 → 보정량 12 + 1.20×3 ≈ 15.60초/km
        // 예측 = (300 − 12.19 + 15.60) × 10 ≈ 3,034.1초 — 원본 3,000초보다 8월이 조금 더 더운 만큼만 느리다
        // (수정 전: 3,000 + 15.60×10 = 3,156초 — 7월 더위가 한 번 더 실렸다)
        val raceDate = iso("2026-08-30T00:00:00Z")
        val outlook = assertNotNull(ready(RaceOutlookEngine.status(
            race = RaceDistance.tenK, goalSec = 3_000.0, raceDate = raceDate, runs = emptyList(), now = now,
            zone = testZone, raceRecords = listOf(julyTenKRecord))))
        assertTrue(abs(outlook.sampleHeatDeltaSecPerKm - 12.19) < 0.05)
        assertTrue(abs(outlook.heatDeltaSecPerKm - 15.60) < 0.05)
        assertTrue(abs(outlook.predictedSec - 3_034.1) < 0.5)
    }

    // MARK: - 캐시

    @Test
    @DisplayName("캐시 왕복 — 저장한 기록을 그대로 복원한다")
    fun cacheRoundTrip() {
        val dir = makeTempDir()
        try {
            val records = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 100.0),
                                 record(RaceDistance.tenK, timeSec = 2_700.0, daysAgo = 30.0))
            RaceRecordCache.save(records, dir)
            // (Android: load는 now를 필수로 받는다 — 정상 파일이라 격리 시각은 쓰이지 않는다)
            assertEquals(records, RaceRecordCache.load(dir, now))
        } finally {
            dir.deleteRecursively()
        }
    }

    // 이슈 #66 — 디코딩 실패가 전체 기록 유실로 번지지 않게. 격리 파일 시각은 now 고정

    private fun makeTempDir(): File = Files.createTempDirectory("race-record-tests-").toFile()

    private fun corruptFiles(dir: File): List<String> =
        (dir.list() ?: emptyArray()).filter { it.startsWith("race-records.corrupt-") }

    @Test
    @DisplayName("깨진 원소 하나는 건너뛰고 나머지 2건을 살린다 — 원본은 corrupt 파일로 격리")
    fun lenientLoadSalvagesAndQuarantines() {
        val dir = makeTempDir()
        try {
            val records = listOf(record(RaceDistance.half, timeSec = 6_330.0, daysAgo = 100.0),
                                 record(RaceDistance.tenK, timeSec = 2_700.0, daysAgo = 30.0))
            // 정상 2건 사이에 raw value가 바뀐 종목("marathon") 원소 1건을 끼운다
            val array = (EngineJson.parseToJsonElement(
                EngineJson.encodeToString(ListSerializer(RaceRecord.serializer()), records)) as JsonArray)
                .toMutableList()
            array.add(1, buildJsonObject {
                put("id", UUID.randomUUID().toString().uppercase())
                put("race", "marathon")
                put("timeSec", JsonPrimitive(12_000))
                put("date", JsonPrimitive(0))
            })
            val file = File(dir, RaceRecordCache.filename)
            val original = JsonArray(array).toString().toByteArray()
            file.writeBytes(original)

            assertEquals(records, RaceRecordCache.load(dir, now))
            // 격리 파일 = race-records.corrupt-<now ISO8601>.json, 내용은 원본 그대로
            val corrupt = File(dir, "race-records.corrupt-2026-08-26T09:00:00Z.json")
            assertContentEquals(original, corrupt.readBytes())
            assertEquals(1, corruptFiles(dir).size)
            // 살린 2건은 다시 저장돼 다음 실행에도 남고, 이번엔 격리가 일어나지 않는다
            assertEquals(records, RaceRecordCache.load(dir, at(60.0)))
            assertEquals(1, corruptFiles(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("파일 전체가 깨져도 원본을 격리한 뒤 빈 목록을 돌려준다")
    fun wholeFileCorruptionQuarantines() {
        val dir = makeTempDir()
        try {
            val original = "{ not json".toByteArray()
            File(dir, RaceRecordCache.filename).writeBytes(original)

            assertEquals(emptyList(), RaceRecordCache.load(dir, now))
            val corrupt = File(dir, "race-records.corrupt-2026-08-26T09:00:00Z.json")
            assertContentEquals(original, corrupt.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("파일이 없으면 nil — 첫 실행은 격리 파일을 만들지 않는다")
    fun missingFileReturnsNil() {
        val dir = makeTempDir()
        try {
            assertNull(RaceRecordCache.load(dir, now))
            assertTrue(corruptFiles(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("모두 정상이면 그대로 읽고 격리 파일을 만들지 않는다")
    fun validFileLoadsUnchanged() {
        val dir = makeTempDir()
        try {
            val records = listOf(record(RaceDistance.fiveK, timeSec = 1_200.0, daysAgo = 10.0),
                                 record(RaceDistance.full, timeSec = 14_400.0, daysAgo = 200.0))
            RaceRecordCache.save(records, dir)
            val file = File(dir, RaceRecordCache.filename)
            val before = file.readBytes()

            assertEquals(records, RaceRecordCache.load(dir, now))
            assertContentEquals(before, file.readBytes())   // 다시 저장하지도 않는다
            assertTrue(corruptFiles(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // MARK: - 자연어 해석 (RaceResultParser.interpret — 순수 로직)

    private fun interpret(distanceKm: Double? = null, hours: Int? = null, minutes: Int? = null,
                          seconds: Int? = null, year: Int? = null, month: Int? = null,
                          day: Int? = null): RaceResultParser.Parsed? =
        RaceResultParser.interpret(distanceKm = distanceKm, hours = hours, minutes = minutes, seconds = seconds,
                                   year = year, month = month, day = day, now = now, zone = testZone)

    @Test
    @DisplayName("시·분·초 합산은 코드가 한다 — 1시간 45분 30초 = 6,330초, 105분 = 6,300초")
    fun interpretAssemblesTime() {
        val parsed = assertNotNull(interpret(hours = 1, minutes = 45, seconds = 30))
        assertEquals(6_330.0, parsed.timeSec)
        val minutesOnly = assertNotNull(interpret(minutes = 105))
        assertEquals(6_300.0, minutesOnly.timeSec)
    }

    @Test
    @DisplayName("거리 → 종목 매칭 — 공인 거리 ±15% 안이면 해당 종목, 밖이면 실패")
    fun interpretMapsDistance() {
        assertEquals(RaceDistance.half, interpret(distanceKm = 21.1)?.race)
        assertEquals(RaceDistance.tenK, interpret(distanceKm = 10.0)?.race)
        // 3km는 어느 공인 종목과도 15% 밖 — 다른 필드도 없으면 파싱 실패(nil)
        assertNull(interpret(distanceKm = 3.0))
    }

    @Test
    @DisplayName("연도 없는 월은 가장 가까운 과거 — 지금 8월에 '10월'은 작년, '3월'은 올해")
    fun interpretResolvesRelativeYear() {
        val october = assertNotNull(interpret(month = 10)?.date).atZone(testZone)
        assertTrue(october.year == 2025 && october.monthValue == 10 && october.dayOfMonth == 15)
        val march = assertNotNull(interpret(month = 3)?.date).atZone(testZone)
        assertTrue(march.year == 2026 && march.monthValue == 3)
    }

    @Test
    @DisplayName("미래 연도는 오독 — 날짜만 버리고 종목·기록은 살린다")
    fun interpretDropsFutureYear() {
        val parsed = assertNotNull(interpret(distanceKm = 10.0, minutes = 50, year = 2027, month = 3))
        assertNull(parsed.date)
        assertEquals(RaceDistance.tenK, parsed.race)
        assertEquals(3_000.0, parsed.timeSec)
    }

    @Test
    @DisplayName("두 자리 연도는 2000년대 — '25년'은 2025년, 최근 2년 밖 연도는 날짜만 버린다 (이슈 #93)")
    fun interpretValidatesYear() {
        // now 2026-08-26 → 허용 연도 2024...2026
        val twoDigit = assertNotNull(interpret(year = 25, month = 10, day = 3)?.date).atZone(testZone)
        assertTrue(twoDigit.year == 2025 && twoDigit.monthValue == 10 && twoDigit.dayOfMonth == 3)
        assertNotNull(interpret(year = 2024, month = 3)?.date)
        // 2023년(3년 전)·'23년'은 범위 밖 → 날짜 nil, 종목은 살린다
        for (year in listOf(2023, 23)) {
            val parsed = assertNotNull(interpret(distanceKm = 10.0, year = year, month = 10))
            assertNull(parsed.date)
            assertEquals(RaceDistance.tenK, parsed.race)
        }
    }

    @Test
    @DisplayName("연도가 있으면 그 달 실제 일수로 검증 — '9월 31일'은 10월 1일이 아니라 9월 15일 (이슈 #93)")
    fun interpretValidatesDayInMonth() {
        val september = assertNotNull(interpret(year = 2025, month = 9, day = 31)?.date).atZone(testZone)
        assertTrue(september.monthValue == 9 && september.dayOfMonth == 15)
        // 윤년 2024년 2월 29일은 실재하는 날 — 그대로 둔다
        val leap = assertNotNull(interpret(year = 2024, month = 2, day = 29)?.date).atZone(testZone)
        assertTrue(leap.monthValue == 2 && leap.dayOfMonth == 29)
    }

    @Test
    @DisplayName("시·분·초 범위 밖·음수 필드는 버린다 — 시 0~7, 분·초 0~59 (이슈 #93)")
    fun interpretValidatesTimeFields() {
        // 1시간 −5분 → 분을 버리고 3,600초 (예전엔 3,300초)
        assertEquals(3_600.0, interpret(hours = 1, minutes = -5)?.timeSec)
        // 1시간 60분 75초 → 분·초를 버리고 3,600초
        assertEquals(3_600.0, interpret(hours = 1, minutes = 60, seconds = 75)?.timeSec)
        // 8시간은 휠 범위(0..<8) 밖 → 시를 버리고 30분 = 1,800초
        assertEquals(1_800.0, interpret(hours = 8, minutes = 30)?.timeSec)
        // 분만 오면 8시간 미만(479분)까지 허용 — 480분은 버린다
        assertEquals(28_740.0, interpret(minutes = 479)?.timeSec)
        // 셋 다 버려지면 timeSec nil — 다른 필드도 없으면 파싱 실패(nil)
        assertNull(interpret(hours = -1, seconds = -3))
        assertNull(interpret(minutes = 480))
        val parsed = assertNotNull(interpret(distanceKm = 10.0, hours = 9))
        assertNull(parsed.timeSec)
        assertEquals(RaceDistance.tenK, parsed.race)
    }

    @Test
    @DisplayName("거대값도 트랩 없이 버린다 — 범위 검사가 곱셈보다 먼저다 (이슈 #93)")
    fun interpretHugeValuesDoNotOverflow() {
        assertNull(interpret(hours = Int.MAX_VALUE, minutes = Int.MAX_VALUE, seconds = Int.MAX_VALUE,
                             year = Int.MAX_VALUE))
        assertEquals(30.0, interpret(minutes = Int.MAX_VALUE, seconds = 30)?.timeSec)
    }
}
