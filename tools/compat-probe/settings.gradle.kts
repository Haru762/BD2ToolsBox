// T13 兼容探针：独立 Gradle 根。生产 settings.gradle.kts 不 include 本目录。
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
rootProject.name = "BD2CompatProbe"
include(":app")
