plugins {
    id("sonic.android.library")
}

android {
    namespace = "com.soniccontrol.core.bluetooth"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:protocol"))
    implementation(project(":core:diagnostics"))
}
