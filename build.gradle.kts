// Plugins declared once here (apply false) so subprojects share one classloader.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
