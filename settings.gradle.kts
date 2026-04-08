import java.io.File
import java.net.URI

/**
 * CWSP Capacitor native shell ([@capacitor/android]) lives under the monorepo `runtime/cwsp` install.
 * Align versions with `runtime/cwsp/package.json` / `android/variables.gradle` (see root [build.gradle.kts] extras).
 */
fun findCwspCapacitorAndroidModule(start: File): File? {
    var dir: File? = start.canonicalFile
    val rel = "runtime/cwsp/node_modules/@capacitor/android/capacitor"
    while (dir != null) {
        val candidate = File(dir, rel)
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile
    }
    return null
}

val capacitorAndroidDir =
    System.getenv("CWS_CAPACITOR_ANDROID_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
        ?: findCwspCapacitorAndroidModule(rootDir)
        ?: file("../../runtime/cwsp/node_modules/@capacitor/android/capacitor")

check(capacitorAndroidDir.isDirectory) {
    "Capacitor Android module missing at ${capacitorAndroidDir.absolutePath}. " +
        "From monorepo: cd runtime/cwsp && npm install. " +
        "Or set CWS_CAPACITOR_ANDROID_DIR to the `capacitor` subfolder inside @capacitor/android."
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_SETTINGS: @capacitor/android adds its own repositories block; ignore it and use these.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = URI("https://jitpack.io") }
        // For SNAPSHOT access
        // maven { url = URI("https://central.sonatype.com/repository/maven-snapshots/") }
    }
}

rootProject.name = "space.u2re.cws"
include(":app")
include(":audioswitch-stub")
include(":capacitor-android")
project(":capacitor-android").projectDir = capacitorAndroidDir

// For local development with the LiveKit Android SDK only.
// includeBuild("../components-android")
