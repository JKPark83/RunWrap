package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jkpark.runwrap.engine.BirdSpecies
import com.jkpark.runwrap.engine.RaceDistance
import com.jkpark.runwrap.ui.RR
import java.time.Instant

// 웨이브 3에서 덮어쓴다 — RootView·네비게이션 연결용 스텁. 시그니처가 콜백 계약이다(Routes.kt KDoc).
/// 수집 세러모니 — 홈이 직접 띄운다(route 아님)
@Composable
fun CeremonyScreen(
    species: BirdSpecies,
    goalLabel: String,
    cycleStartedAt: Instant,
    cycleGoal: RaceDistance?,
    cycleGoalSeconds: Int,
    currentGoal: RaceDistance?,
    currentGoalSeconds: Int,
    onLater: () -> Unit,
    onFinish: (newGoal: RaceDistance?, newGoalSeconds: Int) -> Boolean,
) {
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) { Text("CeremonyScreen", color = RR.text3) }
}
