#!/bin/bash
# Script to find and set JAVA_HOME for a compatible Java version
# Gradle 8.14.3 supports Java 11, 17, 21, but not Java 25

# Check common Java installation locations
JAVA_VERSIONS=(17 21 11)
JAVA_PATHS=(
  "/usr/lib/jvm/java-17-openjdk-amd64"
  "/usr/lib/jvm/java-21-openjdk-amd64"
  "/usr/lib/jvm/java-11-openjdk-amd64"
  "/usr/lib/jvm/java-17"
  "/usr/lib/jvm/java-21"
  "/usr/lib/jvm/java-11"
  "$HOME/.sdkman/candidates/java/17.0*"
  "$HOME/.sdkman/candidates/java/21.0*"
  "$HOME/.sdkman/candidates/java/11.0*"
)

for version in "${JAVA_VERSIONS[@]}"; do
  for base_path in /usr/lib/jvm/java-${version}*; do
    if [ -d "$base_path" ] && [ -f "$base_path/bin/java" ]; then
      java_ver=$("$base_path/bin/java" -version 2>&1 | head -1)
      if echo "$java_ver" | grep -q "version \"${version}"; then
        export JAVA_HOME="$base_path"
        echo "Found Java ${version} at: $JAVA_HOME"
        echo "Setting JAVA_HOME=$JAVA_HOME"
        "$base_path/bin/java" -version
        exit 0
      fi
    fi
  done
done

echo "ERROR: No compatible Java version (11, 17, or 21) found!"
echo "Please install Java 17 or 21 and set JAVA_HOME manually, or update gradle.properties"
exit 1
