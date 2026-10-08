package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/// 주간 리포트 스냅샷 캐시 (계획서 M8) — 알림 본문의 재료를 Application Support에
/// JSON으로 남긴다. 화면 전체가 아니라 알림에 필요한 최소만 담는다 —
/// 리포트 본문은 앱을 열 때마다 항상 새로 계산한다.
@Serializable
data class ReportSnapshot(
    @Serializable(with = ReferenceDateInstantSerializer::class)
    val generatedAt: Instant,
    val headline: String,       // WeeklyReport.headline(level:)
    val suggestion: String? = null,    // 다음 주 제안
    val weekKm: Double,         // 최근 7일 거리 합 (6일 전 자정 ~ 지금 — runCount와 같은 창, 이슈 #75)
    val runCount: Int,          // 최근 7일 러닝 횟수
    /// 주간 거리 수치를 알림에 실어도 되는가 — 런린이는 문장만 (ReportGate, 기획서 §4, 이슈 #141)
    ///
    /// 기존 캐시 호환 (이슈 #141) — showsDistanceNumbers 키가 없던 파일은 예전처럼 수치 노출(true)로 읽는다.
    /// 디코딩이 실패하면 캐시 전체가 nil이 돼 알림이 기본 문구로 떨어지므로 키 하나로 버리지 않는다.
    /// (Android: iOS 커스텀 `init(from:)` 대신 기본값 `= true`로 같은 동작을 낸다)
    val showsDistanceNumbers: Boolean = true,
) {
    companion object {
        /// 리포트 + 원본 기록에서 스냅샷을 만든다 (순수 함수 — 테스트 대상).
        /// 문장은 상세 화면과 같은 레벨 게이트로 고른다 — 숨긴 카드의 판정이 알림으로 새지 않게 (이슈 #124)
        /// (Android: `Calendar.current` 자리에 `zone`을 받는다)
        fun make(report: WeeklyReport, runs: List<RunSummary>, level: RunnerLevel,
                 now: Instant, zone: ZoneId): ReportSnapshot {
            // report.weekRunCount와 같은 달력 창 — 한 문장에 나란히 실리니 창이 같아야 한다 (이슈 #75)
            val windowStart = now.minusSeconds(6L * 86_400).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val weekKm = runs.filter { it.start >= windowStart && it.start < now }
                .mapNotNull { it.distanceKm }
                .sum()
            return ReportSnapshot(generatedAt = now,
                                  headline = report.headline(level = level),
                                  suggestion = report.suggestion(level = level),
                                  weekKm = weekKm,
                                  runCount = report.weekRunCount,
                                  showsDistanceNumbers = ReportGate.showsNumbers(ReportCard.distance, level = level))
        }
    }
}

/// Application Support/RunWrap/weekly-report.json — atomic write.
/// 저장 실패는 조용히 삼킨다: 캐시가 없으면 알림 본문이 기본 문구로 나갈 뿐이다.
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다 — iOS `directory: URL? = nil` 기본 경로는 두지 않는다.
///  iCloud·아이튠즈 백업 제외(심사 지침 5.1.3(ii) — 건강 정보를 클라우드 백업에 두지 않는다)는
///  :app의 Auto Backup 규칙이 맡는다)
object ReportCache {
    const val filename = "weekly-report.json"

    fun save(snapshot: ReportSnapshot, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(snapshot))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
            // 저장 실패는 조용히 삼킨다 — 캐시가 없으면 알림 본문이 기본 문구로 나갈 뿐이다
        }
    }

    fun load(directory: File): ReportSnapshot? =
        try {
            EngineJson.decodeFromString<ReportSnapshot>(File(directory, filename).readText())
        } catch (_: Exception) {
            null
        }

    /// 캐시 삭제 — 데모 모드를 끌 때 합성 수치가 알림 본문에 남지 않게 한다 (이슈 #44).
    /// 파일이 없거나 지우지 못해도 조용히 넘어간다 (다음 저장이 덮어쓴다)
    fun clear(directory: File) {
        File(directory, filename).delete()
    }
}
