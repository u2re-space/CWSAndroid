import java.io.File
import java.net.URI

/**
 * Resolve runtime/cwsp/node_modules for this repo layout:
 * - Monorepo: U2RE.space/runtime/cwsp/node_modules (Android may live under apps/CWSAndroid or elsewhere).
 * - Standalone CWSAndroid clone: sibling checkouts are found by scanning one directory level under each
 *   ancestor for a child directory that contains runtime/cwsp/node_modules.
 */
fun findCwspNodeModules(start: File): File? {
    var dir: File? = start.canonicalFile
    while (dir != null) {
        val direct = File(dir, "runtime/cwsp/node_modules")
        if (direct.isDirectory) return direct
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            val nested = File(child, "runtime/cwsp/node_modules")
            if (nested.isDirectory) return nested
        }
        dir = dir.parentFile
    }
    val local = File(start, "node_modules")
    return local.takeIf { it.isDirectory }
}

val cwspNodeModules: File =
    System.getenv("CWS_CWSP_NODE_MODULES")?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
        ?: findCwspNodeModules(rootDir)
        ?: error(
            "Could not find runtime/cwsp/node_modules from ${rootDir.canonicalPath}. " +
                "Run: cd path/to/U2RE.space/runtime/cwsp && npm install. " +
                "Or set CWS_CWSP_NODE_MODULES to that node_modules directory."
        )

val cwspRoot: File = cwspNodeModules.parentFile

/**
 * Local Capacitor plugin (Java). Prefer `runtime/cwsp/plugins/...` when present (npm `file:` checkout);
 * otherwise use the copy shipped with this Android repo (monorepo layouts often omit `cwsp/plugins`).
 */
fun resolveCapacitorCwsBridgeAndroidDir(cwsp: File, androidRoot: File): File {
    val fromCwsp = File(cwsp, "plugins/capacitor-cws-bridge/android")
    if (fromCwsp.isDirectory) return fromCwsp
    val fromRepo = File(androidRoot, "plugins/capacitor-cws-bridge/android")
    check(fromRepo.isDirectory) {
        "capacitor-cws-bridge Android sources missing.\n" +
            "  Tried: ${fromCwsp.absolutePath}\n" +
            "  Tried: ${fromRepo.absolutePath}\n" +
            "From monorepo: copy or link CWSAndroid/plugins/capacitor-cws-bridge into runtime/cwsp/plugins/, " +
            "or keep building from a CWSAndroid checkout that includes plugins/."
    }
    return fromRepo
}

val capacitorAndroidDir: File =
    System.getenv("CWS_CAPACITOR_ANDROID_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
        ?: File(cwspNodeModules, "@capacitor/android/capacitor")

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
    }
}

rootProject.name = "space.u2re.cws"

include(":app")
include(":audioswitch-stub")
include(":capacitor-cordova-android-plugins")
project(":capacitor-cordova-android-plugins").projectDir = file("capacitor-cordova-android-plugins")

include(":capacitor-android")
project(":capacitor-android").projectDir = capacitorAndroidDir

include(":capacitor-clipboard")
project(":capacitor-clipboard").projectDir = File(cwspNodeModules, "@capacitor/clipboard/android")

include(":capacitor-cws-bridge")
project(":capacitor-cws-bridge").projectDir = resolveCapacitorCwsBridgeAndroidDir(cwspRoot, rootDir)

include(":gachlab-capacitor-permissions")
project(":gachlab-capacitor-permissions").projectDir = File(cwspNodeModules, "@gachlab/capacitor-permissions/android")

include(":capacitor-native-settings")
project(":capacitor-native-settings").projectDir = File(cwspNodeModules, "capacitor-native-settings/android")

// For local development with the LiveKit Android SDK only.
// includeBuild("../components-android")
