package com.jkpark.runwrap.store

import android.app.backup.BackupManager
import android.content.Context

/// 진행도 백업 스토어 (이슈 #29).
///
/// 원칙: 건강 데이터(운동 목록·심박·위치)는 올리지 않는다 — 성장 복원 메타데이터와 도감, 직접 입력한 대회 기록만 백업한다.
/// (Android: iOS는 사용자 CloudKit private database에 단일 스냅샷 레코드를 올리고 병합한다. Android는 Auto Backup이
///  사용자 Google 계정에 파일째 백업하고, 재설치 때 첫 실행 전에 시스템이 자동으로 복원한다.
///  백업 범위는 res/xml/data_extraction_rules.xml의 include 목록 — 설정(settings.xml)과
///  RunWrap/collection.json·race-records.json·race-record-tombstones.json. 건강 데이터 캐시·러닝화는 넣지 않는다.
///  그래서 복원 시도·복원 선택 시트·병합(restoreOnFreshInstall·restoreCandidate·accept/decline·mergedResult)과
///  동기화 메타(cloud.* 키)·마지막 백업 시각은 없다.
///  주의: 설정의 알림 토글·didConnectHealth는 복원되지만 알림·헬스 커넥트 권한은 복원되지 않는다 — 권한은 매번 다시 확인한다)
class ProgressBackupStore(private val context: Context) {
    /// 로컬 진행도가 바뀌었다고 알린다 — 호출부(온보딩·홈·설정·앱 백그라운드 진입)는 iOS와 같다.
    /// (Android: Auto Backup은 시스템이 하루 한 번쯤 알아서 돌아 이 알림이 없어도 백업된다 — 사실상 no-op이다)
    fun backupIfChanged() {
        BackupManager.dataChanged(context.packageName)
    }
}
