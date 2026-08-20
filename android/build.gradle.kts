// 루트 빌드 스크립트 — 플러그인 버전만 카탈로그로 고정하고, 적용은 :app에서 한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
