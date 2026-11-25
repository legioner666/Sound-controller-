plugins {
    id("sonic.android.library")
}

android {
    namespace = "com.soniccontrol.core.background"
}

dependencies {
    implementation(project(":core:devices"))
}
