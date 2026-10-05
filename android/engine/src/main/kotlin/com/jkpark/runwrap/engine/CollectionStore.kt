package com.jkpark.runwrap.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer

/// 도감 파일 입출력 — Application Support/RunWrap/collection.json (atomic write).
///
/// `ReportCache`와 같은 방식이다. 다만 성격이 다르다: 리포트 스냅샷은 언제든
/// 다시 계산할 수 있는 캐시지만, **도감은 재계산이 불가능한 유일 원본**이다 —
/// XP는 HealthKit 이력에서 되살릴 수 있어도 "언제 어떤 목표로 수집했는지"는 여기에만 있다.
/// 그래서 저장 실패를 조용히 삼키지 않고 호출부에 알린다.
/// (Android: 디렉터리는 :app이 filesDir 아래를 넘긴다. 백업 제외는 :app의 Auto Backup 규칙이 맡는다.
/// 상태를 들고 있는 `CollectionStore`(ObservableObject)는 :app ViewModel이고, 그 `add`의 저장 규칙만 여기 `add`로 둔다)
object CollectionCache {
    const val filename = "collection.json"

    /// - Throws: 인코딩·쓰기 실패. 도감은 유일 원본이라 실패를 숨기지 않는다
    fun save(birds: List<CollectedBird>, directory: File) {
        // iOS 기본 경로(Application Support/RunWrap)처럼 폴더가 없으면 만든다 — 실패는 아래 쓰기가 드러낸다
        directory.mkdirs()
        val text = EngineJson.encodeToString(ListSerializer(CollectedBird.serializer()), birds)
        val temp = File.createTempFile(filename, ".tmp", directory)
        try {
            temp.writeText(text)
            Files.move(temp.toPath(), File(directory, filename).toPath(),
                       StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    /// 파일이 없으면 빈 배열 — "아직 한 마리도 수집 안 함"과 "읽기 실패"를 구분하지 않는다.
    /// 도감은 없어도 앱이 도는 부가 화면이라 여기서는 빈 도감으로 시작하는 편이 낫다.
    fun load(directory: File): List<CollectedBird> = try {
        EngineJson.decodeFromString(ListSerializer(CollectedBird.serializer()), File(directory, filename).readText())
    } catch (_: Exception) {
        emptyList()
    }

    /// iOS `CollectionStore.add`의 저장 규칙 (이슈 #67) — 같은 종을 여러 번 수집할 수 있다 (사이클마다 목표가 같을 수 있으므로).
    ///
    /// 파일에 먼저 쓰고 성공해야 메모리에 넣는다 — 실패한 새가 메모리에만 남으면
    /// 재시도 때 중복 수집되고, 호출부가 사이클을 초기화하면 재실행 시 사라진다 (이슈 #67).
    /// - Returns: 파일에 저장됐으면 새 목록(`birds + bird`), 실패면 null — 호출부는 메모리를 바꾸지 않고 사이클도 초기화하면 안 된다
    fun add(bird: CollectedBird, birds: List<CollectedBird>, directory: File,
            defaults: KeyValueStore, now: Instant): List<CollectedBird>? {
        val updated = birds + bird
        try {
            save(updated, directory)
        } catch (_: Exception) {
            return null
        }
        // 도감은 스냅샷 내용이다 — 병합 기준 시각을 갱신한다 (이슈 #130)
        ProgressSnapshot.markLocalChanged(defaults = defaults, now = now)
        return updated
    }
}
