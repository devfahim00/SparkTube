// Top-level build file for the SparkTube project
plugins {
    // 8.6.x is required for compileSdk 35 (Firebase BoM 34.x SDKs build
    // against SDK 35). Gradle 8.7 wrapper already satisfies AGP 8.6.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false

    // Google services Gradle plugin — reads app/google-services.json
    id("com.google.gms.google-services") version "4.5.0" apply false

    // Firebase Crashlytics Gradle plugin — uploads mapping files and
    // processes crash symbols for release builds.
    id("com.google.firebase.crashlytics") version "3.0.4" apply false
}
