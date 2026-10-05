package com.jkpark.runwrap.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jkpark.runwrap.engine.OnboardingAnswers
import com.jkpark.runwrap.ui.RR

// 웨이브 3에서 덮어쓴다 — RootView·네비게이션 연결용 스텁. 시그니처가 콜백 계약이다(Routes.kt KDoc).
/// 온보딩 설문 — 첫 실행은 RootView가, 재진단은 Rediagnosis route가 띄운다
@Composable
fun OnboardingFlowScreen(
    prefill: OnboardingAnswers? = null,
    isRediagnosis: Boolean = false,
    onFinish: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(RR.bg).statusBarsPadding()) { Text("OnboardingFlowScreen", color = RR.text3) }
}
