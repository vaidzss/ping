// Declare all plugins once here (apply false) so subprojects share a single
// classloader for the Kotlin/Android Gradle plugins.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
