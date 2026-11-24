plugins {
    id("sonic.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures { compose = true }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    "implementation"(platform(catalog.findLibrary("androidx-compose-bom").get()))
    "implementation"(catalog.findLibrary("androidx-compose-ui").get())
    "implementation"(catalog.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(catalog.findLibrary("androidx-compose-ui-tooling-preview").get())
    "implementation"(catalog.findLibrary("androidx-compose-material3").get())
    "implementation"(catalog.findLibrary("androidx-compose-material-icons").get())
    "implementation"(catalog.findLibrary("androidx-lifecycle-runtime-compose").get())
    "debugImplementation"(catalog.findLibrary("androidx-compose-ui-tooling").get())
}
