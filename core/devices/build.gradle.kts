plugins {
    id("sonic.jvm.library")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:diagnostics"))
    api(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(project(":core:protocol"))
}
