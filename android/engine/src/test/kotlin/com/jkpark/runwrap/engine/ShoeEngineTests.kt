package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/// 러닝화 마일리지 엔진·캐시 검증 (이슈 #171) — 누적 합산, 자동 배정, 교체 경계, 진행 비율,
/// 러닝화 묻기 대상(이슈 #206), shoes.json 왕복·격리·옛 스키마, 사진 저장. now = 2026-09-30T09:00:00Z 고정.
/// (Android: 사진 저장 2건(imageStoreRoundTrip·imageStoreRejectsNonImage)은 iOS ShoeImageStore가
///  CoreGraphics·ImageIO에 기대는 플랫폼 코드라 :engine에 옮기지 않는다)
class ShoeEngineTests {
    private val now = iso("2026-09-30T09:00:00Z")

    private fun newID(): String = UUID.randomUUID().toString().uppercase()
    private fun daysBefore(days: Double): Instant = instantSince1970(now.timeIntervalSince1970 - days * 86_400)

    private fun shoe(startKm: Double = 0.0, replaceKm: Double = 600.0,
                     isRetired: Boolean = false, daysAgo: Double = 90.0): Shoe =
        Shoe(name = "페가수스 41", startKm = startKm, replaceKm = replaceKm,
             isRetired = isRetired, createdAt = daysBefore(daysAgo))

    private fun run(km: Double?, daysAgo: Double): RunSummary =
        RunSummary(id = newID(), start = daysBefore(daysAgo),
                   durationSec = 3_600.0, distanceMeters = km?.let { it * 1_000 }, avgHeartRate = 150.0)

    // MARK: - 누적 거리

    @Test
    @DisplayName("누적 합산 — 등록 전 480km + 배정 3세션 12.5+8+10.2 = 510.7km, 다른 신발·미배정은 빠진다")
    fun mileageSumsAssignedRuns() {
        val target = shoe(startKm = 480.0)
        val other = shoe()
        val runs = listOf(run(12.5, 1.0), run(8.0, 3.0), run(10.2, 5.0),
                          run(20.0, 7.0), run(15.0, 9.0))
        val assignments = mapOf(runs[0].id to target.id,
                                runs[1].id to target.id,
                                runs[2].id to target.id,
                                runs[3].id to other.id)   // 20km는 다른 신발, 15km는 미배정
        val mileage = ShoeEngine.mileageKm(shoe = target, runs = runs, assignments = assignments)
        assertTrue(abs(mileage - 510.7) < 0.000_1)
    }

    @Test
    @DisplayName("거리 없는 세션·없음 표식은 0으로 센다")
    fun mileageIgnoresMissingDistanceAndNoShoe() {
        val target = shoe(startKm = 100.0)
        val runs = listOf(run(null, 1.0), run(5.0, 2.0))
        val assignments = mapOf(runs[0].id to target.id,
                                runs[1].id to ShoeEngine.noShoeID)
        assertEquals(100.0, ShoeEngine.mileageKm(shoe = target, runs = runs, assignments = assignments))
    }

    // MARK: - 자동 배정

    @Test
    @DisplayName("자동 배정은 빈 세션만 채운다 — 다른 신발·없음 표식 배정을 덮어쓰지 않는다")
    fun autoAssignKeepsExisting() {
        val defaultID = newID()
        val otherID = newID()
        val runs = listOf(run(5.0, 1.0), run(6.0, 2.0), run(7.0, 3.0))
        val existing = mapOf(runs[0].id to otherID,
                             runs[1].id to ShoeEngine.noShoeID)
        val result = ShoeEngine.autoAssign(runs = runs, defaultShoeID = defaultID, assignments = existing)
        assertEquals(otherID, result[runs[0].id])
        assertEquals(ShoeEngine.noShoeID, result[runs[1].id])
        assertEquals(defaultID, result[runs[2].id])
        assertEquals(3, result.size)
    }

