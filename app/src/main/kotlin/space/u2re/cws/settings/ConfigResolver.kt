package space.u2re.cws.settings

import android.util.Base64
import java.io.File
import java.net.URLDecoder

/** Resolution context for config indirections such as `id:`, `client:`, and relative file paths. */
data class ResolveContext(
    val basePath: String = "",
    val deviceId: String = "",
    val hubClientId: String = "",
    val localIp: String = ""
)

/**
 * Resolver for indirection-heavy settings values.
 *
 * AI-READ: this file explains why a stored setting may not be the literal value
 * the network/runtime code finally sees.
 */
object ConfigResolver {
    /**
     * Resolve one setting string from prefixes like `inline:`, `env:`, `fs:`,
     * `data:`, and identity shortcuts.
     */
    fun resolve(value: String, context: ResolveContext): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        when {
            trimmed.startsWith("inline:") -> {
                val inlineVal = trimmed.removePrefix("inline:")
                if ((inlineVal.startsWith("'") && inlineVal.endsWith("'")) ||
                    (inlineVal.startsWith("\"") && inlineVal.endsWith("\""))
                ) {
                    return inlineVal.substring(1, inlineVal.length - 1)
                }
                return inlineVal
            }
            trimmed.startsWith("env:") -> {
                val envName = trimmed.removePrefix("env:")
                return System.getenv(envName) ?: ""
            }
            trimmed.startsWith("id:") -> return context.deviceId
            trimmed.startsWith("client:") -> return context.hubClientId
            trimmed.startsWith("peer:") -> return context.deviceId
            trimmed.startsWith("local:") -> return context.localIp
            trimmed.startsWith("fs:") || trimmed.startsWith("file:") -> {
                val prefix = if (trimmed.startsWith("fs:")) "fs:" else "file:"
                val path = trimmed.removePrefix(prefix).trim()
                if (path.isEmpty()) return ""
                val file = resolveFile(path, context.basePath)
                return try {
                    if (file.exists() && file.isFile) file.readText() else ""
                } catch (_: Exception) {
                    ""
                }
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                return trimmed
            }
            trimmed.startsWith("data:") -> {
                try {
                    val parts = trimmed.removePrefix("data:").split(",", limit = 2)
                    if (parts.size == 2) {
                        val header = parts[0]
                        val data = parts[1]
                        if (header.endsWith(";base64")) {
                            return String(Base64.decode(data, Base64.DEFAULT))
                        }
                        return URLDecoder.decode(data, "UTF-8")
                    }
                } catch (_: Exception) {
                    return ""
                }
            }
        }
        return trimmed
    }

    /**
     * Resolve a settings file path relative to the current config root.
     *
     * NOTE: `~/` intentionally maps to Android external storage first, not a
     * traditional desktop home directory.
     */
    fun resolveFile(path: String, basePath: String = ""): File {
        if (path.startsWith("~/")) {
            val userHome = try {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } catch (_: Exception) {
                System.getProperty("user.home") ?: "/"
            }
            return File(userHome, path.removePrefix("~/"))
        }
        val file = File(path)
        if (file.isAbsolute) {
            return file
        }
        val baseFile = File(basePath)
        val parent = if (baseFile.isFile) baseFile.parentFile ?: File("/") else baseFile
        return File(parent, path)
    }
}
