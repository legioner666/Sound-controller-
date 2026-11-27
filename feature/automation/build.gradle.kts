plugins {
    id("sonic.android.compose")
}

android {
    namespace = "com.soniccontrol.feature.automation"
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:protocol"))
    implementation(project(":core:database"))
    implementation(project(":core:audio"))
    implementation(project(":core:player"))
    implementation(project(":feature:player"))
}
