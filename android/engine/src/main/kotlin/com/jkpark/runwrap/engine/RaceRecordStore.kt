package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray

/// 직접 입력한 대회 기록 (이슈 #35) — 다른 앱·다른 워치·기록증에만 있어 HealthKit에 없는
/// 지난 대회 완주 기록을 예측 표본으로 쓰기 위한 모델. 대회 기록은 정의상 전력 노력이라
/// 훈련 표본에 없는 "힘든 노력의 증거"이고, 예측이 실제보다 느리게 나오는 문제의
/// 가장 직접적인 해법이다 (6개월 전 하프 기록이 이번 주 이지런보다 좋은 재료다).
///
/// 스키마 규칙 (이슈 #66): 저장된 JSON을 옛 버전 앱·새 버전 앱이 모두 읽어야 한다.
/// 새 필드는 반드시 옵셔널이거나 decodeIfPresent + 기본값으로 추가하고,
/// RaceDistance의 raw value(fiveK·tenK·half·full)는 절대 바꾸지 않는다 —
/// 어긋난 원소는 로드 때 건너뛰어지고(원본은 corrupt 파일로 격리) 목록에서 빠진다.
/// (Android: id는 UUID 대신 문자열이다. RaceRecordStore 클래스는 ProgressSnapshot에 기대므로 :app에 둔다)
@Serializable
data class RaceRecord(
    val id: String,
    /// 종목 — 공인 거리(km)는 race.km로 얻는다. 수동 입력이 종목 선택이라 임의 거리는 없다
    val race: RaceDistance,
    /// 완주 기록(초)
    val timeSec: Double,
    /// 대회 날짜 — 오래된 기록의 시점 차이는 엔진의 VO₂max 추세 배율이 보정한다
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val date: Instant,
) {
    companion object {
        /// 기록 타당성 (이슈 #93) — 페이스가 세계기록 수준(2′30″/km)보다 빠르거나 20′00″/km보다
        /// 느리면 오독('1시간 45분' → 1분 45초)이나 휠 실수다. 이런 기록 하나가 Riegel 최솟값으로
        /// 2년간 예측을 지배하지 않도록, 엔진 후보 필터와 입력 시트 저장 버튼이 같은 판정을 쓴다
        fun isPlausible(timeSec: Double, km: Double): Boolean {
            if (!(timeSec > 0 && km > 0)) return false
            return timeSec / km in
                TrainingGuideEngine.minGoalPaceSecPerKm..TrainingGuideEngine.maxRacePaceSecPerKm
        }
    }
}

/// Application Support/RunWrap/race-records.json — PBBaselineCache와 같은 패턴 (atomic write,
/// 기기 백업 제외). HealthKit 건강 데이터가 아니라 사용자 입력값이라 CloudKit 진행도 스냅샷에
/// 함께 백업한다(이슈 #118) — 재설치·기기 이전 때의 원본은 스냅샷이다(`ProgressSnapshot.raceRecords`).
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다. 백업 제외는 :app의 Auto Backup 규칙이 맡는다)
object RaceRecordCache {
    const val filename = "race-records.json"
    /// 지운 기록의 id 목록 (이슈 #118) — 스냅샷 합집합 병합에서 되살아나지 않게 하는 삭제 표식.
    /// 기록 파일과 분리한 이유: race-records.json은 `[RaceRecord]` 배열이라 모양을 바꾸면 옛 버전 앱이 못 읽는다(#66)
    const val deletedFilename = "race-record-tombstones.json"

    fun save(records: List<RaceRecord>, directory: File) =
        write(directory, filename, EngineJson.encodeToString(ListSerializer(RaceRecord.serializer()), records))

    /// 파일이 없으면 nil(첫 실행). 디코딩은 원소 단위로 관대하게 한다 (이슈 #66) —
    /// 예전엔 원소 하나만 어긋나도 통째로 nil이 되고, 스토어가 빈 목록으로 시작해
    /// 다음 저장이 전체 기록을 덮어썼다. 이제 깨진 원소만 건너뛰고 나머지를 살리며,
    /// 하나라도 실패하면 원본을 corrupt 파일로 옮겨 둔 뒤 살린 기록을 다시 저장한다.
    /// now 주입은 테스트용 — 격리 파일 이름의 시각
    fun load(directory: File, now: Instant): List<RaceRecord>? {
        val file = File(directory, filename)
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        }
        // 원소 하나의 디코딩 실패를 삼킨다 — 배열 디코딩이 통째로 실패하지 않게 한다
        val decoded: List<RaceRecord?>? = try {
            (EngineJson.parseToJsonElement(text) as? JsonArray)?.map { element ->
                try {
                    EngineJson.decodeFromJsonElement(RaceRecord.serializer(), element)
                } catch (_: IllegalArgumentException) {
                    null  // SerializationException은 IllegalArgumentException의 하위 타입이다
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        }
        val records = decoded?.filterNotNull() ?: emptyList()
        if (decoded != null && records.size == decoded.size) return records
        // 원본을 먼저 옮겨야 다음 save가 덮어쓰지 않는다. 옮기지 못하면 원본을 건드리지 않는다
        if (quarantine(file, now)) save(records, directory)
        return records
    }

    fun saveDeletedIDs(ids: List<String>, directory: File) =
        write(directory, deletedFilename, EngineJson.encodeToString(ListSerializer(String.serializer()), ids))

    /// 파일이 없거나 깨졌으면 빈 목록 — 표식이 사라져도 기록이 되살아날 뿐 잃는 것은 없다
    fun loadDeletedIDs(directory: File): List<String> = try {
        EngineJson.decodeFromString(ListSerializer(String.serializer()), File(directory, deletedFilename).readText())
    } catch (_: Exception) {
        emptyList()
    }

    /// 같은 폴더의 race-records.corrupt-<ISO8601>.json으로 옮긴다 — 성공 여부 반환
    /// (Android: Swift ISO8601DateFormatter 기본형과 같게 초 단위 UTC "…Z"로 적는다 —
    /// 그 포매터는 ms로 반올림한 뒤 초를 자르므로 .9995초부터는 다음 초가 된다)
    private fun quarantine(file: File, now: Instant): Boolean {
        val stamp = DateTimeFormatter.ISO_INSTANT.format(now.plusNanos(500_000).truncatedTo(ChronoUnit.SECONDS))
        val corrupt = File(file.parentFile, "race-records.corrupt-$stamp.json")
        return try {
            Files.move(file.toPath(), corrupt.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

    /// atomic write — 저장 실패는 조용히 삼킨다
    private fun write(directory: File, name: String, text: String) {
        try {
            directory.mkdirs()
            val target = File(directory, name)
            val temp = File.createTempFile(name, ".tmp", directory)
            try {
                temp.writeText(text)
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
