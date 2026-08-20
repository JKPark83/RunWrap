package com.jkpark.runwrap.store

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// 주간 리포트 스냅샷 — iOS `ReportCache.swift`의 `ReportSnapshot` 이식.
/// 알림 본문의 재료가 되는 최소만 담는다 — 리포트 본문은 앱을 열 때마다 새로 계산한다.
/// (iOS의 `make(report:runs:now:)`는 WeeklyReport 모델이 생기는 M2에서 함께 이식한다.)
@Serializable
data class ReportSnapshot(
    val generatedAtEpochMs: Long,
    val headline: String,
    val suggestion: String?,
    val weekKm: Double,
    val runCount: Int,
)

/// filesDir/weekly-report.json — 원자적 대체 저장.
/// 저장·읽기 실패는 조용히 삼킨다: 캐시가 없으면 알림 본문이 기본 문구로 나갈 뿐이다.
/// directory는 호출부가 `context.filesDir`를 넘긴다 — android 미참조로 JVM 테스트 가능.
/// filesDir는 앱 전용 내부 저장소고, 클라우드 백업 제외는 매니페스트 `allowBackup=false`가
/// 담당한다 (건강 파생 데이터를 기기 밖에 두지 않는다 — iOS 백업 제외와 같은 정책).
object ReportCache {
    const val FILENAME = "weekly-report.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun save(snapshot: ReportSnapshot, directory: File) {
        runCatching {
            directory.mkdirs()
            val tmp = File(directory, "$FILENAME.tmp")
            tmp.writeText(json.encodeToString(ReportSnapshot.serializer(), snapshot))
            Files.move(
                tmp.toPath(), File(directory, FILENAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    fun load(directory: File): ReportSnapshot? = runCatching {
        val file = File(directory, FILENAME)
        if (!file.exists()) return null
        json.decodeFromString(ReportSnapshot.serializer(), file.readText())
    }.getOrNull()
}
