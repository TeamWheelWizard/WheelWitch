# assemble debug APK
build:
    ./gradlew assembleDebug

# signed release APK (needs release.keystore + .env)
build-release:
    #!/usr/bin/env bash
    if [ ! -f release.keystore ] || [ ! -f .env ]; then
      echo "No signing config found. Run: just setup-signing" >&2
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

# generate a keystore and .env for signing release APKs
setup-signing:
    #!/usr/bin/env bash
    set -euo pipefail

    echo "=== WheelWitch Release Signing Setup ==="
    echo ""
    echo "This generates a keystore for signing release APKs."
    echo "You can use it both for local signing and for GitHub Actions CI."
    echo ""

    read -r -p "Keystore password: " -s STORE_PASS
    echo ""
    read -r -p "Key alias [wheelwitch]: " KEY_ALIAS
    KEY_ALIAS=${KEY_ALIAS:-wheelwitch}
    read -r -p "Output path [./release.keystore]: " OUTPUT
    OUTPUT=${OUTPUT:-./release.keystore}
    read -r -p "Your name (CN): " CN
    read -r -p "Organizational unit (OU) [Development]: " OU
    OU=${OU:-Development}
    read -r -p "Organization (O) [WheelWitch]: " O
    O=${O:-WheelWitch}
    read -r -p "City/Locality (L): " L
    read -r -p "State (ST): " ST
    read -r -p "Country code (C) [US]: " C
    C=${C:-US}

    keytool -genkey -v \
      -keystore "$OUTPUT" \
      -storetype pkcs12 \
      -alias "$KEY_ALIAS" \
      -keyalg RSA \
      -keysize 4096 \
      -sigalg SHA512withRSA \
      -validity 10000 \
      -storepass "$STORE_PASS" \
      -keypass "$STORE_PASS" \
      -dname "CN=$CN, OU=$OU, O=$O, L=$L, ST=$ST, C=$C"

    echo ""
    echo "=== Keystore created at $OUTPUT ==="
    echo ""
    echo "Add these secrets to your GitHub repository (Settings > Secrets and variables > Actions):"
    echo ""
    echo "---"
    echo "Secret name: KEYSTORE_BASE64"
    echo "Value:"
    if command -v base64 &>/dev/null; then
      base64 -w0 "$OUTPUT"
      echo ""
    else
      echo "(run: base64 -w0 $OUTPUT)"
    fi
    echo "---"
    echo "Secret name: KEYSTORE_PASSWORD"
    echo "Value: $STORE_PASS"
    echo "---"
    echo "Secret name: KEY_ALIAS"
    echo "Value: $KEY_ALIAS"
    echo "---"
    echo "Secret name: KEY_PASSWORD"
    echo "Value: $STORE_PASS"
    echo "---"
    printf '%s\n' \
      "KEYSTORE_PATH=$OUTPUT" \
      "KEYSTORE_PASSWORD=$STORE_PASS" \
      "KEY_ALIAS=$KEY_ALIAS" \
      "KEY_PASSWORD=$STORE_PASS" > .env

    echo ""
    echo ".env file created — nix develop loads it automatically."
    echo ""
    echo "For release builds, run:  just build-release"
