plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin version to match the Compose plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
