import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

apply(from = "../capacitor-cordova-android-plugins/cordova.variables.gradle")

android {
    namespace = "space.u2re.cws"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // cws = Kotlin-first standalone package id. cwsp = Capacitor-aligned `appId` (extended CWSP / CrossWord web shell); must match capacitor.config.ts.
    flavorDimensions += "distribution"
    productFlavors {
        create("cws") {
            dimension = "distribution"
            applicationId = "space.u2re.cws"
        }
        create("cwsp") {
            dimension = "distribution"
            applicationId = "space.u2re.cwsp"
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
        buildConfig = true
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

repositories {
    flatDir {
        dirs("../capacitor-cordova-android-plugins/src/main/libs", "libs")
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

    implementation(project(":capacitor-android"))
    implementation(project(":capacitor-cordova-android-plugins"))
    implementation(project(":capacitor-clipboard"))
    implementation(project(":capacitor-cws-bridge"))
    implementation(project(":gachlab-capacitor-permissions"))
    implementation(project(":capacitor-native-settings"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinatorlayout)
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
    implementation(libs.java.websocket)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
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

/** Stage the same merged web tree as CWSP Capacitor (`npm run build:capacitor:web` in runtime/cwsp). */
fun findWorkspaceDir(start: File, relativeCandidates: List<String>): File? {
    var dir: File? = start.canonicalFile
    while (dir != null) {
        val current = requireNotNull(dir)
        for (relative in relativeCandidates) {
            val candidate = File(current, relative)
            if (candidate.isDirectory) return candidate
        }
        dir = current.parentFile
    }
    return null
}

fun findCwspDistCapacitor(start: File): File? {
    return findWorkspaceDir(
        start,
        listOf(
            "runtime/cwsp/dist/capacitor",
            "U2RE.space/runtime/cwsp/dist/capacitor",
            "../U2RE.space/runtime/cwsp/dist/capacitor"
        )
    )
}

val cwspCapacitorWebDir =
    findCwspDistCapacitor(rootProject.rootDir)
        ?: rootProject.file("../../runtime/cwsp/dist/capacitor")
val capacitorPublicAssets = layout.projectDirectory.dir("src/main/assets/public")

tasks.register<Sync>("syncCwspCapacitorWeb") {
    group = "build"
    description = "Copy runtime/cwsp/dist/capacitor into app assets/public for CapacitorWebActivity"
    onlyIf { cwspCapacitorWebDir.isDirectory }
    from(cwspCapacitorWebDir)
    into(capacitorPublicAssets)
}
tasks.named("preBuild").configure {
    dependsOn(tasks.named("syncCwspCapacitorWeb"))
}

/** Which flavor `attachDebug` installs/launches: `cws` (Kotlin-only, default) or `cwsp` (hybrid CWSP + WebView). */
val cwsAdbFlavor: String =
    (findProperty("cwsAdbFlavor")?.toString()?.trim()?.lowercase() ?: "cws").let {
        if (it == "cws") "cws" else "cwsp"
    }

val launchPackageName =
    when (cwsAdbFlavor) {
        "cwsp" -> "space.u2re.cwsp"
        else -> "space.u2re.cws"
    }

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
    description =
        "Install and launch debug on ADB (flavor via -PcwsAdbFlavor=cws|cwsp, default cws native). Package: $launchPackageName"
    val flavorCap = cwsAdbFlavor.replaceFirstChar { it.titlecaseChar() }
    dependsOn("install${flavorCap}Debug")

    doLast {
        val adbSerial = project.resolveAdbSerial(debugAdbIp)
        val debugApk =
            project.layout.buildDirectory
                .file("outputs/apk/$cwsAdbFlavor/debug/app-$cwsAdbFlavor-debug.apk")
                .get()
                .asFile
        if (!debugApk.exists()) {
            throw GradleException("Debug APK not found: ${debugApk.absolutePath} (run install${cwsAdbFlavor.replaceFirstChar { it.titlecaseChar() }}Debug first)")
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