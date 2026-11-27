plugins {
    id("sonic.android.application")
}

val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.soniccontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.soniccontrol"
        minSdk = 29 // Android 10
        targetSdk = 36 // Android 16
        versionCode = runNumber
        versionName = "0.8.$runNumber"
        // Commit the build was made from (CI sets GITHUB_SHA); shown in diagnostics exports (TASK 010).
        buildConfigField("String", "GIT_COMMIT", "\"" + (providers.environmentVariable("GITHUB_SHA").orNull?.take(7) ?: "local") + "\"")
    }

    signingConfigs {
        // Public, fixed debug key so every CI build installs over the previous one. Protects nothing.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key, only when CI secrets are present. Never committed.
        val uploadStore = System.getenv("SONIC_UPLOAD_KEYSTORE")
        if (uploadStore != null && file(uploadStore).exists()) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("SONIC_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("SONIC_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("SONIC_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds are signed only with the private upload key. Without it they stay UNSIGNED:
            // the public debug key in this repository is never used for a release.
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = true // lint every module the app uses
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    listOf(
        ":core:protocol", ":core:devices", ":core:diagnostics", ":core:bluetooth", ":core:audio", ":core:database",
        ":core:designsystem",
        ":drivers:generic", ":drivers:jbl", ":drivers:sony", ":drivers:samsung", ":drivers:bose", ":drivers:soundcore",
        ":drivers:apple", ":drivers:nothing", ":drivers:xiaomi",
        ":feature:dashboard", ":feature:devices", ":feature:equalizer", ":feature:noisecontrol", ":feature:gestures",
        ":feature:automation", ":feature:battery", ":feature:diagnostics", ":feature:settings",
        ":core:musiclibrary", ":core:player", ":core:background", ":feature:player", ":core:automation",
    ).forEach { implementation(project(it)) }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// A stable release (tag v*) must be signed with the private upload key. CI sets SONIC_REQUIRE_UPLOAD_KEY=true for tags.
val requireUploadKey = System.getenv("SONIC_REQUIRE_UPLOAD_KEY") == "true"
tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            if (requireUploadKey && android.signingConfigs.findByName("upload") == null) {
                throw GradleException("Stable release refused: no private upload key configured (SONIC_UPLOAD_KEYSTORE).")
            }
        }
    }
}
