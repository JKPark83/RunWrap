package com.jkpark.runwrap.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/// SVG 12(양날개 펼친 새): `M120 62l-22 26c-34-8-70 2-86 24 30-4 56 2 76 18l14 34 18-22 18 22 14-34c20-16 46-22 76-18-16-22-52-32-86-24z`
///
/// (Android: iOS는 `tabItem` 라벨이 Shape를 못 받아 래스터라이즈한 템플릿 Image로 캐시한다.
///  Compose 탭바는 `ImageVector`를 그대로 받고 색(선택/비선택)은 `Icon`의 tint가 정하므로 벡터로 둔다.
///  경로는 iOS BirdTabIconShape의 절대 좌표판을 옮겼다)
object BirdTabIcon {
    /// SF Symbol 탭 아이콘과 눈높이를 맞춘 한 변. 원본이 240 정사각이라 정사각으로 그린다
    val image: ImageVector by lazy {
        ImageVector.Builder(name = "BirdTabIcon", defaultWidth = 28.dp, defaultHeight = 28.dp,
            viewportWidth = 240f, viewportHeight = 240f)
            .addPath(
                pathData = addPathNodes(
                    "M120 62 L98 88 C64 80 28 90 12 112 C42 108 68 114 88 130 " +
                        "L102 164 L120 142 L138 164 L152 130 C172 114 198 108 228 112 C212 90 176 80 142 88 Z",
                ),
                fill = SolidColor(Color.Black),   // 템플릿이라 이 색은 tint로 덮인다
            )
            .build()
    }
}
