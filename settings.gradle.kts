pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "Sonic-Control"
include(":app")
include(":core:protocol")
include(":core:devices")
include(":core:diagnostics")
include(":drivers:generic")
include(":drivers:jbl")
include(":drivers:sony")
include(":drivers:samsung")
include(":drivers:bose")
include(":drivers:soundcore")
include(":drivers:apple")
include(":drivers:nothing")
include(":drivers:xiaomi")
include(":core:bluetooth")
include(":core:audio")
include(":core:database")
include(":core:designsystem")
include(":feature:dashboard")
include(":feature:devices")
include(":feature:equalizer")
include(":feature:noisecontrol")
include(":feature:gestures")
include(":feature:automation")
include(":feature:battery")
include(":feature:diagnostics")
include(":feature:settings")
