package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/// 세션별 심박 히스토그램 (이슈 #165) — 기간별 심박존 분포(80/20 강도 배분) 카드의 재료.
///
/// **왜 존 시간이 아니라 bpm 히스토그램인가.** 존 경계(0.6/0.7/0.8/0.9)는 HeartRateProfile
/// (HRmax·안정 심박·%HRmax/Karvonen)에 달려 있고, 사용자가 설정에서 언제든 바꾼다.
/// 정수 bpm → 초로 남겨 두면 경계는 표시 시점에 적용되므로 심박 기준을 바꿔도 캐시가 유효하다.
@Serializable
data class ZoneHistogram(
    /// 반올림한 정수 bpm → 그 심박으로 보낸 시간(초)
    val secondsByBpm: Map<Int, Double>,
) {
    companion object {
        /// 심박 샘플(시각 오름차순) → 히스토그램. 가중은 TrainingGuideEngine.heartRateZones와 같다 —
        /// 다음 샘플까지 간격(≤15초 캡), 마지막 샘플은 5초. 230 초과(plausiblePeakBpm 상한)는
        /// 착용 불량 스파이크로 보고 버린다 (sessionPeakBpm과 같은 기준)
        fun make(samples: List<TrainingGuideEngine.HeartRateSample>): ZoneHistogram {
            val seconds = LinkedHashMap<Int, Double>()
            for ((i, sample) in samples.withIndex()) {
                if (!(sample.bpm <= TrainingGuideEngine.plausiblePeakBpm.endInclusive)) continue
                val weight = if (i + 1 < samples.size) {
                    min(samples[i + 1].time.timeIntervalSince1970 - sample.time.timeIntervalSince1970, 15.0)
                } else {
                    5.0
                }
                if (!(weight > 0)) continue
                val bpm = sample.bpm.swiftRoundedInt()
                seconds[bpm] = (seconds[bpm] ?: 0.0) + weight
            }
            return ZoneHistogram(secondsByBpm = seconds)
        }
    }
}

/// Application Support/RunWrap/zone-histograms.json — PBBaselineCache·ReportCache와 같은 패턴
/// (atomic write, 백업 제외). 워크아웃마다 심박 샘플 쿼리가 필요해 한 번 만든 값을 남겨 둔다.
/// 저장 실패는 조용히 삼킨다: 다음 조회 때 다시 만들 뿐이다.
/// 저장 형식은 `[UUID 문자열: ZoneHistogram]` — JSON 객체 키는 문자열이어야 한다.
/// (Android: 워크아웃 id는 Health Connect 레코드 id 문자열이다 — UUID 형식이 아니다.
/// 디렉터리는 :app이 filesDir 아래를 넘긴다. 백업 제외는 :app의 Auto Backup 규칙이 맡는다)
object ZoneTimeCache {
    const val filename = "zone-histograms.json"

    private val serializer = MapSerializer(String.serializer(), ZoneHistogram.serializer())

    fun save(histograms: Map<String, ZoneHistogram>, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(serializer, histograms))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /// 파일이 없거나 깨졌으면 빈 딕셔너리 — 백필이 처음부터 다시 채운다
    /// (Android: id가 UUID가 아니라 빈 문자열 키만 버린다)
    fun load(directory: File): Map<String, ZoneHistogram> {
        val stored = try {
            EngineJson.decodeFromString(serializer, File(directory, filename).readText())
        } catch (_: Exception) {
            return emptyMap()
        }
        return stored.filterKeys { it.isNotEmpty() }
    }

    /// 28일 창 밖(또는 삭제된) 워크아웃 항목을 버린다 — 캐시가 기록 전체로 불어나지 않게
    fun prune(dict: Map<String, ZoneHistogram>, keepingIDs: Set<String>): Map<String, ZoneHistogram> =
        dict.filterKeys { it in keepingIDs }
}
