@file:Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "OuterTune-for-Windows"

// The app itself.
include(":desktop")

// Shared with the Android app (github.com/notK50BML/OuterTune), copied from it as they were.
// They are plain Kotlin/JVM modules with no Android dependency. Fixes made on either side
// should be copied to the other.
include(":innertube")
include(":lrclib")
include(":kugou")
include(":betterlyrics")
include(":qqmusic")
include(":kizzy")
