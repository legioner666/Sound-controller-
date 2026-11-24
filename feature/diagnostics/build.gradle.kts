plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.diagnostics"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:protocol"))
}
