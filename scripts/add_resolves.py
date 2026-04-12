import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re

files_to_update = [
    "app/src/main/kotlin/space/u2re/android/service/daemon/Daemon.kt",
    "app/src/main/kotlin/space/u2re/android/service/endpoint/AssistantNetworkBridge.kt",
    "app/src/main/kotlin/space/u2re/android/service/MainActivity.kt",
    "app/src/main/kotlin/space/u2re/android/service/QuickActionActivity.kt",
    "app/src/main/kotlin/space/u2re/android/service/boot/BootCompletedReceiver.kt",
    "app/src/main/kotlin/space/u2re/android/service/daemon/DaemonForegroundService.kt",
    "app/src/main/kotlin/space/u2re/android/service/agent/ConnectScreen.kt"
]

for file in files_to_update:
    if os.path.exists(file):
        with open(file, "r") as f:
            content = f.read()
        
        # We want to replace SettingsStore.load(X) with SettingsStore.load(X).resolve()
        # except when it is already resolved.
        # Regex to find SettingsStore.load(...) that is not followed by .resolve()
        # and replace it.
        # It's easier to just blindly replace and then deduplicate .resolve().resolve()
        new_content = re.sub(r"SettingsStore\.load\(([^)]+)\)", r"SettingsStore.load(\1).resolve()", content)
        new_content = new_content.replace(".resolve().resolve()", ".resolve()")
        
        # Need to make sure import space.u2re.cws.daemon.resolve exists
        if ".resolve()" in new_content and "import space.u2re.cws.daemon.resolve" not in new_content:
            new_content = new_content.replace("import space.u2re.cws.daemon.SettingsStore", "import space.u2re.cws.daemon.SettingsStore\nimport space.u2re.cws.daemon.resolve")
            
        with open(file, "w") as f:
            f.write(new_content)

print("Added resolve() calls")
