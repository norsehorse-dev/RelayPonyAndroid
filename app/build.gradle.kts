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
    // One pinned NDK for AGP and scripts/build-pake.sh, read from gradle.properties.
    ndkVersion = providers.gradleProperty("ndkVersion").get()

    defaultConfig {
        applicationId = "com.relaypony.android"
        minSdk = 24
        targetSdk = 36
        // versionCode must increase with every release on Play, F-Droid and GitHub alike.
        versionCode = 14
        versionName = "4.0.1"

        ndk {
            // 4.0 ships a native library for the first time (RelayPonyPake, word-code pairing),
            // built for exactly these ABIs by scripts/build-pake.sh. Restrict the APK to them so
            // JNA's jnidispatch and AndroidX don't add ABIs with no RelayPonyPake (an install on
            // one of those couldn't load it, and F-Droid flags mismatched per-ABI libraries).
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    // Reproducible builds: keep the git commit and the Play dependency manifest out of the APK,
    // as PassPonyAndroid does, so two builds of the same source match byte for byte.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        // minSdk 24. Core library desugaring must be enabled in the module that consumes the
        // desugared APIs, which is this application module.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            vcsInfo.include = false
            // R8 shrinks, optimizes and obfuscates the release build (Play's DEX optimization check).
            // R8 is deterministic, so this keeps reproducible builds intact; keep rules are in
            // proguard-rules.pro, which lists what each one is for.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":pake"))

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
