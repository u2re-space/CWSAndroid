import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re

directory = "app/src/main/kotlin/space/u2re"

imports_to_replace = [
    (r"import space\.u2re\.service\.daemon\.normalizeHubDispatchUrl", r"import space.u2re.service.network.normalizeHubDispatchUrl"),
    (r"import space\.u2re\.service\.daemon\.normalizeResponsesEndpoint", r"import space.u2re.service.network.normalizeResponsesEndpoint"),
    (r"import space\.u2re\.service\.daemon\.normalizeDestinationUrl", r"import space.u2re.service.network.normalizeDestinationUrl"),
    (r"import space\.u2re\.service\.daemon\.normalizeDestinationHost", r"import space.u2re.service.network.normalizeDestinationHost"),
    (r"import space\.u2re\.service\.daemon\.normalizeEndpointUrl", r"import space.u2re.service.network.normalizeEndpointUrl"),
    (r"import space\.u2re\.service\.daemon\.postJson", r"import space.u2re.service.network.postJson"),
    (r"import space\.u2re\.service\.daemon\.postText", r"import space.u2re.service.network.postText"),
    (r"import space\.u2re\.service\.daemon\.HttpResult", r"import space.u2re.service.network.HttpResult"),
    (r"import space\.u2re\.service\.daemon\.DaemonJson", r"import space.u2re.service.network.DaemonJson"),
    (r"import space\.u2re\.service\.daemon\.LocalHttpServer", r"import space.u2re.service.network.LocalHttpServer"),
    (r"import space\.u2re\.service\.daemon\.HttpServerOptions", r"import space.u2re.service.network.HttpServerOptions"),
    (r"import space\.u2re\.service\.daemon\.TlsConfig", r"import space.u2re.service.network.TlsConfig"),
    (r"import space\.u2re\.service\.daemon\.dispatchHttpRequests", r"import space.u2re.service.network.dispatchHttpRequests"),
    (r"import space\.u2re\.service\.daemon\.DispatchRequest", r"import space.u2re.service.network.DispatchRequest"),
    (r"import space\.u2re\.service\.daemon\.DispatchResult", r"import space.u2re.service.network.DispatchResult"),
    (r"import space\.u2re\.service\.reverse\.ReverseGatewayClient", r"import space.u2re.service.network.ReverseGatewayClient"),
    (r"import space\.u2re\.service\.reverse\.ReverseRelayCodec", r"import space.u2re.service.network.ReverseRelayCodec"),
    (r"import space\.u2re\.service\.endpoint\.SocketIoTunnelClient", r"import space.u2re.service.network.SocketIoTunnelClient"),
]

for root, _, files in os.walk(directory):
    for file in files:
        if file.endswith(".kt"):
            filepath = os.path.join(root, file)
            with open(filepath, "r") as f:
                content = f.read()
            
            new_content = content
            for old, new in imports_to_replace:
                new_content = re.sub(old, new, new_content)
            
            # Additional fixes where imports were missing before or assumed same package
            if "import space.u2re.service.daemon.DaemonLog" not in new_content and "DaemonLog" in new_content and "network" in filepath:
                new_content = re.sub(r"import (.*?)\n\n", r"import \1\nimport space.u2re.service.daemon.DaemonLog\n\n", new_content, count=1)
            if "import space.u2re.service.daemon.PermissionManager" not in new_content and "PermissionManager" in new_content and "network" in filepath:
                new_content = re.sub(r"import (.*?)\n\n", r"import \1\nimport space.u2re.service.daemon.PermissionManager\n\n", new_content, count=1)
            
            if new_content != content:
                with open(filepath, "w") as f:
                    f.write(new_content)

print("Imports updated")
