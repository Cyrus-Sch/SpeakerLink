// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the Kotlin
// plugins here puts the newer Kotlin Gradle plugin on the build classpath for every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