    @Test
    @DisplayName("기본 신발이 없으면 배정표를 그대로 돌려준다")
    fun autoAssignWithoutDefaultIsNoop() {
        val runs = listOf(run(5.0, 1.0), run(6.0, 2.0))
        val existing = mapOf(runs[0].id to newID())
        assertEquals(existing, ShoeEngine.autoAssign(runs = runs, defaultShoeID = null, assignments = existing))
        assertTrue(ShoeEngine.autoAssign(runs = runs, defaultShoeID = null, assignments = emptyMap()).isEmpty())
    }

    @Test
    @DisplayName("since 이전 세션은 채우지 않는다 — 등록 전 거리는 startKm가 이미 담고 있다")
    fun autoAssignRespectsSince() {
        val defaultID = newID()
        val before = run(10.0, 10.0)
        val after = run(5.0, 2.0)
        val result = ShoeEngine.autoAssign(runs = listOf(before, after), defaultShoeID = defaultID,
                                           assignments = emptyMap(),
                                           since = daysBefore(5.0))
        assertEquals(mapOf(after.id to defaultID), result)
    }

    // MARK: - 교체 판정·진행 비율

    @Test
    @DisplayName("교체 경계 — 600 ≥ 600은 교체, 599.9는 아직")
    fun needsReplacementBoundary() {
        val target = shoe(replaceKm = 600.0)
        assertTrue(ShoeEngine.needsReplacement(shoe = target, mileageKm = 600.0))
        assertFalse(ShoeEngine.needsReplacement(shoe = target, mileageKm = 599.9))
    }

    @Test
    @DisplayName("진행 비율은 0…1로 자른다 — 300/600 = 0.5, 900/600 → 1, 음수 → 0, 기준 0 → 0")
    fun progressClamps() {
        assertEquals(0.5, ShoeEngine.progress(mileageKm = 300.0, replaceKm = 600.0))
        assertEquals(1.0, ShoeEngine.progress(mileageKm = 900.0, replaceKm = 600.0))
        assertEquals(0.0, ShoeEngine.progress(mileageKm = -10.0, replaceKm = 600.0))
        assertEquals(0.0, ShoeEngine.progress(mileageKm = 100.0, replaceKm = 0.0))
    }

    @Test
    @DisplayName("진행 바 톤 — 90% 이상이면 주의, 미만이면 유지")
    fun progressTone() {
        assertEquals(RRTone.caution, ShoeEngine.tone(progress = 0.9))
        assertEquals(RRTone.steady, ShoeEngine.tone(progress = 0.89))
    }

    @Test
    @DisplayName("기준 초과분 — 630/600은 30/630, 기준 이하·기준 0이면 0")
    fun overshoot() {
        // 누적 630 중 기준을 넘긴 30km의 몫 → 막대 끝 약 4.8%
        assertEquals(30.0 / 630.0, ShoeEngine.overshoot(mileageKm = 630.0, replaceKm = 600.0))
        assertEquals(0.0, ShoeEngine.overshoot(mileageKm = 600.0, replaceKm = 600.0))
        assertEquals(0.0, ShoeEngine.overshoot(mileageKm = 300.0, replaceKm = 600.0))
        assertEquals(0.0, ShoeEngine.overshoot(mileageKm = 100.0, replaceKm = 0.0))
    }

    // MARK: - 러닝화 묻기 대상 (이슈 #206)

    @Test
    @DisplayName("기준 시각이 없으면(첫 실행) 빈 배열 — 지난 기록을 묻지 않는다")
    fun pendingRunsWithoutBaselineIsEmpty() {
        val runs = listOf(run(5.0, 1.0), run(6.0, 2.0))
        assertTrue(ShoeEngine.pendingRuns(runs = runs, promptedThrough = null, now = now).isEmpty())
    }

    @Test
    @DisplayName("기준 시각보다 늦게 시작한 러닝만 — 기준과 같은 시각은 이미 물어본 러닝이라 뺀다")
    fun pendingRunsOnlyAfterBaseline() {
        val old = run(5.0, 5.0)
        val asked = run(6.0, 3.0)
        val new = run(7.0, 1.0)
        val result = ShoeEngine.pendingRuns(runs = listOf(old, asked, new), promptedThrough = asked.start, now = now)
        assertEquals(listOf(new.id), result.map { it.id })
    }

