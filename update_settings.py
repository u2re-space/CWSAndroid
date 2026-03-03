import re

file = "app/src/main/kotlin/space/u2re/android/service/daemon/Settings.kt"
with open(file, "r") as f:
    content = f.read()

# Add resolve() extension to Settings.kt
resolve_func = """
fun Settings.resolve(): Settings {
    val context = ResolveContext(deviceId = this.deviceId, hubClientId = this.hubClientId)
    return this.copy(
        authToken = ConfigResolver.resolve(this.authToken, context),
        hubToken = ConfigResolver.resolve(this.hubToken, context),
        hubClientId = ConfigResolver.resolve(this.hubClientId, context),
        tlsKeystoreAssetPath = ConfigResolver.resolve(this.tlsKeystoreAssetPath, context),
        tlsKeystorePassword = ConfigResolver.resolve(this.tlsKeystorePassword, context),
        hubDispatchUrl = ConfigResolver.resolve(this.hubDispatchUrl, context),
        apiEndpoint = ConfigResolver.resolve(this.apiEndpoint, context),
        apiKey = ConfigResolver.resolve(this.apiKey, context),
        destinations = this.destinations.map { ConfigResolver.resolve(it, context) }
    )
}
"""

if "fun Settings.resolve(): Settings" not in content:
    content = content.replace("object SettingsStore {", resolve_func + "\nobject SettingsStore {")

# Update load()
load_regex = re.compile(r"fun load\(context: Context\): Settings \{[\s\S]*?migrated\n\s*\} catch[\s\S]*?\}")
new_load = """fun load(context: Context): Settings {
        return try {
            val raw = prefs(context).getString(PREF_NAME, null)
                ?: prefs(context).getString(PREF_NAME_LEGACY, null)
                ?: return defaultSettings()
            val parsed = gson.fromJson(raw, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
            var merged = defaultSettings().mergeFromMap(parsed)
            
            // Migration: carry legacy userKey into authToken if needed.
            if (merged.authToken.isBlank() && parsed["userKey"] is String) {
                merged = merged.copy(authToken = (parsed["userKey"] as? String) ?: merged.authToken)
            }
            
            // Merge from clients.json if configPath is provided
            if (merged.configPath.isNotBlank()) {
                val resolvedPath = ConfigResolver.resolve(merged.configPath, ResolveContext(deviceId = merged.deviceId))
                try {
                    val file = java.io.File(resolvedPath)
                    if (file.exists() && file.isFile) {
                        val clientsRaw = file.readText()
                        val clientsParsed = gson.fromJson(clientsRaw, Map::class.java) as? Map<*, *> ?: emptyMap<String, Any>()
                        val fromClients = defaultSettings().mergeFromMap(clientsParsed)
                        
                        // Apply clients.json over defaults, then apply SharedPreferences non-default values over it.
                        // For simplicity, we just overwrite empty string fields in SharedPreferences with clients.json values
                        merged = merged.copy(
                            hubDispatchUrl = merged.hubDispatchUrl.ifBlank { fromClients.hubDispatchUrl },
                            authToken = merged.authToken.ifBlank { fromClients.authToken },
                            hubToken = merged.hubToken.ifBlank { fromClients.hubToken },
                            hubClientId = merged.hubClientId.ifBlank { fromClients.hubClientId },
                            apiEndpoint = merged.apiEndpoint.ifBlank { fromClients.apiEndpoint },
                            apiKey = merged.apiKey.ifBlank { fromClients.apiKey },
                            destinations = if (merged.destinations.isEmpty()) fromClients.destinations else merged.destinations
                        )
                    }
                } catch (e: Exception) {
                    // Ignore errors reading clients.json
                }
            }
            
            save(context, merged)
            merged
        } catch (_: Exception) {
            defaultSettings()
        }
    }"""
content = load_regex.sub(new_load, content)

with open(file, "w") as f:
    f.write(content)

print("Updated Settings.kt")
