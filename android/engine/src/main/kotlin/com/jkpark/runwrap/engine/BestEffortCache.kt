package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString

/// 워크아웃 UUID → [목표 거리(m): 베스트 에포트(초)] (이슈 #166)
/// (Android: 워크아웃 id는 Health Connect 레코드 id 문자열이다 — UUID 형식이 아니다)
typealias BestEffortTable = Map<String, Map<Double, Double>>

/// Application Support/RunWrap/best-efforts.json — PBBaselineCache와 같은 패턴 (atomic write,
/// 백업 제외). 워크아웃 데이터는 불변이라 한 번 계산한 값은 영구 캐시한다 — 거리 샘플을
/// 워크아웃마다 다시 읽는 비용을 기동마다 치르지 않으려고. 샘플이 없거나 1K 미만인
/// 워크아웃도 빈 dict로 남겨 재시도하지 않는다. 저장 실패는 조용히 삼킨다: 다음 기동에 다시 계산할 뿐이다.
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다. 백업 제외는 :app의 Auto Backup 규칙이 맡는다)
object BestEffortCache {
    const val filename = "best-efforts.json"

    fun save(table: BestEffortTable, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(stored(table)))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
            // 저장 실패는 조용히 삼킨다 — 다음 기동에 다시 계산할 뿐이다
        }
    }

    /// 파일이 없거나 깨졌으면 빈 표 — 처음부터 다시 계산할 뿐 잃는 것은 없다
    fun load(directory: File): BestEffortTable {
        val raw = try {
            EngineJson.decodeFromString<Map<String, Map<String, Double>>>(File(directory, filename).readText())
        } catch (_: Exception) {
            return emptyMap()
        }
        return table(raw)
    }

    /// JSON 키는 문자열이어야 해 [String(UUID): [String(Int(meters)): 초]]로 접는다
    private fun stored(table: BestEffortTable): Map<String, Map<String, Double>> =
        table.mapValues { (_, efforts) -> efforts.entries.associate { it.key.toInt().toString() to it.value } }

    /// 거리 키는 정수로 저장돼(하프 21_097.5 → "21097") 목표 거리 목록에서 원래 값을 되찾는다.
    /// 목록에 없는 키·깨진 UUID는 버린다 (Android: id가 UUID가 아니라 빈 문자열만 버린다)
    private fun table(raw: Map<String, Map<String, Double>>): BestEffortTable {
        val table = LinkedHashMap<String, Map<Double, Double>>()
        for ((key, efforts) in raw) {
            if (key.isEmpty()) continue
            val restored = LinkedHashMap<Double, Double>()
            for ((metersKey, seconds) in efforts) {
                val meters = BestEffortEngine.targets
                    .firstOrNull { it.meters.toInt().toString() == metersKey }?.meters ?: continue
                restored[meters] = seconds
            }
            table[key] = restored
        }
        return table
    }
}
