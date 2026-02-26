import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "space.u2re.service"
    compileSdk = 36

    defaultConfig {
        applicationId = "space.u2re.service"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // TODO: Create your own release signing config
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.davidliu:audioswitch:89582c47c9a04c62f90aa5e57251af4800a62c9a"))
            .using(project(":audioswitch-stub"))
    }
}

dependencies {

    // For local development with the LiveKit Compose SDK only.
    // implementation("io.livekit:livekit-compose-components")

    implementation(project(":audioswitch-stub"))
    implementation(libs.livekit.lib)
    implementation(libs.livekit.components)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation("io.socket:socket.io-client:2.1.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.timberkt)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

val launchPackageName = android.defaultConfig.applicationId.orEmpty()
val debugAdbIp = "192.168.0.196:5555"

fun Project.adbOutput(vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder("adb", *args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return exitCode to output.trim()
}

fun Project.connectedAdbDevices(): List<String> {
    val (exitCode, output) = adbOutput("devices")
    if (exitCode != 0) {
        return emptyList()
    }
    return output
        .lines()
        .drop(1)
        .map { it.trim() }
        .mapNotNull { line ->
            val pieces = line.split('\t')
            if (pieces.size >= 2 && pieces[1].trim() == "device") pieces[0].trim() else null
        }
        .filter { it.isNotEmpty() }
}

fun Project.resolveAdbSerial(preferredIp: String): String {
    val requestedSerial = findProperty("adbSerial")?.toString()?.trim()
    if (!requestedSerial.isNullOrEmpty()) {
        return requestedSerial
    }

    val connected = connectedAdbDevices()
    if (connected.isNotEmpty()) {
        return connected.first()
    }

    logger.lifecycle("No connected ADB device found, connecting to $preferredIp")
    adbOutput("disconnect", preferredIp)
    adbOutput("connect", preferredIp)

    val afterConnect = connectedAdbDevices()
    if (afterConnect.isEmpty()) {
        throw GradleException("No ADB device available. Connect one and retry.")
    }
    return afterConnect.first()
}

tasks.register("attachDebug") {
    group = "android"
    description = "Attach or launch debug on the first available ADB device."
    dependsOn("assembleDebug")

    doLast {
        val adbSerial = project.resolveAdbSerial(debugAdbIp)
        val debugApk = project.layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!debugApk.exists()) {
            throw GradleException("Debug APK not found: ${debugApk.absolutePath}")
        }

        val (installCode, installOutput) = adbOutput(
            "-s",
            adbSerial,
            "install",
            "-r",
            debugApk.absolutePath
        )
        if (installCode != 0) {
            throw GradleException("adb install failed with code $installCode: $installOutput")
        }

        val (launchCode, launchOutput) = adbOutput(
            "-s",
            adbSerial,
            "shell",
            "monkey",
            "-p",
            launchPackageName,
            "-c",
            "android.intent.category.LAUNCHER",
            "1"
        )
        if (launchCode != 0) {
            throw GradleException("adb launch failed with code $launchCode: $launchOutput")
        }

        logger.lifecycle("Launched ${launchPackageName} on $adbSerial")
    }
}