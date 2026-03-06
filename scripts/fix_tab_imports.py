import os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import re

file = "app/src/main/kotlin/space/u2re/android/service/screen/SettingsTabContent.kt"
with open(file, "r") as f:
    content = f.read()

imports = """
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
"""
content = content.replace("import androidx.compose.animation.AnimatedVisibility", "import androidx.compose.animation.AnimatedVisibility\n" + imports)

with open(file, "w") as f:
    f.write(content)

print("Fixed SettingsTabContent imports")
