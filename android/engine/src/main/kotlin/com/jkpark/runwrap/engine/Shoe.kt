package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/// 러닝화 한 켤레 (이슈 #171) — 누적 거리로 교체 시점을 알려 주기 위한 사용자 입력 모델.
/// 러닝화 쿠션은 대략 500~800km에서 수명이 다한다는 게 업계 통념이라 교체 기준 기본값을 600km로 둔다.
///
/// 스키마 규칙 (이슈 #66): 저장된 JSON을 옛 버전 앱·새 버전 앱이 모두 읽어야 한다.
/// 새 필드는 반드시 옵셔널이거나 decodeIfPresent + 기본값으로 추가한다.
/// (Android: id는 iOS UUID 대신 대문자 UUID 문자열 — iOS JSON과 같은 모양이다.
///  기본값이 있는 필드에 `@Required`를 붙여 Swift 합성 디코더처럼 키가 없으면 디코딩에 실패하게 한다)
@Serializable
data class Shoe(
    @Required val id: String = UUID.randomUUID().toString().uppercase(),
    var name: String,
    /// 등록 전에 이미 달린 거리(km) — 앱을 쓰기 전 기록은 세션 배정 대신 이 값이 맡는다
    @Required var startKm: Double = 0.0,
    /// 교체 기준(km) — 누적이 이 값 이상이면 홈에 교체 안내 카드를 띄운다
    @Required var replaceKm: Double = defaultReplaceKm,
    /// 은퇴한 신발 — 목록 끝에 흐리게 남고, 세션 배정·자동 배정 후보에서 빠진다
    @Required var isRetired: Boolean = false,
    /// 사용자가 등록한 사진 파일 이름(경로 아님, `ShoeImageStore`가 위치를 안다) (이슈 #206).
    /// 옵셔널이라 이 필드가 없는 옛 shoes.json도 그대로 읽힌다(스키마 규칙 #66). nil이면 기본 일러스트
    var imageFile: String? = null,
    /// 등록 시각 — 자동 배정은 이 시각 이후 세션만 대상으로 한다(이전 거리는 startKm 몫)
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val createdAt: Instant,
) {
    companion object {
        const val defaultReplaceKm: Double = 600.0
    }
}

/// 러닝 후 러닝화 묻기 팝업의 @AppStorage 키 (이슈 #206) — shoes.json 스키마는 건드리지 않는다
object ShoeKey {
    /// 마지막으로 물어본 러닝의 시작 시각(timeIntervalSince1970, 0 = 아직 없음)
    const val promptedThrough = "shoe.promptedThrough"
    /// '다시 보지 않기' — true면 팝업·홈 등록 권유를 띄우지 않는다
    const val promptOptOut = "shoe.promptOptOut"
}

/// shoes.json의 최상위 모양 — 신발 목록 + 기본 신발 + 세션별 배정.
/// assignments 키는 세션 `RunSummary.id.uuidString`, 값은 신발 id(또는 `ShoeEngine.noShoeID`)
/// (Android: 키는 `RunSummary.id` 그대로 — Health Connect 레코드 id 문자열이다)
@Serializable
data class ShoeFile(
    var schemaVersion: Int,
    var shoes: List<Shoe>,
    var defaultShoeID: String? = null,
    var assignments: Map<String, String>,
) {
    companion object {
        const val currentSchemaVersion = 1
    }
}

/// 러닝화 마일리지 순수 로직 (이슈 #171) — Foundation만 쓴다. 화면은 여기서 낸 값을 그리기만 한다
object ShoeEngine {
    /// "없음"으로 명시한 세션의 배정 값 — 키를 지우면 다음 자동 배정이 기본 신발로 다시 채우므로
    /// 사용자가 고른 "없음"을 기억하려고 모두 0인 UUID를 표식으로 남긴다. 어떤 신발 id와도 같지 않다
    const val noShoeID = "00000000-0000-0000-0000-000000000000"

    /// 교체 기준 대비 이 비율 이상이면 진행 바를 주의 톤으로 바꾼다 (이슈 #171 — 90%)
    const val cautionRatio = 0.9

    /// 누적 거리(km) = 등록 전 거리 + 이 신발에 배정된 세션 거리 합. 거리 없는 세션은 0
    fun mileageKm(shoe: Shoe, runs: List<RunSummary>, assignments: Map<String, String>): Double =
        shoe.startKm + runs.fold(0.0) { sum, run ->
            if (assignments[run.id] == shoe.id) sum + (run.distanceKm ?: 0.0) else sum
        }

    /// 교체 시점인지 — 누적이 기준 이상(경계 포함)
    fun needsReplacement(shoe: Shoe, mileageKm: Double): Boolean =
        mileageKm >= shoe.replaceKm

