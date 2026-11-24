plugins {
    id("sonic.android.library")
}

android {
    namespace = "com.soniccontrol.core.database"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(project(":core:devices"))
}
