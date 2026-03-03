import os
import re

network_dir = "app/src/main/kotlin/space/u2re/android/service/network"
network_files = [f for f in os.listdir(network_dir) if f.endswith('.kt')]

for nf in network_files:
    filepath = os.path.join(network_dir, nf)
    with open(filepath, 'r') as f:
        content = f.read()
    
    # Replace package
    content = re.sub(r'^package\s+space\.u2re\.service\.(daemon|reverse|endpoint)', 'package space.u2re.service.network', content, flags=re.MULTILINE)
    
    with open(filepath, 'w') as f:
        f.write(content)

print("Updated packages in network dir.")