    @Test
    @DisplayName("14일 창 — 14일 전 경계는 포함, 15일 전은 기준 이후여도 뺀다")
    fun pendingRunsRespectsWindow() {
        val outside = run(5.0, 15.0)
        val edge = run(6.0, 14.0)   // now - 14일 정각 = 창 시작(>=)
        val inside = run(7.0, 2.0)
        val result = ShoeEngine.pendingRuns(runs = listOf(outside, edge, inside),
                                            promptedThrough = daysBefore(30.0), now = now)
        assertEquals(listOf(edge.id, inside.id), result.map { it.id })
    }

    @Test
    @DisplayName("12개면 최근 10개만, 오래된 순 — 1~12일 전 중 10~1일 전이 남는다")
    fun pendingRunsCapsToMostRecentOldestFirst() {
        // 입력은 최근 순(1일 전 → 12일 전)으로 섞어 넣어 정렬을 함께 확인한다
        val runs = (1..12).map { run(it.toDouble(), it.toDouble()) }
        val result = ShoeEngine.pendingRuns(runs = runs,
                                            promptedThrough = daysBefore(13.0), now = now)
        assertEquals(ShoeEngine.promptMaxCount, result.size)
        // 11·12일 전 2개가 잘리고 10일 전부터 1일 전까지 시작 시각 오름차순
        assertEquals(runs.subList(0, 10).reversed().map { it.id }, result.map { it.id })
    }

    // MARK: - 캐시

    private fun makeTempDir(): File =
        Files.createTempDirectory("shoe-tests-${UUID.randomUUID()}").toFile()

    @Test
    @DisplayName("캐시 왕복 — 신발·기본 신발·배정표를 그대로 복원한다")
    fun cacheRoundTrip() {
        val dir = makeTempDir()
        try {
            val a = shoe(startKm = 480.0)
            val b = shoe(replaceKm = 800.0, isRetired = true)
            val file = ShoeFile(schemaVersion = ShoeFile.currentSchemaVersion, shoes = listOf(a, b),
                                defaultShoeID = a.id,
                                assignments = mapOf(newID() to a.id, newID() to ShoeEngine.noShoeID))
            ShoeCache.save(file, directory = dir)
            assertEquals(file, ShoeCache.load(directory = dir, now = now))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("파일이 없으면 nil")
    fun missingFileReturnsNil() {
        val dir = makeTempDir()
        try {
            assertNull(ShoeCache.load(directory = dir, now = now))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("깨진 JSON이면 nil — 원본은 shoes.corrupt-<now>.json으로 격리")
    fun corruptFileReturnsNilAndQuarantines() {
        val dir = makeTempDir()
        try {
            val original = "{ not json".toByteArray()
            File(dir, ShoeCache.filename).writeBytes(original)

            assertNull(ShoeCache.load(directory = dir, now = now))
            val corrupt = File(dir, "shoes.corrupt-2026-09-30T09:00:00Z.json")
            assertContentEquals(original, corrupt.readBytes())
            assertFalse(File(dir, ShoeCache.filename).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    @DisplayName("옛 shoes.json(imageFile 없음)도 읽힌다 — 스키마 규칙 #66, imageFile은 nil")
    fun decodesLegacyFileWithoutImage() {
        // 이슈 #206 이전 앱이 쓴 모양. createdAt은 JSONEncoder 기본(2001-01-01 기준 초)
        val json = """
        {"schemaVersion":1,"defaultShoeID":"6F1C3A52-0D3B-4C4B-9E58-2B7A1F0C9D11",
         "shoes":[{"id":"6F1C3A52-0D3B-4C4B-9E58-2B7A1F0C9D11","name":"페가수스 41",
                   "startKm":480,"replaceKm":600,"isRetired":false,"createdAt":0}],
         "assignments":{}}
        """
        val file = EngineJson.decodeFromString<ShoeFile>(json)
        val shoe = assertNotNull(file.shoes.firstOrNull())
        assertEquals("페가수스 41", shoe.name)
        assertEquals(480.0, shoe.startKm)
        assertNull(shoe.imageFile)
    }
}
