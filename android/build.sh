#!/usr/bin/env bash
# Builds app/build/LoRaSignalMap-<version>.apk without Gradle / Android Studio, using the
# Android build tools packaged by Debian/Ubuntu plus two jars from Maven Central.
#
#   sudo apt-get install aapt dalvik-exchange zipalign apksigner android-framework-res \
#        default-jdk zip unzip curl
#   ./build.sh            # build + sign
#
# Android Studio users can instead open this folder as a Gradle project (see build.gradle).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
SRC="$APP/src/main"
OUT="$APP/build"
DEPS="$HERE/.deps"
MAVEN="https://repo1.maven.org/maven2"

FRAMEWORK_RES=/usr/share/android-framework-res/framework-res.apk
ANDROID_ALL="$DEPS/android-all-14-robolectric-10818077.jar"
OSMDROID_AAR="$DEPS/osmdroid-android-6.1.20.aar"
KEYSTORE="$HERE/keystore/lorasignalmap.jks"
KS_PASS="lorasignalmap"
KS_ALIAS="lorasignalmap"
MIN_SDK=29
TARGET_SDK=34

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
log() { printf '\033[1;34m==> %s\033[0m\n' "$*"; }

fetch() { # url dest
  if [ ! -s "$2" ]; then
    log "downloading $(basename "$2")"
    mkdir -p "$(dirname "$2")"
    for i in 1 2 3 4 5; do
      if curl -fsSL --retry 3 -o "$2.part" "$1" && unzip -tq "$2.part" >/dev/null 2>&1; then
        mv "$2.part" "$2"; return 0
      fi
      sleep $((i * 5))
    done
    echo "failed to download $1" >&2; exit 1
  fi
}

for tool in aapt2 dalvik-exchange zipalign apksigner javac jar zip unzip; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
[ -f "$FRAMEWORK_RES" ] || { echo "missing $FRAMEWORK_RES (apt install android-framework-res)" >&2; exit 1; }

fetch "$MAVEN/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" "$ANDROID_ALL"
fetch "$MAVEN/org/osmdroid/osmdroid-android/6.1.20/osmdroid-android-6.1.20.aar" "$OSMDROID_AAR"

VERSION_NAME=$(sed -n 's/.*VERSION_NAME = "\(.*\)";/\1/p' "$SRC/java/com/tomilabs/lorasignalmap/BuildInfo.java")
VERSION_CODE=$(sed -n 's/.*VERSION_CODE = \([0-9]*\);/\1/p' "$SRC/java/com/tomilabs/lorasignalmap/BuildInfo.java")
log "LoRa Signal Map $VERSION_NAME ($VERSION_CODE)"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/osmdroid"
unzip -q "$OSMDROID_AAR" -d "$OUT/osmdroid"

log "compiling resources"
# the namespace lives in build.gradle (AGP 8 style); aapt2 needs it in the manifest
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tomilabs.lorasignalmap">|' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q 'package="com.tomilabs.lorasignalmap"' "$OUT/AndroidManifest.xml" || { echo "manifest patch failed" >&2; exit 1; }
aapt2 compile --dir "$OUT/osmdroid/res" -o "$OUT/osmdroid-res.zip"
aapt2 compile --dir "$SRC/res" -o "$OUT/app-res.zip"
aapt2 link -I "$FRAMEWORK_RES" \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --extra-packages org.osmdroid.library \
  --auto-add-overlay \
  --java "$OUT/gen" \
  -o "$OUT/resources.apk" \
  "$OUT/osmdroid-res.zip" "$OUT/app-res.zip"

log "compiling java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# java.* from the JDK's Java 8 API (Android 10+ supports all of it), android.* from android-all
javac -encoding UTF-8 -nowarn -Xlint:-options --release 8 \
  -cp "$ANDROID_ALL:$OUT/osmdroid/classes.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

log "dexing"
dalvik-exchange --dex --min-sdk-version="$MIN_SDK" --output="$OUT/classes.dex" \
  "$OUT/classes" "$OUT/osmdroid/classes.jar"

log "packaging"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  log "creating signing key $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 20000 \
    -dname "CN=LoRa Signal Map, O=tomilabs" >/dev/null
fi
APK="$OUT/LoRaSignalMap-$VERSION_NAME.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"
apksigner verify "$APK"
zipalign -c -p 4 "$APK"
log "built $APK ($(du -h "$APK" | cut -f1))"
