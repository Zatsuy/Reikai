// Reikai JP's Yomitan engine: Yomitan's unmodified release (assets/yomitan/, written only by
// scripts/fork/yomitan_bump.py), the browser-extension stand-in and the Kotlin that hosts them.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "jp.reikai.yomitan"
}

dependencies {
    // Callers configure their own WebViews through androidx.webkit (the app's debug engine check too).
    api(libs.androidx.webkit)
    // The engine takes the app's OkHttpClient for Yomitan's cross-origin requests.
    api(libs.okhttp.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // FileProvider, which shares card media with AnkiDroid.
    implementation(libs.androidx.core)
    // Local audio's android.db; the app ships this SQLite already (its own database uses it).
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logcat)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
