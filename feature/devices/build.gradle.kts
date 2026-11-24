plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.devices"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:protocol"))
}
