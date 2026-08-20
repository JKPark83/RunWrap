import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Google Maps API 키 — local.properties(gitignore 대상)에서만 읽는다. 커밋 금지 (계획서 §1).
// 키가 없으면 빈 문자열이 들어가고 세션 상세는 지도 대신 빈 상태 카드를 보여준다 (계획서 리스크 표).
val mapsApiKey: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    ?.getProperty("MAPS_API_KEY")
    ?: ""

android {
    namespace = "com.jkpark.runwrap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jkpark.runwrap"
        minSdk = 34            // 계획서 §1: Android 14+ — Health Connect 플랫폼 내장 전제
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // 화면의 "키 없음" 가드 재료 — 매니페스트 메타데이터를 런타임에 다시 파지 않는다
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maps.compose)

    testImplementation(libs.junit)
}
