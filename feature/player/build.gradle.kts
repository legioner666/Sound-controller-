plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.player"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:player"))
    implementation(project(":core:musiclibrary"))
    implementation(libs.androidx.activity.compose)
}
