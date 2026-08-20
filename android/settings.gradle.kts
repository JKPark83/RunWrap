// RunWrap Android 루트 설정 — 저장소 선언은 여기 한 곳에만 둔다 (프로젝트별 저장소 금지).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "runwrap-android"
include(":app")
