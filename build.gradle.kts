// Declares every plugin once on the root classpath (apply false) so subprojects share one
// classloader — without this the Kotlin Gradle plugin loads per-subproject and warns.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
