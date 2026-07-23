# assemble debug APK
build:
    ./gradlew assembleDebug

# signed release APK (needs release.keystore + .env)
build-release:
    #!/usr/bin/env bash
    if [ ! -f release.keystore ] || [ ! -f .env ]; then
      echo "No signing config found. Run: scripts/setup-signing.sh" >&2
      exit 1
    fi
    ./gradlew assembleRelease

# build + install + launch on connected device
install: build
    #!/usr/bin/env bash
    set -euo pipefail
    if ! command -v adb >/dev/null 2>&1; then
      echo "adb not found – run inside nix develop" >&2
      exit 1
    fi
    if ! adb get-state >/dev/null 2>&1; then
      echo "No device connected. Check: adb devices" >&2
      exit 1
    fi
    APK="app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK" ] || { echo "APK not found – run 'just build' first" >&2; exit 1; }
    DEVICE=$(adb get-serialno)
    echo "Installing $APK on $DEVICE..."
    adb install -r -d "$APK"
    echo "Launching com.skiletro.wheelwitch/.MainActivity..."
    adb shell am start -n com.skiletro.wheelwitch.debug/com.skiletro.wheelwitch.MainActivity

# run unit tests
test:
    ./gradlew testDebugUnitTest

# spotless + ktfmt auto-format
format:
    ./gradlew spotlessApply

# Android lint
lint:
    ./gradlew lint

# clean build outputs
clean:
    ./gradlew clean

# build + test
check: build test
