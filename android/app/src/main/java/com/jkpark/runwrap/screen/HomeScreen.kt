package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jkpark.runwrap.engine.RaceEngine
import com.jkpark.runwrap.engine.RecapPeriod
import com.jkpark.runwrap.engine.RunSummary
import com.jkpark.runwrap.engine.WeeklyReport
import com.jkpark.runwrap.ui.RR

// 웨이브 3에서 덮어쓴다 — RootView·네비게이션 연결용 스텁. 시그니처가 콜백 계약이다(Routes.kt KDoc).
/// 홈 탭 루트
@Composable
fun HomeScreen(
    onSelectReport: () -> Unit,
    onOpenSession: (RunSummary, WeeklyReport.DistanceCard?) -> Unit,
    onOpenToday: () -> Unit,
    onOpenRecap: (RecapPeriod) -> Unit,
    onOpenRace: (RaceEngine.Entry) -> Unit,
    onOpenCollection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) { Text("HomeScreen", color = RR.text3) }
}
