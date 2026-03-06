import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re

# Add missing imports for ReverseGatewayClient.kt
file = "app/src/main/kotlin/space/u2re/android/service/network/ReverseGatewayClient.kt"
with open(file, "r") as f:
    content = f.read()

imports_to_add = [
    "import space.u2re.cws.reverse.ReverseGatewayConfig",
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
    "import space.u2re.cws.network.LocalHttpServer",
    "import space.u2re.cws.network.HttpServerOptions",
    "import space.u2re.cws.network.TlsConfig",
    "import space.u2re.cws.network.normalizeHubDispatchUrl",
    "import space.u2re.cws.network.postJson",
    "import space.u2re.cws.network.postText",
    "import space.u2re.cws.network.normalizeDestinationHost",
    "import space.u2re.cws.network.normalizeDestinationUrl",
    "import space.u2re.cws.network.dispatchHttpRequests",
    "import space.u2re.cws.network.DispatchRequest",
    "import space.u2re.cws.network.DispatchResult",
]

for imp in imports_to_add:
    if imp not in content:
        content = content.replace("import space.u2re.cws.notifications.NotificationSpeaker", f"import space.u2re.cws.notifications.NotificationSpeaker\n{imp}")

with open(file, "w") as f:
    f.write(content)

# ResponsesAssistantScreen.kt
file = "app/src/main/kotlin/space/u2re/android/service/agent/ResponsesAssistantScreen.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.cws.network.normalizeResponsesEndpoint" not in content:
    content = content.replace("import space.u2re.cws.agent.sendResponsesRequest", "import space.u2re.cws.agent.sendResponsesRequest\nimport space.u2re.cws.network.normalizeResponsesEndpoint")
with open(file, "w") as f:
    f.write(content)

# ResponsesApi.kt
file = "app/src/main/kotlin/space/u2re/android/service/agent/ResponsesApi.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.cws.network.HttpResult" not in content:
    content = content.replace("package space.u2re.cws.agent", "package space.u2re.cws.agent\n\nimport space.u2re.cws.network.HttpResult\nimport space.u2re.cws.network.postJson")
with open(file, "w") as f:
    f.write(content)

# AssistantNetworkBridge.kt
file = "app/src/main/kotlin/space/u2re/android/service/endpoint/AssistantNetworkBridge.kt"
with open(file, "r") as f:
    content = f.read()
if "import space.u2re.cws.network.postJson" not in content:
    content = content.replace("import space.u2re.cws.daemon.SettingsStore", "import space.u2re.cws.daemon.SettingsStore\nimport space.u2re.cws.network.postJson\nimport space.u2re.cws.network.postText\nimport space.u2re.cws.reverse.ReverseGatewayConfig")
with open(file, "w") as f:
    f.write(content)

print("Imports added")
