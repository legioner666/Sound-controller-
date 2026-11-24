plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.core.designsystem"
}

dependencies {
    implementation(project(":core:devices"))
}
