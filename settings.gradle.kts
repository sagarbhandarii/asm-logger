pluginManagement {
    includeBuild("build-logic")
    includeBuild("wrapper-inject-plugin")
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

rootProject.name = "android-asm-methodtrace-sample"
include(":app", ":sdk", ":wrapper-sdk")
