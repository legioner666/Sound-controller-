plugins {
    id("sonic.jvm.library")
}

dependencies {
    api(project(":core:devices"))
    api(libs.kotlinx.coroutines.core)
}
