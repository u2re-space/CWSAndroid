package space.u2re.cws.settings

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "AppConfigBootstrap"
private const val DEFAULT_APP_CONFIG_ROOT = "/storage/emulated/0/AppConfig"
private const val FALLBACK_APP_CONFIG_DIR = "AppConfig"
private const val STOCK_CONFIG_ASSET_DIR = "stock/config"
private const val STOCK_HTTPS_ASSET_DIR = "stock/https"

private val fallbackPortableCore = """
    {
      "version": 2,
      "core": {
        "endpointIDs": "fs:./clients.json",
        "gateways": "fs:./gateways.json",
        "network": "fs:./network.json"
      }
    }
""".trimIndent()

private val fallbackPortableEndpoint = """
    {
      "version": 2,
      "endpoint": {
        "routing": {
          "byId": true,
          "aliases": true,
          "reverseTunnel": true
        },
        "websocket": {
          "keepalive": true,
          "keepaliveIntervalMs": 15000,
          "staleAfterMs": 45000
        }
      }
    }
""".trimIndent()

private val fallbackClientsJson = """
    {
      "L-192.168.0.196": {
        "origins": "192.168.0.196,45.150.9.153,android-native,android-pwa,vds-client",
        "relations": "both ws,http,tcp"
      },
      "L-192.168.0.110": {
        "origins": "192.168.0.110,192.168.0.111,desktop-110",
        "relations": "both ws,http,tcp"
      },
      "L-192.168.0.200": {
        "origins": "192.168.0.200,192.168.0.201,45.147.121.152,gateway-200",
        "flags": { "gateway": true },
        "relations": "both ws,http,tcp"
      },
      "L-192.168.0.208": {
        "origins": "192.168.0.208",
        "relations": "both ws,http,tcp"
      }
    }
""".trimIndent()

private val fallbackGatewaysJson = """
    {
      "gateways": [
        "L-192.168.0.200",
        "L-192.168.0.110"
      ],
      "destinations": [
        "L-192.168.0.110"
      ]
    }
""".trimIndent()

private val fallbackNetworkJson = """
    {
      "version": 2,
      "listenPort": 8443,
      "httpPort": 8080,
      "networkAliases": {
        "192.168.0.200": "L-192.168.0.200",
        "192.168.0.201": "L-192.168.0.200",
        "45.147.121.152": "L-192.168.0.200",
        "192.168.0.110": "L-192.168.0.110",
        "192.168.0.111": "L-192.168.0.110",
        "192.168.0.196": "L-192.168.0.196",
        "45.150.9.153": "L-192.168.0.196",
        "192.168.0.208": "L-192.168.0.208"
      },
      "endpoints": [
        "https://192.168.0.200:8443/",
        "https://45.147.121.152:8443/"
      ],
      "runtime": {
        "clipboardPeerTargets": [
          "L-192.168.0.110",
          "L-192.168.0.200",
          "L-192.168.0.196",
          "L-192.168.0.208"
        ]
      }
    }
""".trimIndent()

private val fallbackPortableConfig = """
    {
      "version": 2,
      "portableModules": {
        "clients": "fs:./clients.json",
        "gateways": "fs:./gateways.json",
        "endpointIDs": "fs:./clients.json",
        "network": "fs:./network.json",
        "core": "fs:./portable-core.json",
        "endpoint": "fs:./portable-endpoint.json"
      },
      "destinations": [
        "L-192.168.0.110"
      ],
      "launcherEnv": {
        "CWS_PORTABLE_CONFIG_PATH": "fs:./portable.config.json",
        "CWS_ASSOCIATED_ID": "L-192.168.0.196",
        "CWS_ASSOCIATED_TOKEN": "n3v3rm1nd",
        "CWS_BRIDGE_USER_ID": "L-192.168.0.196",
        "CWS_BRIDGE_USER_KEY": "n3v3rm1nd",
        "CWS_BRIDGE_DEVICE_ID": "L-192.168.0.196",
        "CWS_BRIDGE_ENDPOINT_URL": "https://192.168.0.200:8443/,https://45.147.121.152:8443/",
        "CWS_BRIDGE_ENDPOINTS": [
          "https://192.168.0.200:8443/",
          "https://45.147.121.152:8443/"
        ],
        "CWS_BRIDGE_PRECONNECT_TARGETS": [
          "L-192.168.0.200",
          "L-192.168.0.110"
        ],
        "CWS_HTTPS_KEY": "fs:../https/local/multi.key",
        "CWS_HTTPS_CERT": "fs:../https/local/multi.crt",
        "CWS_HTTPS_CA": "fs:../https/local/rootCA.crt",
        "CWS_HTTPS_CERT_MODULE": "fs:./certificate.mjs",
        "CWS_NETWORK_SCHEMA_VERSION": 2,
        "CWS_COORDINATOR_MODE": "unified",
        "CWS_COMPAT_SOCKETIO": "false",
        "CWS_WS_CANONICAL": "true"
      }
    }
""".trimIndent()

private val fallbackCertificateModule = """
    import fs from "node:fs";
    import path from "node:path";
    import { fileURLToPath } from "node:url";
    
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    const httpsDir = path.resolve(__dirname, "../https/local");
    
    export const key = fs.readFileSync(path.join(httpsDir, "multi.key"), "utf8");
    export const cert = fs.readFileSync(path.join(httpsDir, "multi.crt"), "utf8");
    export const ca = fs.readFileSync(path.join(httpsDir, "rootCA.crt"), "utf8");
    export default { key, cert, ca };
""".trimIndent()

