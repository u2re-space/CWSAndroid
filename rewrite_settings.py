import re

with open("app/src/main/kotlin/space/u2re/android/service/daemon/Settings.kt", "r") as f:
    content = f.read()

# Add configPath to Settings
content = re.sub(
    r"val hubDispatchUrl: String,",
    r"val hubDispatchUrl: String,\n    val configPath: String,",
    content
)

# Add configPath to SettingsPatch
content = re.sub(
    r"val hubDispatchUrl: String\? = null,",
    r"val hubDispatchUrl: String? = null,\n    val configPath: String? = null,",
    content
)

# Add to defaultSettings()
content = re.sub(
    r"hubDispatchUrl = \"\",",
    r"hubDispatchUrl = \"\",\n    configPath = \"\",",
    content
)

# Add to save()
content = re.sub(
    r"destinations = next.destinations.filter \{ it.isNotBlank\(\) \},",
    r"destinations = next.destinations.filter { it.isNotBlank() },\n            configPath = next.configPath,",
    content
)

# Add to update()
content = re.sub(
    r"hubDispatchUrl = patch.hubDispatchUrl \?: current.hubDispatchUrl,",
    r"hubDispatchUrl = patch.hubDispatchUrl ?: current.hubDispatchUrl,\n            configPath = patch.configPath ?: current.configPath,",
    content
)

# Add to mergeFromMap()
content = re.sub(
    r"hubDispatchUrl = \(raw\[\"hubDispatchUrl\"\] as\? String\) \?: defaults.hubDispatchUrl,",
    r"hubDispatchUrl = (raw[\"hubDispatchUrl\"] as? String) ?: defaults.hubDispatchUrl,\n        configPath = (raw[\"configPath\"] as? String) ?: defaults.configPath,",
    content
)

with open("app/src/main/kotlin/space/u2re/android/service/daemon/Settings.kt", "w") as f:
    f.write(content)
