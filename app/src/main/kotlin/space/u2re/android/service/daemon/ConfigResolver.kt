package space.u2re.cws.daemon

import android.util.Base64
import java.io.File
import java.net.URLDecoder

data class ResolveContext(
    val basePath: String = "",
    val deviceId: String = "",
    val hubClientId: String = "",
    val localIp: String = ""
)

object ConfigResolver {
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
                } catch (e: Exception) {
                    ""
                }
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                // Return URL as is (passthrough)
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
                } catch (e: Exception) {
                    return ""
                }
            }
        }
        return trimmed
    }

    private fun resolveFile(path: String, basePath: String): File {
        if (path.startsWith("~/")) {
            val userHome = try {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } catch (e: Exception) {
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