/**
 * Bootstrapper for stock config and HTTPS assets used by the Android runtime.
 *
 * AI-READ: this file explains where the daemon's config/CA/certificate files
 * come from before user overrides or resolved `fs:` paths take over.
 */
object AppConfigBootstrap {
    private val lock = Any()
    @Volatile
    private var ensuredRootPath: String? = null

    private val stockConfigFiles = listOf(
        "clients.json",
        "gateways.json",
        "network.json",
        "portable-endpoint.json",
        "portable-core.json",
        "portable.config.json",
        "portable.config.110.json",
        "portable.config.vds.json",
        "certificate.mjs"
    )

    private val stockHttpsFiles = listOf(
        "rootCA.crt",
        "multi.crt",
        "multi.key",
        "server.cnf"
    )

    /** Ensure the writable config root exists and contains the stock/fallback config payloads. */
    fun ensureStockConfig(context: Context): String {
        val cached = ensuredRootPath
        if (!cached.isNullOrBlank()) return cached

        synchronized(lock) {
            val recheck = ensuredRootPath
            if (!recheck.isNullOrBlank()) return recheck

            val root = resolveWritableRoot(context)
            runCatching {
                val configDir = File(root, "config").apply { mkdirs() }
                val httpsDir = File(root, "https").apply { mkdirs() }
                val httpsLocalDir = File(httpsDir, "local").apply { mkdirs() }
                copyMissingAssets(context, STOCK_CONFIG_ASSET_DIR, configDir, stockConfigFiles)
                copyMissingAssets(context, STOCK_HTTPS_ASSET_DIR, httpsDir, stockHttpsFiles)
                copyMissingAssets(context, STOCK_HTTPS_ASSET_DIR, httpsLocalDir, stockHttpsFiles)
                ensureStockFallbackConfigFiles(configDir)
            }.onFailure { error ->
                Log.w(TAG, "stock AppConfig bootstrap failed for $root: ${error.message}")
            }

            ensuredRootPath = root.absolutePath
            return root.absolutePath
        }
    }

    /**
     * Pick the writable config root.
     *
     * WHY: Android storage access differs across devices, so the runtime tries
     * external AppConfig first, then app-scoped external storage, then filesDir.
     */
    private fun resolveWritableRoot(context: Context): File {
        val preferred = File(DEFAULT_APP_CONFIG_ROOT)
        if (ensureWritable(preferred)) return preferred

        val scoped = File(context.getExternalFilesDir(null), FALLBACK_APP_CONFIG_DIR)
        if (ensureWritable(scoped)) return scoped

        return context.filesDir
    }

    private fun ensureWritable(dir: File): Boolean {
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.isDirectory && (dir.canWrite() || dir.setWritable(true))
        }.getOrDefault(false)
    }

    /** Copy stock asset files only when the destination file is still missing or empty. */
    private fun copyMissingAssets(
        context: Context,
        sourceAssetDir: String,
        destinationDir: File,
        fileNames: List<String>
    ) {
        for (name in fileNames) {
            val target = File(destinationDir, name)
            if (target.exists() && target.length() > 0L && !isGeneratedFallbackStockFile(target, name)) continue
            val assetPath = "$sourceAssetDir/$name"
            runCatching {
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "failed to copy $assetPath -> ${target.absolutePath}: ${error.message}")
            }
        }
    }

    /**
     * Refresh only the files that still contain the app-generated fallback
     * placeholder, so newer bundled stock assets can replace stale bootstrap
     * config after reinstall without clobbering user-edited files.
     */
    private fun isGeneratedFallbackStockFile(target: File, fileName: String): Boolean {
        val normalized = runCatching {
            target.readText().replace("\r\n", "\n").trim()
        }.getOrDefault("")
        if (normalized.isBlank()) return true
        return when (fileName) {
            "portable-core.json" -> normalized == fallbackPortableCore
            "portable-endpoint.json" -> normalized == fallbackPortableEndpoint
            "portable.config.json",
            "portable.config.110.json",
            "portable.config.vds.json" -> normalized == fallbackPortableConfig
            "certificate.mjs" -> normalized == fallbackCertificateModule
            else -> false
        }
    }

    /**
     * Write minimal fallback config files when assets are absent.
     *
     * NOTE: these defaults define the same network/bootstrap assumptions the
     * daemon relies on for local config, HTTPS, and unified coordinator mode.
     */
    private fun ensureStockFallbackConfigFiles(configDir: File) {
        writeIfMissing(File(configDir, "clients.json"), "$fallbackClientsJson\n")
        writeIfMissing(File(configDir, "gateways.json"), "$fallbackGatewaysJson\n")
        writeIfMissing(File(configDir, "network.json"), "$fallbackNetworkJson\n")
        writeIfMissing(File(configDir, "portable-core.json"), "$fallbackPortableCore\n")
        writeIfMissing(File(configDir, "portable-endpoint.json"), "$fallbackPortableEndpoint\n")
        writeIfMissing(File(configDir, "portable.config.json"), "$fallbackPortableConfig\n")
        writeIfMissing(File(configDir, "portable.config.110.json"), "$fallbackPortableConfig\n")
        writeIfMissing(File(configDir, "portable.config.vds.json"), "$fallbackPortableConfig\n")
        writeIfMissing(File(configDir, "certificate.mjs"), "$fallbackCertificateModule\n")
    }

    private fun writeIfMissing(target: File, content: String) {
        if (target.exists() && target.length() > 0L) return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.onFailure { error ->
            Log.w(TAG, "failed to write fallback ${target.absolutePath}: ${error.message}")
        }
    }
}
