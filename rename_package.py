import os
import re

directories = ['app/src', 'scripts', 'package.json']

for root_dir in directories:
    if os.path.isfile(root_dir):
        with open(root_dir, 'r') as f:
            content = f.read()
        new_content = content.replace('space.u2re.service', 'space.u2re.cws')
        if new_content != content:
            with open(root_dir, 'w') as f:
                f.write(new_content)
            print(f"Updated {root_dir}")
        continue
        
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith(('.kt', '.xml', '.kts', '.py', '.json', '.md')):
                filepath = os.path.join(root, file)
                try:
                    with open(filepath, 'r', encoding='utf-8') as f:
                        content = f.read()
                    
                    new_content = content.replace('space.u2re.service', 'space.u2re.cws')
                    
                    if new_content != content:
                        with open(filepath, 'w', encoding='utf-8') as f:
                            f.write(new_content)
                        print(f"Updated {filepath}")
                except Exception as e:
                    print(f"Error reading {filepath}: {e}")

