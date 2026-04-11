plugins {
    // Versions come from the root project plugins { ... apply false } (libs.versions.toml); do not repeat a version here.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.twilio.audioswitch"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
