// SphereSLAM root build. AGP 9 provides built-in Kotlin support, so no kotlin-android plugin is
// applied; per-module builds set the Kotlin JVM target via the KotlinCompile task.
plugins {
    alias(libs.plugins.android.library) apply false
}
