package com.jkpark.runwrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jkpark.runwrap.ui.RunWrapTheme

/// 앱 진입점 — 0단계 골격. 화면은 이후 단계에서 iOS를 이식하며 채운다.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 시스템 글꼴 배율은 1.3배까지만 — 시안 수치로 고정한 레이아웃이 넘치지 않게 여기 한 곳에서 막는다
            // (iOS는 Dynamic Type을 아예 적용하지 않는다 — 의도된 트레이드오프)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f)),
            ) {
                RunWrapTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("런미새")
                    }
                }
            }
        }
    }
}
