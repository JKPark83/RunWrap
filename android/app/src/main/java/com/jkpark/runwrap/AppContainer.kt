package com.jkpark.runwrap

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jkpark.runwrap.engine.BirdSpecies
import com.jkpark.runwrap.engine.CollectedBird
import com.jkpark.runwrap.engine.CollectionCache
import com.jkpark.runwrap.engine.ProgressSnapshot
import com.jkpark.runwrap.engine.RaceRecord
import com.jkpark.runwrap.engine.RaceRecordCache
import com.jkpark.runwrap.health.HealthStore
import com.jkpark.runwrap.store.ProgressBackupStore
import com.jkpark.runwrap.store.RaceStore
import com.jkpark.runwrap.store.SettingsStore
import com.jkpark.runwrap.store.ShoeStore
import com.jkpark.runwrap.store.WeatherStore
import com.jkpark.runwrap.store.appSupportDir
import java.io.File
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// 앱 단일 인스턴스 스토어 묶음 — iOS에서 RunWrapApp·RootView가 `@StateObject`로 쥐고
/// `environmentObject`로 내리던 것들 (health·collection·backup은 RunWrapApp, 나머지는 RootView).
/// (Android: RunWrapApp(Application)이 만들고 MainActivity가 `LocalAppContainer`로 내려준다. DI 라이브러리는 쓰지 않는다.
///  화면 전용 스토어(AirQualityStore·CoursePOIStore·LocationProvider·WorkoutDetailStore)는 여기 두지 않고 화면 ViewModel이 쥔다)
class AppContainer(context: Context) {
    val context: Context = context.applicationContext
    val settings = SettingsStore(this.context)
    val health = HealthStore(this.context, settings)
    /// 날씨는 홈이 뜨기 전에 준비돼야 해서 루트가 쥔다 — 스플래시 해제 조건이자 홈의 재료
    val weather = WeatherStore(this.context)
    /// 대회 목록 (이슈 #172) — 대회 탭과 홈 목표 대회 카드·설정 접수 알림이 같이 써서 루트가 쥔다
    val raceStore = RaceStore(this.context)
    /// 러닝화 (이슈 #171) — 설정이 등록하고 홈·세션 상세가 쓰며, 러닝 목록 로드 때 자동 배정을 걸어 루트가 쥔다
    val shoes = ShoeStore(this.context, settings)
    /// 진행도 백업 (이슈 #29) — 주기적 백업은 백그라운드 진입 훅이 굴린다
    val backup = ProgressBackupStore(this.context)
    /// 도감 — 파일에서 한 번 읽어 앱 수명 동안 들고 간다 (기획서 §5)
    val collection = CollectionStore(appSupportDir(this.context), settings)
    /// 직접 입력한 대회 기록 (이슈 #35) — 설정이 입력하고 리포트가 소비해서 루트가 쥔다
    val raceRecords = RaceRecordStore(appSupportDir(this.context), settings)
    /// 기동·포그라운드 로딩을 돌리는 앱 수명 스코프 — iOS는 스토어가 앱 소유라 화면이 다시 만들어져도
    /// 로딩이 끊기지 않는다. 컴포지션 스코프에서 돌리면 액티비티 재생성 때 health.load()가 취소돼 Loading에 남는다
    val scope = MainScope()
}

/// MainActivity가 내려주는 컨테이너 — 화면은 `LocalAppContainer.current`로 스토어를 꺼낸다
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer가 제공되지 않았다") }

/// 화면 ViewModel 생성 헬퍼 — 컨테이너의 스토어를 생성자로 넘긴다 (팩토리 클래스 없이).
/// 예: `val model = containerViewModel { HomeViewModel(it.health, it.weather) }`
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}

/// 도감 상태 보유 — 수집한 새 목록과 사이클 전환을 담당한다 (기획서 §5).
///
/// 다른 스토어(HealthStore 등)와 달리 HealthKit을 만지지 않는다 — 파일과
/// `@AppStorage` 값만 다루는 얇은 계층이라 `enum State` 없이 배열 하나를 노출한다.
/// (Android: iOS CollectionStore.swift의 상태 클래스. 파일 규칙은 엔진 `CollectionCache`. 메인 스레드에서만 부른다)
class CollectionStore(private val directory: File, private val settings: SettingsStore) {
    /// 수집한 새 — 오래된 순. 화면은 최신순이 필요하면 뒤집어 쓴다
    private val _birds = MutableStateFlow(CollectionCache.load(directory))
    val birds: StateFlow<List<CollectedBird>> = _birds.asStateFlow()

    /// 이미 그 종을 수집했는지 — 도감 그리드에서 실루엣/성조를 가른다
    fun hasCollected(species: BirdSpecies): Boolean = _birds.value.any { it.species == species }

    /// 같은 종을 여러 번 수집할 수 있다 (사이클마다 목표가 같을 수 있으므로).
    /// 도감 그리드는 종별로 묶어 보여주고, 여기서는 이력을 그대로 쌓는다.
    ///
    /// 파일에 먼저 쓰고 성공해야 메모리에 넣는다 (이슈 #67) — 규칙은 `CollectionCache.add`.
    /// - Returns: 파일에 저장됐으면 true. false면 호출부는 사이클을 초기화하면 안 된다
    fun add(bird: CollectedBird): Boolean {
        val updated = CollectionCache.add(bird, _birds.value, directory, settings, Instant.now()) ?: return false
        _birds.value = updated
        return true
    }
}

/// 대회 기록 소유 스토어 — 설정 화면이 입력하고 리포트 화면이 소비한다.
/// 두 화면이 공유해야 해서 루트가 쥔다 (이슈 #35)
/// (Android: iOS RaceRecordStore.swift의 상태 클래스. 복원·병합용 `replace`는 Auto Backup이 파일째 복원해 필요 없다)
class RaceRecordStore(private val directory: File, private val settings: SettingsStore) {
    private val _records = MutableStateFlow(RaceRecordCache.load(directory, Instant.now()) ?: emptyList())
    val records: StateFlow<List<RaceRecord>> = _records.asStateFlow()

    fun add(record: RaceRecord) {
        val updated = (_records.value + record).sortedByDescending { it.date }   // 최신이 위 — 설정 목록 표시 순서
        _records.value = updated
        RaceRecordCache.save(updated, directory)
        // 대회 기록은 스냅샷 내용이다 — 병합 기준 시각을 갱신한다 (이슈 #130)
        ProgressSnapshot.markLocalChanged(defaults = settings, now = Instant.now())
    }

    /// 지운 id는 삭제 표식으로 남긴다 (이슈 #118) — 다음 백업의 합집합 병합에서 서버 본이 되살리지 못하게
    fun remove(record: RaceRecord) {
        val updated = _records.value.filter { it.id != record.id }
        _records.value = updated
        RaceRecordCache.save(updated, directory)
        val deleted = RaceRecordCache.loadDeletedIDs(directory)
        if (record.id !in deleted) RaceRecordCache.saveDeletedIDs(deleted + record.id, directory)
        ProgressSnapshot.markLocalChanged(defaults = settings, now = Instant.now())
    }
}
