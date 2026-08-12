plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.relaypony.session"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":crypto"))
    implementation(project(":transport"))
    implementation(libs.kotlinx.serialization.json)
    // IdentityBackup encrypts/decrypts directly with age's scrypt recipient/identity types, so
    // this module needs its own line on agepony-core: crypto's dependency on it is
    // `implementation`, which by design doesn't leak transitively to session. Resolved to the
    // :agepony-core project of the AgePonyAndroid composite build via the dependencySubstitution
    // rule in settings.gradle.kts; the version below is a placeholder that substitution replaces.
    implementation("com.agepony:agepony-core:1.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
