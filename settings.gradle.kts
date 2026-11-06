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

rootProject.name = "zephyr"
include(":app")
include(":torrent") // Zephyr Torrent: the torrent engine, an APK of its own (see torrent/build.gradle.kts)
