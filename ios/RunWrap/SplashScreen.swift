import SwiftUI

/// 기동 스플래시 — 홈이 의존하는 로딩(현재 위치 날씨)이 끝날 때까지 홈 노출을 미룬다.
/// 시스템 런치 스크린(project.yml의 UILaunchScreen: 같은 배경색 + 같은 아이콘)과 동일한
/// 구성이라 프로세스 기동 첫 프레임부터 이 화면까지 이음새 없이 이어진다.
/// 스피너를 얹지 않는 것은 의도다: 런치 스크린의 연장으로 보여야 "로딩 중" 화면이 아니라
/// "앱이 뜨는 중"으로 읽힌다. 노출 시간 관리(안전망)는 RootView가 한다.
struct SplashScreen: View {
    var body: some View {
        ZStack {
            RR.launchBg.ignoresSafeArea()
            // LaunchIcon은 AppIcon 원본의 120pt 축소 사본이다(모서리 라운딩 포함) — 앱 아이콘
            // 에셋은 시스템 전용이라 앱 안에서 직접 못 쓴다. 고유 크기 그대로 그려야 런치 스크린
            // (역시 고유 크기로 그린다)에서 넘어올 때 아이콘이 튀지 않는다.
            // 아이콘을 바꾸면 이 이미지셋도 함께 갈아 줘야 한다
            Image("LaunchIcon")
        }
    }
}