    /// 배정이 없는 세션만 기본 신발로 채운 배정표를 돌려준다. 기존 배정("없음" 표식 포함)은 덮어쓰지 않는다.
    /// 기본 신발이 없으면 그대로. since가 있으면 그 시각 이후 시작한 세션만 채운다 —
    /// 등록 전 거리는 startKm가 이미 담고 있어 지난 세션까지 채우면 이중으로 센다
    fun autoAssign(runs: List<RunSummary>, defaultShoeID: String?,
                   assignments: Map<String, String>, since: Instant? = null): Map<String, String> {
        if (defaultShoeID == null) return assignments
        val result = LinkedHashMap(assignments)
        for (run in runs) {
            if (result[run.id] != null) continue
            if (since != null && run.start < since) continue
            result[run.id] = defaultShoeID
        }
        return result
    }

    /// 진행 바 비율 — 0…1로 자른다. 기준이 0 이하면 0
    fun progress(mileageKm: Double, replaceKm: Double): Double {
        if (!(replaceKm > 0)) return 0.0
        return min(max(mileageKm / replaceKm, 0.0), 1.0)
    }

    /// 기준 초과분 — 누적 거리 중 기준을 넘긴 몫의 비율(막대 끝에 덧칠할 길이). 넘지 않았거나 기준이 0 이하면 0.
    /// 예: 630 / 600 km → 30 / 630 ≈ 0.048
    fun overshoot(mileageKm: Double, replaceKm: Double): Double {
        if (!(replaceKm > 0 && mileageKm > replaceKm)) return 0.0
        return (mileageKm - replaceKm) / mileageKm
    }

    /// 진행 바 톤 — 기준의 90% 이상이면 주의, 아니면 유지
    fun tone(progress: Double): RRTone =
        if (progress >= cautionRatio) RRTone.caution else RRTone.steady

    /// 팝업으로 물을 기간·개수 상한 (이슈 #206) — 오래 쉬었다 열었을 때 카드 30장을 막는다
    const val promptWindowDays = 14
    const val promptMaxCount = 10

    /// 러닝화를 물어볼 새 러닝 (이슈 #206) — promptedThrough보다 늦게 시작했고 최근 14일 이내인 러닝을
    /// 오래된 순으로, 10개가 넘으면 최근 10개만. 넘친 러닝은 묻지 않고 자동 배정 결과로 둔다.
    /// promptedThrough가 nil(첫 실행·업데이트 직후)이면 빈 배열 — 지난 기록 전부를 묻지 않고
    /// 기준만 심는다(PB 베이스라인과 같은 방식, `HomeScreen.checkNewPBs`)
    fun pendingRuns(runs: List<RunSummary>, promptedThrough: Instant?, now: Instant): List<RunSummary> {
        if (promptedThrough == null) return emptyList()
        val windowStart = now.minusSeconds(promptWindowDays.toLong() * 86_400)
        val pending = runs
            .filter { it.start > promptedThrough && it.start >= windowStart }
            .sortedBy { it.start }
        return pending.takeLast(promptMaxCount)
    }
}

/// Application Support/RunWrap/shoes.json — RaceRecordCache와 같은 패턴 (atomic write, 기기 백업 제외,
/// 깨진 파일 격리). 대회 기록과 달리 iCloud 진행도 스냅샷에는 넣지 않는다 (이슈 #171 결정)
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다(iOS 기본 경로처럼 없으면 만든다).
///  백업 제외는 :app의 Auto Backup 규칙이 맡는다)
object ShoeCache {
    const val filename = "shoes.json"

    fun save(file: ShoeFile, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(file))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
            // iOS `try?`와 같이 저장 실패는 조용히 삼킨다
        }
    }

    /// 파일이 없으면 nil(첫 실행). 깨졌으면 원본을 corrupt 파일로 옮겨 둔 뒤 nil —
    /// 옮겨 두지 않으면 빈 상태로 시작한 스토어의 다음 저장이 원본을 덮어쓴다 (이슈 #66).
    /// now 주입은 테스트용 — 격리 파일 이름의 시각 (Android: 기본값 없이 :app이 넘긴다)
    fun load(directory: File, now: Instant): ShoeFile? {
        val file = File(directory, filename)
        val data = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        try {
            return EngineJson.decodeFromString<ShoeFile>(data)
        } catch (_: Exception) {
            // 깨진 파일 — 아래에서 격리한다
        }
        quarantine(file, now)
        return null
    }

    /// 같은 폴더의 shoes.corrupt-<ISO8601>.json으로 옮긴다
    /// (Android: `ISO8601DateFormatter`는 밀리초 반올림 뒤 초를 버린다 — 실측(0.9995초 → 다음 초)대로 맞춘다.
    ///  이미 같은 이름이 있으면 iOS `moveItem`처럼 옮기지 않는다)
    private fun quarantine(file: File, now: Instant) {
        val millis = now.epochSecond * 1_000 + (now.nano + 500_000) / 1_000_000
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS))
        val corrupt = File(file.parentFile, "shoes.corrupt-$stamp.json")
        try {
            Files.move(file.toPath(), corrupt.toPath())
        } catch (_: Exception) {
            // iOS `try?`와 같이 실패는 무시한다
        }
    }
}
