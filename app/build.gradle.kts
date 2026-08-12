import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is driven by a keystore.properties file that is NOT committed (see .gitignore).
// When it is absent (a fresh clone, or CI without secrets) the release build is simply left
// unsigned, so the project still configures and debug builds still work. Provide keystore.properties
// to produce a signed release AAB for the Play Store.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.relaypony.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.relaypony.android"
        minSdk = 23
        targetSdk = 36
        // Bumped well past every versionCode I know about (local tree was 3, the diverged
        // GitHub main is 2, my own TV-debug testing build was 4) since I can't see what's
        // actually live on Play from here — verify this beats the live versionCode before
        // uploading, and bump further if not; Play will reject an upload that doesn't increase.
        // versionName follows GitHub main's own bump to 2.0 for the beacon/hotspot/send-by-address
        // work merged in here, plus TV support and the other fixes on top.
        versionCode = 10
        versionName = "2.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required: the app DEX step desugars agepony-core's java.util.Base64 (API 26) down to
        // minSdk 23. Core library desugaring must be enabled in the module that consumes the
        // desugared APIs, which is this application module.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attach the signing config when a keystore is present, so a keystore-less
            // build (clone/CI) configures cleanly and produces an unsigned release instead of
            // failing. With keystore.properties supplied, the release AAB is signed.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":crypto"))
    implementation(project(":transport"))
    implementation(project(":session"))

    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
