package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/// Application Support/RunWrap/course-fingerprints.json — 워크아웃 id → 코스 지문 (이슈 #223).
/// ReportCache·ZoneTimeCache와 같은 패턴(atomic write, 백업 제외). 과거 기록마다 경로를 다시 읽으면
/// 무거워서 세션 상세를 열 때 만든 지문을 쌓아 둔다. 워크아웃 경로는 불변이라 영구 캐시한다.
/// 저장 실패는 조용히 삼킨다: 다음에 세션을 열 때 다시 만들 뿐이다.
/// (Android: id는 Health Connect 레코드 id 문자열. 디렉터리는 :app이 넘기고, 백업 제외는
/// Auto Backup include 목록에 이 파일이 없어서 저절로 된다)
object CourseFingerprintCache {
    const val filename = "course-fingerprints.json"
    /// 최초 1회 최근 90일 백필을 마쳤는지 (설정 저장소)
    const val backfillDoneKey = "courseFingerprintBackfillDone"
    const val backfillDays = 90.0

    private val serializer = MapSerializer(String.serializer(), CourseMatchEngine.Fingerprint.serializer())

    fun save(fingerprints: Map<String, CourseMatchEngine.Fingerprint>, directory: File) {
        try {
            directory.mkdirs()
            val target = File(directory, filename)
            val temp = File.createTempFile(filename, ".tmp", directory)
            try {
                temp.writeText(EngineJson.encodeToString(serializer, fingerprints))
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /// 파일이 없거나 깨졌으면 빈 딕셔너리 — 세션을 열 때마다 다시 쌓인다
    fun load(directory: File): Map<String, CourseMatchEngine.Fingerprint> {
        val stored = try {
            EngineJson.decodeFromString(serializer, File(directory, filename).readText())
        } catch (_: Exception) {
            return emptyMap()
        }
        return stored.filterKeys { it.isNotEmpty() }
    }
}
