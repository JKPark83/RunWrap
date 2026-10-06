package com.jkpark.runwrap.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jkpark.runwrap.R
import com.jkpark.runwrap.ui.RR

/// 기동 스플래시 — 홈이 의존하는 로딩(현재 위치 날씨)이 끝날 때까지 홈 노출을 미룬다.
/// 시스템 런치 스크린(같은 배경색 + 같은 아이콘)과 동일한
/// 구성이라 프로세스 기동 첫 프레임부터 이 화면까지 이음새 없이 이어진다.
/// 스피너를 얹지 않는 것은 의도다: 런치 스크린의 연장으로 보여야 "로딩 중" 화면이 아니라
/// "앱이 뜨는 중"으로 읽힌다. 노출 시간 관리(안전망)는 RootView가 한다.
/// (Android: 시스템 런치 스크린은 테마의 windowSplashScreenBackground(@color/launch_bg)·
///  windowSplashScreenAnimatedIcon(@drawable/splash_icon — launch_icon을 120dp로 보이게 둘레를 비운 inset)이다)
@Composable
fun SplashScreen() {
    // edge-to-edge 전체 화면 기준 가운데 — 시스템 스플래시 아이콘과 같은 자리
    Box(Modifier.fillMaxSize().background(RR.launchBg), contentAlignment = Alignment.Center) {
        // launch_icon은 AppIcon 원본의 120pt 축소 사본이다(모서리 라운딩 포함) — 앱 아이콘
        // 에셋은 시스템 전용이라 앱 안에서 직접 못 쓴다. 시스템 스플래시와 같은 120dp로 그려야
        // 넘어올 때 아이콘이 튀지 않는다.
        // 아이콘을 바꾸면 이 이미지도 함께 갈아 줘야 한다
        Image(painterResource(R.drawable.launch_icon), contentDescription = null, modifier = Modifier.size(120.dp))
    }
}
