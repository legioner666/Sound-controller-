// Pure Kotlin/JVM module: no Android dependencies, unit tests run on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    "implementation"(catalog.findLibrary("kotlinx-coroutines-core").get())
    "testImplementation"(catalog.findLibrary("junit").get())
    "testImplementation"(catalog.findLibrary("kotlinx-coroutines-test").get())
}

tasks.withType<Test>().configureEach {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
