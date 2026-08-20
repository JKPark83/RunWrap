package com.jkpark.runwrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jkpark.runwrap.screen.RootView
import com.jkpark.runwrap.ui.theme.RunWrapTheme

/// 정식 진입점 — RR 테마를 씌우고 루트 분기(RootView)로 넘긴다 (계획서 M5).
/// M4 검증 화면(시더·레코드 덤프)은 debug 소스셋 DevActivity로 옮겼다.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RunWrapTheme {
                RootView()
            }
        }
    }
}
