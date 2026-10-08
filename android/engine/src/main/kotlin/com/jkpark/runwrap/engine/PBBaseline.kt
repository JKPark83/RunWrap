package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/// PB 베이스라인 (이슈 #21) — 마지막으로 확인한 종목별 최고 기록을 저장해
/// "새 PB 갱신"을 감지한다. 홈 진입 시 1회성 축하 팝업의 재료.
///
/// PersonalRecords는 매번 전체 기록에서 재계산되고 어디에도 저장되지 않으므로,
/// "지난번보다 좋아졌는가"를 알려면 마지막 확인 시점의 기록을 남겨 둬야 한다.
///
/// version (이슈 #166): PR 산식이 평균 페이스 × 거리에서 베스트 에포트로 바뀌어 옛 기록과는
/// 비교할 수 없다 — 그대로 비교하면 같은 세션도 "새 PB"로 축하된다. 그래서 옛 버전 베이스라인은
/// 없는 것(nil)으로 읽어 조용히 재시드한다. 버전 필드가 없는 옛 파일은 1로 본다.
@Serializable(with = PBBaselineSerializer::class)
data class PBBaseline(
    /// 종목 라벨("1K"·"5K"·"10K"·"하프"·"풀") → 기록(초)
    val times: Map<String, Double>,
    /// 기록을 만든 산식 버전
    val version: Int = currentVersion,
) {
    companion object {
        /// 현재 산식 버전 — 1: 평균 페이스 × 공인 거리, 2: 베스트 에포트 (이슈 #166)
        const val currentVersion = 2

        /// (Android: Swift `Dictionary(uniqueKeysWithValues:)`는 중복 라벨에서 트랩하고 associate는 뒤 값을 남긴다 —
        /// 라벨은 PersonalRecords.targets에서 하나씩만 나와 중복이 없다)
        fun make(from: List<PersonalRecords.Entry>): PBBaseline =
            PBBaseline(times = from.associate { it.label to it.timeSec },
                       version = currentVersion)
    }
}

/// Swift `init(from:)` — version 키가 없으면 1 (`decodeIfPresent ?? 1`)
@Serializable
private class PBBaselineStored(val times: Map<String, Double>, val version: Int = 1)

internal object PBBaselineSerializer : KSerializer<PBBaseline> {
    override val descriptor = PBBaselineStored.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PBBaseline) =
        encoder.encodeSerializableValue(PBBaselineStored.serializer(),
                                        PBBaselineStored(value.times, value.version))

    override fun deserialize(decoder: Decoder): PBBaseline {
        val stored = decoder.decodeSerializableValue(PBBaselineStored.serializer())
        return PBBaseline(stored.times, stored.version)
    }
}

object PBEngine {
    /// 새로 세운 PB — 종목이 처음 생겼거나 기존 기록보다 빨라졌으면 갱신으로 본다.
    /// 베이스라인이 없으면(첫 실행) 빈 배열 — 기존 기록 전부를 "새 PB"로 축하하지 않고
    /// 조용히 seed만 하라는 뜻이다. 0.5초 여유는 부동소수 재계산 노이즈 가드.
    fun newRecords(current: List<PersonalRecords.Entry>,
                   baseline: PBBaseline?): List<PersonalRecords.Entry> {
        if (baseline == null) return emptyList()
        return current.filter { entry ->
            val old = baseline.times[entry.label] ?: return@filter true
            entry.timeSec < old - 0.5
        }
    }
}

/// Application Support/RunWrap/pb-baseline.json — ReportCache와 같은 패턴 (atomic write,
/// 백업 제외). 저장 실패는 조용히 삼킨다: 다음 진입 때 다시 감지·축하할 뿐이다.
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다. 백업 제외는 :app의 Auto Backup 규칙이 맡는다)
object PBBaselineCache {
    const val filename = "pb-baseline.json"

    fun save(baseline: PBBaseline, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(PBBaseline.serializer(), baseline))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /// 옛 산식 버전(< currentVersion)은 nil — 첫 실행처럼 조용히 재시드된다 (이슈 #166)
    fun load(directory: File): PBBaseline? {
        val baseline = try {
            EngineJson.decodeFromString(PBBaseline.serializer(), File(directory, filename).readText())
        } catch (_: Exception) {
            return null
        }
        if (baseline.version < PBBaseline.currentVersion) return null
        return baseline
    }
}
