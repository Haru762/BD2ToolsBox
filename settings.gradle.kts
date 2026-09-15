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
rootProject.name = "BD2ModManager"
include(":app")
// 自足的 release 探针 APK：它不依赖 :app 的任何类，因此不受 AGP 对 androidTest 包的
// 类去重、也不受 R8 映射漂移影响，可以驱动**预先构建好的** minified release 包
// （T03 冻结基线）。原有的 :app androidTest 包做不到这一点，原因与实测见
// docs/refactor/evidence/T04.md。
include(":release-probe")
