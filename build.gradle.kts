// Top-level build file for the SparkTube project
plugins {
    // 8.6.x is required for compileSdk 35. Gradle 8.7 wrapper satisfies AGP 8.6.
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.2.x toolchain — kept at 2.2.21 from the earlier migration;
    // staying is safer than downgrading (some deps carry 2.x metadata).
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
