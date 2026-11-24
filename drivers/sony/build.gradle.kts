plugins {
    id("sonic.jvm.library")
}

dependencies {
    implementation(project(":core:devices"))
    implementation(project(":core:protocol"))
    implementation(project(":core:diagnostics"))
}
