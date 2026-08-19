// Top-level build file. Plugins are declared without being applied, so :app can
// apply them without repeating version numbers.
//
// There is no org.jetbrains.kotlin.android plugin here: since AGP 9.0 the
// Android plugin brings its own Kotlin support, and applying both is an error.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
