pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "RelayPony"

// Companion builds (AgePonyAndroid for the age core, PonyDirect-Kotlin for the WAN transport) are
// consumed as Gradle composite builds, so each stays one source of truth with no republish step.
//
// The pinned git SUBMODULE is always the default. That is exactly what a fresh clone, the release
// container and F-Droid build, so a local build compiles the same code they will. A sibling
// checkout (../AgePonyAndroid, ../PonyDirect-Kotlin) is used only when asked for:
//     ./gradlew -Prelaypony.useSiblings=true <task>
// or relaypony.useSiblings=true in ~/.gradle/gradle.properties, or RELAYPONY_USE_SIBLINGS=1.
// (Preferring the sibling automatically is how the 3.0 tag shipped a stale PonyDirect-Kotlin pin
// that built locally and failed on F-Droid, issue #3. Run tools/check_submodules.sh before tagging.)
val useSiblings = providers.gradleProperty("relaypony.useSiblings").orNull == "true" ||
    System.getenv("RELAYPONY_USE_SIBLINGS") == "1"

// An included Android build resolves the SDK on its own: from ANDROID_HOME, or from a
// local.properties in ITS directory, never the root one. A fresh submodule has no local.properties,
// so hand it the root's sdk.dir (local.properties is gitignored in every component, so the
// submodule stays clean). Nothing is written when the root has no sdk.dir or the file exists.
fun shareSdkDir(component: File) {
    val target = component.resolve("local.properties")
    if (target.exists()) return
    val root = settingsDir.resolve("local.properties")
    if (!root.exists()) return
    val sdkLine = root.readLines().firstOrNull { it.trim().startsWith("sdk.dir=") } ?: return
    target.writeText(
        "# Written by RelayPony's settings.gradle.kts from the root local.properties. Not tracked.\n" +
        sdkLine.trim() + "\n"
    )
}

fun componentBuild(dir: String): File {
    fun isBuild(f: File) = f.resolve("settings.gradle.kts").exists() || f.resolve("settings.gradle").exists()
    val sibling = settingsDir.resolve("../$dir")
    val submodule = settingsDir.resolve(dir)
    if (useSiblings && isBuild(sibling)) {
        logger.warn(
            "RelayPony: building $dir from the sibling checkout ${sibling.canonicalPath}, NOT the pinned " +
            "submodule. Release and F-Droid builds use the submodule; bump its pin before tagging."
        )
        shareSdkDir(sibling)
        return sibling
    }
    if (isBuild(submodule)) { shareSdkDir(submodule); return submodule }
    error(
        "$dir not found. This repository needs the $dir submodule: run " +
        "git submodule update --init --recursive (or clone with --recursive). To build against a " +
        "sibling checkout at ../$dir instead, pass -Prelaypony.useSiblings=true."
    )
}

// The dependencySubstitution maps the coordinate RelayPony declares to AgePony's
// :agepony-core project, so no changes to AgePony are required.
includeBuild(componentBuild("AgePonyAndroid")) {
    dependencySubstitution {
        substitute(module("com.agepony:agepony-core")).using(project(":agepony-core"))
    }
}

include(":app")
include(":crypto")
include(":transport")
include(":session")
include(":pake")

// PonyDirect provides the peer-to-peer WAN transport, resolved the same way (submodule by default).
includeBuild(componentBuild("PonyDirect-Kotlin")) {
    dependencySubstitution {
        substitute(module("com.ponydirect:ponydirect")).using(project(":ponydirect"))
    }
}
