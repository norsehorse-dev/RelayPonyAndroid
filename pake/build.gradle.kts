// Word-code pairing for RelayPony 4.0: UniFFI Kotlin bindings and jniLibs generated from
// RelayPonyPake's pake-ffi crate by scripts/build-pake.sh (both gitignored; RelayPonyPake is the
// source of truth), plus the thin Kotlin flow on top of them (WordCodePairing).
//
// Pattern copied from PassPonyAndroid's :core module.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.relaypony.pake"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = providers.gradleProperty("ndkVersion").get()

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // UniFFI's generated Cleaner fallback trips a known NewApi false positive; scoped to uniffi/.
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build the Rust core during the Gradle build when its outputs are missing (a fresh clone, or
// F-Droid's buildserver after the scanner). Local dev and the release container run
// scripts/build-pake.sh first, so this no-ops for them.
val cargoBinDir: String =
    (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory

val buildPake = tasks.register<Exec>("buildPake") {
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-pake.sh")
    val soFile = file("src/main/jniLibs/arm64-v8a/librelaypony_pake_ffi.so")
    val bindings = file("src/main/kotlin/uniffi/relaypony_pake_ffi/relaypony_pake_ffi.kt")
    outputs.files(soFile, bindings)
    onlyIf { !soFile.exists() || !bindings.exists() }
    val ndkVersionProp = providers.gradleProperty("ndkVersion")
    val useSiblings = providers.gradleProperty("relaypony.useSiblings").orNull == "true"
    doFirst {
        // Same opt-in as settings.gradle.kts: the submodule unless a sibling is asked for.
        if (useSiblings) environment("RELAYPONY_USE_SIBLINGS", "1")
        // F-Droid's gradle step doesn't inherit PATH from prebuild:, so put cargo on PATH here.
        environment("PATH", "$cargoBinDir${File.pathSeparator}${System.getenv("PATH") ?: ""}")
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK")
            ?: "${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: sdkDirectory.get().asFile.absolutePath}/ndk/${ndkVersionProp.get()}"
        environment("ANDROID_NDK_HOME", ndkHome)
    }
}

tasks.named("preBuild") { dependsOn(buildPake) }

dependencies {
    implementation(project(":session"))
    // UniFFI's Kotlin runtime loads the .so through JNA; the @aar artifact carries jnidispatch per ABI.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
