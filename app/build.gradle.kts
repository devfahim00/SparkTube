import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

    // Firebase: google-services reads app/google-services.json and generates
    // resources for the Firebase SDKs; crashlytics handles symbol/mapping upload.
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.sparktube.app"
    // Firebase BoM 34.x SDKs are built against SDK 35, so the app must
    // compile against it too (targetSdk intentionally stays at 34).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sparktube.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.2"
    }

    // Release signing: credentials come from environment variables so they
    // never live in the repo. CI exports them from GitHub secrets; local
    // builds without the vars simply produce an unsigned release APK.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = keystoreFile != null && keystorePassword != null &&
        keyAlias != null && keyPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports modern java.* APIs to old Android runtimes. Without this,
        // NewPipeExtractor's Collectors.toUnmodifiableList() (Java 10 API)
        // crashes the app with NoSuchMethodError on Android 10 and below —
        // those runtimes only ship the Java 8 stream API.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Kotlin 2.2 DSL: the old android.kotlinOptions block is deprecated in KGP
// 2.x — compilerOptions is the supported replacement.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3Version = "1.3.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // NewPipeExtractor (from JitPack) + OkHttp downloader
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Desugared java.* APIs (streams, toUnmodifiableList, time, …) for
    // Android < 11 — see compileOptions.isCoreLibraryDesugaringEnabled.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Image loading
    implementation("io.coil-kt:coil:2.6.0")

    // Playback
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Firebase (BoM keeps all Firebase library versions compatible).
    // Crashlytics → crash reports; Analytics → usage events.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
}
