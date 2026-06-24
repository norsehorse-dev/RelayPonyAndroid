// RelayPony root build script. Repositories are centralized in settings.gradle.kts.
// Mirrors AgePony's setup: every module uses AGP 9.2's built-in Kotlin (com.android.application
// or com.android.library). There is deliberately NO standalone kotlin("jvm") module, which under
// AGP 9 would force KGP onto a removed BaseVariant API. Everything is Kotlin 2.2.10 (bundled by
// AGP 9.2.0). The Compose and kotlinx.serialization compiler plugins are first-party Kotlin
// compiler plugins (not KSP), which is why they cooperate with AGP built-in Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
