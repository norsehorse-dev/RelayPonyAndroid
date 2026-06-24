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

// AgePony provides the age crypto core, consumed as a Gradle composite build so the crypto
// stays one source of truth with no republish step. It is resolved in two ways:
//   * Local development: if a sibling checkout exists at ../AgePonyAndroid it is used, so
//     edits to AgePony are picked up immediately.
//   * Fresh clone / CI / F-Droid: the AgePonyAndroid git submodule inside this repo is used,
//     so the repository builds on its own. Clone with --recursive, or afterwards run
//     git submodule update --init --recursive
// The dependencySubstitution maps the coordinate RelayPony declares to AgePony's
// :agepony-core project, so no changes to AgePony are required.
val agePonySibling = settingsDir.resolve("../AgePonyAndroid")
val agePonySubmodule = settingsDir.resolve("AgePonyAndroid")
val agePonyBuild = when {
    agePonySibling.resolve("settings.gradle.kts").exists() ||
        agePonySibling.resolve("settings.gradle").exists() -> agePonySibling
    agePonySubmodule.resolve("settings.gradle.kts").exists() ||
        agePonySubmodule.resolve("settings.gradle").exists() -> agePonySubmodule
    else -> error(
        "AgePony build not found. This repository needs the AgePonyAndroid submodule: run " +
        "git submodule update --init --recursive (or clone with --recursive). " +
        "See the Building section of the README."
    )
}
includeBuild(agePonyBuild) {
    dependencySubstitution {
        substitute(module("com.agepony:agepony-core")).using(project(":agepony-core"))
    }
}

include(":app")
include(":crypto")
include(":transport")
include(":session")
