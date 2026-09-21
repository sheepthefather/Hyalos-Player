// Top-level build file. Plugin versions are declared here and applied by the
// modules, so there is one place to look when something needs updating.
//
// `apply false` is the standard idiom: it puts the plugin on the classpath
// without applying it to the root project, which has no Android or Kotlin code
// of its own.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
