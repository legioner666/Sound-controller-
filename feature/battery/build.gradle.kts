plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.battery"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:protocol"))
    implementation(project(":core:database"))
}
