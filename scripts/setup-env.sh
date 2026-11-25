#!/usr/bin/env bash
# Provisions a JDK 17 + Android SDK + Gradle 8.7 toolchain for building YTCache.
# Safe to re-run: every step is skipped when the artifact already exists.
set -euo pipefail

PROJ="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$HOME"
mkdir -p tools

echo "=== setup start $(date -Is) ==="

# ---------------------------------------------------------------- JDK 17
if [ -d "$HOME/tools/jdk17" ]; then
  export JAVA_HOME="$HOME/tools/jdk17"
elif command -v javac >/dev/null 2>&1; then
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else
  if [ "$(id -u)" = "0" ] || sudo -n true 2>/dev/null; then
    SUDO=""; [ "$(id -u)" = "0" ] || SUDO="sudo -n"
    export DEBIAN_FRONTEND=noninteractive
    $SUDO apt-get update -y -qq
    $SUDO apt-get install -y -qq openjdk-17-jdk-headless unzip curl
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  else
    echo "--- no root, downloading Temurin 17"
    curl -fL --retry 3 -o tools/jdk17.tar.gz \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p tools/jdk17
    tar xzf tools/jdk17.tar.gz -C tools/jdk17 --strip-components=1
    export JAVA_HOME="$HOME/tools/jdk17"
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# ------------------------------------------------------------ Android SDK
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "--- downloading cmdline-tools"
  curl -fL --retry 3 -o tools/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q -o tools/cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
fi
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
echo "--- installing SDK packages"
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --install \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

# ---------------------------------------------------------------- Gradle
if [ ! -x "$HOME/tools/gradle-8.7/bin/gradle" ]; then
  echo "--- downloading gradle 8.7"
  curl -fL --retry 3 -o tools/gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
  unzip -q -o tools/gradle.zip -d tools
fi
"$HOME/tools/gradle-8.7/bin/gradle" --version

# --------------------------------------------------------- project wiring
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$PROJ/local.properties"

# ------------------------------------------------------- release keystore
mkdir -p "$PROJ/keystore"
if [ ! -f "$PROJ/keystore/ytcache.jks" ]; then
  keytool -genkeypair -v -keystore "$PROJ/keystore/ytcache.jks" \
    -storepass ytcache -keypass ytcache -alias ytcache \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=YTCache, OU=Local Build, O=YTCache, L=Internet, S=, C=US" >/dev/null
fi

echo "=== setup done $(date -Is) ==="
