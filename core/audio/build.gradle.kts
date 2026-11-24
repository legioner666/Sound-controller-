plugins {
    id("sonic.android.library")
}

android {
    namespace = "com.soniccontrol.core.audio"
}

dependencies {
    implementation(project(":core:devices"))
}
