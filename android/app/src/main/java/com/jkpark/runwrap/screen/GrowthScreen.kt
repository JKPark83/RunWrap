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
/// 리포트 탭의 성장 세그먼트
@Composable
fun GrowthScreen(
    segment: (@Composable () -> Unit)? = null,
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) { Text("GrowthScreen", color = RR.text3) }
}
