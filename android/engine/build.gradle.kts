// 엔진 모듈 — 순수 Kotlin/JVM. Android 의존성이 없어서 android.*를 import하면 컴파일이 안 된다 (android/CLAUDE.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // iOS 테스트는 로컬(KST)·CI(UTC) 양쪽에서 통과한다 — 여기서도 -PtestZone=Asia/Seoul 로 한 번 더 돌린다 (TestSupport.kt)
    systemProperty("runwrap.testZone", providers.gradleProperty("testZone").getOrElse("UTC"))
    // 엔진이 주입받은 zone 대신 시스템 기본 zone·로케일을 쓰면 테스트가 깨지도록 일부러 엉뚱한 값을 준다
    jvmArgs("-Duser.timezone=Pacific/Kiritimati", "-Duser.language=fr", "-Duser.country=FR")
}
