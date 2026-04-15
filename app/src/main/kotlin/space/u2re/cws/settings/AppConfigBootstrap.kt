package space.u2re.cws.settings

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "AppConfigBootstrap"
private const val DEFAULT_APP_CONFIG_ROOT = "/storage/emulated/0/AppConfig"
private const val FALLBACK_APP_CONFIG_DIR = "AppConfig"
private const val STOCK_CONFIG_ASSET_DIR = "stock/config"
private const val STOCK_HTTPS_ASSET_DIR = "stock/https"

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
                copyMissingAssets(context, STOCK_CONFIG_ASSET_DIR, configDir, stockConfigFiles)
                copyMissingAssets(context, STOCK_HTTPS_ASSET_DIR, httpsDir, stockHttpsFiles)
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
            if (target.exists() && target.length() > 0L) continue
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
     * Write minimal fallback config files when assets are absent.
     *
     * NOTE: these defaults define the same network/bootstrap assumptions the
     * daemon relies on for local config, HTTPS, and unified coordinator mode.
     */
    private fun ensureStockFallbackConfigFiles(configDir: File) {
        val fallbackPortableCore = """
            {
              "version": 2,
              "core": {
                "endpointIDs": "fs:./clients.json",
                "gateways": "fs:./gateways.json",
                "network": "fs:./network.json"
              }
            }
        """.trimIndent()
        val fallbackPortableEndpoint = """
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
        val fallbackPortableConfig = """
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
              "launcherEnv": {
                "CWS_PORTABLE_CONFIG_PATH": "fs:./portable.config.json",
                "CWS_HTTPS_KEY": "fs:../https/local/multi.key",
                "CWS_HTTPS_CERT": "fs:../https/local/multi.crt",
                "CWS_HTTPS_CA": "fs:../https/local/rootCA.crt",
                "CWS_HTTPS_CERT_MODULE": "fs:./certificate.mjs",
                "CWS_NETWORK_SCHEMA_VERSION": 2,
                "CWS_COORDINATOR_MODE": "unified"
              }
            }
        """.trimIndent()
        val fallbackCertificateModule = """
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

        writeIfMissing(File(configDir, "clients.json"), "{}\n")
        writeIfMissing(File(configDir, "gateways.json"), "{}\n")
        writeIfMissing(File(configDir, "network.json"), "{}\n")
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
