import os
import re

# Add missing imports for ReverseGatewayClient.kt
file = "app/src/main/kotlin/space/u2re/android/service/network/ReverseGatewayClient.kt"
with open(file, "r") as f:
    content = f.read()

imports_to_add = [
    "import space.u2re.service.reverse.ReverseGatewayConfig",
]

for imp in imports_to_add:
    if imp not in content:
        content = content.replace("import android.util.Log", f"import android.util.Log\n{imp}")

with open(file, "w") as f:
    f.write(content)

# Add missing imports for Daemon.kt
file = "app/src/main/kotlin/space/u2re/android/service/daemon/Daemon.kt"
with open(file, "r") as f:
    content = f.read()

imports_to_add = [
    "import space.u2re.service.network.LocalHttpServer",
    "import space.u2re.service.network.HttpServerOptions",
    "import space.u2re.service.network.TlsConfig",
    "import space.u2re.service.network.normalizeHubDispatchUrl",
    "import space.u2re.service.network.postJson",
    "import space.u2re.service.network.postText",
    "import space.u2re.service.network.normalizeDestinationHost",
    "import space.u2re.service.network.normalizeDestinationUrl",
    "import space.u2re.service.network.dispatchHttpRequests",
    "import space.u2re.service.network.DispatchRequest",
    "import space.u2re.service.network.DispatchResult",
]

for imp in imports_to_add:
    if imp not in content:
        content = content.replace("import space.u2re.service.notifications.NotificationSpeaker", f"import space.u2re.service.notifications.NotificationSpeaker\n{imp}")

with open(file, "w") as f:
    f.write(content)

# ResponsesAssistantScreen.kt
file = "app/src/main/kotlin/space/u2re/android/service/agent/ResponsesAssistantScreen.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.service.network.normalizeResponsesEndpoint" not in content:
    content = content.replace("import space.u2re.service.agent.sendResponsesRequest", "import space.u2re.service.agent.sendResponsesRequest\nimport space.u2re.service.network.normalizeResponsesEndpoint")
with open(file, "w") as f:
    f.write(content)

# ResponsesApi.kt
file = "app/src/main/kotlin/space/u2re/android/service/agent/ResponsesApi.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.service.network.HttpResult" not in content:
    content = content.replace("package space.u2re.service.agent", "package space.u2re.service.agent\n\nimport space.u2re.service.network.HttpResult\nimport space.u2re.service.network.postJson")
with open(file, "w") as f:
    f.write(content)

# AssistantNetworkBridge.kt
file = "app/src/main/kotlin/space/u2re/android/service/endpoint/AssistantNetworkBridge.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.service.network.postJson" not in content:
    content = content.replace("import space.u2re.service.daemon.SettingsStore", "import space.u2re.service.daemon.SettingsStore\nimport space.u2re.service.network.postJson\nimport space.u2re.service.network.postText\nimport space.u2re.service.reverse.ReverseGatewayConfig")
with open(file, "w") as f:
    f.write(content)

print("Imports added")
