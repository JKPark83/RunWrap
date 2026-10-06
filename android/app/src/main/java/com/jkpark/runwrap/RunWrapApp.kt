package com.jkpark.runwrap

import android.app.Application

/// 앱 진입점 + 알림 수명주기 (계획서 M8).
/// HealthStore를 앱이 소유해 포그라운드 훅에서 재계산→캐시→주간 알림 재예약을 돌린다 —
/// 포그라운드 재계산이 1차 경로다.
/// (Android: 스토어는 AppContainer가 쥐고, 포그라운드·백그라운드 훅은 Compose 루트(RootView)의
///  LifecycleResumeEffect에 있다 — 날씨 스토어까지 같은 곳에서 다루려고 한곳에 모았다.
///  HealthKit 옵저버(러닝 종료 즉시 알림)·위젯 스냅샷은 ios-only — 백그라운드 HC 읽기와 홈 화면 위젯이 없다)
class RunWrapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
