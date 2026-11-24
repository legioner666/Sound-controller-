plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.settings"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:protocol"))
    implementation(project(":core:database"))
    implementation(project(":core:audio"))
}
