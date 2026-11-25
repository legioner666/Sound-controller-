plugins {
    id("sonic.android.library")
}

android {
    namespace = "com.soniccontrol.core.player"
}

dependencies {
    api(project(":core:musiclibrary"))
    api(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
}
