package com.jkpark.runwrap

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jkpark.runwrap.screen.RootView
import com.jkpark.runwrap.screen.privacyPolicyURL
import com.jkpark.runwrap.ui.RunWrapTheme

/// 앱 진입점 — 시스템 스플래시(테마의 windowSplashScreen*)가 걷히면 RootView가 이어받는다.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Health Connect 설정의 '개인정보처리방침 보기'(매니페스트의 ViewPermissionUsageActivity alias)로 열렸으면
        // 앱 화면 대신 방침을 보여 준다 — Play의 HC 권한 심사 요건 (docs/playstore/review-considerations.md §2)
        if (intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyURL)))
            } catch (_: ActivityNotFoundException) {
                // 브라우저가 없으면 열 수 없다 — 그냥 닫는다
            }
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as RunWrapApp).container
        setContent {
            // 시스템 글꼴 배율은 1.3배까지만 — 시안 수치로 고정한 레이아웃이 넘치지 않게 여기 한 곳에서 막는다
            // (iOS는 Dynamic Type을 아예 적용하지 않는다 — 의도된 트레이드오프)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f)),
                LocalAppContainer provides container,
            ) {
                RunWrapTheme {
                    RootView()
                }
            }
        }
    }
}
