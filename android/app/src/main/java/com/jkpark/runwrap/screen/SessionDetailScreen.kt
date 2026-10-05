package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.ui.RR

// 웨이브 3에서 덮어쓴다 — RootView·네비게이션 연결용 스텁. 시그니처가 콜백 계약이다(Routes.kt KDoc).
/// 세션 상세
@Composable
fun SessionDetailScreen(
    run: RunSummary,
    weeklyContext: WeeklyReport.DistanceCard? = null,
    onShoePromptOptOut: (() -> Unit)? = null,
    onBack: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) { Text("SessionDetailScreen", color = RR.text3) }
}
