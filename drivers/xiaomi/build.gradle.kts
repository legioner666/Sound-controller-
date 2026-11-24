plugins {
    id("sonic.jvm.library")
}

dependencies {
    testImplementation(testFixtures(project(":core:devices")))
    implementation(project(":core:devices"))
    implementation(project(":core:protocol"))
    implementation(project(":core:diagnostics"))
}
