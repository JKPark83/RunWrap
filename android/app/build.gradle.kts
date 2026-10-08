import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 비밀값은 local.properties(gitignore 대상)에서만 읽는다. 커밋 금지 (android/CLAUDE.md).
val localProps: Properties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Google Maps API 키 — 없으면 빈 문자열이 들어가고 지도 자리는 빈 상태로 보인다.
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY") ?: ""

// Play 업로드 키 — UPLOAD_STORE_FILE 등 4개가 local.properties에 있을 때만 release에 서명한다.
// 없으면 서명 없는 release(설치 불가)가 나온다 — 로컬 검증용 assembleRelease는 그래도 돌아야 한다.
val uploadStoreFile: String? = localProps.getProperty("UPLOAD_STORE_FILE")

android {
    namespace = "com.jkpark.runwrap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jkpark.runwrap"
        minSdk = 34            // Android 14+ — Health Connect 플랫폼 내장 전제
        targetSdk = 36
        versionCode = 1        // Play 업로드마다 +1
        versionName = "1.0.0"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // 화면의 "키 없음" 가드 재료 — 매니페스트 메타데이터를 런타임에 다시 파지 않는다
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }

    if (uploadStoreFile != null) {
        signingConfigs {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = localProps.getProperty("UPLOAD_STORE_PASSWORD")
                keyAlias = localProps.getProperty("UPLOAD_KEY_ALIAS")
                keyPassword = localProps.getProperty("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (uploadStoreFile != null) signingConfig = signingConfigs.getByName("upload")
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

// iOS 번들 JSON을 APK assets로 — 번들 JSON은 iOS와 공유한다(대회정보는 CI가 매일 덮어쓴다).
// ios/RunWrap 폴더를 통째로 srcDir로 잡으면 Swift 파일까지 들어가므로 이 넷만 복사한다.
// AirQualityKey.json은 gitignore된 비밀값 파일이라 없을 수 있다 — 없으면 건너뛰고 대기질만 빈 상태가 된다.
abstract class CopyIosAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        sources.filter { it.exists() }.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val copyIosAssets = tasks.register<CopyIosAssets>("copyIosAssets") {
    sources.from(
        listOf("Races", "AirStations", "CoursePOI", "AirQualityKey")
            .map { rootProject.file("../ios/RunWrap/$it.json") },
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyIosAssets, CopyIosAssets::outputDir)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maps.compose)
    implementation(libs.mlkit.text.recognition.korean)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
