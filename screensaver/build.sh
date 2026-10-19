#!/bin/sh
# Builds out/tv-screensaver.apk. Needs Java 17 and the Android SDK (platform 34, build-tools 36).
# Reuses the repo's key.jks. On Windows use:  .\build.ps1
set -e
cd "$(dirname "$0")"
ROOT=$(pwd -P)
REPO=$(dirname "$ROOT")

[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK=$d && break; done
BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -d "$BT" ] || { echo "Android SDK not found. Set ANDROID_HOME, and install platforms;android-34 and build-tools;36.0.0." >&2; exit 1; }

rm -rf out; mkdir -p out/classes out/dex

"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 28 out/res.zip
javac --release 8 -classpath "$JAR" -d out/classes $(find src -name "*.java")
"$BT/d8" --min-api 21 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

if [ -f "$REPO/keystore.env" ]; then . "$REPO/keystore.env"; fi
KS=${KEYSTORE:-$REPO/key.jks}; PASS=${KEYSTORE_PASS:-$TVHOME_KEY_PASS}
[ -n "$PASS" ] || { echo "Set KEYSTORE_PASS or TVHOME_KEY_PASS (or create keystore.env in the repo root)." >&2; exit 1; }
[ -f "$KS" ] || { echo "Keystore not found: $KS. Run ../build.sh once to create it, or set KEYSTORE." >&2; exit 1; }
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --ks-key-alias "${KEY_ALIAS:-tvhome}" \
  --out out/tv-screensaver.apk out/aligned.apk
echo "Built out/tv-screensaver.apk"
