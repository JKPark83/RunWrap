package com.jkpark.runwrap.store

import android.content.Context
import com.jkpark.runwrap.engine.KeyValueStore
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.Shoe
import com.jkpark.runwrap.engine.ShoeCache
import com.jkpark.runwrap.engine.ShoeEngine
import com.jkpark.runwrap.engine.ShoeFile
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// 러닝화 소유 스토어 (이슈 #171) — 설정이 등록·편집하고, 세션 상세가 배정을 바꾸고, 홈이 교체 안내에 쓴다.
/// 세 화면이 공유해야 해서 RootView가 쥐고 environmentObject로 내린다 (RaceRecordStore와 같은 구조).
/// 변경마다 shoes.json에 바로 저장한다. iCloud 진행도 백업에는 넣지 않는다
/// (Android: 앱 단일 인스턴스로 AppContainer가 쥔다. 백업 제외는 data_extraction_rules.xml이 shoes.json을 include하지 않는 것으로 한다.
///  iOS `@MainActor`처럼 메인 스레드에서만 부른다)
class ShoeStore(private val context: Context, private val settings: KeyValueStore) {
    private val directory: File = appSupportDir(context)

    private val _shoes = MutableStateFlow<List<Shoe>>(emptyList())
    val shoes: StateFlow<List<Shoe>> = _shoes.asStateFlow()
    private val _defaultShoeID = MutableStateFlow<String?>(null)
    val defaultShoeID: StateFlow<String?> = _defaultShoeID.asStateFlow()
    /// 세션 id → 신발 id. "없음"은 `ShoeEngine.noShoeID`
    private val _assignments = MutableStateFlow<Map<String, String>>(emptyMap())
    val assignments: StateFlow<Map<String, String>> = _assignments.asStateFlow()

    init {
        val file = ShoeCache.load(directory, Instant.now())
        // (Android: iOS의 시뮬레이터 데모 신발 시드는 옮기지 않는다 — DemoMode처럼 에뮬레이터 분기가 없고,
        //  디버그 빌드로 바꾸면 실기기의 실제 shoes.json에 가짜 신발이 쌓인다)
        if (file != null) {
            _shoes.value = file.shoes
            _defaultShoeID.value = file.defaultShoeID
            _assignments.value = file.assignments
        }
    }

    /// 새 신발 등록 — 첫 켤레는 기본 신발 지정 여부를 시트가 정한다
    fun add(shoe: Shoe) {
        _shoes.value = _shoes.value + shoe
        save()
    }

    /// 편집 결과 반영 — 은퇴한 신발이 기본이면 기본 지정을 푼다(자동 배정 대상이 아니다)
    fun update(shoe: Shoe) {
        val index = _shoes.value.indexOfFirst { it.id == shoe.id }
        if (index < 0) return
        _shoes.value = _shoes.value.toMutableList().also { it[index] = shoe }
        if (shoe.isRetired && _defaultShoeID.value == shoe.id) _defaultShoeID.value = null
        save()
    }

    fun retire(shoe: Shoe) {
        update(shoe.copy(isRetired = true))
    }

    /// 삭제 — 이 신발에 배정된 세션은 "없음"으로 남긴다. 키를 지우면 다음 자동 배정이
    /// 기본 신발로 다시 채워, 다른 신발 거리로 잘못 넘어간다
    fun remove(shoe: Shoe) {
        shoe.imageFile?.let { ShoeImageStore.remove(context, it) }   // 사진도 지운다 (이슈 #206)
        _shoes.value = _shoes.value.filter { it.id != shoe.id }
        if (_defaultShoeID.value == shoe.id) _defaultShoeID.value = null
        _assignments.value = _assignments.value.mapValues { (_, shoeID) ->
            if (shoeID == shoe.id) ShoeEngine.noShoeID else shoeID
        }
        save()
    }

    /// 편집 시트 저장 (이슈 #206) — 추가/수정 + 기본 지정·해제 + 자동 배정 재계산.
    /// 설정·홈·팝업 세 곳에서 같은 시트를 쓰므로 SettingsScreen.saveShoe에 있던 로직을 여기로 모았다
    fun save(shoe: Shoe, isDefault: Boolean, runs: List<RunSummary>) {
        if (_shoes.value.any { it.id == shoe.id }) {
            update(shoe)
        } else {
            add(shoe)
        }
        if (isDefault) {
            setDefault(shoe.id)
        } else if (_defaultShoeID.value == shoe.id) {
            setDefault(null)
        }
        syncAssignments(runs)
    }

    fun setDefault(id: String?) {
        if (_defaultShoeID.value == id) return
        _defaultShoeID.value = id
        save()
    }

    /// 세션별 신발 변경 — nil은 "없음"(표식으로 남겨 자동 배정이 되살리지 않게 한다)
    fun assign(runID: String, shoeID: String?) {
        _assignments.value = _assignments.value + (runID to (shoeID ?: ShoeEngine.noShoeID))
        save()
    }

    /// 러닝 목록이 로드될 때 1회 — 배정 없는 세션을 기본 신발(등록 이후 세션만)로 채우고, 바뀌었을 때만 저장
    fun syncAssignments(runs: List<RunSummary>) {
        // 실기기 데모 모드의 합성 러닝 ID를 실제 shoes.json에 쌓지 않는다 (시뮬레이터는 예외)
        // (Android: 항상 데모 모드인 환경이 없어 예외 없이 막는다)
        if (DemoMode.isEnabled(settings)) return
        val since = _shoes.value.firstOrNull { it.id == _defaultShoeID.value }?.createdAt
        val updated = ShoeEngine.autoAssign(runs = runs, defaultShoeID = _defaultShoeID.value,
                                            assignments = _assignments.value, since = since)
        if (updated == _assignments.value) return
        _assignments.value = updated
        save()
    }

    fun mileage(shoe: Shoe, runs: List<RunSummary>): Double =
        ShoeEngine.mileageKm(shoe = shoe, runs = runs, assignments = _assignments.value)

    /// 세션에 배정된 신발 — 없음 표식·삭제된 신발이면 nil
    fun shoe(runID: String): Shoe? {
        val id = _assignments.value[runID] ?: return null
        return _shoes.value.firstOrNull { it.id == id }
    }

    private fun save() {
        ShoeCache.save(ShoeFile(schemaVersion = ShoeFile.currentSchemaVersion, shoes = _shoes.value,
                                defaultShoeID = _defaultShoeID.value, assignments = _assignments.value),
                       directory)
    }
}
