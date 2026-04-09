import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

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

    implementation(project(":capacitor-android"))
    implementation(libs.androidx.appcompat)
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

/** Stage the same merged web tree as CWSP Capacitor (`npm run build:capacitor:web` in runtime/cwsp). */
fun findCwspDistCapacitor(start: File): File? {
    var dir: File? = start.canonicalFile
    val rel = "runtime/cwsp/dist/capacitor"
    while (dir != null) {
        val candidate = File(dir, rel)
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    return null
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

/** Which flavor `attachDebug` installs/launches: `cwsp` (hybrid CWSP + WebView, default) or `cws` (Kotlin-only). */
val cwsAdbFlavor: String =
    (findProperty("cwsAdbFlavor")?.toString()?.trim()?.lowercase() ?: "cwsp").let {
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
        "Install and launch debug on ADB (flavor via -PcwsAdbFlavor=cws|cwsp, default cwsp hybrid). Package: $launchPackageName"
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