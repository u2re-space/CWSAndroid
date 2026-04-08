// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Groovy @capacitor/android reads rootProject.ext.* — mirror runtime/cwsp/android/variables.gradle
extra.apply {
    set("minSdkVersion", 24)
    set("compileSdkVersion", 36)
    set("targetSdkVersion", 36)
    set("androidxActivityVersion", "1.11.0")
    set("androidxAppCompatVersion", "1.7.1")
    set("androidxCoordinatorLayoutVersion", "1.3.0")
    set("androidxCoreVersion", "1.17.0")
    set("androidxFragmentVersion", "1.8.9")
    set("coreSplashScreenVersion", "1.2.0")
    set("androidxWebkitVersion", "1.14.0")
    set("junitVersion", "4.13.2")
    set("androidxJunitVersion", "1.3.0")
    set("androidxEspressoCoreVersion", "3.7.0")
    set("cordovaAndroidVersion", "14.0.1")
}